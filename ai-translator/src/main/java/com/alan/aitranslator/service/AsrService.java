package com.alan.aitranslator.service;

import com.alan.aillm.config.LlmConfig;
import com.alan.aitranslator.config.TranslatorConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Service
public class AsrService {

    private final WebClient webClient;

    private final String asrApiUrl;

    public AsrService(LlmConfig llmConfig, TranslatorConfig translatorConfig) {
        String baseUrl = llmConfig.getBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalArgumentException("LLM baseUrl configuration is required");
        }
        
        // 提取baseUrl的基础部分（不含/v1）
        String webClientBaseUrl = baseUrl.replace("/v1", "");
        
        this.webClient = WebClient.builder()
                .baseUrl(webClientBaseUrl)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();
        
        this.asrApiUrl = baseUrl + "/audio/transcriptions";
        log.info("ASR服务初始化: API地址={}", asrApiUrl);
    }

    public String recognize(File audioFile, String language) {
        log.info("语音识别: file={}, size={}", audioFile.getName(), audioFile.length());

        String url = "/v1/audio/transcriptions";

        try {
            // 读取文件并进行base64编码
            byte[] fileContent = Files.readAllBytes(audioFile.toPath());
            String base64Audio = Base64.getEncoder().encodeToString(fileContent);

            // 构建JSON请求体
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("file", base64Audio);
            body.put("model", "whisper-1");
            if (language != null && !language.isEmpty()) {
                body.put("language", language);
            }
            body.put("response_format", "text");

            String response = webClient.post()
                    .uri(url)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response == null || response.trim().isEmpty()) {
                throw new AsrException("语音识别返回结果为空");
            }

            String result = response.trim();
            log.info("语音识别完成: 文字长度={}", result.length());
            return result;
        } catch (WebClientResponseException e) {
            log.error("语音识别API调用失败: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new AsrException("语音识别服务不可用: " + e.getMessage());
        } catch (IOException e) {
            log.error("读取音频文件失败: {}", e.getMessage());
            throw new AsrException("读取音频文件失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("语音识别失败: {}", e.getMessage(), e);
            throw new AsrException("语音识别失败: " + e.getMessage());
        }
    }
}
