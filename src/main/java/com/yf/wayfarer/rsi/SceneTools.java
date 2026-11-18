package com.yf.wayfarer.rsi;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.toolcall.Tool;
import com.yf.wayfarer.toolcall.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 场景库文件操作工具集（场景整理 agent 的受控写入口）。
 *
 * <p>场景整理 agent 只能通过本工具读写场景文件，工具直接落在
 * {@code stores/<agent>/memory/scenes/} 目录上，越界防护由
 * {@link ToolSandboxGuardHandler} 在工具链上统一拦截，本类不做权限校验。</p>
 *
 * <p>工具只操作一个固定 agent 的场景目录（每次 L2 萃取构造一个实例），
 * 执行结果通过 {@link Counters} 计数器共享给 {@link SceneExtractor} 汇总为 Outcome。</p>
 *
 * @author yyf
 * @description list_scenes/read_scene 供归属判断，update_scene/create_scene/merge_scene/
 * delete_scene 执行四类变更；resolveTarget() 负责场景名 → 文件路径（守卫与工具共用同一套解析，
 * 保证守卫校验的就是工具实际要写的文件）
 */
public class SceneTools {

    private static final Logger log = LoggerFactory.getLogger(SceneTools.class);

    private final Path scenesDir;
    private final int maxScenes;
    private final Counters counters;

    public SceneTools(Path scenesDir, int maxScenes, Counters counters) {
        this.scenesDir = scenesDir;
        this.maxScenes = Math.max(1, maxScenes);
        this.counters = counters;
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                                  */
    /* ------------------------------------------------------------------ */

    @Tool(name = "list_scenes", description =
            "列出场景库中全部现有场景（名称、摘要、热度），做归属判断前先调用它了解现状。")
    public String listScenes(AgentContext context) {
        StringBuilder text = new StringBuilder();
        for (SceneFormat.SceneFile scene : readAll()) {
            text.append("- ").append(scene.name())
                    .append("：").append(scene.summary().isBlank() ? "（无摘要）" : scene.summary())
                    .append("（heat ").append(scene.heat()).append("）\n");
        }
        return text.isEmpty() ? "场景库为空。" : text.toString().strip();
    }

    @Tool(name = "read_scene", description = "读取某个场景的完整正文（含 META），确认内容后再决定如何更新。")
    public String readScene(AgentContext context,
                            @ToolParam(description = "场景名或文件名") String scene) {
        Path file = resolveTarget(scenesDir, scene);
        if (file == null || !Files.isRegularFile(file)) {
            return "场景不存在：" + scene + "，可用 list_scenes 查看现有场景。";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("RSI L2 读取场景失败 scene={}", scene, e);
            return "读取失败：" + e.getMessage();
        }
    }

    @Tool(name = "update_scene", description =
            "向既有场景追加知识：content 为要追加的正文段落（可多句）；summary 可选，"
                    + "给出时替换场景摘要。目标场景必须已存在。")
    public String updateScene(AgentContext context,
                              @ToolParam(description = "目标场景名") String scene,
                              @ToolParam(description = "要追加的正文段落") String content,
                              @ToolParam(required = false, description = "更新后的一句话摘要（不给则保留原摘要）")
                              String summary) {
        Path file = resolveTarget(scenesDir, scene);
        if (file == null || !Files.isRegularFile(file)) {
            counters.skipped();
            return "场景不存在，未更新：" + scene;
        }
        try {
            SceneFormat.SceneFile old = parse(file);
            String body = old.body() + "\n\n" + content.strip();
            String nextSummary = blank(summary) ? old.summary() : summary.strip();
            write(file, SceneFormat.render(old.name(), nextSummary, old.created(),
                    SceneFormat.now(), old.heat(), body));
            counters.updated();
            return "已更新场景：" + old.name();
        } catch (IOException e) {
            counters.skipped();
            log.warn("RSI L2 更新场景失败 scene={}", scene, e);
            return "更新失败：" + e.getMessage();
        }
    }

    @Tool(name = "create_scene", description =
            "新建场景：content 为场景初始正文，summary 为一句话摘要。"
                    + "确无合适既有场景时才使用；达到数量上限会被拒绝。")
    public String createScene(AgentContext context,
                              @ToolParam(description = "新场景名") String scene,
                              @ToolParam(description = "场景初始正文") String content,
                              @ToolParam(description = "一句话摘要") String summary) {
        // 分档硬约束（与任务文本里的预警双保险）：只差 1 个到上限即禁 CREATE
        if (countExisting() >= maxScenes - 1) {
            counters.skipped();
            return "场景数已达上限（" + maxScenes + "），禁止 CREATE，请改用 update_scene / merge_scene / delete_scene。";
        }
        Path file = resolveTarget(scenesDir, scene);
        if (file == null) {
            counters.skipped();
            return "场景名非法：" + scene;
        }
        if (Files.exists(file)) {
            // 命中既有场景：降级为 UPDATE 语义（与旧版程序执行一致）
            return updateScene(context, scene, content, summary);
        }
        String name = scene.strip();
        String now = SceneFormat.now();
        try {
            Files.createDirectories(scenesDir);
            write(file, SceneFormat.render(name, summaryOf(content, summary), now, now, 0, content));
            counters.created();
            return "已创建场景：" + name;
        } catch (IOException e) {
            counters.skipped();
            log.warn("RSI L2 创建场景失败 scene={}", scene, e);
            return "创建失败：" + e.getMessage();
        }
    }

    @Tool(name = "merge_scene", description =
            "合并场景：把 mergeFrom 中列出的场景并入 target，target 正文替换为合并后的完整文本"
                    + "（content），多个被合并场景名用逗号分隔。")
    public String mergeScene(AgentContext context,
                             @ToolParam(description = "保留的目标场景名") String target,
                             @ToolParam(description = "被合并的场景名，多个用逗号分隔") String mergeFrom,
                             @ToolParam(description = "合并后的完整正文") String content,
                             @ToolParam(required = false, description = "合并后的一句话摘要") String summary) {
        Path targetFile = resolveTarget(scenesDir, target);
        if (targetFile == null || !Files.isRegularFile(targetFile)) {
            counters.skipped();
            return "目标场景不存在：" + target;
        }
        try {
            SceneFormat.SceneFile old = parse(targetFile);
            String nextSummary = blank(summary) ? old.summary() : summary.strip();
            write(targetFile, SceneFormat.render(old.name(), nextSummary, old.created(),
                    SceneFormat.now(), old.heat(), content));
            int removed = 0;
            for (String name : mergeFrom.split("[,，、]")) {
                if (blank(name)) {
                    continue;
                }
                Path victim = resolveTarget(scenesDir, name);
                if (victim == null || !Files.isRegularFile(victim)
                        || victim.toAbsolutePath().normalize()
                        .equals(targetFile.toAbsolutePath().normalize())) {
                    continue;
                }
                Files.deleteIfExists(victim);
                removed++;
            }
            counters.merged();
            return "已合并场景：" + target + "（移除 " + removed + " 个被合并场景）";
        } catch (IOException e) {
            counters.skipped();
            log.warn("RSI L2 合并场景失败 target={}", target, e);
            return "合并失败：" + e.getMessage();
        }
    }

    @Tool(name = "delete_scene", description = "删除已过时或错误的场景。")
    public String deleteScene(AgentContext context,
                              @ToolParam(description = "要删除的场景名") String scene) {
        Path file = resolveTarget(scenesDir, scene);
        if (file == null || !Files.isRegularFile(file)) {
            counters.skipped();
            return "场景不存在：" + scene;
        }
        try {
            Files.deleteIfExists(file);
            counters.deleted();
            return "已删除场景：" + scene.strip();
        } catch (IOException e) {
            counters.skipped();
            log.warn("RSI L2 删除场景失败 scene={}", scene, e);
            return "删除失败：" + e.getMessage();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 路径解析（守卫与工具共用）                                                */
    /* ------------------------------------------------------------------ */

    /**
     * 场景名 → 场景库内的文件路径：先按既有文件（场景名或文件名）匹配，
     * 未命中回退 sanitizeFileName 生成的目标路径。返回 null 表示场景名非法。
     */
    public static Path resolveTarget(Path scenesDir, String scene) {
        if (scene == null || scene.isBlank()) {
            return null;
        }
        String wanted = scene.strip();
        // 允许模型带 ".md" 后缀称呼场景，统一剥掉再匹配
        String wantedBase = wanted.replaceAll("(?i)\\.md$", "");
        if (Files.isDirectory(scenesDir)) {
            try (var stream = Files.list(scenesDir)) {
                for (Path file : stream.filter(f -> f.getFileName().toString().endsWith(".md")).toList()) {
                    String fileName = file.getFileName().toString();
                    if (fileName.replaceAll("(?i)\\.md$", "").equalsIgnoreCase(wantedBase)) {
                        return file;
                    }
                    try {
                        SceneFormat.SceneFile parsed = SceneFormat.parse(
                                Files.readString(file, StandardCharsets.UTF_8), fileName);
                        if (parsed.name().strip().equals(wanted)
                                || parsed.name().strip().equalsIgnoreCase(wantedBase)) {
                            return file;
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // 单个文件解析失败不参与名称匹配
                    }
                }
            } catch (IOException e) {
                log.warn("RSI 场景目录扫描失败：{}", scenesDir, e);
            }
        }
        return scenesDir.resolve(sanitizeFileName(wantedBase) + ".md");
    }

    /**
     * 场景名 → 安全文件名：去路径非法字符（Windows 集合，含路径分隔符，
     * 因此 "..\\x" 之类的穿越输入会被清洗为无害文本），压到 40 字符，
     * 空结果回退 hash。
     */
    public static String sanitizeFileName(String scene) {
        String cleaned = scene == null ? "" : scene.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").strip();
        if (cleaned.isEmpty()) {
            cleaned = "scene-" + Integer.toHexString(String.valueOf(scene).hashCode());
        }
        return cleaned.length() <= 40 ? cleaned : cleaned.substring(0, 40);
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                  */
    /* ------------------------------------------------------------------ */

    private List<SceneFormat.SceneFile> readAll() {
        if (!Files.isDirectory(scenesDir)) {
            return List.of();
        }
        try (var stream = Files.list(scenesDir)) {
            return stream.filter(f -> f.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .map(file -> {
                        try {
                            return parse(file);
                        } catch (IOException e) {
                            log.warn("RSI 场景读取失败，跳过：{}", file, e);
                            return null;
                        }
                    })
                    .filter(scene -> scene != null)
                    .toList();
        } catch (IOException e) {
            log.warn("RSI 场景目录扫描失败：{}", scenesDir, e);
            return List.of();
        }
    }

    private int countExisting() {
        if (!Files.isDirectory(scenesDir)) {
            return 0;
        }
        try (var stream = Files.list(scenesDir)) {
            return (int) stream.filter(f -> f.getFileName().toString().endsWith(".md")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private SceneFormat.SceneFile parse(Path file) throws IOException {
        return SceneFormat.parse(Files.readString(file, StandardCharsets.UTF_8),
                file.getFileName().toString());
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String summaryOf(String content, String summary) {
        return blank(summary)
                ? content.strip().lines().findFirst().orElse("").strip()
                : summary.strip();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * @author yyf
     * @description 工具执行计数器：SceneTools 各工具成功执行后递增，SceneExtractor
     * 据此汇总 Outcome；skipped() 单独计数（目标不存在/被上限拦截等未生效操作）
     */
    public static final class Counters {

        private final AtomicInteger updated = new AtomicInteger();
        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger merged = new AtomicInteger();
        private final AtomicInteger deleted = new AtomicInteger();
        private final AtomicInteger skipped = new AtomicInteger();

        public void updated() {
            updated.incrementAndGet();
        }

        public void created() {
            created.incrementAndGet();
        }

        public void merged() {
            merged.incrementAndGet();
        }

        public void deleted() {
            deleted.incrementAndGet();
        }

        public void skipped() {
            skipped.incrementAndGet();
        }

        public int updatedCount() {
            return updated.get();
        }

        public int createdCount() {
            return created.get();
        }

        public int mergedCount() {
            return merged.get();
        }

        public int deletedCount() {
            return deleted.get();
        }

        public int skippedCount() {
            return skipped.get();
        }
    }
}
