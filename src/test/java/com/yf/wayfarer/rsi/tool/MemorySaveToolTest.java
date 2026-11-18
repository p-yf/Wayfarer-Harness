package com.yf.wayfarer.rsi.tool;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.rsi.MemoryExtractor;
import com.yf.wayfarer.rsi.MemorySearcher;
import com.yf.wayfarer.rsi.ProfileDistiller;
import com.yf.wayfarer.rsi.RsiCursorStore;
import com.yf.wayfarer.rsi.RsiGitService;
import com.yf.wayfarer.rsi.RsiPaths;
import com.yf.wayfarer.rsi.RsiPipelineTriggers;
import com.yf.wayfarer.rsi.RsiProperties;
import com.yf.wayfarer.rsi.RsiSedimentPipeline;
import com.yf.wayfarer.rsi.SceneExtractor;
import com.yf.wayfarer.rsi.SceneIndex;
import com.yf.wayfarer.rsi.SkillReviewer;
import com.yf.wayfarer.session.JsonlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * memory_save 显式沉淀链路测试（真实 pipeline + 真实文件，不调 LLM）。
 *
 * @author yyf
 * @description 覆盖：显式落盘规范格式与 id 顺延、非法类型拒绝、近似既有条目提示
 * （只提示不拦截）、超长内容截断
 */
class MemorySaveToolTest {

    @TempDir
    Path temp;

    private MemorySaveTool tool;
    private Path l1File;

    @BeforeEach
    void setUp() {
        RsiPaths paths = new RsiPaths(temp.toString());
        RsiProperties properties = new RsiProperties(true, temp.toString(),
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        MemorySearcher searcher = new MemorySearcher(paths, properties, new SceneIndex(paths));
        RsiSedimentPipeline pipeline = new RsiSedimentPipeline(
                new JsonlSession(temp.resolve("sessions")),
                paths, new RsiGitService(paths), new RsiCursorStore(paths), properties,
                new MemoryExtractor(properties, searcher),
                new SceneExtractor(new SceneIndex(paths), properties),
                new SceneIndex(paths),
                new ProfileDistiller(), new SkillReviewer(properties, paths),
                searcher, new RsiPipelineTriggers(properties));
        tool = new MemorySaveTool(pipeline, "wayfarer-rsi");
        l1File = Path.of(temp.toString(), "stores", "wayfarer-rsi", "memory", "l1.jsonl");
    }

    private static AgentContext context() {
        AgentContext context = new AgentContext();
        context.put(AgentContext.SESSION_ID, "s-1");
        context.put(AgentContext.TRACE_ID, "t-1");
        return context;
    }

    @Test
    void 显式保存落盘并顺延id() {
        String first = tool.save(context(), "fact", "项目用 JDK 21", "jdk, 构建");
        assertTrue(first.contains("已保存"));
        assertTrue(first.contains("[work_fact]"), "旧名 fact 应归一为 work_fact");
        String second = tool.save(context(), "persona", "用户偏好中文注释", null);
        assertTrue(second.contains("已保存"));
        String jsonl = readL1();
        // 规范格式：归一类型、标签解析、显式高分、来源 session
        assertTrue(jsonl.contains("\"type\":\"work_fact\""));
        assertTrue(jsonl.contains("\"score\":9"));
        assertTrue(jsonl.contains("中文注释"));
        // id 按既有最大序号顺延
        String id1 = first.substring(first.indexOf("M-"), first.indexOf("M-") + 15);
        String id2 = second.substring(second.indexOf("M-"), second.indexOf("M-") + 15);
        assertFalse(id1.equals(id2));
        assertEquals(2, jsonl.strip().lines().count());
    }

    @Test
    void 非法类型拒绝() {
        String reply = tool.save(context(), "banana", "乱来的内容", null);
        assertTrue(reply.contains("类型非法"));
        assertFalse(Files.isRegularFile(l1File));
    }

    @Test
    void 空内容拒绝() {
        assertTrue(tool.save(context(), "fact", "  ", null).contains("内容为空"));
    }

    @Test
    void 近似既有条目只提示不拦截() throws Exception {
        Files.createDirectories(l1File.getParent());
        Files.writeString(l1File, """
                {"id":"M-1","type":"work_fact","content":"项目用 JDK 21","tags":["jdk"],"score":9,"createdAt":"t","updatedAt":"t","sources":["s-0"]}
                """, StandardCharsets.UTF_8);
        String reply = tool.save(context(), "fact", "项目用 JDK 21，构建工具 Gradle", null);
        // 仍然保存（宁重不丢，管线判重兜底），但给出近似提示
        assertTrue(reply.contains("已保存"));
        assertTrue(reply.contains("近似记忆"));
        assertTrue(reply.contains("M-1"));
        assertEquals(2, readL1().strip().lines().count());
    }

    @Test
    void 超长内容截断() {
        String longText = "长".repeat(3000);
        String reply = tool.save(context(), "fact", longText, null);
        assertTrue(reply.contains("已保存"));
        String jsonl = readL1();
        // 2000 + 引号包裹的 JSON 转义后仍应短于原文
        assertTrue(jsonl.length() < 3000);
    }

    private String readL1() {
        try {
            return Files.readString(l1File, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
