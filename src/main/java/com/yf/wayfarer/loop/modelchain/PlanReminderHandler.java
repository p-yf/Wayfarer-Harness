package com.yf.wayfarer.loop.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.tools.PlanTools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划提醒处理器。
 *
 * @author yyf
 * @description 挂在模型链上，位于用户请求入历史之后：若有计划则在用户消息末尾追加提醒，
 * 有执行中的计划项时再列出具体内容；提醒只存在于本次请求，不写入会话文件
 */
public class PlanReminderHandler implements ChainHandler {

    public static final String REMINDER = "<sys-plan提醒：当前有计划不要遗漏>";

    private static final String DOING_TITLE = "执行中的计划：";

    @Override
    public int sort() {
        // 用户请求入历史之后；记忆压缩之后，避免提醒被写进压缩快照而长期留在历史里
        return -90;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        remind(context);
        next.run();
    }

    private static void remind(AgentContext context) {
        Map<String, Map<String, String>> plan = planOf(context);
        if (plan.isEmpty()) {
            return;
        }
        List<ChatMessage> messages = context.messages();
        if (messages == null) {
            return;
        }
        int index = lastUserIndex(messages);
        if (index < 0) {
            return;
        }
        ChatMessage user = messages.get(index);
        // 同一次运行内会多次调用模型，已经提醒过就不再重复追加
        if (user.content().contains(REMINDER)) {
            return;
        }
        String content = user.content().isEmpty()
                ? render(plan)
                : user.content() + "\n\n" + render(plan);
        messages.set(index, new ChatMessage(user.role(), content, user.toolCallId(),
                user.reasoningContent(), user.toolCalls()));
    }

    private static String render(Map<String, Map<String, String>> plan) {
        StringBuilder text = new StringBuilder(REMINDER);
        List<String> doing = new ArrayList<>();
        for (String seq : sortedKeys(plan)) {
            Map<String, String> item = plan.get(seq);
            if (PlanTools.DOING.equals(item.getOrDefault("status", PlanTools.NOT_BEGIN))) {
                doing.add(seq + ". " + item.getOrDefault("content", ""));
            }
        }
        if (!doing.isEmpty()) {
            text.append('\n').append(DOING_TITLE);
            for (String line : doing) {
                text.append('\n').append(line);
            }
        }
        return text.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> planOf(AgentContext context) {
        if (!(context.get(AgentContext.PLAN) instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Map<String, String>> plan = new java.util.LinkedHashMap<>();
        raw.forEach((seq, value) -> {
            if (value instanceof Map<?, ?> item) {
                Map<String, String> copy = new java.util.LinkedHashMap<>();
                item.forEach((key, text) -> copy.put(String.valueOf(key), String.valueOf(text)));
                plan.put(String.valueOf(seq), copy);
            }
        });
        return plan;
    }

    private static int lastUserIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == ChatMessage.Role.USER) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> sortedKeys(Map<String, ?> plan) {
        List<String> keys = new ArrayList<>(plan.keySet());
        boolean numeric = !keys.isEmpty() && keys.stream().allMatch(key -> key.chars().allMatch(Character::isDigit));
        if (numeric) {
            keys.sort(Comparator.comparingLong(Long::parseLong));
        } else {
            keys.sort(Comparator.naturalOrder());
        }
        return keys;
    }
}
