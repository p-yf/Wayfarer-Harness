package com.yf.wayfarer.rsi;

import com.yf.wayfarer.model.ChatModel;
import com.yf.wayfarer.rsi.model.MemoryItem;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.SessionMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RSI 沉淀总编排（分层触发模型：L1 阈值提取、L2 场景萃取、L3 画像五级触发、
 * skill 轮次累计，各层独立解耦）。
 *
 * <p>触发链（各层独立解耦，不再是一次 gate 压扁跑完）：</p>
 * <ol>
 *   <li><b>L1</b>：每次 run 结束后由 {@link #onRunFinished} 记账；某会话待处理消息数
 *       ≥ 暖启动阈值（1 起翻倍封顶）时触发提取；<b>idle 兜底</b>由周期巡检触发
 *       （缓冲非空且超 l1-idle-seconds 没活动）；</li>
 *   <li><b>L2</b>：L1 落盘成功后延迟 l2-delay-seconds 调度执行，只处理游标之后的
 *       新增 L1 记录，无新增直接跳过；</li>
 *   <li><b>L3</b>：五级 {@link PersonaTrigger} 判定（P1 信号 / P2 冷启动 / P2.5 恢复 /
 *       P3 首场景 / P4 阈值），在每轮 L1、L2 完成后评估；</li>
 *   <li><b>skill</b>：只记「最终回复轮」（一个 run 即一个 round），累计达
 *       skill-archive-every-n-rounds 才萃取一次，输入是这几轮的对话切片。</li>
 * </ol>
 *
 * <p>线程模型：有界线程池（pool-size / queue-capacity），队列满直接丢弃并 warn；
 * 同一 agent 用 ReentrantLock 串行（触发判定也入锁，保证游标读改写的原子性）。
 * 全链路 try-catch，任何异常只 warn——RSI 挂掉 ≠ 会话挂掉。</p>
 *
 * @author yyf
 * @description onRunFinished()：run 结束记账（skill 轮次 + L1 阈值判定）；
 * runL1Locked()：窗口提取 + 游标前移 + 阈值翻倍 + 调度延迟 L2；
 * runL2Locked()：场景萃取 + personaRequest 信号 + L3 评估；
 * evaluatePersonaLocked()：五级触发判定与画像蒸馏；runSkillLocked()：累计轮数萃取
 */
@Service
public class RsiSedimentPipeline {

    private static final Logger log = LoggerFactory.getLogger(RsiSedimentPipeline.class);

    /** l1.jsonl 的序列化器（每行一条 JSON）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** L3 画像文件名。 */
    public static final String PERSONA_FILE = "persona.md";

    private final Session session;
    private final RsiPaths paths;
    private final RsiGitService git;
    private final RsiCursorStore cursors;
    private final RsiProperties properties;
    private final MemoryExtractor extractor;
    private final SceneExtractor scenes;
    private final SceneIndex sceneIndex;
    private final ProfileDistiller distiller;
    private final SkillReviewer reviewer;
    private final MemorySearcher searcher;
    private final RsiPipelineTriggers triggers;

    /** 有界沉淀线程池：LLM 调用秒级~分钟级，2 线程 + 32 队列默认够用。 */
    private final ThreadPoolExecutor executor;

    /** per-agent 串行锁：同一 agent 的沉淀任务排队执行。 */
    private final Map<String, ReentrantLock> agentLocks = new ConcurrentHashMap<>();

    /** per-agent 最近一次可用的模型引用：idle 兜底与延迟 L2 任务需要模型但没有上下文。 */
    private final Map<String, ChatModel> lastModels = new ConcurrentHashMap<>();

    /** (agent|sessionId) → 最近活动时间戳：idle 兜底判定用（内存态，重启丢失无害）。 */
    private final Map<String, Long> lastActivity = new ConcurrentHashMap<>();

    public RsiSedimentPipeline(Session session, RsiPaths paths, RsiGitService git,
                               RsiCursorStore cursors, RsiProperties properties,
                               MemoryExtractor extractor, SceneExtractor scenes,
                               SceneIndex sceneIndex, ProfileDistiller distiller,
                               SkillReviewer reviewer, MemorySearcher searcher,
                               RsiPipelineTriggers triggers) {
        this.session = session;
        this.paths = paths;
        this.git = git;
        this.cursors = cursors;
        this.properties = properties;
        this.extractor = extractor;
        this.scenes = scenes;
        this.sceneIndex = sceneIndex;
        this.distiller = distiller;
        this.reviewer = reviewer;
        this.searcher = searcher;
        this.triggers = triggers;
        this.executor = new ThreadPoolExecutor(
                properties.poolSize(), properties.poolSize(), 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                r -> {
                    Thread thread = new Thread(r, "rsi-sediment");
                    thread.setDaemon(true);
                    return thread;
                },
                // 拒绝策略：丢弃并 warn（宁缺毋滥）
                (r, e) -> log.warn("RSI 沉淀队列已满，丢弃一次沉淀任务"));
    }

    /**
     * 启动 idle 兜底巡检：周期 = max(5s, idleSeconds/2)，缓冲非空且超时即强制触发 L1。
     */
    @PostConstruct
    public void start() {
        long period = Math.max(5, properties.l1IdleSeconds() / 2);
        triggers.schedulePeriodically(this::idleSweep, period);
    }

    /**
     * 每次运行结束的记账入口（RsiSedimentHandler 后置调用，异步执行不拖住会话收尾）。
     */
    public void onRunFinished(String agent, String sessionId, String traceId, ChatModel model) {
        if (!properties.enabled() || agent == null || sessionId == null) {
            return;
        }
        lastModels.put(agent, model);
        lastActivity.put(key(agent, sessionId), System.currentTimeMillis());
        submit(() -> {
            ReentrantLock lock = lockOf(agent);
            lock.lock();
            try {
                tickLocked(agent, sessionId, traceId, model);
            } finally {
                lock.unlock();
            }
        });
    }

    /**
     * 单次记账：skill 最终回复轮登记 + L1 阈值判定与执行。
     */
    private void tickLocked(String agent, String sessionId, String traceId, ChatModel model) {
        RsiCursorStore.Cursor cursor = cursors.get(agent);
        boolean dirty = false;
        // ---- skill：一个 run 即一个 round，含 assistant 回复才算最终回复轮 ----
        if (traceId != null && hasFinalReply(sessionId, traceId)) {
            cursor.addSkillRound(new RsiCursorStore.Round(sessionId, traceId));
            dirty = true;
        }
        if (cursor.getSkillRounds().size() >= properties.skillArchiveEveryNRounds()) {
            try {
                runSkillLocked(agent, cursor, model);
            } catch (Exception e) {
                log.warn("RSI skill 萃取失败 agent={}", agent, e);
            }
            dirty = true;
        }
        // ---- L1：待处理消息数 ≥ 暖启动阈值即触发 ----
        List<SessionMessage> conversation = conversation(sessionId);
        RsiCursorStore.SessionCursor sessionCursor = cursor.session(sessionId);
        int from = Math.min(sessionCursor.getL1Cursor(), conversation.size());
        int pending = conversation.size() - from;
        int threshold = cursor.getL1Threshold() <= 0
                ? triggers.initialThreshold() : cursor.getL1Threshold();
        if (triggers.shouldFireL1(pending, threshold)) {
            runL1Locked(agent, sessionId, traceId, model, cursor, sessionCursor,
                    conversation, from, threshold);
            dirty = true;
        }
        if (dirty) {
            cursors.save(agent, cursor);
        }
    }

    /**
     * L1 提取执行：窗口切分 → 两阶段判重提取 → 重写 l1.jsonl → 游标前移 + 阈值翻倍
     * → 延迟调度 L2 → L3 评估。
     */
    private void runL1Locked(String agent, String sessionId, String traceId, ChatModel model,
                             RsiCursorStore.Cursor cursor, RsiCursorStore.SessionCursor sessionCursor,
                             List<SessionMessage> conversation, int from, int threshold) {
        RsiPipelineTriggers.Window window = triggers.sliceWindow(conversation, from);
        try {
            List<MemoryItem> existing = readL1(agent);
            MemoryExtractor.ExtractionResult result =
                    extractor.extract(model, window, existing, agent, sessionId);
            writeL1(agent, result.sorted());
            cursor.setMemoriesSincePersona(cursor.getMemoriesSincePersona() + result.changed());
            sessionCursor.setL1Cursor(window.newCursor());
            // 暖启动：本次触发完成，阈值翻倍封顶
            cursor.setL1Threshold(triggers.nextThreshold(threshold));
            commit(agent, RsiPaths.KIND_MEMORY,
                    "memory(l1): +" + result.stored() + " ~" + result.updated()
                            + " merge " + result.merged() + "（session " + sessionId + "）",
                    sessionId, traceId, "l1");
            log.info("RSI L1 沉淀完成 agent={} session={} store={} update={} merge={} skip={} 下次阈值={}",
                    agent, sessionId, result.stored(), result.updated(), result.merged(),
                    result.skipped(), cursor.getL1Threshold());
        } catch (Exception e) {
            // 提取失败：游标不动，下次触发重读本窗口（四决策幂等，重提取只得 skip）
            log.warn("RSI L1 提取失败 agent={} session={}", agent, sessionId, e);
            return;
        }
        // L2 延迟调度：L1 落盘成功后延迟 l2-delay-seconds 执行场景萃取
        triggers.schedule(() -> submitL2(agent), properties.l2DelaySeconds());
        // L3 评估（P2.5 恢复 / P4 阈值等可能在此命中）
        submitL3(agent);
    }

    /**
     * 延迟执行的 L2 任务。
     */
    private void submitL2(String agent) {
        submit(() -> {
            ReentrantLock lock = lockOf(agent);
            lock.lock();
            try {
                runL2Locked(agent, lastModels.get(agent));
            } finally {
                lock.unlock();
            }
        });
    }

    /**
     * L2 场景萃取执行：只处理上次 L2 之后新增的 L1 记录；无新增直接跳过。
     */
    private void runL2Locked(String agent, ChatModel model) {
        if (model == null) {
            return;
        }
        RsiCursorStore.Cursor cursor = cursors.get(agent);
        List<MemoryItem> all = readL1(agent);
        int consumed = Math.min(cursor.getL2ConsumedCount(), all.size());
        if (consumed >= all.size()) {
            log.info("RSI L2 无新增记忆，跳过 agent={}", agent);
            return;
        }
        List<MemoryItem> newL1 = all.subList(consumed, all.size());
        List<SceneExtractor.SceneRef> refs = sceneIndex.refs(agent);
        try {
            // 场景整理交给带工具的 agent 自主完成（UPDATE/CREATE/MERGE/DELETE）
            SceneExtractor.Outcome outcome = scenes.organize(model, agent, newL1, refs, refs.size());
            cursor.setL2ConsumedCount(all.size());
            if (outcome.personaRequest()) {
                // [PERSONA_UPDATE_REQUEST] 信号 → 写入游标，PersonaTrigger 的 P1 路径
                cursor.setPersonaRequest(true);
            }
            cursors.save(agent, cursor);
            commit(agent, RsiPaths.KIND_MEMORY,
                    "memory(l2): 场景萃取 +" + outcome.created() + " ~" + outcome.updated()
                            + " merge " + outcome.merged() + " -" + outcome.deleted(),
                    null, null, "l2");
            log.info("RSI L2 场景萃取完成 agent={} create={} update={} merge={} delete={} personaRequest={}",
                    agent, outcome.created(), outcome.updated(), outcome.merged(),
                    outcome.deleted(), outcome.personaRequest());
        } catch (Exception e) {
            // L2 失败：游标不动，下轮 L1 后重试（新增记录仍在窗口内）
            log.warn("RSI L2 场景萃取失败 agent={}", agent, e);
            return;
        }
        submitL3(agent);
    }

    /**
     * 异步执行的 L3 评估任务。
     */
    private void submitL3(String agent) {
        submit(() -> {
            ReentrantLock lock = lockOf(agent);
            lock.lock();
            try {
                evaluatePersonaLocked(agent, lastModels.get(agent));
            } finally {
                lock.unlock();
            }
        });
    }

    /**
     * L3 五级触发评估与画像蒸馏（对齐 persona-trigger.ts 的判定链）。
     */
    private void evaluatePersonaLocked(String agent, ChatModel model) {
        if (model == null) {
            return;
        }
        RsiCursorStore.Cursor cursor = cursors.get(agent);
        Path personaFile = paths.memoryDir(agent).resolve(PERSONA_FILE);
        boolean personaFileExists = Files.isRegularFile(personaFile);
        String personaBody = personaFileExists ? readStoreFile(personaFile) : "";
        List<MemoryItem> all = readL1(agent);
        int sceneCount = sceneIndex.loadOrRebuild(agent).size();
        PersonaTrigger.Decision decision = PersonaTrigger.evaluate(new PersonaTrigger.State(
                cursor.isPersonaRequest(),
                sceneCount > 0,
                cursor.isPersonaEverGenerated(),
                personaFileExists,
                personaFileExists && personaBody.isBlank(),
                sceneCount,
                all.size(),
                cursor.getMemoriesSincePersona(),
                properties.personaTriggerEveryN()));
        if (!decision.triggered()) {
            return;
        }
        try {
            int delta = cursor.getMemoriesSincePersona();
            List<MemoryItem> deltaL1 = delta <= 0 || delta >= all.size()
                    ? all : all.subList(all.size() - delta, all.size());
            List<String> deltaScenes = sceneRefsAsLines(agent);
            String distilled = distiller.distill(model,
                    personaBody == null ? "" : personaBody, deltaL1, deltaScenes);
            Files.createDirectories(personaFile.getParent());
            Files.writeString(personaFile, distilled, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            cursor.setMemoriesSincePersona(0);
            cursor.setPersonaRequest(false);
            cursor.setPersonaEverGenerated(true);
            cursors.save(agent, cursor);
            commit(agent, RsiPaths.KIND_MEMORY,
                    "memory(l3): 画像重生（" + decision.level() + "，基于 " + delta + " 条增量）",
                    null, null, "l3");
            log.info("RSI L3 画像更新完成 agent={} 级别={} 增量={}", agent, decision.level(), delta);
        } catch (Exception e) {
            // 蒸馏失败保留计数与信号，下轮达标时重试
            log.warn("RSI L3 蒸馏失败 agent={}", agent, e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* skill 沉淀支路（round-level 记账 + 累计轮数阈值）                          */
    /* ------------------------------------------------------------------ */

    /**
     * skill 萃取执行：把累计的最终回复轮切片合并成一份输入，跑一次技能评审 agent；
     * 完成后清空轮次。agent 通过工具自主落库（create/update/patch），偏好事实经
     * Outcome 转投 L1；两项都无即视为无可沉淀经验。
     */
    private void runSkillLocked(String agent, RsiCursorStore.Cursor cursor, ChatModel model) {
        List<RsiCursorStore.Round> rounds = cursor.getSkillRounds();
        if (rounds.isEmpty() || model == null) {
            return;
        }
        // 汇总各轮对话切片（同一会话的多轮直接顺序拼接）
        StringBuilder traceText = new StringBuilder();
        String firstSessionId = null;
        String firstTraceId = null;
        for (RsiCursorStore.Round round : rounds) {
            if (firstSessionId == null) {
                firstSessionId = round.getSessionId();
                firstTraceId = round.getTraceId();
            }
            traceText.append(renderTrace(traceMessages(round.getSessionId(), round.getTraceId())));
        }
        List<SkillReviewer.SkillSummary> skills = listStoreSkills(agent);
        SkillReviewer.Outcome outcome = reviewer.review(model, agent, firstSessionId,
                traceText.toString(), skills, false, skills.size());
        cursor.clearSkillRounds();
        if (!outcome.toMemory().isEmpty()) {
            // Preference 不落 skill，转投 L1（本地精确去重后的轻量落盘）
            MemoryExtractor.ExtractionResult merged =
                    extractor.applyPreferences(readL1(agent), outcome.toMemory(), firstSessionId);
            writeL1(agent, merged.sorted());
            cursor.setMemoriesSincePersona(cursor.getMemoriesSincePersona() + merged.changed());
            commit(agent, RsiPaths.KIND_MEMORY,
                    "memory(l1): +preference " + merged.stored() + " ~" + merged.updated()
                            + "（from skill review, session " + firstSessionId + "）",
                    firstSessionId, firstTraceId, "l1");
        }
        if (outcome.created() + outcome.updated() + outcome.patched() > 0) {
            commit(agent, RsiPaths.KIND_SKILL,
                    "skill: +" + outcome.created() + " ~" + outcome.updated() + " ^" + outcome.patched()
                            + "（review agent，累计 " + rounds.size() + " 轮，session " + firstSessionId + "）",
                    firstSessionId, firstTraceId, "skill");
        } else if (outcome.toMemory().isEmpty()) {
            log.info("RSI skill review agent：累计 {} 轮无可沉淀经验 agent={}", rounds.size(), agent);
        }
    }

    /* ------------------------------------------------------------------ */
    /* idle 兜底巡检                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * idle 兜底：逐 agent 检查各会话——缓冲非空且距上次活动超阈值即强制触发一轮 L1
     * （低频用户不被饿死的 idle 兜底语义）。
     */
    void idleSweep() {
        if (!properties.enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String agent : lastModels.keySet()) {
            RsiCursorStore.Cursor cursor;
            try {
                cursor = cursors.get(agent);
            } catch (RuntimeException e) {
                continue;
            }
            for (String sessionId : cursor.getSessions().keySet()) {
                Long last = lastActivity.get(key(agent, sessionId));
                if (last == null) {
                    continue;
                }
                List<SessionMessage> conversation = conversation(sessionId);
                int from = Math.min(cursor.session(sessionId).getL1Cursor(), conversation.size());
                int pending = conversation.size() - from;
                if (triggers.shouldIdleFire(pending, now - last)) {
                    log.info("RSI idle 兜底触发 agent={} session={} pending={}", agent, sessionId, pending);
                    lastActivity.put(key(agent, sessionId), now);
                    String traceId = null;
                    ChatModel model = lastModels.get(agent);
                    submit(() -> {
                        ReentrantLock lock = lockOf(agent);
                        lock.lock();
                        try {
                            tickLocked(agent, sessionId, traceId, model);
                        } finally {
                            lock.unlock();
                        }
                    });
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 基础设施                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * 提交异步沉淀任务（拒绝即丢弃，宁缺毋滥）。
     */
    private void submit(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    // 故障隔离：沉淀全链路异常只 warn，绝不外抛
                    log.warn("RSI 沉淀任务失败", e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("RSI 沉淀任务被拒绝（队列满）");
        }
    }

    private ReentrantLock lockOf(String agent) {
        return agentLocks.computeIfAbsent(agent, key -> new ReentrantLock());
    }

    /**
     * 触发器调度线程的异常兜底（RsiPipelineTriggers.wrap 调用）。
     */
    static void logTriggerError(RuntimeException e) {
        log.warn("RSI 触发器调度任务异常", e);
    }

    private String key(String agent, String sessionId) {
        return agent + "|" + sessionId;
    }

    /**
     * 本 trace 是否含 assistant 回复（最终回复轮的判定口径：有回复才算一轮）。
     */
    private boolean hasFinalReply(String sessionId, String traceId) {
        return !traceMessages(sessionId, traceId).isEmpty();
    }

    /**
     * 某次 trace 的 root 消息切片（子 Agent 过程不计入）。
     */
    private List<SessionMessage> traceMessages(String sessionId, String traceId) {
        return session.messages(sessionId).stream()
                .filter(m -> traceId != null && traceId.equals(m.traceId()) && m.origin() == null)
                .toList();
    }

    /**
     * root 对话消息（L0 读取源；子 Agent 过程不参与沉淀，与模型历史口径一致）。
     */
    private List<SessionMessage> conversation(String sessionId) {
        return session.messages(sessionId).stream()
                .filter(m -> m.origin() == null)
                .filter(m -> m.type() == JSONMsgType.USER || m.type() == JSONMsgType.ASSISTANT
                        || m.type() == JSONMsgType.TOOL)
                .toList();
    }

    /**
     * 读取 l1.jsonl 全量（文件不存在视为空）。
     */
    List<MemoryItem> readL1(String agent) {
        Path file = paths.memoryDir(agent).resolve("l1.jsonl");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<MemoryItem> items = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    items.add(RsiLlm.parse(line, MemoryItem.class));
                } catch (RuntimeException e) {
                    log.warn("RSI l1.jsonl 存在损坏行，跳过：{}", line, e);
                }
            }
            return items;
        } catch (IOException e) {
            throw new UncheckedIOException("读取 l1.jsonl 失败", e);
        }
    }

    /**
     * 整体重写 l1.jsonl（重写式更新；diff 大小的取舍见规格待决 1）。
     */
    private void writeL1(String agent, List<MemoryItem> items) {
        Path file = paths.memoryDir(agent).resolve("l1.jsonl");
        try {
            Files.createDirectories(file.getParent());
            StringBuilder jsonl = new StringBuilder();
            for (MemoryItem item : items) {
                jsonl.append(JSON_MAPPER.writeValueAsString(item)).append('\n');
            }
            Files.writeString(file, jsonl.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("写入 l1.jsonl 失败", e);
        }
    }

    /**
     * 显式沉淀（memory_save 工具入口）：用户明确要求记住的信息直接写入 L1。
     *
     * <p>「轻工具 + 重管线」分工：工具只做显式落盘（类型校验 + 限长 + 规范格式），
     * 不做提取——提取仍归管线；撞车不拦截（宁重不丢），词面召回的近似条目作为提示
     * 返回给模型自行判断，最终由后续管线的四决策判重兜底。新记录自然落在 L2 游标
     * 之后，后续场景萃取照常覆盖。</p>
     *
     * @return 给模型的工具结果文本（含新记忆 id 与近似既有条目提示）
     */
    public String saveL1Direct(String agent, String type, String content, String tagsText,
                               String sessionId, String traceId) {
        String normalized = MemoryExtractor.normalizeType(type);
        if (normalized == null) {
            return "记忆类型非法：" + type
                    + "，允许：persona / episodic / instruction / work_fact / work_task / work_method / work_artifact";
        }
        if (content == null || content.isBlank()) {
            return "内容为空，未保存。";
        }
        String clipped = content.strip();
        if (clipped.length() > MAX_DIRECT_CONTENT_LENGTH) {
            clipped = clipped.substring(0, MAX_DIRECT_CONTENT_LENGTH);
        }
        ReentrantLock lock = lockOf(agent);
        lock.lock();
        try {
            List<MemoryItem> existing = readL1(agent);
            // 词面召回近似既有条目：只提示不拦截（宁重不丢，管线判重兜底）
            List<MemorySearcher.Hit> near =
                    searcher.search(agent, clipped, "l1", properties.conflictRecallTopK());
            String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            // 显式要求 = 用户意图背书，score 给高值（跳过质量门语义）
            MemoryItem item = MemoryItem.of(MemoryExtractor.nextIdValue(existing), normalized,
                    clipped, parseTags(tagsText), 9, null, now,
                    sessionId == null || sessionId.isBlank() ? "tool" : sessionId);
            List<MemoryItem> all = new ArrayList<>(existing);
            all.add(item);
            writeL1(agent, all);
            // 计入 L3 P4 阈值记账；L2 游标不动——新行自然落在已消费数之后，场景萃取照常覆盖
            RsiCursorStore.Cursor cursor = cursors.get(agent);
            cursor.setMemoriesSincePersona(cursor.getMemoriesSincePersona() + 1);
            cursors.save(agent, cursor);
            try {
                commit(agent, RsiPaths.KIND_MEMORY, "memory(l1): +1（tool 显式沉淀）",
                        sessionId, traceId, "l1");
            } catch (Exception e) {
                // 文件已落盘，commit 失败只影响回溯不影响正确性
                log.warn("RSI 显式沉淀 commit 失败（文件已落盘）agent={}", agent, e);
            }
            log.info("RSI 显式沉淀完成 agent={} id={} type={}", agent, item.id(), normalized);
            StringBuilder reply = new StringBuilder("已保存：")
                    .append(item.id()).append(" [").append(normalized).append("] ").append(clipped);
            if (!near.isEmpty()) {
                reply.append("\n注意：库中已有近似记忆（后续判重可能合并）：");
                for (MemorySearcher.Hit hit : near) {
                    reply.append('\n').append(hit.id() == null ? "?" : hit.id())
                            .append(' ').append(hit.content());
                }
            }
            return reply.toString();
        } catch (Exception e) {
            log.warn("RSI 显式沉淀失败 agent={}", agent, e);
            return "保存失败：" + e.getMessage();
        } finally {
            lock.unlock();
        }
    }

    /** 显式沉淀的单条内容上限：防工具沦为垃圾倾倒口。 */
    private static final int MAX_DIRECT_CONTENT_LENGTH = 2000;

    /**
     * 标签文本解析：逗号/顿号分隔，去空白去重，最多 8 个。
     */
    private static List<String> parseTags(String tagsText) {
        if (tagsText == null || tagsText.isBlank()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String tag : tagsText.split("[,，、]")) {
            String cleaned = tag.strip();
            if (!cleaned.isEmpty() && !tags.contains(cleaned)) {
                tags.add(cleaned);
            }
            if (tags.size() >= 8) {
                break;
            }
        }
        return List.copyOf(tags);
    }

    /**
     * 场景引用渲染为「场景名：summary」行（L3 蒸馏的场景侧输入）。
     */
    private List<String> sceneRefsAsLines(String agent) {
        List<String> lines = new ArrayList<>();
        for (SceneExtractor.SceneRef ref : sceneIndex.refs(agent)) {
            lines.add(ref.name() + "：" + ref.summary());
        }
        return lines;
    }

    /**
     * 列出 store 内技能摘要（name/type/description/version），Review 的 capture-first 基准。
     */
    private List<SkillReviewer.SkillSummary> listStoreSkills(String agent) {
        Path root = paths.skillsDir(agent);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<SkillReviewer.SkillSummary> skills = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path dir : stream.filter(Files::isDirectory).sorted().toList()) {
                Path skillFile = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(skillFile)) {
                    continue;
                }
                String content = readStoreFile(skillFile);
                Map<String, Object> front = com.yf.wayfarer.skill.SkillStore.frontmatter(content);
                skills.add(new SkillReviewer.SkillSummary(
                        dir.getFileName().toString(),
                        String.valueOf(front.getOrDefault("type", "sop")),
                        String.valueOf(front.getOrDefault("description", "")),
                        SkillReviewer.versionOf(content)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描 store 技能失败：" + root, e);
        }
        return skills;
    }

    private String readStoreFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败：" + file, e);
        }
    }

    /**
     * trace 渲染：角色 + 内容（截断），供 Review 使用。
     */
    private static String renderTrace(List<SessionMessage> messages) {
        StringBuilder text = new StringBuilder();
        for (SessionMessage message : messages) {
            String content = String.valueOf(message.content().getOrDefault("content", ""));
            String name = String.valueOf(message.content().getOrDefault("name", ""));
            text.append('[').append(message.type().name());
            if (!name.isEmpty() && !"null".equals(name)) {
                text.append(' ').append(name);
            }
            text.append("] ").append(clip(content, 600)).append('\n');
        }
        return text.toString();
    }

    private static String clip(String text, int limit) {
        String value = text == null ? "" : text.replace('\n', ' ').strip();
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    /**
     * 提交沉淀 commit（trailer 结构见 RsiGitService）。
     */
    private void commit(String agent, String kind, String summary,
                        String sessionId, String traceId, String layer) {
        Map<String, String> trailers = new LinkedHashMap<>();
        trailers.put(RsiGitService.TRAILER_KIND, kind);
        trailers.put(RsiGitService.TRAILER_AGENT, agent);
        if (sessionId != null) {
            trailers.put(RsiGitService.TRAILER_SOURCE_SESSION, sessionId);
        }
        if (traceId != null) {
            trailers.put(RsiGitService.TRAILER_TRACE_ID, traceId);
        }
        trailers.put(RsiGitService.TRAILER_LAYER, layer);
        git.commit(agent, kind, summary, trailers);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
