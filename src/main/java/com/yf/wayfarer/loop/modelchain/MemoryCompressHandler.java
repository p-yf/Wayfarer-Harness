package com.yf.wayfarer.loop.modelchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.message.AgentMessage;
import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ChatModel;
import com.yf.wayfarer.model.ChatRequest;
import com.yf.wayfarer.session.JSONMsgType;
import com.yf.wayfarer.session.Session;
import com.yf.wayfarer.session.SessionMessage;
import com.yf.wayfarer.session.UnloadedMessageStore;
import com.yf.wayfarer.toolcall.ToolCallInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆压缩处理器。
 *
 * @author yyf
 * @description 上下文超过触发阈值后以本轮为界，依次执行三级压缩：卸载大工具结果、卸载大消息、LLM 压缩头部累计消息；
 * 前一级生效即退出，历史处理完后再对本轮执行同样流程
 */
public class MemoryCompressHandler implements ChainHandler {

    /**
     * 卸载占位符，uuid 指向卸载文件中的记录。
     */
    public static final String PLACEHOLDER = "<sys-unloaded-%s>";

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("<sys-unloaded-([0-9a-fA-F\\-]+)>");

    private static final String COMPRESS_PROMPT = """
            把下面的对话压缩成要点记忆，供后续轮次继续使用。
            要求：保留事实、结论、已执行的动作与结果、未完成的事项、关键标识符（路径、函数名、ID、数值）；
            丢弃寒暄与重复表述；不要编造内容；输出纯文本，不超过 800 字。""";

    private final ChatModel model;
    private final UnloadedMessageStore store;
    private final MemoryCompactConfig compact;

    /**
     * @param compact 记忆压缩配置，包含上下文窗口与各级阈值
     */
    public MemoryCompressHandler(ChatModel model, UnloadedMessageStore store, MemoryCompactConfig compact) {
        if (compact == null) {
            throw new IllegalArgumentException("记忆压缩配置不能为空");
        }
        this.model = model;
        this.store = store;
        this.compact = compact;
    }

    @Override
    public int sort() {
        // 先于模型调用：压缩后的消息才是本轮实际发送给模型的内容
        return -100;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        compress(context);
        next.run();
    }

    /**
     * 未超触发阈值时不做任何改动。
     */
    void compress(AgentContext context) {
        List<ChatMessage> messages = context.messages();
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (estimate(messages) <= tokens(compact.triggerRatio())) {
            return;
        }

        List<ChatMessage> original = List.copyOf(messages);
        List<ChatMessage> all = new ArrayList<>(messages);
        int split = lastUserIndex(all);
        List<ChatMessage> history = new ArrayList<>(all.subList(0, split));
        List<ChatMessage> current = new ArrayList<>(all.subList(split, all.size()));

        compressRange(history, context);

        // 历史压完仍然超阈值，则对本轮执行同样的三级流程
        List<ChatMessage> result = new ArrayList<>(history);
        result.addAll(current);
        if (estimate(result) > tokens(compact.triggerRatio())) {
            compressRange(current, context);
            result = new ArrayList<>(history);
            result.addAll(current);
        }

        messages.clear();
        messages.addAll(result);
        if (!original.equals(result)) {
            persistSnapshot(context, result);
            notifyCompressed(context, result);
        }
    }

    /**
     * 压缩发生后推送一条 other 事件，让前端展示压缩时机与压缩后的上下文规模。
     */
    private void notifyCompressed(AgentContext context, List<ChatMessage> messages) {
        Consumer<AgentMessage> sink = context.agentMsgSink();
        if (sink == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "compact");
        data.put("message", "上下文已压缩");
        data.put("tokens", estimate(messages));
        sink.accept(new AgentMessage.Other(context.traceId(), data));
    }

    /**
     * 把压缩后的消息写成 COMPACT 快照。
     * 不落盘的话压缩只在本轮内存里有效，下一轮恢复会话又会被完整历史覆盖回去，压缩等于没做。
     */
    private void persistSnapshot(AgentContext context, List<ChatMessage> messages) {
        // 只有 root 能写 COMPACT；子 Agent 允许在内存中压缩，否则父下次恢复会把子记忆当基线
        if (context.origin() != null) {
            return;
        }
        Session session = context.session();
        String sessionId = context.sessionId();
        if (session == null || sessionId == null) {
            return;
        }
        List<Map<String, Object>> snapshot = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            snapshot.add(SessionMessage.toMap(message));
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("messages", snapshot);
        session.append(sessionId, JSONMsgType.COMPACT, context.traceId(), content);
    }

    /**
     * 对一个区间执行三级压缩：一级生效即退出，否则进入下一级。
     */
    private void compressRange(List<ChatMessage> range, AgentContext context) {
        if (range.isEmpty()) {
            return;
        }
        if (unloadToolResults(range, context)) {
            return;
        }
        if (unloadMessages(range, context)) {
            return;
        }
        // LLM 压缩是破坏性的，仅当该区间自身确实超限时才执行
        if (estimate(range) > tokens(compact.triggerRatio())) {
            compressHead(range, context);
        }
    }

    /**
     * 一级：工具结果超过阈值即整段搬进卸载文件。
     */
    private boolean unloadToolResults(List<ChatMessage> range, AgentContext context) {
        boolean changed = false;
        for (int i = 0; i < range.size(); i++) {
            ChatMessage message = range.get(i);
            if (message.role() != ChatMessage.Role.TOOL
                    || estimate(message.content()) <= tokens(compact.toolRatio())) {
                continue;
            }
            range.set(i, withContent(message, unload(message.content(), context)));
            changed = true;
        }
        return changed;
    }

    /**
     * 二级：普通消息超过阈值即搬进卸载文件，头尾各保留若干字符。
     */
    private boolean unloadMessages(List<ChatMessage> range, AgentContext context) {
        boolean changed = false;
        int keepChars = compact.keepChars();
        for (int i = 0; i < range.size(); i++) {
            ChatMessage message = range.get(i);
            if (message.role() == ChatMessage.Role.TOOL
                    || estimate(message.content()) <= tokens(compact.messageRatio())) {
                continue;
            }
            String content = message.content();
            String head = content.substring(0, Math.min(keepChars, content.length()));
            String tail = content.length() > keepChars
                    ? content.substring(content.length() - keepChars)
                    : "";
            String replaced = head + "\n" + unload(content, context) + (tail.isEmpty() ? "" : "\n" + tail);
            range.set(i, withContent(message, replaced));
            changed = true;
        }
        return changed;
    }

    /**
     * 三级：从头累计到超过阈值的消息整段交给模型压缩。
     * 若到区间末尾仍未超过阈值，则压缩整个区间，视为压缩失败（不够彻底）。
     */
    private void compressHead(List<ChatMessage> range, AgentContext context) {
        int budget = tokens(compact.compressRatio());
        int accumulated = 0;
        int cut = 0;
        while (cut < range.size() && accumulated <= budget) {
            accumulated += estimateMessage(range.get(cut));
            cut++;
        }

        List<ChatMessage> slice = new ArrayList<>(range.subList(0, cut));
        ChatMessage summary = summarize(slice);
        if (summary == null) {
            // 模型不可用：保留原消息，避免丢失上下文
            return;
        }

        // 被压缩内容里已经卸载的记录不再保留，避免残留孤儿数据
        for (ChatMessage message : slice) {
            deleteUnloaded(message.content(), context);
        }

        range.subList(0, cut).clear();
        range.add(0, summary);
    }

    private String unload(String content, AgentContext context) {
        String uuid = store.save(sessionId(context), content);
        return String.format(PLACEHOLDER, uuid);
    }

    private void deleteUnloaded(String content, AgentContext context) {
        if (content == null || content.isEmpty()) {
            return;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(content);
        String sessionId = sessionId(context);
        while (matcher.find()) {
            store.delete(sessionId, matcher.group(1));
        }
    }

    /**
     * 交给模型把一段消息压成一条系统记忆，失败时返回 null。
     */
    private ChatMessage summarize(List<ChatMessage> slice) {
        List<ChatMessage> request = List.of(
                new ChatMessage(ChatMessage.Role.SYSTEM, COMPRESS_PROMPT),
                new ChatMessage(ChatMessage.Role.USER, transcript(slice)));
        try {
            String summary = model.stream(new ChatRequest(request))
                    .map(chunk -> chunk.content() == null ? "" : chunk.content())
                    .collect(StringBuilder::new, StringBuilder::append)
                    .map(StringBuilder::toString)
                    .block();
            if (summary == null || summary.isBlank()) {
                return null;
            }
            return new ChatMessage(ChatMessage.Role.SYSTEM, "以下为之前对话的压缩记忆：\n" + summary);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ChatMessage withContent(ChatMessage message, String content) {
        return new ChatMessage(message.role(), content, message.toolCallId(),
                message.reasoningContent(), message.toolCalls());
    }

    private static String transcript(List<ChatMessage> range) {
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : range) {
            builder.append(message.role().name()).append('：').append(message.content()).append('\n');
        }
        return builder.toString();
    }

    /**
     * 最后一条用户消息的位置，作为本轮的起点；没有用户消息时整段视为历史。
     */
    private static int lastUserIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == ChatMessage.Role.USER) {
                return i;
            }
        }
        return messages.size();
    }

    private static String sessionId(AgentContext context) {
        String sessionId = context.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("压缩记忆需要上下文中的会话 id");
        }
        return sessionId;
    }

    private int tokens(double ratio) {
        return (int) Math.floor(compact.contextWindow() * ratio);
    }

    static int estimate(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            total += estimateMessage(message);
        }
        return total;
    }

    /**
     * 估算单条消息占用的上下文 token。
     * 思考内容只在含工具调用时才会回放给模型，纯文本轮次的思考不占上下文窗口，故不计入。
     */
    private static int estimateMessage(ChatMessage message) {
        int total = estimate(message.content());
        if (!message.toolCalls().isEmpty()) {
            total += estimate(message.reasoningContent());
        }
        for (ToolCallInfo info : message.toolCalls()) {
            total += estimate(info.name()) + estimate(info.arguments());
        }
        // 角色标记与分隔符的固定开销
        return total + 4;
    }

    /**
     * 粗略 token 估算：中日韩字符按 1 token，其余字符按 4 字符 1 token。
     */
    static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            }
        }
        return cjk + (int) Math.ceil((text.length() - cjk) / 4.0);
    }

    private static boolean isCjk(char ch) {
        return ch >= 0x4E00 && ch <= 0x9FFF
                || ch >= 0x3040 && ch <= 0x30FF
                || ch >= 0xAC00 && ch <= 0xD7AF
                || ch >= 0x3400 && ch <= 0x4DBF;
    }
}
