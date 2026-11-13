package com.yf.wayfarer.hitl;

import com.yf.wayfarer.loop.AgentContext;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人工确认（HITL）协调器。
 *
 * @author yyf
 * @description 以工具调用 id 为键登记等待项，工具在执过程中自行调用 {@link #await} 等待决定，
 * 接口按同一个 id 写入决定；等待超时或接口未响应时返回 null，由工具自行处理
 */
public final class HitlService {

    /**
     * 待确认项。
     *
     * @author yyf
     * @description 供前端渲染审批卡片所需的上下文
     */
    public record Pending(String toolCallId, String agentName, String sessionId,
                          String toolName, String arguments, Instant createdAt) {
    }

    private static final String DEFAULT_AGENT = "default";

    private final Map<String, Sinks.One<Map<String, Object>>> waiting = new ConcurrentHashMap<>();
    private final Map<String, Pending> pendings = new ConcurrentHashMap<>();
    private final long timeoutSeconds;

    public HitlService(long timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("超时时间必须大于 0");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 工具在执行过程中调用：登记 Hook 并在上下文写入 HITL+toolCallId 的等待值，然后等待决定。
     *
     * @return 接口写入的决定，超时未响应时返回 null
     */
    public Map<String, Object> await(AgentContext context) {
        String toolCallId = context.toolCallId();
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalStateException("当前没有正在执行的工具调用");
        }
        String agentName = context.get(AgentContext.AGENT_NAME, String.class);
        if (agentName == null || agentName.isBlank()) {
            agentName = DEFAULT_AGENT;
        }
        try {
            return wait(agentName, context, toolCallId, context.toolName()).block();
        } catch (RuntimeException e) {
            // 超时或接口异常：由工具自行判定如何处理
            return null;
        }
    }

    /**
     * 按工具调用 id 写入决定，整个请求体作为决定交给工具。
     *
     * @return 是否存在等待项并被写入
     */
    public boolean complete(String toolCallId, Map<String, Object> payload) {
        requireText(toolCallId, "toolCallId");
        Sinks.One<Map<String, Object>> sink = waiting.get(toolCallId);
        if (sink == null) {
            return false;
        }
        return sink.tryEmitValue(payload == null ? Map.of() : payload).isSuccess();
    }

    /**
     * 待确认列表，agentName 为空时返回全部。
     */
    public List<Pending> pending(String agentName) {
        return pendings.values().stream()
                .filter(item -> agentName == null || agentName.isBlank()
                        || agentName.equals(item.agentName()))
                .sorted(Comparator.comparing(Pending::createdAt))
                .toList();
    }

    /**
     * 上下文中的确认键，约定为 HITL+toolCallId。
     */
    public static String key(String toolCallId) {
        return "HITL" + toolCallId;
    }

    private Mono<Map<String, Object>> wait(String agentName, AgentContext context,
                                           String toolCallId, String toolName) {
        Sinks.One<Map<String, Object>> sink = Sinks.one();
        if (waiting.putIfAbsent(toolCallId, sink) != null) {
            throw new IllegalStateException("重复的工具调用 id：" + toolCallId);
        }
        pendings.put(toolCallId, new Pending(toolCallId, agentName, context.sessionId(),
                toolName, "", Instant.now()));
        context.put(key(toolCallId), sink.asMono());
        // agent 被终止时让等待立即结束，释放被工具占用的线程
        context.onCancel(() -> sink.tryEmitError(new CancellationException("agent 已终止")));

        return sink.asMono()
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .doFinally(signal -> {
                    waiting.remove(toolCallId);
                    pendings.remove(toolCallId);
                });
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
    }
}
