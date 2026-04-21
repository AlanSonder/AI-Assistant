package com.alan.aitranslator.dto;

import lombok.Data;

@Data
public class TranslateRequest {
    private String text;
    private String from;
    private String to;
}
