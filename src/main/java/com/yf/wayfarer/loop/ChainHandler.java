package com.yf.wayfarer.loop;

import reactor.core.publisher.Mono;

/**
 * 环绕式责任链的节点。
 *
 * @author yyf
 * @description 在 next 前后执行自定义逻辑，sort 越小执行顺序越靠前，取消订阅即终止整条链路
 */
@FunctionalInterface
public interface ChainHandler {

    Mono<Void> run(AgentContext context, Mono<Void> next);

    /**
     * 执行顺序，越小越靠前，默认 0。
     */
    default int sort() {
        return 0;
    }
}
