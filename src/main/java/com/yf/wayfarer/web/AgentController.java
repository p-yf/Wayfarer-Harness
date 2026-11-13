package com.yf.wayfarer.web;

import com.yf.wayfarer.loop.AgentMsg;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * agent 接口。
 *
 * @author yyf
 * @description 提供按请求体中的会话 id 流式运行与终止运行两个接口
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * 以 server-sent events 流式返回 agent 消息。
     * 请求体需包含 sessionId，可选 agentName 与任意 store 状态。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentMsg> stream(@RequestBody Map<String, Object> payload) {
        Object rawSessionId = payload.get("sessionId");
        if (rawSessionId == null || String.valueOf(rawSessionId).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少参数：sessionId");
        }
        String sessionId = String.valueOf(rawSessionId);
        if (agentService.isRunning(sessionId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话正在运行：" + sessionId);
        }
        return agentService.stream(payload);
    }

    /**
     * 终止指定会话的运行。
     */
    @PostMapping("/stop/{sessionId}")
    public Mono<Map<String, Object>> stop(@PathVariable String sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("stopped", agentService.cancel(sessionId));
        return Mono.just(result);
    }
}
