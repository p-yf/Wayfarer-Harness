package com.yf.wayfarer.agent.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.skill.SkillFileSystem;
import com.yf.wayfarer.skill.SkillStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能清单处理器测试，使用真实技能目录。
 *
 * @author yyf
 * @description 技能清单应写入系统提示词集合并排在最后，没有技能时不影响原有提示词
 */
class SkillsHandlerTest {

    @TempDir
    Path root;

    @Test
    void 技能清单写入系统提示词末尾() {
        SkillFileSystem files = new SkillFileSystem(root);
        files.mkdir("review");
        files.write("review/SKILL.md", "---\nname: review\ndescription: 代码评审技能\n---\n\n正文");
        Map<String, String> prompts = new LinkedHashMap<>();
        prompts.put("base-1", "基础提示词");
        AgentContext context = new AgentContext();
        context.put(AgentContext.SYSTEM_PROMPT, prompts);
        AtomicBoolean passed = new AtomicBoolean(false);

        new SkillsHandler(new SkillStore(files)).run(context, () -> passed.set(true));

        assertTrue(passed.get());
        assertEquals(2, context.systemPrompts().size());
        assertEquals("基础提示词", context.systemPrompts().get("base-1"));
        String skillPrompt = context.systemPrompts().get(SkillsHandler.SKILL_PROMPT_KEY);
        assertTrue(skillPrompt.contains("<name>review</name>"), skillPrompt);
    }

    @Test
    void 没有技能时不写入系统提示词() {
        AgentContext context = new AgentContext();
        AtomicBoolean passed = new AtomicBoolean(false);

        new SkillsHandler(new SkillStore(new SkillFileSystem(root.resolve("missing"))))
                .run(context, () -> passed.set(true));

        assertTrue(passed.get());
        assertFalse(context.containsKey(AgentContext.SYSTEM_PROMPT));
    }
}
