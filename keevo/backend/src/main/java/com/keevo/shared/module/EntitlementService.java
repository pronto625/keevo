package com.keevo.shared.module;

import java.util.Set;

/**
 * Whether a tenant may use a module — the entitlement check of the core (Story 13.5, spec-socle §9.1, MOD-FR14).
 * Source of truth: {@code public.tenant_modules}, through the same effective {@link ModuleState} as
 * {@link ModuleRegistry} (one computation, evaluated in real time with the injected {@code Clock}: an expired
 * subscription is refused at the next call, without waiting for a cron) — except for the platform-admin schema,
 * see below.
 *
 * <p>A tenant is identified by its schema name ({@code kv_xxxxxx}, the 13.2 convention); an invalid schema string
 * throws {@link IllegalArgumentException}. The platform-admin schema ({@code "public"}) gets every installed module
 * ENABLED: the super-admin is not gated by subscriptions. An unknown module id is DISABLED.
 *
 * <p>Tenant status is not checked here: a SUSPENDED or DELETION_PENDING tenant is already refused at
 * authentication / by the write guards before any entitlement check runs.
 */
public interface EntitlementService {

    /** The effective state of {@code moduleId} for the tenant, evaluated now. */
    ModuleState state(String tenantSchema, String moduleId);

    /** Whether the module can be used at all (ENABLED or READ_ONLY). */
    boolean isEnabled(String tenantSchema, String moduleId);

    /** Whether the module is in its read-only grace (subscription expired, {@code grace_until} in the future). */
    boolean isReadOnly(String tenantSchema, String moduleId);

    /**
     * Refuses a module that cannot be used at all.
     *
     * @throws com.keevo.shared.domain.exception.DomainException {@code MODULE_NOT_ENTITLED} when DISABLED
     */
    void assertEnabled(String tenantSchema, String moduleId);

    /**
     * Refuses a write to a module that is not fully enabled.
     *
     * @throws com.keevo.shared.domain.exception.DomainException {@code MODULE_NOT_ENTITLED} when DISABLED,
     *         {@code MODULE_READ_ONLY} when READ_ONLY
     */
    void assertWritable(String tenantSchema, String moduleId);

    /** Ids of the modules the tenant can use (ENABLED or READ_ONLY), sorted, immutable; always contains core. */
    Set<String> enabledModules(String tenantSchema);
}
