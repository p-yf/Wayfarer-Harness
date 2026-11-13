package com.yf.wayfarer.agent;

import com.yf.wayfarer.agent.modelchain.MemoryCompressHandler;
import com.yf.wayfarer.agent.modelchain.MemoryManageHandler;
import com.yf.wayfarer.agent.modelchain.ModelHandler;
import com.yf.wayfarer.agent.toolchain.ToolCallHandler;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.AgentMsg;
import com.yf.wayfarer.loop.Chain;
import com.yf.wayfarer.loop.Loop;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ChatModel;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.session.MessageType;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.SessionMessage;
import com.yf.wayfarer.session.UnloadedMessageStore;
import com.yf.wayfarer.toolcall.ToolCall;
import com.yf.wayfarer.toolcall.ToolCallInfo;
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
 * @description 组装模型链与工具链，按会话运行循环，运行过程中把消息与状态写入会话文件
 */
public class Agent extends Loop {

    private final Session session;

    /**
     * @param unloadedStore 卸载消息存储，压缩时把大消息正文搬入其中
     * @param contextWindow 上下文窗口 token 数，决定压缩阈值
     */
    public Agent(ChatModel model, List<ToolDefinition> tools, ToolCall toolCall,
                 Integer maxTurns, Session session, UnloadedMessageStore unloadedStore,
                 int contextWindow) {
        super(Chain.of(new MemoryCompressHandler(model, unloadedStore, contextWindow,
                                0.7, 0.01, 0.02, 0.5, 100),
                        new ModelHandler(model, tools),
                        new MemoryManageHandler()),
                Chain.of(new ToolCallHandler(toolCall)), maxTurns);
        this.session = session;
    }

    /**
     * 在上下文中指定的会话上运行，会话内容会自动恢复到上下文。
     */
    public Flux<AgentMsg> stream(AgentContext context) {
        String sessionId = context.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("上下文缺少会话 id");
        }
        session.restore(sessionId, context);
        // 调用方可通过上下文预置轮次 id，使本轮问答归入同一轮次
        String provided = context.turnId();
        String turnId = provided == null || provided.isBlank()
                ? UUID.randomUUID().toString()
                : provided;
        context.put(AgentContext.TURN_ID, turnId);

        return run(context)
                .doOnNext(message -> persistMessage(sessionId, message, turnId))
                // 循环正常结束后先持久化状态再完成，保证下游拿到完成信号时文件已落盘
                .concatWith(Flux.defer(() -> {
                    persistStore(context, sessionId, turnId);
                    return Flux.empty();
                }));
    }

    /**
     * 把最终响应与工具结果按轮次写入会话文件。
     */
    private void persistMessage(String sessionId, AgentMsg message, String turnId) {
        if (message.chunk() != null && message.chunk().response() != null) {
            ModelResponse response = message.chunk().response();
            List<ToolCallInfo> toolCalls = response.toolCalls();
            // DeepSeek V4 思考模式：含工具调用时才回传 reasoning_content
            String reasoning = toolCalls.isEmpty() ? null : response.reasoningContent();
            ChatMessage assistant = ChatMessage.assistant(response.content(), reasoning, toolCalls);
            session.append(sessionId, MessageType.ASSISTANT, turnId, SessionMessage.toMap(assistant));
        } else if (message.toolResult() != null) {
            ChatMessage tool = new ChatMessage(ChatMessage.Role.TOOL, message.toolResult(),
                    message.toolCallInfo().id());
            session.append(sessionId, MessageType.TOOL, turnId, SessionMessage.toMap(tool));
        }
    }

    /**
     * 循环结束后把上下文中的可持久化状态写入一条 CUSTOM 消息。
     */
    private void persistStore(AgentContext context, String sessionId, String turnId) {
        Map<String, Object> store = new LinkedHashMap<>(context.storeState());
        if (store.isEmpty()) {
            return;
        }
        session.append(sessionId, MessageType.CUSTOM, turnId, store);
    }
}
