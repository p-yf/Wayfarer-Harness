package com.yf.wayfarer.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 技能清单测试，基于真实 skills 目录。
 *
 * @author yyf
 * @description 只收录包含 SKILL.md 的文件夹，并解析 frontmatter 中的 description
 */
class SkillStoreTest {

    @TempDir
    Path root;

    @Test
    void 清单包含名称描述路径与读取说明() {
        SkillFileSystem files = new SkillFileSystem(root);
        files.mkdir("review");
        files.write("review/SKILL.md", "---\nname: review\ndescription: 代码评审技能\n---\n\n正文");
        SkillStore store = new SkillStore(files);

        String prompt = store.prompt();

        assertTrue(prompt.contains("read 工具"), prompt);
        assertTrue(prompt.contains("<name>review</name>"), prompt);
        assertTrue(prompt.contains("<description>代码评审技能</description>"), prompt);
        assertTrue(prompt.contains(Path.of("review", "SKILL.md").toString()), prompt);
    }

    @Test
    void 忽略普通文件与缺少SKILL的文件夹() {
        SkillFileSystem files = new SkillFileSystem(root);
        files.write("loose.md", "plain");
        files.mkdir("empty");
        files.mkdir("alpha");
        files.write("alpha/SKILL.md", "---\ndescription: 第一个\n---\n\n正文");
        SkillStore store = new SkillStore(files);

        assertEquals(1, store.skills().size());
        assertEquals("alpha", store.skills().get(0).name());
        assertFalse(store.prompt().contains("empty"), store.prompt());
    }

    @Test
    void 没有技能时返回空清单() {
        SkillStore store = new SkillStore(new SkillFileSystem(root));

        assertEquals("", store.prompt());
        assertTrue(store.skills().isEmpty());
    }

    @Test
    void 解析块状frontmatter描述() {
        SkillFileSystem files = new SkillFileSystem(root);
        files.mkdir("langfuse");
        files.write("langfuse/SKILL.md",
                "---\nname: langfuse\ndescription: >-\n  第一段说明\n  第二段说明\nallowed-tools:\n  - Bash(curl *)\n---\n\n正文");
        SkillStore store = new SkillStore(files);

        assertEquals("第一段说明 第二段说明", store.skills().get(0).description());
    }

    @Test
    void 能解析仓库内的langfuse技能() {
        Path skills = Path.of("skills");
        assumeTrue(Files.isDirectory(skills.resolve("langfuse")), "仓库未内置 langfuse 技能");
        SkillStore store = new SkillStore(new SkillFileSystem(skills));

        SkillStore.Skill langfuse = store.skills().stream()
                .filter(skill -> "langfuse".equals(skill.name()))
                .findFirst()
                .orElse(null);

        assertNotNull(langfuse, store.prompt());
        assertTrue(langfuse.description().startsWith("Interact with Langfuse"), langfuse.description());
        assertTrue(langfuse.position().endsWith(Path.of("langfuse", "SKILL.md").toString()), langfuse.position());
    }
}
