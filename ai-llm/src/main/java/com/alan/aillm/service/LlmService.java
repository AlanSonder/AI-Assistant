package com.alan.aillm.service;

import com.alan.aicommon.exception.LlmException;
import com.alan.aillm.config.LlmConfig;
import com.alan.aillm.dto.request.ChatRequest;
import com.alan.aillm.dto.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    @PostConstruct
    public void init() {
        webClient = WebClient.builder()
                .baseUrl(llmConfig.getBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
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

        ChatResponse response = callApi(request);

        return extractContent(response);
    }

    private ChatRequest buildRequest(List<ChatRequest.Message> messages) {
        ChatRequest request = new ChatRequest();
        request.setModel(llmConfig.getModel());
        request.setMessages(messages);
        request.setTemperature(llmConfig.getTemperature());
        request.setTopP(llmConfig.getTopP());
        request.setMaxTokens(llmConfig.getMaxTokens());
        request.setStream(false);
        return request;
    }

    @Retryable(
            value = {WebClientResponseException.class, RuntimeException.class},
            maxAttemptsExpression = "${ai.llm.max-retries:3}",
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    private ChatResponse callApi(ChatRequest request) {
        String url = "/chat/completions";

        long startTime = System.currentTimeMillis();
        try {
            ChatResponse response = webClient.post()
                    .uri(url)
                    .header("Authorization", llmConfig.getApiKey() != null && !llmConfig.getApiKey().isEmpty() 
                            ? "Bearer " + llmConfig.getApiKey() : null)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(ChatResponse.class)
                    .block();

            long duration = System.currentTimeMillis() - startTime;
            log.info("LLM响应成功: duration={}ms, model={}", duration, llmConfig.getModel());

            if (response == null) {
                throw new LlmException("LLM返回响应体为空");
            }

            return response;
        } catch (WebClientResponseException e) {
            log.error("LLM服务器错误: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new LlmException("LLM服务器错误: " + e.getStatusCode() + ", " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("LLM调用失败: error={}", e.getMessage(), e);
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
