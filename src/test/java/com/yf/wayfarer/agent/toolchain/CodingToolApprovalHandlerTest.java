package com.yf.wayfarer.agent.toolchain;

import com.yf.wayfarer.hitl.HitlService;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.toolchain.CodingToolApprovalHandler;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.FinishReason;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编码工具审批处理器测试，使用真实线程、真实文件系统路径与真实等待。
 *
 * @author yyf
 * @description 覆盖三种审批级别的拦截范围、待确认项 metadata 的结构，以及允许与拒绝两种结论
 */
class CodingToolApprovalHandlerTest {

    @Test
    @Timeout(10)
    void 不拦截时不产生待办也不阻塞() {
        HitlService hitl = new HitlService(5);
        AgentContext context = contextOf("no");
        AtomicBoolean passed = new AtomicBoolean(false);

        run(new CodingToolApprovalHandler(hitl), context, passed);

        join(passed, "不拦截时链路应立即继续");
        assertTrue(hitl.pending(null).isEmpty(), "不拦截时不应登记待办");
    }

    @Test
    @Timeout(10)
    void 全部拦截时登记待办并携带单选按钮组元数据() {
        HitlService hitl = new HitlService(5);
        AgentContext context = contextOf("all");
        AtomicBoolean passed = new AtomicBoolean(false);

        run(new CodingToolApprovalHandler(hitl), context, passed);

        HitlService.Pending pending = awaitPending(hitl);
        assertEquals("read", pending.toolName());
        assertEquals("{\"path\":\"src/Main.java\"}", pending.arguments());
        assertEquals("wayfarer", pending.agentName());

        @SuppressWarnings("unchecked")
        Map<String, Object> sheet = (Map<String, Object>) pending.metadata().get("sheet1");
        assertNotNull(sheet, "应携带 sheet1 审批页");
        assertEquals("允许", sheet.get("key1"));
        assertEquals("拒绝", sheet.get("key2"));
        assertEquals("radio group", sheet.get("style"));

        hitl.complete(pending.toolCallId(), Map.of("sheet1", "key1"));
        join(passed, "允许后链路应继续");
        assertTrue(context.messages().isEmpty(), "允许时不应产生短路结果");
    }

    @Test
    @Timeout(10)
    void 拒绝后短路并写入拒绝结果() throws Exception {
        HitlService hitl = new HitlService(5);
        AgentContext context = contextOf("all");
        AtomicBoolean passed = new AtomicBoolean(false);

        Thread worker = run(new CodingToolApprovalHandler(hitl), context, passed);

        HitlService.Pending pending = awaitPending(hitl);
        hitl.complete(pending.toolCallId(), Map.of("sheet1", "key2"));
        worker.join(5000);
        assertFalse(worker.isAlive(), "拒绝后处理器应立即结束");
        assertFalse(passed.get(), "拒绝后不应进入执行处理器");
        assertEquals(CodingToolApprovalHandler.DENY_RESULT, lastContent(context));
    }

    @Test
    @Timeout(10)
    void 工作区内读写直接放行() {
        HitlService hitl = new HitlService(5);
        AgentContext context = contextOf("workspacebased");
        AtomicBoolean passed = new AtomicBoolean(false);

        run(new CodingToolApprovalHandler(hitl), context, passed);

        join(passed, "工作区内路径不应等待审批");
        assertTrue(hitl.pending(null).isEmpty());
    }

    @Test
    @Timeout(10)
    void 工作区外路径需要审批() {
        HitlService hitl = new HitlService(5);
        AgentContext context = contextOf("workspacebased");
        setResponse(context, "read", "{\"path\":\"../outside.txt\"}");
        AtomicBoolean passed = new AtomicBoolean(false);

        run(new CodingToolApprovalHandler(hitl), context, passed);

        HitlService.Pending pending = awaitPending(hitl);
        hitl.complete(pending.toolCallId(), Map.of("sheet1", "key1"));
        join(passed, "审批后链路应继续");
    }

    @Test
    @Timeout(10)
    void 工作区模式下bash一律需要审批() {
        HitlService hitl = new HitlService(5);
        AgentContext context = contextOf("workspacebased");
        setResponse(context, "bash", "{\"command\":\"ls\"}");
        AtomicBoolean passed = new AtomicBoolean(false);

        run(new CodingToolApprovalHandler(hitl), context, passed);

        HitlService.Pending pending = awaitPending(hitl);
        assertEquals("bash", pending.toolName());
        hitl.complete(pending.toolCallId(), Map.of("sheet1", "key1"));
        join(passed, "审批后链路应继续");
    }

    @Test
    @Timeout(10)
    void 非编码工具不参与审批() {
        HitlService hitl = new HitlService(5);
        AgentContext context = contextOf("all");
        setResponse(context, "plan", "{\"items\":[]}");
        AtomicBoolean passed = new AtomicBoolean(false);

        run(new CodingToolApprovalHandler(hitl), context, passed);

        join(passed, "非编码工具不应等待审批");
        assertTrue(hitl.pending(null).isEmpty());
    }

    @Test
    @Timeout(10)
    void 超时按拒绝处理() throws Exception {
        HitlService hitl = new HitlService(1);
        AgentContext context = contextOf("all");
        AtomicBoolean passed = new AtomicBoolean(false);

        Thread worker = run(new CodingToolApprovalHandler(hitl), context, passed);

        awaitPending(hitl);
        worker.join(5000);
        assertFalse(worker.isAlive(), "超时后处理器应立即结束");
        assertFalse(passed.get(), "超时按拒绝处理，不应进入执行处理器");
        assertEquals(CodingToolApprovalHandler.DENY_RESULT, lastContent(context));
    }

    private static AgentContext contextOf(String approval) {
        AgentContext context = new AgentContext();
        context.put(AgentContext.AGENT_NAME, "wayfarer");
        context.put(AgentContext.SESSION_ID, "session-1");
        context.put(AgentContext.APPROVAL, approval);
        context.put(AgentContext.WORKSPACE, workspace().toString());
        context.put(AgentContext.MESSAGES, new ArrayList<>());
        setResponse(context, "read", "{\"path\":\"src/Main.java\"}");
        return context;
    }

    /**
     * 同时写入模型响应与当前工具调用，供单次调用处理器读取。
     */
    private static void setResponse(AgentContext context, String toolName, String arguments) {
        ModelResponse response = response(toolName, arguments);
        context.put(AgentContext.MODEL_RESPONSE, response);
        context.put(AgentContext.CURRENT_TOOL_CALL, response.toolCalls().get(0));
    }

    private static String lastContent(AgentContext context) {
        List<ChatMessage> messages = context.messages();
        return messages.isEmpty() ? null : messages.get(messages.size() - 1).content();
    }

    /**
     * 工作区固定为临时目录的父目录，保证 src/Main.java 位于其中、../outside.txt 位于其外。
     */
    private static Path workspace() {
        return TempDirHolder.ROOT.resolve("ws");
    }

    private static ModelResponse response(String toolName, String arguments) {
        return new ModelResponse("id", "deepseek-flash", 1L, "", "", FinishReason.TOOL_CALLS,
                List.of(new ToolCallInfo("call_1", toolName, arguments)), null);
    }

    private static Thread run(CodingToolApprovalHandler handler, AgentContext context, AtomicBoolean passed) {
        Thread worker = new Thread(() -> handler.run(context, () -> passed.set(true)));
        worker.setDaemon(true);
        worker.start();
        return worker;
    }

    private static void join(AtomicBoolean passed, String message) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!passed.get() && System.currentTimeMillis() < deadline) {
            sleep(10);
        }
        assertTrue(passed.get(), message);
    }

    private static HitlService.Pending awaitPending(HitlService hitl) {
        long deadline = System.currentTimeMillis() + 5000;
        while (hitl.pending(null).isEmpty() && System.currentTimeMillis() < deadline) {
            sleep(10);
        }
        List<HitlService.Pending> pendings = hitl.pending(null);
        assertTrue(!pendings.isEmpty(), "应登记待确认项");
        return pendings.get(0);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @author yyf
     * @description 惰性创建的临时根目录，供工作区用例共享
     */
    private static final class TempDirHolder {
        private static final Path ROOT = create();

        private static Path create() {
            try {
                return java.nio.file.Files.createTempDirectory("wayfarer-approval-");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
