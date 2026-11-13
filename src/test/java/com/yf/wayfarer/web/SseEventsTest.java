package com.yf.wayfarer.web;

import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.FinishReason;
import com.yf.wayfarer.model.Usage;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * agent 消息到 sse 事件的映射测试。
 *
 * @author yyf
 * @description 校验事件名与数据：数据直接复用消息本身，web 层不再定义消息结构
 */
class SseEventsTest {

    private static final ToolCallInfo CALL = new ToolCallInfo("call_1", "read_file", "{\"path\":\"a\"}");

    @Test
    void 文本增量映射为delta事件() {
        ServerSentEvent<Object> event = single(new AgentMessage.Text("你好"));

        assertEquals("delta", event.event());
        assertEquals(new AgentMessage.Text("你好"), event.data());
    }

    @Test
    void 思考增量映射为reasoning事件() {
        ServerSentEvent<Object> event = single(new AgentMessage.Reasoning("先想一想"));

        assertEquals("reasoning", event.event());
        assertEquals(new AgentMessage.Reasoning("先想一想"), event.data());
    }

    @Test
    void 工具调用映射为toolCall事件并复用工具调用信息() {
        ServerSentEvent<Object> event = single(new AgentMessage.ToolCall(CALL));

        assertEquals("tool_call", event.event());
        assertEquals(CALL, event.data());
    }

    @Test
    void 工具结果映射为toolResult事件() {
        ServerSentEvent<Object> event = single(new AgentMessage.ToolResult("call_1", "文件内容"));

        assertEquals("tool_result", event.event());
        assertEquals(new AgentMessage.ToolResult("call_1", "文件内容"), event.data());
    }

    @Test
    void 收尾消息映射为messageEnd事件() {
        AgentMessage message = new AgentMessage.End(FinishReason.STOP, new Usage(10L, 20L, 30L, 5L, 5L));

        ServerSentEvent<Object> event = single(message);

        assertEquals("message_end", event.event());
        assertEquals(message, event.data());
    }

    private static ServerSentEvent<Object> single(AgentMessage message) {
        List<ServerSentEvent<Object>> events = SseEvents.of(message).collectList().block();
        assertEquals(1, events == null ? 0 : events.size());
        return events.get(0);
    }
}
