package com.yf.wayfarer.rsi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MemorySearcher 单测。
 *
 * @author yyf
 * @description 覆盖分词、L1/L2/L3 词面检索排序、场景命中升温与召回块组装
 * （被动召回块只含 L1）
 */
class MemorySearcherTest {

    @TempDir
    Path temp;

    private MemorySearcher searcher;

    private SceneIndex sceneIndex() {
        return new SceneIndex(new RsiPaths(temp.toString()));
    }

    private MemorySearcher searcher() {
        if (searcher == null) {
            RsiPaths paths = new RsiPaths(temp.toString());
            searcher = new MemorySearcher(paths, properties(), new SceneIndex(paths));
        }
        return searcher;
    }

    private static RsiProperties properties() {
        return new RsiProperties(true, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private Path memoryDir() {
        return Path.of(temp.toString(), "stores", "wayfarer-rsi", "memory");
    }

    @Test
    void 分词提取拉丁词与中文二元组() {
        List<String> tokens = searcher().tokenize("修复 memory_search 游标问题");
        assertTrue(tokens.contains("memory_search"));
        assertTrue(tokens.contains("游标"));
        assertTrue(tokens.contains("问题"), "中文二元组应包含");
        assertFalse(tokens.contains("修复memory"));
    }

    @Test
    void L1按命中与条目分排序() throws Exception {
        writeL1("""
                {"id":"M-1","type":"persona","content":"用户偏好中文注释","tags":["注释","中文"],"score":9,"createdAt":"t","updatedAt":"t","sources":["s-1"]}
                {"id":"M-2","type":"work_fact","content":"测试目录在 test 下","tags":["目录"],"score":2,"createdAt":"t","updatedAt":"t","sources":["s-1"]}
                """);
        List<MemorySearcher.Hit> hits = searcher().search("wayfarer-rsi", "注释", "l1", 10);
        assertEquals(1, hits.size());
        assertEquals("M-1", hits.get(0).id());
        assertTrue(hits.get(0).score() > 0);
    }

    @Test
    void L2命中返回场景正文并升温() throws Exception {
        Path scenesDir = memoryDir().resolve("scenes");
        Files.createDirectories(scenesDir);
        Files.writeString(scenesDir.resolve("build-env.md"), SceneFormat.render(
                        "构建环境", "本会话确定了沉淀规格", SceneFormat.now(), SceneFormat.now(), 0,
                        "本次会话确定了沉淀架构。"),
                StandardCharsets.UTF_8);
        List<MemorySearcher.Hit> hits = searcher().search("wayfarer-rsi", "规格沉淀", "l2", 10);
        assertEquals(1, hits.size());
        assertEquals("build-env.md", hits.get(0).file());
        assertTrue(hits.get(0).content().contains("沉淀架构"));
        // 检索命中即升温：索引条目 heat +1
        assertEquals(1, sceneIndex().load("wayfarer-rsi").get("构建环境").heat());
    }

    @Test
    void L3画像仅在命中时纳入() throws Exception {
        Files.createDirectories(memoryDir());
        Files.writeString(memoryDir().resolve("persona.md"), "## 用户偏好\n- 代码需要中文注释\n",
                StandardCharsets.UTF_8);
        assertEquals(1, searcher().search("wayfarer-rsi", "中文注释", "l3", 10).size());
        assertTrue(searcher().search("wayfarer-rsi", "完全无关词", "l3", 10).isEmpty());
    }

    @Test
    void 被动召回块只含L1() throws Exception {
        writeL1("""
                {"id":"M-1","type":"persona","content":"用户偏好中文注释","tags":["注释"],"score":9,"createdAt":"t","updatedAt":"t","sources":["s-1"]}
                """);
        Files.createDirectories(memoryDir());
        Files.writeString(memoryDir().resolve("persona.md"), "## 用户偏好\n- 中文注释\n", StandardCharsets.UTF_8);
        String block = searcher().recallBlock("wayfarer-rsi", "写代码时的注释偏好", 2000);
        // 分层注入：被动召回只含 L1，L3 全文与 L2 索引由 RsiProfileHandler 装进 system prompt
        assertTrue(block.contains("[L1]"));
        assertTrue(block.contains("中文注释"));
        assertFalse(block.contains("[L3]"));
    }

    @Test
    void 空store召回块为空() {
        assertEquals("", searcher().recallBlock("wayfarer-rsi", "任意查询", 2000));
    }

    private void writeL1(String jsonl) throws Exception {
        Files.createDirectories(memoryDir());
        Files.writeString(memoryDir().resolve("l1.jsonl"), jsonl, StandardCharsets.UTF_8);
    }
}
