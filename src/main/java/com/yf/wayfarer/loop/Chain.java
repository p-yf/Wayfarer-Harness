package com.yf.wayfarer.loop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 环绕式责任链。
 *
 * @author yyf
 * @description 按 sort 升序稳定排序后同步执行，每个处理器通过调用 next 放行到后续处理器
 */
public final class Chain {

    private final List<ChainHandler> handlers;

    private Chain(List<ChainHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    public static Chain of(ChainHandler... handlers) {
        List<ChainHandler> sorted = new ArrayList<>(List.of(handlers));
        sorted.sort(Comparator.comparingInt(ChainHandler::sort));
        return new Chain(sorted);
    }

    public void run(AgentContext context) {
        runFrom(0, context);
    }

    private void runFrom(int index, AgentContext context) {
        if (index >= handlers.size()) {
            return;
        }
        handlers.get(index).run(context, () -> runFrom(index + 1, context));
    }
}
