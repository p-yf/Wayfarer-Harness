package com.yf.wayfarer.rsi.model;

import java.time.Instant;

/**
 * RSI 沉淀仓库的一条提交记录。
 *
 * @author yyf
 * @description 供变更历史列表使用：提交 id、父提交、时间、首行摘要，
 * 以及从 commit message trailer 解析出来的来源信息（会话 / trace / 层）
 */
public record RsiCommit(String hash,
                        String parentHash,
                        Instant time,
                        String summary,
                        String sourceSession,
                        String traceId,
                        String layer) {
}
