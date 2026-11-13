package com.yf.wayfarer.agent.toolchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.session.UnloadedMessageStore;
import com.yf.wayfarer.toolcall.Tool;
import com.yf.wayfarer.toolcall.ToolParam;

/**
 * 卸载消息读取工具。
 *
 * @author yyf
 * @description 供模型按占位符中的 id 取回被卸载的完整内容
 */
public class UnloadTools {

    private final UnloadedMessageStore store;

    public UnloadTools(UnloadedMessageStore store) {
        this.store = store;
    }

    @Tool(name = "read_unloaded", description = "按 id 读取被卸载的完整消息内容，用于查看占位符指向的原文")
    public String readUnloaded(AgentContext context,
                              @ToolParam(description = "卸载记录的 id，取自占位符 sys-unloaded-<id>") String id) {
        String sessionId = context.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return "当前上下文缺少会话 id，无法读取卸载内容";
        }
        String content = store.read(sessionId, id);
        return content == null ? "未找到卸载记录：" + id : content;
    }
}
