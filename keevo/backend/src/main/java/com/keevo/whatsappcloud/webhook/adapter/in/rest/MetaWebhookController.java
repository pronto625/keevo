package com.keevo.whatsappcloud.webhook.adapter.in.rest;

import com.keevo.shared.module.EntitlementExempt;
import com.keevo.whatsappcloud.webhook.application.WhatsAppWebhookService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * Story 21.5 — Meta's WhatsApp webhook of the Keevo app, JWT-free ({@code SecurityConfig.PUBLIC_PATHS}): {@code GET}
 * the subscription check (verify token → the challenge as plain text, else 403), {@code POST} the deliveries
 * authenticated by {@code X-Hub-Signature-256} ({@link WhatsAppWebhookService}). Answers: 200 processed or ignored,
 * 403 bad signature, 413 body over 1 MB, 500 when a company's delivery could not be stored (Meta delivers again).
 */
@Hidden
@EntitlementExempt("webhook de Meta — public, signature X-Hub-Signature-256, routé vers l'entreprise par numéro")
@RestController
@RequestMapping("/api/v1/whatsapp/webhook")
public class MetaWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MetaWebhookController.class);

    static final int MAX_BODY = 1024 * 1024;

    private final WhatsAppWebhookService webhooks;

    public MetaWebhookController(WhatsAppWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                         @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        String answer = webhooks.verify(mode, token, challenge);
        if (answer == null) {
            log.warn("[WhatsApp Cloud] webhook subscription check refused");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_PLAIN).body("Forbidden");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(answer);
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> receive(HttpServletRequest request,
                                          @RequestHeader(name = "X-Hub-Signature-256", required = false)
                                          String signature) {
        byte[] body;
        try {
            body = read(request);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"status\":\"invalid\"}");
        }
        if (body == null) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("{\"status\":\"too_large\"}");
        }
        try {
            return switch (webhooks.handle(body, signature)) {
                case ACCEPTED -> ResponseEntity.ok("{\"status\":\"ok\"}");
                case IGNORED -> ResponseEntity.ok("{\"status\":\"ignored\"}");
                case REJECTED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body("{\"status\":\"forbidden\"}");
                case RETRY -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"status\":\"retry\"}");
            };
        } catch (RuntimeException e) {
            log.error("[WhatsApp Cloud] webhook failed: {}", e.getClass().getSimpleName(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"status\":\"retry\"}");
        }
    }

    /** The raw body (signed bytes), at most {@value #MAX_BODY} bytes; {@code null} when larger. */
    static byte[] read(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY) {
            return null;
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(MAX_BODY + 1);
            return body.length > MAX_BODY ? null : body;
        }
    }
}
