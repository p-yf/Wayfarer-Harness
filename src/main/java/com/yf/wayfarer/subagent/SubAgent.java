package com.yf.wayfarer.subagent;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.Loop;
import com.yf.wayfarer.loop.LoopChain;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.MessageOrigin;
import com.yf.wayfarer.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 子 Agent。
 *
 * @author yyf
 * @description 复用主循环、模型链与工具链，但不承担 root 专属职责：不恢复会话、不写 CUSTOM/COMPACT/TRACE_END、
 * 不挂变更汇总与轮次封装；流式消息包一层归属信息转发给父 sink，结束后写一条带 origin 的终态 EXTRADATA
 */
public final class SubAgent extends Loop {

    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);

    /**
     * 取消后等待循环线程退出的上限。
     */
    private static final long STOP_WAIT_SECONDS = 10;

    private final AgentContext context;
    private final String agentId;
    private final String viaToolCallId;
    private final Consumer<AgentMessage> parentSink;
    private final long timeoutSeconds;

    private volatile Disposable running;

    public SubAgent(AgentContext context, String agentId, String viaToolCallId,
                    LoopChain modelChain, LoopChain toolChain, Integer maxTurns,
                    Consumer<AgentMessage> parentSink, long timeoutSeconds) {
        super(modelChain, toolChain, maxTurns);
        this.context = context;
        this.agentId = agentId;
        this.viaToolCallId = viaToolCallId;
        this.parentSink = parentSink;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 同步执行到结束，返回终态结果。超时或取消会先封闭再释放订阅。
     */
    public SubAgentResult runSub() {
        long start = System.nanoTime();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        running = run(context)
                .doOnNext(this::forward)
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
            cancel();
        }
        // 结束/取消信号发出不等于循环线程已退出，它可能还在收尾当前工具；
        // 等它真正停下再写终态，父级收尾时才能把这段期间的写文件一并记录
        awaitStopped(STOP_WAIT_SECONDS);

        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        SubAgentResult result;
        if (!finished) {
            result = context.isCancelled()
                    ? SubAgentResult.cancelled(agentId, elapsed)
                    : SubAgentResult.timeout(agentId, elapsed);
        } else if (failure.get() != null) {
            Throwable error = failure.get();
            result = SubAgentResult.failed(agentId,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), elapsed);
        } else if (context.isCancelled()) {
            result = SubAgentResult.cancelled(agentId, elapsed);
        } else {
            result = SubAgentResult.completed(agentId, finalAnswer(context.messages()), elapsed);
        }

        writeTerminal(result);
        log.info("子Agent结束 agentId={} status={} 耗时={}ms", agentId, result.status(), elapsed);
        return result;
    }

    /**
     * 取消执行：先置取消标记让链路收尾，再释放订阅。
     */
    public void cancel() {
        context.put(AgentContext.CANCELLED, true);
        context.fireCancel();
        Disposable current = running;
        if (current != null) {
            current.dispose();
        }
    }

    public String agentId() {
        return agentId;
    }

    private void forward(AgentMessage message) {
        if (parentSink == null) {
            return;
        }
        try {
            parentSink.accept(new AgentMessage.Child(context.traceId(), agentId, viaToolCallId, message));
        } catch (RuntimeException e) {
            log.debug("子Agent消息转发失败 agentId={}", agentId, e);
        }
    }

    /**
     * 最终回答：最后一条无工具调用的非空 assistant 文本；若都带工具调用，退回最后一条有内容的文本。
     */
    private static String finalAnswer(List<ChatMessage> messages) {
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

    /**
     * 终态消息，回放时子卡片据此显示完成而不是永远运行中。
     */
    private void writeTerminal(SubAgentResult result) {
        Session session = context.session();
        String sessionId = context.sessionId();
        String traceId = context.traceId();
        if (session == null || sessionId == null || traceId == null) {
            return;
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("kind", "subagent");
        content.put("id", agentId);
        content.put("agent", context.get(AgentContext.AGENT_NAME, String.class));
        content.put("status", result.status());
        content.put("elapsedMs", result.elapsedMs());
        content.put("resultPreview", preview(result.result()));
        MessageOrigin origin = new MessageOrigin(agentId, viaToolCallId, context.depth());
        try {
            session.append(sessionId, JSONMsgType.EXTRADATA, traceId, content, origin);
        } catch (RuntimeException e) {
            log.warn("写入子Agent终态失败 agentId={}", agentId, e);
        }
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
