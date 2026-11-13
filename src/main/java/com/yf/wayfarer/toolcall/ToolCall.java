package com.yf.wayfarer.toolcall;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用的解析与执行。
 *
 * @author yyf
 * @description 解析工具对象时注册工具名与调用实例，并按 ToolCallInfo 反射调用
 */
public final class ToolCall {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, ToolMethod> tools = new LinkedHashMap<>();

    /**
     * 解析多个工具对象，注册工具并返回工具定义。
     */
    public List<ToolDefinition> parse(Object... toolObjects) {
        if (toolObjects == null || toolObjects.length == 0) {
            throw new IllegalArgumentException("工具对象不能为空");
        }
        tools.clear();
        List<ToolDefinition> definitions = new ArrayList<>();
        for (Object toolObject : toolObjects) {
            if (toolObject == null) {
                throw new IllegalArgumentException("工具对象不能为空");
            }
            for (Method method : toolObject.getClass().getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) {
                    continue;
                }
                String name = tool.name().isBlank() ? method.getName() : tool.name();
                tools.put(name, new ToolMethod(method, toolObject));
                definitions.add(toDefinition(method, name, tool.description()));
            }
        }
        return definitions;
    }

    /**
     * 调用一次已注册的工具。
     *
     * @return 工具方法的返回值，void 方法返回 null
     */
    public Object call(ToolCallInfo info) {
        if (info == null || info.name() == null || info.name().isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        ToolMethod tool = tools.get(info.name());
        if (tool == null) {
            throw new IllegalArgumentException("未找到工具: " + info.name());
        }

        Map<String, Object> arguments = parseArguments(info.arguments());
        Parameter[] parameters = tool.method().getParameters();
        Object[] values = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i].getType();
            if (!arguments.containsKey(parameters[i].getName())) {
                if (type.isPrimitive()) {
                    throw new IllegalArgumentException("缺少参数: " + parameters[i].getName());
                }
                continue;
            }
            values[i] = convert(arguments.get(parameters[i].getName()), type);
        }

        try {
            tool.method().setAccessible(true);
            return tool.method().invoke(tool.instance(), values);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("工具执行失败: " + info.name(), cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("工具调用失败: " + info.name(), e);
        }
    }

    private static ToolDefinition toDefinition(Method method, String name, String description) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", schemaType(parameter.getType()));
            ToolParam toolParam = parameter.getAnnotation(ToolParam.class);
            if (toolParam != null && !toolParam.description().isBlank()) {
                schema.put("description", toolParam.description());
            }
            properties.put(parameter.getName(), schema);
            required.add(parameter.getName());
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);
        parameters.put("additionalProperties", false);
        return new ToolDefinition(name, description, parameters);
    }

    private static String schemaType(Class<?> type) {
        if (type == String.class || type == char.class || type == Character.class) {
            return "string";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class || type == byte.class || type == Byte.class) {
            return "integer";
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (List.class.isAssignableFrom(type)) {
            return "array";
        }
        return "string";
    }

    private static Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(arguments, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("工具参数不是合法 JSON: " + arguments, e);
        }
    }

    private static Object convert(Object value, Class<?> type) {
        if (value == null) {
            return null;
        }
        if (type == String.class) {
            return value instanceof String string ? string : String.valueOf(value);
        }
        return JSON.convertValue(value, type);
    }

    /**
     * @author yyf
     * @description 已注册的工具方法与调用实例
     */
    private record ToolMethod(Method method, Object instance) {
    }
}
