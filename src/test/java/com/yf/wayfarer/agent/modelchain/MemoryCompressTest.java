package com.yf.wayfarer.agent.modelchain;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ModelConfig;
import com.yf.wayfarer.model.openai.OpenAIChatModel;
import com.yf.wayfarer.session.UnloadedMessageStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆压缩测试，使用真实本地 HTTP 模型服务与真实文件系统，不使用模拟对象。
 *
 * @author yyf
 * @description 覆盖阈值判断、工具结果卸载、大消息卸载、LLM 压缩与卸载记录清理
 */
class MemoryCompressTest {

    private static final String SUMMARY_SSE = """
            data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"压缩后的要点"},"finish_reason":null}]}

            data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    @TempDir
    Path root;

    private HttpServer server;
    private ExecutorService serverExecutor;
    private OpenAIChatModel model;
    private UnloadedMessageStore store;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    private static final String SESSION = "session-1";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, SUMMARY_SSE);
        });
        server.start();
        model = new OpenAIChatModel(new ModelConfig(
                "test-key",
                "http://localhost:" + server.getAddress().getPort() + "/v1",
                "deepseek-flash",
                null,
                null));
        store = new UnloadedMessageStore(root.resolve("unloaded"));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void 压缩处理器排在模型调用之前() {
        assertEquals(-100, handler(100_000, 0.01, 0.02, 0.5, 100).sort());
    }

    @Test
    void 未超阈值时不改动消息也不写卸载文件() {
        AgentContext context = contextOf(
                new ChatMessage(ChatMessage.Role.USER, "你好"),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "你好，有什么可以帮你"));

        run(handler(100_000, 0.01, 0.02, 0.5, 100), context);

        assertEquals(2, context.messages().size());
        assertTrue(store.ids(SESSION).isEmpty());
        assertTrue(requests.isEmpty(), "未超阈值时不应调用模型");
    }

    @Test
    void 长工具结果卸载到文件并替换为占位符() {
        // 窗口 1000：触发 700 token，工具阈值 30% = 300 token
        String raw = "c".repeat(2000);
        AgentContext context = contextOf(
                new ChatMessage(ChatMessage.Role.USER, "a".repeat(1000)),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "b".repeat(1000)),
                new ChatMessage(ChatMessage.Role.TOOL, raw, "call_1"),
                new ChatMessage(ChatMessage.Role.USER, "当前问题"));

        run(handler(1000, 0.3, 0.4, 0.5, 100), context);

        String content = context.messages().get(2).content();
        assertTrue(content.startsWith("<sys-unloaded-"), "工具结果应整体替换为占位符：" + content);
        assertTrue(content.endsWith(">"));
        String uuid = content.substring("<sys-unloaded-".length(), content.length() - 1);
        assertEquals(raw, store.read(SESSION, uuid), "原文应完整写入卸载文件");
        assertTrue(requests.isEmpty(), "一级生效后不应进入 LLM 压缩");
    }

    @Test
    void 长普通消息卸载时保留头尾字符() {
        // 窗口 1000：触发 700 token，消息阈值 30% = 300 token
        String head = "HEAD-" + "h".repeat(95);
        String tail = "t".repeat(95) + "-TAIL";
        String long1 = head + "m".repeat(1400) + tail;
        AgentContext context = contextOf(
                new ChatMessage(ChatMessage.Role.ASSISTANT, long1),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "b".repeat(1600)),
                new ChatMessage(ChatMessage.Role.USER, "当前问题"));

        run(handler(1000, 0.9, 0.3, 0.5, 100), context);

        String content = context.messages().get(0).content();
        assertTrue(content.startsWith("HEAD-"), "应保留头部：" + content);
        assertTrue(content.endsWith("-TAIL"), "应保留尾部：" + content);
        assertTrue(content.contains("<sys-unloaded-"), "中间应替换为占位符：" + content);
        assertTrue(requests.isEmpty(), "二级生效后不应进入 LLM 压缩");
    }

    @Test
    void 累计超限时从头压缩并清理对应卸载记录() {
        // 窗口 1000：触发 700，三级阈值 50% = 500 token；一条已卸载的占位符位于前三条内
        String uuid = store.save(SESSION, "已经被卸载的原始内容");
        AgentContext context = contextOf(
                new ChatMessage(ChatMessage.Role.USER, "a".repeat(700)),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "b".repeat(700)),
                new ChatMessage(ChatMessage.Role.ASSISTANT, String.format(MemoryCompressHandler.PLACEHOLDER, uuid)),
                new ChatMessage(ChatMessage.Role.USER, "c".repeat(700)),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "d".repeat(700)),
                new ChatMessage(ChatMessage.Role.USER, "e".repeat(700)),
                new ChatMessage(ChatMessage.Role.USER, "当前问题"));

        run(handler(1000, 0.9, 0.9, 0.5, 100), context);

        List<ChatMessage> messages = context.messages();
        assertEquals(ChatMessage.Role.SYSTEM, messages.get(0).role(), "头部应被压成一条记忆");
        assertTrue(messages.get(0).content().contains("压缩后的要点"));
        assertTrue(store.ids(SESSION).isEmpty(), "被压缩内容里的卸载记录应被删除");
        assertFalse(requests.isEmpty(), "应调用模型执行压缩");
    }

    @Test
    void 历史压缩后仍超阈值时本轮同样处理() {
        // 窗口 1000：历史被压成摘要后，本轮自身仍触发二级卸载
        AgentContext context = contextOf(
                new ChatMessage(ChatMessage.Role.USER, "a".repeat(700)),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "b".repeat(700)),
                new ChatMessage(ChatMessage.Role.USER, "c".repeat(700)),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "d".repeat(700)),
                new ChatMessage(ChatMessage.Role.USER, "e".repeat(700)),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "f".repeat(700)),
                new ChatMessage(ChatMessage.Role.USER, "g".repeat(2400)));

        run(handler(1000, 0.5, 0.5, 0.5, 100), context);

        List<ChatMessage> messages = context.messages();
        ChatMessage current = messages.get(messages.size() - 1);
        assertTrue(current.content().contains("<sys-unloaded-"), "本轮消息也应被卸载：" + current.content());
        assertEquals(1, store.ids(SESSION).size());
    }

    @Test
    void 模型不可用时保留原消息() {
        server.stop(0);
        AgentContext context = contextOf(
                new ChatMessage(ChatMessage.Role.USER, "a".repeat(3000)),
                new ChatMessage(ChatMessage.Role.USER, "b".repeat(3000)));

        // 一二级阈值调到不触发，强制进入三级压缩
        run(handler(1000, 0.9, 0.9, 0.5, 100), context);

        assertEquals(2, context.messages().size(), "压缩失败应保留原消息");
        assertTrue(store.ids(SESSION).isEmpty());
    }

    @Test
    void 配置非法时报错() {
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryCompressHandler(model, store, 0, 0.7, 0.01, 0.02, 0.5, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryCompressHandler(model, store, 1000, 1.5, 0.01, 0.02, 0.5, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryCompressHandler(model, store, 1000, 0.7, 0, 0.02, 0.5, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryCompressHandler(model, store, 1000, 0.7, 0.01, 0.02, 0.5, -1));
    }

    private MemoryCompressHandler handler(int window, double toolRatio, double messageRatio,
                                          double compressRatio, int keepChars) {
        return new MemoryCompressHandler(model, store, window, 0.7, toolRatio, messageRatio,
                compressRatio, keepChars);
    }

    private void run(MemoryCompressHandler handler, AgentContext context) {
        handler.run(context, Mono.empty()).block();
    }

    private static AgentContext contextOf(ChatMessage... messages) {
        AgentContext context = new AgentContext();
        context.put(AgentContext.SESSION_ID, SESSION);
        context.put(AgentContext.MESSAGES, new ArrayList<>(List.of(messages)));
        return context;
    }

    private static void respond(HttpExchange exchange, String sse) throws IOException {
        byte[] body = sse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        exchange.close();
    }
}
