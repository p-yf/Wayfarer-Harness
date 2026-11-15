package com.yf.wayfarer.change;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 影子仓库测试，使用真实文件系统与真实 JGit 仓库，不使用模拟对象。
 *
 * @author yyf
 * @description 覆盖基线提交、脏文件归因、diff、单次撤销与冲突跳过
 */
class ShadowRepositoryTest {

    @TempDir
    Path temp;

    private Path workspace;
    private ShadowRepositoryStore store;
    private ShadowRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(temp.resolve("ws"));
        Files.writeString(workspace.resolve("a.txt"), "v1", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("b.txt"), "b1", StandardCharsets.UTF_8);
        Files.createDirectories(workspace.resolve("target"));
        Files.writeString(workspace.resolve("target/ignored.txt"), "x", StandardCharsets.UTF_8);

        store = new ShadowRepositoryStore(
                temp.resolve("shadow"), List.of(".git", ".wayfarer", "target", "sessions"), true);
        repository = store.forWorkspace(workspace.toString());
        assertNotNull(repository);
        repository.ensure();
    }

    @Test
    void 删除工作区会清理影子仓库但保留工作区文件() throws IOException {
        assertTrue(store.deleteWorkspace(workspace.toString()), "已建立的影子仓库应被删除");
        try (Stream<Path> entries = Files.list(temp.resolve("shadow"))) {
            assertEquals(0, entries.count(), "影子仓库目录应被清空");
        }
        assertTrue(Files.isRegularFile(workspace.resolve("a.txt")), "工作区文件不应受影响");
        assertFalse(store.deleteWorkspace(workspace.toString()), "仓库不存在时不应报告删除成功");

        // 再次访问同一工作区会按基线重新建库，说明旧历史已随之清理
        ShadowRepository recreated = store.forWorkspace(workspace.toString());
        assertNotNull(recreated);
        recreated.ensure();
        assertEquals(1, recreated.commits().size(), "重建后只应有基线提交");
    }

    @Test
    void 首次使用创建基线与工具提交() {
        assertEquals(1, repository.commits().size(), "首次 ensure 应只生成一条基线提交");
        assertTrue(repository.dirtyPaths().isEmpty(), "基线提交后工作区应是干净的");

        Set<String> before = repository.dirtyPaths();
        write("a.txt", "v2");
        Set<String> changed = subtract(repository.dirtyPaths(), before);
        assertEquals(Set.of("a.txt"), changed);

        String commitId = commit(changed, "session-1", "trace-1", "write");
        assertNotNull(commitId);

        List<ChangeCommit> commits = repository.commits();
        assertEquals(2, commits.size());
        ChangeCommit latest = commits.get(0);
        assertEquals("session-1", latest.sessionId());
        assertEquals("trace-1", latest.traceId());
        assertEquals("write", latest.tool());
        assertEquals("call-1", latest.toolCallId());

        List<ChangeFile> files = repository.files(commitId);
        assertEquals(1, files.size());
        assertEquals("a.txt", files.get(0).path());
        assertEquals("MODIFY", files.get(0).changeType());
        assertTrue(files.get(0).insertions() + files.get(0).deletions() > 0);
    }

    @Test
    void 调用前已脏的文件不会被带入本次提交() {
        // 模拟用户自己改了一半的文件：调用前就是脏的
        write("b.txt", "b-dirty");
        Set<String> before = repository.dirtyPaths();
        assertTrue(before.contains("b.txt"));

        write("a.txt", "v2");
        Set<String> changed = subtract(repository.dirtyPaths(), before);

        assertEquals(Set.of("a.txt"), changed, "只应记录本次调用产生的改动");
    }

    @Test
    void diff返回改动内容() {
        write("a.txt", "v2");
        String commitId = commit(Set.of("a.txt"), "session-1", "trace-1", "write");
        String parentId = repository.commits().get(0).parentId();

        String patch = repository.diffRange(parentId, commitId, "a.txt");
        assertTrue(patch.contains("-v1"), "补丁应包含删除行：" + patch);
        assertTrue(patch.contains("+v2"), "补丁应包含新增行：" + patch);
    }

    @Test
    void 撤销单文件恢复到父提交并生成回退提交() {
        write("a.txt", "v2");
        commit(Set.of("a.txt"), "session-1", "trace-1", "write");
        write("a.txt", "v3");
        String second = commit(Set.of("a.txt"), "session-1", "trace-1", "write");

        RevertOutcome outcome = repository.revert(List.of(second), "a.txt", "revert a.txt",
                trailers("session-1", "trace-1", "manual-revert"));

        assertNotNull(outcome.commitId());
        assertEquals("v2", read("a.txt"), "应恢复到该提交之前的版本");
        assertEquals("revert", repository.commits().get(0).tool());
    }

    @Test
    void 撤销新增文件会删除文件() {
        write("c.txt", "new");
        String commitId = commit(Set.of("c.txt"), "session-1", "trace-1", "write");
        assertTrue(Files.exists(workspace.resolve("c.txt")));

        RevertOutcome outcome = repository.revert(List.of(commitId), null, "revert c.txt",
                trailers("session-1", "trace-1", "manual-revert"));

        assertNotNull(outcome.commitId());
        assertFalse(Files.exists(workspace.resolve("c.txt")), "新增文件撤销后应被删除");
    }

    @Test
    void 文件之后被手工改过时以撤销内容覆盖() {
        write("a.txt", "v2");
        String commitId = commit(Set.of("a.txt"), "session-1", "trace-1", "write");
        // 模拟用户/其它会话在之后又改了同一个文件
        write("a.txt", "user-edit");

        RevertOutcome outcome = repository.revert(List.of(commitId), "a.txt", "revert a.txt",
                trailers("session-1", "trace-1", "manual-revert"));

        assertNotNull(outcome.commitId(), "覆盖式撤销应产生回退提交");
        assertEquals("v1", read("a.txt"), "撤销优先：工作区后改的内容应被覆盖");
    }

    @Test
    void 排除目录的改动不进入记录() throws IOException {
        Files.writeString(workspace.resolve("target/build.txt"), "artifact", StandardCharsets.UTF_8);
        assertFalse(repository.dirtyPaths().contains("target/build.txt"),
                "排除目录里的文件不应出现在脏文件集合里");
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

    private String commit(Set<String> paths, String sessionId, String traceId, String tool) {
        Map<String, String> trailers = new LinkedHashMap<>();
        trailers.put(ShadowRepository.TRAILER_SESSION, sessionId);
        trailers.put(ShadowRepository.TRAILER_TRACE, traceId);
        trailers.put(ShadowRepository.TRAILER_TOOL, tool);
        trailers.put(ShadowRepository.TRAILER_CALL, "call-1");
        return repository.commit(paths, tool + " test", trailers);
    }

    private static Map<String, String> trailers(String sessionId, String traceId, String reason) {
        Map<String, String> trailers = new LinkedHashMap<>();
        trailers.put(ShadowRepository.TRAILER_SESSION, sessionId);
        trailers.put(ShadowRepository.TRAILER_TRACE, traceId);
        trailers.put(ShadowRepository.TRAILER_TOOL, "revert");
        trailers.put(ShadowRepository.TRAILER_REASON, reason);
        return trailers;
    }

    private static Set<String> subtract(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }
}
