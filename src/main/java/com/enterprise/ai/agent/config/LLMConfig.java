package com.enterprise.ai.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for ChatClient supporting both OpenAI and local LM Studio LLM.
 * <p>
 * Switch between providers using the property: app.llm.provider
 * - Set to "openai" for OpenAI API (default)
 * - Set to "lmstudio" for local LM Studio
 */
@Configuration
public class LLMConfig {

    /**
     * ChatClient bean for OpenAI (default).
     * <p>
     * Active when: app.llm.provider=openai (or not specified)
     * <p>
     * Properties needed in application.properties:
     * - spring.ai.openai.api-key=${OPENAI_API_KEY}
     * - spring.ai.openai.chat.options.model=gpt-4-mini
     */
    @Bean
    @ConditionalOnProperty(
        name = "app.llm.provider",
        havingValue = "openai",
        matchIfMissing = true
    )
    public ChatClient openAiChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /**
     * ChatClient bean for LM Studio (local LLM).
     * <p>
     * Active when: app.llm.provider=lmstudio
     * <p>
     * Properties needed in application.properties:
     * - spring.ai.openai.base-url=http://127.0.0.1:1234/v1
     * - spring.ai.openai.api-key=not-needed (LM Studio doesn't require API key)
     * - spring.ai.openai.chat.options.model=local-model
     */
    @Bean
    @ConditionalOnProperty(
        name = "app.llm.provider",
        havingValue = "lmstudio"
    )
    public ChatClient lmStudioChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
