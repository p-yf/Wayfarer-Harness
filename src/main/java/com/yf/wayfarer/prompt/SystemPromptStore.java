package com.yf.wayfarer.prompt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 系统提示词存储。
 *
 * @author yyf
 * @description 目录下每个提示词是一个 md 文件，文件名为「agent名称-提示词名称-顺序」，
 * 按顺序从小到大拼接后注入上下文
 */
public final class SystemPromptStore {

    private static final String SUFFIX = ".md";

    private final Path root;

    public SystemPromptStore(Path root) {
        this.root = root;
    }

    /**
     * 读取指定 agent 的全部提示词，按顺序拼接。
     */
    public String load(String agent) {
        List<SystemPromptFile> files = list(agent);
        StringBuilder prompt = new StringBuilder();
        for (SystemPromptFile file : files) {
            if (file.content().isBlank()) {
                continue;
            }
            if (!prompt.isEmpty()) {
                prompt.append("\n\n");
            }
            prompt.append(file.content().strip());
        }
        return prompt.toString();
    }

    /**
     * 读取指定 agent 的全部提示词，键为「提示词名称-顺序」，值为内容。
     */
    public Map<String, String> loadMap(String agent) {
        Map<String, String> prompts = new LinkedHashMap<>();
        for (SystemPromptFile file : list(agent)) {
            if (!file.content().isBlank()) {
                prompts.put(file.name() + "-" + file.order(), file.content());
            }
        }
        return prompts;
    }

    /**
     * 列出指定 agent 的全部提示词，按顺序升序。
     */
    public List<SystemPromptFile> list(String agent) {
        requireName(agent, "agent 名称");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            List<SystemPromptFile> prompts = new ArrayList<>();
            for (Path path : files.toList()) {
                parse(agent, path).ifPresent(prompts::add);
            }
            prompts.sort(Comparator.comparingInt(SystemPromptFile::order));
            return prompts;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 读取单个提示词，不存在时返回 null。
     */
    public String read(String agent, String name, int order) {
        Path file = file(agent, name, order);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 新建或覆盖一个提示词。
     */
    public void save(String agent, String name, int order, String content) {
        Path file = file(agent, name, order);
        try {
            Files.createDirectories(root);
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 删除一个提示词，返回是否存在并被删除。
     */
    public boolean delete(String agent, String name, int order) {
        try {
            return Files.deleteIfExists(file(agent, name, order));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 解析文件名：agent-name-order.md，名称允许包含短横线，顺序取最后一段。
     */
    private Optional<SystemPromptFile> parse(String agent, Path path) {
        String fileName = path.getFileName().toString();
        if (!fileName.endsWith(SUFFIX)) {
            return Optional.empty();
        }
        String base = fileName.substring(0, fileName.length() - SUFFIX.length());
        if (!base.startsWith(agent + "-")) {
            return Optional.empty();
        }
        String rest = base.substring(agent.length() + 1);
        int separator = rest.lastIndexOf('-');
        if (separator <= 0) {
            return Optional.empty();
        }
        int order;
        try {
            order = Integer.parseInt(rest.substring(separator + 1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        String name = rest.substring(0, separator);
        try {
            return Optional.of(new SystemPromptFile(agent, name, order,
                    Files.readString(path, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(String agent, String name, int order) {
        requireName(agent, "agent 名称");
        requireName(name, "提示词名称");
        if (order < 0) {
            throw new IllegalArgumentException("顺序不能为负数");
        }
        return root.resolve(sanitize(agent) + "-" + sanitize(name) + "-" + order + SUFFIX);
    }

    private static void requireName(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
    }

    /**
     * 文件名不允许出现路径分隔符与冒号，短横线是分隔符，统一替换为下划线。
     */
    private static String sanitize(String value) {
        return value.trim().replaceAll("[\\\\/:*?\"<>|\\-]+", "_");
    }
}
