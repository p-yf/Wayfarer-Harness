package com.yf.wayfarer.agent.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ChatChunk;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ChatModel;
import com.yf.wayfarer.model.ChatRequest;
import com.yf.wayfarer.model.ModelException;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 模型调用处理器。
 *
 * @author yyf
 * @description 按上下文消息历史发起流式调用，推送流式片段并把完整响应写入上下文；
 * 流在独立线程回调，本处理器阻塞到流结束，因此链路保持同步语义
 */
public class ModelHandler implements ChainHandler {

    private static final Logger log = LoggerFactory.getLogger(ModelHandler.class);

    private final ChatModel model;
    private final List<ToolDefinition> tools;

    public ModelHandler(ChatModel model, List<ToolDefinition> tools) {
        this.model = model;
        this.tools = tools == null ? List.of() : List.copyOf(tools);
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        List<ChatMessage> messages = requestMessages(context);
        log.info("请求模型 traceId={} 消息数={} 工具数={}", context.traceId(), messages.size(), tools.size());
        // 上一轮的响应与占位下标都已失效
        context.remove(AgentContext.MODEL_RESPONSE);
        context.remove(AgentContext.PARTIAL_ASSISTANT);

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Disposable subscription = model.stream(new ChatRequest(messages, tools)).subscribe(
                chunk -> onChunk(context, chunk),
                // 出错同样要放行本线程，否则链路会一直阻塞在这里
                error -> {
                    failure.set(error);
                    finished.countDown();
                },
                finished::countDown);
        // 取消时立即断开模型连接，并唤醒本线程
        context.onCancel(() -> {
            subscription.dispose();
            finished.countDown();
        });
        try {
            finished.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            subscription.dispose();
        }

        Throwable error = failure.get();
        if (error != null) {
            throw error instanceof RuntimeException runtime ? runtime : new ModelException("模型流式调用失败", error);
        }
        next.run();
    }

    private static void onChunk(AgentContext context, ChatChunk chunk) {
        appendPartial(context, chunk);
        ModelResponse response = chunk.response();
        if (response != null) {
            context.put(AgentContext.MODEL_RESPONSE, response);
            if (response.finishReason() != null) {
                log.info("模型响应 traceId={} finishReason={} usage={}",
                        context.traceId(), response.finishReason(), response.usage());
            }
        }
        Consumer<AgentMessage> sink = context.agentMsgSink();
        if (sink == null) {
            return;
        }
        if (!chunk.reasoningContent().isEmpty()) {
            sink.accept(new AgentMessage.Reasoning(context.traceId(), chunk.reasoningContent()));
        }
        if (!chunk.content().isEmpty()) {
            sink.accept(new AgentMessage.Text(context.traceId(), chunk.content()));
        }
        if (response != null) {
            // 回复收尾：正文与思考已按增量送出，这里只给结束原因与用量
            sink.accept(new AgentMessage.End(context.traceId(), response.finishReason(), response.usage()));
        }
    }

    /**
     * 按增量维护本轮 assistant 消息：先在记忆里占一条，边输出边更新。
     * 运行被取消时这条消息已存在于历史中，会随取消路径一起落盘；
     * 正常结束时由记忆管理处理器用完整响应替换它。
     * 半截的工具调用参数无法配对，这里始终不记录工具调用。
     */
    private static void appendPartial(AgentContext context, ChatChunk chunk) {
        if (chunk.content().isEmpty() && chunk.reasoningContent().isEmpty()) {
            return;
        }
        List<ChatMessage> messages = context.messages();
        Integer index = context.get(AgentContext.PARTIAL_ASSISTANT, Integer.class);
        if (index == null) {
            messages.add(ChatMessage.assistant(chunk.content(), chunk.reasoningContent(), List.of()));
            context.put(AgentContext.PARTIAL_ASSISTANT, messages.size() - 1);
            return;
        }
        ChatMessage previous = messages.get(index);
        String reasoning = previous.reasoningContent() == null ? "" : previous.reasoningContent();
        messages.set(index, ChatMessage.assistant(previous.content() + chunk.content(),
                reasoning + chunk.reasoningContent(), List.of()));
    }

    /**
     * 上下文中的系统提示词按「名称-顺序」排序拼接后作为首条 system 消息，不写入消息历史，避免逐轮重复。
     */
    private static List<ChatMessage> requestMessages(AgentContext context) {
        List<ChatMessage> messages = context.messages();
        String systemPrompt = systemPrompt(context);
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return messages;
        }
        List<ChatMessage> withPrompt = new ArrayList<>(messages.size() + 1);
        withPrompt.add(new ChatMessage(ChatMessage.Role.SYSTEM, systemPrompt));
        withPrompt.addAll(messages);
        return withPrompt;
    }

    /**
     * 按键名末尾的顺序号升序拼接，顺序号缺失的排在最后。
     */
    private static String systemPrompt(AgentContext context) {
        Map<String, String> prompts = context.systemPrompts();
        if (prompts == null || prompts.isEmpty()) {
            return null;
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(prompts.entrySet());
        entries.sort(Comparator.comparingInt(entry -> orderOf(entry.getKey())));
        StringBuilder system = new StringBuilder();
        for (Map.Entry<String, String> entry : entries) {
            String content = entry.getValue();
            if (content == null || content.isBlank()) {
                continue;
            }
            if (!system.isEmpty()) {
                system.append("\n\n");
            }
            system.append(content.strip());
        }
        return system.toString();
    }

    private static int orderOf(String key) {
        int separator = key == null ? -1 : key.lastIndexOf('-');
        if (separator < 0) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(key.substring(separator + 1));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
