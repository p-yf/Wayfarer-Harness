package com.yf.wayfarer.toolcall;

import java.util.Map;

/**
 * 可被模型调用的工具定义。
 *
 * @author yyf
 * @description 描述工具名、用途与 JSON Schema 参数，随请求下发给模型
 */
public record ToolDefinition(String name, String description, Map<String, Object> parameters) {

    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
