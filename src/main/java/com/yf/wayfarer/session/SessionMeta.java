package com.yf.wayfarer.session;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话元数据。
 *
 * @author yyf
 * @description 会话的 id、名称、创建时间，以及会话文件首行 METADATA 中保存的任意键值对
 */
public record SessionMeta(String id, String name, Instant createdAt, Map<String, Object> metadata) {

    public SessionMeta {
        // 元数据是任意键值对，值可能为 null，不能用 Map.copyOf
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * 兼容只关心 id、名称与创建时间的调用方。
     */
    public SessionMeta(String id, String name, Instant createdAt) {
        this(id, name, createdAt, Map.of());
    }
}
