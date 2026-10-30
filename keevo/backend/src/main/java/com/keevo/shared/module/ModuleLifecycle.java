package com.keevo.shared.module;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Turns a module on or off for a tenant (Story 13.8a, spec-socle §10, MOD-FR2/3/16). The single writer of
 * {@code public.tenant_modules} status: each call runs in one transaction under a per-tenant lock — plan
 * ({@link DependencyResolver}) → row writes → provisioning ({@link ModuleProvisioner}) → registry cache eviction
 * → audit ({@code MODULE_ACTIVATED} / {@code MODULE_DEACTIVATED}, after commit).
 *
 * <p>Refusals are {@code DomainException}s with a French message: {@code TENANT_NOT_FOUND},
 * {@code MODULE_NOT_FOUND}, {@code MODULE_ACTIVATION_BLOCKED}, {@code MODULE_HAS_DEPENDENTS},
 * {@code CORE_MODULE_REQUIRED}, {@code MODULE_PROVISIONING_FAILED} (whole transaction rolled back),
 * {@code VALIDATION_ERROR} for a bad request. Modules are named by their display name in every message and audit
 * detail, never by id.
 */
public interface ModuleLifecycle {

    /**
     * Activates {@code moduleId} for the tenant, with its missing hard dependencies first. A super-admin actor
     * grants paid modules (every plan step is eligible); any other actor only gets free ones. An already enabled
     * target with nothing to plan is an idempotent success (its row is refreshed only when the request changes its
     * status or expiry).
     */
    ActivationResult activate(UUID tenantId, String moduleId, ActivationRequest request, Actor actor);

    /**
     * Deactivates {@code moduleId} for the tenant: its row becomes {@code DISABLED} with {@code disabled_at}; tables
     * and data are kept. Refused for the core and when active modules require it. Already disabled → idempotent.
     */
    DeactivationResult deactivate(UUID tenantId, String moduleId, Actor actor);

    /** The installed modules a tenant can be given (every category but {@code platform}), sorted by id. */
    List<ModuleManifest> catalogue();

    /** The state of every tenant module of the tenant targeted by id ({@code TENANT_NOT_FOUND} otherwise). */
    List<TenantModuleView> tenantModules(UUID tenantId);

    /** The state of every tenant module of the tenant with this schema (the current tenant of an owner). */
    List<TenantModuleView> tenantModules(String tenantSchema);

    /**
     * What to activate.
     *
     * @param status    {@code ACTIVE} (default when {@code null}) or {@code TRIAL}
     * @param expiresAt end of the trial / subscription, in the future; {@code null} = no end
     */
    record ActivationRequest(TenantModuleStatus status, Instant expiresAt) {

        /** {@code ACTIVE}, no expiry. */
        public static ActivationRequest active() {
            return new ActivationRequest(TenantModuleStatus.ACTIVE, null);
        }
    }

    /**
     * Who acts.
     *
     * @param userId     the actor's user id (audit)
     * @param superAdmin whether the actor is a super-admin (grants paid modules)
     */
    record Actor(UUID userId, boolean superAdmin) {

        public Actor {
            Objects.requireNonNull(userId, "userId");
        }
    }

    /** A module named for people. */
    record ModuleRef(String moduleId, String displayName) {
    }

    /**
     * Outcome of an activation.
     *
     * @param activated          the modules written by this call, in activation order (dependencies first);
     *                           empty for an idempotent call
     * @param status             the target's stored status ({@code ACTIVE} for a free module without a row)
     * @param expiresAt          the target's end of subscription ({@code null}: none)
     * @param provisionedVersion the target's provisioned version ({@code null}: none, e.g. no migrations)
     */
    record ActivationResult(List<ModuleRef> activated, TenantModuleStatus status, Instant expiresAt,
                            String provisionedVersion) {

        public ActivationResult {
            activated = List.copyOf(activated);
        }
    }

    /** Outcome of a deactivation: the module and its stored status ({@code DISABLED}). */
    record DeactivationResult(String moduleId, String displayName, TenantModuleStatus status) {
    }

    /**
     * One module of a tenant.
     *
     * @param state              effective state now ({@link ModuleRegistry#moduleState})
     * @param status             stored status ({@code null}: no row)
     * @param provisionedVersion stored provisioned version ({@code null}: none)
     */
    record TenantModuleView(String moduleId, String displayName, ModuleState state, TenantModuleStatus status,
                            Instant expiresAt, Instant graceUntil, String provisionedVersion) {
    }
}
