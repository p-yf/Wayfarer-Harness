package com.yf.wayfarer.web;

import com.yf.wayfarer.agent.Agent;
import com.yf.wayfarer.agent.toolchain.UnloadTools;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.AgentMsg;
import com.yf.wayfarer.model.ModelConfig;
import com.yf.wayfarer.model.openai.OpenAIChatModel;
import com.yf.wayfarer.prompt.SystemPromptStore;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.UnloadedMessageStore;
import com.yf.wayfarer.toolcall.ToolCall;
import com.yf.wayfarer.toolcall.ToolDefinition;
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
 * @description 把请求参数装配成上下文，注入会话 id 与系统提示词后交给 agent 流式运行，
 * 并按会话 id 维护运行中的订阅以支持中途终止
 */
@Service
public class AgentService {

    private final Session session;
    private final UnloadedMessageStore unloadedStore;
    private final SystemPromptStore promptStore;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Double temperature;
    private final Long maxTokens;
    private final Integer maxTurns;
    private final String defaultAgentName;
    private final int contextWindow;
    private final ConcurrentHashMap<String, Subscription> running = new ConcurrentHashMap<>();

    private volatile Agent agent;

    public AgentService(Session session,
                        UnloadedMessageStore unloadedStore,
                        SystemPromptStore promptStore,
                        @Value("${wayfarer.model.api-key:}") String apiKey,
                        @Value("${wayfarer.model.base-url:https://api.deepseek.com}") String baseUrl,
                        @Value("${wayfarer.model.name:deepseek-flash}") String model,
                        @Value("${wayfarer.model.temperature:#{null}}") Double temperature,
                        @Value("${wayfarer.model.max-tokens:#{null}}") Long maxTokens,
                        @Value("${wayfarer.agent.max-turns:#{null}}") Integer maxTurns,
                        @Value("${wayfarer.agent.name:wayfarer}") String defaultAgentName,
                        @Value("${wayfarer.agent.context-window:128000}") int contextWindow) {
        this.session = session;
        this.unloadedStore = unloadedStore;
        this.promptStore = promptStore;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.maxTurns = maxTurns;
        this.defaultAgentName = defaultAgentName;
        this.contextWindow = contextWindow;
    }

    /**
     * 按请求体中的 sessionId 流式运行，其余参数原样写入上下文。
     */
    public Flux<AgentMsg> stream(Map<String, Object> payload) {
        String sessionId = text(payload.get("sessionId"));
        if (sessionId == null) {
            throw new IllegalArgumentException("缺少参数：sessionId");
        }
        String agentName = text(payload.get(AgentContext.AGENT_NAME));
        if (agentName == null) {
            agentName = defaultAgentName;
        }

        AgentContext context = new AgentContext();
        payload.forEach((key, value) -> {
            if (!AgentContext.AGENT_NAME.equals(key)) {
                context.put(key, value);
            }
        });
        context.put(AgentContext.SESSION_ID, sessionId);
        context.put(AgentContext.AGENT_NAME, agentName);

        // 运行前加载并拼接系统提示词，作为首条 system 消息
        String systemPrompt = promptStore.load(agentName);
        if (!systemPrompt.isBlank()) {
            context.put(AgentContext.SYSTEM_PROMPT, systemPrompt);
        }

        return agent().stream(context)
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
                    ToolCall toolCall = new ToolCall();
                    List<ToolDefinition> tools = toolCall.parse(new UnloadTools(unloadedStore));
                    agent = new Agent(new OpenAIChatModel(config), tools, toolCall, maxTurns,
                            session, unloadedStore, contextWindow);
                }
                current = agent;
            }
        }
        return current;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
