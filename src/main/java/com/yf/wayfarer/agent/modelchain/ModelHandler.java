package com.yf.wayfarer.agent.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.AgentMsg;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.model.ChatModel;
import com.yf.wayfarer.model.ChatRequest;
import com.yf.wayfarer.toolcall.ToolDefinition;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Consumer;

/**
 * 模型调用处理器。
 *
 * @author yyf
 * @description 按上下文消息历史发起流式调用，推送流式片段并把完整响应写入上下文
 */
public class ModelHandler implements ChainHandler {

    private final ChatModel model;
    private final List<ToolDefinition> tools;

    public ModelHandler(ChatModel model, List<ToolDefinition> tools) {
        this.model = model;
        this.tools = tools == null ? List.of() : List.copyOf(tools);
    }

    @Override
    public Mono<Void> run(AgentContext context, Mono<Void> next) {
        return model.stream(new ChatRequest(context.messages(), tools))
                .doOnNext(chunk -> {
                    Consumer<AgentMsg> sink = context.agentMsgSink();
                    if (sink != null) {
                        sink.accept(AgentMsg.chunk(chunk));
                    }
                    if (chunk.response() != null) {
                        context.put(AgentContext.MODEL_RESPONSE, chunk.response());
                    }
                })
                .then(next);
    }
}
