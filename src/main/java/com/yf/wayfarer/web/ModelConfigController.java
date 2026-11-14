package com.yf.wayfarer.web;

import com.yf.wayfarer.config.ConfigStore;
import com.yf.wayfarer.model.ModelConfig;
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
 * 模型配置接口。
 *
 * @author yyf
 * @description 提供模型配置的增删改查，配置在 modelconfig 目录下按名称保存为 jsonl 文件
 */
@RestController
@RequestMapping("/api/model-configs")
public class ModelConfigController {

    private final ConfigStore<ModelConfig> store;

    public ModelConfigController(ConfigStore<ModelConfig> store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, ModelConfig> list() {
        return store.list();
    }

    @GetMapping("/{name}")
    public ModelConfig read(@PathVariable("name") String name) {
        return store.read(name);
    }

    /**
     * 新建或覆盖指定名称的配置。
     */
    @PutMapping("/{name}")
    public ModelConfig save(@PathVariable("name") String name, @RequestBody ModelConfig config) {
        store.save(name, config);
        return config;
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable("name") String name) {
        boolean deleted = store.delete(name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("deleted", deleted);
        return result;
    }
}
