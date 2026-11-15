package com.yf.wayfarer.change;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.loop.toolchain.ToolCallHandler;
import com.yf.wayfarer.toolcall.ToolCallInfo;

/**
 * 单次工具调用的变更记录处理器。
 *
 * @author yyf
 * @description 环绕单次工具调用：执行前取工作区脏文件快照，执行后与快照做差集并提交；
 * 被拦截或审批拒绝的调用不会进入本处理器，因此不会产生变更记录
 */
public final class ChangeRecordHandler implements ChainHandler {

    private final ChangeRecorder changeRecorder;

    public ChangeRecordHandler(ChangeRecorder changeRecorder) {
        this.changeRecorder = changeRecorder;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        ToolCallInfo info = context.currentToolCall();
        ChangeRecorder.Snapshot snapshot = changeRecorder.before(context, info);
        next.run();
        changeRecorder.after(context, snapshot,
                context.get(ToolCallHandler.RESULT_CONTENT, String.class));
    }
}
