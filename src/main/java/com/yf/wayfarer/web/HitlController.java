package com.yf.wayfarer.web;

import com.yf.wayfarer.hitl.HitlService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工确认接口。
 *
 * @author yyf
 * @description 列出等待确认的工具调用，并按请求体中的 toolCallId 把整个请求体写回对应等待项
 */
@RestController
@RequestMapping("/api/hitl")
public class HitlController {

    private final HitlService hitlService;

    public HitlController(HitlService hitlService) {
        this.hitlService = hitlService;
    }

    /**
     * 待确认列表，agent 为空时返回全部。
     */
    @GetMapping("/pending")
    public List<HitlService.Pending> pending(@RequestParam(required = false) String agent) {
        return hitlService.pending(agent);
    }

    /**
     * 写入确认决定。请求体需包含 toolCallId，整个请求体作为决定交给工具链。
     */
    @PostMapping
    public Map<String, Object> complete(@RequestBody Map<String, Object> body) {
        Object raw = body.get("toolCallId");
        String toolCallId = raw == null ? null : String.valueOf(raw).trim();
        if (toolCallId == null || toolCallId.isEmpty()) {
            throw new IllegalArgumentException("缺少参数：toolCallId");
        }
        if (!hitlService.complete(toolCallId, body)) {
            throw new IllegalArgumentException("没有等待中的确认请求：" + toolCallId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("toolCallId", toolCallId);
        result.put("delivered", true);
        return result;
    }
}
