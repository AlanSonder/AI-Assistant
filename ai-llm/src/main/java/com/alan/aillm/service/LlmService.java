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
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpServerErrorException;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class LlmService {

    @Autowired
    private LlmConfig llmConfig;

    private RestTemplate restTemplate;

    @PostConstruct
    public void init() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) llmConfig.getTimeout());
        factory.setReadTimeout((int) llmConfig.getTimeout());
        restTemplate = new RestTemplate(factory);
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
            value = {ResourceAccessException.class, HttpServerErrorException.class},
            maxAttemptsExpression = "${ai.llm.max-retries:3}",
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    private ChatResponse callApi(ChatRequest request) {
        String url = llmConfig.getBaseUrl() + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (llmConfig.getApiKey() != null && !llmConfig.getApiKey().isEmpty()) {
            headers.set("Authorization", "Bearer " + llmConfig.getApiKey());
        }

        HttpEntity<ChatRequest> entity = new HttpEntity<>(request, headers);

        long startTime = System.currentTimeMillis();
        try {
            ResponseEntity<ChatResponse> response =
                    restTemplate.postForEntity(url, entity, ChatResponse.class);

            long duration = System.currentTimeMillis() - startTime;
            log.info("LLM响应成功: duration={}ms, model={}", duration, llmConfig.getModel());

            if (response.getBody() == null) {
                throw new LlmException("LLM返回响应体为空");
            }

            return response.getBody();
        } catch (ResourceAccessException e) {
            log.error("LLM连接异常: url={}, error={}", url, e.getMessage());
            throw e;
        } catch (HttpServerErrorException e) {
            log.error("LLM服务器错误: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        } catch (RestClientException e) {
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
