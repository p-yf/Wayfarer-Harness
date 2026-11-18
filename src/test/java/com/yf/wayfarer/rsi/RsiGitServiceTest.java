package com.yf.wayfarer.rsi;

import com.yf.wayfarer.rsi.model.RsiCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RsiGitService 单测。
 *
 * @author yyf
 * @description 覆盖基线创建、按内容提交、trailer 解析、diff、reset --hard 后线性续提交、
 * 历史文件读取与文件树；全部基于 @TempDir 真实仓库，不做 mock
 */
class RsiGitServiceTest {

    @TempDir
    Path temp;

    private RsiGitService service() {
        return new RsiGitService(new RsiPaths(temp.resolve("rsi").toString()));
    }

    @Test
    void ensureCreatesBaselineCommit() {
        RsiGitService git = service();
        git.ensure("wayfarer-rsi", "skill");
        List<RsiCommit> commits = git.log("wayfarer-rsi", "skill");
        assertEquals(1, commits.size());
        assertEquals("baseline", commits.get(0).summary());
        // 基线提交没有父提交
        assertNull(commits.get(0).parentHash());
    }

    @Test
    void logOnMissingRepoIsEmpty() {
        assertTrue(service().log("wayfarer", "memory").isEmpty());
        assertTrue(service().files("wayfarer", "memory", null).isEmpty());
        assertNull(service().head("wayfarer", "memory"));
    }

    @Test
    void commitWritesTrailerAndDiff() throws Exception {
        RsiGitService git = service();
        Path workTree = new RsiPaths(temp.resolve("rsi").toString()).skillsDir("wayfarer-rsi");
        Files.createDirectories(workTree.resolve("pdf-edit"));
        Files.writeString(workTree.resolve("pdf-edit/SKILL.md"), "v1", StandardCharsets.UTF_8);

        String commitId = git.commit("wayfarer-rsi", "skill", "skill: create pdf-edit（session s-1）",
                Map.of(RsiGitService.TRAILER_KIND, "skill",
                        RsiGitService.TRAILER_AGENT, "wayfarer-rsi",
                        RsiGitService.TRAILER_SOURCE_SESSION, "s-1",
                        RsiGitService.TRAILER_TRACE_ID, "t-1",
                        RsiGitService.TRAILER_LAYER, "skill"));
        assertNotNull(commitId);

        List<RsiCommit> commits = git.log("wayfarer-rsi", "skill");
        assertEquals(2, commits.size());
        RsiCommit latest = commits.get(0);
        assertEquals("skill: create pdf-edit（session s-1）", latest.summary());
        assertEquals("s-1", latest.sourceSession());
        assertEquals("t-1", latest.traceId());
        assertEquals("skill", latest.layer());
        // diff 应包含新增文件内容
        String diff = git.diff("wayfarer-rsi", "skill", commitId);
        assertTrue(diff.contains("+++ b/pdf-edit/SKILL.md"));
        assertTrue(diff.contains("v1"));
    }

    @Test
    void commitWithNoChangeReturnsNull() {
        RsiGitService git = service();
        git.ensure("wayfarer-rsi", "memory");
        // store 为空，没有可提交内容
        assertNull(git.commit("wayfarer-rsi", "memory", "memory(l1): 空提交", Map.of()));
    }

    @Test
    void resetHardMovesHeadAndLaterCommitsLinearlyReappend() throws Exception {
        RsiGitService git = service();
        RsiPaths paths = new RsiPaths(temp.resolve("rsi").toString());
        Path workTree = paths.memoryDir("wayfarer-rsi");
        Files.createDirectories(workTree);
        Files.writeString(workTree.resolve("l1.jsonl"), "one\n", StandardCharsets.UTF_8);
        String c1 = git.commit("wayfarer-rsi", "memory", "memory(l1): c1", Map.of());
        Files.writeString(workTree.resolve("l1.jsonl"), "one\ntwo\n", StandardCharsets.UTF_8);
        String c2 = git.commit("wayfarer-rsi", "memory", "memory(l1): c2", Map.of());
        assertNotNull(c1);
        assertNotNull(c2);

        // 回溯到 c1：HEAD 变为 c1，工作区文件也恢复为 c1 的内容
        String head = git.resetTo("wayfarer-rsi", "memory", c1);
        assertEquals(c1, head);
        assertEquals("one\n", Files.readString(workTree.resolve("l1.jsonl"), StandardCharsets.UTF_8));
        assertEquals(2, git.log("wayfarer-rsi", "memory").size());

        // 回溯后从回溯点线性继续提交
        Files.writeString(workTree.resolve("l1.jsonl"), "one\nthree\n", StandardCharsets.UTF_8);
        String c3 = git.commit("wayfarer-rsi", "memory", "memory(l1): c3", Map.of());
        assertNotNull(c3);
        List<RsiCommit> commits = git.log("wayfarer-rsi", "memory");
        assertEquals(3, commits.size());
        assertEquals(c3, commits.get(0).hash());
        assertEquals(c1, commits.get(0).parentHash());
    }

    @Test
    void diffBetweenTwoCommits() throws Exception {
        RsiGitService git = service();
        RsiPaths paths = new RsiPaths(temp.resolve("rsi").toString());
        Path workTree = paths.memoryDir("wayfarer-rsi");
        Files.createDirectories(workTree);
        Files.writeString(workTree.resolve("l1.jsonl"), "a\n", StandardCharsets.UTF_8);
        String c1 = git.commit("wayfarer-rsi", "memory", "c1", Map.of());
        Files.writeString(workTree.resolve("l1.jsonl"), "a\nb\n", StandardCharsets.UTF_8);
        String c2 = git.commit("wayfarer-rsi", "memory", "c2", Map.of());

        String diff = git.diff("wayfarer-rsi", "memory", c1, c2);
        assertTrue(diff.contains("+b"));

        // 单提交 diff 与区间 diff 在此场景等价
        assertFalse(git.diff("wayfarer-rsi", "memory", c2).isEmpty());
    }

    @Test
    void readFileAndFilesAtCommit() throws Exception {
        RsiGitService git = service();
        RsiPaths paths = new RsiPaths(temp.resolve("rsi").toString());
        Path workTree = paths.memoryDir("wayfarer-rsi");
        Files.createDirectories(workTree.resolve("l2"));
        Files.writeString(workTree.resolve("l1.jsonl"), "one\n", StandardCharsets.UTF_8);
        String c1 = git.commit("wayfarer-rsi", "memory", "c1", Map.of());
        Files.writeString(workTree.resolve("l2/s-1.md"), "scene", StandardCharsets.UTF_8);
        git.commit("wayfarer-rsi", "memory", "c2", Map.of());

        // c1 版本：只有 l1.jsonl
        assertEquals("one\n", git.readFile("wayfarer-rsi", "memory", "l1.jsonl", c1));
        assertNull(git.readFile("wayfarer-rsi", "memory", "l2/s-1.md", c1));
        assertEquals(List.of("l1.jsonl"), git.files("wayfarer-rsi", "memory", c1));
        // HEAD 版本：两个文件都在；commit 缺省读 HEAD
        assertEquals("scene", git.readFile("wayfarer-rsi", "memory", "l2/s-1.md", null));
        assertEquals(2, git.files("wayfarer-rsi", "memory", null).size());
    }

    @Test
    void trailerAndMessageHelpers() {
        String message = RsiGitService.message("skill: patch pdf-edit",
                Map.of(RsiGitService.TRAILER_SOURCE_SESSION, "s-9"));
        Map<String, String> parsed = RsiGitService.trailers(message);
        assertEquals("s-9", parsed.get(RsiGitService.TRAILER_SOURCE_SESSION));
        // 非 Rsi- 前缀的行不进 trailer
        assertTrue(RsiGitService.trailers("Wayfarer-Session: x").isEmpty());
    }
}
