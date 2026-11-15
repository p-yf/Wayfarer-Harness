package com.yf.wayfarer.loop;

import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * agent 主循环。
 *
 * @author yyf
 * @description while 循环交替执行模型链与工具链；链路同步执行，经上下文汇聚口推送流式消息，
 * 取消时设置取消标记并触发上下文回调，当前步骤收尾后结束循环
 */
public class Loop {

    private static final Logger log = LoggerFactory.getLogger(Loop.class);

    private final LoopChain modelChain;
    private final LoopChain toolChain;
    private final Integer maxTurns;

    public Loop(LoopChain modelChain, LoopChain toolChain) {
        this(modelChain, toolChain, null);
    }

    /**
     * @param maxTurns 最大轮次（每轮一次模型调用），null 表示不限制
     */
    public Loop(LoopChain modelChain, LoopChain toolChain, Integer maxTurns) {
        this.modelChain = modelChain;
        this.toolChain = toolChain;
        this.maxTurns = maxTurns;
    }

    /**
     * 主循环以 Sinks 单播桥接流式消息。
     * 不用 Flux.create：生成器线程阻塞等待步骤结束、消息由模型回调线程推送、
     * 下游（如 SSE 写出）分批请求时，Flux.create 会把后续消息憋到流结束才放出，
     * 表现为整段回复一次性出现；单播 Sinks 在同样形态下能按请求逐条送出。
     */
    public Flux<AgentMessage> run(AgentContext context) {
        Sinks.Many<AgentMessage> output = Sinks.many().unicast().onBackpressureBuffer();
        context.put(AgentContext.AGENT_MSG_SINK, (Consumer<AgentMessage>) output::tryEmitNext);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Runnable core = () -> loop(context, output, cancelled);

        return output.asFlux()
                .doOnSubscribe(subscription -> Mono.fromRunnable(() -> {
                    // 运行体同步执行，终止信号在它返回后发出：这样运行级链的后置逻辑
                    // 既能在结束前把汇总消息推进汇聚口，也不会因为下游取消而丢失收尾工作
                    try {
                        execute(context, core);
                        output.tryEmitComplete();
                    } catch (RuntimeException e) {
                        log.error("运行异常 traceId={}", context.traceId(), e);
                        output.tryEmitError(e);
                    }
                }).subscribeOn(Schedulers.boundedElastic()).subscribe(ignored -> {
                }, output::tryEmitError))
                .doOnCancel(() -> {
                    cancelled.set(true);
                    // 供模型与工具判断：模型流随即释放，工具等当前这一条收尾
                    context.put(AgentContext.CANCELLED, true);
                    context.fireCancel();
                });
    }

    /**
     * 执行一次运行。默认直接跑主循环；子类可把主循环包进运行级链，
     * 因为这里是同步调用，前后逻辑在取消或异常时都能靠 try/finally 收尾。
     */
    protected void execute(AgentContext context, Runnable core) {
        core.run();
    }

    private void loop(AgentContext context, Sinks.Many<AgentMessage> output, AtomicBoolean cancelled) {
        int turn = 0;
        while (!cancelled.get() && (maxTurns == null || turn < maxTurns)) {
            turn++;
            // 每一轮都把轮次序号写入上下文，供链路与工具读取
            context.put(AgentContext.TURN_INDEX, turn);
            log.info("轮次开始 turn={} traceId={} 消息数={}", turn, context.traceId(),
                    context.messages() == null ? 0 : context.messages().size());
            // 模型链负责把流式片段推入汇聚口，并把完整响应写入上下文
            runStep(context, modelChain);
            if (cancelled.get()) {
                break;
            }
            ModelResponse response = context.modelResponse();
            if (response == null || response.toolCalls().isEmpty()) {
                log.info("轮次结束 turn={} 无工具调用", turn);
                break;
            }
            log.info("轮次结束 turn={} 工具调用数={}", turn, response.toolCalls().size());
            // 轮次已用尽：不再推送工具调用消息，也不执行工具链
            if (maxTurns != null && turn >= maxTurns) {
                break;
            }
            for (ToolCallInfo info : response.toolCalls()) {
                if (cancelled.get()) {
                    break;
                }
                output.tryEmitNext(new AgentMessage.ToolCall(context.traceId(), info));
            }
            if (cancelled.get()) {
                break;
            }
            // 工具链负责执行工具并把结果推入汇聚口
            runStep(context, toolChain);
        }
    }

    /**
     * 同步执行一个链路步骤，结束后清空本步登记的取消回调。
     */
    private static void runStep(AgentContext context, LoopChain chain) {
        context.clearCancelHooks();
        try {
            chain.run(context);
        } finally {
            context.clearCancelHooks();
        }
    }
}
