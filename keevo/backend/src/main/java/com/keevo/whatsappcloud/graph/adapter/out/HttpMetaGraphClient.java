package com.keevo.whatsappcloud.graph.adapter.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.whatsappcloud.graph.domain.model.MetaGraphException;
import com.keevo.whatsappcloud.graph.domain.model.MetaTemplate;
import com.keevo.whatsappcloud.graph.domain.model.PhoneNumberInfo;
import com.keevo.whatsappcloud.graph.domain.model.TemplateDraft;
import com.keevo.whatsappcloud.graph.domain.port.out.MetaGraphClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Story 21.5 — {@link MetaGraphClient} on Meta's Graph API ({@code https://graph.facebook.com/v21.0}, Bref's version),
 * ported from api-bref {@code WhatsAppService}: Bearer token of the company, JSON bodies, the invoice PDF as multipart
 * media, template header examples through the app's resumable upload ({@code /{appId}/uploads}). Timeouts: connect
 * 5 s, read 30 s; a read timeout is {@link MetaGraphException.Kind#UNKNOWN} (maybe done), a connection failure
 * {@link MetaGraphException.Kind#UNAVAILABLE}, a 4xx {@link MetaGraphException.Kind#REFUSED} with Meta's user text, a 5xx or an
 * unreadable 2xx answer {@link MetaGraphException.Kind#UNKNOWN}. Logs carry the operation and the HTTP status / Meta code only — never a token, a number or a text.
 */
public class HttpMetaGraphClient implements MetaGraphClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMetaGraphClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final String appId;
    private final String appSecret;

    public HttpMetaGraphClient(RestClient http, String appId, String appSecret) {
        this.http = http;
        this.appId = appId;
        this.appSecret = appSecret;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String exchangeCode(String code) {
        JsonNode body = call("exchange-code", () -> http.get()
                .uri(b -> b.path("/oauth/access_token").queryParam("client_id", appId)
                        .queryParam("client_secret", appSecret).queryParam("code", code).build())
                .retrieve().body(JsonNode.class));
        String token = body == null ? "" : body.path("access_token").asText("");
        if (token.isBlank()) {
            throw MetaGraphException.refused("exchange-code: no access_token in Meta's answer", null, -1);
        }
        return token;
    }

    @Override
    public PhoneNumberInfo phoneNumber(String wabaId, String phoneNumberId, String token) {
        JsonNode body = call("phone-numbers", () -> http.get()
                .uri(b -> b.path("/{waba}/phone_numbers").queryParam("fields", "id,display_phone_number,verified_name")
                        .queryParam("limit", 100).build(wabaId))
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(JsonNode.class));
        for (JsonNode n : body == null ? JSON.createArrayNode() : body.path("data")) {
            if (phoneNumberId.equals(n.path("id").asText())) {
                return new PhoneNumberInfo(phoneNumberId, text(n, "display_phone_number"), text(n, "verified_name"));
            }
        }
        throw MetaGraphException.refused("phone-numbers: number not in the WABA", null, -1);
    }

    @Override
    public void subscribeApp(String wabaId, String token) {
        call("subscribe-app", () -> http.post().uri("/{waba}/subscribed_apps", wabaId)
                .headers(h -> h.setBearerAuth(token)).retrieve().body(JsonNode.class));
    }

    @Override
    public String sendText(String phoneNumberId, String token, String to, String body) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messaging_product", "whatsapp");
        request.put("recipient_type", "individual");
        request.put("to", to);
        request.put("type", "text");
        request.put("text", Map.of("body", body, "preview_url", false));
        return messageId("send-text", post("/{id}/messages", phoneNumberId, token, request));
    }

    @Override
    public String sendTemplate(String phoneNumberId, String token, String to, String name, String language,
                               List<Map<String, Object>> components) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", name);
        template.put("language", Map.of("code", language));
        template.put("components", components);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messaging_product", "whatsapp");
        request.put("to", to);
        request.put("type", "template");
        request.put("template", template);
        return messageId("send-template", post("/{id}/messages", phoneNumberId, token, request));
    }

    @Override
    public String uploadMedia(String phoneNumberId, String token, byte[] content, String mimeType, String filename) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("messaging_product", "whatsapp");
        form.add("type", mimeType);
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        JsonNode body = call("upload-media", () -> http.post().uri("/{id}/media", phoneNumberId)
                .headers(h -> h.setBearerAuth(token)).contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .retrieve().body(JsonNode.class));
        String id = body == null ? "" : body.path("id").asText("");
        if (id.isBlank()) {
            throw MetaGraphException.refused("upload-media: no id in Meta's answer", null, -1);
        }
        return id;
    }

    @Override
    public String uploadHeaderSample(String token, byte[] content, String mimeType, String filename) {
        JsonNode session = call("upload-session", () -> http.post()
                .uri(b -> b.path("/{app}/uploads").queryParam("file_name", filename)
                        .queryParam("file_length", content.length).queryParam("file_type", mimeType).build(appId))
                .headers(h -> h.setBearerAuth(token)).retrieve().body(JsonNode.class));
        String sessionId = session == null ? "" : session.path("id").asText("");
        if (sessionId.isBlank()) {
            throw MetaGraphException.refused("upload-session: no id in Meta's answer", null, -1);
        }
        JsonNode upload = call("upload-sample", () -> http.post().uri("/" + sessionId)
                .header("Authorization", "OAuth " + token).header("file_offset", "0")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(content)
                .retrieve().body(JsonNode.class));
        String handle = upload == null ? "" : upload.path("h").asText("");
        if (handle.isBlank()) {
            throw MetaGraphException.refused("upload-sample: no handle in Meta's answer", null, -1);
        }
        return handle;
    }

    @Override
    public MetaTemplate createTemplate(String wabaId, String token, TemplateDraft draft) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", draft.name());
        request.put("category", draft.category());
        request.put("language", draft.language());
        request.put("components", components(draft));
        JsonNode body = post("/{id}/message_templates", wabaId, token, request);
        if (body == null) {
            throw MetaGraphException.unknown("create-template: empty answer");
        }
        return new MetaTemplate(body.path("id").asText(null), draft.name(), draft.language(),
                body.path("status").asText("PENDING"), null);
    }

    @Override
    public List<MetaTemplate> listTemplates(String wabaId, String token) {
        JsonNode body = call("list-templates", () -> http.get()
                .uri(b -> b.path("/{waba}/message_templates")
                        .queryParam("fields", "id,name,status,language,rejected_reason,category,components")
                        .queryParam("limit", 200)
                        .build(wabaId))
                .headers(h -> h.setBearerAuth(token)).retrieve().body(JsonNode.class));
        List<MetaTemplate> templates = new ArrayList<>();
        for (JsonNode n : body == null ? JSON.createArrayNode() : body.path("data")) {
            templates.add(template(n));
        }
        return templates;
    }

    @Override
    public void deleteTemplate(String wabaId, String token, String name) {
        call("delete-template", () -> http.delete()
                .uri(b -> b.path("/{waba}/message_templates").queryParam("name", name).build(wabaId))
                .headers(h -> h.setBearerAuth(token)).retrieve().body(JsonNode.class));
    }

    /** One template of Meta's listing (Story 21.6: with its category, header format and body text). */
    static MetaTemplate template(JsonNode n) {
        String reason = text(n, "rejected_reason");
        String header = "NONE";
        String bodyText = null;
        for (JsonNode c : n.path("components")) {
            String type = c.path("type").asText("").toUpperCase(java.util.Locale.ROOT);
            if ("HEADER".equals(type)) {
                header = c.path("format").asText("TEXT").toUpperCase(java.util.Locale.ROOT);
            } else if ("BODY".equals(type)) {
                bodyText = text(c, "text");
            }
        }
        return new MetaTemplate(text(n, "id"), text(n, "name"), text(n, "language"), text(n, "status"),
                reason == null || "NONE".equals(reason) ? null : reason, text(n, "category"), header, bodyText);
    }

    /** Bref's {@code createTemplate} component layout. */
    static List<Map<String, Object>> components(TemplateDraft d) {
        List<Map<String, Object>> components = new ArrayList<>();
        String header = d.headerFormat() == null ? "NONE" : d.headerFormat();
        if ("TEXT".equals(header)) {
            components.add(Map.of("type", "HEADER", "format", "TEXT", "text", d.headerText()));
        } else if (!"NONE".equals(header)) {
            components.add(Map.of("type", "HEADER", "format", header,
                    "example", Map.of("header_handle", List.of(d.headerHandle()))));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "BODY");
        body.put("text", d.bodyText());
        if (!d.bodyExamples().isEmpty()) {
            body.put("example", Map.of("body_text", List.of(d.bodyExamples())));
        }
        components.add(body);
        if (d.footerText() != null && !d.footerText().isBlank()) {
            components.add(Map.of("type", "FOOTER", "text", d.footerText()));
        }
        return components;
    }

    private JsonNode post(String path, String id, String token, Object request) {
        String operation = path.substring(path.lastIndexOf('/') + 1);
        return call(operation, () -> http.post().uri(path, id).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class));
    }

    private static String messageId(String operation, JsonNode body) {
        String id = body == null ? "" : body.path("messages").path(0).path("id").asText("");
        if (id.isBlank()) {
            // accepted without an id: the message may be out, never sent again
            throw MetaGraphException.unknown(operation + ": no message id in Meta's answer");
        }
        return id;
    }

    private static <T> T call(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                // Meta failed on its side after receiving the request: it may have acted (a message may be out)
                log.warn("[WhatsApp Cloud] {}: Meta answered HTTP {} — unknown outcome", operation,
                        e.getStatusCode().value());
                throw MetaGraphException.unknown(operation + ": HTTP " + e.getStatusCode().value());
            }
            MetaGraphException refused = refusal(operation, e.getStatusCode().value(), e.getResponseBodyAsString());
            log.warn("[WhatsApp Cloud] {} refused by Meta (HTTP {}, code {})", operation, e.getStatusCode().value(),
                    refused.metaCode());
            throw refused;
        } catch (ResourceAccessException e) {
            if (isReadTimeout(e)) {
                log.warn("[WhatsApp Cloud] {}: Meta did not answer in time — unknown outcome", operation);
                throw MetaGraphException.unknown(operation + ": read timeout");
            }
            log.warn("[WhatsApp Cloud] {}: Meta unreachable ({})", operation,
                    e.getCause() == null ? "I/O" : e.getCause().getClass().getSimpleName());
            throw MetaGraphException.unavailable(operation + ": Meta unreachable");
        } catch (RestClientException e) {
            // answered 2xx but unreadable: done at Meta, outcome unknown here
            log.warn("[WhatsApp Cloud] {}: unreadable answer from Meta — unknown outcome", operation);
            throw MetaGraphException.unknown(operation + ": unreadable answer");
        }
    }

    static boolean isReadTimeout(ResourceAccessException e) {
        Throwable cause = e.getCause();
        return cause instanceof SocketTimeoutException && cause.getMessage() != null
                && cause.getMessage().toLowerCase(java.util.Locale.ROOT).contains("read");
    }

    /** Meta's error → refusal with its user text (Bref's {@code metaError}). */
    static MetaGraphException refusal(String operation, int httpStatus, String raw) {
        int code = -1;
        String userMessage = null;
        try {
            JsonNode error = JSON.readTree(raw == null ? "" : raw).path("error");
            code = error.path("code").asInt(-1);
            String title = text(error, "error_user_title");
            String msg = text(error, "error_user_msg");
            if (title != null && msg != null) {
                userMessage = title + " : " + msg;
            } else if (msg != null) {
                userMessage = msg;
            } else if (title != null) {
                userMessage = title;
            }
        } catch (Exception ignored) {
            // not JSON: no user text
        }
        return MetaGraphException.refused(operation + ": HTTP " + httpStatus, userMessage, code);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }
}
