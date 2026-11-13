package com.yf.wayfarer.loop;

import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CountDownLatch;
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
            // 当前步骤的订阅，取消时立即释放，避免模型连接与工具线程被继续占用
            AtomicReference<Disposable> step = new AtomicReference<>();
            sink.onCancel(() -> {
                Disposable running = step.getAndSet(null);
                if (running != null) {
                    running.dispose();
                }
                // 通知工具侧正在等待的确认，使其立即结束
                context.fireCancel();
            });
            try {
                int turn = 0;
                while (!sink.isCancelled() && (maxTurns == null || turn < maxTurns)) {
                    turn++;
                    // 每一轮都把轮次序号写入上下文，供链路与工具读取
                    context.put(AgentContext.TURN_INDEX, turn);
                    // 模型链负责把流式片段推入汇聚口，并把完整响应写入上下文
                    if (!runStep(sink, step, modelChain.run(context))) {
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
                        if (sink.isCancelled()) {
                            break;
                        }
                        sink.next(AgentMsg.toolCall(info));
                    }
                    if (sink.isCancelled()) {
                        break;
                    }
                    // 工具链负责执行工具并把结果推入汇聚口
                    if (!runStep(sink, step, toolChain.run(context))) {
                        break;
                    }
                }
                sink.complete();
            } catch (RuntimeException e) {
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 同步执行一个链路步骤，并登记其订阅以便取消时立即释放。
     *
     * @return 步骤正常结束且未被取消时返回 true
     */
    private static boolean runStep(FluxSink<AgentMsg> sink, AtomicReference<Disposable> step, Mono<Void> chain) {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Disposable subscription = chain
                .doFinally(ignored -> finished.countDown())
                .subscribe(ignored -> {
                }, failure::set);
        step.set(subscription);
        if (sink.isCancelled()) {
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
        return !sink.isCancelled();
    }
}
