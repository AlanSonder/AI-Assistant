package com.alan.aitranslator.service;

import com.alan.aicommon.exception.TranslationException;
import com.alan.aillm.service.VisionLlmService;
import com.alan.aiocr.service.OcrException;
import com.alan.aiocr.service.OcrService;
import com.alan.aitranslator.config.TranslatorConfig;
import com.alan.aitranslator.dto.request.TranslateRequest;
import com.alan.aitranslator.dto.response.ImageTranslateResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
@Service
public class ImageTranslateService {

    @Autowired
    private VisionLlmService visionLlmService;

    @Autowired
    private OcrService ocrService;

    @Autowired
    private TranslateService translateService;

    @Autowired
    private TranslatorConfig translatorConfig;

    /**
     * 图片翻译 - 主要使用Vision LLM方案，OCR作为备用
     */
    public ImageTranslateResponse translateImage(MultipartFile imageFile, String from, String to) {
        long startTime = System.currentTimeMillis();

        validateImage(imageFile);

        log.info("图片翻译请求: from={}, to={}, size={}, 使用Vision LLM方案", from, to, imageFile.getSize());

        try {
            // 主方案：直接发送Base64图片到LLM进行识别和翻译
            byte[] imageBytes = imageFile.getBytes();
            String domain = translatorConfig.getDefaultDomain();
            String style = translatorConfig.getDefaultStyle();

            String translatedText = visionLlmService.translateImage(imageBytes, from, to, domain, style);

            if (translatedText == null || translatedText.trim().isEmpty()) {
                throw new TranslationException("图片翻译结果为空");
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Vision LLM图片翻译成功: duration={}ms", duration);

            return ImageTranslateResponse.builder()
                    .extractedText("[由Vision LLM直接处理]")
                    .translatedText(translatedText)
                    .from(from)
                    .to(to)
                    .durationMs(duration)
                    .build();

        } catch (TranslationException e) {
            log.error("Vision LLM图片翻译失败，尝试OCR备用方案", e);
            return translateImageWithOcr(imageFile, from, to, startTime);
        } catch (IOException e) {
            log.error("读取图片文件失败", e);
            throw new TranslationException("读取图片文件失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("图片翻译异常，尝试OCR备用方案", e);
            return translateImageWithOcr(imageFile, from, to, startTime);
        }
    }

    /**
     * OCR备用方案 - 保留原有OCR+翻译流程
     */
    public ImageTranslateResponse translateImageWithOcr(MultipartFile imageFile, String from, String to, long startTime) {
        log.info("使用OCR备用方案进行图片翻译");

        String extractedText;
        try {
            extractedText = ocrService.extractText(imageFile);
        } catch (OcrException e) {
            log.error("图片OCR提取失败", e);
            throw new TranslationException("图片文字识别失败: " + e.getMessage());
        }

        if (extractedText == null || extractedText.trim().isEmpty()) {
            throw new TranslationException("图片中未识别到任何文字内容");
        }

        log.info("OCR提取成功: 文字长度={}", extractedText.length());

        TranslateRequest translateRequest = new TranslateRequest();
        translateRequest.setText(extractedText);
        translateRequest.setFrom(from);
        translateRequest.setTo(to);

        var translateResult = translateService.translate(translateRequest);

        long duration = System.currentTimeMillis() - startTime;

        return ImageTranslateResponse.builder()
                .extractedText(extractedText)
                .translatedText(translateResult.getTranslatedText())
                .from(from)
                .to(to)
                .durationMs(duration)
                .build();
    }

    private void validateImage(MultipartFile imageFile) {
        if (imageFile == null || imageFile.isEmpty()) {
            throw new TranslationException("请上传图片文件");
        }

        String contentType = imageFile.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new TranslationException("仅支持图片文件格式(JPG/PNG/GIF/WEBP)");
        }

        if (imageFile.getSize() > 50 * 1024 * 1024) {
            throw new TranslationException("图片文件大小超过限制(50MB)");
        }
    }
}
