package com.yf.wayfarer.session;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息归属。
 *
 * @author yyf
 * @description 标记一条会话消息由哪个 Agent 产生：null 表示 root Agent；
 * 否则记录子 Agent 实例 id、启动它的那次工具调用 id 与派生深度，用于回放时还原树形结构
 */
public record MessageOrigin(String agentId, String viaToolCallId, Integer depth) {

    public MessageOrigin {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
    }

    /**
     * 转成写入会话文件的键值对。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("agentId", agentId);
        if (viaToolCallId != null) {
            map.put("viaToolCallId", viaToolCallId);
        }
        if (depth != null) {
            map.put("depth", depth);
        }
        return map;
    }

    /**
     * 从会话文件内容还原，缺少 agentId 时返回 null。
     */
    public static MessageOrigin fromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        Object agentId = map.get("agentId");
        if (agentId == null || String.valueOf(agentId).isBlank()) {
            return null;
        }
        Object via = map.get("viaToolCallId");
        Object depth = map.get("depth");
        return new MessageOrigin(String.valueOf(agentId),
                via == null ? null : String.valueOf(via),
                depth instanceof Number number ? number.intValue() : null);
    }
}
