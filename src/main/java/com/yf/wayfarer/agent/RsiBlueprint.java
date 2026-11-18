package com.yf.wayfarer.agent;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.AssistantAndToolCallPersistHandler;
import com.yf.wayfarer.loop.LoopChain;
import com.yf.wayfarer.loop.RsiSedimentHandler;
import com.yf.wayfarer.loop.modelchain.MemoryCompressHandler;
import com.yf.wayfarer.loop.modelchain.MemoryManageHandler;
import com.yf.wayfarer.loop.modelchain.MemoryRecallHandler;
import com.yf.wayfarer.loop.modelchain.ModelHandler;
import com.yf.wayfarer.loop.modelchain.PlanReminderHandler;
import com.yf.wayfarer.loop.modelchain.RsiProfileHandler;
import com.yf.wayfarer.loop.modelchain.SkillsHandler;
import com.yf.wayfarer.loop.modelchain.ToolResultRepairHandler;
import com.yf.wayfarer.loop.modelchain.TraceRecordHandler;
import com.yf.wayfarer.loop.modelchain.UserQueryPersistHandler;
import com.yf.wayfarer.rsi.MemorySearcher;
import com.yf.wayfarer.rsi.RsiPaths;
import com.yf.wayfarer.rsi.RsiProperties;
import com.yf.wayfarer.rsi.RsiSedimentPipeline;
import com.yf.wayfarer.rsi.SceneIndex;
import com.yf.wayfarer.rsi.tool.MemorySaveTool;
import com.yf.wayfarer.rsi.tool.MemorySearchTool;
import com.yf.wayfarer.rsi.tool.SkillSearchTool;
import com.yf.wayfarer.skill.SkillFileSystem;
import com.yf.wayfarer.skill.SkillStore;
import com.yf.wayfarer.toolcall.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * wayfarer-rsi 蓝图（规格 4.1）：继承普通 {@link AgentBlueprint} 的新 agent。
 *
 * <p>与 wayfarer / wayfarerpro 的三点差异（其余装配完全复用标准链）：</p>
 * <ol>
 *   <li>模型链插入 {@link MemoryRecallHandler}（sort=-88，run 级 L1 记忆召回）与
 *       {@link RsiProfileHandler}（sort=-94，L3 画像 + L2 场景索引装进 system prompt 尾部）；</li>
 *   <li>运行链追加 {@link RsiSedimentHandler}（sort=-5，运行结束后记账 + 异步沉淀）；</li>
 *   <li>工具集追加 memory_search / memory_save / skill_search；技能清单来自「主根 + 沉淀
 *       store 根」的多根 {@link SkillStore}。</li>
 * </ol>
 *
 * <p><b>铁律：自己只沉淀自己的</b>——读写与召回都只落在
 * {@code stores/wayfarer-rsi/} 子树；wayfarer / wayfarerpro 的链上没有任何 RSI
 * handler，行为零变化。</p>
 *
 * <p>说明：模型链采用等价展开而非 {@code super.modelChain().then(...)}，因为标准链里的
 * SkillsHandler 绑定的是 deps.skillStore()（单根），而本蓝图需要换成多根 SkillStore
 * 才能让沉淀技能进入清单——替换 handler 只能整链重建，其余 handler 与标准链逐一对齐。</p>
 *
 * @author yyf
 * @description 按名称注册的第三个 agent：模型链插入召回、运行链追加沉淀、
 * 工具集追加两个检索工具、技能清单多根合并
 */
public final class RsiBlueprint extends AgentBlueprint {

    /** agent 名称：AgentCatalog 按名称装配，前端 agent 选择菜单自动出现。 */
    public static final String NAME = "wayfarer-rsi";

    private final RsiProperties properties;
    private final RsiSedimentPipeline pipeline;
    private final MemorySearcher searcher;
    private final SceneIndex sceneIndex;

    /** 多根技能清单：主 skills 根（人类编辑）+ 本 agent 沉淀 store 根（Agent 写入）。 */
    private final SkillStore rsiSkillStore;

    /** skill_search 专用单根清单：只含沉淀 store 根（普通根已注入提示词，无需检索）。 */
    private final SkillStore sedimentOnlySkillStore;

    public RsiBlueprint(RsiPaths paths, RsiProperties properties, RsiSedimentPipeline pipeline,
                        MemorySearcher searcher, SceneIndex sceneIndex, SkillFileSystem mainSkillFiles) {
        this.properties = properties;
        this.pipeline = pipeline;
        this.searcher = searcher;
        this.sceneIndex = sceneIndex;
        // 沉淀 store 的技能根：stores/wayfarer-rsi/skills/（首次沉淀前目录可能不存在，SkillFileSystem 容忍）
        SkillFileSystem sedimentFiles = new SkillFileSystem(paths.skillsDir(NAME));
        this.rsiSkillStore = new SkillStore(mainSkillFiles, List.of(sedimentFiles));
        // skill_search 只看沉淀根：普通根的技能清单已由 SkillsHandler 注入提示词
        this.sedimentOnlySkillStore = new SkillStore(sedimentFiles, List.of());
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * 工具集 = 基础工具 + 记忆检索/显式沉淀 + 技能检索（仅 wayfarer-rsi 可见）。
     */
    @Override
    public List<Object> toolObjects(AgentDependencies deps) {
        List<Object> tools = new ArrayList<>(baseTools(deps));
        tools.add(new MemorySearchTool(searcher, properties, NAME));
        tools.add(new MemorySaveTool(pipeline, NAME));
        tools.add(new SkillSearchTool(sedimentOnlySkillStore));
        return tools;
    }

    /**
     * 模型链 = 标准链等价展开，在 PlanReminder(-90) 之后插入 MemoryRecallHandler(-88)
     * 与 RsiProfileHandler(-94)，SkillsHandler 换用多根 SkillStore（沉淀技能自动进入清单）。
     */
    @Override
    protected LoopChain modelChain(AgentDependencies deps, List<ToolDefinition> tools) {
        return LoopChain.of(
                new UserQueryPersistHandler(),
                new PlanReminderHandler(),
                new MemoryCompressHandler(deps.model(), deps.unloadedStore(), deps.compact()),
                new SkillsHandler(rsiSkillStore),
                // 画像 + 场景索引 → system prompt 尾部，会话初始化装配一次（护 KV-cache）
                new RsiProfileHandler(NAME, properties, searcher, sceneIndex),
                new MemoryRecallHandler(NAME, properties, searcher),
                new ToolResultRepairHandler(),
                new TraceRecordHandler(),
                new AssistantAndToolCallPersistHandler(),
                new ModelHandler(deps.model(), tools),
                new MemoryManageHandler());
    }

    /**
     * 运行链 = 标准链 + 沉淀总入口（sort=-5，后置在 TRACE_END 封存之后执行）。
     */
    @Override
    public LoopChain runChain(AgentDependencies deps) {
        return super.runChain(deps).then(
                new RsiSedimentHandler(properties, pipeline, deps.model()));
    }
}
