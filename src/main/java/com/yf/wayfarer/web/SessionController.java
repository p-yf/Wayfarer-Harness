package com.yf.wayfarer.web;

import com.yf.wayfarer.change.ChangeService;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.SessionMessage;
import com.yf.wayfarer.session.SessionMeta;
import com.yf.wayfarer.session.UnloadedMessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final Session session;
    private final UnloadedMessageStore unloadedStore;
    private final ChangeService changeService;

    public SessionController(Session session, UnloadedMessageStore unloadedStore, ChangeService changeService) {
        this.session = session;
        this.unloadedStore = unloadedStore;
        this.changeService = changeService;
    }

    @PostMapping
    public SessionMeta create(@RequestBody(required = false) Map<String, Object> body) {
        String name = body == null
                ? Session.DEFAULT_NAME
                : String.valueOf(body.getOrDefault("name", Session.DEFAULT_NAME));
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
     * 改写会话文件首行 METADATA 的内容，用于保存工作区等任意键值对。
     */
    @PutMapping("/{sessionId}/metadata")
    public SessionMeta updateMetadata(@PathVariable String sessionId,
                                      @RequestBody(required = false) Map<String, Object> content) {
        session.updateMetadata(sessionId, content == null ? Map.of() : content);
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
     * 全量消息，用于调试与导出；CUSTOM 是 agent 内部状态快照，不下发到页面。
     */
    @GetMapping("/{sessionId}/messages")
    public List<SessionMessage> messages(@PathVariable String sessionId) {
        return visible(session.messages(sessionId));
    }

    /**
     * 按 trace 滑动取历史：返回 traceId 之前最近 turns 轮的对话消息，traceId 为空时取最近 turns 轮。
     */
    @GetMapping("/{sessionId}/history")
    public List<SessionMessage> history(@PathVariable String sessionId,
                                        @RequestParam(required = false) String traceId,
                                        @RequestParam(defaultValue = "3") int turns) {
        return visible(session.historyBefore(sessionId, traceId, turns));
    }

    /**
     * 只保留页面需要渲染的消息类型，内部状态快照不下发。
     */
    private static List<SessionMessage> visible(List<SessionMessage> messages) {
        return messages.stream()
                .filter(message -> message.type() != JSONMsgType.CUSTOM)
                .toList();
    }

    /**
     * 从指定轮次分支：克隆截至该轮（含该轮）的全部消息到新会话。
     * 卸载内容一并复制，保证新会话里已有的占位符仍能解析到原文。
     */
    @PostMapping("/{sessionId}/fork")
    public Map<String, Object> fork(@PathVariable String sessionId, @RequestParam String traceId) {
        String forkedId = session.fork(sessionId, traceId);
        unloadedStore.copyAll(sessionId, forkedId);
        return idResult(forkedId);
    }

    /**
     * 复制整个会话到新会话，卸载内容一并复制。
     */
    @PostMapping("/{sessionId}/clone")
    public Map<String, Object> clone(@PathVariable String sessionId) {
        String clonedId = session.clone(sessionId);
        unloadedStore.copyAll(sessionId, clonedId);
        return idResult(clonedId);
    }

    /**
     * 回退会话：删除指定轮次及其之后的全部消息，该轮次之前的对话保留。
     *
     * <p>默认同时回退该会话在这几轮里对工作区做的文件改动（revertChanges=true）：
     * 先用会话里的 trace 顺序算出「保留的轮次」，再删消息，最后按影子仓库记录生成回退提交。
     * 顺序不能反：消息一旦删掉，就不知道边界在哪了。</p>
     */
    @PostMapping("/{sessionId}/reset")
    public Map<String, Object> reset(@PathVariable String sessionId,
                                     @RequestParam String traceId,
                                     @RequestParam(defaultValue = "true") boolean revertChanges) {
        Set<String> survivingTraces = revertChanges
                ? ChangeService.survivingTraces(session.messages(sessionId), traceId)
                : Set.of();
        session.reset(sessionId, traceId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("resetFrom", traceId);
        if (revertChanges) {
            try {
                result.put("changes", changeService.revertSessionChanges(sessionId, survivingTraces, traceId));
            } catch (RuntimeException e) {
                // 会话已经删掉了，文件回退失败只提示，不能让接口报错把状态搅乱
                log.warn("会话回退时文件回退失败 sessionId={} traceId={}", sessionId, traceId, e);
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "变更回退失败：" + e.getMessage());
                result.put("changes", error);
            }
        }
        return result;
    }

    /**
     * 删除会话，会话文件夹及其中的卸载消息一并删除。
     */
    @DeleteMapping("/{sessionId}")
    public Map<String, Object> delete(@PathVariable String sessionId) {
        session.delete(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("deleted", true);
        return result;
    }

    private static Map<String, Object> idResult(String sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        return result;
    }
}
