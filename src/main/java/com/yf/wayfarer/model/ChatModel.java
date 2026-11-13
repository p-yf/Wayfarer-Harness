package com.yf.wayfarer.model;

import reactor.core.publisher.Flux;

/**
 * 模型底层接口。取消订阅即停止底层生成，无需额外停止标志。
 *
 * @author yyf
 * @description 定义供应商无关的流式对话能力；完整响应在流正常结束的最后一片 chunk 中给出
 */
public interface ChatModel {

    Flux<ChatChunk> stream(ChatRequest request);
}
