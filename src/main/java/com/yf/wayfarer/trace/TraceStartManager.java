package com.yf.wayfarer.trace;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 轮次准入处理器。
 *
 * @author yyf
 * @description 运行级链最外层的前置逻辑：先补崩溃恢复的终态，再对会话做 trace 准入。
 * 位于所有会写会话的处理器之前，因此被拒绝的请求不会写入任何用户消息
 */
public final class TraceStartManager implements ChainHandler {

    private static final Logger log = LoggerFactory.getLogger(TraceStartManager.class);

    private final TraceStatus traceStatus;

    public TraceStartManager(TraceStatus traceStatus) {
        this.traceStatus = traceStatus;
    }

    /**
     * 最外层：先于一切会话写入执行。
     */
    @Override
    public int sort() {
        return 0;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        String sessionId = context.sessionId();
        String traceId = context.traceId();
        traceStatus.ensureRecovered(sessionId, traceId);
        if (!traceStatus.tryStart(sessionId, traceId)) {
            String running = traceStatus.runningTrace(sessionId);
            log.warn("会话已有运行中的 trace sessionId={} running={} requested={}",
                    sessionId, running, traceId);
            throw new TraceBusyException(sessionId, running);
        }
        log.info("trace 开始 sessionId={} traceId={}", sessionId, traceId);
        next.run();
    }
}
