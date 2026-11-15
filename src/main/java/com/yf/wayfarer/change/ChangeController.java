package com.yf.wayfarer.change;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 变更记录接口。
 *
 * @author yyf
 * @description 只提供查询：按会话/轮次列出本轮改动过的文件，以及某个文件在本轮里的净变化。
 * 文件回退不在页面提供，撤销只跟随会话 reset 一起发生
 */
@RestController
@RequestMapping("/api/changes")
public class ChangeController {

    private final ChangeService changeService;

    public ChangeController(ChangeService changeService) {
        this.changeService = changeService;
    }

    /**
     * 变更列表，新的在前。sessionId 为空时返回全部会话的记录。
     */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(name = "sessionId", required = false) String sessionId,
                                          @RequestParam(name = "traceId", required = false) String traceId,
                                          @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return changeService.list(sessionId, traceId, limit);
    }

    /**
     * 某个文件在本轮里的净变化，供右侧面板按文件展示 diff。
     */
    @GetMapping("/file-diff")
    public Map<String, Object> fileDiff(@RequestParam(name = "sessionId", required = false) String sessionId,
                                        @RequestParam(name = "traceId", required = false) String traceId,
                                        @RequestParam(name = "path") String path) {
        return changeService.fileDiff(sessionId, traceId, path);
    }
}
