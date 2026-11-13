package com.yf.wayfarer.hitl;

import com.yf.wayfarer.loop.AgentContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人工确认协调器测试，使用真实线程、真实超时与真实上下文，不使用模拟对象。
 *
 * @author yyf
 * @description 工具在执行过程中自行等待 HITL+toolCallId 的值：覆盖按 id 写入决定、上下文等待值、
 * 并发多条待办、超时返回 null 与清理
 */
class HitlServiceTest {

    @Test
    @Timeout(10)
    void 工具等待到决定后继续执行() {
        HitlService service = new HitlService(5);
        AgentContext context = contextOf("call_1", "shell");
        AtomicReference<Map<String, Object>> decision = new AtomicReference<>();

        Thread worker = awaitInThread(service, context, decision);
        awaitPending(service, 1);

        assertEquals("call_1", service.pending("wayfarer").get(0).toolCallId());
        assertEquals("shell", service.pending("wayfarer").get(0).toolName());
        assertTrue(service.complete("call_1", Map.of("approved", true)));

        join(worker);
        assertEquals(true, decision.get().get("approved"));
        assertTrue(service.pending(null).isEmpty(), "完成后应移出待办");
    }

    @Test
    @Timeout(10)
    void 上下文中写入HITL加工具调用id的等待值() {
        HitlService service = new HitlService(5);
        AgentContext context = contextOf("call_1", "shell");
        AtomicReference<Map<String, Object>> decision = new AtomicReference<>();

        Thread worker = awaitInThread(service, context, decision);
        awaitPending(service, 1);

        assertNotNull(context.get(HitlService.key("call_1")), "应写入 HITL+toolCallId 键");
        assertTrue(context.get(HitlService.key("call_1")) instanceof Mono);

        service.complete("call_1", Map.of());
        join(worker);
    }

    @Test
    @Timeout(10)
    void 多个工具调用各自独立等待与完成() {
        HitlService service = new HitlService(5);
        AgentContext first = contextOf("call_1", "shell");
        AgentContext second = contextOf("call_2", "write_file");
        AtomicReference<Map<String, Object>> firstDecision = new AtomicReference<>();
        AtomicReference<Map<String, Object>> secondDecision = new AtomicReference<>();

        Thread worker1 = awaitInThread(service, first, firstDecision);
        Thread worker2 = awaitInThread(service, second, secondDecision);
        awaitPending(service, 2);

        assertNotEquals(first.get(HitlService.key("call_1")), second.get(HitlService.key("call_2")));

        assertTrue(service.complete("call_1", Map.of("approved", true)));
        List<HitlService.Pending> left = service.pending(null);
        assertEquals(1, left.size());
        assertEquals("call_2", left.get(0).toolCallId());

        service.complete("call_2", Map.of("approved", false));
        join(worker1);
        join(worker2);

        assertEquals(true, firstDecision.get().get("approved"));
        assertEquals(false, secondDecision.get().get("approved"));
        assertTrue(service.pending(null).isEmpty());
    }

    @Test
    @Timeout(10)
    void 超时后返回null并清理待办() {
        HitlService service = new HitlService(1);
        AgentContext context = contextOf("call_1", "shell");

        assertNull(service.await(context), "超时未响应时返回 null 由工具自行判定");
        assertTrue(service.pending(null).isEmpty(), "超时后应清理待办");
    }

    @Test
    @Timeout(10)
    void 取消运行后等待立即结束() {
        // 超时设为 300 秒，验证等待是被取消回调唤醒而不是靠超时
        HitlService service = new HitlService(300);
        AgentContext context = contextOf("call_1", "shell");
        AtomicReference<Map<String, Object>> decision = new AtomicReference<>();

        Thread worker = awaitInThread(service, context, decision);
        awaitPending(service, 1);

        context.fireCancel();
        join(worker);

        assertNull(decision.get(), "取消后工具侧拿不到决定");
        assertTrue(service.pending(null).isEmpty(), "取消后应清理待办");
    }

    @Test
    void 没有等待者时写入决定返回false() {
        HitlService service = new HitlService(5);

        assertFalse(service.complete("not-exists", Map.of("approved", true)));
    }

    @Test
    void 参数非法时报错() {
        HitlService service = new HitlService(5);

        assertThrows(IllegalArgumentException.class, () -> new HitlService(0));
        assertThrows(IllegalArgumentException.class, () -> service.complete(" ", Map.of()));
        assertThrows(IllegalStateException.class, () -> service.await(new AgentContext()),
                "不在工具执行中时无法等待");
    }

    private static AgentContext contextOf(String toolCallId, String toolName) {
        AgentContext context = new AgentContext();
        context.put(AgentContext.AGENT_NAME, "wayfarer");
        context.put(AgentContext.SESSION_ID, "session-1");
        context.put(AgentContext.TOOL_CALL_ID, toolCallId);
        context.put(AgentContext.TOOL_NAME, toolName);
        return context;
    }

    private static Thread awaitInThread(HitlService service, AgentContext context,
                                        AtomicReference<Map<String, Object>> holder) {
        Thread thread = new Thread(() -> holder.set(service.await(context)));
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void awaitPending(HitlService service, int expected) {
        long deadline = System.currentTimeMillis() + 5000;
        while (service.pending(null).size() < expected && System.currentTimeMillis() < deadline) {
            sleep(10);
        }
        assertEquals(expected, service.pending(null).size());
    }

    private static void join(Thread thread) {
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
