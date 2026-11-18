package com.yf.wayfarer.web;

import com.yf.wayfarer.agent.Agent;
import com.yf.wayfarer.agent.AgentBlueprint;
import com.yf.wayfarer.agent.AgentCatalog;
import com.yf.wayfarer.agent.AgentDependencies;
import com.yf.wayfarer.agent.WayfarerBlueprint;
import com.yf.wayfarer.agent.WayfarerProBlueprint;
import com.yf.wayfarer.change.ChangeRecorder;
import com.yf.wayfarer.config.ConfigStore;
import com.yf.wayfarer.hitl.HitlService;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.modelchain.MemoryCompactConfig;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ModelConfig;
import com.yf.wayfarer.model.openai.OpenAIChatModel;
import com.yf.wayfarer.prompt.SystemPromptStore;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.UnloadedMessageStore;
import com.yf.wayfarer.skill.SkillStore;
import com.yf.wayfarer.subagent.SubAgentManager;
import com.yf.wayfarer.trace.TraceStatus;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * agent 运行服务。
 *
 * @author yyf
 * @description 每次运行都先读取模型配置与记忆压缩配置，按请求中的 agentName 选择蓝图装配链路后流式运行，
 * 并按会话 id 维护运行中的订阅以支持中途终止
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final Session session;
    private final UnloadedMessageStore unloadedStore;
    private final SystemPromptStore promptStore;
    private final ConfigStore<ModelConfig> modelConfigStore;
    private final ConfigStore<MemoryCompactConfig> compactConfigStore;
    private final HitlService hitlService;
    private final SkillStore skillStore;
    private final ChangeRecorder changeRecorder;
    private final TraceStatus traceStatus;
    private final SubAgentManager subAgentManager;
    private final AgentCatalog agentCatalog;
    private final String rootWorkspace;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Double temperature;
    private final Long maxTokens;
    private final Integer maxTurns;
    private final String defaultAgentName;
    private final int maxDepth;
    private final long subAgentTimeoutSeconds;
    private final long finishWaitSeconds;
    private final ConcurrentHashMap<String, Subscription> running = new ConcurrentHashMap<>();

    private volatile OpenAIChatModel chatModel;
    private volatile ModelConfig chatModelConfig;

    public AgentService(Session session,
                        UnloadedMessageStore unloadedStore,
                        SystemPromptStore promptStore,
                        ConfigStore<ModelConfig> modelConfigStore,
                        ConfigStore<MemoryCompactConfig> compactConfigStore,
                        HitlService hitlService,
                        SkillStore skillStore,
                        ChangeRecorder changeRecorder,
                        TraceStatus traceStatus,
                        SubAgentManager subAgentManager,
                        AgentCatalog agentCatalog,
                        @Value("${wayfarer.root-workspace:./}") String rootWorkspace,
                        @Value("${wayfarer.model.api-key:}") String apiKey,
                        @Value("${wayfarer.model.base-url:https://api.deepseek.com}") String baseUrl,
                        @Value("${wayfarer.model.name:deepseek-flash}") String model,
                        @Value("${wayfarer.model.temperature:#{null}}") Double temperature,
                        @Value("${wayfarer.model.max-tokens:#{null}}") Long maxTokens,
                        @Value("${wayfarer.agent.max-turns:#{null}}") Integer maxTurns,
                        @Value("${wayfarer.agent.name:wayfarer}") String defaultAgentName,
                        @Value("${wayfarer.subagent.max-depth:2}") int maxDepth,
                        @Value("${wayfarer.subagent.task-timeout-seconds:600}") long subAgentTimeoutSeconds,
                        @Value("${wayfarer.subagent.finish-wait-seconds:10}") long finishWaitSeconds) {
        this.session = session;
        this.unloadedStore = unloadedStore;
        this.promptStore = promptStore;
        this.modelConfigStore = modelConfigStore;
        this.compactConfigStore = compactConfigStore;
        this.hitlService = hitlService;
        this.skillStore = skillStore;
        this.changeRecorder = changeRecorder;
        this.traceStatus = traceStatus;
        this.subAgentManager = subAgentManager;
        // 蓝图目录改为共享 bean：RsiController 的 agent 白名单与这里保持同一份注册表
        this.agentCatalog = agentCatalog;
        this.rootWorkspace = Path.of(rootWorkspace).toAbsolutePath().normalize().toString();
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.maxTurns = maxTurns;
        this.defaultAgentName = defaultAgentName;
        this.maxDepth = maxDepth;
        this.subAgentTimeoutSeconds = subAgentTimeoutSeconds;
        this.finishWaitSeconds = finishWaitSeconds;
    }

    /**
     * 按请求体中的 sessionId 流式运行，其余参数原样写入上下文。
     */
    public Flux<AgentMessage> stream(Map<String, Object> payload) {
        String sessionId = text(payload.get("sessionId"));
        if (sessionId == null) {
            throw new IllegalArgumentException("缺少参数：sessionId");
        }
        String agentName = text(payload.get(AgentContext.AGENT_NAME));
        if (agentName == null) {
            agentName = defaultAgentName;
        }
        if (!agentCatalog.supports(agentName)) {
            throw new IllegalArgumentException("未知 agent：" + agentName);
        }
        AgentBlueprint blueprint = agentCatalog.find(agentName);

        AgentContext context = new AgentContext();
        payload.forEach((key, value) -> {
            if (!AgentContext.AGENT_NAME.equals(key)) {
                context.put(key, value);
            }
        });
        context.put(AgentContext.SESSION_ID, sessionId);
        context.put(AgentContext.AGENT_NAME, blueprint.name());

        // 运行前把系统提示词按「名称-顺序」放入状态，由模型处理器拼接后作为首条 system 消息
        Map<String, String> prompts = promptStore.loadMap(blueprint.name());
        if (!prompts.isEmpty()) {
            context.put(AgentContext.SYSTEM_PROMPT, prompts);
        }
        context.put(AgentContext.ROOT_WORKSPACE, rootWorkspace);

        log.info("运行开始 sessionId={} agentName={} workspace={} 提示词条数={}",
                sessionId, blueprint.name(), text(payload.get(AgentContext.WORKSPACE)), prompts.size());

        return buildAgent(blueprint).stream(context)
                .doOnSubscribe(subscription -> running.put(sessionId, subscription))
                .doFinally(signal -> {
                    running.remove(sessionId);
                    log.info("运行结束 sessionId={} agentName={} 信号={}", sessionId, blueprint.name(), signal);
                });
    }

    /**
     * 终止指定会话正在运行的任务，返回是否确有任务被终止。
     *
     * <p>取消是协作式的：接口只发出终止请求，主循环收尾（子 Agent 收敛、提交本轮变更、落盘状态）
     * 由运行线程自行完成；页面侧通过轮询变更接口来获取收尾结果。</p>
     */
    public boolean cancel(String sessionId) {
        Subscription subscription = running.remove(sessionId);
        if (subscription == null) {
            return false;
        }
        subscription.cancel();
        // 主循环可能正阻塞等待子 Agent，先级联终止，避免收尾被拖到子任务超时
        subAgentManager.cancelSession(sessionId);
        return true;
    }

    public boolean isRunning(String sessionId) {
        return running.containsKey(sessionId) || traceStatus.isRunning(sessionId);
    }

    /**
     * 每次构建都重新读取配置：优先使用启用的配置文件，没有时回退到 yml 中的取值。
     */
    private Agent buildAgent(AgentBlueprint blueprint) {
        AgentDependencies deps = new AgentDependencies(session, unloadedStore, promptStore,
                chatModel(modelConfig()), compactConfig(), hitlService, skillStore, changeRecorder,
                traceStatus, subAgentManager, maxTurns, maxDepth, subAgentTimeoutSeconds, finishWaitSeconds);
        // 子 Agent 与父 Agent 共用同一套组件，名称固定派生，深度由子上下文控制
        deps.subAgentFactory((parent, agentId, viaToolCallId, task, historyExtend, extra) ->
                blueprint.buildSubAgent(parent, deps, agentId, viaToolCallId, task, historyExtend, extra));
        return blueprint.build(deps);
    }

    private ModelConfig modelConfig() {
        ModelConfig config = modelConfigStore.first(ModelConfig::enabled);
        if (config != null) {
            return config;
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置模型：请在 modelconfig 目录启用一个配置，或配置 wayfarer.model.api-key");
        }
        return new ModelConfig(apiKey, baseUrl, model, temperature, maxTokens);
    }

    private MemoryCompactConfig compactConfig() {
        MemoryCompactConfig config = compactConfigStore.first(MemoryCompactConfig::enabled);
        return config == null ? MemoryCompactConfig.defaults() : config;
    }

    /**
     * 模型客户端按配置复用，配置变化时重建，避免每次运行都新建连接池。
     */
    private OpenAIChatModel chatModel(ModelConfig config) {
        OpenAIChatModel current = chatModel;
        if (current != null && config.equals(chatModelConfig)) {
            return current;
        }
        synchronized (this) {
            if (chatModel == null || !config.equals(chatModelConfig)) {
                log.info("模型客户端建立 baseUrl={} model={}", config.baseUrl(), config.model());
                chatModel = new OpenAIChatModel(config);
                chatModelConfig = config;
            }
            return chatModel;
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
