package com.yf.wayfarer.session;

import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.toolcall.ToolCallInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话文件中的一行消息。
 *
 * @author yyf
 * @description 保存消息 id、所属轮次 id、消息类型与 JSON 内容，并提供与 ChatMessage 的互转
 */
public record SessionMessage(String messageId, String turnId, MessageType type, Map<String, Object> content) {

    public SessionMessage {
        content = content == null ? Map.of() : content;
    }

    /**
     * 把对话消息转换为可写入会话文件的 JSON 内容。
     */
    public static Map<String, Object> toMap(ChatMessage message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", message.role().name());
        map.put("content", message.content());
        if (message.toolCallId() != null) {
            map.put("toolCallId", message.toolCallId());
        }
        if (message.reasoningContent() != null) {
            map.put("reasoningContent", message.reasoningContent());
        }
        if (!message.toolCalls().isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolCallInfo info : message.toolCalls()) {
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", info.id());
                call.put("name", info.name());
                call.put("arguments", info.arguments());
                calls.add(call);
            }
            map.put("toolCalls", calls);
        }
        return map;
    }

    /**
     * 从会话文件的 JSON 内容还原对话消息。
     */
    @SuppressWarnings("unchecked")
    public static ChatMessage toChatMessage(Map<String, Object> map) {
        ChatMessage.Role role = ChatMessage.Role.valueOf(String.valueOf(map.get("role")));
        String content = map.get("content") == null ? "" : String.valueOf(map.get("content"));
        String toolCallId = map.get("toolCallId") == null ? null : String.valueOf(map.get("toolCallId"));
        String reasoning = map.get("reasoningContent") == null ? null : String.valueOf(map.get("reasoningContent"));
        List<ToolCallInfo> toolCalls = new ArrayList<>();
        Object rawCalls = map.get("toolCalls");
        if (rawCalls instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> call = (Map<String, Object>) item;
                toolCalls.add(new ToolCallInfo(
                        String.valueOf(call.get("id")),
                        String.valueOf(call.get("name")),
                        String.valueOf(call.get("arguments"))));
            }
        }
        return new ChatMessage(role, content, toolCallId, reasoning, toolCalls);
    }
}
