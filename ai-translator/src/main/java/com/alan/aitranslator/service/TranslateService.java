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
        String userPrompt = "你是一个翻译助手。\n" +
                "\n" +
                "严格要求：\n" +
                "1. 只输出翻译结果\n" +
                "2. 不要解释\n" +
                "3. 不要输出思考过程\n" +
                "4. 不要添加任何额外内容\n" + text;

        return llmService.chat(systemPrompt, userPrompt);
    }
}
