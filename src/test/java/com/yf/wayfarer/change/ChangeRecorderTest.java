package com.yf.wayfarer.change;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.message.AgentMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行级变更记录测试，使用真实文件系统与真实 JGit 仓库。
 *
 * @author yyf
 * @description 覆盖「一轮运行一条提交」、运行前已脏文件不记入本轮、无改动不提交、收尾幂等与汇总消息
 */
class ChangeRecorderTest {

    @TempDir
    Path temp;

    private Path workspace;
    private ShadowRepositoryStore store;
    private ChangeRecorder recorder;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(temp.resolve("ws"));
        Files.writeString(workspace.resolve("seed.txt"), "seed", StandardCharsets.UTF_8);
        store = new ShadowRepositoryStore(temp.resolve("shadow"), List.of(".git", ".wayfarer"), true);
        recorder = new ChangeRecorder(store, new ChangeService(store));
    }

    @Test
    void 一轮运行只产生一条提交() {
        AgentContext context = context();
        ChangeRecorder.Run run = recorder.begin(context);
        assertNotNull(run);
        // 模拟一轮运行里多个工具先后改了不同文件
        write("a.txt", "hello");
        write("b.txt", "world");
        recorder.finish(context, run);

        ShadowRepository repository = repository();
        List<ChangeCommit> commits = repository.commits();
        assertEquals(2, commits.size(), "基线之外应只有一条运行级提交");
        ChangeCommit latest = commits.get(0);
        assertEquals(ChangeRecorder.RUN_TOOL, latest.tool());
        assertEquals("session-1", latest.sessionId());
        assertEquals("trace-1", latest.traceId());
        assertEquals(2, repository.files(latest.commitId()).size(), "两个文件应合并进同一条提交");
    }

    @Test
    void 运行前已脏的文件不会被记入本轮() {
        // 用户在运行开始前就手工改过 seed.txt
        write("seed.txt", "user-edit");
        AgentContext context = context();
        ChangeRecorder.Run run = recorder.begin(context);

        write("a.txt", "from-agent");
        recorder.finish(context, run);

        ChangeCommit latest = repository().commits().get(0);
        List<ChangeFile> files = repository().files(latest.commitId());
        assertEquals(1, files.size(), "只应记录运行期间产生的改动");
        assertEquals("a.txt", files.get(0).path());
    }

    @Test
    void 运行前已脏的文件被撤回后保留用户改动() {
        // 用户在运行前就改过 seed.txt
        write("seed.txt", "user-edit");
        AgentContext context = context();
        ChangeRecorder.Run run = recorder.begin(context);
        // Agent 又把同一个文件覆盖了一遍
        write("seed.txt", "agent-edit");
        recorder.finish(context, run);

        ChangeService service = new ChangeService(store);
        assertEquals(1, service.list("session-1", "trace-1", 10).size(), "运行前快照不应出现在变更列表");

        service.revertSessionChanges("session-1", Set.of(), "trace-1");

        assertEquals("user-edit", read("seed.txt"), "撤回应回到运行前的用户内容，而不是上一次提交");
    }

    @Test
    void 没有文件差异时不产生提交() {
        AgentContext context = context();
        ChangeRecorder.Run run = recorder.begin(context);
        recorder.finish(context, run);

        assertEquals(1, repository().commits().size(), "没有改动时只保留基线提交");
    }

    @Test
    void 收尾重复调用只提交一次() {
        AgentContext context = context();
        ChangeRecorder.Run run = recorder.begin(context);
        write("a.txt", "hello");
        recorder.finish(context, run);
        recorder.finish(context, run);

        assertEquals(2, repository().commits().size(), "取消与完成回调叠加时不应重复提交");
    }

    @Test
    void 运行结束后汇总本轮变更供页面展示() {
        AgentContext context = context();
        ChangeRecorder.Run run = recorder.begin(context);
        write("a.txt", "hello");
        recorder.finish(context, run);

        AgentMessage message = recorder.summary(context);

        assertTrue(message instanceof AgentMessage.Other, "有变更时应返回一条 other 消息");
        Map<?, ?> data = (Map<?, ?>) ((AgentMessage.Other) message).message();
        assertEquals("changes", data.get("type"));
        assertEquals("trace-1", data.get("traceId"));
        assertEquals(1, ((List<?>) data.get("entries")).size());

        // 没有变更的轮次不推送
        AgentContext other = context();
        other.put(AgentContext.TRACE_ID, "trace-empty");
        assertNull(recorder.summary(other));
    }

    @Test
    void 按文件聚合整轮净变化() {
        // 同一会话的两轮各自改了 a.txt，按文件应得到从最初到最终的净变化
        AgentContext first = context();
        ChangeRecorder.Run firstRun = recorder.begin(first);
        write("a.txt", "v1");
        recorder.finish(first, firstRun);

        AgentContext second = context();
        ChangeRecorder.Run secondRun = recorder.begin(second);
        write("a.txt", "v2");
        recorder.finish(second, secondRun);

        Map<String, Object> result = new ChangeService(store).fileDiff("session-1", null, "a.txt");

        String patch = String.valueOf(result.get("patch"));
        assertEquals("a.txt", result.get("path"));
        assertTrue(patch.contains("+v2"), "应展示最终内容：" + patch);
        assertTrue(!patch.contains("+v1"), "中间状态不应单独出现：" + patch);
    }

    private ShadowRepository repository() {
        return store.forWorkspace(workspace.toString());
    }

    private AgentContext context() {
        AgentContext context = new AgentContext();
        context.put(AgentContext.WORKSPACE, workspace.toString());
        context.put(AgentContext.SESSION_ID, "session-1");
        context.put(AgentContext.TRACE_ID, "trace-1");
        return context;
    }

    private String read(String path) {
        try {
            return Files.readString(workspace.resolve(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(String path, String content) {
        try {
            Files.writeString(workspace.resolve(path), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
