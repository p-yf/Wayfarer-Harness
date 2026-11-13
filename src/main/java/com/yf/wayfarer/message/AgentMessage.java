package com.yf.wayfarer.message;

import com.yf.wayfarer.model.FinishReason;
import com.yf.wayfarer.model.Usage;
import com.yf.wayfarer.toolcall.ToolCallInfo;

/**
 * agent 运行时产生的消息。
 *
 * @author yyf
 * @description 以密封接口表达五类消息：文本增量、思考增量、工具调用、工具结果与模型回复收尾，
 * 模型链、工具链、会话持久化与 sse 推送共用同一套类型
 */
public sealed interface AgentMessage {

    /**
     * 文本增量。
     *
     * @author yyf
     * @description 模型本次输出新增的一段回复文本
     */
    record Text(String content) implements AgentMessage {
    }

    /**
     * 思考增量。
     *
     * @author yyf
     * @description 模型本次输出新增的一段思考过程
     */
    record Reasoning(String content) implements AgentMessage {
    }

    /**
     * 工具调用请求。
     *
     * @author yyf
     * @description 模型要求执行的一次工具调用
     */
    record ToolCall(ToolCallInfo call) implements AgentMessage {
    }

    /**
     * 工具执行结果。
     *
     * @author yyf
     * @description 按工具调用 id 关联的执行结果
     */
    record ToolResult(String toolCallId, String content) implements AgentMessage {
    }

    /**
     * 一条模型回复的收尾信息。
     *
     * @author yyf
     * @description 结束原因与本次用量，回复正文与思考已通过增量消息送出
     */
    record End(FinishReason finishReason, Usage usage) implements AgentMessage {
    }

    /**
     * 别的消息
     *
     * @author yyf
     * @description 用于匹配未定义的消息类型
     */
    record Other(String message) implements AgentMessage {
    }
}
