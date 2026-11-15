package com.yf.wayfarer.loop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 环绕式责任链。
 *
 * @author yyf
 * @description 按 sort 升序稳定排序后同步执行，每个处理器通过调用 next 放行到后续处理器。
 * 模型链、工具链与运行级链共用这一套抽象：运行级链的末端是主循环，因此它的后置逻辑
 * 在 next 返回后执行，取消或异常时同样能靠 try/finally 收尾
 */
public final class LoopChain {

    private final List<ChainHandler> handlers;

    private LoopChain(List<ChainHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    public static LoopChain of(ChainHandler... handlers) {
        List<ChainHandler> sorted = new ArrayList<>(List.of(handlers));
        sorted.sort(Comparator.comparingInt(ChainHandler::sort));
        return new LoopChain(sorted);
    }

    public void run(AgentContext context) {
        run(context, () -> {
        });
    }

    /**
     * 同步执行整条链，末端执行 next。
     */
    public void run(AgentContext context, Runnable next) {
        runFrom(0, context, next);
    }

    private void runFrom(int index, AgentContext context, Runnable next) {
        if (index >= handlers.size()) {
            next.run();
            return;
        }
        handlers.get(index).run(context, () -> runFrom(index + 1, context, next));
    }
}
