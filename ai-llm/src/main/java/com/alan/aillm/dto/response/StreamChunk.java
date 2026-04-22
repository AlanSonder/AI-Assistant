package com.alan.aillm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamChunk {

    private String id;

    private String object = "chat.completion.chunk";

    private Long created;

    private String model;

    private java.util.List<Choice> choices;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Choice {
        private int index;
        private Delta delta;
        private String finishReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Delta {
        private String role;
        private String content;
    }

    public static StreamChunk contentChunk(String content) {
        return StreamChunk.builder()
                .choices(java.util.List.of(
                        Choice.builder()
                                .index(0)
                                .delta(Delta.builder().content(content).build())
                                .build()
                ))
                .build();
    }

    public static StreamChunk doneChunk() {
        return StreamChunk.builder()
                .choices(java.util.List.of(
                        Choice.builder()
                                .index(0)
                                .delta(Delta.builder().build())
                                .finishReason("stop")
                                .build()
                ))
                .build();
    }
}
