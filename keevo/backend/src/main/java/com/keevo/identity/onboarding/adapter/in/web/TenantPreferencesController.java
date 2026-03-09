package com.keevo.identity.onboarding.adapter.in.web;

import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TenantPreferencesController — REST API for tenant preferences.
 */
@RestController
@RequestMapping("/api/v1/tenant")
@PreAuthorize("hasRole('USER')")
public class TenantPreferencesController {

    private final TenantPreferencesRepository tenantPreferencesRepository;

    public TenantPreferencesController(TenantPreferencesRepository tenantPreferencesRepository) {
        this.tenantPreferencesRepository = tenantPreferencesRepository;
    }

    /**
     * Get current tenant preferences including sector type.
     * GET /api/v1/tenant/preferences
     */
    @GetMapping("/preferences")
    public ResponseEntity<ApiResponseWrapper<TenantPreferencesResponseDto>> getPreferences() {
        return tenantPreferencesRepository.findByCurrentTenant()
            .map(prefs -> {
                TenantPreferencesResponseDto dto = TenantPreferencesResponseDto.fromDomain(prefs);
                return ResponseEntity.ok(ApiResponseWrapper.ok(dto));
            })
            .orElse(ResponseEntity.notFound().build());
    }
}