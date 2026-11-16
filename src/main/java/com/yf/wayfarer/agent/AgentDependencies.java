package com.yf.wayfarer.agent;

import com.yf.wayfarer.change.ChangeRecorder;
import com.yf.wayfarer.hitl.HitlService;
import com.yf.wayfarer.loop.modelchain.MemoryCompactConfig;
import com.yf.wayfarer.model.ChatModel;
import com.yf.wayfarer.prompt.SystemPromptStore;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.UnloadedMessageStore;
import com.yf.wayfarer.skill.SkillStore;
import com.yf.wayfarer.subagent.SubAgentFactory;
import com.yf.wayfarer.subagent.SubAgentManager;
import com.yf.wayfarer.trace.TraceStatus;

/**
 * agent 装配依赖。
 *
 * @author yyf
 * @description 一次运行所需的共享组件与运行参数；subAgentFactory 需要反向引用本对象，
 * 因此在装配完成后由调用方注入，避免构造顺序上的循环依赖
 */
public final class AgentDependencies {

    private final Session session;
    private final UnloadedMessageStore unloadedStore;
    private final SystemPromptStore promptStore;
    private final ChatModel model;
    private final MemoryCompactConfig compact;
    private final HitlService hitl;
    private final SkillStore skillStore;
    private final ChangeRecorder changeRecorder;
    private final TraceStatus traceStatus;
    private final SubAgentManager subAgentManager;
    private final Integer maxTurns;
    private final int maxDepth;
    private final long subAgentTimeoutSeconds;
    private final long finishWaitSeconds;

    private SubAgentFactory subAgentFactory;

    public AgentDependencies(Session session, UnloadedMessageStore unloadedStore, SystemPromptStore promptStore,
                             ChatModel model, MemoryCompactConfig compact, HitlService hitl, SkillStore skillStore,
                             ChangeRecorder changeRecorder, TraceStatus traceStatus, SubAgentManager subAgentManager,
                             Integer maxTurns, int maxDepth, long subAgentTimeoutSeconds, long finishWaitSeconds) {
        this.session = session;
        this.unloadedStore = unloadedStore;
        this.promptStore = promptStore;
        this.model = model;
        this.compact = compact;
        this.hitl = hitl;
        this.skillStore = skillStore;
        this.changeRecorder = changeRecorder;
        this.traceStatus = traceStatus;
        this.subAgentManager = subAgentManager;
        this.maxTurns = maxTurns;
        this.maxDepth = maxDepth;
        this.subAgentTimeoutSeconds = subAgentTimeoutSeconds;
        this.finishWaitSeconds = finishWaitSeconds;
    }

    public void subAgentFactory(SubAgentFactory factory) {
        this.subAgentFactory = factory;
    }

    public SubAgentFactory subAgentFactory() {
        return subAgentFactory;
    }

    public Session session() {
        return session;
    }

    public UnloadedMessageStore unloadedStore() {
        return unloadedStore;
    }

    public SystemPromptStore promptStore() {
        return promptStore;
    }

    public ChatModel model() {
        return model;
    }

    public MemoryCompactConfig compact() {
        return compact;
    }

    public HitlService hitl() {
        return hitl;
    }

    public SkillStore skillStore() {
        return skillStore;
    }

    public ChangeRecorder changeRecorder() {
        return changeRecorder;
    }

    public TraceStatus traceStatus() {
        return traceStatus;
    }

    public SubAgentManager subAgentManager() {
        return subAgentManager;
    }

    public Integer maxTurns() {
        return maxTurns;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public long subAgentTimeoutSeconds() {
        return subAgentTimeoutSeconds;
    }

    public long finishWaitSeconds() {
        return finishWaitSeconds;
    }
}
