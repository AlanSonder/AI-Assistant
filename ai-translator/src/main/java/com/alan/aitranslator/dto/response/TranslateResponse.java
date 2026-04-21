package com.alan.aitranslator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslateResponse {
    private String originalText;
    private String translatedText;
    private String from;
    private String to;
    private String domain;
    private String style;
    private Long durationMs;
    private boolean cached;
}
