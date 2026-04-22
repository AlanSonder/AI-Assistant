package com.alan.aillm.service;

import com.alan.aicommon.exception.LlmException;
import com.alan.aillm.config.LlmConfig;
import com.alan.aillm.dto.request.ChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class LlmStreamService {

    @Autowired
    private LlmConfig llmConfig;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    /**
     * 流式对话，通过 SSE 推送结果
     */
    public SseEmitter chatStream(String systemPrompt, String userPrompt) {
        List<ChatRequest.Message> messages = List.of(
                ChatRequest.Message.system(systemPrompt),
                ChatRequest.Message.user(userPrompt)
        );
        return chatStream(messages);
    }

    /**
     * 流式对话，通过 SSE 推送结果
     */
    public SseEmitter chatStream(List<ChatRequest.Message> messages) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        executorService.execute(() -> {
            HttpURLConnection connection = null;
            try {
                ChatRequest request = buildStreamRequest(messages);
                String jsonBody = objectMapper.writeValueAsString(request);

                URL url = new URL(llmConfig.getBaseUrl() + "/chat/completions");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "text/event-stream");
                if (llmConfig.getApiKey() != null && !llmConfig.getApiKey().isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + llmConfig.getApiKey());
                }
                connection.setDoOutput(true);
                connection.setDoInput(true);
                connection.setConnectTimeout((int) llmConfig.getTimeout());
                connection.setReadTimeout((int) llmConfig.getTimeout());

                // 发送请求体
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    throw new LlmException("LLM流式请求失败，HTTP状态码: " + responseCode);
                }

                // 读取 SSE 流
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            if ("[DONE]".equals(data)) {
                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data("{\"finish\": true}"));
                                break;
                            }
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(data));
                        }
                    }
                }

                emitter.complete();
            } catch (Exception e) {
                log.error("流式请求异常", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"error\": \"" + e.getMessage() + "\"}"));
                    emitter.completeWithError(e);
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });

        // 连接断开处理
        emitter.onCompletion(() -> log.debug("SSE连接完成"));
        emitter.onTimeout(() -> log.warn("SSE连接超时"));
        emitter.onError((e) -> log.error("SSE连接异常", e));

        return emitter;
    }

    private ChatRequest buildStreamRequest(List<ChatRequest.Message> messages) {
        ChatRequest request = new ChatRequest();
        request.setModel(llmConfig.getModel());
        request.setMessages(messages);
        request.setTemperature(llmConfig.getTemperature());
        request.setTopP(llmConfig.getTopP());
        request.setMaxTokens(llmConfig.getMaxTokens());
        request.setStream(true); // 关键：启用流式输出
        return request;
    }
}
