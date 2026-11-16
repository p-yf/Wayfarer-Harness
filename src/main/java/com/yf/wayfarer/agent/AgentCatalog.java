package com.yf.wayfarer.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * agent 蓝图目录。
 *
 * @author yyf
 * @description 按名称查找蓝图；名称统一小写比较，带冒号的派生名由内部生成，外部传入一律拒绝
 */
public final class AgentCatalog {

    private final Map<String, AgentBlueprint> blueprints = new LinkedHashMap<>();

    public AgentCatalog(List<AgentBlueprint> blueprints) {
        for (AgentBlueprint blueprint : blueprints) {
            this.blueprints.put(blueprint.name().toLowerCase(Locale.ROOT), blueprint);
        }
    }

    /**
     * 按名称取蓝图，未知名称或派生名返回 null。
     */
    public AgentBlueprint find(String name) {
        if (name == null || name.isBlank() || name.contains(AgentBlueprint.SUBAGENT_SUFFIX)) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (key.contains(":") || key.contains("：")) {
            return null;
        }
        return blueprints.get(key);
    }

    public Set<String> names() {
        return blueprints.keySet();
    }

    public boolean supports(String name) {
        return find(name) != null;
    }
}
