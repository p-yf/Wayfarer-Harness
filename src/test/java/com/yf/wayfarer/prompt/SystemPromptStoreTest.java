package com.yf.wayfarer.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统提示词存储测试，基于真实文件系统。
 *
 * @author yyf
 * @description 覆盖文件命名、按顺序拼接、读写与删除，以及 agent 隔离
 */
class SystemPromptStoreTest {

    @TempDir
    Path root;

    @Test
    void 提示词按顺序拼接而不是写入顺序() {
        SystemPromptStore store = new SystemPromptStore(root);
        store.save("wayfarer", "role", 2, "第二段");
        store.save("wayfarer", "role", 1, "第一段");

        assertEquals("第一段\n\n第二段", store.load("wayfarer"));
    }

    @Test
    void 文件名为agent名称提示词名称与顺序() {
        SystemPromptStore store = new SystemPromptStore(root);
        store.save("wayfarer", "role", 3, "内容");

        assertTrue(java.nio.file.Files.exists(root.resolve("wayfarer-role-3.md")));
    }

    @Test
    void 不同agent的提示词互不影响() {
        SystemPromptStore store = new SystemPromptStore(root);
        store.save("wayfarer", "role", 1, "A 的提示词");
        store.save("reviewer", "role", 1, "B 的提示词");

        assertEquals("A 的提示词", store.load("wayfarer"));
        assertEquals("B 的提示词", store.load("reviewer"));
    }

    @Test
    void 读取与删除单个提示词() {
        SystemPromptStore store = new SystemPromptStore(root);
        store.save("wayfarer", "role", 1, "内容");

        assertEquals("内容", store.read("wayfarer", "role", 1));
        assertTrue(store.delete("wayfarer", "role", 1));
        assertFalse(store.delete("wayfarer", "role", 1));
        assertNull(store.read("wayfarer", "role", 1));
        assertTrue(store.load("wayfarer").isEmpty());
    }

    @Test
    void 列出时按顺序升序() {
        SystemPromptStore store = new SystemPromptStore(root);
        store.save("wayfarer", "b", 5, "五");
        store.save("wayfarer", "a", 1, "一");

        List<SystemPromptFile> files = store.list("wayfarer");

        assertEquals(2, files.size());
        assertEquals(1, files.get(0).order());
        assertEquals("一", files.get(0).content());
        assertEquals(5, files.get(1).order());
    }

    @Test
    void 目录不存在时返回空字符串() {
        assertEquals("", new SystemPromptStore(root.resolve("missing")).load("wayfarer"));
    }

    @Test
    void 参数非法时报错() {
        SystemPromptStore store = new SystemPromptStore(root);

        assertThrows(IllegalArgumentException.class, () -> store.save(" ", "role", 1, "内容"));
        assertThrows(IllegalArgumentException.class, () -> store.save("wayfarer", "  ", 1, "内容"));
        assertThrows(IllegalArgumentException.class, () -> store.save("wayfarer", "role", -1, "内容"));
    }
}
