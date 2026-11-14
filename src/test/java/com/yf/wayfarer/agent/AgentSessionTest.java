package com.yf.wayfarer.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yf.wayfarer.agent.modelchain.MemoryCompactConfig;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ModelConfig;
import com.yf.wayfarer.model.openai.OpenAIChatModel;
import com.yf.wayfarer.session.JsonlSession;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.SessionMessage;
import com.yf.wayfarer.session.UnloadedMessageStore;
import com.yf.wayfarer.toolcall.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * agent 与会话联动测试，使用真实本地 HTTP 服务与真实文件系统，不使用模拟对象。
 *
 * @author yyf
 * @description 覆盖按会话 id 运行、消息与状态持久化、以及再次恢复会话后的历史连续性
 */
class AgentSessionTest {

    private static final String SSE = """
            data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"处理完成"},"finish_reason":null}]}

            data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    /**
     * 思考模式下的返回：先流式输出 reasoning_content，再输出正文。
     */
    private static final String REASONING_SSE = """
            data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"reasoning_content":"先"},"finish_reason":null}]}

            data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"reasoning_content":"想一想"},"finish_reason":null}]}

            data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"处理完成"},"finish_reason":null}]}

            data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    @TempDir
    Path root;

    private HttpServer server;
    private ExecutorService serverExecutor;
    private Session session;
    private OpenAIChatModel model;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> sseResponse = new AtomicReference<>(SSE);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, sseResponse.get());
        });
        server.start();

        OpenAIChatModel model = new OpenAIChatModel(new ModelConfig(
                "test-key",
                "http://localhost:" + server.getAddress().getPort() + "/v1",
                "deepseek-flash",
                null,
                null));
        this.model = model;
        session = new JsonlSession(root);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void 按会话id运行并持久化消息与状态() {
        String sessionId = session.create("会话一");
        session.append(sessionId, JSONMsgType.USER, "turn-user", userContent("查一下错误日志"));

        AgentContext context = new AgentContext();
        context.put(AgentContext.SESSION_ID, sessionId);
        context.put("store:cursor", "cursor-1");

        List<AgentMessage> messages = agent().stream(context).collectList().block();
        assertNotNull(messages);
        assertTrue(messages.size() >= 1);
        assertNotNull(context.traceId());

        // 模型请求应包含会话中的用户消息
        assertTrue(requestBody.get().contains("查一下错误日志"), "请求体应包含历史用户消息：" + requestBody.get());

        List<SessionMessage> stored = session.messages(sessionId);
        assertEquals(4, stored.size(), "应依次写入用户、助手、调用记录、自定义状态四条消息");
        assertEquals(JSONMsgType.USER, stored.get(0).type());
        assertEquals(JSONMsgType.ASSISTANT, stored.get(1).type());
        assertEquals("处理完成", stored.get(1).content().get("content"));
        assertEquals(context.traceId(), stored.get(1).traceId(), "助手消息应记录本次运行的 trace id");
        assertEquals(JSONMsgType.EXTRADATA, stored.get(2).type());
        assertEquals(JSONMsgType.CUSTOM, stored.get(3).type());
        assertEquals("cursor-1", stored.get(3).content().get("store:cursor"));
    }

    @Test
    void 用户请求与计划状态随运行落盘() {
        String sessionId = session.create("用户请求会话");
        AgentContext context = new AgentContext();
        context.put(AgentContext.SESSION_ID, sessionId);
        context.put(AgentContext.USER_QUERY, "帮我排查重启问题");
        context.put(AgentContext.PLAN, Map.of("1", Map.of("content", "读取日志", "status", "not_begin")));

        agent().stream(context).collectList().block();

        List<SessionMessage> stored = session.messages(sessionId);
        assertEquals(4, stored.size(), "应依次写入用户请求、助手回复、调用记录与状态四条消息");
        assertEquals(JSONMsgType.USER, stored.get(0).type());
        assertEquals("帮我排查重启问题", stored.get(0).content().get("content"));
        assertEquals(JSONMsgType.ASSISTANT, stored.get(1).type());
        assertEquals(JSONMsgType.EXTRADATA, stored.get(2).type());
        assertEquals(JSONMsgType.CUSTOM, stored.get(3).type());
        assertTrue(stored.get(3).content().containsKey(AgentContext.PLAN), "计划状态应写入自定义状态");

        AgentContext restored = new AgentContext();
        session.restore(sessionId, restored);
        assertTrue(restored.get(AgentContext.PLAN) instanceof Map, "恢复后计划状态应回到上下文");
    }

    @Test
    void 再次按会话id运行时可恢复历史与状态() {
        String sessionId = session.create("会话二");
        session.append(sessionId, JSONMsgType.USER, "turn-1", userContent("第一轮"));
        AgentContext first = new AgentContext();
        first.put(AgentContext.SESSION_ID, sessionId);
        first.put("store:step", "1");
        agent().stream(first).collectList().block();

        // 新一轮：追加用户消息，再按同一会话 id 运行
        session.append(sessionId, JSONMsgType.USER, "turn-2", userContent("第二轮"));
        AgentContext second = new AgentContext();
        second.put(AgentContext.SESSION_ID, sessionId);
        agent().stream(second).collectList().block();

        assertEquals("1", second.get("store:step"), "上一轮的 store 状态应恢复到上下文");
        assertEquals(4, second.messages().size(), "恢复后应已有两轮问答共四条消息");
        assertEquals("第一轮", second.messages().get(0).content());
        assertEquals("处理完成", second.messages().get(1).content());
        assertEquals("第二轮", second.messages().get(2).content());

        String body = requestBody.get();
        assertTrue(body.contains("第一轮") && body.contains("第二轮"),
                "新一轮请求应携带完整历史：" + body);
    }

    @Test
    void 恢复会话后消息顺序为历史加本轮回复() {
        String sessionId = session.create("会话三");
        session.append(sessionId, JSONMsgType.USER, "turn-1", userContent("你好"));
        AgentContext runContext = new AgentContext();
        runContext.put(AgentContext.SESSION_ID, sessionId);
        agent().stream(runContext).collectList().block();

        AgentContext restored = new AgentContext();
        session.restore(sessionId, restored);

        List<ChatMessage> history = restored.messages();
        assertEquals(2, history.size());
        assertEquals(ChatMessage.Role.USER, history.get(0).role());
        assertEquals("你好", history.get(0).content());
        assertEquals(ChatMessage.Role.ASSISTANT, history.get(1).role());
        assertEquals("处理完成", history.get(1).content());
    }

    @Test
    void 纯文本轮次的思考内容也随会话落盘() {
        sseResponse.set(REASONING_SSE);
        String sessionId = session.create("思考会话");
        session.append(sessionId, JSONMsgType.USER, "turn-1", userContent("查一下错误日志"));

        AgentContext context = new AgentContext();
        context.put(AgentContext.SESSION_ID, sessionId);
        agent().stream(context).collectList().block();

        List<SessionMessage> stored = session.messages(sessionId);
        assertEquals(3, stored.size(), "用户、助手与调用记录三条消息");
        assertEquals(JSONMsgType.ASSISTANT, stored.get(1).type());
        assertEquals("先想一想", stored.get(1).content().get("reasoningContent"),
                "没有工具调用的轮次，思考内容同样要完整落盘");

        AgentContext restored = new AgentContext();
        session.restore(sessionId, restored);
        assertEquals("先想一想", restored.messages().get(1).reasoningContent(),
                "历史回放同样保留思考内容");
    }

    @Test
    void 纯文本轮次的思考内容不回传给模型() {
        sseResponse.set(REASONING_SSE);
        String sessionId = session.create("思考回放");
        session.append(sessionId, JSONMsgType.USER, "turn-1", userContent("第一轮"));
        AgentContext first = new AgentContext();
        first.put(AgentContext.SESSION_ID, sessionId);
        agent().stream(first).collectList().block();

        session.append(sessionId, JSONMsgType.USER, "turn-2", userContent("第二轮"));
        AgentContext second = new AgentContext();
        second.put(AgentContext.SESSION_ID, sessionId);
        agent().stream(second).collectList().block();

        String body = requestBody.get();
        assertTrue(body.contains("第一轮"), "第二轮请求应携带历史：" + body);
        assertFalse(body.contains("reasoning_content"), "纯文本轮次的思考内容不应回传：" + body);
    }

    private Agent agent() {
        return new Agent(model, List.of(), new ToolCall(), null, session,
                new UnloadedMessageStore(root), MemoryCompactConfig.defaults());
    }

    private static Map<String, Object> userContent(String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", ChatMessage.Role.USER.name());
        content.put("content", text);
        return content;
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
