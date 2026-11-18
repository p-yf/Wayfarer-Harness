package com.yf.wayfarer.rsi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RsiPaths 单测。
 *
 * @author yyf
 * @description 覆盖 agent/kind 白名单校验、store 子树路径解析与各类穿越拒绝
 */
class RsiPathsTest {

    @TempDir
    Path temp;

    private RsiPaths paths() {
        return new RsiPaths(temp.resolve("rsi").toString());
    }

    @Test
    void layoutDirectoriesAreUnderRoot() {
        RsiPaths paths = paths();
        assertEquals(temp.resolve("rsi/stores/wayfarer-rsi/skills").normalize(),
                paths.skillsDir("wayfarer-rsi").normalize());
        assertEquals(temp.resolve("rsi/stores/wayfarer-rsi/memory").normalize(),
                paths.memoryDir("wayfarer-rsi").normalize());
        assertEquals(temp.resolve("rsi/git/wayfarer-rsi/skill.git").normalize(),
                paths.gitDir("wayfarer-rsi", "skill").normalize());
        assertEquals(temp.resolve("rsi/state/wayfarer-rsi").normalize(),
                paths.stateDir("wayfarer-rsi").normalize());
    }

    @Test
    void kindMapsToStoreDirectory() {
        RsiPaths paths = paths();
        assertEquals(paths.skillsDir("wayfarer-rsi"), paths.kindDir("wayfarer-rsi", "skill"));
        assertEquals(paths.memoryDir("wayfarer-rsi"), paths.kindDir("wayfarer-rsi", "memory"));
    }

    @Test
    void unknownKindRejected() {
        assertThrows(IllegalArgumentException.class, () -> paths().kind("prompt"));
        assertThrows(IllegalArgumentException.class, () -> paths().kind(null));
    }

    @Test
    void agentNamePatternEnforced() {
        assertEquals("wayfarer-rsi", paths().agent("wayfarer-rsi"));
        // 盘符、路径分隔符、空串都会被 agent 校验挡下
        assertThrows(IllegalArgumentException.class, () -> paths().agent("../x"));
        assertThrows(IllegalArgumentException.class, () -> paths().agent("a/b"));
        assertThrows(IllegalArgumentException.class, () -> paths().agent(""));
        assertThrows(IllegalArgumentException.class, () -> paths().agent(null));
    }

    @Test
    void resolveInStoreAcceptsNormalRelativePath() {
        RsiPaths paths = paths();
        Path resolved = paths.resolveInStore("wayfarer-rsi", "skill", "pdf-edit/SKILL.md");
        assertEquals(paths.skillsDir("wayfarer-rsi").resolve("pdf-edit/SKILL.md").normalize(), resolved);
    }

    @Test
    void traversalRejected() {
        RsiPaths paths = paths();
        // 相对穿越
        assertThrows(IllegalArgumentException.class,
                () -> paths.resolveInStore("wayfarer-rsi", "skill", "../memory/l1.jsonl"));
        // 绝对路径
        assertThrows(IllegalArgumentException.class,
                () -> paths.resolveInStore("wayfarer-rsi", "skill", temp.resolve("x").toString()));
        // 盘符
        assertThrows(IllegalArgumentException.class,
                () -> paths.resolveInStore("wayfarer-rsi", "skill", "C:/Windows"));
        // 空路径
        assertThrows(IllegalArgumentException.class,
                () -> paths.resolveInStore("wayfarer-rsi", "skill", "  "));
    }

    @Test
    void rootIsNormalizedAbsolute() {
        assertTrue(paths().root().isAbsolute());
    }
}
