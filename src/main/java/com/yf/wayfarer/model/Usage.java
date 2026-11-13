package com.yf.wayfarer.model;

/**
 * 模型响应的 token 用量。
 *
 * @author yyf
 * @description 对应 DeepSeek usage 字段，缺失时为 null
 */
public record Usage(
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        Long promptCacheHitTokens,
        Long promptCacheMissTokens,
        Long reasoningTokens) {
}
