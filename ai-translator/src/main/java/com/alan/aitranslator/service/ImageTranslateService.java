package com.alan.aitranslator.service;

import com.alan.aicommon.exception.TranslationException;
import com.alan.aillm.config.LlmConfig;
import com.alan.aillm.service.LlmService;
import com.alan.aiocr.service.OcrService;
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
    private OcrService ocrService;

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

            // 当前 Provider 不支持 Vision → Tesseract OCR + 当前Provider翻译
            if (!llmConfig.isVisionEnabled()) {
                log.info("当前Provider({})不支持Vision，走Tesseract OCR + {}翻译流程",
                        llmConfig.getActiveProvider(), llmConfig.getActiveProvider());
                return translateViaOcr(imageFile, from, to);
            }

            log.info("开始调用Vision LLM服务（流式）...");
            return llmService.translateImageStream(imageBytes, from, to, domain, style);
        } catch (IOException e) {
            log.error("读取图片文件失败", e);
            return Flux.error(new TranslationException("读取图片文件失败: " + e.getMessage()));
        }
    }

    /**
     * Tesseract OCR + 当前Provider纯文本翻译（用于不支持 Vision 的 Provider，如 DeepSeek）
     */
    private Flux<String> translateViaOcr(MultipartFile imageFile, String from, String to) {
        return Flux.defer(() -> {
            try {
                // Step 1: Tesseract OCR 提取文字
                log.info("Step 1: Tesseract OCR 提取文字(from={})...", from);
                String ocrLang = OcrService.toOcrLang(from);
                String ocrText = ocrService.extractText(imageFile, ocrLang);
                if (ocrText == null || ocrText.isEmpty()) {
                    log.warn("Tesseract OCR未识别到文字");
                    return Flux.just("{\"original\":\"\",\"translation\":\"(未识别到文字)\"}");
                }
                log.info("Tesseract OCR完成，原文长度: {} 字符", ocrText.length());
                // 打印识别结果（超过200字符则截断）
                if (ocrText.length() <= 200) {
                    log.info("OCR识别内容: {}", ocrText);
                } else {
                    log.info("OCR识别内容(截断): {}...", ocrText.substring(0, 200));
                }

                // Step 2: 用当前 Provider 翻译
                log.info("Step 2: {} 文本翻译...", llmConfig.getActiveProvider());
                String systemPrompt = String.format("""
                        请将以下文字翻译为%s。以JSON格式输出，包含两个字段："original"（原文）和"translation"（译文）。
                        """, resolveLangName(to));

                return llmService.chatStream(null, null, systemPrompt, ocrText);

            } catch (Exception e) {
                log.error("OCR+翻译流程失败", e);
                return Flux.error(new TranslationException("翻译失败: " + e.getMessage()));
            }
        });
    }

    private static String resolveLangName(String lang) {
        return switch (lang.toLowerCase()) {
            case "auto" -> "自动检测到的文字";
            case "chinese" -> "中文";
            case "english" -> "英文";
            case "japanese" -> "日文";
            case "korean" -> "韩文";
            case "french" -> "法文";
            case "german" -> "德文";
            case "spanish" -> "西班牙文";
            case "russian" -> "俄文";
            default -> lang;
        };
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
