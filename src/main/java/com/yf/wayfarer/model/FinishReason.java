package com.yf.wayfarer.model;

/**
 * 模型结束生成的原因。
 *
 * @author yyf
 * @description 统一不同供应商的结束原因取值
 */
public enum FinishReason {
    STOP,
    LENGTH,
    TOOL_CALLS,
    CONTENT_FILTER,
    OTHER
}
