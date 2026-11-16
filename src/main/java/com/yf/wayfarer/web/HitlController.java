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
     * 当前可展示的待确认项。同一会话一次只返回最早登记的一条，
     * 提交决定后下一条才会出现，避免前端一次弹出多张审批卡片。
     */
    @GetMapping("/pending")
    public List<HitlService.Pending> pending(@RequestParam(name = "session", required = false) String session,
                                             @RequestParam(name = "agent", required = false) String agent) {
        return hitlService.next(session, agent);
    }

    /**
     * 写入确认决定。请求体需包含 key（agentId/toolCallId 复合键）；
     * 旧调用只带 toolCallId 时按唯一后缀匹配。
     */
    @PostMapping
    public Map<String, Object> complete(@RequestBody Map<String, Object> body) {
        Object rawKey = body.get("key");
        Object rawId = body.get("toolCallId");
        String key = rawKey != null && !String.valueOf(rawKey).isBlank()
                ? String.valueOf(rawKey).trim()
                : rawId == null ? null : String.valueOf(rawId).trim();
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("缺少参数：key");
        }
        if (!hitlService.complete(key, body)) {
            throw new IllegalArgumentException("没有等待中的确认请求：" + key);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("delivered", true);
        return result;
    }
}
