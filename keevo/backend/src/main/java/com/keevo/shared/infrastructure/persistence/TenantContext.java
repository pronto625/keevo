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
}
