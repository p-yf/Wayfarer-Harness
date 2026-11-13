package com.yf.wayfarer.web;

import com.yf.wayfarer.agent.modelchain.MemoryCompactConfig;
import com.yf.wayfarer.config.ConfigStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记忆压缩配置接口。
 *
 * @author yyf
 * @description 提供记忆压缩配置的增删改查，配置在 memorycompactconfig 目录下按名称保存为 jsonl 文件
 */
@RestController
@RequestMapping("/api/memory-compact-configs")
public class MemoryCompactConfigController {

    private final ConfigStore<MemoryCompactConfig> store;

    public MemoryCompactConfigController(ConfigStore<MemoryCompactConfig> store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, MemoryCompactConfig> list() {
        return store.list();
    }

    @GetMapping("/{name}")
    public MemoryCompactConfig read(@PathVariable String name) {
        return store.read(name);
    }

    /**
     * 新建或覆盖指定名称的配置。
     */
    @PutMapping("/{name}")
    public MemoryCompactConfig save(@PathVariable String name, @RequestBody MemoryCompactConfig config) {
        store.save(name, config);
        return config;
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name) {
        boolean deleted = store.delete(name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("deleted", deleted);
        return result;
    }
}
