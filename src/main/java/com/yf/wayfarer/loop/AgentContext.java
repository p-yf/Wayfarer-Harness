package com.yf.wayfarer.loop;

import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ModelResponse;
import com.yf.wayfarer.toolcall.ToolContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * agent 运行时上下文。
 *
 * @author yyf
 * @description 以 ConcurrentHashMap 保存链路间共享的状态，并约定模型响应与消息汇聚口的存放键
 */
public final class AgentContext implements ToolContext {

    /**
     * 模型链需要把完整响应写入该键，供循环判断是否继续。
     */
    public static final String MODEL_RESPONSE = "modelResponse";

    /**
     * 循环运行期间写入的消息汇聚口，链路通过它向下游推送流式片段与工具消息。
     */
    public static final String AGENT_MSG_SINK = "agentMsgSink";

    /**
     * 对话消息历史，调用方需放入可变列表，链路按轮次向其拼接消息。
     */
    public static final String MESSAGES = "messages";

    /**
     * 本轮运行的轮次 id，由 Agent 在运行前生成并写入。
     */
    public static final String TURN_ID = "turnId";

    /**
     * 当前轮次序号，从 1 开始，由 Loop 在每一轮开始时写入。
     */
    public static final String TURN_INDEX = "turnIndex";

    /**
     * 本次运行对应的会话 id，由调用方在运行前写入。
     */
    public static final String SESSION_ID = "sessionId";

    /**
     * 拼接好的系统提示词，由调用方在运行前写入，模型调用时作为首条 system 消息。
     */
    public static final String SYSTEM_PROMPT = "systemprompt";

    /**
     * 当前 agent 名称，由调用方在运行前写入，HITL 确认键与提示词目录都依赖它。
     */
    public static final String AGENT_NAME = "agentName";

    /**
     * 正在执行的工具调用 id，由工具链在调用每个工具前写入，工具据此找到自己的 HITL 等待项。
     */
    public static final String TOOL_CALL_ID = "toolCallId";

    /**
     * 正在执行的工具名称，由工具链在调用每个工具前写入，供 HITL 待确认列表展示。
     */
    public static final String TOOL_NAME = "toolName";

    /**
     * 可持久化状态的键前缀，会话结束时统一写入会话文件。
     */
    public static final String STORE_PREFIX = "store:";

    private final ConcurrentHashMap<String, Object> values = new ConcurrentHashMap<>();

    /**
     * 运行被取消时执行的回调，例如让工具侧正在等待的确认立即结束。
     */
    private final List<Runnable> cancelHooks = new CopyOnWriteArrayList<>();

    /**
     * 登记取消回调，供循环取消时统一触发。
     */
    public void onCancel(Runnable hook) {
        cancelHooks.add(hook);
    }

    /**
     * 执行并清空已登记的取消回调。
     */
    public void fireCancel() {
        for (Runnable hook : cancelHooks) {
            try {
                hook.run();
            } catch (RuntimeException ignored) {
                // 单个回调失败不影响其余回调
            }
        }
        cancelHooks.clear();
    }

    public AgentContext put(String key, Object value) {
        values.put(key, value);
        return this;
    }

    public Object get(String key) {
        return values.get(key);
    }

    public <T> T get(String key, Class<T> type) {
        Object value = values.get(key);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    public boolean containsKey(String key) {
        return values.containsKey(key);
    }

    public ModelResponse modelResponse() {
        return get(MODEL_RESPONSE, ModelResponse.class);
    }

    /**
     * 取对话消息历史（可变列表）。
     */
    @SuppressWarnings("unchecked")
    public List<ChatMessage> messages() {
        return (List<ChatMessage>) get(MESSAGES);
    }

    /**
     * 取循环写入的消息汇聚口，仅在 Loop.run 执行期间存在。
     */
    @SuppressWarnings("unchecked")
    public Consumer<AgentMsg> agentMsgSink() {
        return (Consumer<AgentMsg>) get(AGENT_MSG_SINK);
    }

    public ConcurrentHashMap<String, Object> asMap() {
        return values;
    }

    /**
     * 当前轮次 id，未进入运行时为 null。
     */
    public String turnId() {
        return get(TURN_ID, String.class);
    }

    /**
     * 当前会话 id，未设置时为 null。
     */
    public String sessionId() {
        return get(SESSION_ID, String.class);
    }

    /**
     * 当前轮次序号，未进入运行时为 null。
     */
    public Integer turnIndex() {
        return get(TURN_INDEX, Integer.class);
    }

    /**
     * 正在执行的工具调用 id，未在执行工具时为 null。
     */
    public String toolCallId() {
        return get(TOOL_CALL_ID, String.class);
    }

    /**
     * 正在执行的工具名称，未在执行工具时为 null。
     */
    public String toolName() {
        return get(TOOL_NAME, String.class);
    }

    /**
     * 拼接好的系统提示词，未设置时为 null。
     */
    public String systemPrompt() {
        return get(SYSTEM_PROMPT, String.class);
    }

    /**
     * 收集所有可持久化状态（键以 store: 开头的条目）。
     */
    public Map<String, Object> storeState() {
        Map<String, Object> state = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key.startsWith(STORE_PREFIX)) {
                state.put(key, value);
            }
        });
        return state;
    }
}
