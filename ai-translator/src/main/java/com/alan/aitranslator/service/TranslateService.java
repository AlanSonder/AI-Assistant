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
            case "casual" -> "口语化、自然地道的风格";
            case "academic" -> "学术、严谨客观的风格";
            default -> "中性风格，准确传达原文语气";
        };

        return String.format("""
                你是一个极致高效的本地化翻译引擎。
                任务：将 <text> 标签内的内容从【%s】翻译为【%s】。
                
                背景设定：
                - 领域：%s
                - 风格：%s

                【最高指令】(必须严格遵守，否则会导致系统崩溃)：
                1. 绝对静默：只输出最终的翻译结果！绝不允许输出“好的”、“翻译如下”等任何客套话，禁止输出任何解释或思考过程。
                2. 地道精准：消除“机翻感”，必须符合目标语言的母语表达习惯和文化背景。
                3. 格式保持：严格保留原文的换行、标点符号风格（全半角）及 Markdown 格式。
                4. 专业规范：专业术语使用领域标准译法；代码片段、URL、专有名词（人名/地名/品牌名）保持原文。
                5. 完整兜底：如遇完全无法翻译的内容请原样返回；输出必须完整，严禁截断。
                """, from, to, domainDesc, styleDesc);
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

