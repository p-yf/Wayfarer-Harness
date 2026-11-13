package com.yf.wayfarer.agent.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.model.Usage;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 单轮调用记录处理器。
 *
 * @author yyf
 * @description 环绕模型调用，记录本轮耗时与用量，写入会话的 EXTRADATA 消息并推送 trace_data，
 * 使刷新页面后仍能看到每一次 trace 的 token 与耗时
 */
public class TraceRecordHandler implements ChainHandler {

    private static final Logger log = LoggerFactory.getLogger(TraceRecordHandler.class);

    /**
     * 小于会话持久化处理器，使其收尾发生在助手消息落盘之后，
     * 保证文件顺序为先助手回复、再调用记录。
     */
    @Override
    public int sort() {
        return -60;
    }

    @Override
    public Mono<Void> run(AgentContext context, Mono<Void> next) {
        return Mono.defer(() -> {
            long start = System.nanoTime();
            return next.then(Mono.fromRunnable(() -> record(context, start)));
        });
    }

    private static void record(AgentContext context, long start) {
        long durationMs = Math.max(0, (System.nanoTime() - start) / 1_000_000);
        ModelResponse response = context.modelResponse();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("durationMs", durationMs);
        if (response != null) {
            content.put("finishReason", response.finishReason() == null ? null : response.finishReason().name());
            content.put("usage", usageMap(response.usage()));
        }

        Session session = context.session();
        String sessionId = context.sessionId();
        String traceId = context.traceId();
        if (session != null && sessionId != null) {
            session.append(sessionId, JSONMsgType.EXTRADATA, traceId, content);
        }

        log.info("trace 结束 traceId={} 耗时={}ms finishReason={} usage={}",
                traceId, durationMs,
                response == null ? null : response.finishReason(),
                response == null ? null : response.usage());

        Consumer<AgentMessage> sink = context.agentMsgSink();
        if (sink != null) {
            sink.accept(new AgentMessage.TraceData(traceId,
                    response == null ? null : response.finishReason(),
                    response == null ? null : response.usage(),
                    durationMs));
        }
    }

    private static Map<String, Object> usageMap(Usage usage) {
        if (usage == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("promptTokens", usage.promptTokens());
        map.put("completionTokens", usage.completionTokens());
        map.put("totalTokens", usage.totalTokens());
        map.put("promptCacheHitTokens", usage.promptCacheHitTokens());
        map.put("promptCacheMissTokens", usage.promptCacheMissTokens());
        map.put("reasoningTokens", usage.reasoningTokens());
        return map;
    }
}
