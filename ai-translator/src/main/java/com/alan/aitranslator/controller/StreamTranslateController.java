package com.alan.aitranslator.controller;

import com.alan.aitranslator.dto.request.TranslateRequest;
import com.alan.aitranslator.service.StreamTranslateService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/v1/translate")
public class StreamTranslateController {

    @Autowired
    private StreamTranslateService streamTranslateService;

    /**
     * 流式文本翻译（SSE）
     */
    @PostMapping(value = "/stream/text", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter translateTextStream(@Valid @RequestBody TranslateRequest request) {
        log.info("流式翻译请求: from={}, to={}, length={}", request.getFrom(), request.getTo(), request.getText().length());
        return streamTranslateService.translateStream(request);
    }
}
