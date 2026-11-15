package com.yf.wayfarer.agent.toolchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.LoopChain;
import com.yf.wayfarer.loop.toolchain.ToolCallHandler;
import com.yf.wayfarer.loop.toolchain.ToolCallLoopHandler;
import com.yf.wayfarer.loop.toolchain.ToolCircleCheckHandler;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.FinishReason;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.Tool;
import com.yf.wayfarer.toolcall.ToolCall;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复工具调用拦截测试，使用真实工具反射调用。
 *
 * @author yyf
 * @description 覆盖连续三次重复提醒、连续五次短路、不同工具打断计数
 */
class ToolCircleCheckHandlerTest {

    private static final ToolCallInfo READ = new ToolCallInfo("call_1", "read", "{\"path\":\"a.txt\"}");
    private static final ToolCallInfo WRITE = new ToolCallInfo("call_2", "write", "{\"path\":\"b.txt\"}");

    @Test
    void 连续三次相同工具触发提醒() {
        ToolCircleCheckHandler.Guard guard = new ToolCircleCheckHandler.Guard();

        assertEquals(ToolCircleCheckHandler.Level.PASS, guard.check(READ));
        assertEquals(ToolCircleCheckHandler.Level.PASS, guard.check(READ));
        assertEquals(ToolCircleCheckHandler.Level.WARN, guard.check(READ));
    }

    @Test
    void 连续五次相同工具直接短路() {
        ToolCircleCheckHandler.Guard guard = new ToolCircleCheckHandler.Guard();

        for (int i = 0; i < 4; i++) {
            guard.check(READ);
        }

        assertEquals(ToolCircleCheckHandler.Level.BLOCK, guard.check(READ));
    }

    @Test
    void 调用不同工具会打断连续计数() {
        ToolCircleCheckHandler.Guard guard = new ToolCircleCheckHandler.Guard();

        guard.check(READ);
        guard.check(READ);
        guard.check(WRITE);

        assertEquals(ToolCircleCheckHandler.Level.PASS, guard.check(READ));
    }

    @Test
    void 工具链在第五次调用时短路返回警告() {
        CountingTools tools = new CountingTools();
        ToolCall toolCall = new ToolCall();
        toolCall.parse(tools);

        AgentContext context = new AgentContext();
        context.put(AgentContext.TRACE_ID, "trace-1");
        context.put(AgentContext.MESSAGES, new ArrayList<>(List.of(
                new ChatMessage(ChatMessage.Role.USER, "读取文件"))));
        context.put(AgentContext.MODEL_RESPONSE, responseOf(5));
        List<AgentMessage> pushed = new ArrayList<>();
        context.put(AgentContext.AGENT_MSG_SINK, (Consumer<AgentMessage>) pushed::add);

        LoopChain.of(new ToolCallLoopHandler(), new ToolCircleCheckHandler(),
                new ToolCallHandler(toolCall)).run(context);

        assertEquals(4, tools.calls, "第五次调用不应真正执行");
        List<ChatMessage> messages = context.messages();
        assertEquals(ToolCircleCheckHandler.BLOCK, messages.get(messages.size() - 1).content());
        assertTrue(pushed.stream().anyMatch(message -> message instanceof AgentMessage.ToolResult result
                && result.content().contains(ToolCircleCheckHandler.WARN)), "第三次调用应追加提醒");
    }

    private static ModelResponse responseOf(int count) {
        List<ToolCallInfo> calls = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            calls.add(new ToolCallInfo("call_" + i, "read", "{\"path\":\"a.txt\"}"));
        }
        return new ModelResponse("id", "deepseek-flash", 1L, "", "", FinishReason.TOOL_CALLS, calls, null);
    }

    /**
     * @author yyf
     * @description 记录真实执行次数的工具集
     */
    static class CountingTools {

        private int calls;

        @Tool(name = "read", description = "读取文件")
        public String read() {
            calls++;
            return "文件内容";
        }
    }
}
