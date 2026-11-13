package com.yf.wayfarer.toolcall;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author yyf
 * @description 校验工具解析成 ToolDefinition 的结构以及反射调用结果
 */
class ToolCallTest {

    /**
     * @author yyf
     * @description 测试用工具集
     */
    static class DemoTools {

        @Tool(description = "查询天气")
        public String getWeather(@ToolParam(description = "城市名") String city,
                                 @ToolParam(description = "是否包含预报") boolean forecast) {
            return city + "-" + forecast;
        }

        @Tool(name = "add", description = "两数相加")
        public int add(@ToolParam(description = "第一个数") int a,
                       @ToolParam(description = "第二个数") int b) {
            return a + b;
        }

        public String notATool(String value) {
            return value;
        }
    }

    /**
     * @author yyf
     * @description 第二个测试用工具集
     */
    static class OtherTools {

        @Tool(description = "获取当前时间")
        public String now() {
            return "now";
        }
    }

    @Test
    void 一次解析多个工具对象() {
        ToolCall tools = new ToolCall();
        List<ToolDefinition> definitions = tools.parse(new DemoTools(), new OtherTools());
        assertEquals(3, definitions.size());
        assertTrue(definitions.stream().anyMatch(definition -> "now".equals(definition.name())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 解析出所有工具及其参数结构() {
        List<ToolDefinition> definitions = new ToolCall().parse(new DemoTools());
        assertEquals(2, definitions.size());

        ToolDefinition weather = definitions.stream()
                .filter(definition -> "getWeather".equals(definition.name()))
                .findFirst()
                .orElseThrow();
        assertEquals("查询天气", weather.description());
        assertEquals("object", weather.parameters().get("type"));
        assertEquals(false, weather.parameters().get("additionalProperties"));
        assertEquals(List.of("city", "forecast"), weather.parameters().get("required"));

        Map<String, Object> properties = (Map<String, Object>) weather.parameters().get("properties");
        Map<String, Object> city = (Map<String, Object>) properties.get("city");
        assertEquals("string", city.get("type"));
        assertEquals("城市名", city.get("description"));
        Map<String, Object> forecast = (Map<String, Object>) properties.get("forecast");
        assertEquals("boolean", forecast.get("type"));

        ToolDefinition add = definitions.stream()
                .filter(definition -> "add".equals(definition.name()))
                .findFirst()
                .orElseThrow();
        assertEquals("两数相加", add.description());
        assertFalse(definitions.stream().anyMatch(definition -> "notATool".equals(definition.name())));
    }

    @Test
    void 按名称与参数调用工具() {
        ToolCall tools = new ToolCall();
        List<ToolDefinition> definitions = tools.parse(new DemoTools());
        assertEquals(2, definitions.size());

        Object weather = tools.call(new ToolCallInfo("call_1", "getWeather",
                "{\"city\":\"北京\",\"forecast\":true}"));
        assertEquals("北京-true", weather);

        Object sum = tools.call(new ToolCallInfo("call_2", "add", "{\"a\":2,\"b\":3}"));
        assertEquals(5, sum);
    }

    @Test
    void 未知工具或非法参数时抛出异常() {
        ToolCall tools = new ToolCall();
        tools.parse(new DemoTools());

        assertThrows(IllegalArgumentException.class,
                () -> tools.call(new ToolCallInfo("call_1", "notExist", "{}")));
        assertThrows(IllegalArgumentException.class,
                () -> tools.call(new ToolCallInfo("call_2", "add", "not-json")));
        assertThrows(IllegalArgumentException.class,
                () -> tools.call(new ToolCallInfo("call_3", "add", "{\"a\":1}")));
    }
}
