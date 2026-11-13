package com.yf.wayfarer.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卸载消息存储测试，基于真实文件系统。
 *
 * @author yyf
 * @description 覆盖写入、读取、id 列表、单条删除与会话级清理，以及文件命名规则
 */
class UnloadedMessageStoreTest {

    @TempDir
    Path root;

    @Test
    void 每个会话一个同名的jsonl文件() throws Exception {
        UnloadedMessageStore store = new UnloadedMessageStore(root);
        store.save("session-a", "内容一");
        store.save("session-b", "内容二");

        assertTrue(Files.exists(root.resolve("session-a.jsonl")));
        assertTrue(Files.exists(root.resolve("session-b.jsonl")));
        assertEquals(1, Files.readAllLines(root.resolve("session-a.jsonl")).size());
    }

    @Test
    void 保存后可按id读回原文() {
        UnloadedMessageStore store = new UnloadedMessageStore(root);

        String uuid = store.save("session-a", "被卸载的长文本");

        assertEquals("被卸载的长文本", store.read("session-a", uuid));
        assertNull(store.read("session-a", "not-exists"));
    }

    @Test
    void id列表按写入顺序返回() {
        UnloadedMessageStore store = new UnloadedMessageStore(root);
        String first = store.save("session-a", "一");
        String second = store.save("session-a", "二");

        assertEquals(List.of(first, second), store.ids("session-a"));
    }

    @Test
    void 删除单条记录后其余保留() {
        UnloadedMessageStore store = new UnloadedMessageStore(root);
        String first = store.save("session-a", "一");
        String second = store.save("session-a", "二");

        assertTrue(store.delete("session-a", first));
        assertFalse(store.delete("session-a", "not-exists"));

        assertNull(store.read("session-a", first));
        assertEquals("二", store.read("session-a", second));
        assertEquals(List.of(second), store.ids("session-a"));
    }

    @Test
    void 复制会话卸载内容保留原id() {
        UnloadedMessageStore store = new UnloadedMessageStore(root);
        String first = store.save("session-a", "一");
        String second = store.save("session-a", "二");

        store.copyAll("session-a", "session-copy");

        assertEquals("一", store.read("session-copy", first));
        assertEquals("二", store.read("session-copy", second));
        assertEquals(List.of(first, second), store.ids("session-copy"));
        assertEquals(List.of(first, second), store.ids("session-a"), "源会话内容不受影响");
    }

    @Test
    void 源会话没有卸载内容时不生成目标文件() {
        UnloadedMessageStore store = new UnloadedMessageStore(root);

        store.copyAll("session-empty", "session-copy");

        assertFalse(Files.exists(root.resolve("session-copy.jsonl")));
    }

    @Test
    void 会话级清理删除整个文件() {
        UnloadedMessageStore store = new UnloadedMessageStore(root);
        store.save("session-a", "一");

        store.deleteAll("session-a");

        assertFalse(Files.exists(root.resolve("session-a.jsonl")));
        assertTrue(store.ids("session-a").isEmpty());
    }

    @Test
    void 会话id为空时报错() {
        UnloadedMessageStore store = new UnloadedMessageStore(root);

        assertThrows(IllegalArgumentException.class, () -> store.save(" ", "内容"));
        assertThrows(IllegalArgumentException.class, () -> store.ids(null));
    }
}
