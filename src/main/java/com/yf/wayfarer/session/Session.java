package com.yf.wayfarer.session;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.model.ChatMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话存储。
 *
 * @author yyf
 * @description 无状态的会话操作入口，自身不绑定具体会话，所有操作通过 sessionId 指定目标会话
 */
public interface Session {

    /**
     * 新建会话时的默认名称，出现第一条用户消息后会替换为该消息内容。
     */
    String DEFAULT_NAME = "未命名会话";

    /**
     * 会话元数据中保存工作区绝对路径的键，前端按它把会话归组到工作区下。
     */
    String WORKSPACE = "workspace";

    /**
     * 新建会话，返回会话 id。
     */
    String create(String name);

    /**
     * 修改会话名称，会话 id 与创建时间保持不变。
     */
    void rename(String sessionId, String name);

    /**
     * 查询会话元数据，等价于按 id 恢复会话。
     */
    SessionMeta meta(String sessionId);

    /**
     * 列出全部会话元数据。
     */
    List<SessionMeta> list();

    /**
     * 改写会话文件首行 METADATA 的内容，文件没有元数据行时插入到第一行。
     */
    void updateMetadata(String sessionId, Map<String, Object> content);

    /**
     * 向指定会话追加一条消息，缺少 messageId 时自动生成 uuid。
     */
    void append(String sessionId, Map<String, Object> message);

    /**
     * 向指定会话追加无消息 id 的消息，由实现补齐。
     */
    default void append(String sessionId, JSONMsgType type, String traceId, Map<String, Object> content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("traceId", traceId);
        message.put("type", type.name());
        message.put("content", content);
        append(sessionId, message);
    }

    /**
     * 按文件顺序读取指定会话的全部消息。
     */
    List<SessionMessage> messages(String sessionId);

    /**
     * 删除指定会话的全部消息，会话本身随之移除。
     */
    void delete(String sessionId);

    /**
     * 删除指定会话中该 trace 及其之后的全部消息。
     */
    void reset(String sessionId, String traceId);

    /**
     * 把指定会话中截至该 trace（含该轮）的消息克隆到新会话，返回新会话 id。
     */
    String fork(String sessionId, String traceId);

    /**
     * 克隆指定会话的全部消息到新会话，返回新会话 id。
     */
    String clone(String sessionId);

    /**
     * 按 trace 滑动取历史：返回指定 trace 之前（不含该 trace）最近 turns 轮的对话消息。
     * traceId 为空时取最近的 turns 轮。
     */
    default List<SessionMessage> historyBefore(String sessionId, String traceId, int turns) {
        if (turns <= 0) {
            return List.of();
        }
        Map<String, List<SessionMessage>> grouped = new LinkedHashMap<>();
        for (SessionMessage message : messages(sessionId)) {
            if (message.traceId() == null
                    || message.type() == JSONMsgType.COMPACT
                    || message.type() == JSONMsgType.CUSTOM) {
                continue;
            }
            grouped.computeIfAbsent(message.traceId(), key -> new ArrayList<>()).add(message);
        }

        List<String> order = new ArrayList<>(grouped.keySet());
        int end = traceId == null || traceId.isBlank() ? order.size() : order.indexOf(traceId);
        if (end < 0) {
            throw new IllegalArgumentException("会话中不存在 traceId：" + traceId);
        }

        List<SessionMessage> page = new ArrayList<>();
        for (int i = Math.max(0, end - turns); i < end; i++) {
            page.addAll(grouped.get(order.get(i)));
        }
        return page;
    }

    /**
     * 还原对话历史：以最近一条 COMPACT 的内容为基线，再回放其后的对话消息。
     */
    default List<ChatMessage> history(String sessionId) {
        return historyOf(messages(sessionId));
    }

    /**
     * 从消息列表中还原对话历史。
     */
    static List<ChatMessage> historyOf(List<SessionMessage> all) {
        int compactIndex = -1;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).type() == JSONMsgType.COMPACT) {
                compactIndex = i;
                break;
            }
        }

        List<ChatMessage> history = new ArrayList<>();
        if (compactIndex >= 0) {
            Object base = all.get(compactIndex).content().get("messages");
            if (base instanceof List<?> list) {
                for (Object item : list) {
                    history.add(SessionMessage.toChatMessage(cast(item)));
                }
            }
        }
        for (int i = compactIndex + 1; i < all.size(); i++) {
            SessionMessage message = all.get(i);
            if (message.type() == JSONMsgType.USER
                    || message.type() == JSONMsgType.ASSISTANT
                    || message.type() == JSONMsgType.TOOL) {
                history.add(SessionMessage.toChatMessage(message.content()));
            }
        }
        return history;
    }

    /**
     * 把指定会话恢复到上下文：消息历史写入 MESSAGES，最后一个 CUSTOM 消息的条目写回上下文。
     */
    default void restore(String sessionId, AgentContext context) {
        context.put(AgentContext.MESSAGES, new ArrayList<>(history(sessionId)));

        List<SessionMessage> all = messages(sessionId);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).type() == JSONMsgType.CUSTOM) {
                all.get(i).content().forEach(context::put);
                break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
