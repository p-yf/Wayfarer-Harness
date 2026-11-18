package com.yf.wayfarer.loop;

import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.MessageOrigin;
import com.yf.wayfarer.session.SessionMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RsiSedimentHandler 统计逻辑单测（纯函数 statsOf，不触发 LLM/线程池）。
 *
 * @author yyf
 * @description 覆盖 root 对话消息统计、最终回复轮判定、TRACE_END 不计入与子 Agent 消息过滤
 */
class RsiSedimentHandlerTest {

    private static SessionMessage msg(String traceId, JSONMsgType type, String content) {
        return new SessionMessage("m-" + System.nanoTime(), traceId, type, Map.of("content", content));
    }

    @Test
    void 统计root对话量与最终回复轮() {
        List<SessionMessage> messages = List.of(
                msg("t-1", JSONMsgType.USER, "帮我改配置"),
                msg("t-1", JSONMsgType.ASSISTANT, "好的"),
                msg("t-1", JSONMsgType.TOOL, "修改成功"),
                msg("t-1", JSONMsgType.ASSISTANT, "完成"),
                msg("t-1", JSONMsgType.TRACE_END, ""),
                // 其他 trace 的 root 消息计入对话总量
                msg("t-2", JSONMsgType.ASSISTANT, "别的 trace"));
        RsiSedimentHandler.Stats stats = RsiSedimentHandler.statsOf(messages, "t-1");
        assertEquals(5, stats.conversationSize());
        assertTrue(stats.finalReply());
    }

    @Test
    void 无assistant回复不算最终回复轮() {
        List<SessionMessage> messages = List.of(
                msg("t-1", JSONMsgType.USER, "跑测试"),
                msg("t-1", JSONMsgType.TOOL, "error: 连接超时"),
                msg("t-1", JSONMsgType.TRACE_END, ""));
        RsiSedimentHandler.Stats stats = RsiSedimentHandler.statsOf(messages, "t-1");
        assertEquals(2, stats.conversationSize());
        assertFalse(stats.finalReply());
    }

    @Test
    void 子Agent消息不计入() {
        List<SessionMessage> messages = List.of(
                msg("t-1", JSONMsgType.USER, "干活"),
                new SessionMessage("m-sub", "t-1", JSONMsgType.TOOL,
                        Map.of("content", "子Agent工具调用"), new MessageOrigin("child", "via-1", 1)),
                msg("t-1", JSONMsgType.ASSISTANT, "完成"),
                msg("t-1", JSONMsgType.TRACE_END, ""));
        RsiSedimentHandler.Stats stats = RsiSedimentHandler.statsOf(messages, "t-1");
        assertEquals(2, stats.conversationSize());
        assertTrue(stats.finalReply());
    }
}
