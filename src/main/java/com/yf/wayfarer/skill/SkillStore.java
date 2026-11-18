package com.yf.wayfarer.skill;

import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能清单。
 *
 * <p>支持多根扫描（RSI 沉淀技能接入点，规格 8.2）：主根为工作区 {@code skills/} 目录
 * （人类可编辑），附加根为沉淀 store（如 {@code stores/wayfarer-rsi/skills/}，仅 Agent 写入）。
 * 只有 wayfarer-rsi 蓝图使用多根构造，wayfarer / wayfarerpro 继续单根、行为不变。</p>
 *
 * <ul>
 *   <li>合并规则：主根优先；附加根与主根重名的技能加 {@code (rsi)} 后缀标注，position 不变；</li>
 *   <li>type 标注：frontmatter 的 {@code type}（sop / background，缺失按 sop）注入清单，
 *       让 agent 区分「可执行步骤」与「反复适用的背景」。</li>
 * </ul>
 *
 * @author yyf
 * @description 扫描各根目录下包含 SKILL.md 的技能文件夹，解析 frontmatter 中的 description 与 type，
 * 生成注入系统提示词的技能清单；文件系统操作由 {@link SkillFileSystem} 负责
 */
public final class SkillStore {

    private static final String SKILL_FILE = "SKILL.md";
    private static final String FRONTMATTER = "---";
    private static final String DESCRIPTION = "description";
    private static final String TYPE = "type";
    private static final Yaml YAML = new Yaml();

    private final SkillFileSystem files;

    /** 附加扫描根（沉淀 store），可为空列表。 */
    private final List<SkillFileSystem> extraRoots;

    /**
     * 单根构造（现有 agent 使用，行为不变）。
     */
    public SkillStore(SkillFileSystem files) {
        this(files, List.of());
    }

    /**
     * 多根构造（wayfarer-rsi 使用）：主根 + 沉淀 store 附加根。
     */
    public SkillStore(SkillFileSystem files, List<SkillFileSystem> extraRoots) {
        this.files = files;
        this.extraRoots = extraRoots == null ? List.of() : List.copyOf(extraRoots);
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
        prompt.append("type=sop 为可执行步骤；type=background 为反复适用的领域背景/约束，执行相关任务前建议先读取。\n");
        for (Skill skill : skills) {
            prompt.append("\n<skillname>\n")
                    .append("<name>").append(skill.name()).append("</name>\n")
                    .append("<type>").append(skill.type()).append("</type>\n")
                    .append("<description>").append(skill.description()).append("</description>\n")
                    .append("<position>").append(skill.position()).append("</position>\n")
                    .append("</skillname>\n");
        }
        return prompt.toString();
    }

    /**
     * 列出全部技能：主根在前、附加根在后合并，同名单根内按名称排序。
     */
    public List<Skill> skills() {
        List<Skill> skills = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // 主根优先：重名时附加根条目只加 (rsi) 标注，不覆盖主根
        scan(files, skills, seen, false);
        for (SkillFileSystem extra : extraRoots) {
            scan(extra, skills, seen, true);
        }
        skills.sort(Comparator.comparing(Skill::name, String.CASE_INSENSITIVE_ORDER));
        return skills;
    }

    /**
     * 扫描一个根目录下的技能文件夹并追加到结果。
     *
     * @param fromExtra 是否来自附加根（重名时据此加 (rsi) 标注）
     */
    private void scan(SkillFileSystem files, List<Skill> skills, Set<String> seen, boolean fromExtra) {
        for (SkillFileSystem.Entry entry : files.list("").entries()) {
            if (!entry.directory()) {
                continue;
            }
            String skillFile = entry.path() + "/" + SKILL_FILE;
            if (!files.exists(skillFile)) {
                continue;
            }
            String text = files.read(skillFile);
            Map<String, Object> front = frontmatter(text);
            String name = entry.name();
            String displayName = fromExtra && !seen.add(name) ? name + "(rsi)" : name;
            seen.add(name);
            skills.add(new Skill(displayName,
                    front.containsKey(DESCRIPTION) ? String.valueOf(front.get(DESCRIPTION)).strip() : "",
                    files.absolute(skillFile),
                    skillType(front)));
        }
    }

    /**
     * frontmatter 的 type 字段规范化：sop / background，缺失或其他值按 sop。
     */
    private static String skillType(Map<String, Object> frontmatter) {
        Object value = frontmatter.get(TYPE);
        if (value != null && "background".equalsIgnoreCase(String.valueOf(value).strip())) {
            return "background";
        }
        return "sop";
    }

    /**
     * 解析首尾 --- 之间的 frontmatter（标准 YAML），失败时按没有 frontmatter 处理。
     * 公开为静态方法：RSI 沉淀侧（SkillReviewer 输入、pipeline 写盘校验）复用同一解析。
     */
    public static Map<String, Object> frontmatter(String text) {
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
     * @description 技能摘要：名称（文件夹名，附加根重名时带 (rsi) 标注）、描述、
     * SKILL.md 绝对路径与类型（sop / background）
     */
    public record Skill(String name, String description, String position, String type) {
    }
}
