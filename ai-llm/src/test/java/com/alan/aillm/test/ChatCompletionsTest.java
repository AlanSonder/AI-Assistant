package com.alan.aillm.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 直接调用 LLM /chat/completions 接口的测试工具类
 */
public class ChatCompletionsTest {

    private static final String BASE_URL = "http://localhost:1234/v1";
    private static final String API_KEY = "sk-lm-zmU7SpEW:2wED8QUaU6JFaPKTbs6t";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
        try {            
            // 测试通用对话
            testChat("什么是人工智能？");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 测试通用对话
     */
    public static void testChat(String question) throws Exception {
        System.out.println("=== 测试通用对话 ===");
        System.out.println("问题: " + question);
        
        String systemPrompt = "你是一个智能助手，回答问题要简洁明了。";
        String userPrompt = question;
        
        String response = callChatCompletions(systemPrompt, userPrompt);
        System.out.println("回答: " + response);
        System.out.println();
    }

    /**
     * 调用 /chat/completions 接口
     */
    public static String callChatCompletions(String systemPrompt, String userPrompt) throws Exception {
        String url = BASE_URL + "/chat/completions";
        URL endpoint = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        if (API_KEY != null && !API_KEY.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + API_KEY);
        }
        connection.setDoOutput(true);
        connection.setConnectTimeout(60000);
        connection.setReadTimeout(60000);

        // 构建请求体
        Map<String, Object> requestBody = Map.of(
            "model", "qwen",
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ),
            "temperature", 0.3,
            "top_p", 0.9,
            "max_tokens", 4096,
            "stream", false,
            "reasoning",Map.of(
                "effort", "none"
            )
        );

        // 发送请求
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = objectMapper.writeValueAsString(requestBody).getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        // 读取响应
        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder error = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    error.append(line);
                }
                throw new Exception("API调用失败，状态码: " + responseCode + "，错误: " + error);
            }
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }

            // 解析响应
            Map<String, Object> responseMap = objectMapper.readValue(response.toString(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0);
                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                return (String) message.get("content");
            }
            return "无响应内容";
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 构建多轮对话请求体
     */
    public static String callChatWithHistory(List<Map<String, Object>> messages) throws Exception {
        String url = BASE_URL + "/chat/completions";
        URL endpoint = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        if (API_KEY != null && !API_KEY.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + API_KEY);
        }
        connection.setDoOutput(true);

        // 构建请求体
        Map<String, Object> requestBody = Map.of(
            "model", "qwen",
            "messages", messages,
            "temperature", 0.3,
            "top_p", 0.9,
            "max_tokens", 4096,
            "stream", false
        );

        // 发送请求
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = objectMapper.writeValueAsString(requestBody).getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        // 读取响应
        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder error = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    error.append(line);
                }
                throw new Exception("API调用失败，状态码: " + responseCode + "，错误: " + error);
            }
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }

            // 解析响应
            Map<String, Object> responseMap = objectMapper.readValue(response.toString(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0);
                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                return (String) message.get("content");
            }
            return "无响应内容";
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 测试多轮对话
     */
    public static void testChatWithHistory() throws Exception {
        System.out.println("=== 测试多轮对话 ===");
        
        List<Map<String, Object>> messages = new ArrayList<>();
        
        // 第一轮
        messages.add(Map.of("role", "system", "content", "你是一个智能助手，回答问题要简洁明了。"));
        messages.add(Map.of("role", "user", "content", "什么是Java？"));
        String response1 = callChatWithHistory(messages);
        System.out.println("用户: 什么是Java？");
        System.out.println("助手: " + response1);
        
        // 第二轮
        messages.add(Map.of("role", "assistant", "content", response1));
        messages.add(Map.of("role", "user", "content", "它有什么特点？"));
        String response2 = callChatWithHistory(messages);
        System.out.println("用户: 它有什么特点？");
        System.out.println("助手: " + response2);
        System.out.println();
    }
}
