package com.yf.wayfarer.loop;

import com.yf.wayfarer.model.ChatChunk;
import com.yf.wayfarer.model.FinishReason;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author yyf
 * @description 校验环绕式责任链的执行顺序、agent 循环交替与取消终止
 */
class LoopTest {

    @Test
    void 责任链按环绕顺序执行() {
        List<String> trace = new ArrayList<>();
        AgentContext context = new AgentContext();

        Chain chain = Chain.of(
                (ctx, next) -> Mono.fromRunnable(() -> trace.add("a-before"))
                        .then(next)
                        .doOnSuccess(ignored -> trace.add("a-after")),
                (ctx, next) -> Mono.fromRunnable(() -> trace.add("b-before"))
                        .then(next)
                        .doOnSuccess(ignored -> trace.add("b-after")),
                (ctx, next) -> Mono.fromRunnable(() -> ctx.put("flag", "value")));

        StepVerifier.create(chain.run(context)).verifyComplete();

        assertEquals(List.of("a-before", "b-before", "b-after", "a-after"), trace);
        assertEquals("value", context.get("flag"));
    }

    @Test
    void 模型请求工具时执行工具链并继续循环() {
        List<String> trace = new ArrayList<>();
        AtomicInteger modelRuns = new AtomicInteger();
        AtomicInteger toolRuns = new AtomicInteger();
        AgentContext context = new AgentContext();
        ToolCallInfo call = new ToolCallInfo("call_1", "getWeather", "{\"city\":\"北京\"}");

        Chain modelChain = Chain.of(
                (ctx, next) -> Mono.fromRunnable(() -> trace.add("model-before"))
                        .then(next)
                        .doOnSuccess(ignored -> trace.add("model-after")),
                (ctx, next) -> Mono.fromRunnable(() -> {
                    if (modelRuns.incrementAndGet() == 1) {
                        ctx.agentMsgSink().accept(AgentMsg.chunk(new ChatChunk("查天气", null)));
                        ctx.put(AgentContext.MODEL_RESPONSE, responseWithToolCall());
                    } else {
                        ctx.agentMsgSink().accept(AgentMsg.chunk(new ChatChunk("北京晴", null)));
                        ctx.put(AgentContext.MODEL_RESPONSE, responseWithoutToolCall());
                    }
                }));

        Chain toolChain = Chain.of(
                (ctx, next) -> Mono.fromRunnable(() -> trace.add("tool-before"))
                        .then(next)
                        .doOnSuccess(ignored -> trace.add("tool-after")),
                (ctx, next) -> Mono.fromRunnable(() -> {
                    toolRuns.incrementAndGet();
                    ctx.agentMsgSink().accept(AgentMsg.toolResult(call, "晴，25度"));
                }));

        StepVerifier.create(new Loop(modelChain, toolChain).run(context))
                .expectNext(AgentMsg.chunk(new ChatChunk("查天气", null)))
                .expectNext(AgentMsg.toolCall(call))
                .expectNext(AgentMsg.toolResult(call, "晴，25度"))
                .expectNext(AgentMsg.chunk(new ChatChunk("北京晴", null)))
                .verifyComplete();

        assertEquals(2, modelRuns.get());
        assertEquals(1, toolRuns.get());
        assertEquals(List.of(
                "model-before", "model-after",
                "tool-before", "tool-after",
                "model-before", "model-after"), trace);
    }

    @Test
    void 模型未请求工具时直接结束() {
        AtomicInteger toolRuns = new AtomicInteger();
        Chain modelChain = Chain.of((ctx, next) ->
                Mono.fromRunnable(() -> ctx.put(AgentContext.MODEL_RESPONSE, responseWithoutToolCall())));
        Chain toolChain = Chain.of((ctx, next) -> Mono.fromRunnable(toolRuns::incrementAndGet));

        StepVerifier.create(new Loop(modelChain, toolChain).run(new AgentContext())).verifyComplete();

        assertEquals(0, toolRuns.get());
    }

    @Test
    void 取消订阅后当前步骤结束即终止循环() throws Exception {
        AtomicInteger modelRuns = new AtomicInteger();
        AtomicInteger toolRuns = new AtomicInteger();
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CountDownLatch secondModel = new CountDownLatch(1);

        Chain modelChain = Chain.of((ctx, next) -> Mono.fromRunnable(() -> {
            if (modelRuns.incrementAndGet() >= 2) {
                secondModel.countDown();
            }
            ctx.put(AgentContext.MODEL_RESPONSE, responseWithToolCall());
        }));
        Chain toolChain = Chain.of((ctx, next) -> Mono.fromRunnable(() -> {
            toolRuns.incrementAndGet();
            toolStarted.countDown();
            try {
                resume.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }));

        Disposable disposable = new Loop(modelChain, toolChain).run(new AgentContext()).subscribe();
        assertTrue(toolStarted.await(5, TimeUnit.SECONDS));
        disposable.dispose();
        resume.countDown();

        assertFalse(secondModel.await(300, TimeUnit.MILLISECONDS), "取消后不应启动新一轮模型调用");
        assertEquals(1, modelRuns.get());
        assertEquals(1, toolRuns.get());
    }

    @Test
    void 按sort升序执行处理器() {
        List<String> trace = new ArrayList<>();
        AgentContext context = new AgentContext();

        ChainHandler late = new ChainHandler() {
            @Override
            public int sort() {
                return 100;
            }

            @Override
            public Mono<Void> run(AgentContext ctx, Mono<Void> next) {
                return Mono.fromRunnable(() -> trace.add("late")).then(next);
            }
        };
        ChainHandler early = new ChainHandler() {
            @Override
            public Mono<Void> run(AgentContext ctx, Mono<Void> next) {
                return Mono.fromRunnable(() -> trace.add("early")).then(next);
            }
        };

        StepVerifier.create(Chain.of(late, early).run(context)).verifyComplete();

        assertEquals(List.of("early", "late"), trace);
    }

    @Test
    void 超过最大轮次后停止循环() {
        AtomicInteger modelRuns = new AtomicInteger();
        AtomicInteger toolRuns = new AtomicInteger();
        Chain modelChain = Chain.of((ctx, next) -> Mono.fromRunnable(() -> {
            modelRuns.incrementAndGet();
            ctx.put(AgentContext.MODEL_RESPONSE, responseWithToolCall());
        }));
        Chain toolChain = Chain.of((ctx, next) -> Mono.fromRunnable(toolRuns::incrementAndGet));

        // 第一轮推送工具调用消息；轮次用尽后第二轮不再推送也不执行工具
        StepVerifier.create(new Loop(modelChain, toolChain, 2).run(new AgentContext()))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(2, modelRuns.get());
        assertEquals(1, toolRuns.get());
    }

    private static ModelResponse responseWithToolCall() {
        return new ModelResponse("id", "deepseek-flash", 1L, "查天气", "", FinishReason.TOOL_CALLS,
                List.of(new ToolCallInfo("call_1", "getWeather", "{\"city\":\"北京\"}")), null);
    }

    private static ModelResponse responseWithoutToolCall() {
        return new ModelResponse("id", "deepseek-flash", 1L, "北京晴", "", FinishReason.STOP,
                List.of(), null);
    }
}
