package com.yf.wayfarer.rsi.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * L1 原子记忆条目（落盘 {@code stores/<agent>/memory/l1.jsonl}，每行一条 JSON）。
 *
 * <p>记录结构：类型为七型
 * （persona / episodic / instruction / work_fact / work_task / work_method / work_artifact），
 * update/merge 时递增 {@code version}、累积 {@code sources}，
 * {@code sceneName} 沿用提取时 LLM 给出的场景名（供 L2 场景聚合参考）。</p>
 *
 * <p>record 直接作为 Jackson 的序列化/反序列化载体：写文件时逐行
 * {@code writeValueAsString}，读文件时逐行 {@code readValue}（宽松模式，旧文件里
 * 已废弃的 signature 字段会被忽略）。</p>
 *
 * @author yyf
 * @description 一句话原子事实：类型（七型）、关键词 tags、信息量打分、
 * 版本号（update/merge 递增）、来源场景名、创建/更新时间与来源会话列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemoryItem(String id,
                         String type,
                         String content,
                         List<String> tags,
                         int score,
                         int version,
                         String sceneName,
                         String createdAt,
                         String updatedAt,
                         List<String> sources) {

    /**
     * 全字段构造（从 l1.jsonl 反序列化时使用）。
     */
    public MemoryItem {
        tags = tags == null ? List.of() : List.copyOf(tags);
        sources = sources == null ? List.of() : List.copyOf(sources);
        version = Math.max(1, version);
    }

    /**
     * 便捷构造：新建一条记忆（version=1，createdAt = updatedAt = now，单一来源会话）。
     */
    public static MemoryItem of(String id, String type, String content, List<String> tags,
                                int score, String sceneName, String now, String sessionId) {
        return new MemoryItem(id, type, content, tags, score, 1, sceneName, now, now,
                List.of(sessionId));
    }

    /**
     * 以本条为基派生更新版（update / merge 共用）：保留 id 与 createdAt，
     * version 递增，替换内容/tags/场景名，刷新 updatedAt 并追加来源。
     */
    public MemoryItem withUpdate(String newContent, List<String> newTags, String newSceneName,
                                 String now, String sessionId) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(sources);
        merged.add(sessionId);
        return new MemoryItem(id, type, newContent, newTags, score, version + 1,
                newSceneName == null ? sceneName : newSceneName, createdAt, now,
                List.copyOf(merged));
    }
}
