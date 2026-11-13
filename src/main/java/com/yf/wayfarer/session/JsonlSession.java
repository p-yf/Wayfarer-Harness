package com.yf.wayfarer.session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yf.wayfarer.model.ChatMessage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 基于 jsonl 文件的会话存储。
 *
 * @author yyf
 * @description 一个文件代表一个会话，文件名为 id_name_创建时间毫秒.jsonl，每行一条消息；
 * 通过 sessionId 定位文件，达到压缩阈值时追加 COMPACT 快照
 */
public final class JsonlSession implements Session {

    private static final String SUFFIX = ".jsonl";
    private static final String SEPARATOR = "_";

    private final Path root;
    private final ObjectMapper mapper;
    private final int compactThreshold;

    public JsonlSession(Path root) {
        this(root, 0);
    }

    /**
     * @param compactThreshold 压缩阈值，<=0 表示不压缩
     */
    public JsonlSession(Path root, int compactThreshold) {
        this.root = root;
        this.compactThreshold = compactThreshold;
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public synchronized String create(String name) {
        try {
            Files.createDirectories(root);
            String id = UUID.randomUUID().toString();
            Path file = root.resolve(id + SEPARATOR + sanitize(name) + SEPARATOR
                    + Instant.now().toEpochMilli() + SUFFIX);
            Files.writeString(file, "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public SessionMeta meta(String sessionId) {
        return parseMeta(resolve(sessionId));
    }

    @Override
    public List<SessionMeta> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(SUFFIX))
                    .map(JsonlSession::parseMeta)
                    // 按创建时间倒序，最近的会话在前
                    .sorted((left, right) -> right.createdAt().compareTo(left.createdAt()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void append(String sessionId, Map<String, Object> message) {
        Path file = resolve(sessionId);
        Map<String, Object> source = new LinkedHashMap<>(message);
        SessionMessage normalized = new SessionMessage(
                String.valueOf(source.getOrDefault("messageId", UUID.randomUUID().toString())),
                source.get("turnId") == null ? null : String.valueOf(source.get("turnId")),
                MessageType.valueOf(String.valueOf(source.getOrDefault("type", MessageType.CUSTOM.name()))),
                cast(source.get("content")));
        write(file, normalized);
        compactIfNeeded(file);
    }

    @Override
    public List<SessionMessage> messages(String sessionId) {
        return parse(readLines(resolve(sessionId)));
    }

    @Override
    public synchronized void reset(String sessionId, String turnId) {
        Path file = resolve(sessionId);
        List<String> lines = readLines(file);
        int cut = cutAt(parse(lines), turnId);
        try {
            Files.writeString(file, joinLines(lines.subList(0, cut)), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String fork(String sessionId, String turnId) {
        Path file = resolve(sessionId);
        List<String> lines = readLines(file);
        int cut = cutAt(parse(lines), turnId);
        String newName = meta(sessionId).name() + "-fork";
        return copy(newName, lines.subList(0, cut));
    }

    @Override
    public String clone(String sessionId) {
        Path file = resolve(sessionId);
        return copy(meta(sessionId).name() + "-copy", readLines(file));
    }

    private void compactIfNeeded(Path file) {
        if (compactThreshold <= 0) {
            return;
        }
        List<SessionMessage> all = parse(readLines(file));
        int count = 0;
        String lastTurnId = null;
        for (int i = all.size() - 1; i >= 0; i--) {
            SessionMessage message = all.get(i);
            if (lastTurnId == null) {
                lastTurnId = message.turnId();
            }
            if (message.type() == MessageType.COMPACT) {
                break;
            }
            if (message.type() == MessageType.USER
                    || message.type() == MessageType.ASSISTANT
                    || message.type() == MessageType.TOOL) {
                count++;
            }
        }
        if (count < compactThreshold) {
            return;
        }

        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (ChatMessage message : Session.historyOf(all)) {
            snapshot.add(SessionMessage.toMap(message));
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("messages", snapshot);
        write(file, new SessionMessage(UUID.randomUUID().toString(), lastTurnId, MessageType.COMPACT, content));
    }

    private String copy(String newName, List<String> lines) {
        try {
            Files.createDirectories(root);
            String id = UUID.randomUUID().toString();
            Path target = root.resolve(id + SEPARATOR + sanitize(newName) + SEPARATOR
                    + Instant.now().toEpochMilli() + SUFFIX);
            Files.writeString(target, joinLines(lines), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(Path file, SessionMessage message) {
        try {
            String line = mapper.writeValueAsString(message) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 按会话 id 定位文件，文件名形如 id_name_创建时间毫秒.jsonl。
     */
    private Path resolve(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话 id 不能为空");
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("会话目录不存在：" + root);
        }
        try (Stream<Path> files = Files.list(root)) {
            List<Path> matched = files
                    .filter(path -> path.getFileName().toString().startsWith(sessionId + SEPARATOR))
                    .toList();
            if (matched.isEmpty()) {
                throw new IllegalArgumentException("会话不存在：" + sessionId);
            }
            if (matched.size() > 1) {
                throw new IllegalStateException("会话 id 重复：" + sessionId);
            }
            return matched.get(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SessionMeta parseMeta(Path file) {
        String fileName = file.getFileName().toString();
        String base = fileName.substring(0, fileName.length() - SUFFIX.length());
        int first = base.indexOf(SEPARATOR);
        int last = base.lastIndexOf(SEPARATOR);
        return new SessionMeta(
                base.substring(0, first),
                base.substring(first + 1, last),
                Instant.ofEpochMilli(Long.parseLong(base.substring(last + 1))));
    }

    /**
     * 找该轮次第一条消息的位置，轮次不存在时报错。
     */
    private static int cutAt(List<SessionMessage> messages, String turnId) {
        for (int i = 0; i < messages.size(); i++) {
            if (turnId.equals(messages.get(i).turnId())) {
                return i;
            }
        }
        throw new IllegalArgumentException("会话中不存在轮次：" + turnId);
    }

    /**
     * 拼接为文件内容，每行后都带换行，避免后续追加时与最后一行粘连。
     */
    private static String joinLines(List<String> lines) {
        if (lines.isEmpty()) {
            return "";
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private List<String> readLines(Path file) {
        try {
            if (!Files.exists(file)) {
                return List.of();
            }
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<SessionMessage> parse(List<String> lines) {
        List<SessionMessage> messages = new ArrayList<>();
        for (String line : lines) {
            try {
                messages.add(mapper.readValue(line, SessionMessage.class));
            } catch (IOException e) {
                throw new IllegalStateException("会话文件解析失败：" + line, e);
            }
        }
        return messages;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /**
     * 文件名不允许出现路径分隔符、冒号、下划线等字符，统一替换为短横线。
     */
    private static String sanitize(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|_\\s]+", "-");
        return cleaned.isBlank() ? "session" : cleaned;
    }
}
