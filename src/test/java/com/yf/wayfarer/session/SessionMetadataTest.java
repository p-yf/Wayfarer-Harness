package com.yf.wayfarer.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话文件元数据测试，全部基于真实文件系统。
 *
 * @author yyf
 * @description 覆盖创建时的首行 METADATA、改写元数据、元数据不参与对话消息
 */
class SessionMetadataTest {

    @TempDir
    Path root;

    @Test
    void 创建会话时首行写入元数据() throws IOException {
        Session session = new JsonlSession(root);
        String id = session.create("元数据会话");

        Path directory = root.toFile().listFiles()[0].toPath();
        List<String> lines = Files.readAllLines(directory.resolve("session.jsonl"), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith("{\"type\":\"METADATA\""), lines.get(0));
        assertTrue(!lines.get(0).contains("messageId"), "元数据行不应带 messageId：" + lines.get(0));
        assertTrue(!lines.get(0).contains("traceId"), "元数据行不应带 traceId：" + lines.get(0));
        assertTrue(session.messages(id).isEmpty(), "元数据不属于对话消息");
        assertTrue(session.meta(id).metadata().isEmpty());
    }

    @Test
    void 改写元数据后可查询到工作区() {
        Session session = new JsonlSession(root);
        String id = session.create("工作区会话");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workspace", "D:\\work\\demo");
        session.updateMetadata(id, metadata);

        SessionMeta meta = session.meta(id);
        assertEquals("D:\\work\\demo", meta.metadata().get("workspace"));
        assertTrue(session.messages(id).isEmpty());
    }

    @Test
    void 改写元数据不会影响已有消息() {
        Session session = new JsonlSession(root);
        String id = session.create("元数据会话");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "USER");
        content.put("content", "你好");
        session.append(id, JSONMsgType.USER, "turn-1", content);

        session.updateMetadata(id, Map.of("workspace", "/tmp/ws"));

        assertEquals(1, session.messages(id).size());
        assertEquals("你好", session.messages(id).get(0).content().get("content"));
        assertEquals("/tmp/ws", session.meta(id).metadata().get("workspace"));
    }
}
