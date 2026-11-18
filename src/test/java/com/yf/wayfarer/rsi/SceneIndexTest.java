package com.yf.wayfarer.rsi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SceneIndex 单测。
 *
 * @author yyf
 * @description 覆盖索引重建/保存/加载往返、缺失目录空表、loadOrRebuild、
 * 热度递增（文件 META 与索引同步）与 refs 列表
 */
class SceneIndexTest {

    @TempDir
    Path temp;

    private SceneIndex index() {
        return new SceneIndex(new RsiPaths(temp.toString()));
    }

    private Path scenesDir() {
        return Path.of(temp.toString(), "stores", "wayfarer-rsi", "memory", "scenes");
    }

    private void writeScene(String fileName, String name, String summary) throws Exception {
        Files.createDirectories(scenesDir());
        Files.writeString(scenesDir().resolve(fileName),
                SceneFormat.render(name, summary, SceneFormat.now(), SceneFormat.now(), 0,
                        "# " + name + "\n\n正文内容"),
                StandardCharsets.UTF_8);
    }

    @Test
    void 缺失目录返回空索引() {
        assertTrue(index().load("wayfarer-rsi").isEmpty());
        assertTrue(index().rebuild("wayfarer-rsi").isEmpty());
        assertTrue(index().refs("wayfarer-rsi").isEmpty());
    }

    @Test
    void 重扫重建以文件为事实源() throws Exception {
        writeScene("a-build.md", "构建环境", "构建相关约定");
        writeScene("b-debug.md", "调试方法", "调试踩坑经验");
        Map<String, SceneIndex.Entry> rebuilt = index().rebuild("wayfarer-rsi");
        assertEquals(2, rebuilt.size());
        assertEquals("a-build.md", rebuilt.get("构建环境").file());
        assertEquals("构建相关约定", rebuilt.get("构建环境").summary());
    }

    @Test
    void 保存加载往返() throws Exception {
        writeScene("a-build.md", "构建环境", "构建相关约定");
        SceneIndex index = index();
        index.save("wayfarer-rsi", index.rebuild("wayfarer-rsi"));
        Map<String, SceneIndex.Entry> loaded = index.load("wayfarer-rsi");
        assertEquals(1, loaded.size());
        assertNotNull(loaded.get("构建环境"));
    }

    @Test
    void 热度递增同步文件与索引() throws Exception {
        writeScene("a-build.md", "构建环境", "构建相关约定");
        SceneIndex index = index();
        index.save("wayfarer-rsi", index.rebuild("wayfarer-rsi"));
        index.incrementHeat("wayfarer-rsi", "构建环境");
        assertEquals(1, index.load("wayfarer-rsi").get("构建环境").heat());
        // 文件 META 同步
        SceneFormat.SceneFile scene = SceneFormat.parse(
                Files.readString(scenesDir().resolve("a-build.md"), StandardCharsets.UTF_8),
                "a-build.md");
        assertEquals(1, scene.heat());
    }

    @Test
    void refs返回名与摘要() throws Exception {
        writeScene("a-build.md", "构建环境", "构建相关约定");
        var refs = index().refs("wayfarer-rsi");
        assertEquals(1, refs.size());
        assertEquals("构建环境", refs.get(0).name());
        assertEquals("构建相关约定", refs.get(0).summary());
    }
}
