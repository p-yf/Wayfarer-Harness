package com.yf.wayfarer.loop.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.rsi.MemorySearcher;
import com.yf.wayfarer.rsi.RsiPaths;
import com.yf.wayfarer.rsi.RsiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MemoryRecallHandler 单测。
 *
 * @author yyf
 * @description 覆盖：有命中时内存副本注入、run 内 marker 判重、空 store 不注入、
 * 总开关关闭时不召回
 */
class MemoryRecallHandlerTest {

    @TempDir
    Path temp;

    @Test
    void 有命中时注入到用户消息内存副本() throws Exception {
        writeL1("""
                {"id":"M-1","type":"preference","content":"用户偏好中文注释","tags":["注释"],"score":9,"createdAt":"t","updatedAt":"t","sources":["s-1"]}
                """);
        List<ChatMessage> messages = new ArrayList<>(List.of(
                new ChatMessage(ChatMessage.Role.SYSTEM, "系统提示"),
                new ChatMessage(ChatMessage.Role.USER, "写代码时的注释偏好是什么")));
        AgentContext context = contextOf(messages);
        context.put(AgentContext.USER_QUERY, "写代码时的注释偏好是什么");
        handler(true).run(context, () -> {
        });

        String content = messages.get(1).content();
        assertTrue(content.startsWith("写代码时的注释偏好是什么"), content);
        assertTrue(content.contains(MemoryRecallHandler.MARKER), content);
        assertTrue(content.contains("中文注释"), content);
        // SYSTEM 消息不受影响
        assertEquals("系统提示", messages.get(0).content());
    }

    @Test
    void 已注入过则不重复召回() throws Exception {
        writeL1("""
                {"id":"M-1","type":"preference","content":"用户偏好中文注释","tags":["注释"],"score":9,"createdAt":"t","updatedAt":"t","sources":["s-1"]}
                """);
        List<ChatMessage> messages = new ArrayList<>(List.of(new ChatMessage(ChatMessage.Role.USER, "继续")));
        AgentContext context = contextOf(messages);
        context.put(AgentContext.USER_QUERY, "继续");
        MemoryRecallHandler handler = handler(true);
        handler.run(context, () -> {
        });
        String first = messages.get(0).content();
        handler.run(context, () -> {
        });
        // marker 只出现一次 = 第二次判重直接跳过
        assertEquals(first.indexOf(MemoryRecallHandler.MARKER),
                first.lastIndexOf(MemoryRecallHandler.MARKER), first);
        assertEquals(first, messages.get(0).content());
    }

    @Test
    void 空store时不注入() {
        List<ChatMessage> messages = new ArrayList<>(List.of(new ChatMessage(ChatMessage.Role.USER, "查询无关内容")));
        AgentContext context = contextOf(messages);
        context.put(AgentContext.USER_QUERY, "查询无关内容");
        handler(true).run(context, () -> {
        });
        assertEquals("查询无关内容", messages.get(0).content());
    }

    @Test
    void 总开关关闭时不注入() throws Exception {
        writeL1("""
                {"id":"M-1","type":"preference","content":"用户偏好中文注释","tags":["注释"],"score":9,"createdAt":"t","updatedAt":"t","sources":["s-1"]}
                """);
        List<ChatMessage> messages = new ArrayList<>(List.of(new ChatMessage(ChatMessage.Role.USER, "写代码")));
        AgentContext context = contextOf(messages);
        context.put(AgentContext.USER_QUERY, "写代码");
        handler(false).run(context, () -> {
        });
        assertFalse(messages.get(0).content().contains(MemoryRecallHandler.MARKER));
    }

    /**
     * 组装被测 handler：store 路径与分词器都指向 @TempDir，测试间互不污染。
     */
    private MemoryRecallHandler handler(boolean enabled) {
        RsiProperties properties = new RsiProperties(enabled, temp.toString(),
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        RsiPaths paths = new RsiPaths(temp.toString());
        return new MemoryRecallHandler("wayfarer-rsi", properties,
                new MemorySearcher(paths, properties, new com.yf.wayfarer.rsi.SceneIndex(paths)));
    }

    private void writeL1(String jsonl) throws Exception {
        Path memoryDir = Path.of(temp.toString(), "stores", "wayfarer-rsi", "memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("l1.jsonl"), jsonl, StandardCharsets.UTF_8);
    }

    private static AgentContext contextOf(List<ChatMessage> messages) {
        AgentContext context = new AgentContext();
        context.put(AgentContext.MESSAGES, messages);
        return context;
    }
}
