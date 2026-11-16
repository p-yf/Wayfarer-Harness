package com.yf.wayfarer.session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 基于 jsonl 文件的会话存储。
 *
 * @author yyf
 * @description 一个文件夹代表一个会话，文件夹名为 id_名称_创建时间毫秒，主文件为其中的 session.jsonl，
 * 每行一条消息；通过 sessionId 定位文件夹，达到压缩阈值时追加 COMPACT 快照。
 * 读取与复制全部按行流式处理，避免大会话一次性载入内存
 */
public final class JsonlSession implements Session {

    private static final Logger LOG = LoggerFactory.getLogger(JsonlSession.class);

    /**
     * 改写文件时使用的临时文件后缀，写完后再整体替换原文件。
     */
    private static final String TMP_SUFFIX = ".tmp";

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
            Path directory = root.resolve(SessionDirectory.name(id, name, Instant.now().toEpochMilli()));
            Files.createDirectories(directory);
            // 首行固定是文件元数据：只放任意键值对，不带 messageId 与 traceId
            Files.writeString(directory.resolve(SessionDirectory.MAIN_FILE),
                    metadataLine(Map.of()) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public SessionMeta meta(String sessionId) {
        return parseMeta(SessionDirectory.resolve(root, sessionId));
    }

    @Override
    public synchronized void rename(String sessionId, String name) {
        Path directory = SessionDirectory.resolve(root, sessionId);
        SessionMeta meta = parseMeta(directory);
        Path target = root.resolve(SessionDirectory.name(meta.id(), name, meta.createdAt().toEpochMilli()));
        if (target.equals(directory)) {
            return;
        }
        try {
            Files.move(directory, target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void updateMetadata(String sessionId, Map<String, Object> content) {
        Path file = resolve(sessionId);
        String header = metadataLine(content == null ? Map.of() : content);
        Path temp = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
        try {
            if (!Files.exists(file)) {
                Files.writeString(file, header + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return;
            }
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                 BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write(header);
                writer.newLine();
                // 资源管理器、编辑器等工具可能重排首行之外的内容，只替换或插入元数据行，其余行原样保留
                String first = nextNonBlank(reader);
                if (first != null && !isMetadata(first)) {
                    writer.write(first);
                    writer.newLine();
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    writer.write(line);
                    writer.newLine();
                }
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<SessionMeta> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries
                    // 只认含会话主文件的文件夹，避免把同目录下的其他数据目录当成会话
                    .filter(path -> Files.isRegularFile(path.resolve(SessionDirectory.MAIN_FILE)))
                    .map(this::parseMeta)
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
                source.get("traceId") == null ? null : String.valueOf(source.get("traceId")),
                JSONMsgType.valueOf(String.valueOf(source.getOrDefault("type", JSONMsgType.CUSTOM.name()))),
                cast(source.get("content")),
                MessageOrigin.fromMap(castOrNull(source.get("origin"))));
        write(file, normalized);
        compactIfNeeded(file);
    }

    @Override
    public List<SessionMessage> messages(String sessionId) {
        List<SessionMessage> messages = new ArrayList<>();
        // 元数据行属于文件头，不参与对话消息与历史回放
        scan(resolve(sessionId), (index, line, message) -> {
            if (message.type() != JSONMsgType.METADATA) {
                messages.add(message);
            }
            return true;
        });
        return messages;
    }

    @Override
    public List<ChatMessage> history(String sessionId) {
        Restored restored = new Restored();
        scan(resolve(sessionId), (index, line, message) -> {
            apply(restored, message);
            return true;
        });
        return restored.history;
    }

    @Override
    public void restore(String sessionId, AgentContext context) {
        Restored restored = new Restored();
        scan(resolve(sessionId), (index, line, message) -> {
            apply(restored, message);
            return true;
        });
        context.put(AgentContext.MESSAGES, restored.history);
        // 最后一条 CUSTOM 是上下文快照，逐个键写回
        if (restored.custom != null) {
            restored.custom.forEach(context::put);
        }
    }

    @Override
    public synchronized void delete(String sessionId) {
        // 会话即文件夹，删除整个目录，卸载消息等附属文件一并清理
        deleteRecursively(SessionDirectory.resolve(root, sessionId));
    }

    @Override
    public synchronized void reset(String sessionId, String traceId) {
        Path file = resolve(sessionId);
        int cut = firstIndexOfTrace(file, traceId);
        if (cut < 0) {
            throw new IllegalArgumentException("会话中不存在 traceId：" + traceId);
        }
        Path temp = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
        try {
            copyLines(file, temp, cut);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized String fork(String sessionId, String traceId) {
        Path file = resolve(sessionId);
        int cut = lastIndexOfTrace(file, traceId);
        if (cut < 0) {
            throw new IllegalArgumentException("会话中不存在 traceId：" + traceId);
        }
        // 分支包含该轮次的全部消息，即截止到最后一条匹配行
        return copy(meta(sessionId).name() + "-fork", file, cut + 1);
    }

    @Override
    public synchronized String clone(String sessionId) {
        return copy(meta(sessionId).name() + "-copy", resolve(sessionId), Integer.MAX_VALUE);
    }

    private void compactIfNeeded(Path file) {
        if (compactThreshold <= 0) {
            return;
        }
        CompactScan state = new CompactScan();
        scan(file, (index, line, message) -> state.accept(message));
        if (state.count < compactThreshold) {
            return;
        }

        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (ChatMessage message : state.restored.history) {
            snapshot.add(SessionMessage.toMap(message));
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("messages", snapshot);
        write(file, new SessionMessage(UUID.randomUUID().toString(), state.lastTraceId,
                JSONMsgType.COMPACT, content));
    }

    /**
     * 把源会话的前 maxLines 条非空行复制到新会话，逐行流式写入。
     */
    private String copy(String newName, Path source, int maxLines) {
        try {
            Files.createDirectories(root);
            String id = UUID.randomUUID().toString();
            Path directory = root.resolve(SessionDirectory.name(id, newName, Instant.now().toEpochMilli()));
            Files.createDirectories(directory);
            copyLines(source, directory.resolve(SessionDirectory.MAIN_FILE), maxLines);
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 递归删除会话文件夹。
     */
    private static void deleteRecursively(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
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
     * 按会话 id 定位主消息文件，会话文件夹名形如 id_名称_创建时间毫秒。
     */
    private Path resolve(String sessionId) {
        return SessionDirectory.resolve(root, sessionId).resolve(SessionDirectory.MAIN_FILE);
    }

    private SessionMeta parseMeta(Path directory) {
        String dirName = directory.getFileName().toString();
        int first = dirName.indexOf(SessionDirectory.SEPARATOR);
        int last = dirName.lastIndexOf(SessionDirectory.SEPARATOR);
        return new SessionMeta(
                dirName.substring(0, first),
                dirName.substring(first + 1, last),
                Instant.ofEpochMilli(Long.parseLong(dirName.substring(last + 1))),
                readMetadata(directory.resolve(SessionDirectory.MAIN_FILE)));
    }

    /**
     * 只读文件首行的元数据键值对，旧文件没有元数据行时返回空映射。
     */
    private Map<String, Object> readMetadata(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String first = nextNonBlank(reader);
            if (!isMetadata(first)) {
                return Map.of();
            }
            return mapper.readValue(first, SessionMessage.class).content();
        } catch (IOException e) {
            return Map.of();
        }
    }

    /**
     * 元数据行只有 type 与 content，不写 messageId、traceId。
     */
    private String metadataLine(Map<String, Object> content) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", JSONMsgType.METADATA.name());
        line.put("content", content);
        try {
            return mapper.writeValueAsString(line);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isMetadata(String line) {
        return line != null && line.replace(" ", "").startsWith("{\"type\":\"METADATA\"");
    }

    /**
     * 逐行流式扫描会话主文件，按需决定是否继续。
     *
     * @description 最后一行可能因宕机被截断：只有最后一条非空行解析失败时记录警告并跳过，
     * 其余行解析失败仍然抛出，避免掩盖真实损坏；读取与追加并发时同样按此处理
     */
    private void scan(Path file, LineVisitor visitor) {
        if (!Files.exists(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String pending = null;
            int index = 0;
            boolean tailBroken = false;
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    // 延后一行解析，才能知道当前行是否是文件最后一条非空行
                    if (pending != null && !visitor.visit(index++, pending, parseStrict(pending))) {
                        return;
                    }
                    pending = line;
                }
            } catch (MalformedInputException e) {
                // 截断的尾行可能断在多字节字符中间，连解码都无法完成
                tailBroken = true;
            }
            if (pending == null) {
                return;
            }
            SessionMessage message = tailBroken ? parseStrict(pending) : parseTail(pending);
            if (message != null) {
                visitor.visit(index, pending, message);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private SessionMessage parseStrict(String line) {
        try {
            return mapper.readValue(line, SessionMessage.class);
        } catch (IOException e) {
            throw new IllegalStateException("会话文件解析失败：" + line, e);
        }
    }

    /**
     * 尾行解析失败时只跳过该行，不影响其余消息的回放。
     */
    private SessionMessage parseTail(String line) {
        try {
            return mapper.readValue(line, SessionMessage.class);
        } catch (IOException e) {
            LOG.warn("忽略损坏的会话尾行：{}，原因：{}", abbreviate(line), e.getMessage());
            return null;
        }
    }

    /**
     * 该 trace 第一条消息在文件中的非空行序号，不存在时返回 -1。
     */
    private int firstIndexOfTrace(Path file, String traceId) {
        int[] found = {-1};
        scan(file, (index, line, message) -> {
            if (!traceId.equals(message.traceId())) {
                return true;
            }
            found[0] = index;
            return false;
        });
        return found[0];
    }

    /**
     * 该 trace 最后一条消息在文件中的非空行序号，不存在时返回 -1。
     */
    private int lastIndexOfTrace(Path file, String traceId) {
        int[] last = {-1};
        scan(file, (index, line, message) -> {
            if (traceId.equals(message.traceId())) {
                last[0] = index;
            }
            return true;
        });
        return last[0];
    }

    /**
     * 把源文件的前 maxLines 条非空行逐行复制到目标文件。
     */
    private static void copyLines(Path source, Path target, int maxLines) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            int copied = 0;
            String line;
            while (copied < maxLines && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                writer.write(line);
                writer.newLine();
                copied++;
            }
        }
    }

    private static String nextNonBlank(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return null;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("临时文件删除失败：{}", file, e);
        }
    }

    private static String abbreviate(String line) {
        return line.length() <= 200 ? line : line.substring(0, 200) + "…";
    }

    /**
     * 把一条会话消息并入流式还原状态。
     */
    private static void apply(Restored restored, SessionMessage message) {
        switch (message.type()) {
            case COMPACT -> {
                // 以最近一条压缩快照为新的基线，丢弃之前的累积内容
                restored.history.clear();
                Object base = message.content().get("messages");
                if (base instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            restored.history.add(SessionMessage.toChatMessage(cast(map)));
                        }
                    }
                }
            }
            // 最后一条 CUSTOM 是上下文快照
            case CUSTOM -> restored.custom = message.content();
            case USER, ASSISTANT, TOOL -> {
                // 只有 root 的对话消息进入模型历史：子 Agent 的过程不污染父上下文
                if (message.origin() == null) {
                    restored.history.add(SessionMessage.toChatMessage(message.content()));
                }
            }
            default -> {
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castOrNull(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /**
     * 流式扫描时逐行回调，返回 false 表示停止后续扫描。
     */
    @FunctionalInterface
    private interface LineVisitor {

        boolean visit(int index, String line, SessionMessage message);
    }

    /**
     * 流式还原历史的累积状态。
     */
    private static final class Restored {

        private final List<ChatMessage> history = new ArrayList<>();

        private Map<String, Object> custom;
    }

    /**
     * 压缩判定所需的累积状态：还原出的基线历史、最近一条压缩之后的对话消息数量、最后一条消息的 trace id。
     */
    private static final class CompactScan {

        private final Restored restored = new Restored();

        private int count;

        private String lastTraceId;

        private boolean accept(SessionMessage message) {
            lastTraceId = message.traceId();
            if (message.type() == JSONMsgType.COMPACT) {
                count = 0;
            } else if (message.type() == JSONMsgType.USER
                    || message.type() == JSONMsgType.ASSISTANT
                    || message.type() == JSONMsgType.TOOL) {
                count++;
            }
            apply(restored, message);
            return true;
        }
    }
}
