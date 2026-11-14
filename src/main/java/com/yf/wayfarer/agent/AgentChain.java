package com.yf.wayfarer.agent;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.message.AgentMessage;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * agent 运行级环绕链。
 *
 * @author yyf
 * @description 包在主循环之外，处理的是整条消息流：前置逻辑写在取用 next 之前，
 * 后置逻辑通过 next 追加消息，因而可以在一次运行的首尾插入动作，而 agent 本身不必感知这些细节
 */
public final class AgentChain {

    private static final AgentChain EMPTY = new AgentChain(List.of());

    private final List<AgentHandler> handlers;

    private AgentChain(List<AgentHandler> handlers) {
        this.handlers = handlers;
    }

    /**
     * 按 sort 升序稳定排序后组装。
     */
    public static AgentChain of(AgentHandler... handlers) {
        if (handlers == null || handlers.length == 0) {
            return EMPTY;
        }
        List<AgentHandler> sorted = new ArrayList<>(List.of(handlers));
        sorted.sort(Comparator.comparingInt(AgentHandler::sort));
        return new AgentChain(List.copyOf(sorted));
    }

    /**
     * 把主循环的消息流交给各处理器环绕，返回叠加后的流。
     */
    public Flux<AgentMessage> apply(AgentContext context, Flux<AgentMessage> next) {
        Flux<AgentMessage> stream = next;
        // 逆序包裹：sort 最小的处理器最后包裹，因此位于最外层
        for (int index = handlers.size() - 1; index >= 0; index--) {
            AgentHandler handler = handlers.get(index);
            stream = handler.apply(context, stream);
        }
        return stream;
    }
}
