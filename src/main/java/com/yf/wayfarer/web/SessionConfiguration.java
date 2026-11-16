package com.yf.wayfarer.web;

import com.yf.wayfarer.loop.modelchain.MemoryCompactConfig;
import com.yf.wayfarer.config.ConfigStore;
import com.yf.wayfarer.hitl.HitlService;
import com.yf.wayfarer.model.ModelConfig;
import com.yf.wayfarer.prompt.SystemPromptStore;
import com.yf.wayfarer.session.JsonlSession;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.UnloadedMessageStore;
import com.yf.wayfarer.skill.SkillFileSystem;
import com.yf.wayfarer.skill.SkillStore;
import com.yf.wayfarer.subagent.SubAgentManager;
import com.yf.wayfarer.trace.TraceStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

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
}
