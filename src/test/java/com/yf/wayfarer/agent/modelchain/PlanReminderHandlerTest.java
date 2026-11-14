package com.yf.wayfarer.agent.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.tools.PlanTools;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划提醒处理器测试。
 *
 * @author yyf
 * @description 验证有计划时追加提醒、有执行中计划时列出内容、无计划时不改动用户消息
 */
class PlanReminderHandlerTest {

    @Test
    void 没有计划时不追加提醒() {
        List<ChatMessage> messages = new ArrayList<>(List.of(new ChatMessage(ChatMessage.Role.USER, "你好")));
        AgentContext context = contextOf(messages);

        new PlanReminderHandler().run(context, () -> {
        });

        assertEquals("你好", messages.get(0).content());
    }

    @Test
    void 有计划时追加提醒并列出执行中的计划() {
        List<ChatMessage> messages = new ArrayList<>(List.of(new ChatMessage(ChatMessage.Role.USER, "继续")));
        AgentContext context = contextOf(messages);
        context.put(AgentContext.PLAN, plan(
                Map.of("content", "读取日志", "status", PlanTools.DOING),
                Map.of("content", "修复问题", "status", PlanTools.NOT_BEGIN)));

        new PlanReminderHandler().run(context, () -> {
        });

        String content = messages.get(0).content();
        assertTrue(content.startsWith("继续"), content);
        assertTrue(content.contains(PlanReminderHandler.REMINDER), content);
        assertTrue(content.contains("执行中的计划："), content);
        assertTrue(content.contains("1. 读取日志"), content);
        assertFalse(content.contains("修复问题"), "未开始的事项不必列出：" + content);
    }

    @Test
    void 计划存在但没有执行中的事项时只给提醒() {
        List<ChatMessage> messages = new ArrayList<>(List.of(new ChatMessage(ChatMessage.Role.USER, "继续")));
        AgentContext context = contextOf(messages);
        context.put(AgentContext.PLAN, plan(
                Map.of("content", "修复问题", "status", PlanTools.NOT_BEGIN)));

        new PlanReminderHandler().run(context, () -> {
        });

        String content = messages.get(0).content();
        assertTrue(content.contains(PlanReminderHandler.REMINDER), content);
        assertFalse(content.contains("执行中的计划："), content);
    }

    @Test
    void 同一次运行内不重复追加提醒() {
        List<ChatMessage> messages = new ArrayList<>(List.of(new ChatMessage(ChatMessage.Role.USER, "继续")));
        AgentContext context = contextOf(messages);
        context.put(AgentContext.PLAN, plan(Map.of("content", "读取日志", "status", PlanTools.DOING)));

        PlanReminderHandler handler = new PlanReminderHandler();
        handler.run(context, () -> {
        });
        handler.run(context, () -> {
        });

        String content = messages.get(0).content();
        assertEquals(content.indexOf(PlanReminderHandler.REMINDER),
                content.lastIndexOf(PlanReminderHandler.REMINDER), content);
    }

    private static AgentContext contextOf(List<ChatMessage> messages) {
        AgentContext context = new AgentContext();
        context.put(AgentContext.MESSAGES, messages);
        return context;
    }

    @SafeVarargs
    private static Map<String, Map<String, String>> plan(Map<String, String>... items) {
        Map<String, Map<String, String>> plan = new LinkedHashMap<>();
        int seq = 1;
        for (Map<String, String> item : items) {
            plan.put(String.valueOf(seq++), new LinkedHashMap<>(item));
        }
        return plan;
    }
}
