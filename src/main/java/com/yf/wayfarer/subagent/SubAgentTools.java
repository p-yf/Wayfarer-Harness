package com.yf.wayfarer.subagent;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.toolcall.Tool;
import com.yf.wayfarer.toolcall.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 子 Agent 工具集。
 *
 * @author yyf
 * @description 派发与取结果是两个互相独立的工具：`subagent` 只负责启动子任务，
 * `subagent_result` 只负责按 uuid 读取结果；两者都不接受 name 参数，目标名称由根 Agent 派生
 */
public final class SubAgentTools {

    private static final Logger log = LoggerFactory.getLogger(SubAgentTools.class);

    /**
     * 派发被拒时返回给模型的固定文案，配额处理器与工具内二次校验共用。
     */
    public static final String QUOTA_FULL = "<sys-当前子Agent数量已满禁止继续派发>";

    private final SubAgentManager manager;
    private final SubAgentFactory factory;
    private final int maxDepth;

    public SubAgentTools(SubAgentManager manager, SubAgentFactory factory, int maxDepth) {
        this.manager = manager;
        this.factory = factory;
        this.maxDepth = maxDepth;
    }

    /**
     * 派发子 Agent。
     */
    @Tool(name = "subagent", description = "派发一个子Agent执行指定任务。async=true 时立即返回 uuid，"
            + "之后用 subagent_result 按 uuid 取结果；async=false 时阻塞直到子Agent结束并直接返回结果。")
    public String dispatch(AgentContext agentContext,
                           @ToolParam(description = "子Agent要执行的任务") String task,
                           @ToolParam(description = "是否继承父对话历史，默认 true", required = false) Boolean historyextend,
                           @ToolParam(description = "额外上下文或约束，拼在任务之后", required = false) String context,
                           @ToolParam(description = "是否异步派发，默认 false", required = false) Boolean async) {
        if (task == null || task.isBlank()) {
            return "<sys-子Agent任务不能为空>";
        }
        if (agentContext.depth() >= maxDepth) {
            return "<sys-子Agent派生深度已达上限：" + maxDepth + ">";
        }
        String sessionId = agentContext.sessionId();
        String toolCallId = agentContext.toolCallId();
        String rootName = agentContext.rootAgentName();
        String agentName = (rootName == null || rootName.isBlank() ? "wayfarer" : rootName) + ":subagent";
        boolean extend = historyextend == null || historyextend;
        boolean asyncMode = Boolean.TRUE.equals(async);

        SubAgentManager.Submission submission = manager.submit(sessionId, toolCallId, agentName, (uuid, agentId) -> {
            SubAgent child = factory.create(agentContext, agentId, toolCallId, task, extend, context);
            // 先登记取消钩子再运行，root 取消时能级联终止
            manager.onCancel(sessionId, child::cancel);
            return child.runSub();
        });
        if (submission == null) {
            log.warn("子Agent配额已满 sessionId={} toolCallId={}", sessionId, toolCallId);
            return QUOTA_FULL;
        }
        if (asyncMode) {
            return "{\"uuid\":\"" + submission.uuid() + "\",\"status\":\"running\"}";
        }
        // 同步等待期间 root 被终止时先停掉子Agent：否则这里的等待会卡到子任务超时，
        // 主循环迟迟收不了尾，会话一直处于运行中，后续请求都会被准入拒绝
        agentContext.onCancel(() -> manager.cancelSession(sessionId));
        // 取消可能恰好发生在登记钩子之前，这里补一次判断避免漏掉终止信号
        if (agentContext.isCancelled()) {
            manager.cancelSession(sessionId);
        }
        SubAgentResult result = manager.await(submission.uuid());
        return result == null ? "<sys-子Agent结果不可用>" : render(result);
    }

    /**
     * 读取子 Agent 结果。
     */
    @Tool(name = "subagent_result", description = "按 uuid 读取子Agent的返回值。wait=true 时阻塞到子Agent结束；"
            + "结果读取成功后会被删除，无法再次读取。")
    public String result(AgentContext agentContext,
                         @ToolParam(description = "派发时返回的子任务 uuid") String uuid,
                         @ToolParam(description = "是否阻塞等待，默认 false", required = false) Boolean wait) {
        if (uuid == null || uuid.isBlank()) {
            return "<sys-uuid 不能为空>";
        }
        // 阻塞读取期间 root 被终止时同样先停掉子Agent，避免主循环卡在这里收不了尾
        if (Boolean.TRUE.equals(wait)) {
            agentContext.onCancel(() -> manager.cancelSession(agentContext.sessionId()));
            if (agentContext.isCancelled()) {
                manager.cancelSession(agentContext.sessionId());
            }
        }
        SubAgentManager.Query query = manager.read(uuid.trim(), Boolean.TRUE.equals(wait));
        if (query.missing()) {
            return "<sys-未找到子Agent结果：" + uuid + ">";
        }
        if (query.running()) {
            return "{\"uuid\":\"" + query.uuid() + "\",\"status\":\"running\"}"
                    + "\n<sys-子Agent仍在运行，可稍后再次查询或使用 wait=true 阻塞等待>";
        }
        String text = query.result() == null ? "" : query.result();
        if (!"completed".equals(query.status())) {
            text = "<sys-子Agent结束 status=" + query.status() + ">\n" + text;
        }
        return text + "\n<sys-已经读取到子Agent返回值，此值已被删除，后续无法继续读取>";
    }

    /**
     * 同步派发的返回值：完成态直接给出结论，其它状态附上原因。
     */
    private static String render(SubAgentResult result) {
        if ("completed".equals(result.status())) {
            return result.result() == null || result.result().isBlank()
                    ? "<sys-子Agent没有返回内容>"
                    : result.result();
        }
        return "<sys-子Agent结束 status=" + result.status() + ">\n"
                + (result.result() == null ? "" : result.result());
    }
}
