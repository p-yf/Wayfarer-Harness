package com.yf.wayfarer.agent;

import com.yf.wayfarer.subagent.SubAgentTools;

import java.util.ArrayList;
import java.util.List;

/**
 * 增强版 wayfarer agent 蓝图。
 *
 * @author yyf
 * @description 完整继承 wayfarer 的全部工具与能力，只额外暴露子 Agent 派发与取结果两个工具
 */
public class WayfarerProBlueprint extends WayfarerBlueprint {

    public static final String NAME = "wayfarerpro";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Object> toolObjects(AgentDependencies deps) {
        List<Object> tools = new ArrayList<>(super.toolObjects(deps));
        tools.add(new SubAgentTools(deps.subAgentManager(), deps.subAgentFactory(), deps.maxDepth()));
        return tools;
    }
}
