package com.yf.wayfarer.subagent;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.loop.toolchain.ToolCallHandler;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 子 Agent 配额拦截处理器。
 *
 * @author yyf
 * @description 工具链上快速判定派发调用：同一会话同时运行的子 Agent 已达上限时不再进入执行阶段，
 * 直接把固定提示作为工具结果写回。真正的占位由 {@link SubAgentManager} 原子完成，避免并发竞态
 */
public final class SubAgentQuotaHandler implements ChainHandler {

    private static final Logger log = LoggerFactory.getLogger(SubAgentQuotaHandler.class);

    /**
     * 派发工具名，与 {@link SubAgentTools#dispatch} 保持一致。
     */
    public static final String DISPATCH_TOOL = "subagent";

    private final SubAgentManager manager;

    public SubAgentQuotaHandler(SubAgentManager manager) {
        this.manager = manager;
    }

    /**
     * 位于重复检查之后、审批之前：先拦截明显的重复调用，再做配额快速失败。
     */
    @Override
    public int sort() {
        return 15;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        ToolCallInfo info = context.currentToolCall();
        if (info == null || !DISPATCH_TOOL.equals(info.name())) {
            next.run();
            return;
        }
        if (!manager.hasCapacity(context.sessionId())) {
            log.warn("子Agent配额已满，拦截派发 sessionId={} traceId={}",
                    context.sessionId(), context.traceId());
            ToolCallHandler.appendResult(context, info, SubAgentTools.QUOTA_FULL);
            return;
        }
        next.run();
    }
}
