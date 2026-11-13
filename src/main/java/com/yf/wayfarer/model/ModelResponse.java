package com.yf.wayfarer.model;

import com.yf.wayfarer.toolcall.ToolCallInfo;

import java.util.List;

/**
 * 模型流式输出的完整响应（对应 DeepSeek 对话补全的结构）。
 *
 * @author yyf
 * @description 汇总完整文本、推理内容、结束原因、工具调用与 token 用量，作为最后一片 chunk 的 response
 */
public record ModelResponse(
        String id,
        String model,
        long created,
        String content,
        String reasoningContent,
        FinishReason finishReason,
        List<ToolCallInfo> toolCalls,
        Usage usage) {

    public ModelResponse {
        content = content == null ? "" : content;
        reasoningContent = reasoningContent == null ? "" : reasoningContent;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
