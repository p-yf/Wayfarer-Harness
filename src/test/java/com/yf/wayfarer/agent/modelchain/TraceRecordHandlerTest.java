package com.yf.wayfarer.agent.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.FinishReason;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.model.Usage;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.JsonlSession;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.SessionMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 耗时链路测试，使用真实会话文件。
 *
 * @author yyf
 * @description 覆盖一轮结束后耗时与用量写入 EXTRADATA，并向下游推送 trace 数据
 */
class TraceRecordHandlerTest {

    @TempDir
    Path root;

    @Test
    void 记录耗时与用量并持久化到会话() {
        Session session = new JsonlSession(root);
        String id = session.create("耗时会话");

        AgentContext context = new AgentContext();
        context.put(AgentContext.SESSION, session);
        context.put(AgentContext.SESSION_ID, id);
        context.put(AgentContext.TRACE_ID, "trace-1");
        List<AgentMessage> pushed = new ArrayList<>();
        context.put(AgentContext.AGENT_MSG_SINK, (Consumer<AgentMessage>) pushed::add);

        Usage usage = new Usage(10L, 5L, 15L, 6L, 4L, 2L);
        ModelResponse response = new ModelResponse("id", "deepseek-flash", 1L, "回答", "",
                FinishReason.STOP, List.of(), usage);
        new TraceRecordHandler().run(context, () -> context.put(AgentContext.MODEL_RESPONSE, response));

        List<SessionMessage> messages = session.messages(id);
        assertEquals(1, messages.size());
        assertEquals(JSONMsgType.EXTRADATA, messages.get(0).type());
        assertEquals("trace-1", messages.get(0).traceId());
        assertNotNull(messages.get(0).content().get("durationMs"));
        assertTrue(messages.get(0).content().get("usage") instanceof java.util.Map);

        assertEquals(1, pushed.size());
        assertTrue(pushed.get(0) instanceof AgentMessage.TraceData);
        AgentMessage.TraceData data = (AgentMessage.TraceData) pushed.get(0);
        assertEquals("trace-1", data.traceId());
        assertEquals(FinishReason.STOP, data.finishReason());
        assertEquals(15L, data.usage().totalTokens());
        assertTrue(data.durationMs() >= 0);
    }
}
