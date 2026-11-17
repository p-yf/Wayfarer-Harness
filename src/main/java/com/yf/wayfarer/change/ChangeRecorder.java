package com.yf.wayfarer.change;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.message.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 一次 agent 运行的文件变更记录器。
 *
 * @author yyf
 * @description 运行开始时记下工作区脏文件集合，运行收尾时取差集提交成一条记录：
 * 一轮运行 = 一条提交，既不用按工具名判断写不写文件，也不需要为每次调用做两次状态扫描。
 * 收尾由运行级链的 finally 触发，因此正常结束、异常、被取消都不会漏记；
 * 差集的意义在于排除运行前用户自己就改脏的文件
 */
public final class ChangeRecorder {

    private static final Logger log = LoggerFactory.getLogger(ChangeRecorder.class);

    /**
     * 一轮运行写成一条提交，提交说明里的工具名固定用该值，便于在 git log 里区分。
     */
    static final String RUN_TOOL = "run";

    /**
     * 运行开始前的脏文件快照。
     *
     * <p>用户自己改过的文件必须先落成提交，否则它一直处于「运行前就脏」的状态，
     * 永远进不了本轮记录，撤回也就无从恢复。快照只作为本轮提交的基线，
     * 不参与页面展示，也不参与撤回。</p>
     */
    static final String SNAPSHOT_TOOL = "snapshot";

    private final ShadowRepositoryStore store;

    /**
     * 查询服务，仅用于运行结束时汇总本轮变更；单元测试可以只传 store。
     */
    private final ChangeService changeService;

    public ChangeRecorder(ShadowRepositoryStore store) {
        this(store, null);
    }

    public ChangeRecorder(ShadowRepositoryStore store, ChangeService changeService) {
        this.store = store;
        this.changeService = changeService;
    }

    /**
     * 运行开始时调用：确保仓库存在并记下当前脏文件集合。
     *
     * @return 收尾时交给 {@link #finish} 的快照；不需要记录时返回 null
     */
    public Run begin(AgentContext context) {
        if (!store.enabled() || context == null) {
            return null;
        }
        try {
            ShadowRepository repository = store.forWorkspace(
                    context.get(AgentContext.WORKSPACE, String.class));
            if (repository == null) {
                return null;
            }
            repository.ensure();
            snapshot(repository, context);
            // 快照已把运行前的改动落成提交，这里重新取一次脏集合：
            // 正常情况下为空；个别文件暂存失败时会留在集合里，仍按旧规则排除，不影响记录
            return new Run(repository, repository.dirtyPaths());
        } catch (RuntimeException e) {
            // 记录失败不能影响运行本身，工具照常执行
            log.warn("变更记录准备失败 traceId={}", context.traceId(), e);
            return null;
        }
    }

    /**
     * 把运行开始前工作区的脏文件提交成一条快照，作为本轮的基线。
     *
     * <p>没有脏文件时不产生提交。本轮提交的父提交就是这条快照，所以「写回父提交内容」
     * 永远等于「回到本轮开始前的磁盘状态」：即使 Agent 改的是用户运行前就改过的文件，
     * 撤回也只会撤掉 Agent 那部分，用户的改动不会被带到父提交里一起抹掉。</p>
     */
    private void snapshot(ShadowRepository repository, AgentContext context) {
        Set<String> dirty = repository.dirtyPaths();
        if (dirty.isEmpty()) {
            return;
        }
        Map<String, String> trailers = new LinkedHashMap<>();
        trailers.put(ShadowRepository.TRAILER_SESSION, nullSafe(context.sessionId()));
        trailers.put(ShadowRepository.TRAILER_TRACE, nullSafe(context.traceId()));
        trailers.put(ShadowRepository.TRAILER_TOOL, SNAPSHOT_TOOL);
        trailers.put(ShadowRepository.TRAILER_STATUS, "snapshot");
        String commitId = repository.commit(dirty, "snapshot " + dirty.size() + " 个文件", trailers);
        if (commitId != null) {
            log.info("记录运行前快照 traceId={} 文件数={} commit={}",
                    context.traceId(), dirty.size(), commitId);
        }
    }

    /**
     * 运行收尾时调用：把「结束时变脏的文件 − 开始时已脏的文件」提交成一条记录。
     *
     * <p>正常结束、异常、取消都会调用；重复调用只提交一次，避免取消回调与完成回调叠加。</p>
     */
    public void finish(AgentContext context, Run run) {
        if (run == null || context == null || !run.once.compareAndSet(false, true)) {
            return;
        }
        try {
            Set<String> dirtyAfter = run.repository.dirtyPaths();
            Set<String> changed = new LinkedHashSet<>(dirtyAfter);
            changed.removeAll(run.dirtyBefore);
            if (changed.isEmpty()) {
                return;
            }

            Map<String, String> trailers = new LinkedHashMap<>();
            trailers.put(ShadowRepository.TRAILER_SESSION, nullSafe(context.sessionId()));
            trailers.put(ShadowRepository.TRAILER_TRACE, nullSafe(context.traceId()));
            trailers.put(ShadowRepository.TRAILER_TOOL, RUN_TOOL);
            trailers.put(ShadowRepository.TRAILER_STATUS, "success");

            String commitId = run.repository.commit(changed, "run " + changed.size() + " 个文件", trailers);
            if (commitId != null) {
                log.info("记录本轮文件变更 traceId={} 文件数={} commit={}",
                        context.traceId(), changed.size(), commitId);
            }
        } catch (RuntimeException e) {
            log.warn("变更记录失败 traceId={}", context.traceId(), e);
            notifyFailure(context, e);
        }
    }

    /**
     * 运行结束时的整体变更消息。
     *
     * <p>由运行级链追加到 SSE 流末尾，页面据此直接渲染「文件变更气泡」，不需要再发一次查询请求。
     * 没有变更时返回 null，不推送空事件。取消的运行不会走到这里，页面刷新时由查询接口还原气泡。</p>
     */
    public AgentMessage summary(AgentContext context) {
        if (!store.enabled() || changeService == null || context == null) {
            return null;
        }
        String sessionId = context.sessionId();
        String traceId = context.traceId();
        if (sessionId == null || traceId == null) {
            return null;
        }
        try {
            List<Map<String, Object>> entries = changeService.list(sessionId, traceId, 200);
            if (entries.isEmpty()) {
                return null;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "changes");
            data.put("traceId", traceId);
            data.put("entries", entries);
            return new AgentMessage.Other(traceId, data);
        } catch (RuntimeException e) {
            log.warn("汇总本轮变更失败 traceId={}", traceId, e);
            return null;
        }
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
     * @description 一次运行的记录基线：仓库与运行开始时的脏文件集合；once 保证收尾只提交一次
     */
    public static final class Run {
        private final ShadowRepository repository;
        private final Set<String> dirtyBefore;
        private final AtomicBoolean once = new AtomicBoolean(false);

        private Run(ShadowRepository repository, Set<String> dirtyBefore) {
            this.repository = repository;
            this.dirtyBefore = Set.copyOf(dirtyBefore);
        }
    }
}
