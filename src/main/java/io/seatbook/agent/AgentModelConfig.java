package io.seatbook.agent;

import java.time.Duration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AgentModelConfig {
    @Bean
    @ConditionalOnProperty(name = "seatbook.agent.enabled", havingValue = "true")
    ChatModel seatbookChatModel(
        @Value("${seatbook.agent.base-url}") String baseUrl,
        @Value("${seatbook.agent.api-key}") String apiKey,
        @Value("${seatbook.agent.model}") String model
    ) {
        if (apiKey.isBlank() || model.isBlank()) {
            throw new IllegalArgumentException("Set SEATBOOK_AGENT_API_KEY and SEATBOOK_AGENT_MODEL to enable model mode");
        }
        var options = OpenAiChatOptions.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .model(model)
            .timeout(Duration.ofSeconds(5))
            .maxRetries(0)
            .maxCompletionTokens(500)
            .build();
        return OpenAiChatModel.builder().options(options).build();
    }
}
