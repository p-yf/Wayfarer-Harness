package com.yf.wayfarer.rsi;

import com.yf.wayfarer.session.SessionMessage;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 沉淀流水线触发器（分层触发模型的纯判定 + 统一调度）。
 *
 * <p>记忆沉淀分层解耦：L1 由 <b>L0 消息数量阈值</b>触发（带 warm-up：
 * 首轮阈值 1 保证新用户立即出记忆，之后每触发一次翻倍、至上限，逐步放宽），
 * 外加 <b>idle 兜底</b>（缓冲非空但用户长时间没说话，照样消化，低频用户不被饿死）；
 * L2 由 <b>L1 完成后延迟</b>触发。本类承载这套纯判定逻辑与统一调度器：</p>
 * <ul>
 *   <li>暖启动阈值：initial → ×2 → ×4 → … 封顶 l1-threshold-max；</li>
 *   <li>窗口切分：待处理消息 &gt; l1-batch-process 时取末尾一批（新消息），
 *       其前至多 l1-max-background 条作背景（只读不抽取），游标整批前移不拆组；</li>
 *   <li>idle 兜底判定：pending &gt; 0 且距上次活动 ≥ l1-idle-seconds；</li>
 *   <li>调度器：单线程 daemon ScheduledThreadPoolExecutor，承载 idle 周期巡检与
 *       L2 延迟任务（进程退出即销毁，不阻塞 JVM）。</li>
 * </ul>
 *
 * <p>状态（阈值、游标）不在本类保存——由 {@link RsiCursorStore} 持久化，
 * 本类只做无状态计算 + 提供调度能力，单测无需任何文件设施。</p>
 *
 * @author yyf
 * @description nextThreshold()：暖启动翻倍封顶；shouldFireL1()：缓冲达阈值；
 * sliceWindow()：末尾批量 + 前置背景 + 新游标；shouldIdleFire()：idle 兜底判定；
 * schedule()/schedulePeriodically()：L2 延迟与 idle 巡检共用调度器
 */
@Service
public class RsiPipelineTriggers {

    private final RsiProperties properties;

    /** 统一调度器：idle 巡检（固定周期）与 L2 延迟任务（一次性）都走这里。 */
    private final ScheduledThreadPoolExecutor scheduler;

    public RsiPipelineTriggers(RsiProperties properties) {
        this.properties = properties;
        this.scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "rsi-trigger");
            thread.setDaemon(true);
            return thread;
        });
        // 核心线程常驻即可：任务频率低（秒级~分钟级），不需要 keepalive 回收
        scheduler.setKeepAliveTime(0, TimeUnit.SECONDS);
        scheduler.allowCoreThreadTimeOut(false);
    }

    /* ------------------------------------------------------------------ */
    /* 纯判定逻辑（单测覆盖点）                                               */
    /* ------------------------------------------------------------------ */

    /**
     * 暖启动的下一个阈值：当前阈值翻倍、封顶 l1-threshold-max。
     *
     * <p>warm-up 语义：首轮阈值 1（第一条消息就出记忆，新用户立刻感到"它记得我"），
     * 每触发一次翻倍，让触发频率随对话量自然稀疏。</p>
     *
     * @param current 当前生效阈值（0 / 负数视为未初始化，按初始阈值处理）
     * @return 下一次生效的阈值
     */
    public int nextThreshold(int current) {
        int base = current <= 0 ? properties.l1ThresholdStart() : current;
        return Math.min(base * 2, properties.l1ThresholdMax());
    }

    /**
     * 暖启动的初始阈值（首次触发前的生效值）。
     */
    public int initialThreshold() {
        return Math.max(1, properties.l1ThresholdStart());
    }

    /**
     * L1 触发判定：待处理消息数达到生效阈值。
     */
    public boolean shouldFireL1(int pending, int effectiveThreshold) {
        return pending >= Math.max(1, effectiveThreshold);
    }

    /**
     * idle 兜底判定：缓冲非空且距上次活动超过 l1-idle-seconds。
     *
     * <p>防止低频用户永远凑不够阈值的兜底。</p>
     */
    public boolean shouldIdleFire(int pending, long millisSinceLastActivity) {
        return pending > 0 && millisSinceLastActivity >= properties.l1IdleSeconds() * 1000L;
    }

    /**
     * 窗口切分：从 (fromIndex, conversation.size()) 的待处理区取<b>末尾</b>
     * 至多 l1-batch-process 条作新消息（超量时旧的自然淘汰），
     * 再取其前至多 l1-max-background 条作背景（只作上下文、不抽取）。
     *
     * <p>游标整批前移到 conversation.size()——不拆组，避免半批消息反复重提。</p>
     *
     * @param conversation root 对话消息全量（按文件顺序）
     * @param fromIndex    该会话 L1 已消费到的下标（不含）
     * @return 新消息 + 背景 + 新游标
     */
    public Window sliceWindow(List<SessionMessage> conversation, int fromIndex) {
        int size = conversation.size();
        int from = Math.min(Math.max(0, fromIndex), size);
        int pending = size - from;
        if (pending <= 0) {
            return new Window(List.of(), List.of(), size);
        }
        int newCount = Math.min(pending, Math.max(1, properties.l1BatchProcess()));
        List<SessionMessage> newMessages =
                conversation.subList(size - newCount, size);
        int backgroundEnd = size - newCount;
        int backgroundStart = Math.max(from, backgroundEnd - Math.max(0, properties.l1MaxBackground()));
        List<SessionMessage> background = conversation.subList(backgroundStart, backgroundEnd);
        return new Window(List.copyOf(newMessages), List.copyOf(background), size);
    }

    /* ------------------------------------------------------------------ */
    /* 调度能力                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * 延迟调度一次性任务（L2 的 l2DelayAfterL1Seconds 语义）。
     */
    public ScheduledFuture<?> schedule(Runnable task, long delaySeconds) {
        return scheduler.schedule(wrap(task), Math.max(0, delaySeconds), TimeUnit.SECONDS);
    }

    /**
     * 固定延迟周期任务（idle 兜底巡检）。
     */
    public ScheduledFuture<?> schedulePeriodically(Runnable task, long periodSeconds) {
        return scheduler.scheduleWithFixedDelay(wrap(task), Math.max(1, periodSeconds),
                Math.max(1, periodSeconds), TimeUnit.SECONDS);
    }

    /**
     * 调度任务兜底包裹：巡检/延迟任务里的任何异常只记日志，绝不杀死调度线程。
     */
    private static Runnable wrap(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                RsiSedimentPipeline.logTriggerError(e);
            }
        };
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * @author yyf
     * @description 窗口切分结果：newMessages 进提取提示词（新消息区）、background 只作
     * 上下文（不抽取）、newCursor 为本批消费后的会话消息游标
     */
    public record Window(List<SessionMessage> newMessages,
                         List<SessionMessage> background,
                         int newCursor) {
    }
}
