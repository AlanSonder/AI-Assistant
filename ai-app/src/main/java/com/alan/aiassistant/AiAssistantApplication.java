package com.alan.aiassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.alan.aiassistant", "com.alan.aicommon", "com.alan.aitranslator", "com.alan.aillm", "com.alan.aiocr", "com.alan.aiagent"})
public class AiAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAssistantApplication.class, args);
    }

}
