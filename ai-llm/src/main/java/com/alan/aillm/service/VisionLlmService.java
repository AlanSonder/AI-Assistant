package com.alan.aillm.service;

import com.alan.aicommon.exception.LlmException;
import com.alan.aillm.config.LlmConfig;
import com.alan.aillm.dto.request.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import jakarta.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class VisionLlmService {

    @Autowired
    private LlmConfig llmConfig;

    private WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @PostConstruct
    public void init() {
        // 图片翻译需要更长的超时时间，设置为120秒
        int visionTimeout = (int) Math.max(llmConfig.getTimeout() * 4, 120000);
        webClient = WebClient.builder()
                .baseUrl(llmConfig.getBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "text/event-stream")
                .build();
        log.info("VisionLlmService初始化完成: connectTimeout={}ms, readTimeout={}ms", 
                llmConfig.getTimeout(), visionTimeout);
    }

    /**
     * 图片识别与翻译 - 直接发送Base64图片到LLM
     */
    public String translateImage(byte[] imageBytes, String from, String to, String domain, String style) {
        log.info("开始图片翻译: from={}, to={}, domain={}, style={}, imageSize={} bytes", 
                from, to, domain, style, imageBytes.length);
        
        // 压缩图片以减少传输时间
        byte[] compressedBytes = compressImage(imageBytes);
        log.info("图片压缩完成: 原始大小={} bytes, 压缩后大小={} bytes", imageBytes.length, compressedBytes.length);
        
        String base64Image = Base64.getEncoder().encodeToString(compressedBytes);
        log.info("图片Base64编码成功，长度: {} chars", base64Image.length());
        
        return translateImageBase64(base64Image, from, to, domain, style);
    }

    /**
     * 压缩图片 - 限制最大尺寸和文件大小
     */
    private byte[] compressImage(byte[] imageBytes) {
        try {
            // 如果图片已经很小，直接返回
            if (imageBytes.length < 50 * 1024) { // 小于50KB不压缩
                return imageBytes;
            }

            ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
            BufferedImage originalImage = ImageIO.read(bais);
            
            if (originalImage == null) {
                log.warn("无法读取图片，返回原始数据");
                return imageBytes;
            }

            int originalWidth = originalImage.getWidth();
            int originalHeight = originalImage.getHeight();
            
            // 计算缩放比例 - 最大边不超过1024像素
            int maxDimension = 1024;
            double scale = Math.min(1.0, (double) maxDimension / Math.max(originalWidth, originalHeight));
            
            int newWidth = (int) (originalWidth * scale);
            int newHeight = (int) (originalHeight * scale);
            
            log.info("图片缩放: {}x{} -> {}x{}", originalWidth, originalHeight, newWidth, newHeight);

            // 创建缩放后的图片
            Image scaledImage = originalImage.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH);
            BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = resizedImage.createGraphics();
            g2d.drawImage(scaledImage, 0, 0, null);
            g2d.dispose();

            // 压缩为JPEG格式，质量0.8
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(resizedImage, "jpeg", baos);
            baos.flush();
            byte[] compressed = baos.toByteArray();
            baos.close();

            // 如果压缩后反而更大，返回原始数据
            if (compressed.length >= imageBytes.length) {
                log.info("压缩后大小({})大于原始大小({})，使用原始图片", compressed.length, imageBytes.length);
                return imageBytes;
            }

            return compressed;
        } catch (Exception e) {
            log.error("图片压缩失败，返回原始数据", e);
            return imageBytes;
        }
    }

    /**
     * 图片识别与翻译 - Base64编码图片
     */
    public String translateImageBase64(String base64Image, String from, String to, String domain, String style) {
        String systemPrompt = buildImageTranslatePrompt(from, to, domain, style);
        String userText = "请识别图片中的文字并将其翻译为目标语言。只输出翻译结果，不要添加任何解释。";

        log.info("构建Vision LLM请求...");
        List<Object> messages = List.of(
                ChatRequest.Message.system(systemPrompt),
                ChatRequest.VisionMessage.userWithImage(userText, base64Image)
        );

        return callVisionApi(messages);
    }

    /**
     * 图片内容识别（仅提取文字，不翻译）
     */
    public String extractTextFromImage(byte[] imageBytes) {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        String systemPrompt = "你是一个OCR引擎。请识别图片中的所有文字，保持原有格式和换行。只输出识别到的文字内容，不要添加任何解释。";
        String userText = "请识别这张图片中的所有文字：";

        List<Object> messages = List.of(
                ChatRequest.Message.system(systemPrompt),
                ChatRequest.VisionMessage.userWithImage(userText, base64Image)
        );

        return callVisionApi(messages);
    }

    @Retryable(
            value = {WebClientResponseException.class, RuntimeException.class},
            maxAttemptsExpression = "${ai.llm.max-retries:3}",
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    private String callVisionApi(List<Object> messages) {
        String url = "/chat/completions";
        log.info("调用Vision LLM API: {}{}", llmConfig.getBaseUrl(), url);

        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("model", llmConfig.getModel());
        requestBody.put("messages", messages);
        requestBody.put("temperature", llmConfig.getTemperature());
        requestBody.put("top_p", llmConfig.getTopP());
        requestBody.put("max_tokens", llmConfig.getMaxTokens());
        requestBody.put("stream", true);

        log.info("请求体构建完成: model={}, messages.size={}", 
                llmConfig.getModel(), messages.size());

        long startTime = System.currentTimeMillis();
        try {
            log.info("发送Vision LLM流式请求...");
            String responseBody = webClient.post()
                    .uri(url)
                    .header("Authorization", llmConfig.getApiKey() != null && !llmConfig.getApiKey().isEmpty() 
                            ? "Bearer " + llmConfig.getApiKey() : null)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            long duration = System.currentTimeMillis() - startTime;
            log.info("Vision LLM响应成功: duration={}ms", duration);

            if (responseBody == null || responseBody.isEmpty()) {
                log.error("LLM返回响应体为空");
                throw new LlmException("LLM返回响应体为空");
            }

            return parseStreamResponse(responseBody);
        } catch (WebClientResponseException e) {
            String body = e.getResponseBodyAsString();
            log.error("Vision LLM服务器错误: status={}, body={}", e.getStatusCode(), body);
            throw new LlmException("LLM服务内部错误: " + e.getStatusCode() + ", " + body, e);
        } catch (Exception e) {
            log.error("Vision LLM调用失败: error={}", e.getMessage(), e);
            throw new LlmException("LLM调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用Vision LLM API 返回真正的流式 Flux
     */
    public Flux<String> callVisionApiStream(List<Object> messages) {
        String url = "/chat/completions";
        log.info("调用Vision LLM API (流式Flux): {}{}", llmConfig.getBaseUrl(), url);

        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("model", llmConfig.getModel());
        requestBody.put("messages", messages);
        requestBody.put("temperature", llmConfig.getTemperature());
        requestBody.put("top_p", llmConfig.getTopP());
        requestBody.put("max_tokens", llmConfig.getMaxTokens());
        requestBody.put("stream", true);

        log.info("请求体构建完成: model={}, messages.size={}", 
                llmConfig.getModel(), messages.size());

        long startTime = System.currentTimeMillis();
        log.info("发送Vision LLM流式请求 (Flux)...");
        return webClient.post()
                .uri(url)
                .header("Authorization", llmConfig.getApiKey() != null && !llmConfig.getApiKey().isEmpty() 
                        ? "Bearer " + llmConfig.getApiKey() : null)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnComplete(() -> {
                    long duration = System.currentTimeMillis() - startTime;
                    log.info("Vision LLM流式响应完成: duration={}ms", duration);
                })
                .doOnError(e -> {
                    log.error("Vision LLM流式调用失败: error={}", e.getMessage(), e);
                });
    }

    private String parseStreamResponse(String responseBody) {
        StringBuilder content = new StringBuilder();
        String[] lines = responseBody.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || !line.startsWith("data:")) {
                continue;
            }
            
            String data = line.substring(5).trim();
            if (data.equals("[DONE]")) {
                break;
            }
            
            try {
                JsonNode jsonNode = objectMapper.readTree(data);
                JsonNode choices = jsonNode.get("choices");
                if (choices != null && choices.isArray() && choices.size() > 0) {
                    JsonNode delta = choices.get(0).get("delta");
                    if (delta != null) {
                        JsonNode contentNode = delta.get("content");
                        if (contentNode != null && !contentNode.isNull()) {
                            content.append(contentNode.asText());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("解析流式响应行失败: {}", e.getMessage());
            }
        }
        
        String result = content.toString().trim();
        if (result.isEmpty()) {
            throw new LlmException("LLM返回消息内容为空字符串");
        }
        
        log.info("提取内容成功: 长度={}", result.length());
        return result;
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
}
