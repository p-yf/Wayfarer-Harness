package com.yf.wayfarer.rsi;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillTools 与技能装配守卫单测（工具落盘行为，不调 LLM）。
 *
 * @author yyf
 * @description 覆盖：create/update/patch 落盘与计数、标准 frontmatter（name=目录名、
 * description 合法纯标量）、非标准技能名拒绝、重名 create 拒绝、软上限拦截、
 * patch 唯一性与 replace_all、record_preferences 收集、守卫拦截缺 name 参数
 */
class SkillToolsTest {

    @TempDir
    Path temp;

    private Path skillsDir() {
        return temp.resolve("skills");
    }

    private static AgentContext context() {
        return new AgentContext();
    }

    private SkillTools tools(int softLimit) {
        return new SkillTools(skillsDir(), softLimit, 0, new SkillTools.Counters());
    }

    @Test
    void 创建重写修订全链路() {
        SkillTools.Counters counters = new SkillTools.Counters();
        SkillTools tools = new SkillTools(skillsDir(), 20, 0, counters);
        // create：frontmatter 由工具按标准组装（name=目录名，首行）
        String reply = tools.createSkill(context(), "build-env", "background",
                "构建约定：镜像配置", "# 步骤\n\n1. 安装 JDK 21");
        assertTrue(reply.contains("已创建技能"));
        assertEquals("build-env", reply.replace("已创建技能：", "").strip(), "落盘名应是规范化 kebab-case 名");
        Path file = Path.of(temp.toString(), "skills", "build-env", "SKILL.md");
        String text = readAll(file);
        // description 中的"冒号+空格"被替换为" - "（纯标量不允许冒号空格）
        assertTrue(text.startsWith("---\nname: build-env\ntype: background\ndescription: 构建约定 - 镜像配置\n---"));
        assertTrue(text.contains("JDK 21"));
        assertEquals(1, counters.createdCount());
        // 重名 create 被拒绝
        assertTrue(tools.createSkill(context(), "build-env", "sop", "d", "c").contains("已存在同名技能"));
        assertEquals(1, counters.createdCount());
        // patch：唯一匹配替换
        assertTrue(tools.patchSkill(context(), "build-env", "JDK 21", "JDK 25", null).contains("已修订"));
        assertTrue(readAll(file).contains("JDK 25"));
        assertEquals(1, counters.patchedCount());
        // patch：多处出现且未给 replace_all 拒绝
        tools.updateSkill(context(), "build-env", "步骤 1 相同片段\n步骤 2 相同片段", null, null);
        assertTrue(tools.patchSkill(context(), "build-env", "相同片段", "唯一片段", null).contains("多次"));
        // patch：replace_all 替换全部
        assertTrue(tools.patchSkill(context(), "build-env", "相同片段", "唯一片段",
                Boolean.TRUE).contains("已修订"));
        assertEquals(2, countOccurrences(readAll(file), "唯一片段"));
        // update：整篇重写，frontmatter 的 name 保持与目录名一致
        assertTrue(tools.updateSkill(context(), "build-env", "全新正文", "新描述", "sop").contains("已重写"));
        String rewritten = readAll(file);
        assertTrue(rewritten.startsWith("---\nname: build-env\ntype: sop\ndescription: 新描述\n---"));
        assertTrue(rewritten.contains("全新正文"));
        // 模型自带 frontmatter 的 content 被剥掉，不会嵌套两份
        tools.createSkill(context(), "second-skill", "sop", "描述",
                "---\ntype: background\ndescription: 模型给的\n---\n\n正文内容");
        String second = readAll(Path.of(temp.toString(), "skills", "second-skill", "SKILL.md"));
        assertEquals(1, countOccurrences(second, "description:"));
        assertTrue(second.contains("正文内容"));
        // record_preferences
        assertTrue(tools.recordPreferences(context(), "用户偏好中文回复\n构建用 Gradle").contains("2 条"));
        assertEquals(2, counters.preferences().size());
        assertEquals(2, counters.updatedCount());
    }

    @Test
    void 非标准技能名被拒绝并指路() {
        SkillTools tools = tools(20);
        // 中文名不符合 kebab-case 标准，直接拒绝且不落盘
        String reply = tools.createSkill(context(), "构建环境", "background", "d", "c");
        assertTrue(reply.contains("不符合标准格式"));
        assertFalse(Files.exists(skillsDir().resolve("构建环境")), "拒绝的技能不应落盘");
        // 非法字符名同样拒绝
        assertTrue(tools.createSkill(context(), "bad_name!", "sop", "d", "c").contains("不符合标准格式"));
        // 标准名归一化：大写与空格被折叠
        assertEquals("build-env", SkillTools.standardSkillName("Build Env"));
        assertNull(SkillTools.standardSkillName("构建环境"));
        assertNull(SkillTools.standardSkillName("a"));
        assertNull(SkillTools.standardSkillName("-bad-"));
    }

    @Test
    void 软上限拦截create() {
        SkillTools tools = tools(1);
        String reply = tools.createSkill(context(), "first-skill", "sop", "d", "c");
        assertTrue(reply.contains("软上限"), "已达软上限（0+0 >= 1）应拒绝新建");
    }

    @Test
    void 不存在的目标一律拒绝() {
        SkillTools tools = tools(20);
        assertTrue(tools.patchSkill(context(), "ghost", "a", "b", null).contains("不存在"));
        assertTrue(tools.updateSkill(context(), "ghost", "c", null, null).contains("不存在"));
        assertTrue(tools.readSkill(context(), "ghost").contains("不存在"));
        assertTrue(tools.createSkill(context(), "", "sop", "d", "c").contains("技能名非法"));
    }

    @Test
    void 守卫拦截缺name参数的变更调用() {
        Path dir = skillsDir();
        SkillTools.Counters counters = new SkillTools.Counters();
        ToolSandboxGuardHandler guard = new ToolSandboxGuardHandler(dir,
                Set.of("create_skill", "update_skill", "patch_skill"),
                List.of("name"),
                name -> SkillTools.resolveSkillFile(dir, name), "技能目录");
        AgentContext context = context();
        List<com.yf.wayfarer.model.ChatMessage> messages = new java.util.ArrayList<>();
        context.put(AgentContext.MESSAGES, messages);
        context.put(AgentContext.CURRENT_TOOL_CALL,
                new ToolCallInfo("c-1", "patch_skill", "{\"old_string\":\"a\"}"));
        boolean[] nextReached = {false};
        guard.run(context, () -> nextReached[0] = true);
        assertFalse(nextReached[0], "缺 name 参数必须拦截");
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).content().contains("拒绝执行"));
    }

    @Test
    void 路径穿越名称被清洗在技能目录内() {
        Path resolved = SkillTools.resolveSkillFile(skillsDir(), "..\\..\\evil");
        assertTrue(resolved.toAbsolutePath().normalize().startsWith(skillsDir().toAbsolutePath().normalize()));
        assertEquals("SKILL.md", resolved.getFileName().toString());
    }

    private static String readAll(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
