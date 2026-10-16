package com.xhs.assistant;

import com.xhs.assistant.config.AssistantProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class XhsAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(XhsAssistantApplication.class, args);
    }
}
