package com.alan.aillm.dto.request;

import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {
    private String model;
    private List<Message> messages;
    private double temperature;
    private double topP;
    private int maxTokens;
    private boolean stream;

    @Data
    public static class Message {
        private String role;
        private String content;

        public static Message system(String content) {
            Message msg = new Message();
            msg.setRole("system");
            msg.setContent(content);
            return msg;
        }

        public static Message user(String content) {
            Message msg = new Message();
            msg.setRole("user");
            msg.setContent(content);
            return msg;
        }

        public static Message assistant(String content) {
            Message msg = new Message();
            msg.setRole("assistant");
            msg.setContent(content);
            return msg;
        }
    }
}
