package com.yf.wayfarer.agent;

import com.yf.wayfarer.agent.modelchain.*;
import com.yf.wayfarer.agent.toolchain.ToolCallHandler;
import com.yf.wayfarer.agent.toolchain.ToolCircleCheckHandler;
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
     */
    public Agent(ChatModel model, List<ToolDefinition> tools, ToolCall toolCall,
                 Integer maxTurns, Session session, UnloadedMessageStore unloadedStore,
                 MemoryCompactConfig compact) {
        super(Chain.of(
                        // 模型链：用户请求落盘 → 记忆压缩 → 步骤持久化 → 调用记录 → 模型调用 → 记忆回写
                        new UserQueryPersistHandler(),
                        new MemoryCompressHandler(model, unloadedStore, compact),
                        new AssistantAndToolCallPersistHandler(),
                        new TraceRecordHandler(),
                        new ModelHandler(model, tools),
                        new MemoryManageHandler()),
                // 工具链：重复调用检查 → 步骤持久化包裹工具执行，负责工具结果落盘
                Chain.of(new ToolCircleCheckHandler(),
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
                // 循环正常结束后先持久化状态再完成，保证下游拿到完成信号时文件已落盘
                .concatWith(Flux.defer(() -> {
                    persistStoreState(context, sessionId, traceId);
                    return Flux.empty();
                }));
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
