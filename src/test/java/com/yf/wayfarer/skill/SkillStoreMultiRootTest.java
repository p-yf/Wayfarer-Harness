package com.yf.wayfarer.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillStore 多根合并单测（RSI 沉淀技能接入点）。
 *
 * @author yyf
 * @description 覆盖主根+沉淀根合并、重名 (rsi) 标注、type 标注注入清单
 */
class SkillStoreMultiRootTest {

    @TempDir
    Path mainRoot;

    @TempDir
    Path storeRoot;

    @Test
    void 多根合并且主根优先() {
        SkillFileSystem main = new SkillFileSystem(mainRoot);
        main.mkdir("review");
        main.write("review/SKILL.md", "---\ntype: sop\ndescription: 主根评审技能\n---\n正文");
        SkillFileSystem store = new SkillFileSystem(storeRoot);
        store.mkdir("pdf-edit");
        store.write("pdf-edit/SKILL.md", "---\ntype: background\ndescription: 沉淀的领域背景\n---\n正文");

        SkillStore multi = new SkillStore(main, java.util.List.of(store));
        String prompt = multi.prompt();

        // 主根与沉淀根都进清单
        assertTrue(prompt.contains("<name>review</name>"), prompt);
        assertTrue(prompt.contains("<name>pdf-edit</name>"), prompt);
        // type 标注注入
        assertTrue(prompt.contains("<type>sop</type>"), prompt);
        assertTrue(prompt.contains("<type>background</type>"), prompt);
        assertEquals("background", multi.skills().stream()
                .filter(skill -> skill.name().equals("pdf-edit")).findFirst().orElseThrow().type());
    }

    @Test
    void 沉淀根重名时加rsi标注() {
        SkillFileSystem main = new SkillFileSystem(mainRoot);
        main.mkdir("review");
        main.write("review/SKILL.md", "---\ndescription: 主根版本\n---\n正文");
        SkillFileSystem store = new SkillFileSystem(storeRoot);
        store.mkdir("review");
        store.write("review/SKILL.md", "---\ndescription: 沉淀版本\n---\n正文");

        SkillStore multi = new SkillStore(main, java.util.List.of(store));

        // 两个条目都在，沉淀根的带 (rsi) 标注
        assertEquals(2, multi.skills().size());
        SkillStore.Skill annotated = multi.skills().stream()
                .filter(skill -> skill.name().equals("review(rsi)")).findFirst().orElseThrow();
        assertEquals("沉淀版本", annotated.description());
    }

    @Test
    void 缺type时按sop() {
        SkillFileSystem store = new SkillFileSystem(storeRoot);
        store.mkdir("alpha");
        store.write("alpha/SKILL.md", "---\ndescription: 无类型\n---\n正文");
        SkillStore multi = new SkillStore(new SkillFileSystem(mainRoot), java.util.List.of(store));

        assertEquals("sop", multi.skills().get(0).type());
    }
}
