package com.alan.aillm.service;

import com.alan.aicommon.exception.LlmException;
import com.alan.aillm.config.LlmConfig;
import com.alan.aillm.config.LlmConfig.ProviderConfig;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一的 LLM 服务类（多Provider支持）
 *
 * 提供以下能力：
 * 1. 纯文本对话（同步/流式）
 * 2. 图片内容理解（Vision）
 * 3. 图片翻译（OCR + 翻译）
 * 4. 图片内容提取（纯 OCR）
 * 5. 多提供商灵活切换（本地部署 / DeepSeek 等云端API）
 *
 * 所有请求均调用 /chat/completions 接口（OpenAI 兼容格式），
 * 通过 active-provider 配置默认提供商，请求可覆盖。
 */
@Slf4j
@Service
public class LlmService {

    @Autowired
    private LlmConfig llmConfig;

    /** 每个提供商一个 WebClient 实例 */
    private final Map<String, WebClient> webClients = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        refreshClients();
        log.info("LlmService初始化完成: activeProvider={}, providers={}",
                llmConfig.getActiveProvider(), llmConfig.getProviders().keySet());
    }

    /**
     * 刷新所有 WebClient（配置变更后调用）
     */
    public void refreshClients() {
        llmConfig.getProviders().forEach((name, config) -> {
            WebClient client = WebClient.builder()
                    .baseUrl(config.getBaseUrl())
                    .defaultHeader("Content-Type", "application/json")
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                    .build();
            webClients.put(name, client);
            log.info("LLM WebClient 已创建: provider={}, baseUrl={}, models={}",
                    name, config.getBaseUrl(), config.getModels());
        });
    }

    // ========================================
    // 提供商 / 模型 查询
    // ========================================

    /** 获取所有可用提供商及其模型列表 */
    public Map<String, Object> getProvidersInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("activeProvider", llmConfig.getActiveProvider());
        info.put("defaultModel", llmConfig.getDefaultModel());
        info.put("thinkingEnabled", llmConfig.isThinkingEnabled());

        Map<String, Object> providersInfo = new LinkedHashMap<>();
        llmConfig.getProviders().forEach((name, config) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("baseUrl", config.getBaseUrl());
            p.put("models", config.getModels());
            providersInfo.put(name, p);
        });
        info.put("providers", providersInfo);
        return info;
    }

    /** 运行时切换激活的提供商 */
    public void setActiveProvider(String providerName) {
        llmConfig.setActiveProvider(providerName);
        log.info("LLM 提供商已切换为: {}", providerName);
    }

    /** 运行时切换默认模型 */
    public void setDefaultModel(String modelName) {
        llmConfig.setDefaultModel(modelName);
        log.info("LLM 默认模型已切换为: {}", modelName);
    }

    // ========================================
    // 内部辅助 — 解析 provider / model / WebClient
    // ========================================

    private ProviderConfig resolveProvider(String providerName) {
        if (providerName != null && !providerName.isEmpty()) {
            return llmConfig.getProviderConfig(providerName);
        }
        return llmConfig.getActiveProviderConfig();
    }

    private String resolveModel(String providerName, String modelName) {
        return llmConfig.resolveModel(modelName);
    }

    private WebClient getWebClient(String providerName) {
        String key = (providerName != null && !providerName.isEmpty())
                ? providerName : llmConfig.getActiveProvider();
        WebClient client = webClients.get(key);
        if (client == null) {
            throw new LlmException("未找到提供商对应的 WebClient: " + key);
        }
        return client;
    }

    private String buildAuth(ProviderConfig provider) {
        if (provider.getApiKey() == null || provider.getApiKey().isEmpty()) {
            return null;
        }
        return "Bearer " + provider.getApiKey();
    }

    // ========================================
    // 纯文本对话（同步调用）
    // ========================================

    /** 单轮对话（使用激活的提供商） */
    public String chat(String systemPrompt, String userPrompt) {
        return chat(null, null, systemPrompt, userPrompt);
    }

    /** 单轮对话（指定提供商和模型） */
    public String chat(String provider, String model, String systemPrompt, String userPrompt) {
        List<ChatRequest.Message> messages = new ArrayList<>();
        messages.add(ChatRequest.Message.system(systemPrompt));
        messages.add(ChatRequest.Message.user(userPrompt));
        return chat(provider, model, messages);
    }

    /** 多轮对话（使用激活的提供商） */
    public String chat(List<ChatRequest.Message> messages) {
        return chat(null, null, messages);
    }

    /** 多轮对话（指定提供商和模型） */
    public String chat(String provider, String model, List<ChatRequest.Message> messages) {
        ProviderConfig providerConfig = resolveProvider(provider);
        String effectiveModel = resolveModel(provider, model);
        ChatRequest request = buildTextRequest(providerConfig, effectiveModel, messages, false);
        String response = doRequestSync(provider, providerConfig, request);
        return parseNormalResponse(response);
    }

    /** 带历史记录的对话 */
    public String chatWithHistory(String systemPrompt, String userPrompt, List<ChatRequest.Message> history) {
        return chatWithHistory(null, null, systemPrompt, userPrompt, history);
    }

    /** 带历史记录的对话（指定提供商和模型） */
    public String chatWithHistory(String provider, String model,
                                   String systemPrompt, String userPrompt,
                                   List<ChatRequest.Message> history) {
        List<ChatRequest.Message> messages = new ArrayList<>();
        messages.add(ChatRequest.Message.system(systemPrompt));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(ChatRequest.Message.user(userPrompt));
        return chat(provider, model, messages);
    }

    // ========================================
    // 纯文本对话（流式调用）
    // ========================================

    /** 单轮流式对话 */
    public Flux<String> chatStream(String systemPrompt, String userPrompt) {
        return chatStream(null, null, systemPrompt, userPrompt);
    }

    /** 单轮流式对话（指定提供商和模型） */
    public Flux<String> chatStream(String provider, String model, String systemPrompt, String userPrompt) {
        List<ChatRequest.Message> messages = new ArrayList<>();
        messages.add(ChatRequest.Message.system(systemPrompt));
        messages.add(ChatRequest.Message.user(userPrompt));
        return chatStream(provider, model, messages);
    }

    /** 多轮流式对话 */
    public Flux<String> chatStream(List<ChatRequest.Message> messages) {
        return chatStream(null, null, messages);
    }

    /** 多轮流式对话（指定提供商和模型） */
    public Flux<String> chatStream(String provider, String model, List<ChatRequest.Message> messages) {
        ProviderConfig providerConfig = resolveProvider(provider);
        String effectiveModel = resolveModel(provider, model);
        ChatRequest request = buildTextRequest(providerConfig, effectiveModel, messages, true);
        return doRequestStream(provider, providerConfig, request);
    }

    // ========================================
    // 图片翻译（Vision + 翻译）
    // ========================================

    public String translateImage(byte[] imageBytes, String from, String to, String domain, String style) {
        return translateImage(null, null, imageBytes, from, to, domain, style);
    }

    public String translateImage(String provider, String model,
                                  byte[] imageBytes, String from, String to, String domain, String style) {
        log.info("开始图片翻译: provider={}, model={}, from={}, to={}", provider, model, from, to);

        byte[] compressedBytes = compressImage(imageBytes);
        String base64Image = Base64.getEncoder().encodeToString(compressedBytes);

        String systemPrompt = buildImageTranslatePrompt(from, to, domain, style);
        String fromName = resolveLangName(from);
        String toName = resolveLangName(to);
        String userText = String.format("请提取图片中的%s并翻译为%s，以JSON格式输出。", fromName, toName);

        List<Object> messages = List.of(
                ChatRequest.Message.system(systemPrompt),
                ChatRequest.VisionMessage.userWithImage(userText, base64Image)
        );

        return callVisionApi(provider, model, messages);
    }

    public String translateImageBase64(String base64Image, String from, String to, String domain, String style) {
        return translateImageBase64(null, null, base64Image, from, to, domain, style);
    }

    public String translateImageBase64(String provider, String model,
                                        String base64Image, String from, String to, String domain, String style) {
        String systemPrompt = buildImageTranslatePrompt(from, to, domain, style);
        String fromName2 = resolveLangName(from);
        String toName2 = resolveLangName(to);
        String userText = String.format("请提取图片中的%s并翻译为%s，以JSON格式输出。", fromName2, toName2);

        List<Object> messages = List.of(
                ChatRequest.Message.system(systemPrompt),
                ChatRequest.VisionMessage.userWithImage(userText, base64Image)
        );

        return callVisionApi(provider, model, messages);
    }

    public Flux<String> translateImageStream(byte[] imageBytes, String from, String to, String domain, String style) {
        return translateImageStream(null, null, imageBytes, from, to, domain, style);
    }

    public Flux<String> translateImageStream(String provider, String model,
                                              byte[] imageBytes, String from, String to, String domain, String style) {
        log.info("开始图片翻译(流式): provider={}, model={}, from={}, to={}", provider, model, from, to);

        byte[] compressedBytes = compressImage(imageBytes);
        String base64Image = Base64.getEncoder().encodeToString(compressedBytes);

        String systemPrompt = buildImageTranslatePrompt(from, to, domain, style);
        String fromName3 = resolveLangName(from);
        String toName3 = resolveLangName(to);
        String userText = String.format("请提取图片中的%s并翻译为%s，以JSON格式输出。", fromName3, toName3);

        List<Object> messages = List.of(
                ChatRequest.Message.system(systemPrompt),
                ChatRequest.VisionMessage.userWithImage(userText, base64Image)
        );

        return callVisionApiStream(provider, model, messages);
    }

    // ========================================
    // 图片内容提取（纯 OCR）
    // ========================================

    public String extractTextFromImage(byte[] imageBytes) {
        return extractTextFromImage(null, null, imageBytes);
    }

    public String extractTextFromImage(String provider, String model, byte[] imageBytes) {
        byte[] compressedBytes = compressImage(imageBytes);
        String base64Image = Base64.getEncoder().encodeToString(compressedBytes);

        String systemPrompt = "你是一个OCR引擎。请识别图片中的所有文字，保持原有格式和换行。只输出识别到的文字内容，不要添加任何解释。";
        String userText = "请识别这张图片中的所有文字：";

        List<Object> messages = List.of(
                ChatRequest.Message.system(systemPrompt),
                ChatRequest.VisionMessage.userWithImage(userText, base64Image)
        );

        return callVisionApi(provider, model, messages);
    }

    // ========================================
    // Vision API 调用（同步）
    // ========================================

    @Retryable(
            value = {WebClientResponseException.class, RuntimeException.class},
            maxAttemptsExpression = "${ai.llm.max-retries:3}",
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    private String callVisionApi(String provider, String model, List<Object> messages) {
        ProviderConfig providerConfig = resolveProvider(provider);
        String effectiveModel = resolveModel(provider, model);
        WebClient client = getWebClient(provider);

        log.info("调用Vision LLM API: provider={}, model={}, url={}/chat/completions",
                provider, effectiveModel, providerConfig.getBaseUrl());

        Map<String, Object> requestBody = buildVisionRequestBody(providerConfig, effectiveModel, messages);

        long startTime = System.currentTimeMillis();
        try {
            String responseBody = client.post()
                    .uri("/chat/completions")
                    .header("Authorization", buildAuth(providerConfig))
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            long duration = System.currentTimeMillis() - startTime;
            log.info("Vision LLM响应成功: duration={}ms", duration);

            if (responseBody == null || responseBody.isEmpty()) {
                throw new LlmException("LLM返回响应体为空");
            }

            return parseStreamResponse(responseBody);
        } catch (WebClientResponseException e) {
            log.error("Vision LLM服务器错误: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new LlmException("LLM服务内部错误: " + e.getStatusCode() + ", " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("Vision LLM调用失败: error={}", e.getMessage(), e);
            throw new LlmException("LLM调用失败: " + e.getMessage(), e);
        }
    }

    // ========================================
    // Vision API 调用（流式 Flux）
    // ========================================

    public Flux<String> callVisionApiStream(String provider, String model, List<Object> messages) {
        ProviderConfig providerConfig = resolveProvider(provider);
        String effectiveModel = resolveModel(provider, model);
        WebClient client = getWebClient(provider);

        log.info("调用Vision LLM API (流式): provider={}, model={}, url={}/chat/completions",
                provider, effectiveModel, providerConfig.getBaseUrl());

        Map<String, Object> requestBody = buildVisionRequestBody(providerConfig, effectiveModel, messages);

        // 调试：打印请求体（截断 base64 图片数据）
        try {
            String bodyJson = objectMapper.writeValueAsString(requestBody);
            String truncated = bodyJson.length() > 500 ? bodyJson.substring(0, 500) + "..." : bodyJson;
            log.info("Vision请求体(截断): {}", truncated);
        } catch (Exception ex) {
            log.warn("无法序列化请求体: {}", ex.getMessage());
        }

        long startTime = System.currentTimeMillis();

        return client.post()
                .uri("/chat/completions")
                .header("Authorization", buildAuth(providerConfig))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnComplete(() -> {
                    long duration = System.currentTimeMillis() - startTime;
                    log.info("Vision LLM流式响应完成: duration={}ms", duration);
                })
                .doOnError(e -> {
                    String errBody = "";
                    if (e instanceof WebClientResponseException wcre) {
                        errBody = wcre.getResponseBodyAsString();
                    }
                    log.error("Vision LLM流式调用失败: error={}, 响应体={}", e.getMessage(), errBody, e);
                });
    }

    // ========================================
    // 图片压缩工具
    // ========================================

    private byte[] compressImage(byte[] imageBytes) {
        try {
            if (imageBytes.length < 50 * 1024) {
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

            int maxDimension = 1024;
            double scale = Math.min(1.0, (double) maxDimension / Math.max(originalWidth, originalHeight));

            int newWidth = (int) (originalWidth * scale);
            int newHeight = (int) (originalHeight * scale);

            Image scaledImage = originalImage.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH);
            BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = resizedImage.createGraphics();
            g2d.drawImage(scaledImage, 0, 0, null);
            g2d.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(resizedImage, "jpeg", baos);
            baos.flush();
            byte[] compressed = baos.toByteArray();
            baos.close();

            if (compressed.length >= imageBytes.length) {
                return imageBytes;
            }

            return compressed;
        } catch (Exception e) {
            log.error("图片压缩失败，返回原始数据", e);
            return imageBytes;
        }
    }

    // ========================================
    // 请求体构建
    // ========================================

    private ChatRequest buildTextRequest(ProviderConfig providerConfig, String model,
                                          List<ChatRequest.Message> messages, boolean stream) {
        ChatRequest request = new ChatRequest();
        request.setModel(model);
        request.setMessages(messages);
        request.setTemperature(llmConfig.getTemperature());
        request.setTopP(llmConfig.getTopP());
        request.setMaxTokens(llmConfig.getMaxTokens());
        request.setStream(stream);

        // DeepSeek 特有参数
        applyDeepSeekParams(request, providerConfig);

        return request;
    }

    private Map<String, Object> buildVisionRequestBody(ProviderConfig providerConfig, String model,
                                                        List<Object> messages) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", llmConfig.getTemperature());
        requestBody.put("top_p", llmConfig.getTopP());
        requestBody.put("max_tokens", llmConfig.getMaxTokens());
        requestBody.put("stream", true);

        // Vision API 不支持 thinking / reasoning_effort 参数，仅 reasoner 模型支持
        // 这些参数在 buildTextRequest / applyDeepSeekParams 中处理纯文本场景

        return requestBody;
    }

    /**
     * 为 ChatRequest 添加 DeepSeek 特有参数
     */
    private void applyDeepSeekParams(ChatRequest request, ProviderConfig providerConfig) {
        if (llmConfig.isThinkingEnabled()) {
            request.setThinking(Map.of("type", "enabled"));
        }
        if (providerConfig.getReasoningEffort() != null && !providerConfig.getReasoningEffort().isEmpty()) {
            request.setReasoningEffort(providerConfig.getReasoningEffort());
        }
    }

    // ========================================
    // HTTP 请求执行
    // ========================================

    private String doRequestSync(String provider, ProviderConfig providerConfig, ChatRequest request) {
        WebClient client = getWebClient(provider);
        try {
            String response = client.post()
                    .uri("/chat/completions")
                    .header("Authorization", buildAuth(providerConfig))
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response == null || response.isEmpty()) {
                throw new LlmException("LLM返回响应体为空");
            }

            return response;
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("LLM调用失败: " + e.getMessage(), e);
        }
    }

    private Flux<String> doRequestStream(String provider, ProviderConfig providerConfig, ChatRequest request) {
        WebClient client = getWebClient(provider);
        return client.post()
                .uri("/chat/completions")
                .header("Authorization", buildAuth(providerConfig))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)

                // 拆行
                .flatMap(chunk -> Flux.fromArray(chunk.split("\n")))

                // 过滤
                .map(String::trim)
                .filter(line -> line.startsWith("data:"))

                // 去掉前缀
                .map(line -> line.substring(5).trim())
                .filter(data -> !data.equals("[DONE]"))

                // 解析 JSON
                .flatMap(this::parseStreamLine)

                // 防止异常中断流
                .onErrorResume(e -> {
                    log.error("流式解析异常", e);
                    return Flux.empty();
                });
    }

    // ========================================
    // 响应解析
    // ========================================

    private String parseNormalResponse(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            JsonNode content = json.get("choices").get(0).get("message").get("content");
            if (content == null || content.isNull()) {
                throw new LlmException("LLM返回消息内容为空");
            }
            String result = content.asText().trim();
            if (result.isEmpty()) {
                throw new LlmException("LLM返回消息内容为空字符串");
            }
            return result;
        } catch (Exception e) {
            throw new LlmException("解析响应失败", e);
        }
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

    private Flux<String> parseStreamLine(String data) {
        try {
            JsonNode json = objectMapper.readTree(data);
            JsonNode delta = json.get("choices").get(0).get("delta");

            if (delta != null) {
                // content
                JsonNode content = delta.get("content");
                if (content != null && !content.isNull()) {
                    return Flux.just(content.asText());
                }

                // reasoning（DeepSeek 思考过程）
                JsonNode reasoning = delta.get("reasoning");
                if (reasoning != null && !reasoning.isNull()) {
                    return Flux.just(reasoning.asText());
                }
            }
        } catch (Exception e) {
            log.warn("解析失败: {}", data);
        }
        return Flux.empty();
    }

    // ========================================
    // 提示词构建
    // ========================================

    private static final Map<String, String> LANG_NAME_MAP = Map.ofEntries(
            Map.entry("auto", "自动检测到的文字"),
            Map.entry("chinese", "中文"),
            Map.entry("english", "英文"),
            Map.entry("japanese", "日文"),
            Map.entry("korean", "韩文"),
            Map.entry("french", "法文"),
            Map.entry("german", "德文"),
            Map.entry("spanish", "西班牙文"),
            Map.entry("russian", "俄文"),
            Map.entry("arabic", "阿拉伯文"),
            Map.entry("portuguese", "葡萄牙文"),
            Map.entry("italian", "意大利文"),
            Map.entry("thai", "泰文"),
            Map.entry("vietnamese", "越南文")
    );

    private String resolveLangName(String lang) {
        if (lang == null || lang.isEmpty()) return "自动检测";
        String mapped = LANG_NAME_MAP.get(lang.toLowerCase());
        return mapped != null ? mapped : lang;
    }

    private String buildImageTranslatePrompt(String from, String to, String domain, String style) {
        String fromName = resolveLangName(from);
        String toName = resolveLangName(to);

        return String.format("""
                请提取图片中的%s，并将其翻译为%s。请以JSON格式输出，包含两个字段："original"（原文）和"translation"（译文）。
                """, fromName, toName);
    }
}
