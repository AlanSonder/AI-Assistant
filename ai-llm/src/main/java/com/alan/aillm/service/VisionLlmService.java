package com.alan.aillm.service;

import com.alan.aicommon.exception.LlmException;
import com.alan.aillm.config.LlmConfig;
import com.alan.aillm.dto.request.ChatRequest;
import com.alan.aillm.dto.response.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class VisionLlmService {

    @Autowired
    private LlmConfig llmConfig;

    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) llmConfig.getTimeout());
        factory.setReadTimeout((int) llmConfig.getTimeout());
        restTemplate = new RestTemplate(factory);
    }

    /**
     * 图片识别与翻译 - 直接发送Base64图片到LLM
     */
    public String translateImage(byte[] imageBytes, String from, String to, String domain, String style) {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        return translateImageBase64(base64Image, from, to, domain, style);
    }

    /**
     * 图片识别与翻译 - Base64编码图片
     */
    public String translateImageBase64(String base64Image, String from, String to, String domain, String style) {
        String systemPrompt = buildImageTranslatePrompt(from, to, domain, style);
        String userText = "请识别图片中的文字并将其翻译为目标语言。只输出翻译结果，不要添加任何解释。";

        List<Object> messages = List.of(
                ChatRequest.Message.system(systemPrompt),
                ChatRequest.VisionMessage.userWithImage(userText, base64Image)
        );

        return callVisionApi(messages);
    }

    /**
     * 图片内容识别（仅提取文字，不翻译）
     */
    public String extractTextFromImage(byte[] imageBytes) {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        String systemPrompt = "你是一个OCR引擎。请识别图片中的所有文字，保持原有格式和换行。只输出识别到的文字内容，不要添加任何解释。";
        String userText = "请识别这张图片中的所有文字：";

        List<Object> messages = List.of(
                ChatRequest.Message.system(systemPrompt),
                ChatRequest.VisionMessage.userWithImage(userText, base64Image)
        );

        return callVisionApi(messages);
    }

    @Retryable(
            value = {ResourceAccessException.class, HttpServerErrorException.class},
            maxAttemptsExpression = "${ai.llm.max-retries:3}",
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    private String callVisionApi(List<Object> messages) {
        String url = llmConfig.getBaseUrl() + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (llmConfig.getApiKey() != null && !llmConfig.getApiKey().isEmpty()) {
            headers.set("Authorization", "Bearer " + llmConfig.getApiKey());
        }

        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("model", llmConfig.getModel());
        requestBody.put("messages", messages);
        requestBody.put("temperature", llmConfig.getTemperature());
        requestBody.put("top_p", llmConfig.getTopP());
        requestBody.put("max_tokens", llmConfig.getMaxTokens());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        long startTime = System.currentTimeMillis();
        try {
            ResponseEntity<ChatResponse> response =
                    restTemplate.postForEntity(url, entity, ChatResponse.class);

            long duration = System.currentTimeMillis() - startTime;
            log.info("Vision LLM响应成功: duration={}ms, model={}", duration, llmConfig.getModel());

            if (response.getBody() == null) {
                throw new LlmException("LLM返回响应体为空");
            }

            return extractContent(response.getBody());
        } catch (ResourceAccessException e) {
            log.error("Vision LLM连接异常: url={}, error={}", url, e.getMessage());
            throw new LlmException("LLM服务连接失败，请检查LM Studio是否启动", e);
        } catch (HttpServerErrorException e) {
            log.error("Vision LLM服务器错误: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new LlmException("LLM服务内部错误: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            log.error("Vision LLM调用失败: error={}", e.getMessage(), e);
            throw new LlmException("LLM调用失败: " + e.getMessage(), e);
        }
    }

    private String extractContent(ChatResponse response) {
        if (response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new LlmException("LLM返回choices为空");
        }

        ChatResponse.Choice choice = response.getChoices().get(0);
        if (choice.getMessage() == null || choice.getMessage().getContent() == null) {
            throw new LlmException("LLM返回消息内容为空");
        }

        String content = choice.getMessage().getContent();
        if (content.trim().isEmpty()) {
            throw new LlmException("LLM返回消息内容为空字符串");
        }

        return content.trim();
    }

    private String buildImageTranslatePrompt(String from, String to, String domain, String style) {
        String domainDesc = switch (domain.toLowerCase()) {
            case "tech" -> "技术领域（计算机、软件工程相关术语）";
            case "medical" -> "医学领域（临床、解剖、药理等术语）";
            case "legal" -> "法律领域（合同、法规、诉讼文书）";
            case "business" -> "商业领域（财务、管理、市场术语）";
            case "literary" -> "文学领域（小说、诗歌、散文）";
            default -> "通用领域";
        };

        String styleDesc = switch (style.toLowerCase()) {
            case "formal" -> "正式、书面语风格";
            case "casual" -> "口语化、自然风格";
            case "academic" -> "学术、严谨风格";
            default -> "中性风格，保持原文语气";
        };

        return String.format("""
                你是专业的图片翻译引擎，能够识别图片中的文字并进行高质量翻译。

                任务：
                1. 识别图片中的所有文字内容
                2. 将识别到的文字从%s翻译为%s
                3. 保持原文的格式和排版

                领域: %s - %s
                风格: %s

                规则:
                1. 只输出翻译后的结果
                2. 保持原文的格式、换行和标点风格
                3. 专业术语使用领域标准译法
                4. 保留专有名词（人名、地名、品牌名）原文
                5. 代码片段和技术术语不要翻译
                6. 如遇无法翻译的内容，原样返回
                7. 输出必须完整，不能截断
                """, from, to, domain, domainDesc, styleDesc);
    }
}
