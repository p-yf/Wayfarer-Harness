package com.yf.wayfarer.loop.toolchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yf.wayfarer.hitl.HitlService;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 编码工具审批处理器。
 *
 * @author yyf
 * @description 按上下文中的审批级别决定是否需要人工确认：all 全部拦截，
 * workspacebased 只放行工作区内的 read/edit/write（bash 与工作区外路径都拦截），no 不拦截。
 * 需要确认时在此阻塞等待前端提交决定，被拒绝的调用写入上下文，由工具执行处理器直接返回拒绝结果
 */
public class CodingToolApprovalHandler implements ChainHandler {

    private static final Logger log = LoggerFactory.getLogger(CodingToolApprovalHandler.class);

    /**
     * 被拒绝的工具调用结果，键为工具调用 id。
     */
    public static final String DENIED = "approvalDenied";

    public static final String DENY_RESULT = "<sys-用户拒绝了本次工具调用，请勿重复尝试相同操作>";

    private static final Set<String> CODING_TOOLS = Set.of("read", "bash", "edit", "write");
    private static final String SHEET = "sheet1";
    private static final String ALLOW_KEY = "key1";
    private static final String DENY_KEY = "key2";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HitlService hitl;

    public CodingToolApprovalHandler(HitlService hitl) {
        this.hitl = hitl;
    }

    @Override
    public int sort() {
        // 重复调用检查之后、工具执行之前
        return -80;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        ModelResponse response = context.modelResponse();
        if (response != null && !response.toolCalls().isEmpty()) {
            review(context, response);
        }
        next.run();
    }

    /**
     * 逐个判断本轮调用，需要确认的在此等待前端决定。
     */
    private void review(AgentContext context, ModelResponse response) {
        Mode mode = Mode.of(context.get(AgentContext.APPROVAL, String.class));
        if (mode == Mode.NONE) {
            return;
        }
        Map<String, String> denied = new LinkedHashMap<>();
        for (ToolCallInfo info : response.toolCalls()) {
            if (!requiresApproval(context, mode, info)) {
                continue;
            }
            if (approved(context, info)) {
                continue;
            }
            denied.put(info.id(), DENY_RESULT);
            log.info("工具调用被拒绝 traceId={} 工具={}", context.traceId(), info.name());
        }
        if (!denied.isEmpty()) {
            context.put(DENIED, denied);
        }
    }

    private boolean approved(AgentContext context, ToolCallInfo info) {
        log.info("等待工具审批 traceId={} 工具={} 参数={}", context.traceId(), info.name(), info.arguments());
        Map<String, Object> decision = hitl.confirm(context, info.id(), info.name(),
                info.arguments(), metadata());
        // 超时、取消或未选择允许都按拒绝处理
        boolean approved = decision != null && ALLOW_KEY.equals(String.valueOf(decision.get(SHEET)));
        log.info("工具审批完成 traceId={} 工具={} 允许={}", context.traceId(), info.name(), approved);
        return approved;
    }

    /**
     * 审批卡片的扩展描述：一张单选按钮组，key1 允许、key2 拒绝。
     */
    private static Map<String, Object> metadata() {
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put(ALLOW_KEY, "允许");
        sheet.put(DENY_KEY, "拒绝");
        sheet.put("style", "radio group");
        return Map.of(SHEET, sheet);
    }

    /**
     * 取某次调用被拒绝时返回给模型的内容，未拒绝时为 null。
     */
    @SuppressWarnings("unchecked")
    public static String deniedResultOf(AgentContext context, String toolCallId) {
        Map<String, String> denied = context.get(DENIED, Map.class);
        return denied == null ? null : denied.get(toolCallId);
    }

    private static boolean requiresApproval(AgentContext context, Mode mode, ToolCallInfo info) {
        if (info.name() == null || !CODING_TOOLS.contains(info.name())) {
            return false;
        }
        if (mode == Mode.ALL) {
            return true;
        }
        if (mode == Mode.NONE) {
            return false;
        }
        // workspacebased：bash 一律拦截，读写按路径是否位于工作区或根工作区判断
        if ("bash".equals(info.name())) {
            return true;
        }
        Path workspace = workspace(context);
        Path rootWorkspace = rootWorkspace(context);
        if (workspace == null && rootWorkspace == null) {
            return true;
        }
        Path target = resolve(workspace != null ? workspace : rootWorkspace, pathArgument(info.arguments()));
        return !(inside(workspace, target) || inside(rootWorkspace, target));
    }

    private static Path workspace(AgentContext context) {
        String value = context.get(AgentContext.WORKSPACE, String.class);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Path rootWorkspace(AgentContext context) {
        String value = context.rootWorkspace();
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 从工具入参中取 path，解析失败或缺失时返回 null（按工作区外处理）。
     */
    @SuppressWarnings("unchecked")
    private static String pathArgument(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> values = JSON.readValue(arguments, Map.class);
            Object path = values.get("path");
            return path == null ? null : String.valueOf(path);
        } catch (Exception e) {
            return null;
        }
    }

    private static Path resolve(Path workspace, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            Path target = Path.of(path);
            return (target.isAbsolute() ? target : workspace.resolve(target)).normalize().toAbsolutePath();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 目标路径是否位于工作区内。Windows 路径大小写不敏感，比较前统一转小写。
     */
    private static boolean inside(Path workspace, Path target) {
        if (workspace == null || target == null) {
            return false;
        }
        String root = comparable(workspace);
        String value = comparable(target);
        return value.equals(root) || value.startsWith(root + File.separator);
    }

    private static String comparable(Path path) {
        String text = path.toString();
        return isWindows() ? text.toLowerCase(Locale.ROOT) : text;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 审批级别，未知或缺失时按不拦截处理。
     */
    public enum Mode {
        ALL,
        WORKSPACE,
        NONE;

        static Mode of(String value) {
            if (value == null) {
                return NONE;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "all" -> ALL;
                case "workspacebased" -> WORKSPACE;
                default -> NONE;
            };
        }
    }
}
