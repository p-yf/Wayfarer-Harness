package com.yf.wayfarer.loop.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ModelResponse;

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
    public void run(AgentContext context, Runnable next) {
        ModelResponse response = context.modelResponse();
        if (response != null) {
            // 记忆即记录：思考内容一律完整保留，会话落盘与历史回放都依赖它。
            // 是否回传给模型属于供应商报文细节，由模型适配器按工具调用情况决定。
            ChatMessage message = ChatMessage.assistant(response.content(),
                    response.reasoningContent(), response.toolCalls());
            List<ChatMessage> messages = context.messages();
            Integer index = context.get(AgentContext.PARTIAL_ASSISTANT, Integer.class);
            if (index != null && index >= 0 && index < messages.size()) {
                // 用完整响应替换流式过程中边输出边更新的那条
                messages.set(index, message);
                context.remove(AgentContext.PARTIAL_ASSISTANT);
            } else {
                messages.add(message);
            }
        }
        next.run();
    }
}
