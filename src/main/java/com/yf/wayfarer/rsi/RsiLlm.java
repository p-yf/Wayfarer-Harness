package com.yf.wayfarer.rsi;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yf.wayfarer.model.ChatChunk;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ChatModel;
import com.yf.wayfarer.model.ChatRequest;

import java.time.Duration;
import java.util.List;

/**
 * RSI 沉淀专用的 LLM 调用与 JSON 解析助手。
 *
 * <p>沉淀侧的 LLM 用法与主会话完全隔离：单次调用、无工具、system+user 两条消息、
 * 阻塞等待完整文本（沉淀跑在独立线程池，阻塞无碍）。提取/摘要/蒸馏/Review 四个
 * 沉淀器都通过这里发起调用，保证行为一致。</p>
 *
 * @author yyf
 * @description complete()：流式订阅收集到完整回复（优先取最后一片 chunk 携带的完整响应）；
 * extractJson()：从模型回复中剥掉 Markdown 围栏与前后杂文，取出第一段合法 JSON 文本
 */
public final class RsiLlm {

    /** 沉淀 LLM 调用的超时：提取任务输入较大，放宽到 5 分钟。 */
    public static final Duration TIMEOUT = Duration.ofMinutes(5);

    /** 宽松 JSON 解析器：忽略未知字段，模型输出与 schema 的微小偏差不至于让整次沉淀报废。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private RsiLlm() {
    }

    /**
     * 阻塞式完成一次沉淀调用。
     *
     * @param model  本次运行使用的模型（与主会话同源）
     * @param system 系统提示词（角色声明 + 输出 schema + 防注入声明）
     * @param user   用户侧输入（trace / 会话窗口 / 既有记忆等，均已做数据化包裹）
     * @return 模型完整回复文本；流为空或超时抛出对应异常（由调用方捕获降级）
     */
    public static String complete(ChatModel model, String system, String user) {
        StringBuilder accumulated = new StringBuilder();
        ChatChunk last = model.stream(new ChatRequest(List.of(
                        new ChatMessage(ChatMessage.Role.SYSTEM, system),
                        new ChatMessage(ChatMessage.Role.USER, user))))
                .doOnNext(chunk -> accumulated.append(chunk.content()))
                // 完整响应在最后一片 chunk.response() 中；blockLast 取收尾片段
                .blockLast(TIMEOUT);
        if (last != null && last.response() != null && !last.response().content().isBlank()) {
            return last.response().content();
        }
        // 兜底：个别适配器收尾片段不带完整响应时，用累积增量
        return accumulated.toString();
    }

    /**
     * 把模型回复规整为纯 JSON 文本：剥 ```json 围栏、截取首个 {...} 或 [...] 区段。
     *
     * @return JSON 文本；解析不出结构时抛 {@link IllegalArgumentException}
     */
    public static String extractJson(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("模型回复为空，无法解析 JSON");
        }
        String value = text.strip();
        // 常见形态：```json ... ``` 或 ``` ... ```
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```[a-zA-Z]*\\s*", "");
            int fenceEnd = value.lastIndexOf("```");
            if (fenceEnd >= 0) {
                value = value.substring(0, fenceEnd);
            }
            value = value.strip();
        }
        // 再兜底一次：直接截取首个 { 或 [ 到最后一个 } 或 ] 之间的内容
        int start = indexOfFirst(value, '{', '[');
        if (start >= 0) {
            char open = value.charAt(start);
            char close = open == '{' ? '}' : ']';
            int end = value.lastIndexOf(close);
            if (end > start) {
                value = value.substring(start, end + 1);
            }
        }
        if (!value.startsWith("{") && !value.startsWith("[")) {
            throw new IllegalArgumentException("模型回复中不含 JSON 结构：" + abbreviate(text));
        }
        return value;
    }

    /**
     * 解析 JSON 为指定类型（宽松模式，未知字段忽略）。
     */
    public static <T> T parse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败：" + abbreviate(json), e);
        }
    }

    /**
     * 解析 JSON 数组为元素列表。
     */
    public static <T> java.util.List<T> parseList(String json, Class<T> elementType) {
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructCollectionType(java.util.List.class, elementType));
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 数组解析失败：" + abbreviate(json), e);
        }
    }

    /**
     * 找到任一目标字符的最早出现位置。
     */
    private static int indexOfFirst(String value, char a, char b) {
        int ia = value.indexOf(a);
        int ib = value.indexOf(b);
        if (ia < 0) {
            return ib;
        }
        if (ib < 0) {
            return ia;
        }
        return Math.min(ia, ib);
    }

    private static String abbreviate(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() <= 120 ? value : value.substring(0, 120) + "...";
    }
}
