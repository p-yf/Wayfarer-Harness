package com.yf.wayfarer.tools;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.toolcall.Tool;
import com.yf.wayfarer.toolcall.ToolParam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 计划工具。
 *
 * @author yyf
 * @description 供模型维护执行计划：plan 写入或覆盖计划内容，update_status 更新状态，
 * plan_list 查看完整计划；计划写入上下文状态并随会话状态持久化
 */
public class PlanTools {

    public static final String DONE = "done";
    public static final String DOING = "doing";
    public static final String NOT_BEGIN = "not_begin";

    private static final String CONTENT = "content";
    private static final String STATUS = "status";
    private static final Set<String> STATUSES = Set.of(DONE, DOING, NOT_BEGIN);

    @Tool(name = "plan", description = "编写或修改计划：key 为序号，value 为计划内容；已有序号覆盖内容，新序号新增")
    public String plan(AgentContext context,
                       @ToolParam(description = "序号到计划内容的映射，例如 {\"1\":\"读取日志\"}")
                       Map<String, String> plan) {
        if (plan == null || plan.isEmpty()) {
            return "没有提供计划内容";
        }
        Map<String, Map<String, String>> state = state(context);
        plan.forEach((seq, content) -> {
            Map<String, String> item = state.getOrDefault(seq, new LinkedHashMap<>());
            item.put(CONTENT, content == null ? "" : content);
            // 新计划项默认未开始，已有序号只覆盖内容、保留状态
            item.putIfAbsent(STATUS, NOT_BEGIN);
            state.put(seq, item);
        });
        context.put(AgentContext.PLAN, state);
        return render(state);
    }

    @Tool(name = "update_status", description = "更新计划状态：key 为序号，value 为状态 done、doing、not_begin；全部完成时自动清除计划")
    public String updateStatus(AgentContext context,
                               @ToolParam(description = "序号到状态的映射，例如 {\"1\":\"done\"}")
                               Map<String, String> status) {
        Map<String, Map<String, String>> state = state(context);
        if (state.isEmpty()) {
            return "当前没有计划，请先调用 plan 创建计划";
        }
        if (status == null || status.isEmpty()) {
            return "没有提供状态";
        }
        List<String> invalid = new ArrayList<>();
        status.forEach((seq, value) -> {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            Map<String, String> item = state.get(seq);
            if (item == null) {
                invalid.add(seq + "(不存在)");
            } else if (!STATUSES.contains(normalized)) {
                invalid.add(seq + "(" + value + ")");
            } else {
                item.put(STATUS, normalized);
            }
        });
        if (!invalid.isEmpty()) {
            return "以下序号未更新，状态只能是 done、doing、not_begin：" + String.join("、", invalid);
        }
        if (state.values().stream().allMatch(item -> DONE.equals(item.get(STATUS)))) {
            // 全部完成即清除计划，避免旧计划干扰后续轮次
            context.remove(AgentContext.PLAN);
            return "全部计划已完成，计划已清除";
        }
        context.put(AgentContext.PLAN, state);
        return render(state);
    }

    @Tool(name = "plan_list", description = "展示当前的完整计划与状态")
    public String planList(AgentContext context) {
        Map<String, Map<String, String>> state = state(context);
        return state.isEmpty() ? "当前没有计划" : render(state);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> state(AgentContext context) {
        Map<String, Map<String, String>> state = new LinkedHashMap<>();
        Object raw = context.get(AgentContext.PLAN);
        if (!(raw instanceof Map<?, ?> map)) {
            return state;
        }
        map.forEach((seq, value) -> {
            if (value instanceof Map<?, ?> item) {
                Map<String, String> copy = new LinkedHashMap<>();
                item.forEach((key, text) -> copy.put(String.valueOf(key), String.valueOf(text)));
                state.put(String.valueOf(seq), copy);
            }
        });
        return state;
    }

    private static String render(Map<String, Map<String, String>> state) {
        StringBuilder text = new StringBuilder("当前计划：");
        for (String seq : sortedKeys(state)) {
            Map<String, String> item = state.get(seq);
            text.append('\n')
                    .append(seq)
                    .append(". [")
                    .append(item.getOrDefault(STATUS, NOT_BEGIN))
                    .append("] ")
                    .append(item.getOrDefault(CONTENT, ""));
        }
        return text.toString();
    }

    /**
     * 序号全为数字时按数值排序，否则按字典序。
     */
    private static List<String> sortedKeys(Map<String, ?> state) {
        List<String> keys = new ArrayList<>(state.keySet());
        boolean numeric = keys.stream().allMatch(key -> key.chars().allMatch(Character::isDigit));
        if (numeric) {
            keys.sort(Comparator.comparingLong(Long::parseLong));
        } else {
            keys.sort(Comparator.naturalOrder());
        }
        return keys;
    }
}
