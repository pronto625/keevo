package com.keevo.identity.onboarding.adapter.in.web;

import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * TenantPreferencesController — REST API for tenant preferences and store listing.
 */
@RestController
@RequestMapping("/api/v1/tenant")
@PreAuthorize("hasRole('USER')")
public class TenantPreferencesController {

    private final TenantPreferencesRepository tenantPreferencesRepository;
    private final JdbcTemplate jdbcTemplate;

    public TenantPreferencesController(TenantPreferencesRepository tenantPreferencesRepository,
                                       JdbcTemplate jdbcTemplate) {
        this.tenantPreferencesRepository = tenantPreferencesRepository;
        this.jdbcTemplate = jdbcTemplate;
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

    /**
     * List active stores for the current tenant.
     * GET /api/v1/tenant/stores
     *
     * <p>Uses JdbcTemplate with a fully-qualified schema name so that the query
     * works outside a JPA-managed transaction (no search_path dependency).
     * This lightweight endpoint lets Flutter resolve valid store UUIDs before
     * calling stock entry / adjust endpoints.
     *
     * <p>Epic 3 will supersede this with a full StoreRepository.
     */
    @GetMapping("/stores")
    public ResponseEntity<ApiResponseWrapper<List<StoreDto>>> getStores() {
        String schema = TenantContext.getCurrentTenant();
        List<StoreDto> stores = jdbcTemplate.query(
                "SELECT id::text, name FROM \"" + schema + "\".stores WHERE is_active = TRUE ORDER BY created_at ASC",
                (rs, rowNum) -> new StoreDto(rs.getString("id"), rs.getString("name"))
        );
        return ResponseEntity.ok(ApiResponseWrapper.ok(stores));
    }
}