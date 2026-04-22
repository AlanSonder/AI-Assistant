package com.alan.aitranslator.controller;

import com.alan.aicommon.dto.ApiResponse;
import com.alan.aitranslator.dto.request.TranslateRequest;
import com.alan.aitranslator.dto.response.AudioTranslateResponse;
import com.alan.aitranslator.dto.response.ImageTranslateResponse;
import com.alan.aitranslator.dto.response.TranslateResponse;
import com.alan.aitranslator.service.AudioTranslateService;
import com.alan.aitranslator.service.ImageTranslateService;
import com.alan.aitranslator.service.TranslateService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/translate")
public class TranslateController {

    @Autowired
    private TranslateService translateService;

    @Autowired
    private ImageTranslateService imageTranslateService;

    @Autowired
    private AudioTranslateService audioTranslateService;

    @PostMapping("/text")
    public ApiResponse<TranslateResponse> translateText(@Valid @RequestBody TranslateRequest request) {
        TranslateResponse response = translateService.translate(request);
        return ApiResponse.success(response);
    }

    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImageTranslateResponse> translateImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("from") String from,
            @RequestParam("to") String to) {
        ImageTranslateResponse response = imageTranslateService.translateImage(file, from, to);
        return ApiResponse.success(response);
    }

    @PostMapping(value = "/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AudioTranslateResponse> translateAudio(
            @RequestParam("file") MultipartFile file,
            @RequestParam("from") String from,
            @RequestParam("to") String to) {
        AudioTranslateResponse response = audioTranslateService.translateAudio(file, from, to);
        return ApiResponse.success(response);
    }
}
