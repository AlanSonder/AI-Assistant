package com.alan.aillm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.llm")
public class LlmConfig {

    private String baseUrl;

    private String model;

    private String apiKey;

    private long timeout;

    private int maxRetries;

    private double temperature;

    private double topP;

    private int maxTokens;

    private boolean stream;
}
