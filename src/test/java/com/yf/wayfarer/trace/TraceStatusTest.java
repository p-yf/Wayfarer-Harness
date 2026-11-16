package com.yf.wayfarer.trace;

import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.JsonlSession;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.SessionMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话准入与崩溃恢复测试，使用真实会话文件。
 *
 * @author yyf
 * @description 覆盖同一会话的互斥准入，以及进程中断后为悬空 trace 补齐终态
 */
class TraceStatusTest {

    @TempDir
    Path root;

    @Test
    void 同一会话同时只允许一轮运行() {
        Session session = new JsonlSession(root);
        String sessionId = session.create("准入");
        TraceStatus status = new TraceStatus(session);

        assertTrue(status.tryStart(sessionId, "trace-1"));
        assertFalse(status.tryStart(sessionId, "trace-2"), "同一会话不能并发两轮");
        assertTrue(status.isRunning(sessionId));
        assertEquals("trace-1", status.runningTrace(sessionId));

        status.finish(sessionId, "trace-1");
        assertFalse(status.isRunning(sessionId));
        assertTrue(status.tryStart(sessionId, "trace-2"));
    }

    @Test
    void 崩溃恢复为没有结束标记的trace补齐aborted() {
        Session session = new JsonlSession(root);
        String sessionId = session.create("恢复");
        session.append(sessionId, JSONMsgType.USER, "trace-1", Map.of("role", "USER", "content", "问"));
        session.append(sessionId, JSONMsgType.TRACE_END, "trace-2", Map.of("status", "completed"));

        TraceStatus status = new TraceStatus(session);
        status.ensureRecovered(sessionId, null);

        List<SessionMessage> messages = session.messages(sessionId);
        List<SessionMessage> ends = messages.stream()
                .filter(message -> message.type() == JSONMsgType.TRACE_END)
                .toList();
        assertEquals(2, ends.size(), "只应为缺少结束标记的 trace-1 补一条");
        SessionMessage recovered = ends.stream()
                .filter(message -> "trace-1".equals(message.traceId()))
                .findFirst()
                .orElseThrow();
        assertEquals("aborted", recovered.content().get("status"));
        assertEquals(1, ends.stream().filter(message -> "trace-2".equals(message.traceId())).count(),
                "已有终态的 trace 不能重复追加");
    }

    @Test
    void 当前即将开始的trace不参与恢复() {
        Session session = new JsonlSession(root);
        String sessionId = session.create("当前轮");
        session.append(sessionId, JSONMsgType.USER, "trace-now", Map.of("role", "USER", "content", "问"));

        TraceStatus status = new TraceStatus(session);
        status.ensureRecovered(sessionId, "trace-now");

        assertTrue(session.messages(sessionId).stream()
                        .noneMatch(message -> message.type() == JSONMsgType.TRACE_END),
                "当前轮的用户消息不能把自己误判为悬空");
    }

    @Test
    void 同一进程内每个会话只恢复一次() {
        Session session = new JsonlSession(root);
        String sessionId = session.create("只恢复一次");
        session.append(sessionId, JSONMsgType.USER, "trace-1", Map.of("role", "USER", "content", "问"));

        TraceStatus status = new TraceStatus(session);
        status.ensureRecovered(sessionId, null);
        status.ensureRecovered(sessionId, null);

        long ends = session.messages(sessionId).stream()
                .filter(message -> message.type() == JSONMsgType.TRACE_END)
                .count();
        assertEquals(1, ends);
    }
}
