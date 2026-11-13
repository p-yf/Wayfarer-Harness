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
 * @description 提供按会话流式运行与终止运行两个接口
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
     */
    @PostMapping(value = "/stream/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentMsg> stream(@PathVariable String sessionId,
                                 @RequestBody(required = false) Map<String, Object> payload) {
        if (agentService.isRunning(sessionId)) {
            return Flux.error(new ResponseStatusException(HttpStatus.CONFLICT, "会话正在运行：" + sessionId));
        }
        return agentService.stream(sessionId, payload);
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
