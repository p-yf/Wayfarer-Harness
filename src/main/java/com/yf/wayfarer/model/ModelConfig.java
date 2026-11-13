package com.yf.wayfarer.model;

import java.util.Locale;
import java.util.Set;

/**
 * 模型配置。
 *
 * @author yyf
 * @description 承载 apiKey、服务地址、模型名、采样参数与思考强度，并标记该配置是否启用
 */
public record ModelConfig(String apiKey, String baseUrl, String model, Double temperature,
                          Long maxTokens, String reasoningEffort, boolean enabled) {

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-flash";
    public static final String DEFAULT_REASONING_EFFORT = "high";

    private static final Set<String> EFFORTS = Set.of("none", "low", "high", "max");

    public ModelConfig(String apiKey, String baseUrl, String model, Double temperature,
                       Long maxTokens, String reasoningEffort, boolean enabled) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey 不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空");
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("temperature 必须在 0 到 2 之间");
        }
        if (maxTokens != null && maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens 必须大于 0");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.reasoningEffort = normalizeEffort(reasoningEffort);
        this.enabled = enabled;
    }

    public ModelConfig(String apiKey, String model) {
        this(apiKey, DEFAULT_BASE_URL, model, null, null);
    }

    public ModelConfig(String apiKey, String model, Double temperature, Long maxTokens) {
        this(apiKey, DEFAULT_BASE_URL, model, temperature, maxTokens);
    }

    public ModelConfig(String apiKey, String baseUrl, String model, Double temperature, Long maxTokens) {
        this(apiKey, baseUrl, model, temperature, maxTokens, DEFAULT_REASONING_EFFORT, true);
    }

    /**
     * 思考强度归一化：未配置时默认 high，minimal 视为 low，medium 与 xhigh 视为 high。
     */
    private static String normalizeEffort(String effort) {
        if (effort == null || effort.isBlank()) {
            return DEFAULT_REASONING_EFFORT;
        }
        String normalized = effort.trim().toLowerCase(Locale.ROOT);
        normalized = switch (normalized) {
            case "minimal" -> "low";
            case "medium", "xhigh" -> "high";
            default -> normalized;
        };
        if (!EFFORTS.contains(normalized)) {
            throw new IllegalArgumentException("reasoningEffort 只能是 none、low、high、max：" + effort);
        }
        return normalized;
    }
}
