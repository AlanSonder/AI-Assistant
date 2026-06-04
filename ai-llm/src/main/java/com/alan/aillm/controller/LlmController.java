package com.alan.aillm.controller;

import com.alan.aicommon.dto.ApiResponse;
import com.alan.aillm.config.LlmConfig;
import com.alan.aillm.config.LlmConfig.ProviderConfig;
import com.alan.aillm.service.LlmService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * LLM 提供商管理接口
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/llm")
public class LlmController {

    @Autowired
    private LlmService llmService;

    @Autowired
    private LlmConfig llmConfig;

    @GetMapping("/providers")
    public ApiResponse<Map<String, Object>> getProviders() {
        return ApiResponse.success(llmService.getProvidersInfo());
    }

    /**
     * 切换激活的提供商和/或模型。
     * 如果模型不属于当前 provider，自动切换到正确的 provider。
     */
    @PostMapping("/providers/activate")
    public ApiResponse<Map<String, Object>> activateProvider(@RequestBody SwitchRequest request) {
        // 1. 处理模型：自动匹配其所属的 provider
        String model = request.getModel();
        if (model != null && !model.isEmpty()) {
            // 查找该模型属于哪个 provider
            String owner = findProviderForModel(model);
            if (owner == null) {
                throw new IllegalArgumentException("未知模型: " + model);
            }
            // 如果没有显式指定 provider，或指定的 provider 与模型不匹配，自动纠正
            if (request.getProvider() == null || request.getProvider().isEmpty()) {
                request.setProvider(owner);
            } else if (!owner.equals(request.getProvider())) {
                log.warn("模型 {} 属于 {}，但请求指定 provider={}，已自动纠正", model, owner, request.getProvider());
                request.setProvider(owner);
            }
            log.info("切换LLM模型: {}", model);
            llmService.setDefaultModel(model);
        }

        // 2. 处理 provider
        if (request.getProvider() != null && !request.getProvider().isEmpty()) {
            log.info("切换LLM提供商: {}", request.getProvider());
            llmService.setActiveProvider(request.getProvider());
        }

        return ApiResponse.success(llmService.getProvidersInfo());
    }

    /**
     * 切换 DeepSeek 思考模式（thinking）开关。
     * 请求体: { "enabled": true/false }
     */
    @PostMapping("/thinking")
    public ApiResponse<Map<String, Object>> toggleThinking(@RequestBody ThinkingToggleRequest request) {
        log.info("切换DeepSeek思考模式: enabled={}", request.isEnabled());
        llmConfig.setThinkingEnabled(request.isEnabled());
        return ApiResponse.success(llmService.getProvidersInfo());
    }

    /** 在所有 provider 中查找模型所属的 provider 名称 */
    private String findProviderForModel(String model) {
        for (Map.Entry<String, ProviderConfig> entry : llmConfig.getProviders().entrySet()) {
            if (entry.getValue().getModels() != null && entry.getValue().getModels().contains(model)) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Data
    public static class SwitchRequest {
        private String provider;
        private String model;
    }

    @Data
    public static class ThinkingToggleRequest {
        private boolean enabled;
    }
}
