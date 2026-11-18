package com.yf.wayfarer.rsi;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SceneTools 与 ToolSandboxGuardHandler 单测（工具落盘行为 + 守卫拦截，不调 LLM）。
 *
 * @author yyf
 * @description 覆盖四类变更工具的落盘效果与计数器、上限拦截、名称匹配解析、
 * 守卫对缺参/越界名的拦截与合法调用放行
 */
class SceneToolsTest {

    @TempDir
    Path temp;

    private Path scenesDir() {
        return temp.resolve("scenes");
    }

    /**
     * 与 SceneExtractor 装配同款的场景守卫实例。
     */
    private ToolSandboxGuardHandler sceneGuard() {
        Path dir = scenesDir();
        return new ToolSandboxGuardHandler(dir,
                Set.of("update_scene", "create_scene", "merge_scene", "delete_scene"),
                List.of("scene", "target", "mergeFrom"),
                scene -> SceneTools.resolveTarget(dir, scene),
                "场景目录");
    }

    private static AgentContext context() {
        return new AgentContext();
    }

    /* ------------------------------------------------------------------ */
    /* SceneTools                                                            */
    /* ------------------------------------------------------------------ */

    @Test
    void 创建更新合并删除全链路() {
        SceneTools.Counters counters = new SceneTools.Counters();
        SceneTools tools = new SceneTools(scenesDir(), 20, counters);
        assertTrue(tools.createScene(context(), "构建环境", "项目用 JDK 21", "构建约定").contains("已创建"));
        assertEquals(1, counters.createdCount());

        assertTrue(tools.updateScene(context(), "构建环境", "构建工具是 Gradle 8", null).contains("已更新"));
        assertEquals(1, counters.updatedCount());
        String body = tools.readScene(context(), "构建环境");
        assertTrue(body.contains("JDK 21"));
        assertTrue(body.contains("Gradle 8"));

        assertTrue(tools.createScene(context(), "调试方法", "先看堆栈", "调试流程").contains("已创建"));
        assertTrue(tools.mergeScene(context(), "构建环境", "调试方法", "合并后的正文", "合并摘要").contains("已合并"));
        assertEquals(1, counters.mergedCount());
        assertFalse(scenesDir().resolve("调试方法.md").toFile().exists()
                || Path.of(scenesDir() + "/调试方法.md").toFile().exists());

        assertTrue(tools.deleteScene(context(), "构建环境").contains("已删除"));
        assertEquals(1, counters.deletedCount());
        assertTrue(tools.listScenes(context()).contains("场景库为空"));
    }

    @Test
    void 上限拦截create() {
        SceneTools tools = new SceneTools(scenesDir(), 2, new SceneTools.Counters());
        tools.createScene(context(), "唯一场景", "内容", "摘要");
        // 已有 1 个，maxScenes-1 = 1 → 禁止 CREATE
        String result = tools.createScene(context(), "第二个", "内容", "摘要");
        assertTrue(result.contains("禁止 CREATE"));
    }

    @Test
    void create命中既有场景降级update() {
        SceneTools.Counters counters = new SceneTools.Counters();
        SceneTools tools = new SceneTools(scenesDir(), 20, counters);
        tools.createScene(context(), "构建环境", "初始正文", "摘要");
        assertTrue(tools.createScene(context(), "构建环境", "补充内容", null).contains("已更新"));
        assertEquals(1, counters.createdCount());
        assertEquals(1, counters.updatedCount());
        assertTrue(tools.readScene(context(), "构建环境").contains("补充内容"));
    }

    @Test
    void 目标不存在时计数skipped() {
        SceneTools.Counters counters = new SceneTools.Counters();
        SceneTools tools = new SceneTools(scenesDir(), 20, counters);
        assertTrue(tools.updateScene(context(), "不存在的场景", "内容", null).contains("不存在"));
        assertTrue(tools.deleteScene(context(), "不存在的场景").contains("不存在"));
        assertEquals(0, counters.updatedCount());
        assertEquals(2, counters.skippedCount());
    }

    @Test
    void resolveTarget按场景名与文件名匹配() throws Exception {
        SceneTools tools = new SceneTools(scenesDir(), 20, new SceneTools.Counters());
        tools.createScene(context(), "构建环境", "正文", "摘要");
        // 按场景名命中
        assertEquals(scenesDir().resolve("构建环境.md").toAbsolutePath().normalize(),
                SceneTools.resolveTarget(scenesDir(), "构建环境").toAbsolutePath().normalize());
        // 按文件名（去 .md）命中
        assertEquals(scenesDir().resolve("构建环境.md").toAbsolutePath().normalize(),
                SceneTools.resolveTarget(scenesDir(), "构建环境.md").toAbsolutePath().normalize());
        // 未命中回退安全文件名
        assertEquals(scenesDir().resolve("新场景.md").toAbsolutePath().normalize(),
                SceneTools.resolveTarget(scenesDir(), "新场景").toAbsolutePath().normalize());
        // 非法（含路径分隔符）名称被清洗，不会逃出目录
        Path resolved = SceneTools.resolveTarget(scenesDir(), "..\\..\\evil");
        assertTrue(resolved.toAbsolutePath().normalize().startsWith(scenesDir().toAbsolutePath().normalize()));
        assertTrue(SceneTools.resolveTarget(scenesDir(), " ") == null);
    }

    /* ------------------------------------------------------------------ */
    /* ToolSandboxGuardHandler（场景装配）                                      */
    /* ------------------------------------------------------------------ */

    @Test
    void 守卫拦截缺场景名的变更调用() {
        AgentContext context = context();
        List<ChatMessage> messages = new ArrayList<>();
        context.put(AgentContext.MESSAGES, messages);
        context.put(AgentContext.CURRENT_TOOL_CALL, new ToolCallInfo("c-1", "update_scene", "{}"));
        boolean[] nextReached = {false};
        sceneGuard().run(context, () -> nextReached[0] = true);
        // 不放行，且补了系统拒绝结果
        assertFalse(nextReached[0]);
        assertEquals(1, messages.size());
        assertEquals(ChatMessage.Role.TOOL, messages.get(0).role());
        assertTrue(messages.get(0).content().contains("拒绝执行"));
    }

    @Test
    void 守卫放行合法变更调用() {
        AgentContext context = context();
        List<ChatMessage> messages = new ArrayList<>();
        context.put(AgentContext.MESSAGES, messages);
        context.put(AgentContext.CURRENT_TOOL_CALL, new ToolCallInfo("c-1", "create_scene",
                "{\"scene\":\"构建环境\",\"content\":\"正文\",\"summary\":\"摘要\"}"));
        boolean[] nextReached = {false};
        sceneGuard().run(context, () -> nextReached[0] = true);
        assertTrue(nextReached[0]);
        assertTrue(messages.isEmpty());
    }

    @Test
    void 守卫逐个校验merge的目标与被合并场景() {
        AgentContext context = context();
        List<ChatMessage> messages = new ArrayList<>();
        context.put(AgentContext.MESSAGES, messages);
        context.put(AgentContext.CURRENT_TOOL_CALL, new ToolCallInfo("c-1", "merge_scene",
                "{\"target\":\"构建环境\",\"mergeFrom\":\"调试方法, 画像.txt\",\"content\":\"x\"}"));
        boolean[] nextReached = {false};
        sceneGuard().run(context, () -> nextReached[0] = true);
        // 所有目标（含 mergeFrom 逐个）解析后都在场景目录内 → 放行
        assertTrue(nextReached[0]);
    }

    @Test
    void 只读工具不受守卫影响() {
        AgentContext context = context();
        List<ChatMessage> messages = new ArrayList<>();
        context.put(AgentContext.MESSAGES, messages);
        context.put(AgentContext.CURRENT_TOOL_CALL, new ToolCallInfo("c-1", "list_scenes", "{}"));
        boolean[] nextReached = {false};
        sceneGuard().run(context, () -> nextReached[0] = true);
        assertTrue(nextReached[0]);
    }
}
