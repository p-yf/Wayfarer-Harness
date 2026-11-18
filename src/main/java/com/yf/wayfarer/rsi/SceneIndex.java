package com.yf.wayfarer.rsi;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L2 场景索引（scene_index.json 摘要索引 + 以文件为事实源的重扫同步）。
 *
 * <p>场景库 = {@code stores/<agent>/memory/scenes/*.md}（一个主题场景一个文件）+
 * 旁路索引 {@code scenes/scene_index.json}（场景名 → 文件名/摘要/更新时间/热度）。
 * 索引由文件重扫重建——文件是事实源，索引只是加速读取的缓存，任何时候可丢弃重建。</p>
 *
 * <p>写入用「临时文件 + 原子移动」，与游标簿记同一套防损坏策略。</p>
 *
 * @author yyf
 * @description load()：读索引（缺失/损坏返回空表）；rebuild()：重扫场景文件重建索引；
 * save()：原子落盘；incrementHeat()：检索命中时文件 META 与索引热度同步 +1；
 * scenesDir()：场景库目录
 */
@Service
public class SceneIndex {

    private static final Logger log = LoggerFactory.getLogger(SceneIndex.class);
    private static final String INDEX_FILE = "scene_index.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RsiPaths paths;

    public SceneIndex(RsiPaths paths) {
        this.paths = paths;
    }

    /**
     * 场景库目录：stores/&lt;agent&gt;/memory/scenes/。
     */
    public Path scenesDir(String agent) {
        return paths.memoryDir(agent).resolve("scenes");
    }

    /**
     * 读取旁路索引；文件缺失或损坏时返回空表（调用方应改走 rebuild）。
     */
    public Map<String, Entry> load(String agent) {
        Path file = scenesDir(agent).resolve(INDEX_FILE);
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            IndexFile index = MAPPER.readValue(file.toFile(), IndexFile.class);
            return index == null || index.scenes == null ? new LinkedHashMap<>() : index.scenes;
        } catch (IOException e) {
            log.warn("RSI 场景索引读取失败，按空索引处理 agent={}", agent, e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * 重扫场景目录重建索引（文件是事实源；损坏行跳过不拖垮整体）。
     */
    public Map<String, Entry> rebuild(String agent) {
        Map<String, Entry> index = new LinkedHashMap<>();
        Path dir = scenesDir(agent);
        if (!Files.isDirectory(dir)) {
            return index;
        }
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.filter(f -> f.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(f -> f.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("扫描场景目录失败：" + dir, e);
        }
        for (Path file : files) {
            try {
                SceneFormat.SceneFile scene = SceneFormat.parse(
                        Files.readString(file, StandardCharsets.UTF_8),
                        file.getFileName().toString());
                index.put(scene.name(), new Entry(file.getFileName().toString(),
                        scene.summary(), scene.updated(), scene.heat()));
            } catch (IOException | RuntimeException e) {
                log.warn("RSI 场景文件解析失败，跳过：{}", file, e);
            }
        }
        return index;
    }

    /**
     * 保存索引（临时文件 + 原子移动）。
     */
    public void save(String agent, Map<String, Entry> index) {
        Path dir = scenesDir(agent);
        Path file = dir.resolve(INDEX_FILE);
        Path temp = dir.resolve(INDEX_FILE + ".tmp");
        try {
            Files.createDirectories(dir);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), new IndexFile(index));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("保存场景索引失败：" + file, e);
        }
    }

    /**
     * 读取索引（空则先重建），保证与文件一致。
     */
    public Map<String, Entry> loadOrRebuild(String agent) {
        Map<String, Entry> index = load(agent);
        return index.isEmpty() ? rebuild(agent) : index;
    }

    /**
     * 检索命中热度 +1：文件 META 与索引条目同步递增。
     */
    public void incrementHeat(String agent, String sceneName) {
        Map<String, Entry> index = loadOrRebuild(agent);
        Entry entry = index.get(sceneName);
        if (entry == null) {
            return;
        }
        Entry bumped = new Entry(entry.file(), entry.summary(), entry.updated(), entry.heat() + 1);
        index.put(sceneName, bumped);
        save(agent, index);
        // 文件 META 同步：heat 是长期统计，写坏文件的代价小于口径分裂
        Path file = scenesDir(agent).resolve(entry.file());
        if (Files.isRegularFile(file)) {
            try {
                SceneFormat.SceneFile scene = SceneFormat.parse(
                        Files.readString(file, StandardCharsets.UTF_8),
                        entry.file());
                Files.writeString(file, SceneFormat.render(scene.name(), scene.summary(),
                        scene.created(), scene.updated(), scene.heat() + 1, scene.body()),
                        StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                log.warn("RSI 场景热度写回失败 scene={} agent={}", sceneName, agent, e);
            }
        }
    }

    /**
     * 场景摘要列表（名 + summary），供 L2 萃取决策与 L3 画像蒸馏、system prompt 索引用。
     */
    public List<SceneExtractor.SceneRef> refs(String agent) {
        Map<String, Entry> index = loadOrRebuild(agent);
        List<SceneExtractor.SceneRef> refs = new ArrayList<>();
        for (Map.Entry<String, Entry> e : index.entrySet()) {
            refs.add(new SceneExtractor.SceneRef(e.getKey(), e.getValue().summary()));
        }
        return refs;
    }

    /**
     * @author yyf
     * @description 索引条目：场景文件名、一句话摘要、最近更新时间与热度
     */
    public record Entry(String file, String summary, String updated, int heat) {
    }

    /**
     * @author yyf
     * @description scene_index.json 的 JSON 载体：场景名 → 条目
     */
    public static class IndexFile {
        public Map<String, Entry> scenes = new LinkedHashMap<>();

        public IndexFile() {
        }

        public IndexFile(Map<String, Entry> scenes) {
            this.scenes = new LinkedHashMap<>(scenes);
        }
    }
}
