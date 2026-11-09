package com.keevo.payments.appstore.adapter.in.rest;

import com.keevo.payments.appstore.application.service.AppStoreWebhookService;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Story 21.2 — RevenueCat webhook, JWT-free ({@code SecurityConfig.PUBLIC_PATHS} {@code /api/v1/payments/webhook/**}):
 * the {@code Authorization} header (the secret set in the RevenueCat dashboard) authenticates it. 200
 * {@code {"status":"ok"}} when processed, ignored or already processed; 401 when the secret is wrong (nothing read);
 * 503 when RevenueCat must deliver again (TRANSFER while the REST client is unavailable, unexpected error — the
 * transaction rolled back, so the event is processed again).
 */
@Hidden
@EntitlementExempt("rappel du fournisseur de paiement — public, secret dans l'en-tête Authorization")
@RestController
@RequestMapping("/api/v1/payments/webhook")
public class RevenueCatWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RevenueCatWebhookController.class);

    private final AppStoreWebhookService webhooks;

    public RevenueCatWebhookController(AppStoreWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/revenuecat")
    public ResponseEntity<Map<String, String>> revenueCat(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            return switch (webhooks.handle(authorization, body)) {
                case OK -> ResponseEntity.ok(Map.of("status", "ok"));
                case UNAUTHORIZED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status",
                        "unauthorized"));
                case RETRY -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "retry"));
            };
        } catch (RuntimeException e) {
            log.error("RevenueCat webhook failed: {}", e.getClass().getSimpleName(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "retry"));
        }
    }
}
