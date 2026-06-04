package com.alan.aitranslator.service;

import com.alan.aicommon.exception.TranslationException;
import com.alan.aillm.config.LlmConfig;
import com.alan.aillm.service.LlmService;
import com.alan.aitranslator.config.TranslatorConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import reactor.core.publisher.Flux;

@Slf4j
@Service
public class ImageTranslateService {

    @Autowired
    private LlmService llmService;

    @Autowired
    private LlmConfig llmConfig;

    @Autowired
    private TranslatorConfig translatorConfig;

    /**
     * 图片翻译（流式）- 返回Flux
     */
    public Flux<String> translateImageStream(MultipartFile imageFile, String from, String to) {
        validateImage(imageFile);

        log.info("图片翻译请求（流式）: from={}, to={}, size={}", from, to, imageFile.getSize());

        try {
            byte[] imageBytes = imageFile.getBytes();
            log.info("图片读取成功，大小: {} bytes", imageBytes.length);

            String domain = translatorConfig.getDefaultDomain();
            String style = translatorConfig.getDefaultStyle();
            log.info("使用配置: domain={}, style={}", domain, style);

            // 当前 Provider 不支持 Vision，无法进行图片翻译
            if (!llmConfig.isVisionEnabled()) {
                throw new TranslationException(
                        "当前LLM提供商(" + llmConfig.getActiveProvider() + ")不支持Vision，无法进行图片翻译。请切换到支持Vision的提供商。");
            }

            log.info("开始调用Vision LLM服务（流式）...");
            return llmService.translateImageStream(imageBytes, from, to, domain, style);
        } catch (IOException e) {
            log.error("读取图片文件失败", e);
            return Flux.error(new TranslationException("读取图片文件失败: " + e.getMessage()));
        }
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
