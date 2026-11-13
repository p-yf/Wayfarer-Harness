package com.yf.wayfarer.session;

/**
 * 一条被卸载的消息。
 *
 * @author yyf
 * @description 记录卸载 id 与原始全文，写入会话对应的 jsonl 文件
 */
public record UnloadedMessage(String uuid, String content) {
}
