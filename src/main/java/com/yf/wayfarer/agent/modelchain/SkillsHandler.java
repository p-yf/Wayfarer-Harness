package com.yf.wayfarer.agent.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.skill.SkillStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 技能清单处理器。
 *
 * @author yyf
 * @description 每次调用模型前读取 skills 目录，把技能清单按统一格式写入系统提示词集合，
 * 模型据此决定是否用 read 工具加载某个技能的正文
 */
public class SkillsHandler implements ChainHandler {

    private static final Logger log = LoggerFactory.getLogger(SkillsHandler.class);

    /**
     * 技能清单在系统提示词集合中的键，顺序取极大值，始终排在最后。
     */
    public static final String SKILL_PROMPT_KEY = "skills-99999";

    private final SkillStore store;

    public SkillsHandler(SkillStore store) {
        this.store = store;
    }

    @Override
    public int sort() {
        // 早于模型调用，晚于记忆压缩
        return -95;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        String prompt = store == null ? "" : store.prompt();
        if (!prompt.isBlank()) {
            Map<String, String> prompts = context.systemPrompts();
            if (prompts == null) {
                prompts = new LinkedHashMap<>();
                context.put(AgentContext.SYSTEM_PROMPT, prompts);
            }
            prompts.put(SKILL_PROMPT_KEY, prompt);
            log.info("注入技能清单 traceId={} 长度={}", context.traceId(), prompt.length());
        }
        next.run();
    }
}
