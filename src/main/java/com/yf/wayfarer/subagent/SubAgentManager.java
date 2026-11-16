package com.yf.wayfarer.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * 子 Agent 管理器。
 *
 * @author yyf
 * @description 统一持有子 Agent 线程池、按会话统计同时运行数量、以及「uuid/派发toolCallId → Future」结果表。
 * 派发时以会话为粒度原子占位，超过上限直接拒绝；结果被读取后删除对应键，避免内存堆积
 */
public final class SubAgentManager {

    private static final Logger log = LoggerFactory.getLogger(SubAgentManager.class);

    private static final String KEY_SEPARATOR = "/";

    private final int maxChildren;
    private final long taskTimeoutSeconds;
    private final ExecutorService pool;
    private final AtomicInteger threadIndex = new AtomicInteger();

    /**
     * uuid + "/" + 派发 toolCallId -> 子任务结果。
     */
    private final Map<String, CompletableFuture<SubAgentResult>> results = new ConcurrentHashMap<>();

    /**
     * sessionId -> 该会话的子任务运行状态。
     */
    private final Map<String, Running> sessions = new ConcurrentHashMap<>();

    public SubAgentManager(int maxChildren, long taskTimeoutSeconds) {
        this.maxChildren = Math.max(1, maxChildren);
        this.taskTimeoutSeconds = taskTimeoutSeconds;
        this.pool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "subagent-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 会话当前是否还能再派发子 Agent。
     */
    public boolean hasCapacity(String sessionId) {
        Running running = sessions.get(sessionId);
        return running == null || running.active.get() < maxChildren;
    }

    /**
     * 派发一个子任务。
     *
     * @param task 入参为 (uuid, agentId)，返回子 Agent 终态结果
     * @return 派发凭据；会话已满时返回 null
     */
    public Submission submit(String sessionId, String toolCallId, String agentName,
                             BiFunction<String, String, SubAgentResult> task) {
        Running running = sessions.computeIfAbsent(sessionId, key -> new Running());
        if (!tryAcquire(running)) {
            return null;
        }
        String uuid = UUID.randomUUID().toString();
        String agentId = "sub-" + uuid.substring(0, 8);
        String key = key(uuid, toolCallId);
        CompletableFuture<SubAgentResult> future = new CompletableFuture<>();
        results.put(key, future);
        running.keys.add(key);
        running.futures.add(future);
        running.children.put(agentId, new ChildInfo(agentId, agentName, "running"));

        CompletableFuture.runAsync(() -> {
            SubAgentResult result;
            try {
                result = task.apply(uuid, agentId);
            } catch (RuntimeException e) {
                log.warn("子Agent执行异常 agentId={} uuid={}", agentId, uuid, e);
                result = SubAgentResult.failed(agentId,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 0L);
            } finally {
                running.active.decrementAndGet();
            }
            future.complete(result);
        }, pool).whenComplete((ignored, error) -> {
            if (error != null) {
                future.completeExceptionally(error);
            }
            running.futures.remove(future);
        });

        future.whenComplete((result, error) -> {
            if (result != null) {
                running.children.put(agentId, new ChildInfo(agentId, agentName, result.status()));
            } else if (error != null) {
                // 被级联取消的子任务与执行失败要区分开，收尾汇总时状态才准确
                boolean cancelled = error instanceof CancellationException
                        || error.getCause() instanceof CancellationException;
                running.children.put(agentId, new ChildInfo(agentId, agentName,
                        cancelled ? "cancelled" : "failed"));
            }
        });

        log.info("派发子Agent sessionId={} agentName={} agentId={} uuid={} toolCallId={}",
                sessionId, agentName, agentId, uuid, toolCallId);
        return new Submission(uuid, key, agentId);
    }

    /**
     * 阻塞等待子任务结束并删除结果；找不到时返回 null。
     */
    public SubAgentResult await(String uuid) {
        String key = findKey(uuid);
        if (key == null) {
            return null;
        }
        CompletableFuture<SubAgentResult> future = results.remove(key);
        return future == null ? null : get(future, true).result();
    }

    /**
     * 读取子任务结果：wait 为 true 时阻塞到结束；非阻塞且仍在运行时保留结果供后续再次查询。
     */
    public Query read(String uuid, boolean wait) {
        String key = findKey(uuid);
        if (key == null) {
            return new Query(uuid, "missing", null, false);
        }
        CompletableFuture<SubAgentResult> future = results.get(key);
        if (future == null) {
            return new Query(uuid, "missing", null, false);
        }
        if (future.isCancelled()) {
            // 已取消的子任务不会再有结果，读取即消费掉该键
            results.remove(key);
            return new Query(uuid, "cancelled", "子Agent已取消", true);
        }
        if (!wait && !future.isDone()) {
            return new Query(uuid, "running", null, false);
        }
        ReadResult read = get(future, wait);
        if (read.timedOut()) {
            return new Query(uuid, "running", null, false);
        }
        results.remove(key);
        SubAgentResult result = read.result();
        String status = result == null ? "failed" : result.status();
        String text = result == null ? read.error() : result.result();
        return new Query(uuid, status, text, true);
    }

    /**
     * 取消某会话下所有子任务，用于 root 取消或收尾超时。
     */
    public void cancelSession(String sessionId) {
        Running running = sessions.get(sessionId);
        if (running == null) {
            return;
        }
        // 先置标记：取消与子任务启动并发时，后登记的钩子也能立即执行
        running.cancelled = true;
        for (Runnable hook : running.cancelHooks) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                log.warn("子Agent取消钩子执行失败 sessionId={}", sessionId, e);
            }
        }
        for (CompletableFuture<SubAgentResult> future : running.futures) {
            future.cancel(true);
        }
    }

    /**
     * 等待会话内所有子任务结束（含超时），用于 root 收尾。
     */
    public void awaitSession(String sessionId, long timeoutSeconds) {
        Running running = sessions.get(sessionId);
        if (running == null) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
        for (CompletableFuture<SubAgentResult> future : List.copyOf(running.futures)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            try {
                future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                log.warn("等待子Agent收敛超时 sessionId={}", sessionId);
                return;
            } catch (CancellationException e) {
                // 已取消的子任务无需继续等待
            } catch (ExecutionException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * 等待会话内子任务线程退出（含超时）。
     * 取消是被打断的，结果 future 会立刻结束，但子任务线程可能还在写终态消息；
     * 收尾前等它安静下来，TRACE_END 才能是最后一条。
     */
    public void awaitQuiet(String sessionId, long timeoutSeconds) {
        Running running = sessions.get(sessionId);
        if (running == null) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
        while (running.active.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 子任务终态列表，写入 TRACE_END 的 children 字段。
     */
    public List<Map<String, Object>> children(String sessionId) {
        Running running = sessions.get(sessionId);
        if (running == null || running.children.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (ChildInfo info : running.children.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", info.id());
            item.put("agent", info.agent());
            item.put("status", info.status());
            list.add(item);
        }
        return list;
    }

    /**
     * 释放会话级统计与未读取的结果，root 收尾后调用。
     */
    public void closeSession(String sessionId) {
        Running running = sessions.remove(sessionId);
        if (running == null) {
            return;
        }
        // 本会话的未读取结果随 root 结束一并丢弃，避免内存长期占用
        for (String key : running.keys) {
            results.remove(key);
        }
    }

    /**
     * 注册子 Agent 的取消钩子，取消时由管理器统一触发。
     */
    public void onCancel(String sessionId, Runnable hook) {
        Running running = sessions.computeIfAbsent(sessionId, key -> new Running());
        running.cancelHooks.add(hook);
        // 取消可能发生在子Agent真正启动之前，补一次立即执行，避免漏掉这次终止
        if (running.cancelled) {
            hook.run();
        }
    }

    private boolean tryAcquire(Running running) {
        while (true) {
            int current = running.active.get();
            if (current >= maxChildren) {
                return false;
            }
            if (running.active.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private String findKey(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return null;
        }
        String prefix = uuid + KEY_SEPARATOR;
        for (String key : results.keySet()) {
            if (key.startsWith(prefix)) {
                return key;
            }
        }
        return null;
    }

    private ReadResult get(CompletableFuture<SubAgentResult> future, boolean wait) {
        try {
            if (wait) {
                return new ReadResult(future.get(taskTimeoutSeconds, TimeUnit.SECONDS), null, false);
            }
            return new ReadResult(future.get(), null, false);
        } catch (TimeoutException e) {
            return new ReadResult(null, null, true);
        } catch (CancellationException e) {
            return new ReadResult(null, "子Agent已取消", false);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new ReadResult(null,
                    cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ReadResult(null, "等待被中断", false);
        }
    }

    private static String key(String uuid, String toolCallId) {
        return uuid + KEY_SEPARATOR + (toolCallId == null ? "none" : toolCallId);
    }

    /**
     * 派发凭据。
     */
    public record Submission(String uuid, String key, String agentId) {
    }

    /**
     * 查询结果；status 为 missing/running/完成态。
     */
    public record Query(String uuid, String status, String result, boolean removed) {

        public boolean running() {
            return "running".equals(status);
        }

        public boolean missing() {
            return "missing".equals(status);
        }
    }

    private record ReadResult(SubAgentResult result, String error, boolean timedOut) {
    }

    private record ChildInfo(String id, String agent, String status) {
    }

    private static final class Running {
        private final AtomicInteger active = new AtomicInteger();
        private final Set<CompletableFuture<SubAgentResult>> futures = new CopyOnWriteArraySet<>();
        private final Set<String> keys = new CopyOnWriteArraySet<>();
        private final Set<Runnable> cancelHooks = new CopyOnWriteArraySet<>();
        private final Map<String, ChildInfo> children = new ConcurrentHashMap<>();
        /**
         * 本会话是否已被级联取消，供晚到的取消钩子判断。
         */
        private volatile boolean cancelled;
    }
}
