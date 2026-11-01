package com.keevo.shared.module;

import java.util.List;
import java.util.UUID;

/**
 * Story 16.2 — super-admin bundles (MOD-FR12, decision D1): a bundle is a named list of module ids, nothing more. The
 * module engine stays 100 % à-la-carte: applying a bundle to a tenant is one call of
 * {@link ModuleLifecycle#activateModules} over its modules (one activation plan, one topological order), and leaves
 * only per-module {@code tenant_modules} rows — never a bundle state on the tenant. Deleting a bundle removes its
 * definition only; tenants keep their modules.
 *
 * <p>Refusals are {@code DomainException}s with a French message: {@code BUNDLE_NOT_FOUND} (404, « Pack
 * introuvable »), {@code BUNDLE_INVALID} (422, the reason names modules by display name; nothing saved),
 * {@code BUNDLE_NOT_APPLICABLE} (422, a stored bundle holds a module no longer available; nothing written), and the
 * refusals of {@link ModuleLifecycle#activateModules} on apply.
 */
public interface BundleAdmin {

    /** Every bundle, by name, with its modules (display names) in bundle order. */
    List<BundleView> list();

    /**
     * Creates or replaces a bundle.
     *
     * @param bundleId    kebab-case slug ({@code pack-pro})
     * @param name        French display name, required
     * @param description optional ({@code null} or blank: none)
     * @param modules     at least one installed module that is neither core nor platform, no duplicates
     */
    SaveResult save(String bundleId, String name, String description, List<String> modules, UUID actorId);

    /** Removes the bundle definition ({@code BUNDLE_NOT_FOUND} when there is none); tenants keep their modules. */
    void delete(String bundleId, UUID actorId);

    /**
     * Applies the bundle to a tenant: one activation of all its modules with the request's status and end date
     * (paid modules), free modules following the free rule.
     */
    ApplyResult apply(UUID tenantId, String bundleId, ModuleLifecycle.ActivationRequest request,
                      ModuleLifecycle.Actor actor);

    /**
     * A bundle: id, French name, optional description, modules in bundle order, and whether it can still be applied
     * ({@code false} when a module is no longer installed or became core / platform: « Module indisponible »).
     */
    record BundleView(String id, String name, String description, List<ModuleLifecycle.ModuleRef> modules,
                      boolean applicable) {

        public BundleView {
            modules = List.copyOf(modules);
        }
    }

    /** Outcome of a save: the bundle as stored, and whether it was created (else replaced). */
    record SaveResult(BundleView bundle, boolean created) {
    }

    /**
     * Outcome of an application.
     *
     * @param name          the bundle's French name
     * @param activated     the modules written by the call, in activation order (dependencies first)
     * @param alreadyActive the bundle's modules that needed no change
     */
    record ApplyResult(String name, List<ModuleLifecycle.ModuleRef> activated,
                       List<ModuleLifecycle.ModuleRef> alreadyActive) {

        public ApplyResult {
            activated = List.copyOf(activated);
            alreadyActive = List.copyOf(alreadyActive);
        }
    }
}
