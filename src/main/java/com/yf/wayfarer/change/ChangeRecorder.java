package com.yf.wayfarer.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 工具调用的变更记录器。
 *
 * @author yyf
 * @description 在工具执行前后各取一次工作区的脏文件快照：差集就是这次调用真正改动的路径，
 * 成功时提交成一条影子仓库记录。读写分离、成功后提交、失败不提交，避免把别人的改动或半截结果记进来
 */
public final class ChangeRecorder {

    private static final Logger log = LoggerFactory.getLogger(ChangeRecorder.class);

    /**
     * 可能改动文件的工具。read 之类的只读工具不做前后快照，省掉无意义的状态扫描。
     */
    private static final Set<String> WRITE_TOOLS = Set.of("bash", "edit", "write");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ShadowRepositoryStore store;

    public ChangeRecorder(ShadowRepositoryStore store) {
        this.store = store;
    }

    /**
     * 工具执行前调用：准备仓库并记录当前脏文件集合。
     *
     * @return 后续传给 {@link #after} 的快照；不需要记录时返回 null
     */
    public Snapshot before(AgentContext context, ToolCallInfo info) {
        if (!store.enabled() || context == null || info == null || !WRITE_TOOLS.contains(info.name())) {
            return null;
        }
        try {
            ShadowRepository repository = store.forWorkspace(
                    context.get(AgentContext.WORKSPACE, String.class));
            if (repository == null) {
                return null;
            }
            repository.ensure();
            return new Snapshot(repository, info, repository.dirtyPaths());
        } catch (RuntimeException e) {
            // 记录失败不能影响工具本身，工具照常执行
            log.warn("变更记录准备失败 traceId={} 工具={}", context.traceId(), info.name(), e);
            return null;
        }
    }

    /**
     * 工具执行后调用：计算本次真正改动的路径并提交。
     *
     * @param content 工具返回给模型的文本，用于判断本次调用是否成功
     */
    public void after(AgentContext context, Snapshot snapshot, String content) {
        if (snapshot == null || context == null) {
            return;
        }
        if (failed(content)) {
            // 失败调用写了半个文件也不提交；残留改动会在下一次成功提交时被一起带走
            log.info("工具失败，不记录变更 traceId={} 工具={}", context.traceId(), snapshot.info().name());
            return;
        }
        try {
            Set<String> dirtyAfter = snapshot.repository().dirtyPaths();
            Set<String> changed = new LinkedHashSet<>(dirtyAfter);
            changed.removeAll(snapshot.dirtyBefore());

            // write/edit 的参数路径是已知目标，若它确实变脏则强制纳入，避免路径写法差异导致漏记
            String argumentPath = snapshot.repository().relativePath(pathArgument(snapshot.info()));
            if (argumentPath != null && dirtyAfter.contains(argumentPath)) {
                changed.add(argumentPath);
            }
            if (changed.isEmpty()) {
                return;
            }

            Map<String, String> trailers = new LinkedHashMap<>();
            trailers.put(ShadowRepository.TRAILER_SESSION, nullSafe(context.sessionId()));
            trailers.put(ShadowRepository.TRAILER_TRACE, nullSafe(context.traceId()));
            trailers.put(ShadowRepository.TRAILER_TOOL, snapshot.info().name());
            trailers.put(ShadowRepository.TRAILER_CALL, nullSafe(snapshot.info().id()));
            trailers.put(ShadowRepository.TRAILER_STATUS, "success");

            String commitId = snapshot.repository().commit(changed, summary(snapshot.info(), changed), trailers);
            if (commitId != null) {
                log.info("记录文件变更 traceId={} 工具={} 文件数={} commit={}",
                        context.traceId(), snapshot.info().name(), changed.size(), commitId);
            }
        } catch (RuntimeException e) {
            log.warn("变更记录失败 traceId={} 工具={}", context.traceId(), snapshot.info().name(), e);
            notifyFailure(context, e);
        }
    }

    /**
     * 工具失败判定：现有工具把错误写在返回文本里而不是抛异常，这里按约定前缀识别。
     */
    static boolean failed(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        return content.startsWith("错误：")
                || content.contains("命令退出码 ")
                || content.contains("命令超时，已在")
                || content.contains("命令已被终止");
    }

    /**
     * 从工具参数里取 path 字段（write/edit 使用），bash 没有该字段。
     */
    private static String pathArgument(ToolCallInfo info) {
        try {
            JsonNode node = JSON.readTree(info.arguments());
            JsonNode path = node == null ? null : node.get("path");
            return path == null || path.isNull() ? null : path.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static String summary(ToolCallInfo info, Set<String> changed) {
        if (changed.size() == 1) {
            return info.name() + " " + changed.iterator().next();
        }
        return info.name() + " " + changed.size() + " 个文件";
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 记录失败时通过 other 事件告知页面；不影响工具结果，也不中断运行。
     */
    private static void notifyFailure(AgentContext context, RuntimeException error) {
        Consumer<AgentMessage> sink = context.agentMsgSink();
        if (sink == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "change-error");
        data.put("message", "变更记录失败：" + error.getMessage());
        sink.accept(new AgentMessage.Other(context.traceId(), data));
    }

    /**
     * @author yyf
     * @description 一次工具调用的记录上下文：仓库、工具信息与执行前的脏文件集合
     */
    public record Snapshot(ShadowRepository repository, ToolCallInfo info, Set<String> dirtyBefore) {
    }
}
