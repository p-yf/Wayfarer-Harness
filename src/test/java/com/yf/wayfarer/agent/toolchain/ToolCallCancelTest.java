package com.yf.wayfarer.agent.toolchain;

import com.yf.wayfarer.agent.modelchain.ToolResultRepairHandler;
import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.FinishReason;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.Tool;
import com.yf.wayfarer.toolcall.ToolCall;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 工具链取消行为测试，使用真实工具反射调用。
 *
 * @author yyf
 * @description 验证运行被终止后剩余工具不再执行，但仍补一条结果保证消息配对
 */
class ToolCallCancelTest {

    @Test
    void 已终止时剩余工具不执行并补占位结果() {
        CountingTools tools = new CountingTools();
        ToolCall toolCall = new ToolCall();
        toolCall.parse(tools);

        AgentContext context = new AgentContext();
        context.put(AgentContext.TRACE_ID, "trace-1");
        context.put(AgentContext.CANCELLED, true);
        context.put(AgentContext.MESSAGES, new ArrayList<ChatMessage>());
        context.put(AgentContext.MODEL_RESPONSE, new ModelResponse("id", "deepseek-flash", 1L, "", "",
                FinishReason.TOOL_CALLS,
                List.of(new ToolCallInfo("call_1", "read", "{\"path\":\"a.txt\"}")), null));

        new ToolCallHandler(toolCall).run(context, () -> {
        });

        assertEquals(0, tools.calls, "已终止时不应再执行工具");
        List<ChatMessage> messages = context.messages();
        assertEquals(1, messages.size(), "仍需补一条工具结果");
        assertEquals(ToolResultRepairHandler.PLACEHOLDER, messages.get(0).content());
        assertEquals("call_1", messages.get(0).toolCallId());
    }

    /**
     * @author yyf
     * @description 记录真实执行次数的工具
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
