package com.yf.wayfarer.session;

import java.time.Instant;

/**
 * 会话元数据。
 *
 * @author yyf
 * @description 会话的 id、名称与创建时间
 */
public record SessionMeta(String id, String name, Instant createdAt) {
}
