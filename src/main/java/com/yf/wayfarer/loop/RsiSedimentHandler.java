package com.yf.wayfarer.loop;

import com.yf.wayfarer.model.ChatModel;
import com.yf.wayfarer.rsi.RsiProperties;
import com.yf.wayfarer.rsi.RsiSedimentPipeline;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.SessionMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * RSI 沉淀总入口（round-level 记账语义，仅装配在
 * RsiBlueprint 的 runChain）。
 *
 * <p>sort = -5（runChain 最小值）：前置最先放行（不拦截任何东西），<b>后置最后执行</b>——
 * 此时 TRACE_END 已封存、会话历史已落盘，能读到完整 trace 数据。</p>
 *
 * <p>后置逻辑只做记账，不做任何「够不够格」的自造判断（条件门已删除）：</p>
 * <ol>
 *   <li>统计本次 run 后 root 对话消息总量，连同 sessionId/traceId/模型引用交给
 *       {@link RsiSedimentPipeline#onRunFinished}——L1 阈值、idle 兜底由触发器判定；</li>
 *   <li>判定本 run 是否为「最终回复轮」（含 assistant 回复即算），skill 累计轮数
 *       由 pipeline 记账，达阈值才萃取（只有最终回复轮才推送，中间工具轮不计数）。</li>
 * </ol>
 *
 * @author yyf
 * @description 统计 root 对话消息总量 + 最终回复轮判定 → onRunFinished 记账；
 * 总开关关闭或统计异常都静默跳过（故障隔离）
 */
public class RsiSedimentHandler implements ChainHandler {

    private static final Logger log = LoggerFactory.getLogger(RsiSedimentHandler.class);

    private final RsiProperties properties;
    private final RsiSedimentPipeline pipeline;

    /**
     * 本次装配使用的模型：蓝图在 build(deps) 时从 deps.model() 捕获传入——
     * 模型引用随 AgentDependencies 走，不在 AgentContext 上，故由构造器带入。
     */
    private final ChatModel model;

    public RsiSedimentHandler(RsiProperties properties, RsiSedimentPipeline pipeline, ChatModel model) {
        this.properties = properties;
        this.pipeline = pipeline;
        this.model = model;
    }

    @Override
    public int sort() {
        // runChain 中最小：前置最外层放行，后置在所有 handler（含 TRACE_END 封存）之后执行
        return -5;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        next.run();
        try {
            notifyPipeline(context);
        } catch (RuntimeException e) {
            // 故障隔离：沉淀入口的任何异常只 warn，不影响会话收尾
            log.warn("RSI 沉淀入口异常 sessionId={}", context.sessionId(), e);
        }
    }

    /**
     * 统计本次 run → 交给流水线记账（L1 阈值 / skill 轮次 / idle 兜底都在那边判定）。
     */
    private void notifyPipeline(AgentContext context) {
        if (!properties.enabled()) {
            return;
        }
        String sessionId = context.sessionId();
        String traceId = context.traceId();
        String agent = context.rootAgentName() == null ? context.get(AgentContext.AGENT_NAME, String.class)
                : context.rootAgentName();
        if (sessionId == null || traceId == null || agent == null) {
            return;
        }
        List<SessionMessage> all = context.session().messages(sessionId);
        Stats stats = statsOf(all, traceId);
        log.info("RSI run 记账 agent={} session={} traceId={} conversationSize={} finalReply={}",
                agent, sessionId, traceId, stats.conversationSize(), stats.finalReply());
        // 快照后异步：线程池里不碰 AgentContext，只带 id 与模型引用
        pipeline.onRunFinished(agent, sessionId, traceId, model);
    }

    /**
     * 从会话消息统计本次 run 的沉淀信号（只统计 root 消息，子 Agent 过程不计入）。
     *
     * @param all     会话全部消息
     * @param traceId 本次运行的 trace id
     */
    static Stats statsOf(List<SessionMessage> all, String traceId) {
        int conversationSize = 0;
        boolean finalReply = false;
        for (SessionMessage message : all) {
            if (message.origin() != null) {
                continue;
            }
            switch (message.type()) {
                case USER, ASSISTANT, TOOL -> conversationSize++;
                default -> {
                    // TRACE_END / COMPACT / CUSTOM / EXTRADATA 不参与统计
                }
            }
            if (message.type() == JSONMsgType.ASSISTANT && traceId.equals(message.traceId())) {
                // 本 run 产出了 assistant 回复 = 最终回复轮（skill 记账口径）
                finalReply = true;
            }
        }
        return new Stats(conversationSize, finalReply);
    }

    /**
     * @author yyf
     * @description run 统计快照：conversationSize=root 对话消息总量（L1 游标比对的分母）、
     * finalReply=本 trace 是否含 assistant 回复（是否计一个 skill 轮次）
     */
    record Stats(int conversationSize, boolean finalReply) {
    }
}
