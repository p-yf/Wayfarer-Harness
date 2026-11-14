package com.yf.wayfarer.agent;

import com.yf.wayfarer.loop.AssistantAndToolCallPersistHandler;
import com.yf.wayfarer.loop.modelchain.MemoryCompactConfig;
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
import com.yf.wayfarer.loop.toolchain.ToolCircleCheckHandler;
import com.yf.wayfarer.hitl.HitlService;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.LoopChain;
import com.yf.wayfarer.loop.Loop;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ChatModel;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.UnloadedMessageStore;
import com.yf.wayfarer.skill.SkillStore;
import com.yf.wayfarer.toolcall.ToolCall;
import com.yf.wayfarer.toolcall.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * agent 门面。
 *
 * @author yyf
 * @description 在会话上运行调用方装配好的模型链、工具链与运行级链；用户请求与对话消息由链路中的处理器写入会话，
 * 可持久化状态在运行结束后统一落盘
 */
public class Agent extends Loop {

    private final Session session;

    /**
     * 运行级链，包在主循环外层，用于在一次运行的首尾追加动作；为空时直接透传主循环消息流。
     */
    private final AgentChain agentChain;

    /**
     * @param modelChain 模型链，每轮一次模型调用
     * @param toolChain  工具链，模型返回工具调用后执行
     * @param agentChain 运行级链，传入 null 表示不做运行前后的额外处理
     * @param maxTurns   最大轮次，null 表示不限制
     */
    public Agent(Session session, LoopChain modelChain, LoopChain toolChain, AgentChain agentChain, Integer maxTurns) {
        super(modelChain, toolChain, maxTurns);
        this.session = session;
        this.agentChain = agentChain == null ? AgentChain.of() : agentChain;
    }

    /**
     * 按组件装配标准链路，不含运行级处理器，供测试与默认场景使用。
     */
    public Agent(ChatModel model, List<ToolDefinition> tools, ToolCall toolCall,
                 Integer maxTurns, Session session, UnloadedMessageStore unloadedStore,
                 MemoryCompactConfig compact, HitlService hitl, SkillStore skillStore) {
        this(session, defaultModelChain(model, tools, unloadedStore, compact, skillStore),
                defaultToolChain(toolCall, hitl), AgentChain.of(), maxTurns);
    }

    /**
     * 在上下文中指定的会话上运行，会话内容会自动恢复到上下文。
     */
    public Flux<AgentMessage> stream(AgentContext context) {
        String sessionId = context.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("上下文缺少会话 id");
        }
        session.restore(sessionId, context);
        context.put(AgentContext.SESSION, session);
        // 调用方可通过上下文预置 trace id，使本轮问答归入同一次运行
        String provided = context.traceId();
        String traceId = provided == null || provided.isBlank()
                ? UUID.randomUUID().toString()
                : provided;
        context.put(AgentContext.TRACE_ID, traceId);

        // 运行级链包在主循环外层，其后的处理器可在主循环结束后继续追加消息
        return agentChain.apply(context, run(context))
                // 正常结束先持久化状态再结束流，保证下游拿到完成信号时状态已经落盘
                .concatWith(Flux.defer(() -> {
                    persistStoreState(context, sessionId, traceId);
                    return Flux.empty();
                }))
                // 取消与异常时 concatWith 不会执行，各补一次，保证任何终止方式都落盘
                .doOnCancel(() -> persistStoreState(context, sessionId, traceId))
                .doOnError(error -> persistStoreState(context, sessionId, traceId));
    }

    /**
     * 标准模型链：用户请求入历史 → 计划提醒 → 记忆压缩 → 技能清单 → 补齐工具结果 → 调用记录 → 步骤持久化 → 模型调用 → 记忆回写
     */
    private static LoopChain defaultModelChain(ChatModel model, List<ToolDefinition> tools,
                                               UnloadedMessageStore unloadedStore, MemoryCompactConfig compact,
                                               SkillStore skillStore) {
        return LoopChain.of(
                new UserQueryPersistHandler(),
                new PlanReminderHandler(),
                new MemoryCompressHandler(model, unloadedStore, compact),
                new SkillsHandler(skillStore),
                new ToolResultRepairHandler(),
                new TraceRecordHandler(),
                new AssistantAndToolCallPersistHandler(),
                new ModelHandler(model, tools),
                new MemoryManageHandler());
    }

    /**
     * 标准工具链：重复调用检查 → 编码工具审批 → 步骤持久化 → 工具执行
     */
    private static LoopChain defaultToolChain(ToolCall toolCall, HitlService hitl) {
        return LoopChain.of(new ToolCircleCheckHandler(), new CodingToolApprovalHandler(hitl),
                new AssistantAndToolCallPersistHandler(), new ToolCallHandler(toolCall));
    }

    /**
     * 循环结束后把上下文中的可持久化状态写入一条 CUSTOM 消息。
     */
    private void persistStoreState(AgentContext context, String sessionId, String traceId) {
        Map<String, Object> store = new LinkedHashMap<>(context.storeState());
        if (store.isEmpty()) {
            return;
        }
        session.append(sessionId, JSONMsgType.CUSTOM, traceId, store);
    }
}
