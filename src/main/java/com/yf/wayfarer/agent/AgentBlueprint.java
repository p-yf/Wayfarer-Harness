package com.yf.wayfarer.agent;

import com.yf.wayfarer.agent.agentchain.ChangeSummaryHandler;
import com.yf.wayfarer.loop.AssistantAndToolCallPersistHandler;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.LoopChain;
import com.yf.wayfarer.loop.modelchain.MemoryCompressHandler;
import com.yf.wayfarer.loop.modelchain.MemoryManageHandler;
import com.yf.wayfarer.loop.modelchain.ModelHandler;
import com.yf.wayfarer.loop.modelchain.PlanReminderHandler;
import com.yf.wayfarer.loop.modelchain.SkillsHandler;
import com.yf.wayfarer.loop.modelchain.ToolResultRepairHandler;
import com.yf.wayfarer.loop.modelchain.TraceRecordHandler;
import com.yf.wayfarer.loop.modelchain.UserQueryPersistHandler;
import com.yf.wayfarer.loop.toolchain.CodingToolApprovalHandler;
import com.yf.wayfarer.loop.toolchain.ToolCallHandler;
import com.yf.wayfarer.loop.toolchain.ToolCallLoopHandler;
import com.yf.wayfarer.loop.toolchain.ToolCircleCheckHandler;
import com.yf.wayfarer.session.MessageOrigin;
import com.yf.wayfarer.subagent.SubAgent;
import com.yf.wayfarer.subagent.SubAgentQuotaHandler;
import com.yf.wayfarer.subagent.SubAgentSupervisor;
import com.yf.wayfarer.subagent.SubAgentTools;
import com.yf.wayfarer.tools.CodingTools;
import com.yf.wayfarer.tools.PlanTools;
import com.yf.wayfarer.tools.UnloadTools;
import com.yf.wayfarer.toolcall.ToolCall;
import com.yf.wayfarer.toolcall.ToolDefinition;
import com.yf.wayfarer.trace.StoreStatePersistHandler;
import com.yf.wayfarer.trace.TraceStartManager;
import com.yf.wayfarer.trace.TraceStopManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * agent 蓝图。
 *
 * @author yyf
 * @description 按名称决定工具集与三条链路的装配方式；子 Agent 由父蓝图在运行时派生，
 * 复用同款模型链与工具链，但不挂轮次封装、状态落盘与变更汇总
 */
public abstract class AgentBlueprint {

    /**
     * 子 Agent 固定使用的名称后缀，不作为独立名称注册。
     */
    public static final String SUBAGENT_SUFFIX = ":subagent";

    private static final String SUBAGENT_INSTRUCTION = """
            你是子 Agent，只负责完成给定任务。
            不要询问用户，不要等待确认；信息不足时按最合理的假设继续。
            完成后用简洁的结论回答，不要复述过程。""";

    public abstract String name();

    /**
     * 基础工具集：现有卸载、计划与编码工具。
     */
    protected List<Object> baseTools(AgentDependencies deps) {
        List<Object> tools = new ArrayList<>();
        tools.add(new UnloadTools(deps.unloadedStore()));
        tools.add(new PlanTools());
        tools.add(new CodingTools());
        return tools;
    }

    /**
     * 本 Agent 暴露给模型的工具对象，子类只在此基础上追加。
     */
    public List<Object> toolObjects(AgentDependencies deps) {
        return baseTools(deps);
    }

    /**
     * 装配 root agent。
     */
    public Agent build(AgentDependencies deps) {
        ToolCall toolCall = new ToolCall();
        List<ToolDefinition> tools = toolCall.parse(toolObjects(deps).toArray());
        return new Agent(deps.session(), modelChain(deps, tools), toolChain(deps, toolCall),
                runChain(deps), deps.maxTurns());
    }

    /**
     * 派生一个子 Agent：独立上下文、独立 sink、共享 traceId，并带 origin 归属。
     */
    public SubAgent buildSubAgent(AgentContext parent, AgentDependencies deps, String agentId,
                                  String viaToolCallId, String task, boolean historyExtend, String extra) {
        ToolCall toolCall = new ToolCall();
        List<ToolDefinition> tools = toolCall.parse(toolObjects(deps).toArray());
        AgentContext child = childContext(parent, deps, agentId, viaToolCallId, task, historyExtend, extra);
        return new SubAgent(child, agentId, viaToolCallId, modelChain(deps, tools), toolChain(deps, toolCall),
                deps.maxTurns(), parent.agentMsgSink(), deps.subAgentTimeoutSeconds());
    }

    /**
     * 标准模型链：用户请求入历史 → 计划提醒 → 记忆压缩 → 技能清单 → 补齐工具结果 → 调用记录 → 步骤持久化 → 模型调用 → 记忆回写
     */
    protected LoopChain modelChain(AgentDependencies deps, List<ToolDefinition> tools) {
        return LoopChain.of(
                new UserQueryPersistHandler(),
                new PlanReminderHandler(),
                new MemoryCompressHandler(deps.model(), deps.unloadedStore(), deps.compact()),
                new SkillsHandler(deps.skillStore()),
                new ToolResultRepairHandler(),
                new TraceRecordHandler(),
                new AssistantAndToolCallPersistHandler(),
                new ModelHandler(deps.model(), tools),
                new MemoryManageHandler());
    }

    /**
     * 标准工具链：步骤持久化 → 批量调度 → 单次调用（重复检查 → 子 Agent 配额 → 审批 → 执行）
     */
    protected LoopChain toolChain(AgentDependencies deps, ToolCall toolCall) {
        return LoopChain.of(new AssistantAndToolCallPersistHandler(),
                new ToolCallLoopHandler(), new ToolCircleCheckHandler(),
                new SubAgentQuotaHandler(deps.subAgentManager()),
                new CodingToolApprovalHandler(deps.hitl()), new ToolCallHandler(toolCall));
    }

    /**
     * 运行级链：准入 → 收尾 → 状态落盘 → 变更汇总 → 子 Agent 收敛。
     * sort 越小越外层，因此前置顺序与后置顺序相反。
     */
    public LoopChain runChain(AgentDependencies deps) {
        return LoopChain.of(
                new TraceStartManager(deps.traceStatus()),
                new TraceStopManager(deps.traceStatus()),
                new StoreStatePersistHandler(),
                new ChangeSummaryHandler(deps.changeRecorder()),
                new SubAgentSupervisor(deps.subAgentManager(), deps.finishWaitSeconds()));
    }

    /**
     * 复制父上下文得到子上下文：只继承会话、trace、工作区、审批级别与可选的历史快照。
     */
    private AgentContext childContext(AgentContext parent, AgentDependencies deps, String agentId,
                                      String viaToolCallId, String task, boolean historyExtend, String extra) {
        AgentContext child = new AgentContext();
        child.put(AgentContext.SESSION, deps.session());
        child.put(AgentContext.SESSION_ID, parent.sessionId());
        child.put(AgentContext.TRACE_ID, parent.traceId());
        String rootName = parent.rootAgentName();
        String childName = (rootName == null || rootName.isBlank() ? name() : rootName) + SUBAGENT_SUFFIX;
        child.put(AgentContext.ROOT_AGENT_NAME, rootName == null ? name() : rootName);
        child.put(AgentContext.AGENT_NAME, childName);
        child.put(AgentContext.AGENT_ID, agentId);
        child.put(AgentContext.DEPTH, parent.depth() + 1);
        child.put(AgentContext.ORIGIN, new MessageOrigin(agentId, viaToolCallId, parent.depth() + 1));
        child.put(AgentContext.USER_QUERY,
                extra == null || extra.isBlank() ? task : task + "\n\n补充上下文：\n" + extra);
        copy(parent, child, AgentContext.WORKSPACE);
        copy(parent, child, AgentContext.ROOT_WORKSPACE);
        copy(parent, child, AgentContext.APPROVAL);

        Map<String, String> prompts = new LinkedHashMap<>(deps.promptStore().loadMap(childName));
        if (prompts.isEmpty()) {
            prompts.putAll(deps.promptStore().loadMap(name()));
            prompts.put("999-subagent", SUBAGENT_INSTRUCTION);
        }
        child.put(AgentContext.SYSTEM_PROMPT, prompts);

        List<com.yf.wayfarer.model.ChatMessage> history = new ArrayList<>();
        if (historyExtend && parent.messages() != null) {
            history.addAll(parent.messages());
        }
        child.put(AgentContext.MESSAGES, history);
        return child;
    }

    private static void copy(AgentContext from, AgentContext to, String key) {
        Object value = from.get(key);
        if (value != null) {
            to.put(key, value);
        }
    }
}
