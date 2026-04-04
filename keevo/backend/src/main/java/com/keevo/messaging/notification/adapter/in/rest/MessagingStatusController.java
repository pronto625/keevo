package com.keevo.messaging.notification.adapter.in.rest;

import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Messaging", description = "Messaging infrastructure status")
@RestController
@RequestMapping("/api/v1/messaging")
public class MessagingStatusController {

    private final String whatsappProvider;
    private final boolean fcmEnabled;

    public MessagingStatusController(
            @Value("${keevo.whatsapp.provider:noop}") String whatsappProvider,
            @Value("${keevo.fcm.enabled:false}") boolean fcmEnabled) {
        this.whatsappProvider = whatsappProvider;
        this.fcmEnabled = fcmEnabled;
    }

    @Operation(summary = "Get messaging infrastructure status")
    @GetMapping("/status")
    public ResponseEntity<ApiResponseWrapper<Map<String, Object>>> getStatus() {
        Map<String, Object> status = Map.of(
                "whatsappProvider", whatsappProvider,
                "fcmEnabled", fcmEnabled);
        return ResponseEntity.ok(ApiResponseWrapper.ok(status));
    }
}
