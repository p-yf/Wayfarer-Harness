package com.yf.wayfarer.web;

import com.yf.wayfarer.prompt.SystemPromptFile;
import com.yf.wayfarer.prompt.SystemPromptStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统提示词接口。
 *
 * @author yyf
 * @description 按 agent 名称维护提示词，文件名为 agent名称-提示词名称-顺序.md
 */
@RestController
@RequestMapping("/api/prompts")
public class SystemPromptController {

    private final SystemPromptStore store;

    public SystemPromptController(SystemPromptStore store) {
        this.store = store;
    }

    /**
     * 列出指定 agent 的全部提示词，按顺序升序。
     */
    @GetMapping
    public List<SystemPromptFile> list(@RequestParam("agent") String agent) {
        return store.list(agent);
    }

    /**
     * 读取单个提示词。
     */
    @GetMapping("/{agent}/{name}/{order}")
    public Map<String, Object> read(@PathVariable("agent") String agent,
                                    @PathVariable("name") String name,
                                    @PathVariable("order") int order) {
        String content = store.read(agent, name, order);
        if (content == null) {
            throw new IllegalArgumentException("提示词不存在：" + fileName(agent, name, order));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", agent);
        result.put("name", name);
        result.put("order", order);
        result.put("content", content);
        return result;
    }

    /**
     * 新建或覆盖一个提示词。
     */
    @PutMapping("/{agent}/{name}/{order}")
    public Map<String, Object> save(@PathVariable("agent") String agent,
                                    @PathVariable("name") String name,
                                    @PathVariable("order") int order,
                                    @RequestBody Map<String, Object> body) {
        Object content = body.get("content");
        store.save(agent, name, order, content == null ? "" : String.valueOf(content));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", agent);
        result.put("name", name);
        result.put("order", order);
        return result;
    }

    /**
     * 删除一个提示词。
     */
    @DeleteMapping("/{agent}/{name}/{order}")
    public Map<String, Object> delete(@PathVariable("agent") String agent,
                                      @PathVariable("name") String name,
                                      @PathVariable("order") int order) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", store.delete(agent, name, order));
        return result;
    }

    private static String fileName(String agent, String name, int order) {
        return agent + "-" + name + "-" + order + ".md";
    }
}
