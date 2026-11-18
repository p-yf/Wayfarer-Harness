package com.yf.wayfarer.rsi;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RSI 游标簿记（「记录各层读到哪了」的消费位点状态）。
 *
 * <p>落盘 {@code .wayfarer/rsi/state/<agent>/cursor.json}，<b>不入 git</b>——
 * 游标是纯簿记状态而不是沉淀产物，reset 回溯不应回滚游标。</p>
 *
 * <p>崩溃恢复与幂等：若「落盘成功但游标未写」（两步之间崩溃），下次会重读一小段窗口——
 * L1 的 LLM 判重四决策（store/update/merge/skip）天然幂等，重复提取只会得到 skip。
 * 游标是性能优化，正确性由去重保证，因此允许滞后写、无需事务。</p>
 *
 * @author yyf
 * @description 每个 agent 一份游标：按 session 记录 l1Cursor（L1 已消费到的消息序号）；
 * agent 级记录暖启动阈值 l1Threshold、画像计数 memoriesSincePersona、P1 信号
 * personaRequest、画像是否生成过 personaEverGenerated、L2 已消费 L1 条数
 * l2ConsumedCount、待萃取的 skill 轮次 skillRounds；写入用「临时文件 + 原子移动」
 */
@Service
public class RsiCursorStore {

    private static final Logger log = LoggerFactory.getLogger(RsiCursorStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            // 旧版本游标文件多了字段不要报错，向后兼容
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RsiPaths paths;

    public RsiCursorStore(RsiPaths paths) {
        this.paths = paths;
    }

    /**
     * 读取指定 agent 的游标；文件不存在或损坏时返回全新游标（首次运行 / 损坏兜底）。
     */
    public Cursor get(String agent) {
        Path file = paths.stateDir(agent).resolve("cursor.json");
        if (!Files.isRegularFile(file)) {
            return new Cursor();
        }
        try {
            Cursor cursor = MAPPER.readValue(file.toFile(), Cursor.class);
            return cursor == null ? new Cursor() : cursor;
        } catch (IOException e) {
            // 损坏的游标当作不存在处理：L1 去重四决策保证重读不产生脏数据
            log.warn("RSI 游标文件读取失败，按全新游标处理 agent={} file={}", agent, file, e);
            return new Cursor();
        }
    }

    /**
     * 保存指定 agent 的游标：先写临时文件再原子移动，避免进程中断留下半截 JSON。
     */
    public void save(String agent, Cursor cursor) {
        Path directory = paths.stateDir(agent);
        Path file = directory.resolve("cursor.json");
        Path temp = directory.resolve("cursor.json.tmp");
        try {
            Files.createDirectories(directory);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), cursor);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("保存 RSI 游标失败：" + file, e);
        }
    }

    /**
     * 游标数据结构（JSON 序列化形态）。
     *
     * @author yyf
     * @description sessions 按会话记录 L1 消费位置；agent 级字段承载触发链状态：
     * 暖启动阈值、画像触发计数/信号、L2 消费进度与 skill 待萃取轮次
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Cursor {

        /** sessionId -> 该会话的消费状态。 */
        private Map<String, SessionCursor> sessions = new LinkedHashMap<>();

        /** L1 暖启动当前生效阈值（触发一次翻倍，至 l1-threshold-max 封顶；0 表示未初始化）。 */
        private int l1Threshold;

        /** 自上次画像（L3）以来 L1 的 store/update/merge 累计条数（P4 阈值依据）。 */
        private int memoriesSincePersona;

        /** [PERSONA_UPDATE_REQUEST] 信号：L2 萃取要求更新画像（P1 路径依据）。 */
        private boolean personaRequest;

        /** 画像是否生成过（P2 冷启动判定：从未生成过才走冷启动）。 */
        private boolean personaEverGenerated;

        /** L2 已消费的 L1 条数（l1.jsonl 行数口径，L2 只处理其后的新增记录）。 */
        private int l2ConsumedCount;

        /** 待萃取的 skill 轮次（最终回复轮累计，达阈值触发一次 skill 萃取后清空）。 */
        private List<Round> skillRounds = new ArrayList<>();

        /** 取指定会话的游标，不存在时返回全新的一条（l1Cursor=0 表示从头消费）。 */
        public SessionCursor session(String sessionId) {
            return sessions.computeIfAbsent(sessionId, key -> new SessionCursor());
        }

        public Map<String, SessionCursor> getSessions() {
            return sessions;
        }

        public void setSessions(Map<String, SessionCursor> sessions) {
            this.sessions = sessions == null ? new LinkedHashMap<>() : sessions;
        }

        public int getL1Threshold() {
            return l1Threshold;
        }

        public void setL1Threshold(int l1Threshold) {
            this.l1Threshold = l1Threshold;
        }

        public int getMemoriesSincePersona() {
            return memoriesSincePersona;
        }

        public void setMemoriesSincePersona(int memoriesSincePersona) {
            this.memoriesSincePersona = memoriesSincePersona;
        }

        public boolean isPersonaRequest() {
            return personaRequest;
        }

        public void setPersonaRequest(boolean personaRequest) {
            this.personaRequest = personaRequest;
        }

        public boolean isPersonaEverGenerated() {
            return personaEverGenerated;
        }

        public void setPersonaEverGenerated(boolean personaEverGenerated) {
            this.personaEverGenerated = personaEverGenerated;
        }

        public int getL2ConsumedCount() {
            return l2ConsumedCount;
        }

        public void setL2ConsumedCount(int l2ConsumedCount) {
            this.l2ConsumedCount = l2ConsumedCount;
        }

        public List<Round> getSkillRounds() {
            return skillRounds == null ? List.of() : skillRounds;
        }

        public void setSkillRounds(List<Round> skillRounds) {
            this.skillRounds = skillRounds == null ? new ArrayList<>() : skillRounds;
        }

        /**
         * 追加一轮待萃取的 skill 轮次。
         */
        public void addSkillRound(Round round) {
            if (skillRounds == null) {
                skillRounds = new ArrayList<>();
            }
            skillRounds.add(round);
        }

        /**
         * 清空待萃取轮次（skill 萃取完成后调用）。
         */
        public void clearSkillRounds() {
            skillRounds = new ArrayList<>();
        }
    }

    /**
     * 单个会话的游标。
     *
     * @author yyf
     * @description l1Cursor：该会话已被 L1 提取消费到的消息序号（窗口 = (l1Cursor, now]）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SessionCursor {

        /** 该 session 已被 L1 提取消费到的消息序号，会话消息按文件顺序即 seq 单调递增。 */
        private int l1Cursor;

        public int getL1Cursor() {
            return l1Cursor;
        }

        public void setL1Cursor(int l1Cursor) {
            this.l1Cursor = l1Cursor;
        }
    }

    /**
     * 一轮待萃取的 skill 轮次（最终回复轮的定位信息）。
     *
     * @author yyf
     * @description round-level 记账语义：只记「哪次运行的最终回复」，
     * 累计达阈值后由 pipeline 回读各 trace 的会话切片做一次 skill 萃取
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Round {

        private String sessionId;
        private String traceId;

        public Round() {
        }

        public Round(String sessionId, String traceId) {
            this.sessionId = sessionId;
            this.traceId = traceId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getTraceId() {
            return traceId;
        }

        public void setTraceId(String traceId) {
            this.traceId = traceId;
        }
    }
}
