package com.yf.wayfarer.rsi;

import com.yf.wayfarer.skill.SkillStore;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 场景文件格式（{@code scenes/*.md}：META 头 + 正文）。
 *
 * <p>一个主题场景一个 Markdown 文件，META 头承载结构化元数据，正文是场景知识：</p>
 * <pre>
 * ---
 * created: 2026-01-01T00:00:00+08:00   # 场景创建时间
 * updated: 2026-01-02T00:00:00+08:00   # 最近一次 UPDATE/MERGE 时间
 * summary: 一句话摘要                    # 进索引、进画像、进 system prompt 索引
 * heat: 3                              # 被检索命中次数（热度）
 * ---
 *
 * # 场景名
 *
 * 正文……
 * </pre>
 *
 * <p>frontmatter 解析委托 {@link SkillStore#frontmatter}（SnakeYAML 标准解析），
 * 避免两套实现漂移。</p>
 *
 * @author yyf
 * @description render()：场景名 + META + 正文 → 落盘 Markdown；parse()：文件文本 →
 * 结构化 SceneFile（缺字段容忍，heat 缺省 0）
 */
public final class SceneFormat {

    private SceneFormat() {
    }

    /**
     * 渲染为落盘 Markdown。
     *
     * @param sceneName 场景名（同时作为正文一级标题）
     * @param summary   一句话摘要
     * @param created   创建时间（ISO 偏移格式）
     * @param updated   最近更新时间
     * @param heat      热度（检索命中次数）
     * @param body      场景正文（不含一级标题）
     */
    public static String render(String sceneName, String summary, String created,
                                String updated, int heat, String body) {
        return "---\n"
                + "created: " + created + "\n"
                + "updated: " + updated + "\n"
                + "summary: " + (summary == null ? "" : summary.replace('\n', ' ')) + "\n"
                + "heat: " + Math.max(0, heat) + "\n"
                + "---\n\n"
                + "# " + sceneName + "\n\n"
                + (body == null ? "" : body.strip()) + "\n";
    }

    /**
     * 解析场景文件文本（宽松：缺 META 字段给缺省值，不抛异常）。
     *
     * @param text     文件全文
     * @param fileName 文件名（场景名解析失败时的兜底，去掉 .md 后缀）
     */
    public static SceneFile parse(String text, String fileName) {
        String value = text == null ? "" : text;
        Map<String, Object> front = SkillStore.frontmatter(value);
        String body = stripFrontmatter(value);
        String name = fileName == null ? "scene" : fileName.replaceAll("(?i)\\.md$", "");
        // 正文首个非空行若是 "# 场景名" 一级标题，剥掉（场景名以标题/文件名为准）
        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("# ")) {
                name = line.substring(2).strip();
                StringBuilder rest = new StringBuilder();
                for (int j = i + 1; j < lines.length; j++) {
                    rest.append(lines[j]).append('\n');
                }
                body = rest.toString();
            }
            break;
        }
        return new SceneFile(
                name,
                String.valueOf(front.getOrDefault("summary", "")),
                String.valueOf(front.getOrDefault("created", "")),
                String.valueOf(front.getOrDefault("updated", "")),
                parseInt(front.get("heat")),
                body.strip());
    }

    /**
     * 当前时间的 ISO 偏移格式（META 时间戳统一口径）。
     */
    public static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * 去掉 frontmatter 后的正文（与 SkillStore 的 frontmatter 解析配套）。
     */
    static String stripFrontmatter(String text) {
        if (text == null || !text.startsWith("---")) {
            return text == null ? "" : text;
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return text;
        }
        int bodyStart = text.indexOf('\n', end + 1);
        return bodyStart < 0 ? "" : text.substring(bodyStart + 1);
    }

    private static int parseInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * @author yyf
     * @description 场景文件的结构化形态：场景名、摘要、创建/更新时间、热度与正文
     */
    public record SceneFile(String name, String summary, String created, String updated,
                            int heat, String body) {
    }
}
