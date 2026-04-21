package com.alan.aitranslator.service;

import com.alan.aillm.service.LlmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TranslateService {

    @Autowired
    private LlmService llmService;

    public String translate(String text, String from, String to) {

        String systemPrompt = """
        你是一个翻译引擎。

        规则：
        - 只输出翻译结果
        - 不解释
        - 不扩展
        - 输出必须完整
        """;

        String userPrompt = String.format(
                "将以下内容从 %s 翻译为 %s：\n%s",
                from, to, text
        );

        return llmService.chat(systemPrompt, userPrompt);
    }
}
