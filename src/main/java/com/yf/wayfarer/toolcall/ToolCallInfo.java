package com.yf.wayfarer.toolcall;

/**
 * 模型请求的一次完整工具调用。
 *
 * @author yyf
 * @description 保存工具调用的 id、工具名与参数 JSON 文本
 */
public record ToolCallInfo(String id, String name, String arguments) {
}
