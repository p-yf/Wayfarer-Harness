package com.yf.wayfarer.loop;

/**
 * 环绕式责任链处理器。
 *
 * @author yyf
 * @description 同步执行：前置逻辑写在 next.run() 之前，后置逻辑写在之后，
 * 不需要编排响应式信号即可拦截模型调用与工具执行的前后
 */
public interface ChainHandler {

    /**
     * 执行顺序，值越小越靠前。
     */
    default int sort() {
        return 0;
    }

    /**
     * 处理一次步骤。
     *
     * @param next 链路中的下一个处理器，需要放行时调用 next.run()
     */
    void run(AgentContext context, Runnable next);
}
