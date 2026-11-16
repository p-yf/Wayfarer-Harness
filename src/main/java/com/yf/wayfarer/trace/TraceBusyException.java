package com.yf.wayfarer.trace;

/**
 * 会话正在运行另一轮时拒绝新请求。
 *
 * @author yyf
 * @description 根 trace 的准入异常，由 web 层转成 TRACE_BUSY 事件，提示稍后重试
 */
public class TraceBusyException extends RuntimeException {

    private final String runningTraceId;

    public TraceBusyException(String sessionId, String runningTraceId) {
        super("上一轮收尾未执行完成，请稍后（sessionId=" + sessionId + "）");
        this.runningTraceId = runningTraceId;
    }

    public String runningTraceId() {
        return runningTraceId;
    }
}
