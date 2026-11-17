package com.yf.wayfarer.subagent;

import com.yf.wayfarer.loop.AgentContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author yyf
 * @description 校验本轮结束会封闭并取消仍存活的子 Agent，避免它们在本轮变更记录之后继续写文件
 */
class SubAgentSupervisorTest {

    private static final String SESSION = "session-supervisor";

    @Test
    void 本轮结束取消仍存活的子任务() throws Exception {
        SubAgentManager manager = new SubAgentManager(3, 30);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        manager.submit(SESSION, "call-1", "wayfarer:subagent", (uuid, agentId) -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return SubAgentResult.completed(agentId, "完成", 1L);
        });
        // 真实子 Agent 会登记取消钩子，这里用钩子放行子任务，模拟取消后线程退出
        manager.onCancel(SESSION, release::countDown);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        AgentContext context = new AgentContext();
        context.put(AgentContext.SESSION_ID, SESSION);
        new SubAgentSupervisor(manager, 2).run(context, () -> {
        });

        assertEquals(0, release.getCount(), "本轮结束应级联取消仍存活的子任务");
        assertTrue(manager.hasCapacity(SESSION), "收尾后应释放会话级统计");
    }
}
