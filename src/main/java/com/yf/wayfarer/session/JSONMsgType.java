package com.yf.wayfarer.session;

/**
 * 会话消息类型。
 *
 * @author yyf
 * @description 对话消息（USER/ASSISTANT/TOOL）、自定义状态消息（CUSTOM）与压缩快照消息（COMPACT）
 */
public enum JSONMsgType {
    USER,
    ASSISTANT,
    TOOL,
    CUSTOM,
    COMPACT
}
