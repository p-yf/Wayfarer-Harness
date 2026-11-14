package com.yf.wayfarer.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 会话目录结构。
 *
 * @author yyf
 * @description 一个会话对应 sessions 下的一个文件夹，文件夹名为 id_名称_创建时间毫秒；
 * 会话主文件固定为 session.jsonl，卸载消息固定为 unloadmessages.jsonl，删除会话即删除整个文件夹
 */
final class SessionDirectory {

    static final String MAIN_FILE = "session.jsonl";
    static final String UNLOADED_FILE = "unloadmessages.jsonl";
    static final String SEPARATOR = "_";

    private SessionDirectory() {
    }

    /**
     * 会话文件夹名。
     */
    static String name(String sessionId, String sessionName, long millis) {
        return sessionId + SEPARATOR + sanitize(sessionName) + SEPARATOR + millis;
    }

    /**
     * 按会话 id 定位会话文件夹，文件夹名以 id_ 开头。
     */
    static Path resolve(Path root, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话 id 不能为空");
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("会话目录不存在：" + root);
        }
        try (Stream<Path> entries = Files.list(root)) {
            List<Path> matched = entries
                    .filter(Files::isDirectory)
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

    /**
     * 文件与目录名不允许出现路径分隔符、冒号等字符，统一替换为短横线；
     * 下划线是元数据分隔符，同样需要替换。空格合法，保留原样。
     */
    static String sanitize(String sessionName) {
        String cleaned = sessionName == null ? "" : sessionName.trim()
                .replaceAll("[\\\\/:*?\"<>|]+", "-")
                .replace('_', '-');
        return cleaned.isBlank() ? "session" : cleaned;
    }
}
