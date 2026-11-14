package com.yf.wayfarer.agent.toolchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.session.UnloadedMessageStore;
import com.yf.wayfarer.toolcall.ToolCall;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import com.yf.wayfarer.toolcall.ToolDefinition;
import com.yf.wayfarer.tools.UnloadTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卸载消息读取工具测试，基于真实文件系统。
 *
 * @author yyf
 * @description 覆盖按 id 取回原文、缺失记录提示与上下文参数自动注入
 */
class UnloadToolsTest {

    @TempDir
    Path root;

    @Test
    void 上下文参数不出现在工具定义中() {
        UnloadedMessageStore store = new UnloadedMessageStore(root);
        List<ToolDefinition> definitions = new ToolCall().parse(new UnloadTools(store));

        assertEquals(1, definitions.size());
        ToolDefinition definition = definitions.get(0);
        assertEquals("read_unloaded", definition.name());
        assertTrue(String.valueOf(definition.parameters().get("required")).contains("id"));
        assertTrue(!String.valueOf(definition.parameters().get("required")).contains("context"));
    }

    @Test
    void 按id读回被卸载的原文() {
        UnloadedMessageStore store = new UnloadedMessageStore(root);
        String uuid = store.save("session-1", "被卸载的完整内容");

        AgentContext context = new AgentContext().put(AgentContext.SESSION_ID, "session-1");

        assertEquals("被卸载的完整内容", call(store, context, uuid));
    }

    @Test
    void 记录不存在时返回提示() {
        UnloadedMessageStore store = new UnloadedMessageStore(root);
        AgentContext context = new AgentContext().put(AgentContext.SESSION_ID, "session-1");

        assertEquals("未找到卸载记录：missing", call(store, context, "missing"));
    }

    private Object call(UnloadedMessageStore store, AgentContext context, String id) {
        ToolCall toolCall = new ToolCall();
        toolCall.parse(new UnloadTools(store));
        return toolCall.call(new ToolCallInfo("call_1", "read_unloaded", "{\"id\":\"" + id + "\"}"), context);
    }
}
