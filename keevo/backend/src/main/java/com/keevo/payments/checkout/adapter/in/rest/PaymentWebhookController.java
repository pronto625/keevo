package com.keevo.payments.checkout.adapter.in.rest;

import com.keevo.payments.checkout.application.service.PaymentWebhookService;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Story 21.1 — MyCoolPay callback, JWT-free ({@code SecurityConfig.PUBLIC_PATHS}): the secret path segment and the
 * signature authenticate it ({@link PaymentWebhookService}). Plain-text answer as Bref's: « OK » (processed or
 * ignored), « KO » (refused — HTTP 200, as MyCoolPay expects), HTTP 503 « KO » when the provider could not be reached
 * or an unexpected error happened, so the callback is delivered again.
 */
@Hidden
@EntitlementExempt("rappel du fournisseur de paiement — public, secret d'URL et signature")
@RestController
@RequestMapping("/api/v1/payments/webhook")
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final PaymentWebhookService webhooks;

    public PaymentWebhookController(PaymentWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping(value = "/mycoolpay/{hash}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> myCoolPay(@PathVariable String hash,
                                            @RequestBody(required = false) Map<String, Object> payload) {
        try {
            return switch (webhooks.handle(hash, payload)) {
                case ACCEPTED, IGNORED -> ResponseEntity.ok("OK");
                case REJECTED -> ResponseEntity.ok("KO");
                case RETRY -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("KO");
            };
        } catch (RuntimeException e) {
            log.error("Payment webhook failed: {}", e.getClass().getSimpleName(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("KO");
        }
    }
}
