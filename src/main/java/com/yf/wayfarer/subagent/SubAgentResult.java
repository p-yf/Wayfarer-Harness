package com.yf.wayfarer.subagent;

/**
 * 子 Agent 的终态结果。
 *
 * @author yyf
 * @description status 取值 completed/cancelled/failed/timeout；result 为最终回答，失败时为原因；
 * elapsedMs 为执行耗时，供日志与页面终态展示
 */
public record SubAgentResult(String agentId, String status, String result, long elapsedMs) {

    public static SubAgentResult completed(String agentId, String result, long elapsedMs) {
        return new SubAgentResult(agentId, "completed", result, elapsedMs);
    }

    public static SubAgentResult failed(String agentId, String reason, long elapsedMs) {
        return new SubAgentResult(agentId, "failed", reason, elapsedMs);
    }

    public static SubAgentResult cancelled(String agentId, long elapsedMs) {
        return new SubAgentResult(agentId, "cancelled", "子Agent已取消", elapsedMs);
    }

    public static SubAgentResult timeout(String agentId, long elapsedMs) {
        return new SubAgentResult(agentId, "timeout", "子Agent执行超时", elapsedMs);
    }

    public boolean finished() {
        return !"running".equals(status);
    }
}
