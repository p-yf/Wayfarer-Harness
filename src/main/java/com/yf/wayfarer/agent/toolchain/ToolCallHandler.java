package com.yf.wayfarer.agent.toolchain;

import com.yf.wayfarer.agent.modelchain.ToolResultRepairHandler;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCall;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 工具调用处理器。
 *
 * @author yyf
 * @description 按顺序执行模型请求的每个工具调用，把工具 id 与名称写入上下文供工具自行鉴权，
 * 执行结果作为 tool 消息推送并按轮次拼接到消息历史
 */
public class ToolCallHandler implements ChainHandler {

    private static final Logger log = LoggerFactory.getLogger(ToolCallHandler.class);

    private final ToolCall toolCall;

    public ToolCallHandler(ToolCall toolCall) {
        this.toolCall = toolCall;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        ModelResponse response = context.modelResponse();
        if (response != null) {
            for (ToolCallInfo info : response.toolCalls()) {
                execute(context, info);
            }
        }
        next.run();
    }

    private void execute(AgentContext context, ToolCallInfo info) {
        if (context.isCancelled()) {
            // 已终止：不再执行剩余工具，但要补一条结果，避免助手消息缺少配对
            appendResult(context, info, ToolResultRepairHandler.PLACEHOLDER);
            return;
        }
        // 是否放行由重复调用检查处理器提前判定，这里只按结论执行
        ToolCircleCheckHandler.Level level = ToolCircleCheckHandler.levelOf(context, info.id());
        if (level == ToolCircleCheckHandler.Level.BLOCK) {
            appendResult(context, info, ToolCircleCheckHandler.BLOCK);
            return;
        }
        // 人工审批结论由审批处理器提前写入，被拒绝的调用不再执行
        String denied = CodingToolApprovalHandler.deniedResultOf(context, info.id());
        if (denied != null) {
            appendResult(context, info, denied);
            return;
        }
        // 工具在执行过程中可据此定位自己的 HITL 等待项
        context.put(AgentContext.TOOL_CALL_ID, info.id());
        context.put(AgentContext.TOOL_NAME, info.name());
        log.info("执行工具 traceId={} 工具={} 参数={}", context.traceId(),
                info.name(), abbreviate(info.arguments()));
        Object result = toolCall.call(info, context);
        String content = result == null ? "" : String.valueOf(result);
        if (level == ToolCircleCheckHandler.Level.WARN) {
            content = content + "\n" + ToolCircleCheckHandler.WARN;
        }
        log.info("工具完成 traceId={} 工具={} 结果长度={}", context.traceId(), info.name(), content.length());
        appendResult(context, info, content);
    }

    /**
     * 把工具结果推给下游并写入消息历史。
     */
    private static void appendResult(AgentContext context, ToolCallInfo info, String content) {
        Consumer<AgentMessage> sink = context.agentMsgSink();
        if (sink != null) {
            sink.accept(new AgentMessage.ToolResult(context.traceId(), info.id(), content));
        }
        context.messages().add(new ChatMessage(ChatMessage.Role.TOOL, content, info.id()));
    }

    /**
     * 日志中的参数只保留前 200 个字符，避免大段文件内容刷屏。
     */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
