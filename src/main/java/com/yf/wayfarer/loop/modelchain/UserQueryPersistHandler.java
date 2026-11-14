package com.yf.wayfarer.loop.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.SessionMessage;

/**
 * 用户请求处理器。
 *
 * @author yyf
 * @description 只挂在模型链上：在本轮第一个轮次把上下文中的用户请求写入消息历史与会话文件，
 * 并在会话还没有名称时用第一条用户消息命名；先于记忆压缩与模型调用执行
 */
public class UserQueryPersistHandler implements ChainHandler {

    /**
     * 自动命名的最大长度。
     */
    private static final int TITLE_MAX = 30;

    /**
     * 先于记忆压缩与模型调用执行。
     */
    @Override
    public int sort() {
        return -200;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        appendQuery(context);
        next.run();
    }

    private static void appendQuery(AgentContext context) {
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
        boolean firstUserMessage = context.messages().stream()
                .noneMatch(message -> message.role() == ChatMessage.Role.USER);
        context.messages().add(user);
        session.append(sessionId, JSONMsgType.USER, traceId, SessionMessage.toMap(user));

        // 会话还没定名时用第一条用户消息命名，前端列表直接展示后端名称
        if (firstUserMessage && Session.DEFAULT_NAME.equals(session.meta(sessionId).name())) {
            session.rename(sessionId, titleOf(query));
        }
    }

    /**
     * 名称取第一条用户消息，压平空白并限制长度，避免文件夹名过长。
     */
    private static String titleOf(String query) {
        String title = query.strip().replaceAll("\\s+", " ");
        return title.length() <= TITLE_MAX ? title : title.substring(0, TITLE_MAX) + "…";
    }
}
