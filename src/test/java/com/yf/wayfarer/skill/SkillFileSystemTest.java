package com.yf.wayfarer.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能目录文件系统测试，基于真实目录与文件。
 *
 * @author yyf
 * @description 覆盖目录浏览、进入子目录、文件读写、建目录、删除与越界保护
 */
class SkillFileSystemTest {

    @TempDir
    Path root;

    @Test
    void 列出根目录时目录排在文件前面() {
        SkillFileSystem files = new SkillFileSystem(root);
        files.mkdir("review");
        files.write("notes.md", "内容");

        SkillFileSystem.Tree tree = files.list("");

        assertEquals("", tree.path());
        assertNull(tree.parent());
        List<SkillFileSystem.Entry> entries = tree.entries();
        assertEquals(2, entries.size());
        assertTrue(entries.get(0).directory());
        assertEquals("review", entries.get(0).name());
        assertEquals("review", entries.get(0).path());
        assertFalse(entries.get(1).directory());
        assertEquals("notes.md", entries.get(1).path());
    }

    @Test
    void 可以进入子目录并返回上一级() {
        SkillFileSystem files = new SkillFileSystem(root);
        files.mkdir("review/references");
        files.write("review/SKILL.md", "正文");

        SkillFileSystem.Tree tree = files.list("review");
        assertEquals("review", tree.path());
        assertEquals("", tree.parent());
        assertEquals("review/references", tree.entries().get(0).path());

        SkillFileSystem.Tree nested = files.list("review/references");
        assertEquals("review/references", nested.path());
        assertEquals("review", nested.parent());
        assertTrue(nested.entries().isEmpty());
    }

    @Test
    void 写入自动创建父目录并可读回() {
        SkillFileSystem files = new SkillFileSystem(root);

        files.write("a/b/SKILL.md", "第一行");

        assertEquals("第一行", files.read("a/b/SKILL.md"));
        assertTrue(files.exists("a/b/SKILL.md"));
    }

    @Test
    void 目录与文件同名时拒绝创建() {
        SkillFileSystem files = new SkillFileSystem(root);
        files.write("same", "文件");

        assertThrows(IllegalArgumentException.class, () -> files.mkdir("same"));
    }

    @Test
    void 删除目录会递归清理() throws IOException {
        SkillFileSystem files = new SkillFileSystem(root);
        files.write("review/references/guide.md", "附加文件");
        Path directory = root.resolve("review");
        Files.writeString(directory.resolve("SKILL.md"), "正文", StandardCharsets.UTF_8);

        assertTrue(files.delete("review"));
        assertFalse(Files.exists(directory));
        assertFalse(files.delete("review"));
    }

    @Test
    void 拒绝越界路径并保护根目录() {
        SkillFileSystem files = new SkillFileSystem(root);

        assertThrows(IllegalArgumentException.class, () -> files.list("../outside"));
        assertThrows(IllegalArgumentException.class, () -> files.write("../outside.md", "x"));
        assertThrows(IllegalArgumentException.class, () -> files.delete(""));
    }

    @Test
    void 读取不存在的文件报错() {
        SkillFileSystem files = new SkillFileSystem(root);

        assertThrows(IllegalArgumentException.class, () -> files.read("missing.md"));
    }
}
