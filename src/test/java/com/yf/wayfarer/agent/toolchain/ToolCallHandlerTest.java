package com.yf.wayfarer.agent.toolchain;

import com.yf.wayfarer.hitl.HitlService;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.FinishReason;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.Tool;
import com.yf.wayfarer.toolcall.ToolCall;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具链人工确认测试，使用真实工具反射调用、真实线程与真实文件。
 *
 * @author yyf
 * @description 工具在执行过程中自行调用 HITL 等待决定：批准后继续、拒绝或超时由工具自行判定
 */
class ToolCallHandlerTest {

    private final HitlService hitl = new HitlService(1);
    private final GuardedTools tools = new GuardedTools(hitl);
    private final ToolCall toolCall = parseTools();

    private ToolCall parseTools() {
        ToolCall call = new ToolCall();
        call.parse(tools);
        return call;
    }

    @Test
    @Timeout(10)
    void 工具等待到批准后继续执行() throws Exception {
        AgentContext context = contextOf("write_file");
        CountDownLatch done = new CountDownLatch(1);

        run(context, done);

        HitlService.Pending pending = awaitPending();
        assertEquals("call_1", pending.toolCallId());
        assertEquals("write_file", pending.toolName());
        assertTrue(hitl.complete("call_1", Map.of("approved", true)));
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(1, tools.writes.get());
        assertEquals("已写入", lastToolContent(context));
    }

    @Test
    @Timeout(10)
    void 工具收到拒绝后自行返回无权限() throws Exception {
        AgentContext context = contextOf("write_file");
        CountDownLatch done = new CountDownLatch(1);

        run(context, done);

        awaitPending();
        hitl.complete("call_1", Map.of("approved", false, "reason", "先别动"));
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(0, tools.writes.get());
        assertEquals("没有写入权限", lastToolContent(context));
    }

    @Test
    @Timeout(15)
    void 等待超时后工具自行返回无权限() throws Exception {
        AgentContext context = contextOf("write_file");
        CountDownLatch done = new CountDownLatch(1);

        run(context, done);

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(0, tools.writes.get());
        assertEquals("没有写入权限", lastToolContent(context));
    }

    @Test
    @Timeout(10)
    void 不需要鉴权的工具不产生待办() throws Exception {
        AgentContext context = contextOf("read_file");
        CountDownLatch done = new CountDownLatch(1);

        run(context, done);

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(hitl.pending("wayfarer").isEmpty(), "不需要鉴权时不应产生待办");
        assertEquals("文件内容", lastToolContent(context));
    }

    @Test
    @Timeout(10)
    void 执行期间上下文带有工具id与名称() throws Exception {
        AgentContext context = contextOf("read_file");
        CountDownLatch done = new CountDownLatch(1);

        run(context, done);
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals("call_1", context.toolCallId());
        assertEquals("read_file", context.toolName());
    }

    private HitlService.Pending awaitPending() {
        long deadline = System.currentTimeMillis() + 5000;
        while (hitl.pending("wayfarer").isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        List<HitlService.Pending> pendings = hitl.pending("wayfarer");
        assertTrue(!pendings.isEmpty(), "应登记待确认项");
        return pendings.get(0);
    }

    private void run(AgentContext context, CountDownLatch done) {
        // 工具会在执行中阻塞等待决定，放到独立线程模拟真实运行环境
        new ToolCallHandler(toolCall).run(context, Mono.empty())
                .doFinally(ignored -> done.countDown())
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    private static AgentContext contextOf(String toolName) {
        AgentContext context = new AgentContext();
        context.put(AgentContext.SESSION_ID, "session-1");
        context.put(AgentContext.AGENT_NAME, "wayfarer");
        context.put(AgentContext.MESSAGES, new ArrayList<>(List.of(
                new ChatMessage(ChatMessage.Role.USER, "执行操作"))));
        context.put(AgentContext.MODEL_RESPONSE, toolCallResponse(toolName));
        context.put(AgentContext.AGENT_MSG_SINK, (Consumer<AgentMessage>) ignored -> {
        });
        return context;
    }

    private static String lastToolContent(AgentContext context) {
        List<ChatMessage> messages = context.messages();
        return messages.get(messages.size() - 1).content();
    }

    private static ModelResponse toolCallResponse(String toolName) {
        return new ModelResponse("id", "deepseek-flash", 1L, "", "", FinishReason.TOOL_CALLS,
                List.of(new ToolCallInfo("call_1", toolName, "{}")), null);
    }

    /**
     * @author yyf
     * @description 供工具链真实反射调用的工具集，写操作在执行中自行等待人工确认
     */
    static class GuardedTools {

        private final HitlService hitl;
        private final AtomicInteger writes = new AtomicInteger();

        GuardedTools(HitlService hitl) {
            this.hitl = hitl;
        }

        @Tool(name = "write_file", description = "写文件")
        public String writeFile(AgentContext context) {
            Map<String, Object> decision = hitl.await(context);
            if (decision == null || !Boolean.TRUE.equals(decision.get("approved"))) {
                return "没有写入权限";
            }
            writes.incrementAndGet();
            return "已写入";
        }

        @Tool(name = "read_file", description = "读文件")
        public String readFile() {
            return "文件内容";
        }
    }
}
