package com.yf.wayfarer.web;

import com.sun.net.httpserver.HttpServer;
import com.yf.wayfarer.hitl.HitlService;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.SessionMessage;
import com.yf.wayfarer.session.SessionMeta;
import com.yf.wayfarer.session.UnloadedMessageStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        registry.add("wayfarer.prompt.root", () -> SESSION_ROOT.resolve("prompt").toString());
        registry.add("wayfarer.root-workspace", () -> SESSION_ROOT.resolve("root").toString());
        registry.add("wayfarer.model-config.root", () -> SESSION_ROOT.resolve("modelconfig").toString());
        registry.add("wayfarer.memory-compact-config.root",
                () -> SESSION_ROOT.resolve("memorycompactconfig").toString());
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
    UnloadedMessageStore unloadedStore;

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
                        .queryParam("traceId", "turn-5")
                        .queryParam("turns", 3)
                        .build(sessionId))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionMessage[].class)
                .returnResult()
                .getResponseBody();

        assertTrue(page != null);
        assertEquals(6, page.length, "应返回第 2、3、4 轮共六条消息");
        assertEquals("turn-2", page[0].traceId());
        assertEquals("问2", page[0].content().get("content"));
        assertEquals("turn-4", page[5].traceId());
    }

    @Test
    void 消息接口过滤内部状态快照() {
        String sessionId = session.create("过滤内部状态");
        session.append(sessionId, JSONMsgType.USER, "turn-1", userContent("问题"));
        session.append(sessionId, JSONMsgType.CUSTOM, "turn-1",
                Map.of("store:plan", Map.of("1", Map.of("content", "计划", "status", "done"))));
        session.append(sessionId, JSONMsgType.EXTRADATA, "turn-1", Map.of("finishReason", "stop"));

        SessionMessage[] visible = client.get().uri("/api/sessions/{id}/messages", sessionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionMessage[].class)
                .returnResult()
                .getResponseBody();

        assertTrue(visible != null);
        assertEquals(2, visible.length, "CUSTOM 不应下发给页面");
        for (SessionMessage message : visible) {
            assertNotEquals(JSONMsgType.CUSTOM, message.type());
        }
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
        assertEquals("turn-3", page[0].traceId());
    }

    @Test
    void 流式运行返回片段并写入会话文件() {
        String sessionId = session.create("流式会话");

        // 先通过接口追加用户消息，trace id 由调用方指定
        client.post().uri("/api/sessions/{id}/messages", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("traceId", "turn-web", "content", userContent("查日志")))
                .exchange()
                .expectStatus().isOk();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("traceId", "turn-web");
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
        assertEquals(5, stored.size(), "实际消息：" + stored);
        assertEquals("turn-web", stored.get(0).traceId());
        assertEquals(JSONMsgType.ASSISTANT, stored.get(1).type());
        assertEquals("turn-web", stored.get(1).traceId(), "助手消息应沿用调用方指定的 trace id");
        assertEquals(JSONMsgType.EXTRADATA, stored.get(2).type());
        assertEquals("cursor-9", stored.get(3).content().get("store:cursor"));
        assertEquals(JSONMsgType.TRACE_END, stored.get(4).type(), "TRACE_END 应是本轮最后一条写入");
    }

    @Test
    void 压缩后通过other事件实时推送压缩提示() throws Exception {
        Path configRoot = SESSION_ROOT.resolve("memorycompactconfig");
        Path config = configRoot.resolve("tiny.jsonl");
        Files.createDirectories(configRoot);
        // 用极小的上下文窗口触发一级、二级压缩，避免压缩路径依赖真实模型
        Files.writeString(config, """
                {"contextWindow":100,"triggerRatio":0.5,"toolRatio":0.05,"messageRatio":0.05,"compressRatio":0.5,"keepChars":10,"enabled":true}
                """ + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            String sessionId = session.create("压缩推送");
            session.append(sessionId, JSONMsgType.USER, "turn-1", userContent("一".repeat(2000)));
            session.append(sessionId, JSONMsgType.ASSISTANT, "turn-1", assistantContent("二".repeat(2000)));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("userquery", "继续");

            List<ServerSentEvent<String>> events = client.post()
                    .uri("/api/agent/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .exchange()
                    .expectStatus().isOk()
                    .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                    })
                    .getResponseBody()
                    .collectList()
                    .block();

            assertTrue(events != null);
            assertTrue(events.stream().anyMatch(event -> "other".equals(event.event())
                            && event.data() != null && event.data().contains("\"type\":\"compact\"")),
                    "运行中应以 other 事件推送压缩提示：" + events);
            assertTrue(session.messages(sessionId).stream()
                            .anyMatch(message -> message.type() == JSONMsgType.COMPACT),
                    "压缩快照应同时写入会话文件");
        } finally {
            Files.deleteIfExists(config);
        }
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
    void 专业版Agent同时携带基础工具与两个独立子Agent工具() {
        String sessionId = session.create("专业版工具");
        session.append(sessionId, JSONMsgType.USER, "turn-pro", userContent("规划一下"));

        client.post().uri("/api/agent/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("sessionId", sessionId, "agentName", "wayfarerpro"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();

        String request = MODEL_REQUEST.get();
        // 专业版必须继承基础版的全部能力
        assertTrue(request.contains("\"bash\""), "专业版应保留编码工具：" + request);
        assertTrue(request.contains("\"read\""), "专业版应保留读取工具：" + request);
        assertTrue(request.contains("\"plan\""), "专业版应保留计划工具：" + request);
        // 派发与取结果必须是两个互相独立的工具
        assertTrue(request.contains("\"subagent\""), "专业版应提供派发工具：" + request);
        assertTrue(request.contains("\"subagent_result\""), "专业版应提供取结果工具：" + request);
    }

    @Test
    void 基础版Agent不携带子Agent工具() {
        String sessionId = session.create("基础版工具");
        session.append(sessionId, JSONMsgType.USER, "turn-base", userContent("规划一下"));

        client.post().uri("/api/agent/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("sessionId", sessionId, "agentName", "wayfarer"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();

        String request = MODEL_REQUEST.get();
        assertTrue(request.contains("\"bash\""), "基础版应携带编码工具：" + request);
        assertFalse(request.contains("\"subagent\""), "基础版不应携带派发工具：" + request);
        assertFalse(request.contains("\"subagent_result\""), "基础版不应携带取结果工具：" + request);
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
                .bodyValue(Map.of("traceId", "t1"))
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

    @Test
    void 分支会话保留指定轮次在内的消息并复制卸载内容() {
        String sessionId = session.create("分支会话");
        String uuid = unloadedStore.save(sessionId, "第一轮的大段日志原文");
        session.append(sessionId, JSONMsgType.USER, "turn-1", userContent("一"));
        session.append(sessionId, JSONMsgType.ASSISTANT, "turn-1",
                assistantContent("摘要<sys-unloaded-" + uuid + ">"));
        session.append(sessionId, JSONMsgType.USER, "turn-2", userContent("二"));
        session.append(sessionId, JSONMsgType.ASSISTANT, "turn-2", assistantContent("二答"));

        Map<?, ?> forkedBody = client.post()
                .uri(uri -> uri.path("/api/sessions/{id}/fork").queryParam("traceId", "turn-2").build(sessionId))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertTrue(forkedBody != null);
        String forkedId = String.valueOf(forkedBody.get("sessionId"));

        List<SessionMessage> forked = session.messages(forkedId);
        assertEquals(4, forked.size(), "应保留到第二轮为止的四条消息");
        assertEquals("turn-1", forked.get(0).traceId());
        assertEquals("turn-2", forked.get(2).traceId());
        assertEquals("第一轮的大段日志原文", unloadedStore.read(forkedId, uuid),
                "卸载内容应随分支复制，否则占位符无法解析");
    }

    @Test
    void 复制会话保留全部消息与卸载内容() {
        String sessionId = session.create("复制会话");
        String uuid = unloadedStore.save(sessionId, "被卸载的长文本");
        session.append(sessionId, JSONMsgType.USER, "turn-1", userContent("一"));
        session.append(sessionId, JSONMsgType.ASSISTANT, "turn-1",
                assistantContent("摘要<sys-unloaded-" + uuid + ">"));

        Map<?, ?> clonedBody = client.post().uri("/api/sessions/{id}/clone", sessionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertTrue(clonedBody != null);
        String clonedId = String.valueOf(clonedBody.get("sessionId"));

        assertEquals(2, session.messages(clonedId).size());
        assertEquals("被卸载的长文本", unloadedStore.read(clonedId, uuid),
                "卸载内容应随复制带到新会话");
    }

    @Test
    void 回退会话删除指定轮次及之后的全部消息() {
        String sessionId = session.create("回退会话");
        session.append(sessionId, JSONMsgType.USER, "turn-1", userContent("一"));
        session.append(sessionId, JSONMsgType.ASSISTANT, "turn-1", assistantContent("一答"));
        session.append(sessionId, JSONMsgType.USER, "turn-2", userContent("二"));
        session.append(sessionId, JSONMsgType.ASSISTANT, "turn-2", assistantContent("二答"));

        client.post()
                .uri(uri -> uri.path("/api/sessions/{id}/reset").queryParam("traceId", "turn-2").build(sessionId))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.resetFrom").isEqualTo("turn-2");

        List<SessionMessage> remained = session.messages(sessionId);
        assertEquals(2, remained.size(), "第二轮及之后的消息应被删除");
        assertEquals("turn-1", remained.get(1).traceId());
    }

    @Test
    void 分支或回退到不存在的轮次时返回错误() {
        String sessionId = session.create("错误分支");
        session.append(sessionId, JSONMsgType.USER, "turn-1", userContent("一"));

        client.post()
                .uri(uri -> uri.path("/api/sessions/{id}/fork").queryParam("traceId", "turn-9").build(sessionId))
                .exchange()
                .expectStatus().isBadRequest();

        client.post()
                .uri(uri -> uri.path("/api/sessions/{id}/reset").queryParam("traceId", "turn-9").build(sessionId))
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
