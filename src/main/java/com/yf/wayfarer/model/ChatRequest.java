package com.yf.wayfarer.model;

import com.yf.wayfarer.toolcall.ToolDefinition;

import java.util.List;

/**
 * 与供应商无关的对话请求。
 *
 * @author yyf
 * @description 承载消息列表与可用工具定义，消息列表不可为空
 */
public record ChatRequest(List<ChatMessage> messages, List<ToolDefinition> tools) {

    public ChatRequest {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("消息列表不能为空");
        }
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public ChatRequest(List<ChatMessage> messages) {
        this(messages, List.of());
    }
}
