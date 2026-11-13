package com.yf.wayfarer.loop;

import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * agent 主循环。
 *
 * @author yyf
 * @description while 循环交替执行模型链与工具链；链路经上下文汇聚口推送消息，
 * 取消订阅后立即释放当前步骤的订阅并通知上下文取消回调，随后终止
 */
public class Loop {

    private static final Logger log = LoggerFactory.getLogger(Loop.class);

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
        // 当前步骤的订阅，取消时立即释放，避免模型连接与工具线程被继续占用
        AtomicReference<Disposable> step = new AtomicReference<>();

        return output.asFlux()
                .doOnSubscribe(subscription -> Mono.fromRunnable(() -> loop(context, output, cancelled, step))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(ignored -> {
                        }, output::tryEmitError))
                .doOnCancel(() -> {
                    cancelled.set(true);
                    Disposable running = step.getAndSet(null);
                    if (running != null) {
                        running.dispose();
                    }
                    // 通知工具侧正在等待的确认，使其立即结束
                    context.fireCancel();
                });
    }

    private void loop(AgentContext context, Sinks.Many<AgentMessage> output,
                      AtomicBoolean cancelled, AtomicReference<Disposable> step) {
        try {
            int turn = 0;
            while (!cancelled.get() && (maxTurns == null || turn < maxTurns)) {
                turn++;
                // 每一轮都把轮次序号写入上下文，供链路与工具读取
                context.put(AgentContext.TURN_INDEX, turn);
                log.info("轮次开始 turn={} traceId={} 消息数={}", turn, context.traceId(),
                        context.messages() == null ? 0 : context.messages().size());
                // 模型链负责把流式片段推入汇聚口，并把完整响应写入上下文
                if (!runStep(cancelled, step, modelChain.run(context))) {
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
                if (!runStep(cancelled, step, toolChain.run(context))) {
                    break;
                }
            }
            output.tryEmitComplete();
        } catch (RuntimeException e) {
            log.error("运行异常 traceId={}", context.traceId(), e);
            output.tryEmitError(e);
        }
    }

    /**
     * 同步执行一个链路步骤，并登记其订阅以便取消时立即释放。
     *
     * @return 步骤正常结束且未被取消时返回 true
     */
    private static boolean runStep(AtomicBoolean cancelled, AtomicReference<Disposable> step, Mono<Void> chain) {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Disposable subscription = chain
                .doFinally(ignored -> finished.countDown())
                .subscribe(ignored -> {
                }, failure::set);
        step.set(subscription);
        if (cancelled.get()) {
            // 订阅建立前已被取消：立即释放
            subscription.dispose();
        }
        try {
            finished.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            subscription.dispose();
        } finally {
            step.compareAndSet(subscription, null);
        }
        Throwable error = failure.get();
        if (error != null) {
            throw error instanceof RuntimeException runtime ? runtime : new IllegalStateException(error);
        }
        return !cancelled.get();
    }
}
