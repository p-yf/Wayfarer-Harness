package com.yf.wayfarer.loop.toolchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.toolcall.ToolCall;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * 单次工具调用处理器。
 *
 * @author yyf
 * @description 执行上下文中的当前工具调用，把工具 id 与名称写入上下文供工具自行鉴权，
 * 结果作为 tool 消息推送并按轮次拼接到消息历史；固定位于单次调用链最内层
 */
public class ToolCallHandler implements ChainHandler {

    private static final Logger log = LoggerFactory.getLogger(ToolCallHandler.class);

    /**
     * 本次工具返回给模型的最终文本，供外层环绕处理器读取。
     */
    public static final String RESULT_CONTENT = "toolResultContent";

    private final ToolCall toolCall;

    public ToolCallHandler(ToolCall toolCall) {
        this.toolCall = toolCall;
    }

    @Override
    public int sort() {
        // 最内层：所有单次调用链上的处理器都包裹在它外层
        return Integer.MAX_VALUE;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        ToolCallInfo info = context.currentToolCall();
        if (info != null) {
            execute(context, info);
        }
        next.run();
    }

    private void execute(AgentContext context, ToolCallInfo info) {
        // 工具在执行过程中可据此定位自己的 HITL 等待项
        context.put(AgentContext.TOOL_CALL_ID, info.id());
        context.put(AgentContext.TOOL_NAME, info.name());
        log.info("执行工具 traceId={} 工具={} 参数={}", context.traceId(),
                info.name(), abbreviate(info.arguments()));
        Object result = toolCall.call(info, context);
        String content = result == null ? "" : String.valueOf(result);
        if (ToolCircleCheckHandler.levelOf(context) == ToolCircleCheckHandler.Level.WARN) {
            content = content + "\n" + ToolCircleCheckHandler.WARN;
        }
        log.info("工具完成 traceId={} 工具={} 结果长度={}", context.traceId(), info.name(), content.length());
        context.put(RESULT_CONTENT, content);
        appendResult(context, info, content);
    }

    /**
     * 把单个工具的结果推给下游并写入消息历史，一次调用推一条。
     */
    public static void appendResult(AgentContext context, ToolCallInfo info, String content) {
        Consumer<AgentMessage> sink = context.agentMsgSink();
        if (sink != null) {
            sink.accept(new AgentMessage.ToolResult(context.traceId(), info.id(), content));
        }
        List<ChatMessage> messages = context.messages();
        if (messages != null) {
            messages.add(new ChatMessage(ChatMessage.Role.TOOL, content, info.id()));
        }
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
