package com.yf.wayfarer.agent;

import com.sun.net.httpserver.HttpServer;
import com.yf.wayfarer.agent.modelchain.MemoryCompactConfig;
import com.yf.wayfarer.hitl.HitlService;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ModelConfig;
import com.yf.wayfarer.model.openai.OpenAIChatModel;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.JsonlSession;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.SessionMessage;
import com.yf.wayfarer.session.UnloadedMessageStore;
import com.yf.wayfarer.toolcall.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行终止时的落盘测试，使用真实本地 HTTP 流与真实文件系统。
 *
 * @author yyf
 * @description 验证模型输出中途终止后，已流出的内容仍然写入会话，不会随着取消一起丢失
 */
class CancelPersistTest {

    private static final String FIRST_CHUNK = """
            data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"半截回复"},"finish_reason":null}]}

            """;

    private static final String TAIL_CHUNKS = """
            data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    @TempDir
    Path root;

    private HttpServer server;
    private Session session;
    private OpenAIChatModel model;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            // 长度 0 表示分块输出，先送一片再停顿，模拟模型持续输出
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(FIRST_CHUNK.getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(1500);
                out.write(TAIL_CHUNKS.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException | InterruptedException ignored) {
                // 客户端取消后写出失败属于预期
            }
            exchange.close();
        });
        server.start();

        model = new OpenAIChatModel(new ModelConfig(
                "test-key",
                "http://localhost:" + server.getAddress().getPort() + "/v1",
                "deepseek-flash",
                null,
                null));
        session = new JsonlSession(root);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 模型输出中途终止时已流出内容仍写入会话() throws Exception {
        String sessionId = session.create("终止会话");
        session.append(sessionId, JSONMsgType.USER, "turn-1", userContent("写一段说明"));

        AgentContext context = new AgentContext();
        context.put(AgentContext.SESSION_ID, sessionId);

        CountDownLatch firstText = new CountDownLatch(1);
        Disposable subscription = agent().stream(context).subscribe(message -> {
            if (message instanceof AgentMessage.Text) {
                firstText.countDown();
            }
        });
        assertTrue(firstText.await(10, TimeUnit.SECONDS), "应先收到第一段输出");

        subscription.dispose();
        // 取消路径在订阅释放时同步落盘，留出一点时间等待写入完成
        Thread.sleep(500);

        List<SessionMessage> stored = session.messages(sessionId);
        SessionMessage assistant = stored.stream()
                .filter(message -> message.type() == JSONMsgType.ASSISTANT)
                .reduce((first, second) -> second)
                .orElse(null);
        assertNotNull(assistant, "终止前的模型输出应写入会话：" + stored);
        assertEquals("半截回复", assistant.content().get("content"));
        assertFalse(stored.stream().anyMatch(message -> message.type() == JSONMsgType.EXTRADATA),
                "本轮未正常结束，不应写入调用记录");
    }

    @Test
    void 终止运行时可持久化状态也写入会话() throws Exception {
        String sessionId = session.create("终止状态会话");
        session.append(sessionId, JSONMsgType.USER, "turn-1", userContent("写一段说明"));

        AgentContext context = new AgentContext();
        context.put(AgentContext.SESSION_ID, sessionId);
        context.put("store:cursor", "cursor-1");

        CountDownLatch firstText = new CountDownLatch(1);
        Disposable subscription = agent().stream(context).subscribe(message -> {
            if (message instanceof AgentMessage.Text) {
                firstText.countDown();
            }
        });
        assertTrue(firstText.await(10, TimeUnit.SECONDS), "应先收到第一段输出");

        subscription.dispose();
        Thread.sleep(500);

        List<SessionMessage> stored = session.messages(sessionId);
        SessionMessage state = stored.stream()
                .filter(message -> message.type() == JSONMsgType.CUSTOM)
                .reduce((first, second) -> second)
                .orElse(null);
        assertNotNull(state, "终止时也应写入可持久化状态：" + stored);
        assertEquals("cursor-1", state.content().get("store:cursor"));
    }

    private Agent agent() {
        return new Agent(model, List.of(), new ToolCall(), null, session,
                new UnloadedMessageStore(root), MemoryCompactConfig.defaults(), new HitlService(60));
    }

    private static Map<String, Object> userContent(String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", ChatMessage.Role.USER.name());
        content.put("content", text);
        return content;
    }
}
