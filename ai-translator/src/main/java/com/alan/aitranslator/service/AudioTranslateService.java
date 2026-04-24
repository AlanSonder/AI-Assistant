package com.alan.aitranslator.service;

import com.alan.aicommon.exception.TranslationException;
import com.alan.aillm.service.LlmService;
import com.alan.aitranslator.dto.request.TranslateRequest;
import com.alan.aitranslator.dto.response.AudioTranslateResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class AudioTranslateService {

    private final AsrService asrService;

    private final TranslateService translateService;

    private final LlmService llmService;

    public AudioTranslateService(AsrService asrService, TranslateService translateService, LlmService llmService) {
        this.asrService = asrService;
        this.translateService = translateService;
        this.llmService = llmService;
    }

    public AudioTranslateResponse translateAudio(MultipartFile audioFile, String from, String to) {
        long startTime = System.currentTimeMillis();

        validateAudio(audioFile);

        log.info("语音翻译请求: from={}, to={}, size={}", from, to, audioFile.getSize());

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("audio_", getFileExtension(audioFile.getOriginalFilename()));
            audioFile.transferTo(tempFile.toFile());

            long audioDuration = getAudioDurationMs(tempFile.toFile());

            String recognizedText;
            try {
                recognizedText = asrService.recognize(tempFile.toFile(), from);
            } catch (Exception e) {
                log.error("语音识别失败", e);
                throw new TranslationException("语音识别失败: " + e.getMessage());
            }

            if (recognizedText == null || recognizedText.trim().isEmpty()) {
                throw new TranslationException("语音中未识别到任何文字内容");
            }

            log.info("语音识别成功: 文字长度={}", recognizedText.length());

            TranslateRequest translateRequest = new TranslateRequest();
            translateRequest.setText(recognizedText);
            translateRequest.setFrom(from);
            translateRequest.setTo(to);

            var translateResult = translateService.translate(translateRequest);

            long duration = System.currentTimeMillis() - startTime;

            return AudioTranslateResponse.builder()
                    .recognizedText(recognizedText)
                    .translatedText(translateResult.getTranslatedText())
                    .from(from)
                    .to(to)
                    .durationMs(duration)
                    .audioDurationMs(audioDuration)
                    .build();
        } catch (TranslationException e) {
            throw e;
        } catch (IOException e) {
            log.error("处理音频文件失败", e);
            throw new TranslationException("处理音频文件失败: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("清理临时文件失败: {}", tempFile);
                }
            }
        }
    }

    public SseEmitter translateAudioStream(MultipartFile audioFile, String from, String to, ObjectMapper objectMapper) {
        long startTime = System.currentTimeMillis();
        SseEmitter emitter = new SseEmitter(120000L);

        validateAudio(audioFile);
        log.info("流式语音翻译请求: from={}, to={}, size={}", from, to, audioFile.getSize());

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("audio_stream_", getFileExtension(audioFile.getOriginalFilename()));
            audioFile.transferTo(tempFile.toFile());

            long audioDuration = getAudioDurationMs(tempFile.toFile());
            final long finalAudioDuration = audioDuration;
            final Path finalTempFile = tempFile;

            CompletableFuture.runAsync(() -> {
                try {
                    long asrStartTime = System.currentTimeMillis();
                    String recognizedText;
                    try {
                        recognizedText = asrService.recognize(finalTempFile.toFile(), from);
                    } catch (Exception e) {
                        log.error("语音识别失败", e);
                        emitter.completeWithError(new TranslationException("语音识别失败: " + e.getMessage()));
                        return;
                    }
                    long asrDuration = System.currentTimeMillis() - asrStartTime;
                    log.info("ASR完成: duration={}ms, 文字长度={}", asrDuration, recognizedText != null ? recognizedText.length() : 0);

                    if (recognizedText == null || recognizedText.trim().isEmpty()) {
                        emitter.completeWithError(new TranslationException("语音中未识别到任何文字内容"));
                        return;
                    }

                    Map<String, Object> asrData = new HashMap<>();
                    asrData.put("type", "asr_result");
                    asrData.put("recognizedText", recognizedText);
                    asrData.put("asrDuration", asrDuration);
                    emitter.send(SseEmitter.event().name("asr").data(objectMapper.writeValueAsString(asrData)));

                    String systemPrompt = String.format("翻译：%s -> %s\n只输出结果，保持原格式。", from.toLowerCase(), to.toLowerCase());
                    String userPrompt = String.format("<text>\n%s\n</text>", recognizedText.trim());

                    log.info("开始流式翻译...");
                    
                    StringBuilder fullTranslation = new StringBuilder();

                    Flux<String> translationFlux = llmService.chatStream(systemPrompt, userPrompt);
                    
                    translationFlux
                        .doOnNext(chunk -> {
                            fullTranslation.append(chunk);
                            try {
                                Map<String, Object> transData = new HashMap<>();
                                transData.put("text", chunk);
                                transData.put("done", false);
                                emitter.send(SseEmitter.event().name("translation").data(objectMapper.writeValueAsString(transData)));
                            } catch (IOException e) {
                                log.warn("发送SSE失败: {}", e.getMessage());
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                long totalDuration = System.currentTimeMillis() - startTime;
                                Map<String, Object> doneData = new HashMap<>();
                                doneData.put("type", "done");
                                doneData.put("recognizedText", recognizedText);
                                doneData.put("translatedText", fullTranslation.toString());
                                doneData.put("totalDuration", totalDuration);
                                doneData.put("audioDuration", finalAudioDuration);
                                emitter.send(SseEmitter.event().name("done").data(objectMapper.writeValueAsString(doneData)));
                                emitter.complete();
                            } catch (IOException e) {
                                log.warn("发送完成信号失败: {}", e.getMessage());
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnError(e -> {
                            log.error("流式翻译失败", e);
                            emitter.completeWithError(e);
                        })
                        .subscribe();

                } catch (Exception e) {
                    log.error("流式语音翻译异常", e);
                    emitter.completeWithError(e);
                } finally {
                    try {
                        Files.deleteIfExists(finalTempFile);
                    } catch (IOException e) {
                        log.warn("清理临时文件失败：{}", finalTempFile);
                    }
                }
            });
        } catch (IOException e) {
            log.error("处理音频文件失败", e);
            emitter.completeWithError(new TranslationException("处理音频文件失败: " + e.getMessage()));
        }

        return emitter;
    }

    private void validateAudio(MultipartFile audioFile) {
        if (audioFile == null || audioFile.isEmpty()) {
            throw new TranslationException("请上传音频文件");
        }

        String contentType = audioFile.getContentType();
        if (contentType == null || !contentType.startsWith("audio/")) {
            String filename = audioFile.getOriginalFilename();
            boolean isAudioFile = filename != null && (
                    filename.toLowerCase().endsWith(".mp3") ||
                            filename.toLowerCase().endsWith(".wav") ||
                            filename.toLowerCase().endsWith(".m4a") ||
                            filename.toLowerCase().endsWith(".ogg") ||
                            filename.toLowerCase().endsWith(".flac")
            );
            if (!isAudioFile) {
                throw new TranslationException("仅支持音频文件格式(MP3/WAV/M4A/OGG/FLAC)");
            }
        }

        if (audioFile.getSize() > 50 * 1024 * 1024) {
            throw new TranslationException("音频文件大小超过限制(50MB)");
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return ".tmp";
        }
        return filename.substring(filename.lastIndexOf("."));
    }

    private long getAudioDurationMs(File audioFile) {
        try {
            javax.sound.sampled.AudioInputStream audioStream = javax.sound.sampled.AudioSystem.getAudioInputStream(audioFile);
            javax.sound.sampled.AudioFormat format = audioStream.getFormat();
            long frames = audioStream.getFrameLength();
            double durationSeconds = (double) frames / format.getFrameRate();
            audioStream.close();
            return (long) (durationSeconds * 1000);
        } catch (Exception e) {
            log.warn("无法获取音频时长: {}", e.getMessage());
            return 0;
        }
    }
}
