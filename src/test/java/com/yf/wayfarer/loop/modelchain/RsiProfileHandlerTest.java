package com.yf.wayfarer.loop.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.rsi.MemorySearcher;
import com.yf.wayfarer.rsi.RsiPaths;
import com.yf.wayfarer.rsi.RsiProperties;
import com.yf.wayfarer.rsi.SceneFormat;
import com.yf.wayfarer.rsi.SceneIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RsiProfileHandler 单测。
 *
 * @author yyf
 * @description 覆盖：画像全文 + 场景索引（无正文）注入 system prompt、空 store 不注入、
 * 总开关关闭不注入、按 sessionId 缓存一次装配（会话中途文件变更不刷新）
 */
class RsiProfileHandlerTest {

    @TempDir
    Path temp;

    @Test
    void 注入画像全文与场景索引() throws Exception {
        writePersona("用户偏好中文注释");
        writeScene("构建环境", "构建相关约定", "场景正文细节");
        AgentContext context = contextOf("s-1");
        handler(true).run(context, () -> {
        });
        String block = context.systemPrompts().get(RsiProfileHandler.PROFILE_PROMPT_KEY);
        assertTrue(block.contains("用户偏好中文注释"));
        // 场景索引：只有名字与摘要，正文不进 system prompt
        assertTrue(block.contains("构建环境"));
        assertTrue(block.contains("构建相关约定"));
        assertFalse(block.contains("场景正文细节"));
        assertTrue(block.contains("memory_search"));
    }

    @Test
    void 空store不注入() {
        AgentContext context = contextOf("s-1");
        handler(true).run(context, () -> {
        });
        assertFalse(context.systemPrompts().containsKey(RsiProfileHandler.PROFILE_PROMPT_KEY));
    }

    @Test
    void 总开关关闭不注入() throws Exception {
        writePersona("用户偏好中文注释");
        AgentContext context = contextOf("s-1");
        handler(false).run(context, () -> {
        });
        assertFalse(context.systemPrompts().containsKey(RsiProfileHandler.PROFILE_PROMPT_KEY));
    }

    @Test
    void 同会话缓存一次中途变更不刷新() throws Exception {
        writePersona("第一版画像");
        AgentContext context = contextOf("s-1");
        RsiProfileHandler handler = handler(true);
        handler.run(context, () -> {
        });
        String first = context.systemPrompts().get(RsiProfileHandler.PROFILE_PROMPT_KEY);
        // 会话中途画像文件变了：session_init cacheStrategy，不刷新
        writePersona("第二版画像");
        handler.run(context, () -> {
        });
        assertEquals(first, context.systemPrompts().get(RsiProfileHandler.PROFILE_PROMPT_KEY));
        // 新会话才重新装配
        AgentContext nextSession = contextOf("s-2");
        handler.run(nextSession, () -> {
        });
        assertTrue(nextSession.systemPrompts().get(RsiProfileHandler.PROFILE_PROMPT_KEY)
                .contains("第二版画像"));
    }

    /* ------------------------------------------------------------------ */

    private RsiProfileHandler handler(boolean enabled) {
        RsiProperties properties = new RsiProperties(enabled, temp.toString(),
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        RsiPaths paths = new RsiPaths(temp.toString());
        return new RsiProfileHandler("wayfarer-rsi", properties,
                new MemorySearcher(paths, properties, new SceneIndex(paths)), new SceneIndex(paths));
    }

    private AgentContext contextOf(String sessionId) {
        AgentContext context = new AgentContext();
        context.put(AgentContext.SESSION_ID, sessionId);
        context.put(AgentContext.SYSTEM_PROMPT, new LinkedHashMap<>());
        return context;
    }

    private void writePersona(String content) throws Exception {
        Path memoryDir = Path.of(temp.toString(), "stores", "wayfarer-rsi", "memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("persona.md"), content, StandardCharsets.UTF_8);
    }

    private void writeScene(String name, String summary, String body) throws Exception {
        Path scenesDir = Path.of(temp.toString(), "stores", "wayfarer-rsi", "memory", "scenes");
        Files.createDirectories(scenesDir);
        Files.writeString(scenesDir.resolve("scene.md"),
                SceneFormat.render(name, summary, SceneFormat.now(), SceneFormat.now(), 0, body),
                StandardCharsets.UTF_8);
    }
}
