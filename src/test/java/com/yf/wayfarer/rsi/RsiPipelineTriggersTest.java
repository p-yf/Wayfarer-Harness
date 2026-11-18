package com.yf.wayfarer.rsi;

import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.SessionMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RsiPipelineTriggers 纯判定逻辑单测（不碰调度器）。
 *
 * @author yyf
 * @description 覆盖暖启动翻倍封顶、L1 触发判定、idle 兜底判定与窗口切分（末尾批量 + 背景 + 游标）
 */
class RsiPipelineTriggersTest {

    private static RsiPipelineTriggers triggers() {
        // 全零走代码内缺省值：start=1 / max=16 / batch=10 / background=5 / idle=60s
        return new RsiPipelineTriggers(new RsiProperties(
                true, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    private static SessionMessage msg(int seq) {
        return new SessionMessage("m-" + seq, "t-1", JSONMsgType.USER,
                Map.of("content", "消息" + seq));
    }

    @Test
    void 暖启动翻倍封顶() {
        RsiPipelineTriggers triggers = triggers();
        assertEquals(1, triggers.initialThreshold());
        assertEquals(2, triggers.nextThreshold(1));
        assertEquals(6, triggers.nextThreshold(3));
        assertEquals(16, triggers.nextThreshold(16));
        assertEquals(16, triggers.nextThreshold(100));
        // 0 视为未初始化，按初始阈值翻倍
        assertEquals(2, triggers.nextThreshold(0));
    }

    @Test
    void L1触发判定() {
        RsiPipelineTriggers triggers = triggers();
        assertTrue(triggers.shouldFireL1(1, 1));
        assertFalse(triggers.shouldFireL1(0, 1));
        assertFalse(triggers.shouldFireL1(9, 10));
        assertTrue(triggers.shouldFireL1(10, 10));
    }

    @Test
    void idle兜底判定() {
        RsiPipelineTriggers triggers = triggers();
        // 缓冲非空 + 超 60s 没活动
        assertTrue(triggers.shouldIdleFire(1, 60_000));
        assertFalse(triggers.shouldIdleFire(1, 59_999));
        // 缓冲为空永远不触发
        assertFalse(triggers.shouldIdleFire(0, Long.MAX_VALUE));
    }

    @Test
    void 窗口切分整批前移() {
        RsiPipelineTriggers triggers = triggers();
        List<SessionMessage> conversation = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            conversation.add(msg(i));
        }
        RsiPipelineTriggers.Window window = triggers.sliceWindow(conversation, 0);
        // 新消息取末尾 10 条，背景取其前 5 条，游标整批前移
        assertEquals(10, window.newMessages().size());
        assertEquals("消息21", String.valueOf(window.newMessages().get(0).content().get("content")));
        assertEquals(5, window.background().size());
        assertEquals("消息16", String.valueOf(window.background().get(0).content().get("content")));
        assertEquals(30, window.newCursor());
    }

    @Test
    void 窗口切分待处理不足一批时全取() {
        RsiPipelineTriggers triggers = triggers();
        List<SessionMessage> conversation = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            conversation.add(msg(i));
        }
        RsiPipelineTriggers.Window window = triggers.sliceWindow(conversation, 28);
        assertEquals(2, window.newMessages().size());
        // 背景不越过游标
        assertTrue(window.background().isEmpty());
        assertEquals(30, window.newCursor());
    }

    @Test
    void 无待处理消息返回空窗口() {
        RsiPipelineTriggers triggers = triggers();
        List<SessionMessage> conversation = List.of(msg(1), msg(2));
        RsiPipelineTriggers.Window window = triggers.sliceWindow(conversation, 2);
        assertTrue(window.newMessages().isEmpty());
        assertTrue(window.background().isEmpty());
        assertEquals(2, window.newCursor());
    }
}
