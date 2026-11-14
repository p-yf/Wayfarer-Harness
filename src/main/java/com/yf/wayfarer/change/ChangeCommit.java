package com.yf.wayfarer.change;

import java.time.Instant;

/**
 * 影子仓库里的一条变更提交。
 *
 * @author yyf
 * @description 除提交 id 与父提交 id 外，其余字段来自提交说明里的 Wayfarer-* trailer，
 * 用于按会话、轮次、工具调用检索
 */
public record ChangeCommit(String commitId, String parentId, String sessionId, String traceId,
                           String tool, String toolCallId, String summary, Instant createdAt) {
}
