package com.yf.wayfarer.web;

import com.yf.wayfarer.skill.SkillFileSystem;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 技能目录接口。
 *
 * @author yyf
 * @description 以文件系统方式浏览和增删改 skills 目录：列目录、读写文件、建目录、删除文件或目录
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillFileSystem files;

    public SkillController(SkillFileSystem files) {
        this.files = files;
    }

    /**
     * 列出目录内容，path 为空时列出 skills 根目录。
     */
    @GetMapping("/tree")
    public SkillFileSystem.Tree tree(@RequestParam(name = "path", required = false) String path) {
        return files.list(path);
    }

    /**
     * 读取文本文件。
     */
    @GetMapping("/file")
    public Map<String, Object> read(@RequestParam("path") String path) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        result.put("content", files.read(path));
        return result;
    }

    /**
     * 写入文本文件，父目录不存在时自动创建。
     */
    @PutMapping("/file")
    public Map<String, Object> write(@RequestBody Map<String, Object> body) {
        String path = text(body.get("path"));
        files.write(path, body.get("content") == null ? "" : String.valueOf(body.get("content")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        result.put("saved", true);
        return result;
    }

    /**
     * 新建目录。
     */
    @PostMapping("/dir")
    public Map<String, Object> mkdir(@RequestBody Map<String, Object> body) {
        String path = text(body.get("path"));
        files.mkdir(path);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        result.put("created", true);
        return result;
    }

    /**
     * 删除文件或整个目录。
     */
    @DeleteMapping("/entry")
    public Map<String, Object> delete(@RequestParam("path") String path) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", files.delete(path));
        return result;
    }

    private static String text(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("缺少参数：path");
        }
        return String.valueOf(value).trim();
    }
}
