package com.yf.wayfarer.subagent;

import com.yf.wayfarer.loop.AgentContext;

/**
 * 子 Agent 构建器。
 *
 * @author yyf
 * @description 由 agent 装配层实现：基于父上下文复制出独立的子上下文，并用同款模型链、工具链构建 {@link SubAgent}。
 * 子 Agent 名称固定为 `{rootAgent}:subagent`，不作为独立名称注册
 */
public interface SubAgentFactory {

    /**
     * @param parent       父上下文，用于继承会话、trace、工作区与历史快照
     * @param agentId      子 Agent 实例 id
     * @param viaToolCallId 派生它的那次工具调用 id
     * @param task         任务描述
     * @param historyExtend 是否继承父对话历史
     * @param extra        额外上下文，拼在任务之后
     */
    SubAgent create(AgentContext parent, String agentId, String viaToolCallId,
                    String task, boolean historyExtend, String extra);
}
