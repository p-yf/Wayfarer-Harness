package com.yf.wayfarer.session;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话持久化测试，全部基于真实文件系统。
 *
 * @author yyf
 * @description 覆盖文件命名、追加消息、fork、clone、按 id 恢复、压缩与状态回填
 */
class JsonlSessionTest {

    @TempDir
    Path root;

    @Test
    void 会话按约定规则创建文件夹并在其中写入消息() {
        Session session = new JsonlSession(root);
        String id = session.create("排查错误");

        assertTrue(session.messages(id).isEmpty());

        Path directory = root.toFile().listFiles()[0].toPath();
        String dirName = directory.getFileName().toString();
        assertTrue(directory.toFile().isDirectory(), "一个会话应是一个文件夹：" + dirName);
        assertTrue(dirName.startsWith(id + "_"), "文件夹名应以会话 id 开头：" + dirName);
        assertTrue(dirName.contains("排查错误"), "文件夹名应包含会话名称：" + dirName);
        assertTrue(Files.exists(directory.resolve("session.jsonl")), "会话主文件应为 session.jsonl");

        session.append(id, JSONMsgType.USER, "turn-1", userContent("查一下日志"));

        // 文件实际写入了合法 json 行，messageId 自动生成
        SessionMessage stored = session.messages(id).get(0);
        assertEquals(JSONMsgType.USER, stored.type());
        assertEquals("turn-1", stored.traceId());
        assertEquals("查一下日志", stored.content().get("content"));
        assertTrue(stored.messageId() != null && !stored.messageId().isBlank());
    }

    @Test
    void 按会话id恢复完整会话() {
        Session session = new JsonlSession(root);
        String id = session.create("恢复测试");
        session.append(id, JSONMsgType.USER, "turn-1", userContent("第一句"));
        session.append(id, JSONMsgType.ASSISTANT, "turn-1", assistantContent("第一答"));

        SessionMeta meta = session.meta(id);
        assertEquals(id, meta.id());
        assertEquals("恢复测试", meta.name());

        assertEquals(2, session.history(id).size());

        AgentContext context = new AgentContext();
        session.restore(id, context);
        List<ChatMessage> history = context.messages();
        assertEquals(2, history.size());
        assertEquals("第一句", history.get(0).content());
        assertEquals("第一答", history.get(1).content());
    }

    @Test
    void 会话名称中的空格原样保留() {
        Session session = new JsonlSession(root);
        String id = session.create("修复 auth 模块的类型错误");

        assertEquals("修复 auth 模块的类型错误", session.meta(id).name());
    }

    @Test
    void 删除会话后无法再按id打开() {
        Session session = new JsonlSession(root);
        String id = session.create("待删除会话");
        session.append(id, JSONMsgType.USER, "turn-1", userContent("一"));

        session.delete(id);

        assertThrows(IllegalArgumentException.class, () -> session.messages(id));
        assertTrue(session.list().isEmpty());
    }

    @Test
    void 打开不存在的会话时报错() {
        Session session = new JsonlSession(root);

        assertThrows(IllegalArgumentException.class, () -> session.messages("not-exists"));
    }

    @Test
    void fork只克隆指定轮次之前的消息() {
        Session session = new JsonlSession(root);
        String id = session.create("原始会话");
        session.append(id, JSONMsgType.USER, "turn-1", userContent("一"));
        session.append(id, JSONMsgType.ASSISTANT, "turn-1", assistantContent("一答"));
        session.append(id, JSONMsgType.USER, "turn-2", userContent("二"));
        session.append(id, JSONMsgType.ASSISTANT, "turn-2", assistantContent("二答"));

        String forkedId = session.fork(id, "turn-2");

        assertNotEquals(id, forkedId);
        assertEquals(2, session.messages(forkedId).size());
        assertEquals("一", session.messages(forkedId).get(0).content().get("content"));
        assertEquals("一答", session.messages(forkedId).get(1).content().get("content"));
        // 原会话不受影响
        assertEquals(4, session.messages(id).size());
    }

    @Test
    void fork指定不存在的轮次时报错() {
        Session session = new JsonlSession(root);
        String id = session.create("原始会话");
        session.append(id, JSONMsgType.USER, "turn-1", userContent("一"));

        assertThrows(IllegalArgumentException.class, () -> session.fork(id, "turn-9"));
    }

    @Test
    void clone克隆当前完整会话() {
        Session session = new JsonlSession(root);
        String id = session.create("原始会话");
        session.append(id, JSONMsgType.USER, "turn-1", userContent("一"));
        session.append(id, JSONMsgType.ASSISTANT, "turn-1", assistantContent("一答"));

        String copiedId = session.clone(id);

        assertNotEquals(id, copiedId);
        assertEquals(2, session.messages(copiedId).size());
        assertEquals(session.history(id), session.history(copiedId));
    }

    @Test
    void 达到压缩阈值后写入压缩消息并能正确恢复() {
        Session session = new JsonlSession(root, 2);
        String id = session.create("压缩会话");
        session.append(id, JSONMsgType.USER, "turn-1", userContent("一"));
        session.append(id, JSONMsgType.ASSISTANT, "turn-1", assistantContent("一答"));

        List<SessionMessage> messages = session.messages(id);
        assertEquals(3, messages.size());
        assertEquals(JSONMsgType.COMPACT, messages.get(2).type());
        assertEquals(2, ((List<?>) messages.get(2).content().get("messages")).size());

        // 压缩后继续追加，恢复时应叠加在压缩基线之后
        session.append(id, JSONMsgType.USER, "turn-2", userContent("二"));

        List<ChatMessage> history = session.history(id);
        assertEquals(3, history.size());
        assertEquals("一", history.get(0).content());
        assertEquals("一答", history.get(1).content());
        assertEquals("二", history.get(2).content());
    }

    @Test
    void 恢复时回填最后一个自定义消息的状态() {
        Session session = new JsonlSession(root);
        String id = session.create("状态会话");
        session.append(id, JSONMsgType.USER, "turn-1", userContent("一"));

        Map<String, Object> first = new LinkedHashMap<>();
        first.put("store:step", "1");
        session.append(id, JSONMsgType.CUSTOM, "turn-1", first);

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("store:step", "2");
        session.append(id, JSONMsgType.CUSTOM, "turn-2", second);

        AgentContext context = new AgentContext();
        session.restore(id, context);

        assertEquals("2", context.get("store:step"));
        assertEquals(1, context.messages().size());
    }

    @Test
    void reset删除指定轮次及其之后的全部消息() {
        Session session = new JsonlSession(root);
        String id = session.create("重置会话");
        session.append(id, JSONMsgType.USER, "turn-1", userContent("一"));
        session.append(id, JSONMsgType.ASSISTANT, "turn-1", assistantContent("一答"));
        session.append(id, JSONMsgType.USER, "turn-2", userContent("二"));
        session.append(id, JSONMsgType.ASSISTANT, "turn-2", assistantContent("二答"));

        session.reset(id, "turn-2");

        List<SessionMessage> remaining = session.messages(id);
        assertEquals(2, remaining.size());
        assertEquals("一", remaining.get(0).content().get("content"));
        assertEquals("一答", remaining.get(1).content().get("content"));
        assertEquals(2, session.history(id).size());

        // 重置后继续追加，不应与已有内容粘连
        session.append(id, JSONMsgType.USER, "turn-3", userContent("三"));
        assertEquals(3, session.messages(id).size());
        assertEquals("三", session.messages(id).get(2).content().get("content"));
    }

    @Test
    void reset指定不存在的轮次时报错() {
        Session session = new JsonlSession(root);
        String id = session.create("重置会话");
        session.append(id, JSONMsgType.USER, "turn-1", userContent("一"));

        assertThrows(IllegalArgumentException.class, () -> session.reset(id, "turn-9"));
        assertEquals(1, session.messages(id).size());
    }

    @Test
    void 工具消息往返后保留工具调用id() {
        Session session = new JsonlSession(root);
        String id = session.create("工具会话");
        ChatMessage assistant = ChatMessage.assistant("", "先查日志",
                List.of(new ToolCallInfo("call_1", "inspect_logs", "{}")));
        session.append(id, JSONMsgType.ASSISTANT, "turn-1", SessionMessage.toMap(assistant));
        session.append(id, JSONMsgType.TOOL, "turn-1",
                SessionMessage.toMap(new ChatMessage(ChatMessage.Role.TOOL, "128条错误", "call_1")));

        List<ChatMessage> history = session.history(id);
        assertEquals(1, history.get(0).toolCalls().size());
        assertEquals("先查日志", history.get(0).reasoningContent());
        assertEquals("call_1", history.get(1).toolCallId());
        assertEquals("128条错误", history.get(1).content());
    }

    private static Map<String, Object> userContent(String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", ChatMessage.Role.USER.name());
        content.put("content", text);
        return content;
    }

    private static Map<String, Object> assistantContent(String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", ChatMessage.Role.ASSISTANT.name());
        content.put("content", text);
        return content;
    }
}
