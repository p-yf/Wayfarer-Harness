package com.yf.wayfarer.agent;

/**
 * 标准 wayfarer agent 蓝图。
 *
 * @author yyf
 * @description 暴露卸载、计划与编码工具，不包含子 Agent 能力
 */
public class WayfarerBlueprint extends AgentBlueprint {

    public static final String NAME = "wayfarer";

    @Override
    public String name() {
        return NAME;
    }
}
