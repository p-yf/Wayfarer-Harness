package com.yf.wayfarer.agent;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.message.AgentMessage;
import reactor.core.publisher.Flux;

/**
 * Agent 级处理器。
 *
 * @author yyf
 * @description 环绕整个 agent 运行：实现方可以在 next 订阅前做前置处理，
 * 用 next.concatWith(...) 在运行结束后追加消息，适合放「运行级」的横切逻辑
 */
public interface AgentHandler {

    /**
     * 执行顺序，值越小越靠外层：前置逻辑先执行，后置逻辑后执行。
     */
    default int sort() {
        return 0;
    }

    /**
     * @param next 已经组装好的后续运行流
     * @return 包装后的流，必须保证 next 只被订阅一次
     */
    Flux<AgentMessage> apply(AgentContext context, Flux<AgentMessage> next);
}
