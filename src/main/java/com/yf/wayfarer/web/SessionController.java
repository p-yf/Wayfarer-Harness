package com.yf.wayfarer.web;

import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.SessionMessage;
import com.yf.wayfarer.session.SessionMeta;
import com.yf.wayfarer.session.UnloadedMessageStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话接口。
 *
 * @author yyf
 * @description 提供会话的创建、列表、详情、消息查看、按 trace 滑动取历史，以及 fork、clone、reset 操作
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final Session session;
    private final UnloadedMessageStore unloadedStore;

    public SessionController(Session session, UnloadedMessageStore unloadedStore) {
        this.session = session;
        this.unloadedStore = unloadedStore;
    }

    @PostMapping
    public SessionMeta create(@RequestBody(required = false) Map<String, Object> body) {
        String name = body == null ? "未命名会话" : String.valueOf(body.getOrDefault("name", "未命名会话"));
        return session.meta(session.create(name));
    }

    @GetMapping
    public List<SessionMeta> list() {
        return session.list();
    }

    @GetMapping("/{sessionId}")
    public SessionMeta meta(@PathVariable String sessionId) {
        return session.meta(sessionId);
    }

    /**
     * 向会话追加一条消息，未指定 trace id 时自动生成并返回，便于与该次运行对应。
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/{sessionId}/messages")
    public Map<String, Object> append(@PathVariable String sessionId, @RequestBody Map<String, Object> body) {
        String traceId = body.get("traceId") == null || String.valueOf(body.get("traceId")).isBlank()
                ? UUID.randomUUID().toString()
                : String.valueOf(body.get("traceId"));
        JSONMsgType type = JSONMsgType.valueOf(
                String.valueOf(body.getOrDefault("type", JSONMsgType.USER.name())).toUpperCase());
        Object rawContent = body.get("content");
        Map<String, Object> content = rawContent instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        session.append(sessionId, type, traceId, content);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("traceId", traceId);
        return result;
    }

    /**
     * 全量消息，用于调试与导出。
     */
    @GetMapping("/{sessionId}/messages")
    public List<SessionMessage> messages(@PathVariable String sessionId) {
        return session.messages(sessionId);
    }

    /**
     * 按 trace 滑动取历史：返回 traceId 之前最近 turns 轮的对话消息，traceId 为空时取最近 turns 轮。
     */
    @GetMapping("/{sessionId}/history")
    public List<SessionMessage> history(@PathVariable String sessionId,
                                        @RequestParam(required = false) String traceId,
                                        @RequestParam(defaultValue = "3") int turns) {
        return session.historyBefore(sessionId, traceId, turns);
    }

    @PostMapping("/{sessionId}/fork")
    public Map<String, Object> fork(@PathVariable String sessionId, @RequestParam String traceId) {
        return idResult(session.fork(sessionId, traceId));
    }

    @PostMapping("/{sessionId}/clone")
    public Map<String, Object> clone(@PathVariable String sessionId) {
        return idResult(session.clone(sessionId));
    }

    /**
     * 删除会话，同时清理该会话的卸载消息。
     */
    @DeleteMapping("/{sessionId}")
    public Map<String, Object> delete(@PathVariable String sessionId) {
        session.delete(sessionId);
        unloadedStore.deleteAll(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("deleted", true);
        return result;
    }

    @DeleteMapping("/{sessionId}/traces/{traceId}")
    public Map<String, Object> reset(@PathVariable String sessionId, @PathVariable String traceId) {
        session.reset(sessionId, traceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("resetFrom", traceId);
        return result;
    }

    private static Map<String, Object> idResult(String sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        return result;
    }
}
