package com.xhs.assistant.llm;

import com.xhs.assistant.config.AssistantProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class LlmClientSelectorTest {
    @Mock private LlmClient openAi;
    @Mock private LlmClient anthropic;

    @Test
    void shouldRouteMainlandChineseProvidersToOpenAiCompatibleClient() {
        AssistantProperties properties = new AssistantProperties();
        List<String> providers = List.of("glm", "zhipu", "bigmodel", "deepseek", "moonshot",
                "kimi", "qwen", "dashscope", "tongyi", "doubao", "ark", "volcengine",
                "minimax", "siliconflow", "openai-compatible");
        for (String provider : providers) {
            properties.getModel().setProvider(provider);
            assertThat(new LlmClientSelector(openAi, anthropic, properties).current())
                    .as(provider)
                    .contains(openAi);
        }
    }

    @Test
    void shouldRouteAnthropicToAnthropicClient() {
        AssistantProperties properties = new AssistantProperties();
        properties.getModel().setProvider("anthropic");
        assertThat(new LlmClientSelector(openAi, anthropic, properties).current())
                .contains(anthropic);
    }
}
