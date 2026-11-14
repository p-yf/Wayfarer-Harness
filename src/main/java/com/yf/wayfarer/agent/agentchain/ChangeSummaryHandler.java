package com.yf.wayfarer.agent.agentchain;

import com.yf.wayfarer.agent.AgentHandler;
import com.yf.wayfarer.change.ChangeRecorder;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.message.AgentMessage;
import reactor.core.publisher.Flux;

/**
 * 运行结束后的文件变更汇总处理器。
 *
 * @author yyf
 * @description 作为 agent 运行级链的一环，在主循环把本轮消息全部推完后，
 * 把本轮变更整体作为一条消息追加到流末尾；变更逻辑因此留在链上，agent 无需依赖变更记录器
 */
public final class ChangeSummaryHandler implements AgentHandler {

    private final ChangeRecorder changeRecorder;

    public ChangeSummaryHandler(ChangeRecorder changeRecorder) {
        this.changeRecorder = changeRecorder;
    }

    /**
     * 位于最外层：后置逻辑在所有环节之后执行，保证汇总消息始终是流的最后一条。
     */
    @Override
    public int sort() {
        return Integer.MIN_VALUE;
    }

    @Override
    public Flux<AgentMessage> apply(AgentContext context, Flux<AgentMessage> next) {
        return next.concatWith(Flux.defer(() -> {
            AgentMessage summary = changeRecorder.summary(context);
            return summary == null ? Flux.empty() : Flux.just(summary);
        }));
    }
}
