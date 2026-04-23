package com.alan.aitranslator.service;

import com.alan.aillm.service.LlmService;
import com.alan.aitranslator.config.TranslatorConfig;
import com.alan.aitranslator.dto.request.TranslateRequest;
import com.alan.aitranslator.dto.response.TranslateResponse;
import com.alan.aitranslator.util.TextPreprocessor;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TranslateService {

    @Autowired
    private LlmService llmService;

    @Autowired
    private TranslatorConfig translatorConfig;

    private Cache<String, String> translationCache;

    @PostConstruct
    public void init() {
        translationCache = Caffeine.newBuilder()
                .maximumSize(translatorConfig.getCacheSize())
                .expireAfterWrite(translatorConfig.getCacheTtlMinutes(), TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    public TranslateResponse translate(TranslateRequest request) {
        long startTime = System.currentTimeMillis();

        String text = TextPreprocessor.preprocess(request.getText());
        String from = request.getFrom().toLowerCase();
        String to = request.getTo().toLowerCase();
        String domain = request.getDomain() != null ? request.getDomain() : translatorConfig.getDefaultDomain();
        String style = request.getStyle() != null ? request.getStyle() : translatorConfig.getDefaultStyle();

        String cacheKey = buildCacheKey(text, from, to, domain, style);

        if (translatorConfig.isEnableCache()) {
            String cachedResult = translationCache.getIfPresent(cacheKey);
            if (cachedResult != null) {
                log.info("翻译命中缓存: from={}, to={}, length={}", from, to, text.length());
                return TranslateResponse.builder()
                        .originalText(text)
                        .translatedText(cachedResult)
                        .from(from)
                        .to(to)
                        .domain(domain)
                        .style(style)
                        .durationMs(System.currentTimeMillis() - startTime)
                        .cached(true)
                        .build();
            }
        }

        String result;
        if (text.length() > translatorConfig.getMaxSegmentLength()) {
            result = translateLongText(text, from, to, domain, style, request.getContextText());
        } else {
            result = translateSingle(text, from, to, domain, style, request.getContextText());
        }

        if (translatorConfig.isEnableCache()) {
            translationCache.put(cacheKey, result);
        }

        return TranslateResponse.builder()
                .originalText(text)
                .translatedText(result)
                .from(from)
                .to(to)
                .domain(domain)
                .style(style)
                .durationMs(System.currentTimeMillis() - startTime)
                .cached(false)
                .build();
    }

    private String translateSingle(String text, String from, String to, String domain, String style, String context) {
        String systemPrompt = buildSystemPrompt(from, to, domain, style);
        String userPrompt = buildUserPrompt(text, context);

        return llmService.chat(systemPrompt, userPrompt);
    }

    private String translateLongText(String text, String from, String to, String domain, String style, String context) {
        log.info("长文本翻译: length={}, 分段处理", text.length());

        List<String> segments = splitText(text, translatorConfig.getMaxSegmentLength());
        List<String> translatedSegments = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            String segmentContext = buildSegmentContext(segments, i, context);
            String translated = translateSingle(segment, from, to, domain, style, segmentContext);
            translatedSegments.add(translated);
        }

        return String.join("\n", translatedSegments);
    }

    private String buildSystemPrompt(String from, String to, String domain, String style) {
        return String.format("""
        翻译：%s -> %s
        只输出结果，保持原格式。
        """, from, to, domain, style);
    }

    private String buildUserPrompt(String text, String context) {
        // 使用 XML 标签包裹内容，有效防止本地小模型的“提示词注入(Prompt Injection)”问题
        if (context != null && !context.trim().isEmpty()) {
            return String.format("""
                    <context>
                    %s
                    </context>
                    
                    <text>
                    %s
                    </text>""", context.trim(), text.trim());
        }
        
        return String.format("""
                <text>
                %s
                </text>""", text.trim());
    }

    private String buildSegmentContext(List<String> segments, int currentIndex, String globalContext) {
        StringBuilder context = new StringBuilder();
        if (globalContext != null && !globalContext.trim().isEmpty()) {
            context.append(globalContext);
        }
        if (currentIndex > 0) {
            String prevSegment = segments.get(currentIndex - 1);
            if (prevSegment.length() > 200) {
                prevSegment = prevSegment.substring(0, 200) + "...";
            }
            context.append("\n前一段内容: ").append(prevSegment);
        }
        return context.toString();
    }

    private List<String> splitText(String text, int maxSegmentLength) {
        List<String> segments = new ArrayList<>();
        String[] paragraphs = text.split("\n");
        StringBuilder currentSegment = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (currentSegment.length() + paragraph.length() > maxSegmentLength && !currentSegment.isEmpty()) {
                segments.add(currentSegment.toString().trim());
                currentSegment = new StringBuilder();
            }
            currentSegment.append(paragraph).append("\n");
        }

        if (!currentSegment.isEmpty()) {
            segments.add(currentSegment.toString().trim());
        }

        return segments;
    }

    private String buildCacheKey(String text, String from, String to, String domain, String style) {
        int hash = (text + from + to + domain + style).hashCode();
        return String.format("%s_%s_%s_%s_%s_%d", from, to, domain, style, text.length(), hash);
    }

    public Cache<String, String> getCache() {
        return translationCache;
    }
}

