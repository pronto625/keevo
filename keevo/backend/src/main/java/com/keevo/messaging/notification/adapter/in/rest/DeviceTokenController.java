package com.keevo.messaging.notification.adapter.in.rest;

import com.keevo.messaging.notification.adapter.in.rest.dto.DeleteDeviceTokenRequestDto;
import com.keevo.messaging.notification.adapter.in.rest.dto.RegisterDeviceTokenRequestDto;
import com.keevo.messaging.notification.domain.port.in.DeleteDeviceTokenUseCase;
import com.keevo.messaging.notification.domain.port.in.RegisterDeviceTokenCommand;
import com.keevo.messaging.notification.domain.port.in.RegisterDeviceTokenUseCase;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Device Tokens", description = "FCM device token management")
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceTokenController {

    private final RegisterDeviceTokenUseCase registerUseCase;
    private final DeleteDeviceTokenUseCase deleteUseCase;

    public DeviceTokenController(RegisterDeviceTokenUseCase registerUseCase,
                                  DeleteDeviceTokenUseCase deleteUseCase) {
        this.registerUseCase = registerUseCase;
        this.deleteUseCase = deleteUseCase;
    }

    @Operation(summary = "Register FCM device token")
    @PostMapping("/token")
    public ResponseEntity<ApiResponseWrapper<Map<String, Boolean>>> registerToken(
            @Valid @RequestBody RegisterDeviceTokenRequestDto request) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID actorId = (UUID) auth.getPrincipal();
        String role = auth.getAuthorities().stream()
                .findFirst()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.replace("ROLE_", ""))
                .orElse("OWNER");

        RegisterDeviceTokenCommand command = new RegisterDeviceTokenCommand(
                actorId, role, request.token(), request.platform(), request.deviceName());

        registerUseCase.register(command);

        return ResponseEntity.ok(ApiResponseWrapper.ok(Map.of("registered", true)));
    }

    @Operation(summary = "Delete FCM device token (logout cleanup)")
    @DeleteMapping("/token")
    public ResponseEntity<ApiResponseWrapper<Map<String, Boolean>>> deleteToken(
            @Valid @RequestBody DeleteDeviceTokenRequestDto request) {

        boolean deleted = deleteUseCase.delete(request.token());

        return ResponseEntity.ok(ApiResponseWrapper.ok(Map.of("deleted", deleted)));
    }
}
