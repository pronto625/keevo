package com.keevo.shared.domain.port.out;

/**
 * Deletes every file a module stores for one tenant (product photos…) outside the database.
 * Called by the account deletion (Story 14.5 / epic 8 « uploaded photos ») once the tenant
 * schema is dropped. Implementations are idempotent and never throw: a failure is logged and
 * the next call retries it.
 */
public interface TenantFilesPurger {

    /** Delete all files of {@code tenantSchema}; a tenant without files is a no-op. */
    void purgeTenant(String tenantSchema);
}
