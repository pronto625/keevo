package com.keevo.messaging.whatsapp.adapter.out.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.whatsapp.adapter.out.config.MetaWhatsAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 19.9b — Meta Cloud API template call, Bref's exact body, fr→en on 132001, timeout = unknown. */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("MetaWhatsAppProvider — bref_point_otp template (Story 19.9b)")
class MetaWhatsAppProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PHONE = "+237699000080";
    private static final String CODE = "482913";
    private static final String TOKEN = "EAAGsecretToken123";
    private static final String MISSING = "{\"error\":{\"code\":132001,\"message\":\"Template name does not exist in the translation\"}}";

    @Mock RestTemplate http;
    private MetaWhatsAppProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MetaWhatsAppProvider(http, new MetaWhatsAppProperties(TOKEN, "1098765", null));
    }

    private static HttpClientErrorException badRequest(String body) {
        return HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<HttpEntity> requests(int count) {
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(http, times(count)).exchange(eq("https://graph.facebook.com/v21.0/1098765/messages"),
                eq(HttpMethod.POST), captor.capture(), eq(String.class));
        return captor.getAllValues();
    }

    @Test
    @DisplayName("body is Bref's: to = digits, template bref_point_otp, body text + URL button 0 = code")
    void body() {
        when(http.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"messages\":[{\"id\":\"wamid\"}]}"));

        assertThat(provider.sendCode(PHONE, CODE, "fr")).isEqualTo(SendOutcome.DELIVERED);

        HttpEntity<?> request = requests(1).get(0);
        assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + TOKEN);
        JsonNode body = JSON.valueToTree(request.getBody());
        JsonNode expected = JSON.valueToTree(Map.of(
                "messaging_product", "whatsapp",
                "to", "237699000080",
                "type", "template",
                "template", Map.of(
                        "name", "bref_point_otp",
                        "language", Map.of("code", "fr"),
                        "components", List.of(
                                Map.of("type", "body", "parameters", List.of(Map.of("type", "text", "text", CODE))),
                                Map.of("type", "button", "sub_type", "url", "index", "0",
                                        "parameters", List.of(Map.of("type", "text", "text", CODE)))))));
        assertThat(body).isEqualTo(expected);
    }

    @Test
    @DisplayName("132001 in fr: retried in en")
    void missingTranslationRetriesEnglish() {
        when(http.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(badRequest(MISSING))
                .thenReturn(ResponseEntity.ok("{}"));

        assertThat(provider.sendCode(PHONE, CODE, "fr")).isEqualTo(SendOutcome.DELIVERED);

        List<HttpEntity> requests = requests(2);
        assertThat(JSON.valueToTree(requests.get(0).getBody()).at("/template/language/code").asText()).isEqualTo("fr");
        assertThat(JSON.valueToTree(requests.get(1).getBody()).at("/template/language/code").asText()).isEqualTo("en");
    }

    @Test
    @DisplayName("132001 in fr then in en: failed (the chain falls back)")
    void missingBoth() {
        when(http.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(badRequest(MISSING));
        assertThat(provider.sendCode(PHONE, CODE, "fr")).isEqualTo(SendOutcome.FAILED);
        requests(2);
    }

    @Test
    @DisplayName("English: en only, no fr attempt; 132001 in en = failed")
    void englishOnly() {
        when(http.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(badRequest(MISSING));
        assertThat(provider.sendCode(PHONE, CODE, "en")).isEqualTo(SendOutcome.FAILED);
        assertThat(JSON.valueToTree(requests(1).get(0).getBody()).at("/template/language/code").asText())
                .isEqualTo("en");
    }

    @Test
    @DisplayName("other 400 / 500: failed, no retry; WARN without code nor token")
    void otherErrors(CapturedOutput output) {
        when(http.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(badRequest("{\"error\":{\"code\":131026}}"))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(provider.sendCode(PHONE, CODE, "fr")).isEqualTo(SendOutcome.FAILED);
        assertThat(provider.sendCode(PHONE, CODE, "fr")).isEqualTo(SendOutcome.FAILED);
        requests(2);
        assertThat(output.getAll()).contains("HTTP 400").contains("HTTP 500")
                .doesNotContain(CODE).doesNotContain(TOKEN).doesNotContain("699000080");
    }

    @Test
    @DisplayName("missing translation read from the JSON error code only: 132001 elsewhere in the body is not it")
    void missingTranslationParsing() {
        assertThat(MetaWhatsAppProvider.isMissingTranslation(MISSING)).isTrue();
        assertThat(MetaWhatsAppProvider.isMissingTranslation(
                "{\"error\":{\"code\":131026,\"message\":\"ref 132001 does not exist in fr\"}}")).isFalse();
        assertThat(MetaWhatsAppProvider.isMissingTranslation("132001")).isFalse();
        assertThat(MetaWhatsAppProvider.isMissingTranslation(null)).isFalse();

        when(http.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(badRequest("{\"error\":{\"code\":100,\"message\":\"param 132001\"}}"));
        assertThat(provider.sendCode(PHONE, CODE, "fr")).isEqualTo(SendOutcome.FAILED);
        requests(1); // no English retry
    }

    @Test
    @DisplayName("read timeout: unknown outcome (no fallback); connect failure: failed")
    void timeouts() {
        when(http.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("I/O", new SocketTimeoutException("Read timed out")))
                .thenThrow(new ResourceAccessException("I/O", new ConnectException("Connection refused")));
        assertThat(provider.sendCode(PHONE, CODE, "fr")).isEqualTo(SendOutcome.UNKNOWN);
        assertThat(provider.sendCode(PHONE, CODE, "fr")).isEqualTo(SendOutcome.FAILED);
    }

    @Test
    @DisplayName("templates only: free text never calls Meta; configured with token + phone number id")
    void templatesOnly() {
        assertThat(provider.supportsTemplates()).isTrue();
        assertThat(provider.sendText(PHONE, "x")).isEqualTo(SendOutcome.FAILED);
        verify(http, never()).exchange(anyString(), any(), any(), eq(String.class));
        assertThat(provider.isConfigured()).isTrue();
        assertThat(new MetaWhatsAppProvider(http, new MetaWhatsAppProperties(TOKEN, " ", null)).isConfigured())
                .isFalse();
    }
}
