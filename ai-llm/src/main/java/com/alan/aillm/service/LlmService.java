package com.alan.aillm.service;

import com.alan.aicommon.exception.LlmException;
import com.alan.aillm.config.LlmConfig;
import com.alan.aillm.dto.request.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

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
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    // ==========================
    // ✅ 同步调用（非流式）
    // ==========================
    public String chat(String systemPrompt, String userPrompt) {
        List<ChatRequest.Message> messages = new ArrayList<>();
        messages.add(ChatRequest.Message.system(systemPrompt));
        messages.add(ChatRequest.Message.user(userPrompt));
        return chat(messages);
    }

    public String chat(List<ChatRequest.Message> messages) {
        ChatRequest request = buildRequest(messages, false); // ❗关闭 stream

        try {
            String response = webClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", buildAuth())
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseNormalResponse(response);

        } catch (Exception e) {
            throw new LlmException("LLM调用失败: " + e.getMessage(), e);
        }
    }

    private String parseNormalResponse(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            return json.get("choices").get(0).get("message").get("content").asText();
        } catch (Exception e) {
            throw new LlmException("解析响应失败", e);
        }
    }

    // ==========================
    // ✅ 流式调用（真正流）
    // ==========================
    public Flux<String> chatStream(String systemPrompt, String userPrompt) {
        List<ChatRequest.Message> messages = new ArrayList<>();
        messages.add(ChatRequest.Message.system(systemPrompt));
        messages.add(ChatRequest.Message.user(userPrompt));
        return chatStream(messages);
    }

    public Flux<String> chatStream(List<ChatRequest.Message> messages) {
        ChatRequest request = buildRequest(messages, true);

        return webClient.post()
                .uri("/chat/completions")
                .header("Authorization", buildAuth())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)

                // 拆行
                .flatMap(chunk -> Flux.fromArray(chunk.split("\n")))

                // 过滤
                .map(String::trim)
                .filter(line -> line.startsWith("data:"))

                // 去掉前缀
                .map(line -> line.substring(5).trim())
                .filter(data -> !data.equals("[DONE]"))

                // 解析 JSON
                .flatMap(this::parseStreamLine)

                // 防止异常中断流
                .onErrorResume(e -> {
                    log.error("流式解析异常", e);
                    return Flux.empty();
                });
    }

    private Flux<String> parseStreamLine(String data) {
        try {
            JsonNode json = objectMapper.readTree(data);
            JsonNode delta = json.get("choices").get(0).get("delta");

            if (delta != null) {

                // content
                JsonNode content = delta.get("content");
                if (content != null && !content.isNull()) {
                    return Flux.just(content.asText());
                }

                // reasoning（可选）
                JsonNode reasoning = delta.get("reasoning");
                if (reasoning != null && !reasoning.isNull()) {
                    return Flux.just(reasoning.asText());
                }
            }

        } catch (Exception e) {
            log.warn("解析失败: {}", data);
        }
        return Flux.empty();
    }

    // ==========================
    // 工具方法
    // ==========================
    private ChatRequest buildRequest(List<ChatRequest.Message> messages, boolean stream) {
        ChatRequest request = new ChatRequest();
        request.setModel(llmConfig.getModel());
        request.setMessages(messages);
        request.setTemperature(llmConfig.getTemperature());
        request.setTopP(llmConfig.getTopP());
        request.setMaxTokens(llmConfig.getMaxTokens());
        request.setStream(stream);
        return request;
    }

    private String buildAuth() {
        if (llmConfig.getApiKey() == null || llmConfig.getApiKey().isEmpty()) {
            return null;
        }
        return "Bearer " + llmConfig.getApiKey();
    }
}