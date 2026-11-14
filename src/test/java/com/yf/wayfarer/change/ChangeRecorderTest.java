package com.yf.wayfarer.change;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.toolcall.ToolCallInfo;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调用变更记录测试，使用真实文件系统与真实 JGit 仓库。
 *
 * @author yyf
 * @description 覆盖写类工具成功记录、只读工具不记录、失败不记录三种情况
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
        recorder = new ChangeRecorder(store);
    }

    @Test
    void 写入成功后记录一条提交() {
        AgentContext context = context();
        ToolCallInfo info = new ToolCallInfo("call-1", "write", "{\"path\":\"a.txt\",\"content\":\"hello\"}");

        ChangeRecorder.Snapshot snapshot = recorder.before(context, info);
        assertNotNull(snapshot);
        write("a.txt", "hello");
        recorder.after(context, snapshot, "已写入 a.txt");

        ShadowRepository repository = store.forWorkspace(workspace.toString());
        List<ChangeCommit> commits = repository.commits();
        // 一条基线 + 一条工具提交
        assertEquals(2, commits.size());
        ChangeCommit latest = commits.get(0);
        assertEquals("write", latest.tool());
        assertEquals("session-1", latest.sessionId());
        assertEquals("trace-1", latest.traceId());
        assertEquals("call-1", latest.toolCallId());
    }

    @Test
    void 只读工具不记录也不创建仓库() {
        AgentContext context = context();
        ToolCallInfo info = new ToolCallInfo("call-2", "read", "{\"path\":\"seed.txt\"}");

        assertNull(recorder.before(context, info));
    }

    @Test
    void 工具失败时不留提交() {
        AgentContext context = context();
        ToolCallInfo info = new ToolCallInfo("call-3", "write", "{\"path\":\"b.txt\",\"content\":\"half\"}");

        ChangeRecorder.Snapshot snapshot = recorder.before(context, info);
        // 模拟「写了一半就失败」：文件确实变了，但工具报告错误
        write("b.txt", "half");
        recorder.after(context, snapshot, "错误：写入文件失败：磁盘满了");

        ShadowRepository repository = store.forWorkspace(workspace.toString());
        assertEquals(1, repository.commits().size(), "失败调用不应产生提交");
        assertTrue(repository.dirtyPaths().contains("b.txt"), "残留改动留给下一次成功提交带走");
    }

    @Test
    void 轮次结束时汇总本轮变更供页面展示() {
        ShadowRepository repository = store.forWorkspace(workspace.toString());
        repository.ensure();
        write("a.txt", "hello");
        Map<String, String> trailers = new LinkedHashMap<>();
        trailers.put(ShadowRepository.TRAILER_SESSION, "session-1");
        trailers.put(ShadowRepository.TRAILER_TRACE, "trace-1");
        trailers.put(ShadowRepository.TRAILER_TOOL, "write");
        trailers.put(ShadowRepository.TRAILER_CALL, "call-1");
        repository.commit(Set.of("a.txt"), "write a.txt", trailers);

        ChangeRecorder recorder = new ChangeRecorder(store, new ChangeService(store));
        AgentMessage message = recorder.summary(context());

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

    private AgentContext context() {
        AgentContext context = new AgentContext();
        context.put(AgentContext.WORKSPACE, workspace.toString());
        context.put(AgentContext.SESSION_ID, "session-1");
        context.put(AgentContext.TRACE_ID, "trace-1");
        return context;
    }

    private void write(String path, String content) {
        try {
            Files.writeString(workspace.resolve(path), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
