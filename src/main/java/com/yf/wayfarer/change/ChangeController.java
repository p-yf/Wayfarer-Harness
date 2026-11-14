package com.yf.wayfarer.change;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 变更记录接口。
 *
 * @author yyf
 * @description 提供按会话/轮次查询变更、查看某次变更的 diff，以及撤销单次改动
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
     * 某次变更的 unified diff，path 为空时返回本次提交全部文件的补丁。
     */
    @GetMapping("/{commitId}/diff")
    public Map<String, Object> diff(@PathVariable("commitId") String commitId,
                                    @RequestParam(name = "path", required = false) String path) {
        return changeService.diff(commitId, path);
    }

    /**
     * 撤销某次变更；请求体传 path 时只撤销该文件，不传则撤销整次工具调用。
     */
    @PostMapping("/{commitId}/revert")
    public Map<String, Object> revert(@PathVariable("commitId") String commitId,
                                      @RequestBody(required = false) Map<String, Object> body) {
        Object path = body == null ? null : body.get("path");
        return changeService.revert(commitId, path == null ? null : String.valueOf(path));
    }
}
