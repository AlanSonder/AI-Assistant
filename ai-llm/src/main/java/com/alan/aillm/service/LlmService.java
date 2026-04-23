package com.alan.aillm.service;

import com.alan.aicommon.exception.LlmException;
import com.alan.aillm.config.LlmConfig;
import com.alan.aillm.dto.request.ChatRequest;
import com.alan.aillm.dto.response.ChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class LlmService {

    @Autowired
    private LlmConfig llmConfig;

    private WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        webClient = WebClient.builder()
                .baseUrl(llmConfig.getBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "text/event-stream")
                .build();
    }

    public String chat(String systemPrompt, String userPrompt) {
        List<ChatRequest.Message> messages = new ArrayList<>();
        messages.add(ChatRequest.Message.system(systemPrompt));
        messages.add(ChatRequest.Message.user(userPrompt));
        return chat(messages);
    }

    public String chat(List<ChatRequest.Message> messages) {
        ChatRequest request = buildRequest(messages);

        log.debug("LLM请求: model={}, messages={}", llmConfig.getModel(), messages.size());

        return callStreamApi(request);
    }

    private ChatRequest buildRequest(List<ChatRequest.Message> messages) {
        ChatRequest request = new ChatRequest();
        request.setModel(llmConfig.getModel());
        request.setMessages(messages);
        request.setTemperature(llmConfig.getTemperature());
        request.setTopP(llmConfig.getTopP());
        request.setMaxTokens(llmConfig.getMaxTokens());
        request.setStream(true);
        return request;
    }

    @Retryable(
            value = {WebClientResponseException.class, RuntimeException.class},
            maxAttemptsExpression = "${ai.llm.max-retries:3}",
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    private String callStreamApi(ChatRequest request) {
        String url = "/chat/completions";

        long startTime = System.currentTimeMillis();
        try {
            log.info("发送流式LLM请求...");
            String responseBody = webClient.post()
                    .uri(url)
                    .header("Authorization", llmConfig.getApiKey() != null && !llmConfig.getApiKey().isEmpty() 
                            ? "Bearer " + llmConfig.getApiKey() : null)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            long duration = System.currentTimeMillis() - startTime;
            log.info("LLM响应成功: duration={}ms, model={}", duration, llmConfig.getModel());

            if (responseBody == null || responseBody.isEmpty()) {
                throw new LlmException("LLM返回响应体为空");
            }

            return parseStreamResponse(responseBody);
        } catch (WebClientResponseException e) {
            log.error("LLM服务器错误: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new LlmException("LLM服务器错误: " + e.getStatusCode() + ", " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("LLM调用失败: error={}", e.getMessage(), e);
            throw new LlmException("LLM调用失败: " + e.getMessage(), e);
        }
    }

    private String parseStreamResponse(String responseBody) {
        StringBuilder content = new StringBuilder();
        String[] lines = responseBody.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || !line.startsWith("data:")) {
                continue;
            }
            
            String data = line.substring(5).trim();
            if (data.equals("[DONE]")) {
                break;
            }
            
            try {
                JsonNode jsonNode = objectMapper.readTree(data);
                JsonNode choices = jsonNode.get("choices");
                if (choices != null && choices.isArray() && choices.size() > 0) {
                    JsonNode delta = choices.get(0).get("delta");
                    if (delta != null) {
                        JsonNode contentNode = delta.get("content");
                        if (contentNode != null && !contentNode.isNull()) {
                            content.append(contentNode.asText());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("解析流式响应行失败: {}", e.getMessage());
            }
        }
        
        String result = content.toString().trim();
        if (result.isEmpty()) {
            throw new LlmException("LLM返回消息内容为空字符串");
        }
        
        log.info("提取内容成功: 长度={}", result.length());
        return result;
    }

    public String chatWithHistory(String systemPrompt, String userPrompt, List<ChatRequest.Message> history) {
        List<ChatRequest.Message> messages = new ArrayList<>();
        messages.add(ChatRequest.Message.system(systemPrompt));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(ChatRequest.Message.user(userPrompt));
        return chat(messages);
    }
}
