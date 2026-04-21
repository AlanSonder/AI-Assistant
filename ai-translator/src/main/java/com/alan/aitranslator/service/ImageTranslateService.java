package com.alan.aitranslator.service;

import com.alan.aicommon.exception.TranslationException;
import com.alan.aiocr.service.OcrException;
import com.alan.aiocr.service.OcrService;
import com.alan.aitranslator.dto.request.TranslateRequest;
import com.alan.aitranslator.dto.response.ImageTranslateResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class ImageTranslateService {

    @Autowired
    private OcrService ocrService;

    @Autowired
    private TranslateService translateService;

    public ImageTranslateResponse translateImage(MultipartFile imageFile, String from, String to) {
        long startTime = System.currentTimeMillis();

        validateImage(imageFile);

        log.info("图片翻译请求: from={}, to={}, size={}", from, to, imageFile.getSize());

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
