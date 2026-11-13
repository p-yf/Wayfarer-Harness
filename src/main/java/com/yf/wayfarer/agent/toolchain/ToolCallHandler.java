package com.yf.wayfarer.agent.toolchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCall;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;

/**
 * 工具调用处理器。
 *
 * @author yyf
 * @description 按顺序执行模型请求的每个工具调用，把工具 id 与名称写入上下文供工具自行鉴权，
 * 执行结果作为 tool 消息推送并按轮次拼接到消息历史
 */
public class ToolCallHandler implements ChainHandler {

    private static final Logger log = LoggerFactory.getLogger(ToolCallHandler.class);

    private final ToolCall toolCall;

    public ToolCallHandler(ToolCall toolCall) {
        this.toolCall = toolCall;
    }

    @Override
    public Mono<Void> run(AgentContext context, Mono<Void> next) {
        ModelResponse response = context.modelResponse();
        if (response == null || response.toolCalls().isEmpty()) {
            return next;
        }
        return Flux.fromIterable(response.toolCalls())
                .concatMap(info -> execute(context, info))
                .then(next);
    }

    private Mono<Void> execute(AgentContext context, ToolCallInfo info) {
        return Mono.fromRunnable(() -> {
            // 工具在执行过程中可据此定位自己的 HITL 等待项
            context.put(AgentContext.TOOL_CALL_ID, info.id());
            context.put(AgentContext.TOOL_NAME, info.name());

            ToolCircleCheckHandler.Guard guard =
                    context.get(ToolCircleCheckHandler.GUARD, ToolCircleCheckHandler.Guard.class);
            ToolCircleCheckHandler.Level level =
                    guard == null ? ToolCircleCheckHandler.Level.PASS : guard.check(info);
            String content;
            if (level == ToolCircleCheckHandler.Level.BLOCK) {
                // 连续五次相同：不再执行，直接把警告作为工具结果回给模型
                content = ToolCircleCheckHandler.BLOCK;
                log.warn("拦截重复工具调用 traceId={} 工具={} 参数={}", context.traceId(),
                        info.name(), abbreviate(info.arguments()));
            } else {
                log.info("执行工具 traceId={} 工具={} 参数={}", context.traceId(),
                        info.name(), abbreviate(info.arguments()));
                Object result = toolCall.call(info, context);
                content = result == null ? "" : String.valueOf(result);
                if (level == ToolCircleCheckHandler.Level.WARN) {
                    content = content + "\n" + ToolCircleCheckHandler.WARN;
                    log.warn("工具被连续重复调用 traceId={} 工具={}", context.traceId(), info.name());
                }
                log.info("工具完成 traceId={} 工具={} 结果长度={}", context.traceId(), info.name(), content.length());
            }
            Consumer<AgentMessage> sink = context.agentMsgSink();
            if (sink != null) {
                sink.accept(new AgentMessage.ToolResult(context.traceId(), info.id(), content));
            }
            context.messages().add(new ChatMessage(ChatMessage.Role.TOOL, content, info.id()));
        });
    }

    /**
     * 日志中的参数只保留前 200 个字符，避免大段文件内容刷屏。
     */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
