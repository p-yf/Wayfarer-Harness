package com.yf.wayfarer.agent.modelchain;

/**
 * 记忆压缩配置。
 *
 * @author yyf
 * @description 承载上下文窗口与三级压缩的各级阈值，并标记该配置是否启用；未填写的项使用默认值
 */
public record MemoryCompactConfig(Integer contextWindow, Double triggerRatio, Double toolRatio,
                                  Double messageRatio, Double compressRatio, Integer keepChars,
                                  boolean enabled) {

    public static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    public static final double DEFAULT_TRIGGER_RATIO = 0.7;
    public static final double DEFAULT_TOOL_RATIO = 0.01;
    public static final double DEFAULT_MESSAGE_RATIO = 0.02;
    public static final double DEFAULT_COMPRESS_RATIO = 0.5;
    public static final int DEFAULT_KEEP_CHARS = 100;

    public MemoryCompactConfig {
        contextWindow = contextWindow == null ? DEFAULT_CONTEXT_WINDOW : contextWindow;
        triggerRatio = triggerRatio == null ? DEFAULT_TRIGGER_RATIO : triggerRatio;
        toolRatio = toolRatio == null ? DEFAULT_TOOL_RATIO : toolRatio;
        messageRatio = messageRatio == null ? DEFAULT_MESSAGE_RATIO : messageRatio;
        compressRatio = compressRatio == null ? DEFAULT_COMPRESS_RATIO : compressRatio;
        keepChars = keepChars == null ? DEFAULT_KEEP_CHARS : keepChars;
        if (contextWindow <= 0) {
            throw new IllegalArgumentException("contextWindow 必须大于 0");
        }
        requireRatio(triggerRatio, "triggerRatio");
        requireRatio(toolRatio, "toolRatio");
        requireRatio(messageRatio, "messageRatio");
        requireRatio(compressRatio, "compressRatio");
        if (keepChars < 0) {
            throw new IllegalArgumentException("keepChars 不能为负数");
        }
    }

    /**
     * 全部使用默认值且启用的配置。
     */
    public static MemoryCompactConfig defaults() {
        return new MemoryCompactConfig(null, null, null, null, null, null, true);
    }

    private static void requireRatio(double ratio, String label) {
        if (ratio <= 0 || ratio > 1) {
            throw new IllegalArgumentException(label + " 必须在 0 到 1 之间");
        }
    }
}
