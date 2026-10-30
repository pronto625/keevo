package com.keevo.admin.platform.adapter.in.rest;

import com.keevo.admin.platform.adapter.in.rest.dto.HealthStatusDto;
import com.keevo.admin.platform.adapter.in.rest.dto.SystemHealthDto;
import com.keevo.admin.platform.domain.port.in.GetSystemHealthUseCase;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminHealthController — « Santé système » (Story 9-5, FR83).
 *
 * <p>Inherits the {@code /api/v1/admin/**} {@code SUPER_ADMIN} filter from SecurityConfig, plus the
 * class-level {@code @PreAuthorize} and an explicit {@link CurrentActor#requireSuperAdmin()} per method.
 */
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/health")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminHealthController {

    private final GetSystemHealthUseCase healthUseCase;

    public AdminHealthController(GetSystemHealthUseCase healthUseCase) {
        this.healthUseCase = healthUseCase;
    }

    /** Live metrics with thresholds; answers 200 even when the database check fails. */
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<SystemHealthDto>> health() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(SystemHealthDto.from(healthUseCase.health())));
    }

    /** Cheap incident flag for the banner (reuses a computation of the last 30 s). */
    @GetMapping("/status")
    public ResponseEntity<ApiResponseWrapper<HealthStatusDto>> status() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(HealthStatusDto.from(healthUseCase.recentHealth())));
    }
}
