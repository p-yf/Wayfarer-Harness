package com.yf.wayfarer.web;

import com.sun.net.httpserver.HttpServer;
import com.yf.wayfarer.hitl.HitlService;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.SessionMessage;
import com.yf.wayfarer.session.SessionMeta;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * web 接口测试，使用真实 HTTP 服务与真实文件系统，不使用模拟对象。
 *
 * @author yyf
 * @description 覆盖会话创建与列表、按轮次滑动取历史、流式运行与终止
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentWebTest {

    private static final String SSE = """
            data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"处理完成"},"finish_reason":null}]}

            data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    private static final HttpServer MODEL_SERVER;
    private static final Path SESSION_ROOT;
    private static final AtomicReference<String> MODEL_REQUEST = new AtomicReference<>("");

    static {
        try {
            MODEL_SERVER = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            MODEL_SERVER.createContext("/", exchange -> {
                // 记录请求体，供断言系统提示词与历史是否送达模型
                MODEL_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = SSE.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                exchange.close();
            });
            MODEL_SERVER.start();
            SESSION_ROOT = Files.createTempDirectory("wayfarer-web");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("wayfarer.model.api-key", () -> "test-key");
        registry.add("wayfarer.model.base-url",
                () -> "http://localhost:" + MODEL_SERVER.getAddress().getPort());
        registry.add("wayfarer.session.root", SESSION_ROOT::toString);
        registry.add("wayfarer.unload.root", () -> SESSION_ROOT.resolve("unloaded").toString());
        registry.add("wayfarer.prompt.root", () -> SESSION_ROOT.resolve("prompt").toString());
        registry.add("wayfarer.agent.max-turns", () -> 1);
    }

    @AfterAll
    static void stopServer() {
        MODEL_SERVER.stop(0);
    }

    @Autowired
    WebTestClient client;

    @Autowired
    Session session;

    @Autowired
    HitlService hitlService;

    @Test
    void 创建会话并出现在会话列表中() {
        SessionMeta created = client.post().uri("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "网页会话"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionMeta.class)
                .returnResult()
                .getResponseBody();

        assertTrue(created != null && created.id() != null);
        assertEquals("网页会话", created.name());

        SessionMeta[] all = client.get().uri("/api/sessions")
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionMeta[].class)
                .returnResult()
                .getResponseBody();

        assertTrue(all != null && all.length >= 1);
    }

    @Test
    void 按轮次id滑动返回之前三轮历史() {
        String sessionId = session.create("滑动历史");
        for (int turn = 1; turn <= 5; turn++) {
            session.append(sessionId, JSONMsgType.USER, "turn-" + turn, userContent("问" + turn));
            session.append(sessionId, JSONMsgType.ASSISTANT, "turn-" + turn, assistantContent("答" + turn));
        }

        SessionMessage[] page = client.get()
                .uri(uri -> uri.path("/api/sessions/{id}/history")
                        .queryParam("turnId", "turn-5")
                        .queryParam("turns", 3)
                        .build(sessionId))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionMessage[].class)
                .returnResult()
                .getResponseBody();

        assertTrue(page != null);
        assertEquals(6, page.length, "应返回第 2、3、4 轮共六条消息");
        assertEquals("turn-2", page[0].turnId());
        assertEquals("问2", page[0].content().get("content"));
        assertEquals("turn-4", page[5].turnId());
    }

    @Test
    void 不传轮次id时返回最近三轮() {
        String sessionId = session.create("最近历史");
        for (int turn = 1; turn <= 5; turn++) {
            session.append(sessionId, JSONMsgType.USER, "turn-" + turn, userContent("问" + turn));
        }

        SessionMessage[] page = client.get()
                .uri(uri -> uri.path("/api/sessions/{id}/history").queryParam("turns", 3).build(sessionId))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionMessage[].class)
                .returnResult()
                .getResponseBody();

        assertTrue(page != null);
        assertEquals(3, page.length);
        assertEquals("turn-3", page[0].turnId());
    }

    @Test
    void 流式运行返回片段并写入会话文件() {
        String sessionId = session.create("流式会话");

        // 先通过接口追加用户消息，轮次 id 由调用方指定
        client.post().uri("/api/sessions/{id}/messages", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("turnId", "turn-web", "content", userContent("查日志")))
                .exchange()
                .expectStatus().isOk();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("turnId", "turn-web");
        payload.put("store:cursor", "cursor-9");

        List<String> events = client.post().uri("/api/agent/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block();

        assertTrue(events != null && !events.isEmpty());
        assertTrue(String.join("", events).contains("处理完成"), "流中应包含模型输出：" + events);
        assertFalse(String.join("", events).contains("\"chunk\""), "不应再推送内部消息外壳：" + events);

        List<SessionMessage> stored = session.messages(sessionId);
        assertEquals(3, stored.size());
        assertEquals("turn-web", stored.get(0).turnId());
        assertEquals(JSONMsgType.ASSISTANT, stored.get(1).type());
        assertEquals("turn-web", stored.get(1).turnId(), "助手消息应沿用调用方指定的轮次 id");
        assertEquals("cursor-9", stored.get(2).content().get("store:cursor"));
    }

    @Test
    void 空闲会话终止时返回未终止() {
        String sessionId = session.create("空会话");

        Map<?, ?> result = client.post().uri("/api/agent/stop/{id}", sessionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();

        assertTrue(result != null);
        assertFalse((Boolean) result.get("stopped"));
    }

    @Test
    void 系统提示词按顺序拼接并送达模型() {
        // 顺序 2 先存、顺序 1 后存，验证按顺序而非写入顺序拼接
        client.put().uri("/api/prompts/{a}/{n}/{o}", "wayfarer", "role", 2)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "第二条提示词"))
                .exchange()
                .expectStatus().isOk();
        client.put().uri("/api/prompts/{a}/{n}/{o}", "wayfarer", "role", 1)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "第一条提示词"))
                .exchange()
                .expectStatus().isOk();

        String sessionId = session.create("提示词会话");
        session.append(sessionId, JSONMsgType.USER, "turn-1", userContent("你好"));

        client.post().uri("/api/agent/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("sessionId", sessionId))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();

        String request = MODEL_REQUEST.get();
        assertTrue(request.contains("第一条提示词"), "请求应包含第一条提示词：" + request);
        assertTrue(request.contains("第二条提示词"), "请求应包含第二条提示词：" + request);
        assertTrue(request.indexOf("第一条提示词") < request.indexOf("第二条提示词"),
                "提示词应按顺序拼接：" + request);
    }

    @Test
    void 提示词支持查询与删除() {
        client.put().uri("/api/prompts/{a}/{n}/{o}", "wayfarer", "extra", 3)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "可删除的提示词"))
                .exchange()
                .expectStatus().isOk();

        Map<?, ?> single = client.get().uri("/api/prompts/{a}/{n}/{o}", "wayfarer", "extra", 3)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertTrue(single != null && "可删除的提示词".equals(single.get("content")));

        Map<?, ?> deleted = client.delete().uri("/api/prompts/{a}/{n}/{o}", "wayfarer", "extra", 3)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertTrue(deleted != null && Boolean.TRUE.equals(deleted.get("deleted")));

        client.get().uri("/api/prompts/{a}/{n}/{o}", "wayfarer", "extra", 3)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void 删除会话后列表中不再出现() {
        String sessionId = session.create("待删除会话");
        session.append(sessionId, JSONMsgType.USER, "turn-1", userContent("一"));

        client.delete().uri("/api/sessions/{id}", sessionId)
                .exchange()
                .expectStatus().isOk();

        SessionMeta[] all = client.get().uri("/api/sessions")
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionMeta[].class)
                .returnResult()
                .getResponseBody();

        assertTrue(all != null);
        assertFalse(java.util.Arrays.stream(all).anyMatch(item -> sessionId.equals(item.id())));
    }

    @Test
    void 缺少会话id时返回错误() {
        client.post().uri("/api/agent/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("turnId", "t1"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void 人工确认接口写入决定() throws Exception {
        AgentContext context = new AgentContext();
        context.put(AgentContext.SESSION_ID, "hitl-session");
        context.put(AgentContext.AGENT_NAME, "wayfarer");
        context.put(AgentContext.TOOL_CALL_ID, "call_1");
        context.put(AgentContext.TOOL_NAME, "shell");
        AtomicReference<Map<String, Object>> decision = new AtomicReference<>();
        // 工具在执行过程中自行等待决定，放到独立线程模拟真实运行环境
        Thread worker = new Thread(() -> decision.set(hitlService.await(context)));
        worker.setDaemon(true);
        worker.start();
        waitPending();

        Map<?, ?> result = client.post().uri("/api/hitl")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("toolCallId", "call_1", "approved", true))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();

        worker.join(5000);
        assertTrue(result != null && Boolean.TRUE.equals(result.get("delivered")));
        assertEquals(true, decision.get().get("approved"), "请求体应整体写入等待项");

        HitlService.Pending[] pending = client.get()
                .uri(uri -> uri.path("/api/hitl/pending").queryParam("agent", "wayfarer").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(HitlService.Pending[].class)
                .returnResult()
                .getResponseBody();
        assertTrue(pending != null && pending.length == 0, "确认后不应再有待办");
    }

    @Test
    void 没有待确认项时返回错误() {
        client.post().uri("/api/hitl")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("toolCallId", "nobody-waiting"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    private void waitPending() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (hitlService.pending("wayfarer").isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, hitlService.pending("wayfarer").size(), "应登记待确认项");
    }

    private static Map<String, Object> userContent(String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "USER");
        content.put("content", text);
        return content;
    }

    private static Map<String, Object> assistantContent(String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "ASSISTANT");
        content.put("content", text);
        return content;
    }
}
