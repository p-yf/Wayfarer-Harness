package com.yf.wayfarer.web;

import com.yf.wayfarer.loop.modelchain.MemoryCompactConfig;
import com.yf.wayfarer.agent.AgentBlueprint;
import com.yf.wayfarer.agent.AgentCatalog;
import com.yf.wayfarer.agent.WayfarerBlueprint;
import com.yf.wayfarer.agent.WayfarerProBlueprint;
import com.yf.wayfarer.config.ConfigStore;
import com.yf.wayfarer.hitl.HitlService;
import com.yf.wayfarer.model.ModelConfig;
import com.yf.wayfarer.prompt.SystemPromptStore;
import com.yf.wayfarer.session.JsonlSession;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.UnloadedMessageStore;
import com.yf.wayfarer.rsi.RsiPaths;
import com.yf.wayfarer.rsi.RsiProperties;
import com.yf.wayfarer.skill.SkillFileSystem;
import com.yf.wayfarer.skill.SkillStore;
import com.yf.wayfarer.subagent.SubAgentManager;
import com.yf.wayfarer.trace.TraceStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

/**
 * web 层装配。
 *
 * @author yyf
 * @description 按配置创建会话存储、卸载消息存储与系统提示词存储
 */
@Configuration
public class SessionConfiguration {

    @Bean
    public Session session(@Value("${wayfarer.session.root:./sessions}") String root,
                           @Value("${wayfarer.session.compact-threshold:0}") int compactThreshold) {
        return new JsonlSession(Path.of(root), compactThreshold);
    }

    @Bean
    public UnloadedMessageStore unloadedMessageStore(
            @Value("${wayfarer.session.root:./sessions}") String root) {
        // 卸载消息存放在各会话文件夹内，与主消息文件同级
        return new UnloadedMessageStore(Path.of(root));
    }

    @Bean
    public SystemPromptStore systemPromptStore(
            @Value("${wayfarer.prompt.root:./systemprompt}") String root) {
        return new SystemPromptStore(Path.of(root));
    }

    @Bean
    public HitlService hitlService(
            @Value("${wayfarer.hitl.timeout-seconds:300}") long timeoutSeconds) {
        return new HitlService(timeoutSeconds);
    }

    @Bean
    public SkillFileSystem skillFileSystem(@Value("${wayfarer.root-workspace:./}") String root) {
        return new SkillFileSystem(Path.of(root).resolve("skills"));
    }

    @Bean
    public SkillStore skillStore(SkillFileSystem files) {
        return new SkillStore(files);
    }

    @Bean
    public ConfigStore<ModelConfig> modelConfigStore(
            @Value("${wayfarer.model-config.root:./modelconfig}") String root) {
        return new ConfigStore<>(Path.of(root), ModelConfig.class);
    }

    @Bean
    public ConfigStore<MemoryCompactConfig> compactConfigStore(
            @Value("${wayfarer.memory-compact-config.root:./memorycompactconfig}") String root) {
        return new ConfigStore<>(Path.of(root), MemoryCompactConfig.class);
    }

    @Bean
    public TraceStatus traceStatus(Session session) {
        // 准入状态需要读会话文件做崩溃恢复，因此依赖 Session
        return new TraceStatus(session);
    }

    @Bean
    public SubAgentManager subAgentManager(
            @Value("${wayfarer.subagent.max-children:3}") int maxChildren,
            @Value("${wayfarer.subagent.task-timeout-seconds:600}") long taskTimeoutSeconds) {
        return new SubAgentManager(maxChildren, taskTimeoutSeconds);
    }

    /**
     * RSI 存储路径解析：沉淀产物 store、git-dir、游标 state 的唯一路径出口与防穿越守卫。
     */
    @Bean
    public RsiPaths rsiPaths(@Value("${wayfarer.rsi.root:./.wayfarer/rsi}") String root) {
        return new RsiPaths(root);
    }

    /**
     * RSI 配置：阈值全部带缺省值，application.yml 里只写需要覆盖的项。
     * 触发模型：L1 消息数阈值（暖启动 + idle 兜底）、
     * L2 延迟、L3 五级触发阈值、skill 累计轮数。
     */
    @Bean
    public RsiProperties rsiProperties(
            @Value("${wayfarer.rsi.enabled:true}") boolean enabled,
            @Value("${wayfarer.rsi.root:./.wayfarer/rsi}") String root,
            @Value("${wayfarer.rsi.pool-size:2}") int poolSize,
            @Value("${wayfarer.rsi.queue-capacity:32}") int queueCapacity,
            @Value("${wayfarer.rsi.l1-score-floor:6}") int l1ScoreFloor,
            @Value("${wayfarer.rsi.recall-top-k:5}") int recallTopK,
            @Value("${wayfarer.rsi.recall-token-budget:800}") int recallTokenBudget,
            @Value("${wayfarer.rsi.skill-soft-limit:20}") int skillSoftLimit,
            @Value("${wayfarer.rsi.l1-threshold-start:1}") int l1ThresholdStart,
            @Value("${wayfarer.rsi.l1-threshold-max:16}") int l1ThresholdMax,
            @Value("${wayfarer.rsi.l1-idle-seconds:60}") int l1IdleSeconds,
            @Value("${wayfarer.rsi.l1-batch-process:10}") int l1BatchProcess,
            @Value("${wayfarer.rsi.l1-max-background:5}") int l1MaxBackground,
            @Value("${wayfarer.rsi.l1-max-memories:10}") int l1MaxMemories,
            @Value("${wayfarer.rsi.conflict-recall-top-k:5}") int conflictRecallTopK,
            @Value("${wayfarer.rsi.l2-delay-seconds:30}") int l2DelaySeconds,
            @Value("${wayfarer.rsi.max-scenes:20}") int maxScenes,
            @Value("${wayfarer.rsi.persona-trigger-every-n:20}") int personaTriggerEveryN,
            @Value("${wayfarer.rsi.skill-archive-every-n-rounds:5}") int skillArchiveEveryNRounds) {
        return new RsiProperties(enabled, root, poolSize, queueCapacity, l1ScoreFloor,
                recallTopK, recallTokenBudget, skillSoftLimit,
                l1ThresholdStart, l1ThresholdMax, l1IdleSeconds, l1BatchProcess,
                l1MaxBackground, l1MaxMemories, conflictRecallTopK, l2DelaySeconds,
                maxScenes, personaTriggerEveryN, skillArchiveEveryNRounds);
    }

    /**
     * agent 蓝图目录：注册为共享 bean，AgentService（运行装配）与 RsiController（agent 白名单）共用。
     * 新增蓝图时只需在这里追加一个实例。
     *
     * <p>wayfarer-rsi 需要注入 RSI 装配件（路径 / 配置 / 沉淀流水线 / 分词器），
     * 因此 agentCatalog 反过来依赖这些 bean；它们彼此无环。</p>
     */
    @Bean
    public AgentCatalog agentCatalog(RsiPaths rsiPaths, RsiProperties rsiProperties,
                                     com.yf.wayfarer.rsi.RsiSedimentPipeline rsiSedimentPipeline,
                                     com.yf.wayfarer.rsi.MemorySearcher rsiSearcher,
                                     com.yf.wayfarer.rsi.SceneIndex sceneIndex,
                                     SkillFileSystem skillFileSystem) {
        return new AgentCatalog(List.of(new WayfarerBlueprint(), new WayfarerProBlueprint(),
                new com.yf.wayfarer.agent.RsiBlueprint(rsiPaths, rsiProperties, rsiSedimentPipeline,
                        rsiSearcher, sceneIndex, skillFileSystem)));
    }
}
