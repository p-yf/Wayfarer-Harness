package com.yf.wayfarer.agent.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 记忆管理处理器。
 *
 * @author yyf
 * @description 每轮模型响应结束后把 assistant 消息按轮次拼接到消息历史，不做压缩
 */
public class MemoryManageHandler implements ChainHandler {

    @Override
    public int sort() {
        // 排在模型调用之后：模型出结果才能拼接进记忆
        return 100;
    }

    @Override
    public Mono<Void> run(AgentContext context, Mono<Void> next) {
        return Mono.fromRunnable(() -> {
            ModelResponse response = context.modelResponse();
            if (response == null) {
                return;
            }
            List<ToolCallInfo> toolCalls = response.toolCalls();
            // DeepSeek V4 思考模式：含工具调用时必须回传 reasoning_content，纯文本轮次不回传
            String reasoning = toolCalls.isEmpty() ? null : response.reasoningContent();
            context.messages().add(ChatMessage.assistant(response.content(), reasoning, toolCalls));
        }).then(next);
    }
}
