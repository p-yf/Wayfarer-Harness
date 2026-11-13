package com.yf.wayfarer.loop;

import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 环绕式责任链。
 *
 * @author yyf
 * @description 按 sort 升序稳定排序后执行处理器，每个处理器通过 then(next) 调用后续链路
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

    public Mono<Void> run(AgentContext context) {
        return runFrom(0, context);
    }

    private Mono<Void> runFrom(int index, AgentContext context) {
        if (index >= handlers.size()) {
            return Mono.empty();
        }
        return handlers.get(index).run(context, Mono.defer(() -> runFrom(index + 1, context)));
    }
}
