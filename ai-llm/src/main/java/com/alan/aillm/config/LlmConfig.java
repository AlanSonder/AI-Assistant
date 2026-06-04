package com.alan.aillm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * LLM 多Provider配置
 * <p>
 * 支持同时配置多个LLM提供商（本地部署、DeepSeek等云端API），
 * 通过 active-provider 切换当前使用的提供商，
 * 每个提供商可配置多个可用模型。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.llm")
public class LlmConfig {

    /** 当前激活的提供商名称，对应 providers 的 key */
    private String activeProvider = "local";

    /** 默认模型（当请求未指定模型时使用） */
    private String defaultModel = "qwen";

    /** 请求超时时间（毫秒） */
    private long timeout = 60000;

    /** DeepSeek 思考模式运行时开关（null = 使用 YAML 默认值） */
    private Boolean thinkingEnabled = null;

    /** 最大重试次数 */
    private int maxRetries = 3;

    /** 默认温度参数 */
    private double temperature = 0.3;

    /** 默认 Top-P 参数 */
    private double topP = 0.9;

    /** 默认最大 Token 数 */
    private int maxTokens = 4096;

    /** 多提供商配置，key 为提供商名称 */
    private Map<String, ProviderConfig> providers = Map.of();

    /**
     * 获取当前激活的提供商配置
     */
    public ProviderConfig getActiveProviderConfig() {
        ProviderConfig config = providers.get(activeProvider);
        if (config == null) {
            throw new IllegalStateException("未找到激活的LLM提供商配置: " + activeProvider);
        }
        return config;
    }

    /**
     * 设置激活的提供商（拒绝 null 和空字符串）
     */
    public void setActiveProvider(String activeProvider) {
        if (activeProvider == null || activeProvider.isEmpty()) {
            throw new IllegalArgumentException("activeProvider 不能为 null 或空字符串");
        }
        this.activeProvider = activeProvider;
    }

    /**
     * 设置默认模型（拒绝 null 和空字符串）
     */
    public void setDefaultModel(String defaultModel) {
        if (defaultModel == null || defaultModel.isEmpty()) {
            throw new IllegalArgumentException("defaultModel 不能为 null 或空字符串");
        }
        this.defaultModel = defaultModel;
    }

    /**
     * 获取指定提供商配置
     */
    public ProviderConfig getProviderConfig(String providerName) {
        ProviderConfig config = providers.get(providerName);
        if (config == null) {
            throw new IllegalArgumentException("未找到LLM提供商配置: " + providerName);
        }
        return config;
    }

    /**
     * 获取当前有效的模型名称
     */
    public String resolveModel(String requestedModel) {
        if (requestedModel != null && !requestedModel.isEmpty()) {
            return requestedModel;
        }
        return defaultModel;
    }

    /**
     * 当前激活的提供商是否支持 Vision
     */
    public boolean isVisionEnabled() {
        ProviderConfig config = providers.get(activeProvider);
        return config != null && config.isVisionEnabled();
    }

    /**
     * 是否启用 DeepSeek 思考模式（运行时 > YAML 默认值）
     */
    public boolean isThinkingEnabled() {
        if (thinkingEnabled != null) {
            return thinkingEnabled;
        }
        ProviderConfig config = providers.get(activeProvider);
        return config != null && config.hasThinking();
    }

    /**
     * 单个提供商的配置
     */
    @Data
    public static class ProviderConfig {
        /** API 基础地址（不含 /chat/completions 路径） */
        private String baseUrl;

        /** API 密钥 */
        private String apiKey;

        /** 该提供商可用的模型列表 */
        private List<String> models;

        /** DeepSeek 特有的思考模式配置（仅 deepseek 提供商需要） */
        private ThinkingConfig thinking;

        /** DeepSeek 特有的推理深度（仅 deepseek 提供商需要） */
        private String reasoningEffort;

        /** 是否支持 Vision（图片输入），默认 true */
        private boolean visionEnabled = true;

        public boolean hasThinking() {
            return thinking != null && thinking.isEnabled();
        }

        public boolean isVisionEnabled() {
            return visionEnabled;
        }
    }

    /**
     * DeepSeek 思考模式配置
     */
    @Data
    public static class ThinkingConfig {
        private boolean enabled = false;
    }
}
