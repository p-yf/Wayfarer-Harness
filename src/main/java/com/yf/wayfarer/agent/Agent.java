package com.yf.wayfarer.agent;

import com.yf.wayfarer.agent.modelchain.*;
import com.yf.wayfarer.agent.toolchain.CodingToolApprovalHandler;
import com.yf.wayfarer.agent.toolchain.ToolCallHandler;
import com.yf.wayfarer.agent.toolchain.ToolCircleCheckHandler;
import com.yf.wayfarer.hitl.HitlService;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.Chain;
import com.yf.wayfarer.loop.Loop;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ChatModel;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.UnloadedMessageStore;
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
 * @description 组装模型链与工具链，按会话运行循环；用户请求与对话消息由链路中的处理器写入会话，
 * 可持久化状态在运行结束后统一落盘
 */
public class Agent extends Loop {

    private final Session session;

    /**
     * @param unloadedStore 卸载消息存储，压缩时把大消息正文搬入其中
     * @param compact       记忆压缩配置，决定压缩阈值
     * @param hitl          人工确认协调器，编码工具审批时在此等待前端决定
     */
    public Agent(ChatModel model, List<ToolDefinition> tools, ToolCall toolCall,
                 Integer maxTurns, Session session, UnloadedMessageStore unloadedStore,
                 MemoryCompactConfig compact, HitlService hitl) {
        // 模型链：用户请求入历史 → 计划提醒 → 记忆压缩 → 补齐工具结果 → 调用记录 → 步骤持久化 → 模型调用 → 记忆回写
        super(Chain.of(
                new UserQueryPersistHandler(),
                new PlanReminderHandler(),
                new MemoryCompressHandler(model, unloadedStore, compact),
                new ToolResultRepairHandler(),
                new TraceRecordHandler(),
                new AssistantAndToolCallPersistHandler(),
                new ModelHandler(model, tools),
                new MemoryManageHandler()),
                // 工具链：重复调用检查 → 编码工具审批 → 步骤持久化 → 工具执行
                Chain.of(new ToolCircleCheckHandler(), new CodingToolApprovalHandler(hitl),
                        new AssistantAndToolCallPersistHandler(), new ToolCallHandler(toolCall)), maxTurns);
        this.session = session;
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

        return run(context)
                // 正常结束先持久化状态再完成，保证下游拿到完成信号时文件已落盘
                .concatWith(Flux.defer(() -> {
                    persistStoreState(context, sessionId, traceId);
                    return Flux.empty();
                }))
                // 取消与异常时 concatWith 不会执行，各补一次，保证任何终止方式都落盘
                .doOnCancel(() -> persistStoreState(context, sessionId, traceId))
                .doOnError(error -> persistStoreState(context, sessionId, traceId));
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
