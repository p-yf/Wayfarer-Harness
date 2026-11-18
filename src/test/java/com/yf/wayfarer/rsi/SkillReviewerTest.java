package com.yf.wayfarer.rsi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yf.wayfarer.model.ModelConfig;
import com.yf.wayfarer.model.openai.OpenAIChatModel;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillReviewer 技能评审 agent 端到端测试（本地 HTTP 桩模型跑真实 Loop 编排）。
 *
 * @author yyf
 * @description 覆盖：agent 用工具 CREATE 技能落盘（frontmatter 由工具组装）、
 * record_preferences 收集的偏好事实透出为 Outcome.toMemory（pipeline 据此转投 L1）
 */
class SkillReviewerTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private OpenAIChatModel model;
    private SkillReviewer reviewer;

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
        reviewer = new SkillReviewer(properties(), new RsiPaths(temp.toString()));
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
    void agent建技能并汇总计数() {
        // 第 1 轮：模型调用 create_skill；第 2 轮：最终回复
        String round1 = """
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"create_skill","arguments":"{\\"name\\":\\"gradle-mirror\\",\\"type\\":\\"sop\\",\\"description\\":\\"Gradle 走国内镜像的配置步骤\\",\\"content\\":\\"# 步骤\\\\n\\\\n1. 修改 settings.gradle.kts 换镜像\\"}"}}]},"finish_reason":null}]}

                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;
        String round2 = """
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"已创建技能 gradle-mirror。"},"finish_reason":null}]}

                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        server.createContext("/", exchange ->
                respond(exchange, requestCount.incrementAndGet() == 1 ? round1 : round2));

        SkillReviewer.Outcome outcome = reviewer.review(model, "wayfarer-rsi", "s-1",
                "USER: 配置了 Gradle 镜像\nASSISTANT: 完成", List.of(), false, 0);

        assertEquals(1, outcome.created());
        assertEquals(0, outcome.updated() + outcome.patched());
        assertTrue(outcome.toMemory().isEmpty());

        // 技能文件真实落盘，frontmatter 由工具统一组装
        Path file = Path.of(temp.toString(), "stores", "wayfarer-rsi", "skills",
                "gradle-mirror", "SKILL.md");
        assertTrue(Files.isRegularFile(file));
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(text.startsWith("---\nname: gradle-mirror\ntype: sop\ndescription: Gradle 走国内镜像的配置步骤\n---"));
            assertTrue(text.contains("settings.gradle.kts"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void 偏好事实经工具收集转投记忆() {
        // 第 1 轮：模型调用 record_preferences；第 2 轮：最终回复
        String round1 = """
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"record_preferences","arguments":"{\\"facts\\":\\"用户偏好中文注释\\\\n构建工具固定用 Gradle\\"}"}}]},"finish_reason":null}]}

                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;
        String round2 = """
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"本次为偏好类经验，已转投记忆库。"},"finish_reason":null}]}

                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        server.createContext("/", exchange ->
                respond(exchange, requestCount.incrementAndGet() == 1 ? round1 : round2));

        SkillReviewer.Outcome outcome = reviewer.review(model, "wayfarer-rsi", "s-1",
                "USER: 我喜欢中文注释\nASSISTANT: 好的", List.of(), false, 0);

        assertTrue(outcome.created() + outcome.updated() + outcome.patched() == 0);
        assertEquals(2, outcome.toMemory().size());
        assertTrue(outcome.toMemory().contains("用户偏好中文注释"));
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
