package com.yf.wayfarer.message;

import com.yf.wayfarer.model.FinishReason;
import com.yf.wayfarer.model.Usage;
import com.yf.wayfarer.toolcall.ToolCallInfo;

/**
 * agent 运行时产生的消息。
 *
 * @author yyf
 * @description 以密封接口表达五类消息：文本增量、思考增量、工具调用、工具结果与模型回复收尾，
 * 每条消息都携带本次运行的 trace id，模型链、工具链、会话持久化与 sse 推送共用同一套类型
 */
public sealed interface AgentMessage {

    /**
     * 本次运行对应的 trace id。
     */
    String traceId();

    /**
     * 文本增量。
     *
     * @author yyf
     * @description 模型本次输出新增的一段回复文本
     */
    record Text(String traceId, String content) implements AgentMessage {
    }

    /**
     * 思考增量。
     *
     * @author yyf
     * @description 模型本次输出新增的一段思考过程
     */
    record Reasoning(String traceId, String content) implements AgentMessage {
    }

    /**
     * 工具调用请求。
     *
     * @author yyf
     * @description 模型要求执行的一次工具调用
     */
    record ToolCall(String traceId, ToolCallInfo call) implements AgentMessage {
    }

    /**
     * 工具执行结果。
     *
     * @author yyf
     * @description 按工具调用 id 关联的执行结果
     */
    record ToolResult(String traceId, String toolCallId, String content) implements AgentMessage {
    }

    /**
     * 一条模型回复的收尾信息。
     *
     * @author yyf
     * @description 结束原因与本次用量，回复正文与思考已通过增量消息送出
     */
    record End(String traceId, FinishReason finishReason, Usage usage) implements AgentMessage {
    }

    /**
     * 一轮模型调用的附加数据。
     *
     * @author yyf
     * @description 用时、结束原因与用量，同时写入会话的 EXTRADATA 消息，刷新页面后仍可展示
     */
    record TraceData(String traceId, FinishReason finishReason, Usage usage, Long durationMs)
            implements AgentMessage {
    }

    /**
     * 其他消息。
     *
     * @author yyf
     * @description 无法归入上述类型的自定义消息，内容原样携带
     */
    record Other(String traceId, Object message) implements AgentMessage {
    }
}
