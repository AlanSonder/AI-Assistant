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
            testChat(
                    "不假思索地,以最快的速度翻译这段文本:When college students graduate， most of them will choose to join the civil servants exam or further study， these two choices bee more and more popular， they have the mon side， that is stability. The young people pay special attention to stability when they find a job， because in today’s society， the pressure is so heavy， they fear to lose job， working for the government is the best choice for them， they don’t have to worry about losing jobs. While they are so young， they should be energetic， they should do the pioneering work， they have nothing to lose， because they have nothing at the beginning. If the young people don’t dare to fight， they waste their youth， all their lives are insipid， when they are old， they look back on their youth， just nothing leaves.");

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

        // String response = callChatCompletions(systemPrompt, userPrompt);
        String response = callChatCompletionsStream(systemPrompt, userPrompt);
        System.out.println("回答: " + response);
        System.out.println();
    }

    /**
     * 调用 /chat/completions 接口
     */
    public static String callChatCompletions(String systemPrompt, String userPrompt) throws Exception {
        long startTime = System.currentTimeMillis();
        System.out.println("开始调用 LLM API...");

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
                        Map.of("role", "user", "content", userPrompt)),
                "temperature", 0.3,
                "top_p", 0.9,
                "max_tokens", 4096,
                "stream", false,
                // 👇 新增 extra_body 透传字段
                "extra_body", Map.of(
                        "enable_thinking", false // 🔑 设为 true 开启思考模式，false 关闭
                ));
        System.out.println("请求体: " + objectMapper.writeValueAsString(requestBody));
        System.out.println();

        // 发送请求
        long sendTime = System.currentTimeMillis();
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = objectMapper.writeValueAsString(requestBody).getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
        System.out.println("请求发送完成，耗时: " + (System.currentTimeMillis() - sendTime) + "ms");

        // 读取响应
        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder error = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    error.append(line);
                }
                throw new Exception("API调用失败，状态码: " + responseCode + "，错误: " + error);
            }
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
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
                String content = (String) message.get("content");

                long totalTime = System.currentTimeMillis() - startTime;
                System.out.println("API调用完成，总耗时: " + totalTime + "ms");
                System.out.println("响应长度: " + content.length() + " 字符");

                return content;
            }
            return "无响应内容";
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 调用 /chat/completions 接口（流式）
     */
    public static String callChatCompletionsStream(String systemPrompt, String userPrompt) throws Exception {
        long startTime = System.currentTimeMillis();
        System.out.println("开始调用 LLM API (流式)...");
        
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

        // 构建请求体（开启流式）
        Map<String, Object> requestBody = Map.of(
            "model", "qwen",
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ),
            "temperature", 0.3,
            "top_p", 0.9,
            "max_tokens", 4096,
            "stream", true  // 开启流式
        );
        System.out.println("请求体: " + objectMapper.writeValueAsString(requestBody));
        System.out.println();
        
        // 发送请求
        long sendTime = System.currentTimeMillis();
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = objectMapper.writeValueAsString(requestBody).getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
        System.out.println("请求发送完成，耗时: " + (System.currentTimeMillis() - sendTime) + "ms");

        // 读取流式响应
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

        StringBuilder fullContent = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                // 跳过空行
                if (line.isEmpty()) {
                    continue;
                }
                
                // 移除 "data: " 前缀
                if (line.startsWith("data: ")) {
                    line = line.substring(6);
                }
                
                // 处理结束标记
                if (line.equals("[DONE]")) {
                    break;
                }
                
                try {
                    // 解析每个JSON对象
                    Map<String, Object> responseMap = objectMapper.readValue(line, Map.class);
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        Map<String, Object> choice = choices.get(0);
                        Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
                        if (delta != null) {
                            String content = (String) delta.get("content");
                            if (content != null && !content.isEmpty()) {
                                System.out.print(content);  // 实时输出
                                fullContent.append(content);
                            }
                        }
                    }
                } catch (Exception e) {
                    // 忽略解析错误，继续处理下一行
                    System.err.println("解析响应行失败: " + e.getMessage());
                }
            }
            System.out.println();  // 换行
        } finally {
            connection.disconnect();
        }
        
        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("API调用完成，总耗时: " + totalTime + "ms");
        System.out.println("响应长度: " + fullContent.length() + " 字符");
        
        return fullContent.toString();
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
                "stream", false);

        // 发送请求
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = objectMapper.writeValueAsString(requestBody).getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        // 读取响应
        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder error = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    error.append(line);
                }
                throw new Exception("API调用失败，状态码: " + responseCode + "，错误: " + error);
            }
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
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
