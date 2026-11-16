package com.yf.wayfarer.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 会话读取容错与回放测试，基于真实文件。
 *
 * @author yyf
 * @description 覆盖尾行截断跳过、中间行损坏报错，以及子 Agent 消息归属的持久化与历史过滤
 */
class JsonlSessionToleranceTest {

    @TempDir
    Path root;

    @Test
    void 尾行解析失败只跳过该行() throws IOException {
        Session session = new JsonlSession(root);
        String sessionId = session.create("尾行容错");
        session.append(sessionId, JSONMsgType.USER, "trace-1", Map.of("role", "USER", "content", "第一问"));
        session.append(sessionId, JSONMsgType.ASSISTANT, "trace-1",
                Map.of("role", "ASSISTANT", "content", "第一答"));

        // 模拟宕机：最后一行只写了一半，且没有换行
        Files.writeString(mainFile(sessionId), "{\"type\":\"ASSIST", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        assertEquals(2, session.messages(sessionId).size());
        assertEquals(2, session.history(sessionId).size());
    }

    @Test
    void 尾行断在多字节字符中间时同样跳过() throws IOException {
        Session session = new JsonlSession(root);
        String sessionId = session.create("字符截断");
        session.append(sessionId, JSONMsgType.USER, "trace-1", Map.of("role", "USER", "content", "第一问"));

        byte[] line = "{\"type\":\"USER\",\"content\":\"中文内容\"}".getBytes(StandardCharsets.UTF_8);
        // 砍掉最后一个汉字的尾部字节，使整行无法按 UTF-8 解码
        byte[] truncated = Arrays.copyOf(line, line.length - 2);
        Files.write(mainFile(sessionId), truncated, StandardOpenOption.APPEND);

        assertEquals(1, session.messages(sessionId).size());
    }

    @Test
    void 中间行损坏仍然抛出解析错误() throws IOException {
        Session session = new JsonlSession(root);
        String sessionId = session.create("中间损坏");
        session.append(sessionId, JSONMsgType.USER, "trace-1", Map.of("role", "USER", "content", "第一问"));
        Files.writeString(mainFile(sessionId), "这不是一行JSON" + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        session.append(sessionId, JSONMsgType.ASSISTANT, "trace-1",
                Map.of("role", "ASSISTANT", "content", "第一答"));

        assertThrows(IllegalStateException.class, () -> session.messages(sessionId),
                "损坏行不在尾部时必须暴露出来，不能静默丢弃");
    }

    @Test
    void 子Agent消息保留归属且不进入模型历史() {
        Session session = new JsonlSession(root);
        String sessionId = session.create("归属回放");
        MessageOrigin origin = new MessageOrigin("sub-7f3a", "call-1", 1);
        session.append(sessionId, JSONMsgType.USER, "trace-1",
                Map.of("role", "USER", "content", "子任务"), origin);
        session.append(sessionId, JSONMsgType.ASSISTANT, "trace-1",
                Map.of("role", "ASSISTANT", "content", "子答案"));

        List<SessionMessage> messages = session.messages(sessionId);
        assertEquals(2, messages.size());
        assertEquals(origin, messages.get(0).origin(), "归属应原样写回并读出");
        assertNull(messages.get(1).origin());
        assertEquals(1, session.history(sessionId).size(), "只有 root 的消息进入模型历史");
        assertEquals("子答案", session.history(sessionId).get(0).content());
    }

    private Path mainFile(String sessionId) throws IOException {
        try (var entries = Files.list(root)) {
            Path directory = entries.filter(Files::isDirectory).findFirst().orElseThrow();
            return directory.resolve("session.jsonl");
        }
    }
}
