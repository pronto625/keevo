package com.keevo.shared.module;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Which modules are installed, and which are active for a tenant (Story 13.2, spec-socle §5.1, MOD-FR1).
 *
 * <p>A tenant is identified by its schema name ({@code kv_xxxxxx}, the identity carried by the JWT and
 * {@code TenantContext}); an invalid schema name throws {@link IllegalArgumentException}
 * ({@code TenantSchema.validate}). Effective state of a module for a tenant:
 * <ol>
 *   <li>a module that is not {@linkplain #installed() installed} (unknown id, or a catalogue row whose manifest
 *       left the classpath) is never active;</li>
 *   <li>{@code core} is always active (it never enters the lifecycle), whatever {@code tenant_modules} says;</li>
 *   <li>when a {@code public.tenant_modules} row exists for (tenant, module): active ⇔ status ∈ {TRIAL, ACTIVE};</li>
 *   <li>when no row exists: active ⇔ the manifest is {@code free}.</li>
 * </ol>
 * An unknown tenant (no {@code public.tenants} row, or a {@code DELETED} one) only has {@code core} active. A
 * platform-admin session ({@code TenantSchema.PLATFORM_ADMIN_SCHEMA}, {@code "public"}) is not a tenant: it gets
 * {@code core} + the installed modules of category {@code platform}, without any query. The returned sets are
 * sorted, immutable, and answer {@code false} to {@code contains(null)}.
 *
 * <p>Within one HTTP request the tenant's rows are read at most once (request-scoped cache); outside a request
 * (schedulers, async listeners, boot) every call reads the database. A caller that changes a tenant's modules
 * within a request calls {@link #evict(String)} so the next read sees the change. Read-only otherwise: activation
 * is Story 13.8.
 */
public interface ModuleRegistry {

    /** Every module installed in this build (the validated manifests on the classpath), sorted by id. */
    Collection<ModuleManifest> installed();

    /** The manifest of an installed module; empty for an unknown or uninstalled id. */
    Optional<ModuleManifest> definition(String moduleId);

    /** Ids of the modules active for the tenant (always contains {@code core}). */
    Set<String> activeModules(String tenantSchema);

    /** Whether {@code moduleId} is active for the tenant ({@code false} for an unknown module id). */
    boolean isActive(String tenantSchema, String moduleId);

    /**
     * Drops the tenant's cached active modules of the current request (no-op without a request, for the platform
     * schema or an invalid schema string), so the next call reads the database again.
     */
    void evict(String tenantSchema);
}
