package com.keevo.messaging.notification.adapter.in.rest;

import com.keevo.messaging.notification.adapter.in.rest.dto.WebPushSubscribeRequestDto;
import com.keevo.messaging.notification.adapter.in.rest.dto.WebPushUnsubscribeRequestDto;
import com.keevo.messaging.notification.domain.port.in.WebPushSubscriptionUseCase;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Story 19.9b — browser notifications (Web Push, port of Bref's {@code web-push.controller.ts}): the VAPID public key
 * (public), subscribe / unsubscribe of the signed-in user's browser (any role). Not gated by a module: a browser
 * receives the notifications of every company of its user.
 */
@Tag(name = "Web Push", description = "Notifications du navigateur (Web Push)")
@EntitlementExempt("notifications du navigateur — compte")
@RestController
@RequestMapping("/api/v1/web-push")
public class WebPushController {

    private final WebPushSubscriptionUseCase useCase;

    public WebPushController(WebPushSubscriptionUseCase useCase) {
        this.useCase = useCase;
    }

    @Operation(summary = "VAPID public key (empty when Web Push is disabled)")
    @GetMapping("/vapid-public-key")
    public Map<String, String> vapidPublicKey() {
        return Map.of("publicKey", useCase.vapidPublicKey());
    }

    @Operation(summary = "Link this browser to the signed-in user (upsert by endpoint)")
    @ApiResponse(responseCode = "204", description = "Linked")
    @ApiResponse(responseCode = "409", description = "PUSH_ENDPOINT_OWNED — endpoint linked to another account")
    @ApiResponse(responseCode = "422", description = "VALIDATION_FAILED — endpoint outside the browser push services")
    @PostMapping("/subscribe")
    public ResponseEntity<Void> subscribe(@RequestBody WebPushSubscribeRequestDto request) {
        useCase.subscribe(CurrentActor.actorId(), request.endpoint(), request.p256dh(), request.auth(),
                request.userAgent());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Unlink this browser from the signed-in user")
    @ApiResponse(responseCode = "204", description = "Unlinked (or was not linked)")
    @DeleteMapping("/subscribe")
    public ResponseEntity<Void> unsubscribe(@RequestBody WebPushUnsubscribeRequestDto request) {
        useCase.unsubscribe(CurrentActor.actorId(), request.endpoint());
        return ResponseEntity.noContent().build();
    }
}
