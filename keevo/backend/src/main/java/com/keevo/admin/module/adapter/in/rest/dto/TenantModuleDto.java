package com.keevo.admin.module.adapter.in.rest.dto;

import com.keevo.shared.module.ModuleLifecycle;

import java.time.Instant;

/**
 * One module of a tenant for the super-admin console (Story 13.8a): effective state now
 * ({@code ENABLED|READ_ONLY|DISABLED}), stored status ({@code null}: no row), dates, provisioned version.
 */
public record TenantModuleDto(String moduleId, String displayName, String state, String status, Instant expiresAt,
                              Instant graceUntil, String provisionedVersion) {

    public static TenantModuleDto from(ModuleLifecycle.TenantModuleView view) {
        return new TenantModuleDto(view.moduleId(), view.displayName(), view.state().name(),
                view.status() != null ? view.status().name() : null, view.expiresAt(), view.graceUntil(),
                view.provisionedVersion());
    }
}
