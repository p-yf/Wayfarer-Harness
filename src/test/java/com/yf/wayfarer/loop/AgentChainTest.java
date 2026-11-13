package com.yf.wayfarer.loop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.FinishReason;
import com.yf.wayfarer.model.ModelConfig;
import com.yf.wayfarer.model.openai.OpenAIChatModel;
import com.yf.wayfarer.agent.modelchain.MemoryManageHandler;
import com.yf.wayfarer.agent.modelchain.ModelHandler;
import com.yf.wayfarer.agent.toolchain.ToolCallHandler;
import com.yf.wayfarer.toolcall.Tool;
import com.yf.wayfarer.toolcall.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用真实本地 HTTP 服务验证模型链与工具链的完整一轮工具调用循环，不使用任何模拟对象。
 *
 * @author yyf
 * @description 覆盖模型调用、记忆逐轮拼接、工具执行与消息推送顺序
 */
class AgentChainTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private OpenAIChatModel model;

    private final AtomicInteger requestCount = new AtomicInteger();
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
    void 模型链与工具链完成一轮工具调用循环() {
        String round1 = """
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"inspect_logs","arguments":"{}"}}]},"finish_reason":null}]}

                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;
        String round2 = """
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"检查完成"},"finish_reason":null}]}

                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, requestCount.incrementAndGet() == 1 ? round1 : round2);
        });

        ToolCall toolCall = new ToolCall();
        toolCall.parse(new LogTools());

        Chain modelChain = Chain.of(new ModelHandler(model, List.of()), new MemoryManageHandler());
        Chain toolChain = Chain.of(new ToolCallHandler(toolCall));

        AgentContext context = new AgentContext();
        context.put(AgentContext.TRACE_ID, "trace-1");
        context.put(AgentContext.MESSAGES, new ArrayList<>(List.of(
                new ChatMessage(ChatMessage.Role.USER, "检查日志"))));

        StepVerifier.create(new Loop(modelChain, toolChain).run(context))
                .expectNextMatches(msg -> msg instanceof AgentMessage.End end
                        && "trace-1".equals(end.traceId())
                        && end.finishReason() == FinishReason.TOOL_CALLS)
                .expectNextMatches(msg -> msg instanceof AgentMessage.ToolCall call
                        && "trace-1".equals(call.traceId())
                        && "call_1".equals(call.call().id())
                        && "inspect_logs".equals(call.call().name()))
                .expectNextMatches(msg -> msg instanceof AgentMessage.ToolResult result
                        && "call_1".equals(result.toolCallId())
                        && "128条错误".equals(result.content()))
                .expectNextMatches(msg -> msg instanceof AgentMessage.Text text
                        && "检查完成".equals(text.content()))
                .expectNextMatches(msg -> msg instanceof AgentMessage.End end
                        && end.finishReason() == FinishReason.STOP)
                .verifyComplete();

        // 第二次请求应回传工具调用、结果与完整历史
        String secondBody = requestBody.get();
        assertTrue(secondBody.contains("\"tool_calls\""), "第二次请求应回传 tool_calls，实际为 " + secondBody);
        assertTrue(secondBody.contains("\"tool_call_id\":\"call_1\""), "第二次请求应回传 tool_call_id，实际为 " + secondBody);
        assertTrue(secondBody.contains("128条错误"), "第二次请求应回传工具结果，实际为 " + secondBody);

        // 记忆按轮拼接：user -> assistant(工具调用) -> tool(结果) -> assistant(最终回答)
        List<ChatMessage> messages = context.messages();
        assertEquals(4, messages.size());
        assertEquals(ChatMessage.Role.ASSISTANT, messages.get(1).role());
        assertEquals(1, messages.get(1).toolCalls().size());
        assertEquals(ChatMessage.Role.TOOL, messages.get(2).role());
        assertEquals("128条错误", messages.get(2).content());
        assertEquals(ChatMessage.Role.ASSISTANT, messages.get(3).role());
        assertEquals("检查完成", messages.get(3).content());
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

    /**
     * @author yyf
     * @description 供工具链真实执行的工具对象
     */
    static class LogTools {

        @Tool(name = "inspect_logs", description = "检查日志")
        public String inspectLogs() {
            return "128条错误";
        }
    }
}
