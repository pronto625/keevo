package com.keevo.shared.infrastructure.persistence;

/**
 * TenantContext — ThreadLocal-based multi-tenant context holder.
 *
 * <p>Set by the JWT auth filter for each incoming request.
 * MUST be cleared after the request completes (call {@link #clear()}).
 *
 * <p>Pattern: Factory/Singleton per request thread.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void setCurrentTenant(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getCurrentTenant() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }

    /**
     * Tenant convention of the published module APIs (Story 12.2d): a tenant-scoped port method
     * receives the tenant schema explicitly, while the tenant-bound JPA repositories behind it read
     * this context. Both must agree — anything else would silently read another (or no) tenant.
     *
     * @throws IllegalStateException if {@code tenantSchema} is null/blank or is not the current tenant
     */
    public static void requireCurrent(String tenantSchema) {
        if (tenantSchema == null || tenantSchema.isBlank()) {
            throw new IllegalStateException("tenantSchema is required");
        }
        if (!tenantSchema.equals(CURRENT_TENANT.get())) {
            throw new IllegalStateException("tenantSchema does not match the current tenant context");
        }
    }
}
