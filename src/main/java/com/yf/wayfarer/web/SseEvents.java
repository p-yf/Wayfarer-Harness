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
     * ToolCall 为 tool_call、ToolResult 为 tool_result、End 为 message_end、Other 为 other。
     */
    public static Flux<ServerSentEvent<Object>> of(AgentMessage message) {
        return switch (message) {
            case AgentMessage.Text text -> Flux.just(event("delta", text));
            case AgentMessage.Reasoning reasoning -> Flux.just(event("reasoning", reasoning));
            case AgentMessage.ToolCall call -> Flux.just(event("tool_call", call));
            case AgentMessage.ToolResult result -> Flux.just(event("tool_result", result));
            case AgentMessage.End end -> Flux.just(event("message_end", end));
            case AgentMessage.TraceData trace -> Flux.just(event("trace_data", trace));
            case AgentMessage.Other other -> Flux.just(event("other", other.message()));
        };
    }

    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder(data).event(name).build();
    }

    /**
     * 运行期异常转成一条 other 事件。
     * 流已经开始写出后无法再改 http 状态，直接抛给框架会让连接被掐断，前端只看到「连接中断」。
     */
    public static ServerSentEvent<Object> error(Throwable error) {
        String detail = error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
        return event("other", "出错：" + detail);
    }
}
