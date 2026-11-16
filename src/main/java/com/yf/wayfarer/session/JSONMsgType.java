package com.yf.wayfarer.session;

/**
 * 会话消息类型。
 *
 * @author yyf
 * @description 文件元数据（METADATA）、对话消息（USER/ASSISTANT/TOOL）、自定义状态消息（CUSTOM）、
 * 压缩快照消息（COMPACT）、运行附加数据（EXTRADATA）与轮次结束标记（TRACE_END）
 */
public enum JSONMsgType {
    METADATA,
    USER,
    ASSISTANT,
    TOOL,
    CUSTOM,
    COMPACT,
    EXTRADATA,
    TRACE_END
}
