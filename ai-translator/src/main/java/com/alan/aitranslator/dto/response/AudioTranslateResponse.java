package com.alan.aitranslator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioTranslateResponse {
    private String recognizedText;
    private String translatedText;
    private String from;
    private String to;
    private Long durationMs;
    private Long audioDurationMs;
}
