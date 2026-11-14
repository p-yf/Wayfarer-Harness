package com.yf.wayfarer.session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 卸载消息存储。
 *
 * @author yyf
 * @description 卸载内容存放在会话自己的文件夹里，文件名固定为 unloadmessages.jsonl，每行一条 {uuid, content}；
 * 压缩时把大消息正文搬到此处，正文位置只保留占位符，删除会话时随文件夹一起删除
 */
public final class UnloadedMessageStore {

    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public UnloadedMessageStore(Path root) {
        this.root = root;
    }

    /**
     * 保存一条卸载内容，返回用于占位符的 uuid。
     */
    public synchronized String save(String sessionId, String content) {
        String uuid = UUID.randomUUID().toString();
        try {
            String line = mapper.writeValueAsString(new UnloadedMessage(uuid, content)) + System.lineSeparator();
            Files.writeString(file(sessionId), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return uuid;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 读取一条卸载内容，不存在时返回 null。
     */
    public String read(String sessionId, String uuid) {
        for (UnloadedMessage message : messages(sessionId)) {
            if (message.uuid().equals(uuid)) {
                return message.content();
            }
        }
        return null;
    }

    /**
     * 指定会话已卸载的全部 id，按写入顺序。
     */
    public List<String> ids(String sessionId) {
        List<String> ids = new ArrayList<>();
        for (UnloadedMessage message : messages(sessionId)) {
            ids.add(message.uuid());
        }
        return ids;
    }

    /**
     * 删除一条卸载记录，返回是否存在并被删除。
     */
    public synchronized boolean delete(String sessionId, String uuid) {
        List<UnloadedMessage> all = messages(sessionId);
        List<UnloadedMessage> kept = all.stream()
                .filter(message -> !message.uuid().equals(uuid))
                .collect(Collectors.toList());
        if (kept.size() == all.size()) {
            return false;
        }
        rewrite(sessionId, kept);
        return true;
    }

    /**
     * 把源会话的全部卸载内容复制到目标会话，保留原 uuid，
     * 使 fork、clone 出来的会话中已有的占位符仍能解析到原文。
     */
    public synchronized void copyAll(String fromSessionId, String toSessionId) {
        List<UnloadedMessage> all = messages(fromSessionId);
        if (all.isEmpty()) {
            return;
        }
        try {
            Files.writeString(file(toSessionId), serialize(all), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<UnloadedMessage> messages(String sessionId) {
        Path file = file(sessionId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<UnloadedMessage> messages = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (!line.isBlank()) {
                    messages.add(mapper.readValue(line, UnloadedMessage.class));
                }
            }
            return messages;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void rewrite(String sessionId, List<UnloadedMessage> messages) {
        try {
            Files.writeString(file(sessionId), serialize(messages), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 把若干条卸载记录拼成文件内容，每行后带换行。
     */
    private String serialize(List<UnloadedMessage> messages) {
        StringBuilder content = new StringBuilder();
        for (UnloadedMessage message : messages) {
            try {
                content.append(mapper.writeValueAsString(message)).append(System.lineSeparator());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return content.toString();
    }

    /**
     * 卸载文件位于会话文件夹内，与主消息文件同级。
     */
    private Path file(String sessionId) {
        return SessionDirectory.resolve(root, sessionId).resolve(SessionDirectory.UNLOADED_FILE);
    }
}
