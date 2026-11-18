package com.yf.wayfarer.rsi;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.Loop;
import com.yf.wayfarer.model.ChatMessage;
import reactor.core.Disposable;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 沉淀类 agent 的同步执行与收尾工具（场景萃取 / 技能评审共用）。
 *
 * <p>沉淀 agent 都跑在沉淀线程池里、需要同步拿到结果再继续推进管线，
 * 因此统一走「订阅 → latch 等待 → 超时取消 → awaitStopped → 抛错」的执行形态；
 * 最终回答的提取口径也统一：最后一条无工具调用的非空 assistant 文本。</p>
 *
 * @author yyf
 * @description runSync()：同步执行到结束，超时先取消再等线程收尾，失败抛异常；
 * finalAnswer()：无工具调用的最后一条 assistant 文本，全部带工具调用时退回最后一条有内容的
 */
final class AgentRuns {

    private AgentRuns() {
    }

    /**
     * 同步执行到结束（仿 SubAgent.runSub）：超时先取消再等线程收尾，失败抛异常。
     */
    static void runSync(Loop loop, AgentContext context, long timeoutSeconds) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Disposable running = loop.run(context)
                .doOnError(failure::set)
                .doOnComplete(done::countDown)
                .doOnCancel(done::countDown)
                .subscribe();
        boolean finished;
        try {
            finished = done.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            context.put(AgentContext.CANCELLED, true);
            context.fireCancel();
            running.dispose();
        }
        // 结束信号不等于循环线程已退出，等它真正停下再读消息历史
        loop.awaitStopped(10);
        if (!finished) {
            throw new IllegalStateException("沉淀 agent 执行超时（" + timeoutSeconds + "s）");
        }
        Throwable error = failure.get();
        if (error != null) {
            throw error instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("沉淀 agent 执行失败", error);
        }
    }

    /**
     * 最终回答：最后一条无工具调用的非空 assistant 文本；若都带工具调用，退回最后一条有内容的文本。
     */
    static String finalAnswer(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        String fallback = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.role() != ChatMessage.Role.ASSISTANT
                    || message.content() == null || message.content().isBlank()) {
                continue;
            }
            if (message.toolCalls().isEmpty()) {
                return message.content();
            }
            if (fallback == null) {
                fallback = message.content();
            }
        }
        return fallback == null ? "" : fallback;
    }
}
