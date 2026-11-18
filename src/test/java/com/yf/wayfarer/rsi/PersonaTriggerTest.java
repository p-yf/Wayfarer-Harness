package com.yf.wayfarer.rsi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PersonaTrigger 五级触发单测（纯函数）。
 *
 * @author yyf
 * @description 覆盖 P1 显式请求 / P2 冷启动 / P2.5 恢复 / P3 首场景 / P4 阈值 /
 * NONE，以及优先级次序
 */
class PersonaTriggerTest {

    private static PersonaTrigger.State state(boolean personaRequest, boolean hasScenes,
                                              boolean everGenerated, boolean fileExists,
                                              boolean bodyBlank, int sceneCount,
                                              int totalMemories, int since, int everyN) {
        return new PersonaTrigger.State(personaRequest, hasScenes, everGenerated,
                fileExists, bodyBlank, sceneCount, totalMemories, since, everyN);
    }

    @Test
    void P1显式请求最高优先() {
        PersonaTrigger.Decision decision = PersonaTrigger.evaluate(
                state(true, true, true, true, false, 5, 100, 0, 20));
        assertEquals(PersonaTrigger.Level.P1_REQUEST, decision.level());
        assertTrue(decision.triggered());
    }

    @Test
    void P2冷启动() {
        PersonaTrigger.Decision decision = PersonaTrigger.evaluate(
                state(false, true, false, false, false, 3, 10, 0, 20));
        assertEquals(PersonaTrigger.Level.P2_COLD_START, decision.level());
    }

    @Test
    void P2冷启动需要场景与记忆齐备() {
        // 无场景不触发
        assertEquals(PersonaTrigger.Level.NONE,
                PersonaTrigger.evaluate(state(false, false, false, false, false, 0, 10, 0, 20)).level());
        // 无记忆不触发
        assertEquals(PersonaTrigger.Level.NONE,
                PersonaTrigger.evaluate(state(false, true, false, false, false, 3, 0, 0, 20)).level());
    }

    @Test
    void P25恢复() {
        PersonaTrigger.Decision decision = PersonaTrigger.evaluate(
                state(false, true, true, true, true, 3, 50, 0, 20));
        assertEquals(PersonaTrigger.Level.P2_5_RECOVER, decision.level());
    }

    @Test
    void P3首场景() {
        PersonaTrigger.Decision decision = PersonaTrigger.evaluate(
                state(false, true, true, false, false, 1, 10, 2, 20));
        assertEquals(PersonaTrigger.Level.P3_FIRST_SCENE, decision.level());
    }

    @Test
    void P4例行阈值() {
        PersonaTrigger.Decision decision = PersonaTrigger.evaluate(
                state(false, true, true, false, false, 5, 100, 20, 20));
        assertEquals(PersonaTrigger.Level.P4_THRESHOLD, decision.level());
        // 未达阈值不触发
        assertEquals(PersonaTrigger.Level.NONE,
                PersonaTrigger.evaluate(state(false, true, true, false, false, 5, 100, 19, 20)).level());
    }

    @Test
    void 全不满足返回NONE() {
        PersonaTrigger.Decision decision = PersonaTrigger.evaluate(
                state(false, true, true, false, false, 5, 100, 1, 20));
        assertEquals(PersonaTrigger.Level.NONE, decision.level());
        assertFalse(decision.triggered());
    }

    @Test
    void 优先级P1高于P2() {
        // 同时满足 P1 与 P2 冷启动：P1 胜出
        assertEquals(PersonaTrigger.Level.P1_REQUEST,
                PersonaTrigger.evaluate(state(true, true, false, false, false, 3, 10, 0, 20)).level());
    }
}
