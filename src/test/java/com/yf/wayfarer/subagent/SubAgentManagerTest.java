package com.yf.wayfarer.subagent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子 Agent 管理器测试，使用真实线程池，不使用模拟对象。
 *
 * @author yyf
 * @description 覆盖会话级并发上限、结果读取后删除、运行中查询保留、级联取消与终态汇总
 */
class SubAgentManagerTest {

    private static final String SESSION = "session-1";
    private static final String AGENT_NAME = "wayfarer:subagent";

    @Test
    void 同一会话最多同时运行三个子任务() throws Exception {
        SubAgentManager manager = new SubAgentManager(3, 30);
        CountDownLatch started = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        List<SubAgentManager.Submission> submissions = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                String toolCallId = "call-" + i;
                SubAgentManager.Submission submission = manager.submit(SESSION, toolCallId, AGENT_NAME,
                        (uuid, agentId) -> {
                            started.countDown();
                            awaitQuietly(release);
                            return SubAgentResult.completed(agentId, "结果-" + agentId, 1L);
                        });
                assertNotNull(submission);
                submissions.add(submission);
            }

            assertTrue(started.await(5, TimeUnit.SECONDS), "三个子任务应全部开始运行");
            assertFalse(manager.hasCapacity(SESSION));
            assertNull(manager.submit(SESSION, "call-overflow", AGENT_NAME,
                    (uuid, agentId) -> SubAgentResult.completed(agentId, "不应执行", 0L)),
                    "超过上限应直接拒绝派发");

            release.countDown();
            for (SubAgentManager.Submission submission : submissions) {
                SubAgentResult result = manager.await(submission.uuid());
                assertNotNull(result);
                assertEquals("completed", result.status());
            }
            assertTrue(manager.hasCapacity(SESSION), "子任务结束后应释放配额");
        } finally {
            release.countDown();
            manager.closeSession(SESSION);
        }
    }

    @Test
    void 读取结果后删除对应键() {
        SubAgentManager manager = new SubAgentManager(3, 30);
        try {
            SubAgentManager.Submission submission = manager.submit(SESSION, "call-1", AGENT_NAME,
                    (uuid, agentId) -> SubAgentResult.completed(agentId, "最终答案", 1L));

            SubAgentManager.Query first = manager.read(submission.uuid(), true);
            assertEquals("completed", first.status());
            assertEquals("最终答案", first.result());
            assertTrue(first.removed());

            SubAgentManager.Query second = manager.read(submission.uuid(), false);
            assertTrue(second.missing(), "结果读取后应被删除，不能重复读取");
        } finally {
            manager.closeSession(SESSION);
        }
    }

    @Test
    void 非阻塞查询运行中保留结果供后续读取() throws Exception {
        SubAgentManager manager = new SubAgentManager(3, 30);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            SubAgentManager.Submission submission = manager.submit(SESSION, "call-1", AGENT_NAME,
                    (uuid, agentId) -> {
                        started.countDown();
                        awaitQuietly(release);
                        return SubAgentResult.completed(agentId, "稍后返回", 1L);
                    });
            assertTrue(started.await(5, TimeUnit.SECONDS));

            SubAgentManager.Query running = manager.read(submission.uuid(), false);
            assertEquals("running", running.status());
            assertFalse(running.removed());

            release.countDown();
            SubAgentManager.Query done = poll(manager, submission.uuid());
            assertEquals("completed", done.status());
            assertEquals("稍后返回", done.result());
            assertTrue(done.removed());
        } finally {
            release.countDown();
            manager.closeSession(SESSION);
        }
    }

    @Test
    void 取消会话触发钩子并把子任务标记为取消() throws Exception {
        SubAgentManager manager = new SubAgentManager(3, 30);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        try {
            SubAgentManager.Submission submission = manager.submit(SESSION, "call-1", AGENT_NAME,
                    (uuid, agentId) -> {
                        started.countDown();
                        awaitQuietly(release);
                        return SubAgentResult.completed(agentId, "正常结束", 1L);
                    });
            manager.onCancel(SESSION, () -> cancelled.set(true));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            manager.cancelSession(SESSION);
            assertTrue(cancelled.get(), "取消会话应触发已登记的取消钩子");
            // 已取消的子任务不应阻塞收尾
            manager.awaitSession(SESSION, 1);

            SubAgentManager.Query query = manager.read(submission.uuid(), false);
            assertEquals("cancelled", query.status());
            assertTrue(query.removed());

            List<Map<String, Object>> children = manager.children(SESSION);
            assertEquals(1, children.size());
            assertEquals("cancelled", children.get(0).get("status"));
        } finally {
            release.countDown();
            manager.closeSession(SESSION);
        }
    }

    /**
     * 轮询到结果被消费为止，避免依赖固定睡眠时间。
     */
    private static SubAgentManager.Query poll(SubAgentManager manager, String uuid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        SubAgentManager.Query query = manager.read(uuid, false);
        while (query.running() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            query = manager.read(uuid, false);
        }
        return query;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
