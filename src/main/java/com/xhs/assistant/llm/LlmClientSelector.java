package com.xhs.assistant.llm;

import com.xhs.assistant.config.AssistantProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class LlmClientSelector {
    private final LlmClient openAi;
    private final LlmClient anthropic;
    private final AssistantProperties properties;

    public LlmClientSelector(@Qualifier("openAiLlmClient") LlmClient openAi,
                             @Qualifier("anthropicLlmClient") LlmClient anthropic,
                             AssistantProperties properties) {
        this.openAi = openAi;
        this.anthropic = anthropic;
        this.properties = properties;
    }

    // Mainland Chinese model platforms commonly expose an OpenAI-compatible
    // /chat/completions endpoint. They share one configurable client instead of
    // requiring a provider-specific SDK.
    private static final Set<String> OPENAI_COMPATIBLE_PROVIDERS = Set.of(
            "openai", "openai-compatible", "compatible", "generic",
            "glm", "zhipu", "bigmodel",
            "deepseek",
            "moonshot", "kimi",
            "qwen", "dashscope", "tongyi",
            "doubao", "ark", "volcengine",
            "minimax",
            "siliconflow"
    );

    public Optional<LlmClient> current() {
        String provider = properties.getModel().getProvider().toLowerCase(Locale.ROOT);
        if ("anthropic".equals(provider)) return Optional.of(anthropic);
        if (OPENAI_COMPATIBLE_PROVIDERS.contains(provider)) return Optional.of(openAi);
        return Optional.empty();
    }
}
