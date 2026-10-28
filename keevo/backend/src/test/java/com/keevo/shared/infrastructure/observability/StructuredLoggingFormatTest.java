package com.keevo.shared.infrastructure.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StructuredLoggingFormatTest — Story 11.1: prod/staging console lines are logstash JSON with the
 * agreed key names, and dev/test keep the human-readable pattern.
 *
 * <p>Encodes a real event with Spring Boot's own {@link StructuredLogEncoder}, configured from the
 * actual {@code application-<profile>.yml} on the classpath — the test breaks if the yml drifts.
 */
@DisplayName("Structured JSON logging (prod/staging)")
class StructuredLoggingFormatTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static StandardEnvironment environmentFor(String... ymlFiles) throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        // Same precedence as Spring Boot: the profile file overrides application.yml.
        for (String yml : ymlFiles) {
            List<PropertySource<?>> sources = loader.load(yml, new ClassPathResource(yml));
            sources.forEach(env.getPropertySources()::addFirst);
        }
        return env;
    }

    private static JsonNode encode(StandardEnvironment env, Map<String, String> mdc) throws Exception {
        LoggerContext context = new LoggerContext();
        context.putObject(Environment.class.getName(), env);
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat(env.getRequiredProperty("logging.structured.format.console"));
        encoder.start();

        LoggingEvent event = new LoggingEvent(
                "fqcn", context.getLogger("com.keevo.sales.SaleService"), Level.INFO,
                "Vente validée {}", null, new Object[] {"V-001"});
        event.setMDCPropertyMap(mdc);

        String line = new String(encoder.encode(event), StandardCharsets.UTF_8);
        assertThat(line).endsWith("\n");
        assertThat(line.strip()).doesNotContain("\n"); // exactly one JSON object per line
        return JSON.readTree(line);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"application-prod.yml", "application-staging.yml"})
    @DisplayName("deployed profile → logstash JSON with timestamp/level/logger/message + MDC keys")
    void deployedProfilesEmitJsonWithAgreedKeys(String profileYml) throws Exception {
        StandardEnvironment env = environmentFor("application.yml", profileYml);
        assertThat(env.getProperty("logging.structured.format.console")).isEqualTo("logstash");

        JsonNode json = encode(env, Map.of(
                "traceId", "abc-123",
                "tenantId", "kv_abc123",
                "userId", "11111111-2222-3333-4444-555555555555"));

        assertThat(json.isObject()).isTrue();
        assertThat(json.path("timestamp").asText()).isNotBlank();
        assertThat(json.path("level").asText()).isEqualTo("INFO");
        assertThat(json.path("logger").asText()).isEqualTo("com.keevo.sales.SaleService");
        assertThat(json.path("message").asText()).isEqualTo("Vente validée V-001");
        assertThat(json.path("traceId").asText()).isEqualTo("abc-123");
        assertThat(json.path("tenantId").asText()).isEqualTo("kv_abc123");
        assertThat(json.path("userId").asText()).isEqualTo("11111111-2222-3333-4444-555555555555");
        // Renamed, not duplicated.
        assertThat(json.has("logger_name")).isFalse();
        assertThat(json.has("@timestamp")).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"application-prod.yml", "application-staging.yml"})
    @DisplayName("unauthenticated line → traceId only, no tenantId/userId")
    void unauthenticatedLineCarriesTraceIdOnly(String profileYml) throws Exception {
        JsonNode json = encode(environmentFor("application.yml", profileYml), Map.of("traceId", "t-1"));

        assertThat(json.path("traceId").asText()).isEqualTo("t-1");
        assertThat(json.has("tenantId")).isFalse();
        assertThat(json.has("userId")).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"application-dev.yml", "application-test.yml"})
    @DisplayName("dev/test profiles keep the human-readable console pattern")
    void devAndTestKeepPlainText(String profileYml) throws Exception {
        StandardEnvironment env = environmentFor("application.yml", profileYml);

        assertThat(env.getProperty("logging.structured.format.console")).isNull();
        if (profileYml.equals("application-dev.yml")) {
            assertThat(env.getProperty("logging.file.name")).isEqualTo("/tmp/keevo-dev.log");
        }
    }
}
