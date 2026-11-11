package com.keevo.messaging.whatsapp.adapter.out.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.messaging.channel.adapter.out.external.ProviderHttp;
import com.keevo.messaging.channel.domain.model.Recipients;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.whatsapp.adapter.out.config.MetaWhatsAppProperties;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Story 19.9b — Meta WhatsApp Cloud API, platform number (port of Bref's {@code WhatsappService.sendTemplate} /
 * {@code sendOtp}): {@code POST https://graph.facebook.com/v21.0/{phoneNumberId}/messages}, Bearer token, {@code to}
 * = digits only, template {@code bref_point_otp} with the code as body parameter and as URL button parameter
 * (button index 0). French first then English when Meta answers error code 132001 (translation missing, read from the JSON error); English only otherwise.
 *
 * <p>Templates only: free text works only inside the 24-hour customer window, so {@link #sendText} never calls Meta.
 * HTTP client {@code channelRestTemplate} (connect 5 s, read 10 s); a read timeout is {@link SendOutcome#UNKNOWN}.
 * Logs carry the HTTP status and the masked phone, never the code, the token or the error body.
 */
@Component
public class MetaWhatsAppProvider implements WhatsAppProvider {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppProvider.class);

    public static final String NAME = "meta";
    static final String URL = "https://graph.facebook.com/v21.0/%s/messages";
    static final int MISSING_TRANSLATION_CODE = 132001;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestTemplate http;
    private final MetaWhatsAppProperties properties;

    public MetaWhatsAppProvider(@Qualifier("channelRestTemplate") RestTemplate http,
                                MetaWhatsAppProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public boolean supportsTemplates() {
        return true;
    }

    /** Never used by the chain (free text is outside an approved template). */
    @Override
    public SendOutcome sendText(String phone, String text) {
        return SendOutcome.FAILED;
    }

    @Override
    public SendOutcome sendCode(String phone, String code, String language) {
        List<String> languages = "fr".equals(language) ? List.of("fr", "en") : List.of("en");
        for (int i = 0; i < languages.size(); i++) {
            String lang = languages.get(i);
            Attempt attempt = post(otpBody(phone, code, lang, properties.otpTemplate()));
            switch (attempt) {
                case SENT -> {
                    return SendOutcome.DELIVERED;
                }
                case UNKNOWN -> {
                    log.warn("[WhatsApp] meta did not answer in time for {} — unknown outcome",
                            Recipients.maskPhone(phone));
                    return SendOutcome.UNKNOWN;
                }
                case MISSING_TRANSLATION -> {
                    if (i == languages.size() - 1) return SendOutcome.FAILED;
                    log.warn("[WhatsApp] meta template {} missing in {} — trying {}", properties.otpTemplate(), lang,
                            languages.get(i + 1));
                }
                default -> {
                    return SendOutcome.FAILED;
                }
            }
        }
        return SendOutcome.FAILED;
    }

    /**
     * Story 19.9c — an approved platform template (single language, no fallback language: the catalogue is French).
     * Logs the template name and the HTTP status, never the parameters.
     */
    @Override
    public SendOutcome sendTemplate(String phone, String template, String language, List<String> parameters) {
        Attempt attempt = post(templateBody(phone, template, language, parameters));
        return switch (attempt) {
            case SENT -> SendOutcome.DELIVERED;
            case UNKNOWN -> {
                log.warn("[WhatsApp] meta did not answer in time for template {} to {} — unknown outcome", template,
                        Recipients.maskPhone(phone));
                yield SendOutcome.UNKNOWN;
            }
            default -> {
                log.warn("[WhatsApp] meta template {} not sent to {}", template, Recipients.maskPhone(phone));
                yield SendOutcome.FAILED;
            }
        };
    }

    /** {@code template{name, language, components:[{type:body, parameters:[{type:text,text}…]}]}}; no component without parameters. */
    static Map<String, Object> templateBody(String phone, String template, String language, List<String> parameters) {
        Map<String, Object> templateNode = new LinkedHashMap<>();
        templateNode.put("name", template);
        templateNode.put("language", Map.of("code", language));
        if (!parameters.isEmpty()) {
            templateNode.put("components", List.of(Map.of("type", "body",
                    "parameters", parameters.stream().map(p -> Map.of("type", "text", "text", p)).toList())));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", digits(phone));
        body.put("type", "template");
        body.put("template", templateNode);
        return body;
    }

    enum Attempt { SENT, FAILED, MISSING_TRANSLATION, UNKNOWN }

    private Attempt post(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(properties.accessToken());
        String url = String.format(URL, properties.phoneNumberId());
        try {
            ResponseEntity<String> response = http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers),
                    String.class);
            if (response.getStatusCode().is2xxSuccessful()) return Attempt.SENT;
            log.warn("[WhatsApp] meta failed (HTTP {})", response.getStatusCode().value());
            return isMissingTranslation(response.getBody()) ? Attempt.MISSING_TRANSLATION : Attempt.FAILED;
        } catch (RestClientResponseException e) {
            log.warn("[WhatsApp] meta failed (HTTP {})", e.getStatusCode().value());
            return isMissingTranslation(e.getResponseBodyAsString()) ? Attempt.MISSING_TRANSLATION : Attempt.FAILED;
        } catch (ResourceAccessException e) {
            if (ProviderHttp.isReadTimeout(e)) return Attempt.UNKNOWN;
            log.warn("[WhatsApp] meta failed (connection error)");
            return Attempt.FAILED;
        } catch (RuntimeException e) {
            log.warn("[WhatsApp] meta failed ({})", e.getClass().getSimpleName());
            return Attempt.FAILED;
        }
    }

    /** Meta's error {@code {"error":{"code":132001,…}}}: the template has no translation in that language. */
    static boolean isMissingTranslation(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) return false;
        try {
            return JSON.readTree(errorBody).path("error").path("code").asInt(-1) == MISSING_TRANSLATION_CODE;
        } catch (Exception e) {
            return false;
        }
    }

    /** Bref's exact component layout of {@code bref_point_otp}: body text = code, URL button 0 = code. */
    static Map<String, Object> otpBody(String phone, String code, String language, String template) {
        Map<String, Object> templateNode = new LinkedHashMap<>();
        templateNode.put("name", template);
        templateNode.put("language", Map.of("code", language));
        templateNode.put("components", List.of(
                Map.of("type", "body",
                        "parameters", List.of(Map.of("type", "text", "text", code))),
                new LinkedHashMap<>(Map.of(
                        "type", "button",
                        "sub_type", "url",
                        "index", "0",
                        "parameters", List.of(Map.of("type", "text", "text", code))))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", digits(phone));
        body.put("type", "template");
        body.put("template", templateNode);
        return body;
    }

    static String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("\\D", "");
    }
}
