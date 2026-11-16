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
     * @description 供前端渲染审批卡片所需的上下文；metadata 决定卡片的选项与样式，
     * 形如 {"sheet1": {"key1": "允许", "key2": "拒绝", "style": "radio group"}}。
     * key 是「agentId/toolCallId」复合键，父子 Agent 的 call_0 因此互不干扰
     */
    public record Pending(String key, String agentId, String toolCallId, String agentName, String sessionId,
                          String toolName, String arguments, Map<String, Object> metadata,
                          Instant createdAt) {
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
        return confirm(context, toolCallId, context.toolName(), "", Map.of());
    }

    /**
     * 登记一次需要人工确认的工具调用并阻塞等待决定。
     *
     * @param arguments 工具入参原文，供审批卡片展示
     * @param metadata  审批卡片的扩展描述，例如单选按钮组
     * @return 接口写入的决定；超时或运行被终止时返回 null
     */
    public Map<String, Object> confirm(AgentContext context, String toolCallId, String toolName,
                                       String arguments, Map<String, Object> metadata) {
        try {
            return register(context, toolCallId, toolName, arguments, metadata).block();
        } catch (RuntimeException e) {
            // 超时或接口异常：由调用方自行判定如何处理
            return null;
        }
    }

    /**
     * 只登记待确认项，不等待决定，返回等待结果的 Mono。
     *
     * <p>一批工具调用需要审批时，先把每个调用都登记进来（前端立刻能看到全部审批卡片），
     * 再逐个 block 等待决定，避免「审批一个、执行一个、再弹下一个」的串行体验。</p>
     */
    public Mono<Map<String, Object>> register(AgentContext context, String toolCallId, String toolName,
                                              String arguments, Map<String, Object> metadata) {
        requireText(toolCallId, "toolCallId");
        String agentName = context.get(AgentContext.AGENT_NAME, String.class);
        if (agentName == null || agentName.isBlank()) {
            agentName = DEFAULT_AGENT;
        }
        return wait(agentName, context, toolCallId, toolName,
                arguments == null ? "" : arguments,
                metadata == null ? Map.of() : metadata);
    }

    /**
     * 按复合键写入决定，整个请求体作为决定交给工具。
     *
     * @return 是否存在等待项并被写入
     */
    public boolean complete(String key, Map<String, Object> payload) {
        requireText(key, "key");
        Sinks.One<Map<String, Object>> sink = waiting.get(key);
        if (sink == null) {
            // 兼容只传 toolCallId 的旧调用：唯一命中时才接受
            String suffix = "/" + key;
            String matched = waiting.keySet().stream()
                    .filter(candidate -> candidate.endsWith(suffix))
                    .findFirst()
                    .orElse(null);
            if (matched == null) {
                return false;
            }
            key = matched;
            sink = waiting.get(key);
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
     * 上下文中的确认键，约定为 HITL+agentId/toolCallId，避免父子 Agent 的 call_0 冲突。
     */
    public static String key(String agentId, String toolCallId) {
        return "HITL" + (agentId == null || agentId.isBlank() ? "root" : agentId) + "/" + toolCallId;
    }

    private Mono<Map<String, Object>> wait(String agentName, AgentContext context, String toolCallId,
                                           String toolName, String arguments, Map<String, Object> metadata) {
        String agentId = context.agentId() == null ? "root" : context.agentId();
        String key = agentId + "/" + toolCallId;
        Sinks.One<Map<String, Object>> sink = Sinks.one();
        if (waiting.putIfAbsent(key, sink) != null) {
            throw new IllegalStateException("重复的工具调用 id：" + key);
        }
        pendings.put(key, new Pending(key, agentId, toolCallId, agentName, context.sessionId(),
                toolName, arguments, metadata, Instant.now()));
        context.put(key(agentId, toolCallId), sink.asMono());
        // agent 被终止时让等待立即结束，释放被工具占用的线程
        context.onCancel(() -> sink.tryEmitError(new CancellationException("agent 已终止")));

        return sink.asMono()
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .doFinally(signal -> {
                    waiting.remove(key);
                    pendings.remove(key);
                });
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
    }
}
