package com.alan.aillm.service;

import com.alan.aillm.dto.request.ChatRequest;
import com.alan.aillm.dto.response.ChatResponse;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
public class LlmService {

    private final RestTemplate restTemplate = new RestTemplate();

    private static final String URL = "http://localhost:1234/v1/chat/completions";

    public String chat(String systemPrompt, String userPrompt) {
        ChatRequest request = new ChatRequest();
        request.setModel("qwen");

        List<ChatRequest.Message> messages = new ArrayList<>();

        ChatRequest.Message sys = new ChatRequest.Message();
        sys.setRole("system");
        sys.setContent(systemPrompt);

        ChatRequest.Message user = new ChatRequest.Message();
        user.setRole("user");
        user.setContent(userPrompt);

        messages.add(sys);
        messages.add(user);

        request.setMessages(messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<ChatRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<ChatResponse> response =
                restTemplate.postForEntity(URL, entity, ChatResponse.class);

        return response.getBody()
                .getChoices()
                .get(0)
                .getMessage()
                .getContent();
    }
}
