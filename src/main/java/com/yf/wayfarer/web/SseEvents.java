package com.yf.wayfarer.web;

import com.yf.wayfarer.message.AgentMessage;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * agent 消息到 sse 事件的适配。
 *
 * @author yyf
 * @description 只负责给消息补充事件名，data 直接复用消息本身，不在 web 层重复定义消息结构
 */
public final class SseEvents {

    private SseEvents() {
    }

    /**
     * 把一条 agent 消息转换为 sse 事件：Text 为 delta、Reasoning 为 reasoning、
     * ToolCall 为 tool_call、ToolResult 为 tool_result、End 为 message_end。
     */
    public static Flux<ServerSentEvent<Object>> of(AgentMessage message) {
        return switch (message) {
            case AgentMessage.Text text -> Flux.just(event("delta", text));
            case AgentMessage.Reasoning reasoning -> Flux.just(event("reasoning", reasoning));
            case AgentMessage.ToolCall call -> Flux.just(event("tool_call", call.call()));
            case AgentMessage.ToolResult result -> Flux.just(event("tool_result", result));
            case AgentMessage.End end -> Flux.just(event("message_end", end));
            case AgentMessage.Other other -> Flux.just(event("other", other.message()));
        };
    }

    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder(data).event(name).build();
    }
}
