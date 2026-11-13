package com.yf.wayfarer.config;

import com.yf.wayfarer.model.ModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置存储测试，使用真实文件系统。
 *
 * @author yyf
 * @description 覆盖保存、读取、列举、覆盖、删除与启用配置的选取
 */
class ConfigStoreTest {

    @TempDir
    Path root;

    private ConfigStore<ModelConfig> store;

    @BeforeEach
    void setUp() {
        store = new ConfigStore<>(root.resolve("modelconfig"), ModelConfig.class);
    }

    @Test
    void 保存后可读取并列出() {
        store.save("默认", config("deepseek-flash", true));

        assertEquals("deepseek-flash", store.read("默认").model());
        Map<String, ModelConfig> all = store.list();
        assertEquals(1, all.size());
        assertTrue(all.containsKey("默认"));
    }

    @Test
    void 同名配置被覆盖() {
        store.save("默认", config("deepseek-flash", true));
        store.save("默认", config("deepseek-chat", true));

        assertEquals("deepseek-chat", store.read("默认").model());
        assertEquals(1, store.list().size());
    }

    @Test
    void 取第一个启用的配置() {
        store.save("a-关闭", config("deepseek-flash", false));
        store.save("b-开启", config("deepseek-chat", true));

        ModelConfig enabled = store.first(ModelConfig::enabled);
        assertEquals("deepseek-chat", enabled.model());
    }

    @Test
    void 没有启用的配置时返回null() {
        store.save("a-关闭", config("deepseek-flash", false));

        assertNull(store.first(ModelConfig::enabled));
    }

    @Test
    void 删除配置() {
        store.save("默认", config("deepseek-flash", true));

        assertTrue(store.delete("默认"));
        assertFalse(store.delete("默认"));
        assertTrue(store.list().isEmpty());
    }

    @Test
    void 读取不存在的配置时报错() {
        assertThrows(IllegalArgumentException.class, () -> store.read("不存在"));
        assertThrows(IllegalArgumentException.class, () -> store.save(" ", config("deepseek-flash", true)));
    }

    private static ModelConfig config(String model, boolean enabled) {
        return new ModelConfig("test-key", ModelConfig.DEFAULT_BASE_URL, model,
                null, null, "high", enabled);
    }
}
