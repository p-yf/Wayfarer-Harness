package com.yf.wayfarer.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author yyf
 * @description 校验 ModelConfig 与消息类型的构造约束
 */
class ModelConfigTest {

    @Test
    void 缺少apiKey或model时构造失败() {
        assertThrows(IllegalArgumentException.class, () -> new ModelConfig(" ", "deepseek-flash"));
        assertThrows(IllegalArgumentException.class, () -> new ModelConfig(null, "deepseek-flash"));
        assertThrows(IllegalArgumentException.class, () -> new ModelConfig("test-key", " "));
        assertThrows(IllegalArgumentException.class, () -> new ModelConfig("test-key", null));
    }

    @Test
    void 未提供地址时使用默认地址且采样参数可为空() {
        ModelConfig config = new ModelConfig("test-key", "deepseek-flash");
        assertEquals(ModelConfig.DEFAULT_BASE_URL, config.baseUrl());
        assertEquals("deepseek-flash", config.model());
        assertNull(config.temperature());
        assertNull(config.maxTokens());
    }

    @Test
    void 保存采样参数并校验取值范围() {
        ModelConfig config = new ModelConfig(
                "test-key", "http://localhost:8080/v1", "deepseek-flash", 0.3, 128L);
        assertEquals(0.3, config.temperature());
        assertEquals(128L, config.maxTokens());

        assertThrows(IllegalArgumentException.class,
                () -> new ModelConfig("k", "m", -0.1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelConfig("k", "m", 2.1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelConfig("k", "m", null, 0L));
    }

    @Test
    void 思考强度归一化并默认开启() {
        assertEquals("high", new ModelConfig("test-key", "deepseek-flash").reasoningEffort());
        assertEquals("low", effortOf("minimal"));
        assertEquals("low", effortOf("low"));
        assertEquals("high", effortOf("medium"));
        assertEquals("high", effortOf("xhigh"));
        assertEquals("none", effortOf("none"));
        assertEquals("max", effortOf("max"));
        assertThrows(IllegalArgumentException.class, () -> effortOf("超高"));
        assertTrue(new ModelConfig("test-key", "deepseek-flash").enabled());
    }

    private static String effortOf(String effort) {
        return new ModelConfig("test-key", ModelConfig.DEFAULT_BASE_URL, "deepseek-flash",
                null, null, effort, true).reasoningEffort();
    }

    @Test
    void 空消息列表被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest(null));
    }

    @Test
    void tool消息必须携带toolCallId() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChatMessage(ChatMessage.Role.TOOL, "结果"));
        ChatMessage message = new ChatMessage(ChatMessage.Role.TOOL, "结果", "call_1");
        assertEquals("call_1", message.toolCallId());
    }
}
