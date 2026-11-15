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
 * @author yyf
 * @description 按工作区的绝对路径建仓库，路径做规范化后取 sha1 作为目录名，所以同一个目录
 * 无论用盘符大小写、带不带尾斜杠访问，都会落到同一个仓库；仓库跟工作区绑定，与会话无关
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
     * 按提交 id 反查所属仓库，找不到返回 null。
     */
    public ShadowRepository forCommit(String commitId) {
        for (ShadowRepository repository : all()) {
            if (repository.contains(commitId)) {
                return repository;
            }
        }
        return null;
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
