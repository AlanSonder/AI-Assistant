package com.alan.aitranslator.service;

import com.alan.aitranslator.config.TranslatorConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Service
public class AsrService {

    private final RestTemplate restTemplate;

    private final String asrApiUrl;

    public AsrService(TranslatorConfig translatorConfig) {
        this.restTemplate = new RestTemplate();
        this.asrApiUrl = "http://localhost:1234/v1/audio/transcriptions";
        log.info("ASR服务初始化: API地址={}", asrApiUrl);
    }

    public String recognize(File audioFile, String language) {
        log.info("语音识别: file={}, size={}", audioFile.getName(), audioFile.length());

        String url = asrApiUrl;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", createFileSystemResource(audioFile));
        body.add("model", "whisper-1");
        if (language != null && !language.isEmpty()) {
            body.add("language", language);
        }
        body.add("response_format", "text");

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);

            if (response.getBody() == null || response.getBody().trim().isEmpty()) {
                throw new AsrException("语音识别返回结果为空");
            }

            String result = response.getBody().trim();
            log.info("语音识别完成: 文字长度={}", result.length());
            return result;
        } catch (RestClientException e) {
            log.error("语音识别API调用失败: {}", e.getMessage());
            throw new AsrException("语音识别服务不可用: " + e.getMessage());
        }
    }

    private org.springframework.core.io.FileSystemResource createFileSystemResource(File file) {
        return new org.springframework.core.io.FileSystemResource(file);
    }
}
