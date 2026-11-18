package com.yf.wayfarer.rsi;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * RSI 存储路径解析与防穿越。
 *
 * <p>RSI 的磁盘布局（见规格文档第六节）：</p>
 * <pre>
 * &lt;rsi 根&gt;/
 * ├── stores/&lt;agent&gt;/skills/...        # 沉淀产物：技能（jgit worktree）
 * ├── stores/&lt;agent&gt;/memory/...        # 沉淀产物：记忆（l1.jsonl / l2/ / l3-profile.md）
 * ├── git/&lt;agent&gt;/&lt;kind&gt;.git          # git-dir，与 worktree 分离（模仿 ShadowRepository）
 * └── state/&lt;agent&gt;/cursor.json       # 游标簿记，不入 git
 * </pre>
 *
 * <p>安全护栏：沉淀流水线与 Web 接口拿到的都是相对路径，一律经这里解析成绝对路径；
 * 带 {@code ..}、绝对路径、盘符的输入直接拒绝，保证写入永远落在
 * {@code stores/<agent>/} 子树内——systemprompt 与主 skills 目录不在任何写入路径上。</p>
 *
 * @author yyf
 * @description 路径唯一出口：store / git / state 三类目录的解析，agent 与 kind 的合法性校验，
 * 以及「相对路径必须落在指定 store 子树内」的穿越防护
 */
public final class RsiPaths {

    /** 沉淀产物类别：skill（技能）与 memory（记忆）。 */
    public static final String KIND_SKILL = "skill";
    public static final String KIND_MEMORY = "memory";

    /** 合法 kind 集合，Controller 与 Git 服务共用。 */
    public static final java.util.Set<String> KINDS = java.util.Set.of(KIND_SKILL, KIND_MEMORY);

    /** agent 名称约束：小写字母、数字、短横线，1~64 位；防止用名称拼出路径穿越。 */
    private static final Pattern AGENT_PATTERN = Pattern.compile("[a-zA-Z0-9-]{1,64}");

    /** 相对路径约束：不允许盘符（如 C:）、不允许 .. 段、不允许以 / 开头，段内不允许特殊字符。 */
    private static final Pattern RELATIVE_PATTERN = Pattern.compile("[\\w][\\w\\-. /\\\\]*");

    private final Path root;

    public RsiPaths(String root) {
        // 统一转绝对路径并规范化，后续所有 startsWith 判断都基于规范形式才可靠
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    /** RSI 根目录绝对路径。 */
    public Path root() {
        return root;
    }

    /**
     * 沉淀产物根目录：stores/。
     */
    public Path storesRoot() {
        return root.resolve("stores");
    }

    /**
     * 指定 agent 的 store 目录：stores/&lt;agent&gt;/。
     *
     * @throws IllegalArgumentException agent 名称不合法时
     */
    public Path storeRoot(String agent) {
        return storesRoot().resolve(agent(agent));
    }

    /**
     * 指定 agent 的技能 store 目录：stores/&lt;agent&gt;/skills/。
     */
    public Path skillsDir(String agent) {
        return storeRoot(agent).resolve("skills");
    }

    /**
     * 指定 agent 的记忆 store 目录：stores/&lt;agent&gt;/memory/。
     */
    public Path memoryDir(String agent) {
        return storeRoot(agent).resolve("memory");
    }

    /**
     * kind 到 store 子目录名的映射：skill → skills，memory → memory。
     *
     * @throws IllegalArgumentException kind 不在白名单时
     */
    public Path kindDir(String agent, String kind) {
        return switch (kind) {
            case KIND_SKILL -> skillsDir(agent);
            case KIND_MEMORY -> memoryDir(agent);
            default -> throw new IllegalArgumentException("未知沉淀类别：" + kind);
        };
    }

    /**
     * 指定 (agent, kind) 的 git-dir：git/&lt;agent&gt;/&lt;kind&gt;.git。
     */
    public Path gitDir(String agent, String kind) {
        return root.resolve("git").resolve(agent(agent)).resolve(kind(kind) + ".git");
    }

    /**
     * 指定 agent 的游标簿记目录：state/&lt;agent&gt;/（不入 git）。
     */
    public Path stateDir(String agent) {
        return root.resolve("state").resolve(agent(agent));
    }

    /**
     * 把 store 内的相对路径解析为绝对路径，并做穿越防护。
     *
     * <p>规则：相对路径必须落在 {@code kindDir(agent, kind)} 子树内；
     * 绝对路径、盘符、{@code ..} 段、空路径一律拒绝。</p>
     *
     * @return 解析后的绝对路径（已 normalize）
     * @throws IllegalArgumentException 路径非法或越界时
     */
    public Path resolveInStore(String agent, String kind, String relative) {
        Path base = kindDir(agent, kind);
        String value = relative == null ? "" : relative.trim().replace('\\', '/');
        if (value.isEmpty()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        if (Path.of(value).isAbsolute() || value.contains("..") || !RELATIVE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("路径非法：" + relative);
        }
        Path target = base.resolve(value).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("路径越界：" + relative);
        }
        return target;
    }

    /**
     * 校验 agent 名称并返回，不合法直接抛异常。
     */
    public String agent(String agent) {
        if (agent == null || !AGENT_PATTERN.matcher(agent).matches()) {
            throw new IllegalArgumentException("agent 名称非法：" + agent);
        }
        return agent;
    }

    /**
     * 校验 kind 并返回，不合法直接抛异常。
     */
    public String kind(String kind) {
        if (kind == null || !KINDS.contains(kind)) {
            throw new IllegalArgumentException("沉淀类别非法：" + kind);
        }
        return kind;
    }
}
