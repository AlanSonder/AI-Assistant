package com.alan.aitranslator.service;

import com.alan.aillm.config.LlmConfig;
import com.alan.aitranslator.config.TranslatorConfig;
import com.alan.aitranslator.dto.request.TranslateRequest;
import com.alan.aitranslator.util.TextPreprocessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class StreamTranslateService {

    @Autowired
    private TranslatorConfig translatorConfig;

    @Autowired
    private LlmConfig llmConfig;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    /**
     * 流式翻译，通过 SSE 实时推送翻译结果
     */
    public SseEmitter translateStream(TranslateRequest request) {
        SseEmitter emitter = new SseEmitter(300000L);

        executorService.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String text = TextPreprocessor.preprocess(request.getText());
                String from = request.getFrom().toLowerCase();
                String to = request.getTo().toLowerCase();
                String domain = request.getDomain() != null ? request.getDomain() : translatorConfig.getDefaultDomain();
                String style = request.getStyle() != null ? request.getStyle() : translatorConfig.getDefaultStyle();

                // 发送开始事件
                emitter.send(SseEmitter.event()
                        .name("start")
                        .data("{\"status\": \"started\", \"textLength\": " + text.length() + ", \"from\": \"" + from + "\", \"to\": \"" + to + "\"}"));

                String systemPrompt = buildSystemPrompt(from, to, domain, style);
                String userPrompt = buildUserPrompt(text, request.getContextText());

                // 构建流式请求
                String jsonBody = buildStreamRequestBody(systemPrompt, userPrompt);

                URL url = new URL(llmConfig.getBaseUrl() + "/chat/completions");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "text/event-stream");
                if (llmConfig.getApiKey() != null && !llmConfig.getApiKey().isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + llmConfig.getApiKey());
                }
                connection.setDoOutput(true);
                connection.setDoInput(true);
                connection.setConnectTimeout((int) llmConfig.getTimeout());
                connection.setReadTimeout((int) llmConfig.getTimeout());

                // 发送请求体
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    throw new RuntimeException("LLM流式请求失败，HTTP状态码: " + responseCode);
                }

                // 读取 SSE 流并转发给客户端
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    StringBuilder contentBuffer = new StringBuilder();

                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            if ("[DONE]".equals(data)) {
                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data("{\"finish\": true, \"fullText\": \"" + escapeJson(contentBuffer.toString()) + "\"}"));
                                break;
                            }

                            // 解析内容块
                            String content = extractContentFromChunk(data);
                            if (content != null && !content.isEmpty()) {
                                contentBuffer.append(content);
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data("{\"content\": \"" + escapeJson(content) + "\"}"));
                            }
                        }
                    }
                }

                emitter.complete();
            } catch (Exception e) {
                log.error("流式翻译异常", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"error\": \"" + escapeJson(e.getMessage()) + "\"}"));
                    emitter.completeWithError(e);
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });

        // 连接断开处理
        emitter.onCompletion(() -> log.debug("SSE连接完成"));
        emitter.onTimeout(() -> log.warn("SSE连接超时"));
        emitter.onError((e) -> log.error("SSE连接异常", e));

        return emitter;
    }

    private String buildStreamRequestBody(String systemPrompt, String userPrompt) throws Exception {
        var request = new java.util.HashMap<String, Object>();
        request.put("model", llmConfig.getModel());
        request.put("messages", List.of(
                java.util.Map.of("role", "system", "content", systemPrompt),
                java.util.Map.of("role", "user", "content", userPrompt)
        ));
        request.put("temperature", llmConfig.getTemperature());
        request.put("top_p", llmConfig.getTopP());
        request.put("max_tokens", llmConfig.getMaxTokens());
        request.put("stream", true);
        return objectMapper.writeValueAsString(request);
    }

    private String extractContentFromChunk(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta != null) {
                    JsonNode content = delta.get("content");
                    if (content != null && !content.isNull()) {
                        return content.asText();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析流式数据块失败: {}", data, e);
        }
        return null;
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String buildSystemPrompt(String from, String to, String domain, String style) {
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
                你是专业翻译引擎，专注于%s到%s的高质量翻译。

                领域: %s - %s
                风格: %s

                规则:
                1. 只输出翻译结果，不添加任何解释或注释
                2. 保持原文的格式、换行和标点风格
                3. 专业术语使用领域标准译法
                4. 保留专有名词（人名、地名、品牌名）原文
                5. 代码片段和技术术语不要翻译
                6. 如遇无法翻译的内容，原样返回
                7. 输出必须完整，不能截断
                """, from, to, domain, domainDesc, styleDesc);
    }

    private String buildUserPrompt(String text, String context) {
        if (context != null && !context.trim().isEmpty()) {
            return String.format("参考上下文：\n%s\n\n将以下内容翻译为目标语言：\n%s", context, text);
        }
        return String.format("翻译以下内容：\n%s", text);
    }
}
