package com.yf.wayfarer.agent.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.SessionMessage;
import reactor.core.publisher.Mono;

/**
 * 用户请求持久化处理器。
 *
 * @author yyf
 * @description 只挂在模型链上：在本轮第一个轮次把上下文中的用户请求写入会话与消息历史，
 * 先于记忆压缩与模型调用执行，保证本轮模型能看到该请求
 */
public class UserQueryPersistHandler implements ChainHandler {

    /**
     * 先于记忆压缩与模型调用执行。
     */
    @Override
    public int sort() {
        return -200;
    }

    @Override
    public Mono<Void> run(AgentContext context, Mono<Void> next) {
        return Mono.defer(() -> {
            persist(context);
            return next;
        });
    }

    private static void persist(AgentContext context) {
        Integer turnIndex = context.turnIndex();
        // 只在第一轮写入，后续轮次是同一次运行内的工具循环
        if (turnIndex == null || turnIndex != 1) {
            return;
        }
        String query = context.userQuery();
        Session session = context.session();
        String sessionId = context.sessionId();
        String traceId = context.traceId();
        if (query == null || query.isBlank() || session == null || sessionId == null || traceId == null) {
            return;
        }
        ChatMessage user = new ChatMessage(ChatMessage.Role.USER, query);
        context.messages().add(user);
        session.append(sessionId, JSONMsgType.USER, traceId, SessionMessage.toMap(user));
    }
}
