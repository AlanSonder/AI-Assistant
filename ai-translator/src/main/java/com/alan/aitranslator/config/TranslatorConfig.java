package com.alan.aitranslator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.translator")
public class TranslatorConfig {

    private int maxTextLength = 10000;

    private int maxSegmentLength = 3000;

    private boolean enableCache = true;

    private int cacheSize = 10000;

    private int cacheTtlMinutes = 1440;

    private String defaultDomain = "general";

    private String defaultStyle = "neutral";
}
