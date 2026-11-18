package com.yf.wayfarer.rsi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.loop.toolchain.ToolCallHandler;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 沉淀类 agent 的统一目录守卫（「文件工具只能写指定目录」的安全边界）。
 *
 * <p>挂在工具链上、位于 {@link com.yf.wayfarer.loop.toolchain.ToolCallLoopHandler}（sort=0）
 * 与 {@link ToolCallHandler}（sort=MAX）之间：对文件变更类工具，先用与工具实现
 * <b>完全同一套</b>名称→路径解析函数解析出实际目标路径，再校验它是否越出允许目录；
 * 越界则拒绝放行——直接补一条系统拒绝结果作为 tool 消息，不调用 next，工具本体不会执行。
 * 场景整理 agent 与技能评审 agent 各自用不同的目录/工具集/解析函数构造实例。</p>
 *
 * <p>这不是冗余防御：模型可能输出路径形式的名称（如 "../persona"），
 * 清洗解析属于行为，守卫属于边界——两层独立生效。</p>
 *
 * @author yyf
 * @description run()：读 CURRENT_TOOL_CALL，变更类工具从 args 里提取目标参数
 * （逗号分隔展开）逐一交给 resolver 解析并校验路径在 allowedDir 内；
 * 越界 appendResult 拒绝文本并中断链路
 */
public class ToolSandboxGuardHandler implements ChainHandler {

    private static final Logger log = LoggerFactory.getLogger(ToolSandboxGuardHandler.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 允许写入的根目录（绝对规范化）。 */
    private final Path allowedDir;

    /** 需要守卫的文件变更类工具；只读工具不在集合内即放行。 */
    private final Set<String> mutationTools;

    /** 目标名称所在的参数键（值内逗号/顿号分隔的多个名称会展开逐一校验）。 */
    private final List<String> targetKeys;

    /** 名称 → 实际目标路径（与工具实现共用同一套解析，保证校验的就是要写的文件）。 */
    private final Function<String, Path> resolver;

    /** 拒绝文案里的范围名（如「场景目录」「技能目录」）。 */
    private final String scope;

    public ToolSandboxGuardHandler(Path allowedDir, Set<String> mutationTools,
                                   List<String> targetKeys, Function<String, Path> resolver,
                                   String scope) {
        this.allowedDir = allowedDir.toAbsolutePath().normalize();
        this.mutationTools = Set.copyOf(mutationTools);
        this.targetKeys = List.copyOf(targetKeys);
        this.resolver = resolver;
        this.scope = scope;
    }

    @Override
    public int sort() {
        // 介于批量调度（0）与实际执行（MAX）之间
        return 10;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        ToolCallInfo info = context.currentToolCall();
        if (info == null || !mutationTools.contains(info.name())) {
            next.run();
            return;
        }
        for (String target : targetNames(info)) {
            Path resolved = resolver.apply(target);
            if (resolved == null || !resolved.toAbsolutePath().normalize().startsWith(allowedDir)) {
                String message = "<sys-拒绝执行：目标路径越出" + scope + "（" + allowedDir
                        + "），目标=\"" + target + "\"。请改用范围内的既有名称。>";
                log.warn("RSI 沉淀守卫拦截 traceId={} 工具={} target={}", context.traceId(), info.name(), target);
                ToolCallHandler.appendResult(context, info, message);
                // 不调用 next：拦截即终止本次调用链
                return;
            }
        }
        next.run();
    }

    /**
     * 从工具参数里提取需要校验的目标名称：逐键取字符串值，
     * 值内逗号/顿号分隔的多个名称展开；一个都取不到时返回空名（同样拦下，不给默认放行）。
     */
    private List<String> targetNames(ToolCallInfo info) {
        List<String> names = new ArrayList<>();
        Map<String, Object> args;
        try {
            args = info.arguments() == null || info.arguments().isBlank()
                    ? Map.of() : JSON.readValue(info.arguments(), new TypeReference<>() {
            });
        } catch (Exception e) {
            // 参数不可解析：视为可疑，拦下来让模型重发
            return List.of("");
        }
        for (String key : targetKeys) {
            if (args.get(key) instanceof String value && !value.isBlank()) {
                for (String name : value.split("[,，、]")) {
                    if (!name.isBlank()) {
                        names.add(name.strip());
                    }
                }
            }
        }
        if (names.isEmpty()) {
            // 变更类工具拿不到任何目标名：同样拦下，不给默认放行
            names.add("");
        }
        return names;
    }
}
