package com.keevo.assistant.chat.adapter.out.model;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.assistant.chat.domain.port.out.AssistantModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;

/**
 * Story 21.4 — chooses the assistant's model provider (strategy) from {@code keevo.ai.provider} and validates it at
 * startup, like Bref's {@code AI_PROVIDER}:
 * <ul>
 *   <li>{@code disabled} (default) — no model: free-text questions answer 503, guided topics work;</li>
 *   <li>{@code fake} — scripted model, under the {@code dev} / {@code test} profiles only (never {@code prod} /
 *       {@code staging}); script {@code keevo.ai.fake-script} (file) or the classpath default;</li>
 *   <li>{@code openai} / {@code gemini} — key {@code KEEVO_AI_API_KEY} required (missing → disabled with a warning,
 *       the application still starts); {@code ollama} — local server without key, Ollama Cloud with one;</li>
 *   <li>{@code anthropic} (21.4.2) — Claude through the official Anthropic SDK, key {@code KEEVO_AI_API_KEY} required
 *       (missing → disabled with a warning), default model {@code claude-haiku-5-5}.</li>
 * </ul>
 * Questions wait {@code keevo.ai.timeout-seconds} (default 45 s) per provider call, with one retry on a transient
 * failure (anthropic: only within the question's deadline); titles wait 10 s without retry.
 * Model ({@code KEEVO_AI_MODEL}) and base URL ({@code KEEVO_AI_BASE_URL}) default per provider (Bref's defaults). The
 * key comes from the environment only and is never logged; errors name the variable, never its value.
 */
@Configuration
public class AssistantModelConfig {

    private static final Logger log = LoggerFactory.getLogger(AssistantModelConfig.class);

    static final String PROVIDER = "keevo.ai.provider";
    static final String DEFAULT_SCRIPT = "keevo/ai/fake-assistant-script.json";
    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 45_000;
    static final String TIMEOUT = "keevo.ai.timeout-seconds";
    static final int TITLE_READ_TIMEOUT_MS = 10_000;

    static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai";
    static final String OLLAMA_LOCAL_BASE_URL = "http://localhost:11434";
    static final String OLLAMA_CLOUD_BASE_URL = "https://ollama.com";

    @Bean
    public AssistantModel assistantModel(Environment env, ObjectMapper mapper) {
        return create(env, mapper);
    }

    /**
     * The resolved connection of an OpenAI-compatible provider ({@code key} never logged).
     *
     * @param completionTokens {@code max_completion_tokens} (OpenAI) rather than {@code max_tokens}
     */
    record Settings(String kind, String baseUrl, String path, String key, String model, boolean completionTokens) {

        @Override
        public String toString() {
            return "Settings[" + kind + ", " + baseUrl + path + ", " + model + "]"; // never the key
        }
    }

    /** The provider of this environment, validated (also used by tests). */
    static AssistantModel create(Environment env, ObjectMapper mapper) {
        String kind = env.getProperty(PROVIDER, "disabled").trim().toLowerCase(Locale.ROOT);
        switch (kind) {
            case "disabled", "" -> {
                log.warn("[AI] assistant model disabled ({}=disabled): free-text questions answer 503", PROVIDER);
                return new DisabledAssistantModel();
            }
            case "fake" -> {
                if (!env.acceptsProfiles(Profiles.of("dev", "test"))
                        || env.acceptsProfiles(Profiles.of("prod", "staging"))) {
                    throw new IllegalStateException(PROVIDER + "=fake is allowed under the dev and test profiles only: "
                            + "set KEEVO_AI_PROVIDER=anthropic, openai, gemini, ollama or disabled");
                }
                log.warn("[AI] assistant model FAKE: scripted answers, no model called");
                return new ScriptedAssistantModel(script(env.getProperty("keevo.ai.fake-script", ""), mapper), mapper);
            }
            case "anthropic" -> {
                String key = env.getProperty("keevo.ai.api-key", "").trim();
                if (key.isEmpty()) {
                    log.warn("[AI] {}=anthropic without KEEVO_AI_API_KEY: assistant model disabled, free-text "
                            + "questions answer 503", PROVIDER);
                    return new DisabledAssistantModel();
                }
                return anthropic(key, or(env.getProperty("keevo.ai.model", "").trim(),
                        AnthropicAssistantModel.DEFAULT_MODEL), env.getProperty("keevo.ai.base-url", "").trim(),
                        readTimeoutMs(env), mapper);
            }
            default -> {
                Settings settings = settings(env);
                if (settings == null) {
                    log.warn("[AI] {}={} without KEEVO_AI_API_KEY: assistant model disabled, free-text questions "
                            + "answer 503", PROVIDER, kind);
                    return new DisabledAssistantModel();
                }
                return openAi(settings, readTimeoutMs(env));
            }
        }
    }

    /**
     * The connection of an OpenAI-compatible provider ({@code openai}, {@code gemini}, {@code ollama}); {@code null}
     * when its key is required and missing. The base URL is a host (a trailing {@code /} or {@code /v1} is removed,
     * the path adds it back).
     *
     * @throws IllegalStateException unknown provider
     */
    static Settings settings(Environment env) {
        String kind = env.getProperty(PROVIDER, "disabled").trim().toLowerCase(Locale.ROOT);
        String key = env.getProperty("keevo.ai.api-key", "").trim();
        String model = env.getProperty("keevo.ai.model", "").trim();
        String baseUrl = env.getProperty("keevo.ai.base-url", "").trim();
        return switch (kind) {
            case "openai" -> key.isEmpty() ? null : new Settings(kind, host(or(baseUrl, "https://api.openai.com")),
                    "/v1/chat/completions", key, or(model, "gpt-4o-mini"), true);
            case "gemini" -> key.isEmpty() ? null : new Settings(kind, trimSlash(or(baseUrl, GEMINI_BASE_URL)),
                    "/chat/completions", key, or(model, "gemini-2.0-flash"), false);
            case "ollama" -> new Settings(kind, host(or(baseUrl, key.isEmpty() ? OLLAMA_LOCAL_BASE_URL
                    : OLLAMA_CLOUD_BASE_URL)), "/v1/chat/completions", key.isEmpty() ? "ollama" : key,
                    or(model, "llama3.2"), false);
            default -> throw new IllegalStateException(PROVIDER + " must be disabled, fake, anthropic, openai, gemini "
                    + "or ollama");
        };
    }

    /** Per provider call of a question: {@code keevo.ai.timeout-seconds} (1..120), default 45 s. */
    static int readTimeoutMs(Environment env) {
        String raw = env.getProperty(TIMEOUT, String.valueOf(READ_TIMEOUT_MS / 1_000)).strip();
        int seconds;
        try {
            seconds = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            seconds = -1;
        }
        if (seconds < 1 || seconds > 120) {
            throw new IllegalStateException(TIMEOUT + " must be a whole number of seconds between 1 and 120");
        }
        return seconds * 1_000;
    }

    /**
     * Claude through the official SDK: one client without the SDK's own retries (the adapter retries once within the
     * question's deadline, so a {@code retry-after} never runs past it); questions wait at most the configured
     * timeout per call, titles 10 s without retry. The key is given explicitly (never read from the SDK's own
     * environment variables); the base URL is a host (a trailing {@code /} or {@code /v1} is removed, the SDK adds the
     * path).
     */
    static AssistantModel anthropic(String key, String model, String baseUrl, int readTimeoutMs, ObjectMapper mapper) {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(key)
                .timeout(Duration.ofMillis(readTimeoutMs)).maxRetries(0);
        if (!baseUrl.isBlank()) {
            b.baseUrl(host(baseUrl));
        }
        AnthropicClient client = b.build();
        log.info("[AI] assistant model provider: anthropic (model {})", model);
        return new AnthropicAssistantModel(client, model, Duration.ofMillis(readTimeoutMs), mapper);
    }

    private static AssistantModel openAi(Settings s, int readTimeoutMs) {
        // questions: one retry on a transient failure (5xx, network), bounded again by the assistant's deadline
        RetryTemplate retry = RetryTemplate.builder().maxAttempts(2).fixedBackoff(1_500)
                .retryOn(TransientAiException.class).retryOn(ResourceAccessException.class).build();
        OpenAiChatModel chat = chatModel(s, readTimeoutMs, retry);
        // titles: short timeout, no retry (a title falls back to the first words of the question)
        OpenAiChatModel titles = chatModel(s, TITLE_READ_TIMEOUT_MS, RetryTemplate.builder().maxAttempts(1).build());
        log.info("[AI] assistant model provider: {} (model {})", s.kind(), s.model());
        return new OpenAiCompatibleAssistantModel(chat, titles, s.kind(), s.model(), s.completionTokens());
    }

    private static OpenAiChatModel chatModel(Settings s, int readTimeoutMs, RetryTemplate retry) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(readTimeoutMs);
        OpenAiApi api = OpenAiApi.builder().baseUrl(s.baseUrl()).completionsPath(s.path()).apiKey(s.key())
                .restClientBuilder(RestClient.builder().requestFactory(factory)).build();
        return OpenAiChatModel.builder().openAiApi(api).retryTemplate(retry).build();
    }

    /** {@code https://api.openai.com/v1/} → {@code https://api.openai.com}. */
    static String host(String url) {
        String u = trimSlash(url);
        return u.endsWith("/v1") ? u.substring(0, u.length() - 3) : u;
    }

    private static String trimSlash(String url) {
        String u = url.strip();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static com.fasterxml.jackson.databind.JsonNode script(String file, ObjectMapper mapper) {
        Resource resource = file.isBlank() ? new ClassPathResource(DEFAULT_SCRIPT) : new FileSystemResource(file);
        try (InputStream in = resource.getInputStream()) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("[AI] fake assistant script unreadable: keevo.ai.fake-script", e);
        }
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
