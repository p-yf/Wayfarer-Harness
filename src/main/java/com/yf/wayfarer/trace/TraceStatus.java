package com.yf.wayfarer.trace;

import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.SessionMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话运行状态。
 *
 * @author yyf
 * @description 单实例内存中的权威准入状态：以 sessionId 为键记录正在运行的 traceId，
 * 保证同一会话任意时刻最多一个未结束的 trace；同时负责进程崩溃后的悬空 trace 补终态
 */
public final class TraceStatus {

    private static final Logger log = LoggerFactory.getLogger(TraceStatus.class);

    private final Session session;

    /**
     * sessionId -> 运行中的 traceId。
     */
    private final Map<String, String> running = new ConcurrentHashMap<>();

    /**
     * traceId -> 开始时间。
     */
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();

    /**
     * 已完成崩溃恢复的会话。
     */
    private final Set<String> recovered = ConcurrentHashMap.newKeySet();

    /**
     * 已封存的 trace：TRACE_END 落盘后不再接受该 trace 的写入。
     */
    private final Set<String> sealed = ConcurrentHashMap.newKeySet();

    public TraceStatus(Session session) {
        this.session = session;
    }

    /**
     * 尝试开始一轮。以 sessionId 为键 putIfAbsent，成功才放行；
     * 不能用 traceId 做键，否则两个并发请求各自插入不同 traceId 会双双放行。
     */
    public boolean tryStart(String sessionId, String traceId) {
        if (sessionId == null || traceId == null) {
            return false;
        }
        String existing = running.putIfAbsent(sessionId, traceId);
        if (existing != null) {
            return false;
        }
        startedAt.put(traceId, System.currentTimeMillis());
        return true;
    }

    /**
     * 结束一轮。只有当前值等于该 traceId 才移除，避免误删下一轮。
     */
    public void finish(String sessionId, String traceId) {
        if (sessionId != null && traceId != null) {
            running.remove(sessionId, traceId);
            startedAt.remove(traceId);
        }
    }

    /**
     * 封存 trace：此后的追加写入应被拒绝。
     */
    public void seal(String traceId) {
        if (traceId != null) {
            sealed.add(traceId);
        }
    }

    public boolean sealed(String traceId) {
        return traceId != null && sealed.contains(traceId);
    }

    public String runningTrace(String sessionId) {
        return running.get(sessionId);
    }

    public boolean isRunning(String sessionId) {
        return sessionId != null && running.containsKey(sessionId);
    }

    public Long startedAt(String traceId) {
        return traceId == null ? null : startedAt.get(traceId);
    }

    /**
     * 崩溃恢复：单实例下进程崩溃等于运行线程全部消失，因此允许新请求；
     * 但出现过消息却没有 TRACE_END 的 trace 需要补一条 aborted，页面才不会永远转圈。
     * 每个会话在进程内只扫描一次；当前即将开始的 trace 不参与判定，否则会把自己的用户消息误判为悬空。
     */
    public void ensureRecovered(String sessionId, String currentTraceId) {
        if (sessionId == null || !recovered.add(sessionId)) {
            return;
        }
        try {
            List<SessionMessage> all = session.messages(sessionId);
            Set<String> seen = new LinkedHashSet<>();
            Set<String> ended = new LinkedHashSet<>();
            for (SessionMessage message : all) {
                if (message.traceId() == null) {
                    continue;
                }
                seen.add(message.traceId());
                if (message.type() == JSONMsgType.TRACE_END) {
                    ended.add(message.traceId());
                }
            }
            for (String traceId : seen) {
                if (ended.contains(traceId) || traceId.equals(currentTraceId)) {
                    continue;
                }
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("status", "aborted");
                content.put("reason", "进程中断，未正常收尾");
                content.put("endedAt", System.currentTimeMillis());
                session.append(sessionId, JSONMsgType.TRACE_END, traceId, content);
                log.warn("补齐中断的 trace sessionId={} traceId={}", sessionId, traceId);
            }
        } catch (RuntimeException e) {
            // 恢复失败不能阻止新请求，最多页面把该轮显示为运行中
            log.warn("崩溃恢复失败 sessionId={}", sessionId, e);
        }
    }
}
