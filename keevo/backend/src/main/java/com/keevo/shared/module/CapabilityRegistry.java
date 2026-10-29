package com.keevo.shared.module;

import java.util.Optional;

/**
 * Tenant-aware resolution of optional capabilities between modules (Story 13.6, spec-socle §6, MOD-FR9,
 * MOD-NFR10).
 *
 * <p>{@link #find} returns the implementation of a {@link Capability} type iff its provider module (the
 * {@link ProvidesCapability#module()} of the bean) is usable for the tenant — its {@link ModuleState} through
 * {@link ModuleRegistry#moduleState} is ENABLED or READ_ONLY (capabilities are lookups; a capability that writes
 * must itself call {@link EntitlementService#assertWritable}). An absent provider (none in this build) or a module
 * off for the tenant (DISABLED, unknown tenant) gives {@code Optional.empty()} so the consumer degrades;
 * infrastructure errors (e.g. the database being unreachable while the module state is read) propagate. The
 * platform-admin schema ({@code "public"}) is not a tenant: no capability. An invalid schema string throws
 * {@link IllegalArgumentException} (the 13.2 convention).
 *
 * <p>Cheap: the module state is read through the registry's request cache; no transaction is opened. Usable
 * at any time: the providers are collected and validated once, after the singletons are created or at the first
 * call if earlier. Registration is declarative only ({@link ProvidesCapability}); a programmatic
 * {@code provide} may come with the {@code KeevoModule} lifecycle (13.9).
 */
public interface CapabilityRegistry {

    /** The implementation of {@code type} for the tenant, if its provider module is usable for it. */
    <T extends Capability> Optional<T> find(Class<T> type, String tenantSchema);

    /** Whether {@link #find} would return an implementation. */
    default boolean has(Class<? extends Capability> type, String tenantSchema) {
        return find(type, tenantSchema).isPresent();
    }
}
