package com.keevo.admin.module.adapter.in.rest.dto;

import com.keevo.shared.module.ModuleLifecycle;

import java.time.Instant;

/**
 * One module of a tenant for the super-admin console (Story 13.8a): effective state now
 * ({@code ENABLED|READ_ONLY|DISABLED}), stored status ({@code null}: no row), dates, provisioned version; Story 16.4:
 * {@code origin} of the stored row ({@code PLAN} offer, {@code ADMIN} super-admin, {@code SYSTEM} platform;
 * {@code null}: no row, included).
 */
public record TenantModuleDto(String moduleId, String displayName, String state, String status, Instant expiresAt,
                              Instant graceUntil, String provisionedVersion, String origin) {

    public static TenantModuleDto from(ModuleLifecycle.TenantModuleView view) {
        return new TenantModuleDto(view.moduleId(), view.displayName(), view.state().name(),
                view.status() != null ? view.status().name() : null, view.expiresAt(), view.graceUntil(),
                view.provisionedVersion(), view.origin());
    }
}
