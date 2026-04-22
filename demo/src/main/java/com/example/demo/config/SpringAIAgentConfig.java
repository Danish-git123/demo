package com.example.demo.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAIAgentConfig {

    @Bean
    public ChatMemory chatMemory() {
        // Spring AI's updated memory architecture.
        // We now use MessageWindowChatMemory backed by a repository.
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20) // Keeps the last 20 messages in the rolling window
                .build();
    }
}