package com.yf.wayfarer.agent.toolchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCall;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;

/**
 * 工具调用处理器。
 *
 * @author yyf
 * @description 按顺序执行模型请求的每个工具调用，把工具 id 与名称写入上下文供工具自行鉴权，
 * 执行结果作为 tool 消息推送并按轮次拼接到消息历史
 */
public class ToolCallHandler implements ChainHandler {

    private final ToolCall toolCall;

    public ToolCallHandler(ToolCall toolCall) {
        this.toolCall = toolCall;
    }

    @Override
    public Mono<Void> run(AgentContext context, Mono<Void> next) {
        ModelResponse response = context.modelResponse();
        if (response == null || response.toolCalls().isEmpty()) {
            return next;
        }
        return Flux.fromIterable(response.toolCalls())
                .concatMap(info -> execute(context, info))
                .then(next);
    }

    private Mono<Void> execute(AgentContext context, ToolCallInfo info) {
        return Mono.fromRunnable(() -> {
            // 工具在执行过程中可据此定位自己的 HITL 等待项
            context.put(AgentContext.TOOL_CALL_ID, info.id());
            context.put(AgentContext.TOOL_NAME, info.name());

            Object result = toolCall.call(info, context);
            String content = result == null ? "" : String.valueOf(result);
            Consumer<AgentMessage> sink = context.agentMsgSink();
            if (sink != null) {
                sink.accept(new AgentMessage.ToolResult(context.traceId(), info.id(), content));
            }
            context.messages().add(new ChatMessage(ChatMessage.Role.TOOL, content, info.id()));
        });
    }
}
