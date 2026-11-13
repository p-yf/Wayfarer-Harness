package com.yf.wayfarer.agent;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.SessionMessage;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 会话持久化处理器（两条链子都挂）。
 *
 * @author yyf
 * @description 环绕下游处理器，把本次步骤新增的对话消息按其自带的 trace id 写入会话文件；
 * 模型链与工具链各挂一个：模型链负责本轮 assistant 回复，工具链负责工具结果
 */
public class AssistantAndToolCallPersistHandler implements ChainHandler {

    /**
     * 先于产生消息的处理器执行，才能观察到该步骤新增的消息。
     */
    @Override
    public int sort() {
        return -50;
    }

    @Override
    public Mono<Void> run(AgentContext context, Mono<Void> next) {
        return Mono.defer(() -> {
            int before = sizeOf(context);
            return next.then(Mono.fromRunnable(() -> persist(context, before)));
        });
    }

    private static void persist(AgentContext context, int from) {
        Session session = context.session();
        List<ChatMessage> messages = context.messages();
        if (session == null || messages == null) {
            return;
        }
        String sessionId = context.sessionId();
        String traceId = context.traceId();
        for (int i = Math.max(0, from); i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            JSONMsgType type = typeOf(message.role());
            if (type == null) {
                continue;
            }
            session.append(sessionId, type, traceId, SessionMessage.toMap(message));
        }
    }

    private static int sizeOf(AgentContext context) {
        List<ChatMessage> messages = context.messages();
        return messages == null ? 0 : messages.size();
    }

    private static JSONMsgType typeOf(ChatMessage.Role role) {
        return switch (role) {
            case USER -> JSONMsgType.USER;
            case ASSISTANT -> JSONMsgType.ASSISTANT;
            case TOOL -> JSONMsgType.TOOL;
            case SYSTEM -> null;
        };
    }
}
