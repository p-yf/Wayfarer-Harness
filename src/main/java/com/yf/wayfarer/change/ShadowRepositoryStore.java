package com.yf.wayfarer.change;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 影子仓库的定位与缓存。
 *
 * <p>磁盘结构：</p>
 * <pre>
 * &lt;wayfarer.changes.root&gt;/
 *   └── &lt;工作区路径 sha1 前 16 位&gt;/
 *       ├── git-dir/     JGit 仓库数据（在工作区之外，不污染用户目录）
 *       └── meta.json    {"workspace":"D:/xxx","createdAt":"..."}
 * </pre>
 *
 * <p>为什么用路径哈希做目录名：仓库必须跟<b>工作区</b>绑定，而不是跟会话绑定。
 * 同一个目录被多个会话使用时，文件时间线才连续；哈希保证同一路径永远落到同一个仓库，
 * 同时避免把带盘符、斜杠的路径直接当目录名。Windows 大小写不敏感，所以先转小写再哈希，
 * 否则 {@code D:/Proj} 与 {@code d:/proj} 会算出两个仓库。</p>
 *
 * <p>为什么还要 meta.json：目录名只有哈希，反查不到原路径。重启后读回它，
 * 才能把 work-tree 指回正确的工作区（也顺便保留用户当时传入的路径写法）。</p>
 *
 * <p>仓库对象会被缓存（惰性创建），{@link #all()} 则是扫描磁盘上所有已建仓库，
 * 供「按会话查询」和「reset 回退」这类需要跨工作区的场景使用。</p>
 *
 * @author yyf
 * @description 按工作区定位影子仓库、维护仓库目录与元数据，并提供全量枚举与删除
 */
public final class ShadowRepositoryStore {

    private static final Logger log = LoggerFactory.getLogger(ShadowRepositoryStore.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String META_FILE = "meta.json";
    private static final String GIT_DIR = "git-dir";

    private final Path root;
    private final List<String> excludes;
    private final boolean enabled;
    private final ConcurrentHashMap<String, ShadowRepository> repositories = new ConcurrentHashMap<>();

    public ShadowRepositoryStore(Path root, List<String> excludes, boolean enabled) {
        this.root = root.toAbsolutePath().normalize();
        this.excludes = excludes == null ? List.of() : List.copyOf(excludes);
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 取工作区对应的仓库；未开启功能、未设置工作区或目录不存在时返回 null。
     * 仓库本身是懒创建的，首次真正需要记录改动时才做基线提交。
     */
    public ShadowRepository forWorkspace(String workspace) {
        if (!enabled || workspace == null || workspace.isBlank()) {
            return null;
        }
        Path directory;
        try {
            directory = Path.of(workspace).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            log.warn("工作区路径非法，跳过变更记录：{}", workspace);
            return null;
        }
        if (!Files.isDirectory(directory)) {
            log.warn("工作区目录不存在，跳过变更记录：{}", directory);
            return null;
        }
        String key = key(directory);
        return repositories.computeIfAbsent(key, ignored -> create(key, directory));
    }

    /**
     * 已经建立过的全部仓库，用于按会话跨工作区查询与回退。
     */
    public List<ShadowRepository> all() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            List<ShadowRepository> repositories = new ArrayList<>();
            for (Path directory : entries.filter(Files::isDirectory).sorted(Comparator.naturalOrder()).toList()) {
                ShadowRepository repository = read(directory);
                if (repository != null) {
                    repositories.add(repository);
                }
            }
            return repositories;
        } catch (IOException e) {
            throw new UncheckedIOException("读取影子仓库目录失败：" + root, e);
        }
    }

    /**
     * 删除某个工作区对应的影子仓库，连同 git-dir 与元数据一并清理。
     * 只清理变更历史，工作区目录本身不受影响；仓库目录不存在时返回 false。
     */
    public boolean deleteWorkspace(String workspace) {
        if (workspace == null || workspace.isBlank()) {
            return false;
        }
        Path directory;
        try {
            directory = Path.of(workspace).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            log.warn("工作区路径非法，跳过影子仓库删除：{}", workspace);
            return false;
        }
        String key = key(directory);
        repositories.remove(key);
        Path target = root.resolve(key);
        if (!Files.isDirectory(target)) {
            return false;
        }
        try {
            deleteRecursively(target);
            log.info("影子仓库已删除 workspace={} dir={}", directory, target);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("删除影子仓库失败：" + target, e);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (Stream<Path> entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                // JGit 写的对象文件是只读的，Windows 上直接删会报拒绝访问，先恢复可写
                if (!Files.isWritable(entry)) {
                    entry.toFile().setWritable(true);
                }
                Files.deleteIfExists(entry);
            }
        }
    }

    /**
     * 读取或创建某个仓库目录，工作区以 meta.json 为准（重启后路径大小写可能与当时不同）。
     */
    private ShadowRepository create(String key, Path workspace) {
        Path directory = root.resolve(key);
        Path metaFile = directory.resolve(META_FILE);
        Path workTree = workspace;
        if (Files.isRegularFile(metaFile)) {
            try {
                Map<?, ?> meta = JSON.readValue(Files.readString(metaFile, StandardCharsets.UTF_8), Map.class);
                Object stored = meta.get("workspace");
                if (stored != null) {
                    workTree = Path.of(String.valueOf(stored)).toAbsolutePath().normalize();
                }
            } catch (IOException | RuntimeException e) {
                log.warn("读取影子仓库元数据失败，按传入路径处理：{}", metaFile, e);
            }
        } else {
            writeMeta(directory, workspace);
        }
        return new ShadowRepository(directory, directory.resolve(GIT_DIR), workTree, excludes);
    }

    private ShadowRepository read(Path directory) {
        Path metaFile = directory.resolve(META_FILE);
        if (!Files.isRegularFile(metaFile)) {
            return null;
        }
        String key = directory.getFileName().toString();
        return repositories.computeIfAbsent(key, ignored -> create(key, directory));
    }

    private void writeMeta(Path directory, Path workspace) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("workspace", workspace.toString());
        meta.put("createdAt", Instant.now().toString());
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(META_FILE), JSON.writeValueAsString(meta), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入影子仓库元数据失败：" + directory, e);
        }
    }

    /**
     * 目录名：规范化路径的 sha1 前 16 位。Windows 上大小写不敏感，统一转小写后再算。
     */
    private static String key(Path workspace) {
        String text = workspace.toString();
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            text = text.toLowerCase();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-1 实现", e);
        }
    }
}
