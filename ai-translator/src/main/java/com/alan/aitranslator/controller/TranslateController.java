package com.alan.aitranslator.controller;

import com.alan.aicommon.dto.ApiResponse;
import com.alan.aillm.service.LlmService;
import com.alan.aitranslator.dto.request.TranslateRequest;
import com.alan.aitranslator.dto.response.AudioTranslateResponse;
import com.alan.aitranslator.dto.response.ImageTranslateResponse;
import com.alan.aitranslator.dto.response.TranslateResponse;
import com.alan.aitranslator.service.AudioTranslateService;
import com.alan.aitranslator.service.ImageTranslateService;
import com.alan.aitranslator.service.TranslateService;
import com.alan.aitranslator.util.TextPreprocessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/v1/translate")
public class TranslateController {

    @Autowired
    private TranslateService translateService;

    @Autowired
    private ImageTranslateService imageTranslateService;

    @Autowired
    private AudioTranslateService audioTranslateService;

    @Autowired
    private LlmService llmService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/text")
    public ApiResponse<TranslateResponse> translateText(@Valid @RequestBody TranslateRequest request) {
        TranslateResponse response = translateService.translate(request);
        return ApiResponse.success(response);
    }

    @PostMapping(value = "/text/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter translateTextStream(@Valid @RequestBody TranslateRequest request) {
        SseEmitter emitter = new SseEmitter(120000L);
        
        CompletableFuture.runAsync(() -> {
            try {
                String text = TextPreprocessor.preprocess(request.getText());
                String from = request.getFrom().toLowerCase();
                String to = request.getTo().toLowerCase();
                String domain = request.getDomain() != null ? request.getDomain() : "general";
                String style = request.getStyle() != null ? request.getStyle() : "neutral";

                // 构建系统提示
                String systemPrompt = String.format("翻译：%s -> %s\n只输出结果，保持原格式。", from, to);
                String userPrompt = buildUserPrompt(text, request.getContextText());

                // 使用 LLM 服务获取流式响应
                String result = llmService.chat(systemPrompt, userPrompt);
                
                // 分块发送结果
                int chunkSize = 10;
                for (int i = 0; i < result.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, result.length());
                    String chunk = result.substring(i, end);
                    
                    Map<String, Object> data = new HashMap<>();
                    data.put("text", chunk);
                    data.put("done", false);
                    emitter.send(SseEmitter.event().name("message").data(objectMapper.writeValueAsString(data)));
                    
                    Thread.sleep(50);
                }

                // 发送完成信号
                Map<String, Object> doneData = new HashMap<>();
                doneData.put("text", "");
                doneData.put("done", true);
                emitter.send(SseEmitter.event().name("done").data(objectMapper.writeValueAsString(doneData)));
                
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        
        return emitter;
    }

    @PostMapping(value = "/image/stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> translateImageStream(
            @RequestParam("file") MultipartFile file,
            @RequestParam("from") String from,
            @RequestParam("to") String to) {
        return imageTranslateService.translateImageStream(file, from, to);
    }

    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImageTranslateResponse> translateImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("from") String from,
            @RequestParam("to") String to) {
        ImageTranslateResponse response = imageTranslateService.translateImage(file, from, to);
        return ApiResponse.success(response);
    }

    @PostMapping(value = "/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AudioTranslateResponse> translateAudio(
            @RequestParam("file") MultipartFile file,
            @RequestParam("from") String from,
            @RequestParam("to") String to) {
        AudioTranslateResponse response = audioTranslateService.translateAudio(file, from, to);
        return ApiResponse.success(response);
    }

    @PostMapping(value = "/audio/stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter translateAudioStream(
            @RequestParam("file") MultipartFile file,
            @RequestParam("from") String from,
            @RequestParam("to") String to) {
        return audioTranslateService.translateAudioStream(file, from, to, objectMapper);
    }

    private String buildUserPrompt(String text, String context) {
        if (context != null && !context.trim().isEmpty()) {
            return String.format("""
                    <context>
                    %s
                    </context>
                    
                    <text>
                    %s
                    </text>""", context.trim(), text.trim());
        }
        
        return String.format("""
                <text>
                %s
                </text>""", text.trim());
    }
}
