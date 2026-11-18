package com.yf.wayfarer.rsi.tool;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.rsi.MemorySearcher;
import com.yf.wayfarer.rsi.RsiProperties;
import com.yf.wayfarer.toolcall.Tool;
import com.yf.wayfarer.toolcall.ToolParam;

import java.util.List;

/**
 * memory_search 工具（规格 8.3）：agent 主动检索长期记忆。
 *
 * <p>被动召回（MemoryRecallHandler）是 run 级一次的兜底；此工具让 agent 在执行中
 * 能按需深挖——"我好像记过这个？查一下"。只装配在 wayfarer-rsi 的 toolset。</p>
 *
 * <p>结果作为普通 tool 消息进入对话（当轮对话内容，允许持久化），与被动召回的
 * 「不持久化」约束不冲突。只读 store，检索失败降级为错误消息，不影响主循环。</p>
 *
 * @author yyf
 * @description 按 query 词面检索 l1/l2/l3 记忆条目，返回 layer/content/tags/updatedAt 列表；
 * 空结果返回明确提示而非报错
 */
public class MemorySearchTool {

    private final MemorySearcher searcher;
    private final RsiProperties properties;

    /** 检索目标 agent 固定为 wayfarer-rsi 自己（"自己只沉淀自己的"同样适用于检索）。 */
    private final String agent;

    public MemorySearchTool(MemorySearcher searcher, RsiProperties properties, String agent) {
        this.searcher = searcher;
        this.properties = properties;
        this.agent = agent;
    }

    @Tool(name = "memory_search", description =
            "检索长期记忆库（沉淀的原子事实/偏好、场景摘要、用户画像）。"
                    + "当怀疑之前记录过与当前任务相关的信息时使用，例如用户偏好、项目约定、过往结论。")
    public String search(AgentContext context,
                         @ToolParam(description = "检索关键词，可用空格分隔多个词") String query,
                         @ToolParam(required = false, description =
                                 "限定检索层：l1=原子记忆，l2=场景摘要，l3=用户画像，all=全部（默认）")
                         String layer,
                         @ToolParam(required = false, description = "返回条数上限，默认 10") Integer limit) {
        try {
            List<MemorySearcher.Hit> hits = searcher.search(agent, query, layer,
                    limit == null || limit <= 0 ? 10 : Math.min(limit, 50));
            if (hits.isEmpty()) {
                return "无匹配记忆。";
            }
            StringBuilder text = new StringBuilder();
            for (MemorySearcher.Hit hit : hits) {
                text.append('[').append(hit.layer().toUpperCase()).append("] ");
                if (hit.id() != null) {
                    text.append(hit.id()).append(' ');
                }
                if (hit.sessionId() != null) {
                    text.append("(session ").append(hit.sessionId()).append(") ");
                }
                text.append(hit.content()).append('\n');
                String tags = hit.tagsText();
                if (!tags.isEmpty()) {
                    text.append("    ").append(tags).append('\n');
                }
            }
            return text.toString().strip();
        } catch (RuntimeException e) {
            // 检索是纯增益能力：失败降级为错误消息，绝不打断主循环
            return "记忆检索失败：" + e.getMessage();
        }
    }
}
