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

    @Data
    public static class VisionMessage {
        private String role;
        private List<Content> content;

        public static VisionMessage userWithImage(String text, String base64Image) {
            VisionMessage msg = new VisionMessage();
            msg.setRole("user");
            msg.setContent(List.of(
                    Content.text(text),
                    Content.image(base64Image)
            ));
            return msg;
        }
    }

    @Data
    public static class Content {
        private String type;
        private String text;
        private ImageUrl image_url;

        public static Content text(String text) {
            Content c = new Content();
            c.setType("text");
            c.setText(text);
            return c;
        }

        public static Content image(String base64Data) {
            Content c = new Content();
            c.setType("image_url");
            c.setImage_url(new ImageUrl("data:image/jpeg;base64," + base64Data));
            return c;
        }
    }

    @Data
    public static class ImageUrl {
        private String url;

        public ImageUrl(String url) {
            this.url = url;
        }
    }
}
