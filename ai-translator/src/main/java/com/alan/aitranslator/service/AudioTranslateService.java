package com.alan.aitranslator.service;

import com.alan.aicommon.exception.TranslationException;
import com.alan.aitranslator.dto.request.TranslateRequest;
import com.alan.aitranslator.dto.response.AudioTranslateResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class AudioTranslateService {

    private final AsrService asrService;

    private final TranslateService translateService;

    public AudioTranslateService(AsrService asrService, TranslateService translateService) {
        this.asrService = asrService;
        this.translateService = translateService;
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
