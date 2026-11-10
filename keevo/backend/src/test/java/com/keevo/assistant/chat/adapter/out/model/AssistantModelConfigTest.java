package com.keevo.assistant.chat.adapter.out.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.assistant.chat.domain.port.out.AssistantModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("21.4 — AssistantModelConfig: provider chosen and validated at startup")
class AssistantModelConfigTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private static MockEnvironment env(String provider, String key, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        if (provider != null) {
            env.setProperty("keevo.ai.provider", provider);
        }
        if (key != null) {
            env.setProperty("keevo.ai.api-key", key);
        }
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    @DisplayName("disabled by default; openai / gemini without key → disabled (the application starts)")
    void disabled() {
        assertThat(AssistantModelConfig.create(env(null, null), MAPPER)).isInstanceOf(DisabledAssistantModel.class);
        assertThat(AssistantModelConfig.create(env("openai", ""), MAPPER).available()).isFalse();
        assertThat(AssistantModelConfig.create(env("gemini", null), MAPPER).available()).isFalse();
    }

    @Test
    @DisplayName("openai, gemini with a key and ollama without one → OpenAI-compatible model")
    void providers() {
        assertThat(AssistantModelConfig.create(env("openai", "sk-test"), MAPPER))
                .isInstanceOf(OpenAiCompatibleAssistantModel.class);
        assertThat(AssistantModelConfig.create(env("GEMINI", "g-test"), MAPPER))
                .isInstanceOf(OpenAiCompatibleAssistantModel.class);
        assertThat(AssistantModelConfig.create(env("ollama", null), MAPPER).available()).isTrue();
    }

    @Test
    @DisplayName("resolved connection per provider: base URL (trailing /v1 tolerated), path, default model, token cap")
    void settings() {
        MockEnvironment openai = env("openai", "sk-test");
        openai.setProperty("keevo.ai.base-url", "https://api.openai.com/v1/");
        assertThat(AssistantModelConfig.settings(openai)).isEqualTo(new AssistantModelConfig.Settings("openai",
                "https://api.openai.com", "/v1/chat/completions", "sk-test", "gpt-4o-mini", true));
        assertThat(AssistantModelConfig.settings(openai).toString()).doesNotContain("sk-test");
        assertThat(AssistantModelConfig.settings(env("gemini", "g"))).isEqualTo(new AssistantModelConfig.Settings(
                "gemini", AssistantModelConfig.GEMINI_BASE_URL, "/chat/completions", "g", "gemini-2.0-flash", false));
        assertThat(AssistantModelConfig.settings(env("ollama", null))).isEqualTo(new AssistantModelConfig.Settings(
                "ollama", "http://localhost:11434", "/v1/chat/completions", "ollama", "llama3.2", false));
        MockEnvironment cloud = env("ollama", "o-key");
        cloud.setProperty("keevo.ai.model", "qwen3");
        assertThat(AssistantModelConfig.settings(cloud)).isEqualTo(new AssistantModelConfig.Settings("ollama",
                "https://ollama.com", "/v1/chat/completions", "o-key", "qwen3", false));
        assertThat(AssistantModelConfig.settings(env("openai", null))).isNull();
    }

    @Test
    @DisplayName("anthropic: key required (missing → disabled), Claude Haiku 5.5 by default, model overridable")
    void anthropic() throws Exception {
        assertThat(AssistantModelConfig.create(env("anthropic", ""), MAPPER)).isInstanceOf(DisabledAssistantModel.class);
        AssistantModel haiku = AssistantModelConfig.create(env("ANTHROPIC", "test-key"), MAPPER);
        assertThat(haiku).isInstanceOf(AnthropicAssistantModel.class);
        assertThat(((AnthropicAssistantModel) haiku).model()).isEqualTo("claude-haiku-5-5");
        ((AnthropicAssistantModel) haiku).close();
        MockEnvironment other = env("anthropic", "test-key");
        other.setProperty("keevo.ai.model", "claude-sonnet-5-5");
        other.setProperty("keevo.ai.base-url", "https://proxy.example/");
        AssistantModel sonnet = AssistantModelConfig.create(other, MAPPER);
        assertThat(((AnthropicAssistantModel) sonnet).model()).isEqualTo("claude-sonnet-5-5");
        ((AnthropicAssistantModel) sonnet).close();
    }

    @Test
    @DisplayName("timeout of a question call: keevo.ai.timeout-seconds (default 45 s), bounded 1..120")
    void timeout() {
        assertThat(AssistantModelConfig.readTimeoutMs(env(null, null))).isEqualTo(45_000);
        MockEnvironment shorter = env("anthropic", "k");
        shorter.setProperty("keevo.ai.timeout-seconds", "5");
        assertThat(AssistantModelConfig.readTimeoutMs(shorter)).isEqualTo(5_000);
        shorter.setProperty("keevo.ai.timeout-seconds", "0");
        assertThatThrownBy(() -> AssistantModelConfig.readTimeoutMs(shorter)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keevo.ai.timeout-seconds");
    }

    @Test
    @DisplayName("fake only under dev / test, never with prod or staging; unknown provider refused")
    void fake() {
        assertThat(AssistantModelConfig.create(env("fake", null, "dev"), MAPPER))
                .isInstanceOf(ScriptedAssistantModel.class);
        assertThatThrownBy(() -> AssistantModelConfig.create(env("fake", null, "prod"), MAPPER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("dev and test");
        assertThatThrownBy(() -> AssistantModelConfig.create(env("fake", null, "test", "staging"), MAPPER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AssistantModelConfig.create(env("claude", null), MAPPER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("keevo.ai.provider");
    }
}
