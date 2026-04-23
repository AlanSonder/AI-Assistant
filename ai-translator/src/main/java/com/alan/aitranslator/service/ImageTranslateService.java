package com.alan.aitranslator.service;

import com.alan.aicommon.exception.TranslationException;
import com.alan.aillm.dto.request.ChatRequest;
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
import java.util.Base64;
import java.util.List;

import reactor.core.publisher.Flux;

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
     * 图片翻译 - 仅使用Vision LLM方案，不回退到OCR
     */
    public ImageTranslateResponse translateImage(MultipartFile imageFile, String from, String to) {
        long startTime = System.currentTimeMillis();

        validateImage(imageFile);

        log.info("图片翻译请求: from={}, to={}, size={}, 使用Vision LLM方案", from, to, imageFile.getSize());

        try {
            // 主方案：直接发送Base64图片到LLM进行识别和翻译
            byte[] imageBytes = imageFile.getBytes();
            log.info("图片读取成功，大小: {} bytes", imageBytes.length);
            
            String domain = translatorConfig.getDefaultDomain();
            String style = translatorConfig.getDefaultStyle();
            log.info("使用配置: domain={}, style={}", domain, style);

            log.info("开始调用Vision LLM服务...");
            String translatedText = visionLlmService.translateImage(imageBytes, from, to, domain, style);
            log.info("Vision LLM返回结果: 长度={}", translatedText != null ? translatedText.length() : 0);

            if (translatedText == null || translatedText.trim().isEmpty()) {
                throw new TranslationException("图片翻译结果为空");
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Vision LLM图片翻译成功: duration={}ms", duration);

            return ImageTranslateResponse.builder()
                    .translatedText(translatedText)
                    .from(from)
                    .to(to)
                    .durationMs(duration)
                    .build();

        } catch (IOException e) {
            log.error("读取图片文件失败", e);
            throw new TranslationException("读取图片文件失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("Vision LLM图片翻译失败", e);
            throw new TranslationException("Vision LLM图片翻译失败: " + e.getMessage(), e);
        }
    }

    /**
     * 图片翻译（流式）- 返回Flux
     */
    public Flux<String> translateImageStream(MultipartFile imageFile, String from, String to) {
        validateImage(imageFile);

        log.info("图片翻译请求（流式）: from={}, to={}, size={}", from, to, imageFile.getSize());

        try {
            byte[] imageBytes = imageFile.getBytes();
            log.info("图片读取成功，大小: {} bytes", imageBytes.length);
            
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            log.info("图片Base64编码成功，长度: {} chars", base64Image.length());
            
            String domain = translatorConfig.getDefaultDomain();
            String style = translatorConfig.getDefaultStyle();
            log.info("使用配置: domain={}, style={}", domain, style);

            log.info("开始调用Vision LLM服务（流式）...");
            
            String systemPrompt = buildImageTranslatePrompt(from, to, domain, style);
            String userText = "请识别图片中的文字并将其翻译为目标语言。只输出翻译结果，不要添加任何解释。";
            
            List<Object> messages = List.of(
                    ChatRequest.Message.system(systemPrompt),
                    ChatRequest.VisionMessage.userWithImage(userText, base64Image)
            );

            return visionLlmService.callVisionApiStream(messages);
        } catch (IOException e) {
            log.error("读取图片文件失败", e);
            return Flux.error(new TranslationException("读取图片文件失败: " + e.getMessage()));
        }
    }

    private String buildImageTranslatePrompt(String from, String to, String domain, String style) {
        String domainDesc = switch (domain.toLowerCase()) {
            case "tech" -> "技术领域（计算机、软件工程相关术语）";
            case "medical" -> "医学领域（临床、解剖、药理等术语）";
            case "legal" -> "法律领域（合同、法规、诉讼文书）";
            case "business" -> "商业领域（财务、管理、市场术语）";
            case "literary" -> "文学领域（小说、诗歌、散文）";
            default -> "通用领域";
        };

        String styleDesc = switch (style.toLowerCase()) {
            case "formal" -> "正式、书面语风格";
            case "casual" -> "口语化、自然风格";
            case "academic" -> "学术、严谨风格";
            default -> "中性风格，保持原文语气";
        };

        return String.format("""
                你是专业的图片翻译引擎，能够识别图片中的文字并进行高质量翻译。

                任务：
                1. 识别图片中的所有文字内容
                2. 将识别到的文字从%s翻译为%s
                3. 保持原文的格式和排版

                领域: %s - %s
                风格: %s

                规则:
                1. 只输出翻译后的结果
                2. 保持原文的格式、换行和标点风格
                3. 专业术语使用领域标准译法
                4. 保留专有名词（人名、地名、品牌名）原文
                5. 代码片段和技术术语不要翻译
                6. 如遇无法翻译的内容，原样返回
                7. 输出必须完整，不能截断
                """, from, to, domain, domainDesc, styleDesc);
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
