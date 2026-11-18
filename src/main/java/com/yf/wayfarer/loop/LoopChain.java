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

    /**
     * 在现有链上追加处理器，返回重新按 sort 排序的新链（原链不可变、不受影响）。
     *
     * <p>供子类蓝图在 super 装配结果上插入自己的 handler（如 RsiBlueprint 在标准模型链上
     * 插入记忆召回、在标准运行链上追加沉淀入口），避免整条链的手工复制。</p>
     */
    public LoopChain then(ChainHandler... extra) {
        List<ChainHandler> merged = new ArrayList<>(handlers);
        merged.addAll(List.of(extra));
        merged.sort(Comparator.comparingInt(ChainHandler::sort));
        return new LoopChain(merged);
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
