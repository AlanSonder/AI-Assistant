package com.alan.aitranslator.controller;

import com.alan.aitranslator.dto.TranslateRequest;
import com.alan.aitranslator.service.TranslateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/translate")
public class TranslateController {

    @Autowired
    private TranslateService translateService;

    @PostMapping
    public String translate(@RequestBody TranslateRequest request) {
        return translateService.translate(
                request.getText(),
                request.getFrom(),
                request.getTo()
        );
    }
}
