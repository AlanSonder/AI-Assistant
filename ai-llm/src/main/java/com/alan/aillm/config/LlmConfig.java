package com.alan.aillm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.llm")
public class LlmConfig {

    private String baseUrl = "http://localhost:1234/v1";

    private String model = "qwen";

    private String apiKey = "";

    private long timeout = 30000;

    private int maxRetries = 3;

    private double temperature = 0.3;

    private double topP = 0.9;

    private int maxTokens = 4096;

    private boolean stream = false;
}
