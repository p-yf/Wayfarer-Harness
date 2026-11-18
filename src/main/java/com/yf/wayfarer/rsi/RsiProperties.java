package com.yf.wayfarer.rsi;

/**
 * RSI（自动沉淀）配置。
 *
 * <p>对应 application.yml 中 {@code wayfarer.rsi.*} 配置段，由 {@code SessionConfiguration}
 * 在装配时从 Spring 环境读入。所有阈值都有缺省值，零配置也能工作。
 * 触发模型：L1 由 L0 消息数量阈值触发（暖启动翻倍 +
 * idle 兜底）、L2 由 L1 完成后延迟触发、L3 由五级 PersonaTrigger 触发、
 * skill 由累计轮数阈值触发。</p>
 *
 * @author yyf
 * @description RSI 模块的配置载体：存储根目录、线程池参数、L1 阈值族、L2 延迟、
 * 场景库上限、画像触发阈值、skill 轮数阈值、召回预算等，一处定义、处处只读
 */
public final class RsiProperties {

    /** RSI 总开关：关闭后沉淀 handler 直接跳过，召回也不注入（普通 agent 行为完全不受影响）。 */
    private final boolean enabled;

    /** RSI 存储根目录：其下分 stores/（沉淀产物，jgit worktree）、git/（git-dir）、state/（游标簿记）。 */
    private final String root;

    /** 沉淀线程池核心线程数：LLM 提取是秒级~分钟级操作，2 个线程足够。 */
    private final int poolSize;

    /** 沉淀任务有界队列容量：满了直接丢弃并 warn（宁缺毋滥，不堆积）。 */
    private final int queueCapacity;

    /** L1 打分门槛：提取出的原子记忆 score 低于该值直接丢弃。 */
    private final int l1ScoreFloor;

    /** 记忆召回：L1 取 top-k 条数。 */
    private final int recallTopK;

    /** 记忆召回：注入文本总预算（粗略按字符数估算 token，超限截断）。 */
    private final int recallTokenBudget;

    /** skill 软上限：store 内技能数达到该值后 Review prompt 收紧（优先 patch 合并）。 */
    private final int skillSoftLimit;

    /** 暖启动初始阈值：首轮对话第 1 条消息即触发 L1（新用户立刻感知"它记得我"）。 */
    private final int l1ThresholdStart;

    /** 暖启动阈值上限：每次触发翻倍、至此封顶。 */
    private final int l1ThresholdMax;

    /** idle 兜底：缓冲非空且距上次活动超过该秒数时强制触发一轮 L1。 */
    private final int l1IdleSeconds;

    /** 单轮 L1 处理的新消息条数（= 提取窗口 maxNewMessages）。 */
    private final int l1BatchProcess;

    /** L1 提取窗口的背景消息条数（只作上下文，不抽取）。 */
    private final int l1MaxBackground;

    /** 单次 L1 落盘的记忆条数上限。 */
    private final int l1MaxMemories;

    /** 去重候选召回数：每条新记忆词面召回的既有 L1 条数（两阶段判重的阶段 A）。 */
    private final int conflictRecallTopK;

    /** L2 延迟：L1 落盘成功后延迟该秒数再执行场景萃取。 */
    private final int l2DelaySeconds;

    /** 场景库上限：达到后 L2 萃取 prompt 强制 MERGE（分档预警）。 */
    private final int maxScenes;

    /** L3 P4 阈值：自上次画像以来新增记忆达到该值触发例行蒸馏。 */
    private final int personaTriggerEveryN;

    /** skill 累计轮数阈值：累计这么多最终回复轮才触发一次 skill 萃取。 */
    private final int skillArchiveEveryNRounds;

    /**
     * 全参构造，由 SessionConfiguration 以 @Value 注入调用。
     */
    public RsiProperties(boolean enabled, String root, int poolSize, int queueCapacity,
                         int l1ScoreFloor, int recallTopK, int recallTokenBudget, int skillSoftLimit,
                         int l1ThresholdStart, int l1ThresholdMax, int l1IdleSeconds,
                         int l1BatchProcess, int l1MaxBackground, int l1MaxMemories,
                         int conflictRecallTopK, int l2DelaySeconds, int maxScenes,
                         int personaTriggerEveryN, int skillArchiveEveryNRounds) {
        this.enabled = enabled;
        this.root = root == null || root.isBlank() ? "./.wayfarer/rsi" : root;
        this.poolSize = poolSize <= 0 ? 2 : poolSize;
        this.queueCapacity = queueCapacity <= 0 ? 32 : queueCapacity;
        this.l1ScoreFloor = l1ScoreFloor <= 0 ? 6 : l1ScoreFloor;
        this.recallTopK = recallTopK <= 0 ? 5 : recallTopK;
        this.recallTokenBudget = recallTokenBudget <= 0 ? 800 : recallTokenBudget;
        this.skillSoftLimit = skillSoftLimit <= 0 ? 20 : skillSoftLimit;
        this.l1ThresholdStart = l1ThresholdStart <= 0 ? 1 : l1ThresholdStart;
        this.l1ThresholdMax = l1ThresholdMax <= 0 ? 16 : l1ThresholdMax;
        this.l1IdleSeconds = l1IdleSeconds <= 0 ? 60 : l1IdleSeconds;
        this.l1BatchProcess = l1BatchProcess <= 0 ? 10 : l1BatchProcess;
        this.l1MaxBackground = l1MaxBackground <= 0 ? 5 : l1MaxBackground;
        this.l1MaxMemories = l1MaxMemories <= 0 ? 10 : l1MaxMemories;
        this.conflictRecallTopK = conflictRecallTopK <= 0 ? 5 : conflictRecallTopK;
        this.l2DelaySeconds = l2DelaySeconds <= 0 ? 30 : l2DelaySeconds;
        this.maxScenes = maxScenes <= 0 ? 20 : maxScenes;
        this.personaTriggerEveryN = personaTriggerEveryN <= 0 ? 20 : personaTriggerEveryN;
        this.skillArchiveEveryNRounds = skillArchiveEveryNRounds <= 0 ? 5 : skillArchiveEveryNRounds;
    }

    public boolean enabled() {
        return enabled;
    }

    public String root() {
        return root;
    }

    public int poolSize() {
        return poolSize;
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    public int l1ScoreFloor() {
        return l1ScoreFloor;
    }

    public int recallTopK() {
        return recallTopK;
    }

    public int recallTokenBudget() {
        return recallTokenBudget;
    }

    public int skillSoftLimit() {
        return skillSoftLimit;
    }

    public int l1ThresholdStart() {
        return l1ThresholdStart;
    }

    public int l1ThresholdMax() {
        return l1ThresholdMax;
    }

    public int l1IdleSeconds() {
        return l1IdleSeconds;
    }

    public int l1BatchProcess() {
        return l1BatchProcess;
    }

    public int l1MaxBackground() {
        return l1MaxBackground;
    }

    public int l1MaxMemories() {
        return l1MaxMemories;
    }

    public int conflictRecallTopK() {
        return conflictRecallTopK;
    }

    public int l2DelaySeconds() {
        return l2DelaySeconds;
    }

    public int maxScenes() {
        return maxScenes;
    }

    public int personaTriggerEveryN() {
        return personaTriggerEveryN;
    }

    public int skillArchiveEveryNRounds() {
        return skillArchiveEveryNRounds;
    }
}
