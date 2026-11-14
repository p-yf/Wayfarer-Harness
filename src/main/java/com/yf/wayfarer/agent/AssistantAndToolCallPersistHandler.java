package com.yf.wayfarer.agent;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.SessionMessage;

import java.util.List;

/**
 * 会话持久化处理器（两条链子都挂）。
 *
 * @author yyf
 * @description 环绕下游处理器，把本次步骤新增的对话消息按其自带的 trace id 写入会话文件；
 * finally 中落盘，运行被取消或抛异常时已产生的消息同样保留
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
    public void run(AgentContext context, Runnable next) {
        int before = sizeOf(context);
        try {
            next.run();
        } finally {
            persist(context, before);
        }
    }

    private static void persist(AgentContext context, int from) {
        Session session = context.session();
        List<ChatMessage> messages = context.messages();
        if (session == null || messages == null) {
            return;
        }
        String sessionId = context.sessionId();
        String traceId = context.traceId();
        if (sessionId == null) {
            return;
        }
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
