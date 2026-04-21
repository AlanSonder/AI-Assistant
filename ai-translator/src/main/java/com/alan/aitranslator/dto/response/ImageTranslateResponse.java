package com.alan.aitranslator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageTranslateResponse {
    private String extractedText;
    private String translatedText;
    private String from;
    private String to;
    private Long durationMs;
}
