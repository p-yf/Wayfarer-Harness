package com.yf.wayfarer.trace;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可持久化状态落盘处理器。
 *
 * @author yyf
 * @description 运行结束后把上下文中的 store 状态写成一条 CUSTOM 消息；只有 root 写，
 * 子 Agent 的 store（计划等）属于临时状态，不跨轮保存
 */
public final class StoreStatePersistHandler implements ChainHandler {

    /**
     * 位于变更汇总外层，保证 CUSTOM 先于 TRACE_END、晚于变更汇总。
     */
    @Override
    public int sort() {
        return 20;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        try {
            next.run();
        } finally {
            persist(context);
        }
    }

    private static void persist(AgentContext context) {
        if (context.origin() != null) {
            return;
        }
        Session session = context.session();
        String sessionId = context.sessionId();
        String traceId = context.traceId();
        if (session == null || sessionId == null || traceId == null) {
            return;
        }
        Map<String, Object> store = new LinkedHashMap<>(context.storeState());
        if (store.isEmpty()) {
            return;
        }
        session.append(sessionId, JSONMsgType.CUSTOM, traceId, store);
    }
}
