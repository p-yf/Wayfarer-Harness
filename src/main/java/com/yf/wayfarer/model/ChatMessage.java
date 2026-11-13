package com.yf.wayfarer.model;

import com.yf.wayfarer.toolcall.ToolCallInfo;

import java.util.List;

/**
 * 与供应商无关的单条对话消息。
 *
 * @author yyf
 * @description 承载角色、内容与思考内容，tool 角色必须携带 toolCallId，assistant 角色可回放工具调用
 */
public record ChatMessage(Role role, String content, String toolCallId,
                          String reasoningContent, List<ToolCallInfo> toolCalls) {

    /**
     * @author yyf
     * @description 消息角色
     */
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public ChatMessage {
        if (role == null) {
            throw new IllegalArgumentException("role 不能为空");
        }
        content = content == null ? "" : content;
        if (role == Role.TOOL && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("tool 消息必须提供 toolCallId");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public ChatMessage(Role role, String content) {
        this(role, content, null);
    }

    public ChatMessage(Role role, String content, String toolCallId) {
        this(role, content, toolCallId, null, null);
    }

    /**
     * 构造 assistant 消息，用于把上一轮模型响应回放到消息历史。
     * reasoningContent 是模型本轮的完整思考内容，作为记录随会话落盘；
     * 是否回传给供应商由具体模型适配器决定。
     */
    public static ChatMessage assistant(String content, String reasoningContent, List<ToolCallInfo> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, content, null, reasoningContent, toolCalls);
    }
}
