package com.yf.wayfarer.model;

/**
 * 流式片段。
 *
 * @author yyf
 * @description 普通片段只带文本与思考的增量；流正常结束后的最后一片携带完整响应
 */
public record ChatChunk(String content, String reasoningContent, FinishReason finishReason,
                        ModelResponse response) {

    public ChatChunk {
        content = content == null ? "" : content;
        reasoningContent = reasoningContent == null ? "" : reasoningContent;
    }

    /**
     * 不带思考增量的文本片段。
     */
    public ChatChunk(String content, FinishReason finishReason) {
        this(content, "", finishReason, null);
    }

    /**
     * 携带完整响应的收尾片段。
     */
    public ChatChunk(String content, FinishReason finishReason, ModelResponse response) {
        this(content, "", finishReason, response);
    }
}
