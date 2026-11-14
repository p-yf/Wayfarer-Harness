package com.yf.wayfarer.skill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 技能目录文件系统。
 *
 * @author yyf
 * @description 以 skills 根目录为边界提供目录浏览与文件增删改查；
 * 对外路径一律使用 / 分隔的相对路径，越界路径直接拒绝
 */
public final class SkillFileSystem {

    private final Path root;

    public SkillFileSystem(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * 列出目录内容；空路径表示根目录，根目录尚未创建时视为空。
     * 目录排在文件前面，同类按名称排序。
     */
    public Tree list(String relative) {
        if (!Files.isDirectory(root)) {
            return new Tree("", null, List.of());
        }
        Path directory = resolve(relative);
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("目录不存在：" + relative);
        }
        try (Stream<Path> children = Files.list(directory)) {
            List<Entry> entries = new ArrayList<>();
            for (Path child : children.toList()) {
                boolean isDirectory = Files.isDirectory(child);
                entries.add(new Entry(child.getFileName().toString(), relative(child), isDirectory,
                        isDirectory ? 0 : size(child), modified(child)));
            }
            entries.sort(Comparator.comparing(Entry::directory).reversed()
                    .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
            return new Tree(normalize(relative), parent(relative), entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 读取文本文件内容，超过 1MB 时拒绝，避免把大二进制读进页面。
     */
    public String read(String relative) {
        Path file = resolve(relative);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在：" + relative);
        }
        try {
            long size = Files.size(file);
            if (size > 1024 * 1024) {
                throw new IllegalArgumentException("文件超过 1MB，无法在页面编辑：" + relative);
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 写入文本文件，父目录不存在时自动创建。
     */
    public void write(String relative, String content) {
        Path file = resolve(relative);
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("目标是目录，无法写入：" + relative);
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 创建目录，已存在时直接返回。
     */
    public void mkdir(String relative) {
        Path directory = resolve(relative);
        if (Files.isRegularFile(directory)) {
            throw new IllegalArgumentException("同名文件已存在：" + relative);
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 删除文件或整个目录，返回是否存在并被删除；不允许删除根目录。
     */
    public boolean delete(String relative) {
        Path target = resolve(relative);
        if (target.equals(root)) {
            throw new IllegalArgumentException("不能删除 skills 根目录");
        }
        if (!Files.exists(target)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 相对路径对应的绝对路径，用于把文件真实位置交给模型。
     */
    public String absolute(String relative) {
        return resolve(relative).toString();
    }

    /**
     * 相对路径是否存在。
     */
    public boolean exists(String relative) {
        return Files.exists(resolve(relative));
    }

    /**
     * 相对路径对应的绝对路径，必须位于 skills 根目录内。
     */
    private Path resolve(String relative) {
        String value = normalize(relative);
        Path target = value.isEmpty() ? root : root.resolve(value).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("路径越界：" + relative);
        }
        return target;
    }

    /**
     * 统一成 / 分隔、无首尾斜杠的相对路径。
     */
    private static String normalize(String relative) {
        String value = relative == null ? "" : relative.trim().replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String relative(Path path) {
        String value = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return value.isEmpty() ? "" : value;
    }

    private static String parent(String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        String value = relative.trim().replace('\\', '/');
        int separator = value.lastIndexOf('/');
        return separator < 0 ? "" : value.substring(0, separator);
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static String modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant().toString();
        } catch (IOException e) {
            return Instant.EPOCH.toString();
        }
    }

    /**
     * @author yyf
     * @description 目录项：名称、相对路径、是否目录、字节数与修改时间
     */
    public record Entry(String name, String path, boolean directory, long size, String modified) {
    }

    /**
     * @author yyf
     * @description 目录列表：当前相对路径、上一级相对路径与全部子项
     */
    public record Tree(String path, String parent, List<Entry> entries) {
    }
}
