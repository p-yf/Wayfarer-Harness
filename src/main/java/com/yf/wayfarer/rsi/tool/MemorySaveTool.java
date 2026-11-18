package com.yf.wayfarer.rsi.tool;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.rsi.RsiSedimentPipeline;
import com.yf.wayfarer.toolcall.Tool;
import com.yf.wayfarer.toolcall.ToolParam;

/**
 * memory_save 工具：用户显式要求记忆时的「直通车」，把信息直接写入 L1。
 *
 * <p>「轻工具 + 重管线」分工：工具只做显式落盘（类型校验、限长、规范格式、git 回溯），
 * 不做提取——对话经验的自动提取仍归沉淀管线；即使与既有记忆撞车也不拦截
 * （宁重不丢），近似条目作为提示返回，由后续管线的四决策判重兜底。</p>
 *
 * <p>只装配在 wayfarer-rsi 的 toolset。调用时机约束写进工具描述：仅限用户
 * 明确说"记住/以后都/别忘了"等显式要求，防止模型把普通对话经验都倒进来。</p>
 *
 * @author yyf
 * @description save()：校验七型类型 → 委托 pipeline.saveL1Direct（per-agent 锁内
 * 读改写 l1.jsonl + 游标记账 + git commit trailers source=tool 语义）
 */
public class MemorySaveTool {

    private final RsiSedimentPipeline pipeline;

    /** 沉淀目标 agent 固定为 wayfarer-rsi 自己（"自己只沉淀自己的"）。 */
    private final String agent;

    public MemorySaveTool(RsiSedimentPipeline pipeline, String agent) {
        this.pipeline = pipeline;
        this.agent = agent;
    }

    @Tool(name = "memory_save", description =
            "把用户明确要求记住的信息写入长期记忆库。仅在用户显式要求时使用"
                    + "（如\"记住…\"、\"以后都…\"、\"别忘了…\"）；普通对话中的经验教训由系统"
                    + "自动沉淀，不要主动调用本工具。每次保存一条原子事实。")
    public String save(AgentContext context,
                       @ToolParam(description =
                               "记忆类型：persona=用户偏好/习惯，episodic=事件，instruction=用户指令，"
                                       + "work_fact=项目事实，work_task=任务结论，work_method=方法/流程，"
                                       + "work_artifact=产物说明")
                       String type,
                       @ToolParam(description = "要记住的内容，一句原子事实，不要堆砌大段文本")
                       String content,
                       @ToolParam(required = false, description = "标签，逗号分隔，可选") String tags) {
        try {
            return pipeline.saveL1Direct(agent, type, content, tags,
                    context.sessionId(), context.traceId());
        } catch (RuntimeException e) {
            // 沉淀是纯增益能力：失败降级为错误消息，绝不打断主循环
            return "保存失败：" + e.getMessage();
        }
    }
}
