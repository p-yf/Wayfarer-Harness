package com.yf.wayfarer.rsi.tool;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.skill.SkillStore;
import com.yf.wayfarer.toolcall.Tool;
import com.yf.wayfarer.toolcall.ToolParam;

import java.util.List;

/**
 * skill_search 工具（规格 8.3）：agent 主动检索<b>沉淀技能库</b>（仅 RSI store 根）。
 *
 * <p>只检索 {@code stores/<agent>/skills/}——普通 skills 根目录的技能已由 SkillsHandler
 * 全量注入 system prompt 技能清单，无需也不应再检索；此工具只负责沉淀技能的按需发现
 * （沉淀数量增长后清单可能被截断或淹没）。返回 name/description/position
 * （SKILL.md 绝对路径），全文由 agent 用现有 read 工具按需读取。</p>
 *
 * @author yyf
 * @description 在单根（沉淀 store）技能清单上匹配 name + description 的词面命中，返回摘要列表；
 * 只读操作，空结果返回明确提示
 */
public class SkillSearchTool {

    private final SkillStore store;

    public SkillSearchTool(SkillStore store) {
        this.store = store;
    }

    @Tool(name = "skill_search", description =
            "按关键词检索沉淀技能库（仅 RSI 自动沉淀的技能；常规技能已全量列在技能清单中，"
                    + "无需检索）。返回技能名、类型、描述与 SKILL.md 路径，需要正文时再用 read 工具读取该路径。")
    public String search(AgentContext context,
                         @ToolParam(description = "检索关键词，可用空格分隔多个词") String query,
                         @ToolParam(required = false, description = "返回条数上限，默认 5") Integer limit) {
        try {
            int capped = limit == null || limit <= 0 ? 5 : Math.min(limit, 20);
            List<String> tokens = tokenize(query);
            List<SkillStore.Skill> skills = store.skills();
            List<SkillStore.Skill> hits = new java.util.ArrayList<>();
            for (SkillStore.Skill skill : skills) {
                if (tokens.isEmpty() || matches(tokens, skill)) {
                    hits.add(skill);
                }
                if (hits.size() >= capped) {
                    break;
                }
            }
            if (hits.isEmpty()) {
                return "无匹配技能。";
            }
            StringBuilder text = new StringBuilder();
            for (SkillStore.Skill skill : hits) {
                text.append("name=").append(skill.name())
                        .append(" type=").append(skill.type())
                        .append("\n  description=").append(skill.description())
                        .append("\n  position=").append(skill.position()).append('\n');
            }
            return text.toString().strip();
        } catch (RuntimeException e) {
            return "技能检索失败：" + e.getMessage();
        }
    }

    /**
     * 简易分词：空格/标点切分 + 小写化（技能名与描述以词组为主，无需中文二元组）。
     */
    private static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(query.toLowerCase().split("[\\s\\p{Punct}，。、；：]+"))
                .filter(token -> !token.isBlank())
                .toList();
    }

    /**
     * 匹配：任一 token 出现在 name 或 description 中即命中（不区分大小写）。
     */
    private static boolean matches(List<String> tokens, SkillStore.Skill skill) {
        String haystack = (skill.name() + "\n" + skill.description()).toLowerCase();
        return tokens.stream().anyMatch(haystack::contains);
    }
}
