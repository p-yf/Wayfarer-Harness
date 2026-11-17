package com.yf.wayfarer.loop;

import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.FinishReason;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
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

        LoopChain chain = LoopChain.of(
                (ctx, next) -> {
                    trace.add("a-before");
                    next.run();
                    trace.add("a-after");
                },
                (ctx, next) -> {
                    trace.add("b-before");
                    next.run();
                    trace.add("b-after");
                },
                (ctx, next) -> ctx.put("flag", "value"));

        chain.run(context);

        assertEquals(List.of("a-before", "b-before", "b-after", "a-after"), trace);
        assertEquals("value", context.get("flag"));
    }

    @Test
    void 模型请求工具时执行工具链并继续循环() {
        List<String> trace = new ArrayList<>();
        AtomicInteger modelRuns = new AtomicInteger();
        AtomicInteger toolRuns = new AtomicInteger();
        AgentContext context = new AgentContext();
        context.put(AgentContext.TRACE_ID, "trace-1");
        ToolCallInfo call = new ToolCallInfo("call_1", "getWeather", "{\"city\":\"北京\"}");

        LoopChain modelChain = LoopChain.of(
                (ctx, next) -> {
                    trace.add("model-before");
                    next.run();
                    trace.add("model-after");
                },
                (ctx, next) -> {
                    if (modelRuns.incrementAndGet() == 1) {
                        ctx.agentMsgSink().accept(new AgentMessage.Text("trace-1", "查天气"));
                        ctx.put(AgentContext.MODEL_RESPONSE, responseWithToolCall());
                    } else {
                        ctx.agentMsgSink().accept(new AgentMessage.Text("trace-1", "北京晴"));
                        ctx.put(AgentContext.MODEL_RESPONSE, responseWithoutToolCall());
                    }
                });

        LoopChain toolChain = LoopChain.of(
                (ctx, next) -> {
                    trace.add("tool-before");
                    next.run();
                    trace.add("tool-after");
                },
                (ctx, next) -> {
                    toolRuns.incrementAndGet();
                    ctx.agentMsgSink().accept(new AgentMessage.ToolResult("trace-1", "call_1", "晴，25度"));
                });

        StepVerifier.create(new Loop(modelChain, toolChain).run(context))
                .expectNext(new AgentMessage.Text("trace-1", "查天气"))
                .expectNext(new AgentMessage.ToolCall("trace-1", call))
                .expectNext(new AgentMessage.ToolResult("trace-1", "call_1", "晴，25度"))
                .expectNext(new AgentMessage.Text("trace-1", "北京晴"))
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
        LoopChain modelChain = LoopChain.of((ctx, next) ->
                ctx.put(AgentContext.MODEL_RESPONSE, responseWithoutToolCall()));
        LoopChain toolChain = LoopChain.of((ctx, next) -> toolRuns.incrementAndGet());

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

        LoopChain modelChain = LoopChain.of((ctx, next) -> {
            if (modelRuns.incrementAndGet() >= 2) {
                secondModel.countDown();
            }
            ctx.put(AgentContext.MODEL_RESPONSE, responseWithToolCall());
        });
        LoopChain toolChain = LoopChain.of((ctx, next) -> {
            toolRuns.incrementAndGet();
            toolStarted.countDown();
            try {
                resume.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });

        Disposable disposable = new Loop(modelChain, toolChain).run(new AgentContext()).subscribe();
        assertTrue(toolStarted.await(5, TimeUnit.SECONDS));
        disposable.dispose();
        resume.countDown();

        assertFalse(secondModel.await(300, TimeUnit.MILLISECONDS), "取消后不应启动新一轮模型调用");
        assertEquals(1, modelRuns.get());
        assertEquals(1, toolRuns.get());
    }

    @Test
    void 取消时运行级收尾仍执行() throws Exception {
        AtomicInteger modelRuns = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);

        LoopChain modelChain = LoopChain.of((ctx, next) -> {
            modelRuns.incrementAndGet();
            ctx.put(AgentContext.MODEL_RESPONSE, responseWithToolCall());
        });
        LoopChain toolChain = LoopChain.of((ctx, next) -> {
            toolStarted.countDown();
            try {
                resume.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });
        // 运行级链的后置逻辑写在 next 之后，取消后主循环 break、方法照常返回，后置仍会执行
        Loop loop = new Loop(modelChain, toolChain) {
            @Override
            protected void execute(AgentContext context, Runnable core) {
                try {
                    core.run();
                } finally {
                    finished.incrementAndGet();
                }
            }
        };

        Disposable disposable = loop.run(new AgentContext()).subscribe();
        assertTrue(toolStarted.await(5, TimeUnit.SECONDS));
        disposable.dispose();
        resume.countDown();

        long deadline = System.currentTimeMillis() + 5000;
        while (finished.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, finished.get(), "取消后运行级收尾应执行一次");
        assertEquals(1, modelRuns.get());
    }

    @Test
    void 等待工作线程真正退出后才返回() throws Exception {
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();

        LoopChain modelChain = LoopChain.of((ctx, next) ->
                ctx.put(AgentContext.MODEL_RESPONSE, responseWithToolCall()));
        LoopChain toolChain = LoopChain.of((ctx, next) -> {
            toolStarted.countDown();
            try {
                resume.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        Loop loop = new Loop(modelChain, toolChain) {
            @Override
            protected void execute(AgentContext context, Runnable core) {
                try {
                    core.run();
                } finally {
                    finished.incrementAndGet();
                }
            }
        };

        Disposable disposable = loop.run(new AgentContext()).subscribe();
        assertTrue(toolStarted.await(5, TimeUnit.SECONDS));
        disposable.dispose();

        // 取消只打断阻塞点，当前工具还在跑，此时不能报告线程已退出
        assertFalse(loop.awaitStopped(1), "工具未收尾前不应判定为已退出");

        resume.countDown();
        assertTrue(loop.awaitStopped(5), "工具收尾后应等到线程退出");
        assertEquals(1, finished.get());
    }

    @Test
    void 运行异常时运行级收尾仍执行() {
        AtomicInteger finished = new AtomicInteger();
        LoopChain modelChain = LoopChain.of((ctx, next) -> {
            throw new IllegalStateException("模型调用失败");
        });
        Loop loop = new Loop(modelChain, LoopChain.of((ctx, next) -> {
        })) {
            @Override
            protected void execute(AgentContext context, Runnable core) {
                try {
                    core.run();
                } finally {
                    finished.incrementAndGet();
                }
            }
        };

        StepVerifier.create(loop.run(new AgentContext()))
                .expectError(IllegalStateException.class)
                .verify();

        assertEquals(1, finished.get(), "异常终止也应执行运行级收尾");
    }

    @Test
    void 下游分批请求时消息仍逐步送出() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch emitted = new CountDownLatch(1);

        LoopChain modelChain = LoopChain.of((ctx, next) -> {
            ctx.agentMsgSink().accept(new AgentMessage.Text("t", "第一段"));
            ctx.agentMsgSink().accept(new AgentMessage.Text("t", "第二段"));
            emitted.countDown();
            // 步骤尚未结束：此时第二段就应该已经送到下游
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ctx.put(AgentContext.MODEL_RESPONSE, responseWithoutToolCall());
        });

        BlockingQueue<AgentMessage> received = new LinkedBlockingQueue<>();
        Disposable disposable = new Loop(modelChain, LoopChain.of((ctx, next) -> {
        }))
                .run(new AgentContext())
                // 一次只请求一个，模拟 sse 写出这类分批请求的下游
                .limitRate(1)
                .subscribe(received::add);

        try {
            assertEquals(new AgentMessage.Text("t", "第一段"), received.poll(5, TimeUnit.SECONDS));
            assertTrue(emitted.await(5, TimeUnit.SECONDS));
            assertEquals(new AgentMessage.Text("t", "第二段"), received.poll(5, TimeUnit.SECONDS),
                    "步骤未结束时第二段也应送出，不能憋到流结束");
        } finally {
            release.countDown();
            disposable.dispose();
        }
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
            public void run(AgentContext ctx, Runnable next) {
                trace.add("late");
                next.run();
            }
        };
        ChainHandler early = new ChainHandler() {
            @Override
            public int sort() {
                return -100;
            }

            @Override
            public void run(AgentContext ctx, Runnable next) {
                trace.add("early");
                next.run();
            }
        };

        LoopChain.of(late, early).run(context);

        assertEquals(List.of("early", "late"), trace);
    }

    @Test
    void 超过最大轮次后停止循环() {
        AtomicInteger modelRuns = new AtomicInteger();
        AtomicInteger toolRuns = new AtomicInteger();
        LoopChain modelChain = LoopChain.of((ctx, next) -> {
            modelRuns.incrementAndGet();
            ctx.put(AgentContext.MODEL_RESPONSE, responseWithToolCall());
        });
        LoopChain toolChain = LoopChain.of((ctx, next) -> toolRuns.incrementAndGet());

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
