package com.yf.wayfarer.change;

import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.SessionMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话 reset 的文件回退测试，使用真实文件系统与真实 JGit 仓库。
 *
 * @author yyf
 * @description 覆盖边界计算、其它会话隔离、reset 到第一轮、冲突跳过
 */
class ChangeServiceResetTest {

    @TempDir
    Path temp;

    private Path workspace;
    private ShadowRepository repository;
    private ChangeService service;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(temp.resolve("ws"));
        Files.writeString(workspace.resolve("a.txt"), "a0", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("b.txt"), "b0", StandardCharsets.UTF_8);

        ShadowRepositoryStore store = new ShadowRepositoryStore(
                temp.resolve("shadow"), List.of(".git", ".wayfarer"), true);
        repository = store.forWorkspace(workspace.toString());
        repository.ensure();
        service = new ChangeService(store);
    }

    @Test
    void 按边界回退本会话改动且不影响其它会话() {
        write("a.txt", "a1");
        commit(Set.of("a.txt"), "s1", "t1");
        write("a.txt", "a2");
        commit(Set.of("a.txt"), "s1", "t2");
        write("c.txt", "c1");
        commit(Set.of("c.txt"), "s1", "t3");
        // 另一个会话在同一工作区的改动
        write("b.txt", "b1");
        commit(Set.of("b.txt"), "s2", "t9");

        Map<String, Object> result = service.revertSessionChanges("s1", Set.of("t1"), "t2");

        assertEquals(1, result.get("reverted"), "应生成一条回退提交");
        assertEquals("a1", read("a.txt"), "t2 的改动应被撤销");
        assertFalse(Files.exists(workspace.resolve("c.txt")), "t3 新增的文件应被删除");
        assertEquals("b1", read("b.txt"), "其它会话的改动不能被牵连");
    }

    @Test
    void reset到第一轮时回退本会话全部改动() {
        write("a.txt", "a1");
        commit(Set.of("a.txt"), "s1", "t1");
        write("b.txt", "b1");
        commit(Set.of("b.txt"), "s1", "t2");

        service.revertSessionChanges("s1", Set.of(), "t1");

        assertEquals("a0", read("a.txt"));
        assertEquals("b0", read("b.txt"));
    }

    @Test
    void 之后被手工改过的文件也按撤销内容覆盖() {
        write("a.txt", "a1");
        commit(Set.of("a.txt"), "s1", "t1");
        write("a.txt", "a2");
        commit(Set.of("a.txt"), "s1", "t2");
        // 模拟用户之后又手工改了这个文件
        write("a.txt", "manual");

        Map<String, Object> result = service.revertSessionChanges("s1", Set.of("t1"), "t2");

        assertEquals(1, result.get("reverted"));
        assertEquals("a1", read("a.txt"), "撤销优先：工作区后改的内容应被覆盖成边界之前的内容");
    }

    @Test
    void 保留轮次按会话文件顺序计算() {
        List<SessionMessage> messages = List.of(
                message("t1"), message("t2"), message("t3"));

        assertEquals(Set.of("t1"), ChangeService.survivingTraces(messages, "t2"));
        assertEquals(Set.of(), ChangeService.survivingTraces(messages, "t1"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ChangeService.survivingTraces(messages, "不存在"));
        assertTrue(error.getMessage().contains("不存在"));
    }

    private static SessionMessage message(String traceId) {
        return new SessionMessage("m-" + traceId, traceId, JSONMsgType.USER, Map.of());
    }

    private void commit(Set<String> paths, String sessionId, String traceId) {
        Map<String, String> trailers = new LinkedHashMap<>();
        trailers.put(ShadowRepository.TRAILER_SESSION, sessionId);
        trailers.put(ShadowRepository.TRAILER_TRACE, traceId);
        trailers.put(ShadowRepository.TRAILER_TOOL, "write");
        trailers.put(ShadowRepository.TRAILER_CALL, "call-" + traceId);
        repository.commit(paths, "write " + paths, trailers);
    }

    private void write(String path, String content) {
        try {
            Files.writeString(workspace.resolve(path), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String read(String path) {
        try {
            return Files.readString(workspace.resolve(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
