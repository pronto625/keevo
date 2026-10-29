package com.keevo.shared.module;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which modules are installed, and which are active for a tenant (Story 13.2, spec-socle §5.1, MOD-FR1); since
 * Story 13.5, the effective {@link ModuleState} of each module.
 *
 * <p>A tenant is identified by its schema name ({@code kv_xxxxxx}, the identity carried by the JWT and
 * {@code TenantContext}); an invalid schema name throws {@link IllegalArgumentException}
 * ({@code TenantSchema.validate}). The effective state is computed in one place
 * ({@code shared.module.application.ModuleStateRules}) in real time with the injected {@code Clock}:
 * <ol>
 *   <li>a module that is not {@linkplain #installed() installed} (unknown id, or a catalogue row whose manifest
 *       left the classpath) is always {@link ModuleState#DISABLED};</li>
 *   <li>{@code core} is always {@link ModuleState#ENABLED} (it never enters the lifecycle), whatever
 *       {@code tenant_modules} says;</li>
 *   <li>a TRIAL/ACTIVE row is ENABLED until {@code expires_at}, then READ_ONLY while {@code grace_until} is in the
 *       future, then DISABLED; an EXPIRED row is READ_ONLY during its grace, else DISABLED; a DISABLED/AVAILABLE
 *       row is DISABLED;</li>
 *   <li>when no row exists: ENABLED ⇔ the manifest is {@code free}.</li>
 * </ol>
 * A module is <em>active</em> ({@link #activeModules}, {@link #isActive}) when its state is not DISABLED: a
 * read-only module stays active so its data remains visible (and still satisfies a {@code requires}); whether it
 * accepts writes is {@link EntitlementService}'s question. An unknown tenant (no {@code public.tenants} row, or a
 * {@code DELETED} one) only has {@code core}. A platform-admin session ({@code TenantSchema.PLATFORM_ADMIN_SCHEMA},
 * {@code "public"}) is not a tenant: it gets {@code core} + the installed modules of category {@code platform},
 * without any query. The returned collections are sorted, immutable, and answer {@code false} to
 * {@code contains(null)}.
 *
 * <p>Within one HTTP request the tenant's rows are read at most once (request-scoped cache, the state is
 * recomputed from the cached rows at each call); outside a request (schedulers, async listeners, boot) every call
 * reads the database. A caller that changes a tenant's modules within a request calls {@link #evict(String)} so
 * the next read sees the change. Read-only otherwise: activation is Story 13.8.
 */
public interface ModuleRegistry {

    /** Every module installed in this build (the validated manifests on the classpath), sorted by id. */
    Collection<ModuleManifest> installed();

    /** The manifest of an installed module; empty for an unknown or uninstalled id. */
    Optional<ModuleManifest> definition(String moduleId);

    /** Ids of the modules active (state not DISABLED) for the tenant (always contains {@code core}). */
    Set<String> activeModules(String tenantSchema);

    /**
     * Whether the schema names a registered, non-{@code DELETED} tenant (Story 12.7: tells an unknown tenant, which
     * only has {@code core}, from a tenant whose modules are off). Same query and request cache as
     * {@link #activeModules(String)}; {@code false} for the platform-admin schema; invalid schema strings throw.
     */
    boolean isKnownTenant(String tenantSchema);

    /** Whether {@code moduleId} is active (state not DISABLED) for the tenant ({@code false} for an unknown id). */
    boolean isActive(String tenantSchema, String moduleId);

    /**
     * The effective state of {@code moduleId} for the tenant, evaluated now (Story 13.5); DISABLED for an unknown
     * or uninstalled module id (including {@code null}).
     */
    ModuleState moduleState(String tenantSchema, String moduleId);

    /**
     * The effective state of every installed module for the tenant, evaluated now (Story 13.5): an immutable map
     * sorted by module id, one entry per {@linkplain #installed() installed} module.
     */
    Map<String, ModuleState> moduleStates(String tenantSchema);

    /**
     * Drops the tenant's cached active modules of the current request (no-op without a request, for the platform
     * schema or an invalid schema string), so the next call reads the database again.
     */
    void evict(String tenantSchema);
}
