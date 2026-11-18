package com.yf.wayfarer.rsi;

import com.yf.wayfarer.rsi.model.MemoryItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MemoryExtractor 判重结论应用单测（纯函数 applyJudgments，不调 LLM）。
 *
 * @author yyf
 * @description 覆盖 store / update / merge / skip 四种判定、目标缺失降级 store、
 * 类型归一与 id 顺延
 */
class MemoryExtractorTest {

    private final MemoryExtractor extractor = new MemoryExtractor(properties(), null);

    private static RsiProperties properties() {
        return new RsiProperties(true, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static MemoryItem item(String content, List<String> tags) {
        return MemoryItem.of("M-1", "work_fact", content, tags, 8, null,
                "2026-01-01T00:00:00+08:00", "s-1");
    }

    private static MemoryExtractor.Judgment judgment(int index, String action,
                                                     String targetId, String content) {
        return new MemoryExtractor.Judgment(index, action, targetId, content);
    }

    @Test
    void 判重跳过时全部当新存() {
        MemoryExtractor.ExtractionResult result = extractor.applyJudgments(
                List.of(), List.of(item("项目用 JDK 21", List.of("jdk"))), null, "s-2");
        assertEquals(1, result.stored());
        assertEquals(1, result.items().size());
        assertEquals("s-2", result.items().get(0).sources().get(0));
    }

    @Test
    void update判定原位替换() {
        MemoryItem existing = item("项目用 JDK 21", List.of("jdk"));
        MemoryItem richer = item("项目用 JDK 21，构建工具是 Gradle 8", List.of("jdk", "构建"));
        MemoryExtractor.ExtractionResult result = extractor.applyJudgments(
                List.of(existing), List.of(richer), List.of(judgment(1, "update", "M-1", null)), "s-2");
        assertEquals(1, result.updated());
        assertEquals(1, result.items().size());
        assertEquals(richer.content(), result.items().get(0).content());
        // 原位替换保留 id 与创建时间，版本递增、来源合并
        assertEquals(existing.id(), result.items().get(0).id());
        assertEquals(existing.createdAt(), result.items().get(0).createdAt());
        assertEquals(2, result.items().get(0).version());
        assertTrue(result.items().get(0).sources().contains("s-2"));
    }

    @Test
    void merge判定使用合并文本() {
        MemoryItem existing = item("构建用 Gradle", List.of("gradle"));
        MemoryItem candidate = item("构建用 Gradle 8", List.of("gradle", "版本"));
        MemoryExtractor.ExtractionResult result = extractor.applyJudgments(
                List.of(existing), List.of(candidate),
                List.of(judgment(1, "merge", "M-1", "构建用 Gradle 8，JDK 21")), "s-2");
        assertEquals(1, result.merged());
        assertEquals("构建用 Gradle 8，JDK 21", result.items().get(0).content());
        assertEquals(List.of("gradle", "版本"), result.items().get(0).tags());
    }

    @Test
    void skip判定丢弃候选() {
        MemoryItem existing = item("项目用 JDK 21", List.of("jdk"));
        MemoryItem duplicate = item("项目用 JDK 21", List.of("jdk"));
        MemoryExtractor.ExtractionResult result = extractor.applyJudgments(
                List.of(existing), List.of(duplicate), List.of(judgment(1, "skip", null, null)), "s-2");
        assertEquals(1, result.skipped());
        assertEquals(1, result.items().size());
        assertEquals(existing, result.items().get(0));
    }

    @Test
    void 判定目标不存在时降级store() {
        MemoryItem existing = item("项目用 JDK 21", List.of("jdk"));
        MemoryItem candidate = item("测试目录在 test 下", List.of("目录"));
        MemoryExtractor.ExtractionResult result = extractor.applyJudgments(
                List.of(existing), List.of(candidate),
                List.of(judgment(1, "update", "M-404", null)), "s-2");
        // 宁重不丢：目标不存在降级为 store
        assertEquals(1, result.stored());
        assertEquals(2, result.items().size());
    }

    @Test
    void 未知动作按store处理() {
        MemoryExtractor.ExtractionResult result = extractor.applyJudgments(
                List.of(), List.of(item("新信息", List.of("新"))),
                List.of(judgment(1, "unknown", null, null)), "s-2");
        assertEquals(1, result.stored());
    }

    @Test
    void 类型归一兼容旧名() {
        assertEquals("persona", MemoryExtractor.normalizeType("preference"));
        assertEquals("episodic", MemoryExtractor.normalizeType("episode"));
        assertEquals("work_fact", MemoryExtractor.normalizeType("fact"));
        assertEquals("work_method", MemoryExtractor.normalizeType("procedure"));
        assertEquals("work_artifact", MemoryExtractor.normalizeType("artifact"));
        assertNull(MemoryExtractor.normalizeType("banana"));
        assertNull(MemoryExtractor.normalizeType(null));
    }

    @Test
    void id按既有最大序号顺延() {
        String first = MemoryExtractor.nextIdValue(List.of());
        assertTrue(first.endsWith("0001"));
        MemoryItem occupied = MemoryItem.of(first, "work_fact", "占位", List.of(), 8,
                null, "t", "s-1");
        assertNotEquals(first, MemoryExtractor.nextIdValue(List.of(occupied)));
    }
}
