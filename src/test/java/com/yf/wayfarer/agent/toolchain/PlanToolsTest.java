package com.yf.wayfarer.agent.toolchain;

import com.yf.wayfarer.loop.AgentContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划工具测试。
 *
 * @author yyf
 * @description 覆盖计划写入与覆盖、状态更新、全部完成清除、非法状态提示与状态持久化输出
 */
class PlanToolsTest {

    private final PlanTools tools = new PlanTools();

    @Test
    void 写入计划并记入状态() {
        AgentContext context = new AgentContext();

        String text = tools.plan(context, Map.of("1", "读取日志", "2", "分析错误"));

        assertTrue(text.contains("读取日志") && text.contains("分析错误"), text);
        Map<?, ?> plan = state(context);
        assertEquals(2, plan.size());
        assertEquals("not_begin", item(context, "1").get("status"));
    }

    @Test
    void 已有序号覆盖内容并保留状态() {
        AgentContext context = new AgentContext();
        tools.plan(context, Map.of("1", "读取日志"));
        tools.updateStatus(context, Map.of("1", "doing"));

        tools.plan(context, Map.of("1", "读取错误日志"));

        assertEquals("读取错误日志", item(context, "1").get("content"));
        assertEquals("doing", item(context, "1").get("status"));
    }

    @Test
    void 更新状态后可查看完整计划() {
        AgentContext context = new AgentContext();
        tools.plan(context, Map.of("1", "读取日志", "2", "分析错误"));

        String text = tools.updateStatus(context, Map.of("1", "done", "2", "doing"));

        assertTrue(text.contains("done") && text.contains("doing"), text);
        assertTrue(tools.planList(context).contains("读取日志"));
    }

    @Test
    void 全部完成后自动清除计划() {
        AgentContext context = new AgentContext();
        tools.plan(context, Map.of("1", "读取日志", "2", "分析错误"));

        String text = tools.updateStatus(context, Map.of("1", "done", "2", "done"));

        assertTrue(text.contains("清除"), text);
        assertNull(context.get(AgentContext.PLAN));
        assertEquals("当前没有计划", tools.planList(context));
    }

    @Test
    void 非法状态不更新并给出提示() {
        AgentContext context = new AgentContext();
        tools.plan(context, Map.of("1", "读取日志"));

        String text = tools.updateStatus(context, Map.of("1", "finished", "9", "done"));

        assertTrue(text.contains("done、doing、not_begin"), text);
        assertTrue(text.contains("9(不存在)"), text);
        assertEquals("not_begin", item(context, "1").get("status"));
    }

    @Test
    void 计划随可持久化状态输出() {
        AgentContext context = new AgentContext();

        tools.plan(context, Map.of("1", "读取日志"));

        assertTrue(context.storeState().containsKey(AgentContext.PLAN), "计划应进入可持久化状态");
    }

    private static Map<?, ?> state(AgentContext context) {
        Object plan = context.get(AgentContext.PLAN);
        assertTrue(plan instanceof Map, "计划状态应为映射");
        return (Map<?, ?>) plan;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> item(AgentContext context, String seq) {
        return (Map<String, String>) state(context).get(seq);
    }
}
