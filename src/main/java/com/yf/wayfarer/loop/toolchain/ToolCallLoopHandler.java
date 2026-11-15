package com.yf.wayfarer.loop.toolchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.loop.LoopChain;
import com.yf.wayfarer.loop.modelchain.ToolResultRepairHandler;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCallInfo;

/**
 * 工具调用调度处理器。
 *
 * @author yyf
 * @description 遍历本轮模型请求的每个工具调用，把当前调用写入上下文后交给单次调用链执行；
 * 单个工具的检查、审批、变更记录与执行都由该链上的处理器完成，本处理器不感知具体逻辑
 */
public class ToolCallLoopHandler implements ChainHandler {

    private final LoopChain toolChain;

    public ToolCallLoopHandler(LoopChain toolChain) {
        this.toolChain = toolChain;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        try {
            ModelResponse response = context.modelResponse();
            if (response != null) {
                for (ToolCallInfo info : response.toolCalls()) {
                    if (context.isCancelled()) {
                        // 已终止：不再执行剩余工具，但要补一条结果，避免助手消息缺少配对
                        ToolCallHandler.appendResult(context, info,
                                ToolResultRepairHandler.PLACEHOLDER);
                        continue;
                    }
                    context.put(AgentContext.CURRENT_TOOL_CALL, info);
                    toolChain.run(context);
                }
            }
        } finally {
            context.remove(AgentContext.CURRENT_TOOL_CALL);
        }
        next.run();
    }
}
