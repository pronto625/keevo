package com.keevo.identity.auth.domain.model;

/**
 * TenantStatus — Lifecycle states of a tenant.
 *
 * <p>Pure Java enum — NO framework imports.
 */
public enum TenantStatus {
    ACTIVE,
    /** Tenant has requested account deletion; data retained for grace period (Story 8-5). */
    DELETION_PENDING,
    SUSPENDED,
    DELETED
}
