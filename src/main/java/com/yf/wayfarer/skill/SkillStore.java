package com.yf.wayfarer.skill;

import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能清单。
 *
 * @author yyf
 * @description 扫描 skills 根目录下包含 SKILL.md 的技能文件夹，解析 frontmatter 中的 description，
 * 生成注入系统提示词的技能清单；文件系统操作由 {@link SkillFileSystem} 负责
 */
public final class SkillStore {

    private static final String SKILL_FILE = "SKILL.md";
    private static final String FRONTMATTER = "---";
    private static final String DESCRIPTION = "description";
    private static final Yaml YAML = new Yaml();

    private final SkillFileSystem files;

    public SkillStore(SkillFileSystem files) {
        this.files = files;
    }

    /**
     * 生成注入系统提示词的技能清单；没有技能时返回空串。
     */
    public String prompt() {
        List<Skill> skills = skills();
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("以下是可用的 skills。它们是按需读取的技能说明文件：");
        prompt.append("先根据 description 判断是否相关，需要时用 read 工具读取 position 指向的 SKILL.md，再按文件里的步骤执行。\n");
        for (Skill skill : skills) {
            prompt.append("\n<skillname>\n")
                    .append("<name>").append(skill.name()).append("</name>\n")
                    .append("<description>").append(skill.description()).append("</description>\n")
                    .append("<position>").append(skill.position()).append("</position>\n")
                    .append("</skillname>\n");
        }
        return prompt.toString();
    }

    /**
     * 列出全部技能：根目录下包含 SKILL.md 的文件夹，按名称排序。
     */
    public List<Skill> skills() {
        List<Skill> skills = new ArrayList<>();
        for (SkillFileSystem.Entry entry : files.list("").entries()) {
            if (!entry.directory()) {
                continue;
            }
            String skillFile = entry.path() + "/" + SKILL_FILE;
            if (!files.exists(skillFile)) {
                continue;
            }
            skills.add(new Skill(entry.name(), description(files.read(skillFile)),
                    files.absolute(skillFile)));
        }
        skills.sort(Comparator.comparing(Skill::name, String.CASE_INSENSITIVE_ORDER));
        return skills;
    }

    /**
     * 取 SKILL.md frontmatter 中的 description，缺失或解析失败时返回空串。
     * frontmatter 是标准 YAML，支持块状描述（>、|-）与多行列表。
     */
    private static String description(String text) {
        Object value = frontmatter(text).get(DESCRIPTION);
        return value == null ? "" : String.valueOf(value).strip();
    }

    /**
     * 用 YAML 解析首尾 --- 之间的 frontmatter，失败时按没有 frontmatter 处理。
     */
    private static Map<String, Object> frontmatter(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !FRONTMATTER.equals(lines[0].strip())) {
            return Map.of();
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if (FRONTMATTER.equals(lines[i].strip())) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            return Map.of();
        }
        String block = String.join("\n", Arrays.copyOfRange(lines, 1, end));
        try {
            Object loaded = YAML.load(block);
            if (loaded instanceof Map<?, ?> map) {
                Map<String, Object> frontmatter = new LinkedHashMap<>();
                map.forEach((key, value) -> frontmatter.put(String.valueOf(key), value));
                return frontmatter;
            }
        } catch (RuntimeException ignored) {
            // frontmatter 不合法时按无描述处理，不影响技能被收录
        }
        return Map.of();
    }


    /**
     * @author yyf
     * @description 技能摘要：名称（文件夹名）、描述与 SKILL.md 绝对路径
     */
    public record Skill(String name, String description, String position) {
    }
}
