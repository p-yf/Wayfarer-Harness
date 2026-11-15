package com.yf.wayfarer.loop.toolchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.loop.ChainHandler;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * 工具重复调用检查处理器。
 *
 * @author yyf
 * @description 按先进先出维护最近 10 次调用的哈希：连续 3 次相同则放行并追加提醒，
 * 连续 5 次相同则短路，直接返回严重警告且不执行工具
 */
public class ToolCircleCheckHandler implements ChainHandler {

    private static final Logger log = LoggerFactory.getLogger(ToolCircleCheckHandler.class);

    /**
     * 检查状态在上下文中的键，单次运行内由各轮工具链共享。
     */
    public static final String GUARD = "toolGuard";

    /**
     * 当前工具调用的判定结果，由本处理器写入，执行处理器读取后决定是否追加提醒。
     */
    public static final String LEVEL = "toolLevel";

    public static final String WARN = "<sys-注意：你已经连续三次调用同一个工具了请勿重复调用>";
    public static final String BLOCK = "<sys-严重警告：连续五次调用同一个工具！！禁止执行！！>";

    @Override
    public int sort() {
        // 单次处理的最外层：先判定是否允许执行
        return 10;
    }

    @Override
    public void run(AgentContext context, Runnable next) {
        ToolCallInfo info = context.currentToolCall();
        if (info == null) {
            next.run();
            return;
        }
        Guard guard = context.get(GUARD, Guard.class);
        if (guard == null) {
            guard = new Guard();
            context.put(GUARD, guard);
        }
        Level level = guard.check(info);
        context.put(LEVEL, level);
        switch (level) {
            case BLOCK -> {
                log.warn("拦截重复工具调用 traceId={} 工具={}", context.traceId(), info.name());
                ToolCallHandler.appendResult(context, info, BLOCK);
            }
            case WARN -> {
                log.warn("工具被连续重复调用 traceId={} 工具={}", context.traceId(), info.name());
                next.run();
            }
            case PASS -> next.run();
        }
    }

    /**
     * 当前调用的判定结果，未经检查时视为放行。
     */
    public static Level levelOf(AgentContext context) {
        Level level = context.get(LEVEL, Level.class);
        return level == null ? Level.PASS : level;
    }

    /**
     * 检查结果。
     */
    public enum Level {
        PASS,
        WARN,
        BLOCK
    }

    /**
     * 最近调用的哈希队列，队列大小固定为 10。
     */
    public static final class Guard {

        private static final int SIZE = 10;

        private final Deque<Integer> recent = new ArrayDeque<>(SIZE);

        /**
         * 记录一次调用并给出处理级别。哈希只取工具名与参数，
         * 不含调用 id，否则每次调用都不同，无法识别重复调用。
         */
        public synchronized Level check(ToolCallInfo info) {
            if (info == null || info.name() == null) {
                return Level.PASS;
            }
            recent.addLast(Objects.hash(info.name(), info.arguments()));
            while (recent.size() > SIZE) {
                recent.removeFirst();
            }
            int same = 0;
            Integer latest = recent.peekLast();
            for (var iterator = recent.descendingIterator(); iterator.hasNext(); ) {
                if (!Objects.equals(iterator.next(), latest)) {
                    break;
                }
                same++;
            }
            if (same >= 5) {
                return Level.BLOCK;
            }
            if (same >= 3) {
                return Level.WARN;
            }
            return Level.PASS;
        }
    }
}
