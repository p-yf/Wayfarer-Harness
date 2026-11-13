package com.yf.wayfarer.loop;

import com.yf.wayfarer.model.ChatChunk;
import com.yf.wayfarer.toolcall.ToolCallInfo;

/**
 * agent 循环输出的消息。
 *
 * @author yyf
 * @description 三类消息：流式片段、模型请求的工具调用、工具执行结果，每条消息只携带其中一类
 */
public record AgentMsg(ChatChunk chunk, ToolCallInfo toolCallInfo, String toolResult) {

    /**
     * 模型流式输出的一个片段，最后一片携带完整响应。
     */
    public static AgentMsg chunk(ChatChunk chunk) {
        return new AgentMsg(chunk, null, null);
    }

    /**
     * 模型请求的一次工具调用。
     */
    public static AgentMsg toolCall(ToolCallInfo toolCallInfo) {
        return new AgentMsg(null, toolCallInfo, null);
    }

    /**
     * 一次工具调用的执行结果。
     */
    public static AgentMsg toolResult(ToolCallInfo toolCallInfo, String toolResult) {
        return new AgentMsg(null, toolCallInfo, toolResult);
    }
}
