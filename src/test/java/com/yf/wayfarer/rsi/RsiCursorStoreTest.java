package com.yf.wayfarer.rsi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RsiCursorStore 单测。
 *
 * @author yyf
 * @description 覆盖游标的持久化往返（触发链全字段）、缺省值、损坏文件兜底与按会话取游标
 */
class RsiCursorStoreTest {

    @TempDir
    Path temp;

    private RsiCursorStore store() {
        return new RsiCursorStore(new RsiPaths(temp.resolve("rsi").toString()));
    }

    @Test
    void missingFileReturnsFreshCursor() {
        RsiCursorStore.Cursor cursor = store().get("wayfarer-rsi");
        assertEquals(0, cursor.getL1Threshold());
        assertEquals(0, cursor.getMemoriesSincePersona());
        assertFalse(cursor.isPersonaRequest());
        assertFalse(cursor.isPersonaEverGenerated());
        assertEquals(0, cursor.getL2ConsumedCount());
        assertTrue(cursor.getSkillRounds().isEmpty());
        // 首次取会话游标自动补零
        assertEquals(0, cursor.session("s-1").getL1Cursor());
    }

    @Test
    void saveAndLoadRoundTrip() {
        RsiCursorStore store = store();
        RsiCursorStore.Cursor cursor = store.get("wayfarer-rsi");
        cursor.session("s-1").setL1Cursor(42);
        cursor.session("s-2").setL1Cursor(7);
        cursor.setL1Threshold(8);
        cursor.setMemoriesSincePersona(5);
        cursor.setPersonaRequest(true);
        cursor.setPersonaEverGenerated(true);
        cursor.setL2ConsumedCount(9);
        cursor.addSkillRound(new RsiCursorStore.Round("s-1", "t-1"));
        store.save("wayfarer-rsi", cursor);

        RsiCursorStore.Cursor loaded = store.get("wayfarer-rsi");
        assertEquals(42, loaded.session("s-1").getL1Cursor());
        assertEquals(7, loaded.session("s-2").getL1Cursor());
        assertEquals(8, loaded.getL1Threshold());
        assertEquals(5, loaded.getMemoriesSincePersona());
        assertTrue(loaded.isPersonaRequest());
        assertTrue(loaded.isPersonaEverGenerated());
        assertEquals(9, loaded.getL2ConsumedCount());
        assertEquals(1, loaded.getSkillRounds().size());
        assertEquals("t-1", loaded.getSkillRounds().get(0).getTraceId());
    }

    @Test
    void clearSkillRoundsEmptiesQueue() {
        RsiCursorStore.Cursor cursor = store().get("wayfarer-rsi");
        cursor.addSkillRound(new RsiCursorStore.Round("s-1", "t-1"));
        cursor.addSkillRound(new RsiCursorStore.Round("s-1", "t-2"));
        cursor.clearSkillRounds();
        assertTrue(cursor.getSkillRounds().isEmpty());
    }

    @Test
    void corruptedFileFallsBackToFreshCursor() throws Exception {
        Path file = temp.resolve("rsi/state/wayfarer-rsi/cursor.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ not json");
        // 损坏不抛异常：游标只是性能优化，正确性由 L1 去重保证
        assertEquals(0, store().get("wayfarer-rsi").getL2ConsumedCount());
    }

    @Test
    void agentsHaveIndependentCursors() {
        RsiCursorStore store = store();
        RsiCursorStore.Cursor a = store.get("wayfarer-rsi");
        a.setMemoriesSincePersona(3);
        store.save("wayfarer-rsi", a);
        // 另一个 agent 的游标互不影响
        assertEquals(0, store.get("wayfarer").getMemoriesSincePersona());
    }
}
