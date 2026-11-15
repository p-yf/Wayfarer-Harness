package com.yf.wayfarer.agent.agentchain;

import com.yf.wayfarer.change.ChangeRecorder;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.message.AgentMessage;

import java.util.function.Consumer;

/**
 * 运行级的文件变更记录与汇总处理器。
 *
 * @author yyf
 * @description 运行开始时记下脏文件基线，主循环结束后把整轮改动提交成一条记录，并把汇总消息推进汇聚口。
 * 收尾写在 finally 里，因此正常结束、异常、被取消都会执行；取消后汇聚口已断开，
 * 汇总消息会静默丢弃，页面刷新时用查询接口还原气泡
 */
public final class ChangeSummaryHandler implements ChainHandler {

    private final ChangeRecorder changeRecorder;

    public ChangeSummaryHandler(ChangeRecorder changeRecorder) {
        this.changeRecorder = changeRecorder;
    }

    /**
     * 位于最外层：前置逻辑在整轮运行之前执行，后置逻辑在主循环之后执行。
     */
    @Override
    public int sort() {
        return Integer.MIN_VALUE;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        ChangeRecorder.Run run = changeRecorder.begin(context);
        try {
            next.run();
        } finally {
            changeRecorder.finish(context, run);
            AgentMessage summary = changeRecorder.summary(context);
            Consumer<AgentMessage> sink = context.agentMsgSink();
            if (summary != null && sink != null) {
                sink.accept(summary);
            }
        }
    }
}
