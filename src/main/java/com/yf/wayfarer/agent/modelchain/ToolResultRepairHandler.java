package com.yf.wayfarer.agent.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具结果补齐处理器。
 *
 * @author yyf
 * @description 调用模型前检查历史：若某条 assistant 的 tool_calls 缺少对应工具结果
 * （例如运行被终止），就地插入占位结果，避免供应商报错。占位只用于本次请求，不写入会话
 */
public class ToolResultRepairHandler implements ChainHandler {

    private static final Logger log = LoggerFactory.getLogger(ToolResultRepairHandler.class);

    public static final String PLACEHOLDER = "<sys-未得到工具调用结果，占位防止报错>";

    @Override
    public int sort() {
        // 先于会话持久化处理器：补齐后的历史长度不再变化，落盘下标才不会错位
        return -70;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        repair(context);
        next.run();
    }

    private static void repair(AgentContext context) {
        List<ChatMessage> messages = context.messages();
        if (messages == null) {
            return;
        }        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message.role() != ChatMessage.Role.ASSISTANT || message.toolCalls().isEmpty()) {
                continue;
            }
            Set<String> missing = new LinkedHashSet<>();
            for (ToolCallInfo call : message.toolCalls()) {
                missing.add(call.id());
            }
            // 工具结果必须紧跟在发起调用的 assistant 之后
            int end = i + 1;
            while (end < messages.size() && messages.get(end).role() == ChatMessage.Role.TOOL) {
                missing.remove(messages.get(end).toolCallId());
                end++;
            }
            if (missing.isEmpty()) {
                continue;
            }
            List<ChatMessage> placeholders = new ArrayList<>(missing.size());
            for (String id : missing) {
                placeholders.add(new ChatMessage(ChatMessage.Role.TOOL, PLACEHOLDER, id));
            }
            messages.addAll(end, placeholders);
            log.warn("补齐缺失的工具结果 数量={}", placeholders.size());
            i = end + placeholders.size() - 1;
        }
    }
}
