package com.yf.wayfarer.agent.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.modelchain.ToolResultRepairHandler;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 工具结果补齐处理器测试。
 *
 * @author yyf
 * @description 验证缺失的工具结果被就地补齐、结果齐全时不改动历史
 */
class ToolResultRepairHandlerTest {

    @Test
    void 缺失的工具结果就地补齐() {
        AgentContext context = new AgentContext();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage(ChatMessage.Role.USER, "查一下"));
        messages.add(ChatMessage.assistant("", null, List.of(
                new ToolCallInfo("call-1", "bash", "{\"command\":\"dir\"}"),
                new ToolCallInfo("call-2", "read", "{\"path\":\"a.txt\"}"))));
        messages.add(new ChatMessage(ChatMessage.Role.TOOL, "目录内容", "call-1"));
        messages.add(new ChatMessage(ChatMessage.Role.USER, "继续"));
        context.put(AgentContext.MESSAGES, messages);

        new ToolResultRepairHandler().run(context, () -> {
        });

        assertEquals(5, messages.size());
        assertEquals(ChatMessage.Role.TOOL, messages.get(3).role(), "占位结果必须紧跟助手消息");
        assertEquals("call-2", messages.get(3).toolCallId());
        assertEquals(ToolResultRepairHandler.PLACEHOLDER, messages.get(3).content());
        assertEquals("继续", messages.get(4).content(), "原有消息顺序保持在后");
    }

    @Test
    void 结果齐全时不插入占位() {
        AgentContext context = new AgentContext();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistant("", null, List.of(new ToolCallInfo("call-1", "bash", "{}"))));
        messages.add(new ChatMessage(ChatMessage.Role.TOOL, "完成", "call-1"));
        context.put(AgentContext.MESSAGES, messages);

        new ToolResultRepairHandler().run(context, () -> {
        });

        assertEquals(2, messages.size());
    }

    @Test
    void 纯文本轮次不做处理() {
        AgentContext context = new AgentContext();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistant("你好", null, List.of()));
        context.put(AgentContext.MESSAGES, messages);

        new ToolResultRepairHandler().run(context, () -> {
        });

        assertEquals(1, messages.size());
    }
}
