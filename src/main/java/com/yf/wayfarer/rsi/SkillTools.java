package com.yf.wayfarer.rsi;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.skill.SkillStore;
import com.yf.wayfarer.toolcall.Tool;
import com.yf.wayfarer.toolcall.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 技能库文件操作工具集（技能评审 agent 的受控写入口）。
 *
 * <p>技能评审 agent 只能通过本工具读写沉淀技能文件，工具直接落在
 * {@code stores/<agent>/skills/} 目录上（每个技能一个文件夹、内含 SKILL.md），
 * 越界防护由 {@link ToolSandboxGuardHandler} 在工具链上统一拦截，本类不做权限校验。
 * frontmatter（type / description）由工具按参数统一组装，避免模型写坏格式。</p>
 *
 * <p>并发说明：技能写路径只有沉淀管线一处入口，per-agent 沉淀锁保证同一 agent
 * 的评审串行执行（单写者），因此不需要内容级乐观锁；重名 create 直接拒绝，
 * 由 agent 自行转用修订（capture-first 的硬约束面）。</p>
 *
 * <p>工具只操作一个固定 agent 的技能目录（每次评审构造一个实例），
 * 执行结果通过 {@link Counters} 计数器共享给 {@link SkillReviewer} 汇总为 Outcome。</p>
 *
 * @author yyf
 * @description list_skills/read_skill 供 capture-first 比对，create_skill/update_skill/
 * patch_skill 执行三类变更，record_preferences 收集转投记忆的偏好事实；resolveSkillFile()
 * 负责技能名 → SKILL.md 路径（守卫与工具共用同一套解析，保证守卫校验的就是工具实际要写的文件）
 */
public class SkillTools {

    private static final Logger log = LoggerFactory.getLogger(SkillTools.class);

    private static final String SKILL_FILE = "SKILL.md";

    private final Path skillsDir;
    private final int softLimit;
    private final int currentCount;
    private final Counters counters;

    public SkillTools(Path skillsDir, int softLimit, int currentCount, Counters counters) {
        this.skillsDir = skillsDir;
        this.softLimit = Math.max(1, softLimit);
        this.currentCount = currentCount;
        this.counters = counters;
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                                  */
    /* ------------------------------------------------------------------ */

    @Tool(name = "list_skills", description =
            "列出沉淀技能库中全部既有技能（名称、类型、描述），评审前先调用它了解现状。")
    public String listSkills(AgentContext context) {
        List<String> lines = new ArrayList<>();
        if (Files.isDirectory(skillsDir)) {
            try (var stream = Files.list(skillsDir)) {
                for (Path dir : stream.filter(Files::isDirectory).sorted().toList()) {
                    Path file = dir.resolve(SKILL_FILE);
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    var front = SkillStore.frontmatter(readString(file));
                    lines.add("- " + dir.getFileName()
                            + "（" + front.getOrDefault("type", "sop") + "）："
                            + String.valueOf(front.getOrDefault("description", "（无描述）")));
                }
            } catch (IOException e) {
                log.warn("RSI 技能目录扫描失败：{}", skillsDir, e);
            }
        }
        return lines.isEmpty() ? "技能库为空。" : String.join("\n", lines);
    }

    @Tool(name = "read_skill", description =
            "读取某个技能的完整 SKILL.md 原文，修订前必须先读取（patch 的 old_string 要精确抄写原文）。")
    public String readSkill(AgentContext context,
                            @ToolParam(description = "技能名") String name) {
        Path file = resolveSkillFile(skillsDir, name);
        if (file == null || !Files.isRegularFile(file)) {
            return "技能不存在：" + name + "，可用 list_skills 查看现有技能。";
        }
        return readString(file);
    }

    @Tool(name = "create_skill", description =
            "新建技能：name 必须是 kebab-case 英文（小写字母/数字/单连字符，2-64 字符，如 build-env）；"
                    + "description 单行、写清（做什么 + 何时用）；content 为 Markdown 正文（不含 frontmatter，"
                    + "frontmatter 由工具按标准生成）。确无重叠的既有技能才使用；重名或达到软上限会被拒绝，"
                    + "此时应改用修订类工具。")
    public String createSkill(AgentContext context,
                              @ToolParam(description = "新技能名：kebab-case 英文，小写字母/数字/单连字符，2-64 字符")
                              String name,
                              @ToolParam(description = "技能类型：sop=可执行步骤，background=反复适用的领域背景")
                              String type,
                              @ToolParam(description = "一句话用途描述（做什么 + 何时用，单行）")
                              String description,
                              @ToolParam(description = "技能正文（Markdown）") String content) {
        // 软上限硬约束（与任务文本里的收紧提示双保险）：只差 1 个到上限即禁 CREATE
        if (currentCount + counters.createdCount() >= softLimit - 1) {
            counters.skipped();
            return "技能数已达软上限（" + softLimit + "），禁止新建，请改用 patch_skill / update_skill 修订既有技能。";
        }
        Path file = resolveSkillFile(skillsDir, name);
        if (file == null) {
            counters.skipped();
            return "技能名非法：" + name;
        }
        String standardName = standardSkillName(name);
        if (standardName == null) {
            // 标准格式硬校验：kebab-case 英文名，含中文等非规范字符时直接拒绝并指路
            counters.skipped();
            return "技能名 \"" + name + "\" 不符合标准格式：必须是小写字母/数字/单连字符的 kebab-case 英文"
                    + "（2-64 字符，如 build-env），请换用英文技能名重试。";
        }
        if (Files.exists(file)) {
            // 重名拒绝（capture-first 硬约束）：agent 应转用修订
            counters.skipped();
            return "已存在同名技能「" + name + "」，禁止覆盖式新建，请 read_skill 后用 patch_skill / update_skill 修订。";
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, skillFileText(standardName, type, description, content),
                    StandardCharsets.UTF_8);
            counters.created();
            return "已创建技能：" + standardName;
        } catch (IOException e) {
            counters.skipped();
            log.warn("RSI 创建技能失败 name={}", name, e);
            return "创建失败：" + e.getMessage();
        }
    }

    @Tool(name = "update_skill", description =
            "整篇重写既有技能：content 为重写后的完整正文（不含 frontmatter）。"
                    + "适用于结构性改写；小步修订请用 patch_skill。目标技能必须已存在。")
    public String updateSkill(AgentContext context,
                              @ToolParam(description = "目标技能名") String name,
                              @ToolParam(description = "重写后的完整正文（Markdown）") String content,
                              @ToolParam(required = false, description = "更新后的一句话描述（不给则保留原描述）")
                              String description,
                              @ToolParam(required = false, description = "技能类型：sop / background（不给则保留原类型）")
                              String type) {
        Path file = resolveSkillFile(skillsDir, name);
        if (file == null || !Files.isRegularFile(file)) {
            counters.skipped();
            return "技能不存在，未更新：" + name;
        }
        var front = SkillStore.frontmatter(readString(file));
        String nextDescription = blank(description)
                ? String.valueOf(front.getOrDefault("description", "")) : description.strip();
        String nextType = blank(type)
                ? String.valueOf(front.getOrDefault("type", "sop")) : type;
        try {
            // frontmatter 的 name 始终取文件夹名，保证与目录名一致（标准要求）
            Files.writeString(file, skillFileText(file.getParent().getFileName().toString(),
                    nextType, nextDescription, content), StandardCharsets.UTF_8);
            counters.updated();
            return "已重写技能：" + name;
        } catch (IOException e) {
            counters.skipped();
            log.warn("RSI 重写技能失败 name={}", name, e);
            return "重写失败：" + e.getMessage();
        }
    }

    @Tool(name = "patch_skill", description =
            "小步修订既有技能：把 old_string 精确替换为 new_string。old_string 必须来自"
                    + " read_skill 读到的原文且在全文中唯一；确需替换多处相同片段时给 replace_all=true。")
    public String patchSkill(AgentContext context,
                             @ToolParam(description = "目标技能名") String name,
                             @ToolParam(description = "被替换的原文片段（精确匹配，必须唯一）") String oldString,
                             @ToolParam(description = "替换后的文本") String newString,
                             @ToolParam(required = false, description = "替换全部相同片段（默认 false，要求唯一匹配）")
                             Boolean replaceAll) {
        Path file = resolveSkillFile(skillsDir, name);
        if (file == null || !Files.isRegularFile(file)) {
            counters.skipped();
            return "技能不存在，未修订：" + name;
        }
        if (blank(oldString) || newString == null) {
            counters.skipped();
            return "old_string / new_string 不能为空。";
        }
        String current = readString(file);
        int first = current.indexOf(oldString);
        if (first < 0) {
            counters.skipped();
            return "old_string 在「" + name + "」中不存在，请 read_skill 后用原文精确重试。";
        }
        boolean all = Boolean.TRUE.equals(replaceAll);
        if (!all && current.indexOf(oldString, first + 1) >= 0) {
            counters.skipped();
            return "old_string 在「" + name + "」中出现多次且未指定 replace_all，无法唯一定位。"
                    + "请扩大片段范围使其唯一，或给 replace_all=true。";
        }
        String applied = all
                ? current.replace(oldString, newString)
                : current.substring(0, first) + newString + current.substring(first + oldString.length());
        try {
            Files.writeString(file, applied, StandardCharsets.UTF_8);
            counters.patched();
            return "已修订技能：" + name;
        } catch (IOException e) {
            counters.skipped();
            log.warn("RSI 修订技能失败 name={}", name, e);
            return "修订失败：" + e.getMessage();
        }
    }

    @Tool(name = "record_preferences", description =
            "把「用户/项目偏好类事实」记录为待转投记忆库的条目（不属于 skill）。"
                    + "facts 每行一条原子事实；只有确属偏好时才使用。")
    public String recordPreferences(AgentContext context,
                                    @ToolParam(description = "偏好事实，每行一条") String facts) {
        if (blank(facts)) {
            counters.skipped();
            return "facts 为空，未记录。";
        }
        int recorded = 0;
        for (String line : facts.strip().split("\n")) {
            String fact = line.strip();
            if (!fact.isEmpty()) {
                counters.preference(fact);
                recorded++;
            }
        }
        return recorded == 0 ? "没有可记录的偏好事实。" : "已记录 " + recorded + " 条偏好事实（将转投记忆库）。";
    }

    /* ------------------------------------------------------------------ */
    /* 路径解析（守卫与工具共用）                                                */
    /* ------------------------------------------------------------------ */

    /**
     * 技能名 → 技能库内的 SKILL.md 路径（目录名 = 清洗后的技能名）。
     * 返回 null 表示技能名非法（空/空白）。
     */
    public static Path resolveSkillFile(Path skillsDir, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return skillsDir.resolve(sanitizeSkillName(name)).resolve(SKILL_FILE);
    }

    /**
     * 技能名 → 安全目录名：去路径非法字符（Windows 集合，含路径分隔符，
     * 因此 "../x" 之类的穿越输入会被清洗为无害文本），压到 40 字符，
     * 空结果回退 hash。
     */
    public static String sanitizeSkillName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").strip();
        if (cleaned.isEmpty()) {
            cleaned = "skill-" + Integer.toHexString(name == null ? 0 : name.hashCode());
        }
        return cleaned.length() <= 40 ? cleaned : cleaned.substring(0, 40);
    }

    /**
     * 标准技能名校验（Agent Skills 规范口径）：小写 a-z/0-9、单词间单连字符，
     * 2-64 字符，以字母或数字开头结尾；空格折叠为连字符。
     * 返回 null 表示不符合标准（含中文等非规范字符），调用方应拒绝并指路。
     */
    public static String standardSkillName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.strip().toLowerCase().replaceAll("\\s+", "-");
        if (!normalized.matches("[a-z0-9]+(-[a-z0-9]+)*") || normalized.length() < 2
                || normalized.length() > 64) {
            return null;
        }
        return normalized;
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                  */
    /* ------------------------------------------------------------------ */

    /**
     * 组装 SKILL.md 全文（Agent Skills 标准格式）：frontmatter 的 name 必须等于
     * 目录名、description 为单行纯标量（无"冒号+空格"、无尖括号、不加引号），
     * type 为本项目扩展键（sop / background，标准允许额外键）；正文剥掉模型
     * 可能自带的 frontmatter 后拼接。
     */
    private static String skillFileText(String name, String type, String description, String content) {
        String body = content == null ? "" : content.strip();
        if (body.startsWith("---")) {
            // 模型自带 frontmatter 时剥掉，避免嵌套两份
            int end = body.indexOf("\n---", 3);
            if (end >= 0) {
                body = body.substring(body.indexOf('\n', end + 1)).strip();
            }
        }
        String desc = sanitizeDescription(description);
        String normalizedType = SkillReviewer.normalizeType(type);
        return "---\nname: " + name + "\ntype: " + normalizedType + "\ndescription: " + desc
                + "\n---\n\n"
                + (body.isEmpty() ? "（模型未提供正文）" : body) + "\n";
    }

    /**
     * description 清洗为合法的 YAML 单行纯标量：剥包裹引号、"冒号+空格"（中英文）
     * 换成" - "、去掉尖括号、压成单行、超长截断（目标 200 字符内，硬上限 500）。
     */
    private static String sanitizeDescription(String text) {
        String value = text == null ? "" : text.strip();
        // 剥一层成对包裹引号（纯标量不加引号）
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1).strip();
        }
        value = value.replace(": ", " - ").replace("：", " - ")
                .replace("<", "＜").replace(">", "＞")
                .replace('\n', ' ').replace('\r', ' ').strip();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("RSI 读取技能失败：{}", file, e);
            return "";
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * @author yyf
     * @description 工具执行计数器：SkillTools 各工具成功执行后递增，SkillReviewer
     * 据此汇总 Outcome；preferences 收集 record_preferences 的待转投条目；
     * skipped() 单独计数（目标不存在/被上限拦截等未生效操作）
     */
    public static final class Counters {

        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger updated = new AtomicInteger();
        private final AtomicInteger patched = new AtomicInteger();
        private final AtomicInteger skipped = new AtomicInteger();
        private final List<String> preferences = Collections.synchronizedList(new ArrayList<>());

        public void created() {
            created.incrementAndGet();
        }

        public void updated() {
            updated.incrementAndGet();
        }

        public void patched() {
            patched.incrementAndGet();
        }

        public void skipped() {
            skipped.incrementAndGet();
        }

        public void preference(String fact) {
            preferences.add(fact);
        }

        public int createdCount() {
            return created.get();
        }

        public int updatedCount() {
            return updated.get();
        }

        public int patchedCount() {
            return patched.get();
        }

        public int skippedCount() {
            return skipped.get();
        }

        public List<String> preferences() {
            return List.copyOf(preferences);
        }
    }
}
