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

