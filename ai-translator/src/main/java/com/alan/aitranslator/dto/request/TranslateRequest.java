package com.alan.aitranslator.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TranslateRequest {

    @NotBlank(message = "翻译文本不能为空")
    @Size(max = 10000, message = "文本长度不能超过10000字符")
    private String text;

    @NotBlank(message = "源语言不能为空")
    private String from;

    @NotBlank(message = "目标语言不能为空")
    private String to;

    private String domain = "general";

    private String style = "neutral";

    private String contextId;

    @Size(max = 500, message = "上下文文本长度不能超过500字符")
    private String contextText;
}
