package com.yf.wayfarer.model;

/**
 * 流式片段。
 *
 * @author yyf
 * @description 正常流式片段只带增量文本与结束原因；流正常结束后的最后一片携带完整响应
 */
public record ChatChunk(String content, FinishReason finishReason, ModelResponse response) {

    public ChatChunk {
        content = content == null ? "" : content;
    }

    public ChatChunk(String content, FinishReason finishReason) {
        this(content, finishReason, null);
    }
}
