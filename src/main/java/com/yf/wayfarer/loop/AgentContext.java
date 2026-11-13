package com.yf.wayfarer.loop;

import com.yf.wayfarer.model.ChatMessage;
import com.yf.wayfarer.model.ModelResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * agent 运行时上下文。
 *
 * @author yyf
 * @description 以 ConcurrentHashMap 保存链路间共享的状态，并约定模型响应与消息汇聚口的存放键
 */
public final class AgentContext {

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
     * 可持久化状态的键前缀，会话结束时统一写入会话文件。
     */
    public static final String STORE_PREFIX = "store:";

    private final ConcurrentHashMap<String, Object> values = new ConcurrentHashMap<>();

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
