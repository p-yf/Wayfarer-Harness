package com.yf.wayfarer.web;

import com.yf.wayfarer.agent.Agent;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.AgentMsg;
import com.yf.wayfarer.model.ModelConfig;
import com.yf.wayfarer.model.openai.OpenAIChatModel;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.toolcall.ToolCall;
import org.reactivestreams.Subscription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * agent 运行服务。
 *
 * @author yyf
 * @description 把请求参数装配成上下文交给 agent 流式运行，并按会话 id 维护运行中的订阅以支持中途终止
 */
@Service
public class AgentService {

    private final Session session;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Double temperature;
    private final Long maxTokens;
    private final Integer maxTurns;
    private final ConcurrentHashMap<String, Subscription> running = new ConcurrentHashMap<>();

    private volatile Agent agent;

    public AgentService(Session session,
                        @Value("${wayfarer.model.api-key:}") String apiKey,
                        @Value("${wayfarer.model.base-url:https://api.deepseek.com}") String baseUrl,
                        @Value("${wayfarer.model.name:deepseek-flash}") String model,
                        @Value("${wayfarer.model.temperature:#{null}}") Double temperature,
                        @Value("${wayfarer.model.max-tokens:#{null}}") Long maxTokens,
                        @Value("${wayfarer.agent.max-turns:#{null}}") Integer maxTurns) {
        this.session = session;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.maxTurns = maxTurns;
    }

    /**
     * 按会话 id 流式运行，请求参数写入上下文。
     */
    public Flux<AgentMsg> stream(String sessionId, Map<String, Object> payload) {
        AgentContext context = new AgentContext();
        if (payload != null) {
            payload.forEach(context::put);
        }
        return agent().stream(context, sessionId)
                .doOnSubscribe(subscription -> running.put(sessionId, subscription))
                .doFinally(signal -> running.remove(sessionId));
    }

    /**
     * 终止指定会话正在运行的任务，返回是否确有任务被终止。
     */
    public boolean cancel(String sessionId) {
        Subscription subscription = running.remove(sessionId);
        if (subscription == null) {
            return false;
        }
        subscription.cancel();
        return true;
    }

    public boolean isRunning(String sessionId) {
        return running.containsKey(sessionId);
    }

    /**
     * 首次使用时才创建 agent，未配置密钥时不影响其他接口。
     */
    private Agent agent() {
        Agent current = agent;
        if (current == null) {
            synchronized (this) {
                if (agent == null) {
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new IllegalStateException("未配置 wayfarer.model.api-key");
                    }
                    ModelConfig config = new ModelConfig(apiKey, baseUrl, model, temperature, maxTokens);
                    agent = new Agent(new OpenAIChatModel(config), List.of(), new ToolCall(), maxTurns, session);
                }
                current = agent;
            }
        }
        return current;
    }
}
