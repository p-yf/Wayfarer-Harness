package com.yf.wayfarer.subagent;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.trace.TraceStopManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 子 Agent 收尾处理器。
 *
 * @author yyf
 * @description 运行级链最内层：主循环结束后等待本轮子 Agent 收敛（有超时），
 * 取消时级联终止；随后把子任务终态写入上下文供 TRACE_END 汇总，并释放会话级统计与未读结果
 */
public final class SubAgentSupervisor implements ChainHandler {

    private static final Logger log = LoggerFactory.getLogger(SubAgentSupervisor.class);

    private final SubAgentManager manager;
    private final long finishWaitSeconds;

    public SubAgentSupervisor(SubAgentManager manager, long finishWaitSeconds) {
        this.manager = manager;
        this.finishWaitSeconds = finishWaitSeconds;
    }

    /**
     * 最内层：后置逻辑先于变更汇总、状态落盘与 TRACE_END 执行。
     */
    @Override
    public int sort() {
        return 40;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        try {
            next.run();
        } finally {
            finish(context);
        }
    }

    private void finish(AgentContext context) {
        // 子 Agent 自己不承担收尾职责，避免嵌套等待
        if (context.origin() != null) {
            return;
        }
        String sessionId = context.sessionId();
        if (sessionId == null) {
            return;
        }
        if (context.isCancelled()) {
            manager.cancelSession(sessionId);
            // 取消会立刻结束结果 future，但子任务线程可能还在写终态消息，等它安静后再继续收尾
            manager.awaitQuiet(sessionId, finishWaitSeconds);
        } else {
            manager.awaitSession(sessionId, finishWaitSeconds);
        }
        List<Map<String, Object>> children = manager.children(sessionId);
        if (!children.isEmpty()) {
            context.put(TraceStopManager.CHILDREN, children);
            log.info("本轮子Agent终态 sessionId={} 数量={}", sessionId, children.size());
        }
        manager.closeSession(sessionId);
    }
}
