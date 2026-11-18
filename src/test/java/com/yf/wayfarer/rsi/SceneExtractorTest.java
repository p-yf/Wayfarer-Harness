package com.yf.wayfarer.rsi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yf.wayfarer.model.ModelConfig;
import com.yf.wayfarer.model.openai.OpenAIChatModel;
import com.yf.wayfarer.rsi.model.MemoryItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SceneExtractor 场景整理 agent 端到端测试（本地 HTTP 桩模型跑真实 Loop 编排）。
 *
 * @author yyf
 * @description 覆盖：agent 用工具 CREATE 场景落盘（META + 正文）、结束后索引以文件为
 * 事实源重建、最终回复 [PERSONA_UPDATE_REQUEST] 信号透出为 Outcome（pipeline 据此写
 * cursor 触发 P1）；守卫在链上真实拦截
 */
class SceneExtractorTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private OpenAIChatModel model;
    private SceneIndex sceneIndex;
    private SceneExtractor extractor;

    @TempDir
    Path temp;

    private final AtomicInteger requestCount = new AtomicInteger();

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
        sceneIndex = new SceneIndex(new RsiPaths(temp.toString()));
        extractor = new SceneExtractor(sceneIndex, properties());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    private RsiProperties properties() {
        return new RsiProperties(true, temp.toString(),
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void agent建场景并透出画像信号() {
        // 第 1 轮：模型调用 create_scene；第 2 轮：最终回复带 [PERSONA_UPDATE_REQUEST]
        String round1 = """
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"create_scene","arguments":"{\\"scene\\":\\"构建环境\\",\\"content\\":\\"项目用 JDK 21\\",\\"summary\\":\\"构建约定\\"}"}}]},"finish_reason":null}]}

                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;
        String round2 = """
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"已创建场景：构建环境\\n[PERSONA_UPDATE_REQUEST] 用户在搭建个人构建环境"},"finish_reason":null}]}

                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        server.createContext("/", exchange ->
                respond(exchange, requestCount.incrementAndGet() == 1 ? round1 : round2));

        List<MemoryItem> newL1 = List.of(MemoryItem.of("M-1", "work_fact", "项目用 JDK 21",
                List.of(), 8, null, "t", "s-1"));
        List<SceneExtractor.SceneRef> refs = List.of(new SceneExtractor.SceneRef("旧场景", "占位摘要"));

        SceneExtractor.Outcome outcome =
                extractor.organize(model, "wayfarer-rsi", newL1, refs, refs.size());

        assertEquals(1, outcome.created());
        assertTrue(outcome.personaRequest(), "最终回复含 [PERSONA_UPDATE_REQUEST] 应透出为信号");

        // 场景文件真实落盘（META + 正文），索引以文件为事实源重建
        Path sceneFile = Path.of(temp.toString(), "stores", "wayfarer-rsi", "memory",
                "scenes", "构建环境.md");
        assertTrue(Files.isRegularFile(sceneFile));
        String text = readAll(sceneFile);
        assertTrue(text.contains("构建约定"));
        assertTrue(text.contains("JDK 21"));
        assertTrue(sceneIndex.load("wayfarer-rsi").containsKey("构建环境"));
    }

    private static String readAll(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
