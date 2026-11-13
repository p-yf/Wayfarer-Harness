package com.yf.wayfarer.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * jsonl 配置存储。
 *
 * @author yyf
 * @description 目录下每个配置文件是一个 jsonl 文件，文件名为配置名称，单行保存一份配置，
 * 供模型配置与记忆压缩配置共用
 */
public final class ConfigStore<T> {

    private static final String SUFFIX = ".jsonl";
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path root;
    private final Class<T> type;

    public ConfigStore(Path root, Class<T> type) {
        this.root = root;
        this.type = type;
    }

    /**
     * 列出全部配置，按配置名称排序。
     */
    public Map<String, T> list() {
        Map<String, T> configs = new LinkedHashMap<>();
        for (Path file : files()) {
            configs.put(name(file), readFile(file));
        }
        return configs;
    }

    /**
     * 读取单个配置，不存在时报错。
     */
    public T read(String name) {
        Path file = resolve(name);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("配置不存在：" + name);
        }
        return readFile(file);
    }

    /**
     * 新建或覆盖一个配置。
     */
    public void save(String name, T config) {
        if (config == null) {
            throw new IllegalArgumentException("配置不能为空");
        }
        Path file = resolve(name);
        try {
            Files.createDirectories(root);
            Files.writeString(file, JSON.writeValueAsString(config) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 删除一个配置，返回是否存在并被删除。
     */
    public boolean delete(String name) {
        try {
            return Files.deleteIfExists(resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 取第一个满足条件的配置，没有时返回 null。
     */
    public T first(Predicate<T> filter) {
        for (T config : list().values()) {
            if (filter.test(config)) {
                return config;
            }
        }
        return null;
    }

    private List<Path> files() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(SUFFIX))
                    .sorted((left, right) -> name(left).compareTo(name(right)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private T readFile(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (!line.isBlank()) {
                    return JSON.readValue(line, type);
                }
            }
            throw new IllegalArgumentException("配置文件为空：" + file.getFileName());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path resolve(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("配置名称不能为空");
        }
        return root.resolve(sanitize(name) + SUFFIX);
    }

    private static String name(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.substring(0, fileName.length() - SUFFIX.length());
    }

    /**
     * 文件名不允许出现路径分隔符与冒号等字符，统一替换为短横线。
     */
    private static String sanitize(String value) {
        return value.trim().replaceAll("[\\\\/:*?\"<>|]+", "-");
    }
}
