package com.alan.aitranslator.service;

import com.alan.aillm.service.LlmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TranslateService {

    @Autowired
    private LlmService llmService;

    public String translateToEnglish(String text) {
        String systemPrompt = "你是一个专业翻译助手";
        String userPrompt = "把下面内容翻译成英文，不要解释，只输出结果：\n" + text;

        return llmService.chat(systemPrompt, userPrompt);
    }
}
