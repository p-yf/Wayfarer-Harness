package com.yf.wayfarer.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卸载消息存储测试，基于真实文件系统。
 *
 * @author yyf
 * @description 覆盖卸载文件位置、写入、读取、id 列表、单条删除与复制
 */
class UnloadedMessageStoreTest {

    @TempDir
    Path root;

    @Test
    void 卸载文件位于会话文件夹内() throws IOException {
        String sessionId = createSession("会话一");
        new UnloadedMessageStore(root).save(sessionId, "内容一");

        Path file = sessionDirectory(sessionId).resolve("unloadmessages.jsonl");
        assertTrue(Files.exists(file), "卸载文件应与 session.jsonl 同级");
        assertEquals(1, Files.readAllLines(file).size());
    }

    @Test
    void 保存后可按id读回原文() {
        String sessionId = createSession("会话一");
        UnloadedMessageStore store = new UnloadedMessageStore(root);

        String uuid = store.save(sessionId, "被卸载的长文本");

        assertEquals("被卸载的长文本", store.read(sessionId, uuid));
        assertNull(store.read(sessionId, "not-exists"));
    }

    @Test
    void id列表按写入顺序返回() {
        String sessionId = createSession("会话一");
        UnloadedMessageStore store = new UnloadedMessageStore(root);
        String first = store.save(sessionId, "一");
        String second = store.save(sessionId, "二");

        assertEquals(List.of(first, second), store.ids(sessionId));
    }

    @Test
    void 删除单条记录后其余保留() {
        String sessionId = createSession("会话一");
        UnloadedMessageStore store = new UnloadedMessageStore(root);
        String first = store.save(sessionId, "一");
        String second = store.save(sessionId, "二");

        assertTrue(store.delete(sessionId, first));
        assertFalse(store.delete(sessionId, "not-exists"));

        assertNull(store.read(sessionId, first));
        assertEquals("二", store.read(sessionId, second));
        assertEquals(List.of(second), store.ids(sessionId));
    }

    @Test
    void 复制会话卸载内容保留原id() {
        String from = createSession("源会话");
        String to = createSession("目标会话");
        UnloadedMessageStore store = new UnloadedMessageStore(root);
        String first = store.save(from, "一");
        String second = store.save(from, "二");

        store.copyAll(from, to);

        assertEquals("一", store.read(to, first));
        assertEquals("二", store.read(to, second));
        assertEquals(List.of(first, second), store.ids(to));
        assertEquals(List.of(first, second), store.ids(from), "源会话内容不受影响");
    }

    @Test
    void 源会话没有卸载内容时不生成目标文件() throws IOException {
        String from = createSession("空会话");
        String to = createSession("目标会话");

        new UnloadedMessageStore(root).copyAll(from, to);

        assertFalse(Files.exists(sessionDirectory(to).resolve("unloadmessages.jsonl")));
    }

    @Test
    void 会话id为空时报错() {
        UnloadedMessageStore store = new UnloadedMessageStore(root);

        assertThrows(IllegalArgumentException.class, () -> store.save(" ", "内容"));
        assertThrows(IllegalArgumentException.class, () -> store.ids(null));
    }

    private String createSession(String name) {
        return new JsonlSession(root).create(name);
    }

    private Path sessionDirectory(String sessionId) throws IOException {
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(sessionId + "_"))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
