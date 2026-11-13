package com.yf.wayfarer.loop;

import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.function.Consumer;

/**
 * agent 主循环。
 *
 * @author yyf
 * @description while 循环交替执行模型链与工具链；链路经上下文汇聚口推送消息，取消订阅后当前步骤结束即终止
 */
public class Loop {

    private final Chain modelChain;
    private final Chain toolChain;
    private final Integer maxTurns;

    public Loop(Chain modelChain, Chain toolChain) {
        this(modelChain, toolChain, null);
    }

    /**
     * @param maxTurns 最大轮次（每轮一次模型调用），null 表示不限制
     */
    public Loop(Chain modelChain, Chain toolChain, Integer maxTurns) {
        this.modelChain = modelChain;
        this.toolChain = toolChain;
        this.maxTurns = maxTurns;
    }

    public Flux<AgentMsg> run(AgentContext context) {
        return Flux.<AgentMsg>create(sink -> {
            Consumer<AgentMsg> agentMsgSink = sink::next;
            context.put(AgentContext.AGENT_MSG_SINK, agentMsgSink);
            try {
                int turn = 0;
                while (!sink.isCancelled() && (maxTurns == null || turn < maxTurns)) {
                    turn++;
                    // 模型链负责把流式片段推入汇聚口，并把完整响应写入上下文
                    modelChain.run(context).block();
                    if (sink.isCancelled()) {
                        break;
                    }
                    ModelResponse response = context.modelResponse();
                    if (response == null || response.toolCalls().isEmpty()) {
                        break;
                    }
                    // 轮次已用尽：不再推送工具调用消息，也不执行工具链
                    if (maxTurns != null && turn >= maxTurns) {
                        break;
                    }
                    for (ToolCallInfo info : response.toolCalls()) {
                        sink.next(AgentMsg.toolCall(info));
                    }
                    // 工具链负责执行工具并把结果推入汇聚口
                    toolChain.run(context).block();
                }
                sink.complete();
            } catch (RuntimeException e) {
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
