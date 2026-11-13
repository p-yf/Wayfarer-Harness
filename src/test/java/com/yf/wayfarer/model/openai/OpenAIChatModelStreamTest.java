package com.yf.wayfarer.model.openai;

import com.sun.net.httpserver.HttpServer;
import com.yf.wayfarer.model.ChatChunk;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ChatRequest;
import com.yf.wayfarer.model.FinishReason;
import com.yf.wayfarer.model.ModelConfig;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import com.yf.wayfarer.toolcall.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用真实本地 HTTP 服务验证流式解析与取消行为，不使用任何模拟对象。
 *
 * @author yyf
 * @description 覆盖流式解析、取消关闭连接、惰性请求与 tool 角色映射
 */
class OpenAIChatModelStreamTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private OpenAIChatModel model;

    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
        model = new OpenAIChatModel(new ModelConfig(
                "test-key",
                "http://localhost:" + server.getAddress().getPort() + "/v1",
                "deepseek-flash",
                null,
                null));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void 解析流式增量并在结束时给出结束原因() throws Exception {
        String sse = """
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"你"},"finish_reason":null}]}

                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"好"},"finish_reason":null}]}

                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        serve(exchange -> {
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });

        ChatRequest request = new ChatRequest(List.of(
                new ChatMessage(ChatMessage.Role.USER, "你好")));

        StepVerifier.create(model.stream(request))
                .expectNextMatches(chunk -> "你".equals(chunk.content()) && chunk.finishReason() == null)
                .expectNextMatches(chunk -> "好".equals(chunk.content()) && chunk.finishReason() == null)
                .expectNextMatches(chunk -> chunk.finishReason() == FinishReason.STOP)
                .expectNextMatches(chunk -> {
                    ModelResponse response = chunk.response();
                    return "".equals(chunk.content())
                            && response != null
                            && "你好".equals(response.content())
                            && response.finishReason() == FinishReason.STOP
                            && "deepseek-flash".equals(response.model());
                })
                .verifyComplete();

        assertEquals("/v1/chat/completions", requestPath.get());
        assertTrue(requestBody.get().contains("\"model\":\"deepseek-flash\""),
                "请求体应包含模型名，实际为 " + requestBody.get());
    }

    @Test
    void 取消订阅后服务端连接被关闭() throws Exception {
        CountDownLatch clientDisconnected = new CountDownLatch(1);

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    readHttpRequest(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/event-stream\r\n"
                            + "Transfer-Encoding: chunked\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    writeChunk(out, """
                            data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"x"},"finish_reason":null}]}

                            """);
                    out.flush();
                    // 流不会自行结束；客户端取消后连接被关闭，读取会返回 -1 或抛异常
                    InputStream in = socket.getInputStream();
                    while (in.read() != -1) {
                        // 忽略客户端后续数据，等待连接关闭
                    }
                    clientDisconnected.countDown();
                } catch (IOException e) {
                    clientDisconnected.countDown();
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            OpenAIChatModel realServerModel = new OpenAIChatModel(new ModelConfig(
                    "test-key",
                    "http://localhost:" + serverSocket.getLocalPort() + "/v1",
                    "deepseek-flash",
                    null,
                    null));

            ChatRequest request = new ChatRequest(List.of(
                    new ChatMessage(ChatMessage.Role.USER, "你好")));

            AtomicInteger received = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch firstChunk = new CountDownLatch(1);

            Disposable subscription = realServerModel.stream(request).subscribe(chunk -> {
                received.incrementAndGet();
                firstChunk.countDown();
            }, error::set);

            assertTrue(firstChunk.await(5, TimeUnit.SECONDS), "应至少收到一个流式片段");
            int beforeCancel = received.get();

            subscription.dispose();

            assertTrue(clientDisconnected.await(10, TimeUnit.SECONDS),
                    "取消订阅后底层 HTTP 连接应被关闭");
            assertNull(error.get(), "取消后不应收到错误信号");
            assertEquals(beforeCancel, received.get(), "取消后不应再收到片段");
        }
    }

    private static void readHttpRequest(InputStream in) throws IOException {
        StringBuilder headers = new StringBuilder();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b == -1) {
                throw new IOException("请求头未读取完整");
            }
            headers.append((char) b);
            matched = switch (matched) {
                case 0 -> b == '\r' ? 1 : 0;
                case 1 -> b == '\n' ? 2 : 0;
                case 2 -> b == '\r' ? 3 : 0;
                case 3 -> b == '\n' ? 4 : 0;
                default -> 0;
            };
        }
        int contentLength = 0;
        for (String line : headers.toString().split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        in.readNBytes(contentLength);
    }

    private static void writeChunk(OutputStream out, String event) throws IOException {
        byte[] bytes = event.getBytes(StandardCharsets.UTF_8);
        out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 未订阅时不发起网络请求() {
        serve(exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        ChatRequest request = new ChatRequest(List.of(
                new ChatMessage(ChatMessage.Role.USER, "你好")));

        model.stream(request);

        assertEquals(0, requestCount.get());
    }

    @Test
    void tool角色消息映射为tool_call_id() {
        String sse = """
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        serve(exchange -> {
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });

        ChatRequest request = new ChatRequest(List.of(
                new ChatMessage(ChatMessage.Role.USER, "查一下"),
                new ChatMessage(ChatMessage.Role.TOOL, "结果", "call_1")));

        StepVerifier.create(model.stream(request))
                .expectNextMatches(chunk -> "ok".equals(chunk.content()))
                .expectNextMatches(chunk -> chunk.response() != null)
                .verifyComplete();

        String body = requestBody.get();
        assertTrue(body.contains("\"role\":\"tool\""), "请求体应包含 tool 角色，实际为 " + body);
        assertTrue(body.contains("\"tool_call_id\":\"call_1\""), "请求体应包含 tool_call_id，实际为 " + body);
    }

    @Test
    void assistant思考内容与工具调用回传到请求体() {
        serveSse("""
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}

                data: [DONE]

                """);

        ChatRequest request = new ChatRequest(List.of(
                new ChatMessage(ChatMessage.Role.SYSTEM, "你是日志分析助手"),
                new ChatMessage(ChatMessage.Role.USER, "检查错误高峰"),
                ChatMessage.assistant("", "需要先检查日志", List.of(
                        new ToolCallInfo("call_logs_001", "inspect_logs",
                                "{\"window_start\":\"10:00\",\"window_end\":\"10:15\"}"))),
                new ChatMessage(ChatMessage.Role.TOOL, "{\"error_count\":128}", "call_logs_001")));

        StepVerifier.create(model.stream(request))
                .expectNextCount(2)
                .verifyComplete();

        String body = requestBody.get();
        assertTrue(body.contains("\"reasoning_content\":\"需要先检查日志\""),
                "请求体应回传 reasoning_content，实际为 " + body);
        assertTrue(body.contains("\"tool_calls\""), "请求体应包含 tool_calls，实际为 " + body);
        assertTrue(body.contains("\"id\":\"call_logs_001\""), "请求体应包含工具调用 id，实际为 " + body);
        assertTrue(body.contains("\"name\":\"inspect_logs\""), "请求体应包含工具名，实际为 " + body);
        assertTrue(body.contains("window_start") && body.contains("10:00"), "请求体应包含工具调用参数，实际为 " + body);
        assertTrue(body.contains("\"tool_call_id\":\"call_logs_001\""), "请求体应包含 tool_call_id，实际为 " + body);
    }

    @Test
    void 温度与最大token下发到请求体() {
        String sse = """
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        serve(exchange -> {
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });

        OpenAIChatModel configuredModel = new OpenAIChatModel(new ModelConfig(
                "test-key",
                "http://localhost:" + server.getAddress().getPort() + "/v1",
                "deepseek-flash",
                0.3,
                128L));

        StepVerifier.create(configuredModel.stream(new ChatRequest(List.of(
                        new ChatMessage(ChatMessage.Role.USER, "你好")))))
                .expectNextCount(2)
                .verifyComplete();

        String body = requestBody.get();
        assertTrue(body.contains("\"temperature\":0.3"), "请求体应包含 temperature，实际为 " + body);
        assertTrue(body.contains("\"max_tokens\":128"), "请求体应包含 max_tokens，实际为 " + body);
    }

    @Test
    void 工具定义下发到请求体() {
        serveSse("""
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}

                data: [DONE]

                """);

        ChatRequest request = new ChatRequest(
                List.of(new ChatMessage(ChatMessage.Role.USER, "北京天气")),
                List.of(new ToolDefinition("get_weather", "查询天气", Map.of(
                        "type", "object",
                        "properties", Map.of("city", Map.of("type", "string")),
                        "required", List.of("city")))));

        StepVerifier.create(model.stream(request))
                .expectNextCount(2)
                .verifyComplete();

        String body = requestBody.get();
        assertTrue(body.contains("\"tools\""), "请求体应包含 tools，实际为 " + body);
        assertTrue(body.contains("\"name\":\"get_weather\""), "请求体应包含工具名，实际为 " + body);
        assertTrue(body.contains("\"city\""), "请求体应包含工具参数，实际为 " + body);
    }

    @Test
    void 完整响应汇总文本与工具调用() {
        serveSse("""
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"我来查"},"finish_reason":null}]}

                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\\"city\\":"}}]},"finish_reason":null}]}

                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"北京\\"}"}}]},"finish_reason":null}]}

                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """);

        ChatRequest request = new ChatRequest(List.of(
                new ChatMessage(ChatMessage.Role.USER, "北京天气")));

        List<ChatChunk> chunks = model.stream(request).collectList().block();

        assertNotNull(chunks);
        ModelResponse response = chunks.get(chunks.size() - 1).response();
        assertNotNull(response, "最后一片 chunk 应携带完整响应");
        assertEquals("我来查", response.content());
        assertEquals(FinishReason.TOOL_CALLS, response.finishReason());
        assertEquals(1, response.toolCalls().size());
        assertEquals("call_1", response.toolCalls().get(0).id());
        assertEquals("get_weather", response.toolCalls().get(0).name());
        assertEquals("{\"city\":\"北京\"}", response.toolCalls().get(0).arguments());
    }

    private void serveSse(String sse) {
        serve(exchange -> {
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
    }

    private void serve(ExchangeHandler handler) {
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            handler.handle(exchange);
        });
    }

    /**
     * @author yyf
     * @description 允许抛出 IOException 的请求处理器
     */
    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}
