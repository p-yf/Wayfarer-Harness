package com.yf.wayfarer.model.openai;

import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.core.JsonValue;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.completions.CompletionUsage;
import com.yf.wayfarer.model.ChatChunk;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ChatModel;
import com.yf.wayfarer.model.ChatRequest;
import com.yf.wayfarer.model.FinishReason;
import com.yf.wayfarer.model.ModelConfig;
import com.yf.wayfarer.model.ModelException;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import com.yf.wayfarer.toolcall.ToolDefinition;
import com.yf.wayfarer.model.Usage;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于 openai-java 的响应式实现（普通 Java 类，不依赖 Spring）。
 *
 * @author yyf
 * @description 把 SDK 的回调式流桥接为 Reactor Flux；取消订阅时取消 HTTP 调用并关闭连接
 */
public class OpenAIChatModel implements ChatModel {

    private static final String STREAM_ID_HEADER = "X-Wayfarer-Stream-Id";

    private final OpenAIClientAsync client;
    private final ModelConfig config;
    private final ConcurrentMap<String, Call> activeCalls = new ConcurrentHashMap<>();
    private final Set<String> cancelledStreams = ConcurrentHashMap.newKeySet();

    public OpenAIChatModel(ModelConfig config) {
        this.client = OpenAIOkHttpClientAsync.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                // 记录每个流对应的 OkHttp 调用，取消订阅时可直接中止底层连接
                .addInterceptor(chain -> {
                    String streamId = chain.request().header(STREAM_ID_HEADER);
                    if (streamId == null) {
                        return chain.proceed(chain.request());
                    }
                    Call call = chain.call();
                    activeCalls.put(streamId, call);
                    if (cancelledStreams.remove(streamId)) {
                        call.cancel();
                    }
                    return chain.proceed(chain.request());
                })
                .build();
        this.config = config;
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        return Flux.create(sink -> {
            String streamId = UUID.randomUUID().toString();
            ResponseAccumulator accumulator = new ResponseAccumulator();
            AsyncStreamResponse<ChatCompletionChunk> response;
            try {
                response = client.chat().completions().createStreaming(toParams(request, streamId));
            } catch (RuntimeException e) {
                sink.error(new ModelException("发起模型流式调用失败", e));
                return;
            }

            sink.onDispose(() -> {
                Call call = activeCalls.remove(streamId);
                if (call != null) {
                    call.cancel();
                } else {
                    // 调用尚未注册到拦截器时，标记后交由拦截器取消
                    cancelledStreams.add(streamId);
                }
                try {
                    response.close();
                } catch (RuntimeException ignored) {
                    // 取消路径上的关闭失败无需向上游传播
                }
            });

            response.subscribe(new AsyncStreamResponse.Handler<>() {
                @Override
                public void onNext(ChatCompletionChunk chunk) {
                    if (sink.isCancelled()) {
                        return;
                    }
                    try {
                        accumulator.update(chunk);
                        toChatChunk(chunk).ifPresent(sink::next);
                    } catch (RuntimeException e) {
                        sink.error(new ModelException("解析模型流式响应失败", e));
                    }
                }

                @Override
                public void onComplete(Optional<Throwable> error) {
                    if (error.isPresent()) {
                        sink.error(new ModelException("模型流式调用失败", error.get()));
                        return;
                    }
                    if (!sink.isCancelled()) {
                        // 正常输出完整后，最后一片携带累计的完整响应
                        sink.next(new ChatChunk("", null, accumulator.toResponse()));
                        sink.complete();
                    }
                }
            });
        });
    }

    private ChatCompletionCreateParams toParams(ChatRequest request, String streamId) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(config.model())
                .putAdditionalHeader(STREAM_ID_HEADER, streamId)
                // DeepSeek 思考模式：reasoning_effort 同时控制开关与强度
                .putAdditionalBodyProperty("reasoning_effort", JsonValue.from(config.reasoningEffort()));
        if (config.temperature() != null) {
            builder.temperature(config.temperature());
        }
        if (config.maxTokens() != null) {
            builder.maxTokens(config.maxTokens());
        }
        for (ChatMessage message : request.messages()) {
            switch (message.role()) {
                case SYSTEM -> builder.addSystemMessage(message.content());
                case USER -> builder.addUserMessage(message.content());
                case ASSISTANT -> builder.addMessage(toAssistantMessage(message));
                case TOOL -> builder.addMessage(ChatCompletionToolMessageParam.builder()
                        .toolCallId(message.toolCallId())
                        .content(message.content())
                        .build());
            }
        }
        for (ToolDefinition tool : request.tools()) {
            builder.addTool(toSdkTool(tool));
        }
        return builder.build();
    }

    /**
     * 构造 assistant 回放消息：内容、思考内容与工具调用。
     * DeepSeek V4 思考模式要求含工具调用的 assistant 消息必须回传 reasoning_content，否则 400；
     * 纯文本轮次不回传，既避免多余 token，也符合供应商预期。
     * 记忆层始终保留完整思考内容用于落盘，这里只决定回放报文的形态。
     */
    private static ChatCompletionAssistantMessageParam toAssistantMessage(ChatMessage message) {
        ChatCompletionAssistantMessageParam.Builder assistant = ChatCompletionAssistantMessageParam.builder()
                .content(message.content());
        if (!message.toolCalls().isEmpty()
                && message.reasoningContent() != null && !message.reasoningContent().isBlank()) {
            assistant.putAdditionalProperty("reasoning_content", JsonValue.from(message.reasoningContent()));
        }
        for (ToolCallInfo info : message.toolCalls()) {
            assistant.addToolCall(ChatCompletionMessageFunctionToolCall.builder()
                    .id(info.id())
                    .type(JsonValue.from("function"))
                    .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                            .name(info.name())
                            .arguments(info.arguments())
                            .build())
                    .build());
        }
        return assistant.build();
    }

    private static ChatCompletionFunctionTool toSdkTool(ToolDefinition tool) {
        FunctionParameters.Builder parameters = FunctionParameters.builder();
        tool.parameters().forEach((key, value) -> parameters.putAdditionalProperty(key, JsonValue.from(value)));

        FunctionDefinition.Builder function = FunctionDefinition.builder()
                .name(tool.name())
                .parameters(parameters.build());
        if (tool.description() != null) {
            function.description(tool.description());
        }
        return ChatCompletionFunctionTool.builder().function(function.build()).build();
    }

    private Optional<ChatChunk> toChatChunk(ChatCompletionChunk chunk) {
        if (chunk.choices().isEmpty()) {
            return Optional.empty();
        }
        ChatCompletionChunk.Choice choice = chunk.choices().get(0);
        String content = choice.delta().content().orElse("");
        String reasoning = reasoningOf(choice.delta());
        FinishReason finishReason = choice.finishReason()
                .map(OpenAIChatModel::mapFinishReason)
                .orElse(null);
        if (content.isEmpty() && reasoning.isEmpty() && finishReason == null) {
            return Optional.empty();
        }
        return Optional.of(new ChatChunk(content, reasoning, finishReason, null));
    }

    /**
     * 取 DeepSeek 思考模式下的 reasoning_content 增量，缺失时为空串。
     */
    private static String reasoningOf(ChatCompletionChunk.Choice.Delta delta) {
        JsonValue value = delta._additionalProperties().get("reasoning_content");
        return value == null ? "" : Optional.ofNullable(value.convert(String.class)).orElse("");
    }

    private static FinishReason mapFinishReason(ChatCompletionChunk.Choice.FinishReason reason) {
        return switch (reason.asString()) {
            case "stop" -> FinishReason.STOP;
            case "length" -> FinishReason.LENGTH;
            case "tool_calls" -> FinishReason.TOOL_CALLS;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.OTHER;
        };
    }

    /**
     * @author yyf
     * @description 累计流式输出，生成完整响应
     */
    private static final class ResponseAccumulator {

        private String id = "";
        private String model = "";
        private long created;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoningContent = new StringBuilder();
        private final Map<Integer, ToolCallAccumulator> toolCalls = new TreeMap<>();
        private FinishReason finishReason;
        private Usage usage;

        void update(ChatCompletionChunk chunk) {
            if (chunk.id() != null) {
                id = chunk.id();
            }
            if (chunk.model() != null) {
                model = chunk.model();
            }
            created = chunk.created();
            chunk.usage().ifPresent(value -> usage = toUsage(value));
            if (chunk.choices().isEmpty()) {
                return;
            }
            ChatCompletionChunk.Choice choice = chunk.choices().get(0);
            choice.delta().content().ifPresent(content::append);
            reasoningContent.append(reasoningOf(choice.delta()));
            choice.delta().toolCalls().ifPresent(calls -> calls.forEach(call ->
                    toolCalls.computeIfAbsent((int) call.index(), index -> new ToolCallAccumulator()).merge(call)));
            choice.finishReason().ifPresent(reason -> finishReason = mapFinishReason(reason));
        }

        ModelResponse toResponse() {
            List<ToolCallInfo> calls = toolCalls.values().stream()
                    .map(ToolCallAccumulator::toToolCall)
                    .toList();
            return new ModelResponse(id, model, created, content.toString(), reasoningContent.toString(),
                    finishReason, calls, usage);
        }

        private static Usage toUsage(CompletionUsage value) {
            Map<String, JsonValue> extra = value._additionalProperties();
            // 缓存命中：DeepSeek 用 prompt_cache_hit_tokens，OpenAI 兼容网关放在 prompt_tokens_details.cached_tokens
            Long hit = extraLong(extra, "prompt_cache_hit_tokens");
            if (hit == null) {
                hit = value.promptTokensDetails()
                        .flatMap(CompletionUsage.PromptTokensDetails::cachedTokens)
                        .orElse(null);
            }
            Long miss = extraLong(extra, "prompt_cache_miss_tokens");
            if (miss == null && hit != null && value.promptTokens() > 0) {
                miss = Math.max(0, value.promptTokens() - hit);
            }
            Long reasoning = value.completionTokensDetails()
                    .flatMap(CompletionUsage.CompletionTokensDetails::reasoningTokens)
                    .orElse(null);
            return new Usage(
                    value.promptTokens(),
                    value.completionTokens(),
                    value.totalTokens(),
                    hit,
                    miss,
                    reasoning);
        }

        private static Long extraLong(Map<String, JsonValue> extra, String key) {
            JsonValue value = extra.get(key);
            return value == null ? null : value.convert(Long.class);
        }
    }

    /**
     * @author yyf
     * @description 按 index 拼接工具调用片段
     */
    private static final class ToolCallAccumulator {

        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        void merge(ChatCompletionChunk.Choice.Delta.ToolCall call) {
            // 首片携带 id 与工具名，后续分片只追加参数且把 id、name 传成空串，空值不能覆盖已有值
            call.id().filter(value -> !value.isBlank()).ifPresent(value -> id = value);
            call.function().ifPresent(function -> {
                function.name().filter(value -> !value.isBlank()).ifPresent(value -> name = value);
                function.arguments().ifPresent(arguments::append);
            });
        }

        ToolCallInfo toToolCall() {
            return new ToolCallInfo(id, name, arguments.toString());
        }
    }
}
