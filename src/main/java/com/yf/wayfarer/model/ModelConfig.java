package com.yf.wayfarer.model;

/**
 * 模型配置，仅保留必要参数。
 *
 * @author yyf
 * @description 承载 apiKey、服务地址、模型名与采样参数（temperature、max_tokens）
 */
public final class ModelConfig {

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-flash";

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Double temperature;
    private final Long maxTokens;

    public ModelConfig(String apiKey, String model) {
        this(apiKey, DEFAULT_BASE_URL, model, null, null);
    }

    public ModelConfig(String apiKey, String model, Double temperature, Long maxTokens) {
        this(apiKey, DEFAULT_BASE_URL, model, temperature, maxTokens);
    }

    public ModelConfig(String apiKey, String baseUrl, String model, Double temperature, Long maxTokens) {
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
    }

    public String apiKey() {
        return apiKey;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String model() {
        return model;
    }

    public Double temperature() {
        return temperature;
    }

    public Long maxTokens() {
        return maxTokens;
    }
}
