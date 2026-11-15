package com.yf.wayfarer.agent.agentchain;

import com.yf.wayfarer.agent.Agent;
import com.yf.wayfarer.change.ChangeFile;
import com.yf.wayfarer.change.ChangeRecorder;
import com.yf.wayfarer.change.ChangeService;
import com.yf.wayfarer.change.ShadowRepositoryStore;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.LoopChain;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.FinishReason;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.session.JsonlSession;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行级变更处理的测试，使用真实 agent、真实会话、真实文件系统与真实 JGit 仓库。
 *
 * @author yyf
 * @description 验证正常结束会提交本轮改动并推送汇总消息，取消时仍提交改动（取消后不再推送消息）
 */
class ChangeSummaryHandlerTest {

    @TempDir
    Path temp;

    private Path workspace;
    private ShadowRepositoryStore store;
    private ChangeRecorder recorder;
    private JsonlSession session;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(temp.resolve("ws"));
        Files.writeString(workspace.resolve("seed.txt"), "seed", StandardCharsets.UTF_8);
        store = new ShadowRepositoryStore(temp.resolve("shadow"), List.of(".git", ".wayfarer"), true);
        recorder = new ChangeRecorder(store, new ChangeService(store));
        session = new JsonlSession(temp.resolve("sessions"));
    }

    @Test
    @Timeout(15)
    void 运行结束提交本轮变更并推送汇总消息() {
        // 模型步骤里模拟 Agent 改了文件；随后结束本轮，不产生工具调用
        LoopChain modelChain = LoopChain.of((ctx, next) -> {
            write("a.txt", "hello");
            ctx.agentMsgSink().accept(new AgentMessage.Text(ctx.traceId(), "完成"));
            ctx.put(AgentContext.MODEL_RESPONSE, responseWithoutToolCall());
        });

        Agent agent = new Agent(session, modelChain, LoopChain.of(),
                LoopChain.of(new ChangeSummaryHandler(recorder)), null);
        Flux<AgentMessage> stream = agent.stream(context());

        StepVerifier.create(stream)
                .expectNextMatches(message -> message instanceof AgentMessage.Text)
                .expectNextMatches(message -> message instanceof AgentMessage.Other)
                .verifyComplete();

        List<ChangeFile> files = latestCommitFiles();
        assertEquals(1, files.size(), "本轮改动应提交成一条记录");
        assertEquals("a.txt", files.get(0).path());
    }

    @Test
    @Timeout(15)
    void 取消时仍提交本轮变更() throws Exception {
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        LoopChain modelChain = LoopChain.of((ctx, next) ->
                ctx.put(AgentContext.MODEL_RESPONSE, responseWithToolCall()));
        LoopChain toolChain = LoopChain.of((ctx, next) -> {
            write("b.txt", "from-tool");
            toolStarted.countDown();
            await(resume);
        });

        Agent agent = new Agent(session, modelChain, toolChain,
                LoopChain.of(new ChangeSummaryHandler(recorder)), null);
        Disposable disposable = agent.stream(context()).subscribe();

        assertTrue(toolStarted.await(5, TimeUnit.SECONDS));
        disposable.dispose();
        resume.countDown();

        // 取消后主循环先收尾再退出，提交发生在主循环线程上，这里等它落地
        List<ChangeFile> files = awaitCommittedFiles();
        assertEquals("b.txt", files.get(0).path(), "取消的运行也应记录已产生的改动");
    }

    private List<ChangeFile> latestCommitFiles() {
        var repository = store.forWorkspace(workspace.toString());
        var commits = repository.commits();
        return repository.files(commits.get(0).commitId());
    }

    private List<ChangeFile> awaitCommittedFiles() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            var repository = store.forWorkspace(workspace.toString());
            // 基线提交之外出现一条运行级提交，说明收尾已经落盘
            if (repository.commits().size() > 1) {
                return latestCommitFiles();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("取消后未提交本轮变更");
    }

    private AgentContext context() {
        AgentContext context = new AgentContext();
        context.put(AgentContext.SESSION_ID, session.create("变更测试"));
        context.put(AgentContext.WORKSPACE, workspace.toString());
        return context;
    }

    private static ModelResponse responseWithToolCall() {
        return new ModelResponse("id", "deepseek-flash", 1L, "", "", FinishReason.TOOL_CALLS,
                List.of(new ToolCallInfo("call_1", "write", "{}")), null);
    }

    private static ModelResponse responseWithoutToolCall() {
        return new ModelResponse("id", "deepseek-flash", 1L, "", "", FinishReason.STOP,
                List.of(), null);
    }

    private void write(String path, String content) {
        try {
            Files.writeString(workspace.resolve(path), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
