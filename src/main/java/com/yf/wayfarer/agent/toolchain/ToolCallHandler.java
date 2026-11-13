package com.yf.wayfarer.agent.toolchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.AgentMsg;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCall;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;

/**
 * 工具调用处理器。
 *
 * @author yyf
 * @description 执行模型请求的每个工具调用，推送结果消息并把 tool 消息按轮次拼接到消息历史
 */
public class ToolCallHandler implements ChainHandler {

    private final ToolCall toolCall;

    public ToolCallHandler(ToolCall toolCall) {
        this.toolCall = toolCall;
    }

    @Override
    public Mono<Void> run(AgentContext context, Mono<Void> next) {
        return Mono.fromRunnable(() -> {
            ModelResponse response = context.modelResponse();
            if (response == null) {
                return;
            }
            Consumer<AgentMsg> sink = context.agentMsgSink();
            for (ToolCallInfo info : response.toolCalls()) {
                Object result = toolCall.call(info);
                String content = result == null ? "" : String.valueOf(result);
                if (sink != null) {
                    sink.accept(AgentMsg.toolResult(info, content));
                }
                context.messages().add(new ChatMessage(ChatMessage.Role.TOOL, content, info.id()));
            }
        }).then(next);
    }
}
