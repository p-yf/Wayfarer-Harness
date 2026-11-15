package com.yf.wayfarer.loop.toolchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.loop.modelchain.ToolResultRepairHandler;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCallInfo;

/**
 * 工具调用调度处理器。
 *
 * @author yyf
 * @description 遍历本轮模型请求的每个工具调用，把当前调用写入上下文后调用 next，
 * 使链上后续的单次处理器逐个执行；本处理器不感知检查、审批、变更记录与执行细节
 */
public class ToolCallLoopHandler implements ChainHandler {

    /**
     * 位于单次处理器之前、持久化处理器之后。
     */
    @Override
    public int sort() {
        return 0;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        try {
            ModelResponse response = context.modelResponse();
            if (response == null) {
                return;
            }
            for (ToolCallInfo info : response.toolCalls()) {
                if (context.isCancelled()) {
                    // 已终止：不再执行剩余工具，但要补一条结果，避免助手消息缺少配对
                    ToolCallHandler.appendResult(context, info,
                            ToolResultRepairHandler.PLACEHOLDER);
                    continue;
                }
                context.put(AgentContext.CURRENT_TOOL_CALL, info);
                next.run();
            }
        } finally {
            context.remove(AgentContext.CURRENT_TOOL_CALL);
        }
    }
}
