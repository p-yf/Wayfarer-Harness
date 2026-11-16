package com.yf.wayfarer.trace;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轮次收尾处理器。
 *
 * @author yyf
 * @description 运行级链的内层前置、外层后置：主循环结束后封存 trace 并追加 TRACE_END。
 * 写在 finally 中，正常结束、异常、取消都会执行；TRACE_END 是该 trace 的最后一次写入
 */
public final class TraceStopManager implements ChainHandler {

    private static final Logger log = LoggerFactory.getLogger(TraceStopManager.class);

    /**
     * 本轮子 Agent 终态列表，由子 Agent 收尾处理器写入，供 TRACE_END 汇总。
     */
    public static final String CHILDREN = "traceChildren";

    private final TraceStatus traceStatus;

    public TraceStopManager(TraceStatus traceStatus) {
        this.traceStatus = traceStatus;
    }

    /**
     * 位于准入之后、所有会写会话的处理器之外。
     */
    @Override
    public int sort() {
        return 10;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        Throwable failure = null;
        try {
            next.run();
        } catch (Throwable e) {
            failure = e;
            throw e;
        } finally {
            finish(context, failure);
        }
    }

    private void finish(AgentContext context, Throwable failure) {
        String sessionId = context.sessionId();
        String traceId = context.traceId();
        Session session = context.session();
        if (sessionId == null || traceId == null || session == null) {
            return;
        }
        String status;
        String reason = null;
        if (context.isCancelled()) {
            status = "cancelled";
        } else if (failure != null) {
            status = "failed";
            reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        } else {
            status = "completed";
        }

        long endedAt = System.currentTimeMillis();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("status", status);
        if (reason != null) {
            content.put("reason", reason);
        }
        Long startedAt = traceStatus.startedAt(traceId);
        if (startedAt != null) {
            content.put("startedAt", startedAt);
        }
        content.put("endedAt", endedAt);
        Object children = context.get(CHILDREN);
        if (children != null) {
            content.put("children", children);
        }

        try {
            // 先写 END 再封存：END 自身不受 fence 影响，之后的迟到写入被丢弃
            session.append(sessionId, JSONMsgType.TRACE_END, traceId, content);
            log.info("trace 结束 sessionId={} traceId={} status={} 耗时={}ms",
                    sessionId, traceId, status,
                    startedAt == null ? 0 : endedAt - startedAt);
        } catch (RuntimeException e) {
            log.warn("写入 TRACE_END 失败 traceId={}", traceId, e);
        } finally {
            traceStatus.seal(traceId);
            traceStatus.finish(sessionId, traceId);
        }
    }
}
