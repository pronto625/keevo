package com.keevo.shared.module;

import com.keevo.identity.api.Role;

import java.util.Optional;
import java.util.Set;

/**
 * The code-side contract of a module (Story 13.9, spec-socle §4.1, MOD-FR1): lifecycle hooks called by the
 * {@link ModuleLifecycle} for one tenant, and the programmatic registration of the module's capabilities and
 * extension contributions.
 *
 * <p><b>The manifest stays JSON-only</b> ({@code keevo/modules/<id>/module.json}): this interface has no
 * {@code manifest()} — {@link ModuleRegistry#definition(String) ModuleRegistry.definition(id())} gives it, so a
 * manifest built in Java can never bypass the loader's validation. A module is a singleton Spring bean; at most one
 * bean per {@link #id()}, which must be an installed manifest (checked at boot, the boot fails otherwise). A manifest
 * may have no bean: modules not converted yet keep working through annotations and state-driven gates.
 *
 * <p><b>Hooks</b> run inside the lifecycle call's transaction, under its per-tenant lock: {@link #onActivate} after
 * the module's row is written and its tables provisioned — only when it goes from inactive to active —,
 * {@link #onDeactivate} after the {@code DISABLED} write. An exception rolls the call's {@code tenant_modules} writes
 * back, but not DDL already committed by the module's migrations (Flyway runs on its own connection). They must be idempotent (a reactivation calls {@code onActivate} again) and
 * never delete data (a deactivated module keeps its tables and rows for life).
 *
 * <p><b>Registration</b> ({@link #registerCapabilities}, {@link #registerExtensions}) is called once at boot by the
 * core registries; what is registered is validated like the annotations of Stories 13.6/13.7 (manifest
 * {@code provides} / {@code extensionPoints.fills} cross-check, duplicates) and resolved per tenant. The
 * implementations handed over are plain objects, not beans: nobody can inject them and skip the tenant check.
 */
public interface KeevoModule {

    /** The module id: the {@code id} of its installed manifest. */
    String id();

    /** The module was activated for a tenant (row written, tables provisioned). Idempotent seeds only. */
    default void onActivate(ModuleTenantContext ctx) {
    }

    /** The module was deactivated for a tenant ({@code DISABLED} written). Data is kept. */
    default void onDeactivate(ModuleTenantContext ctx) {
    }

    /**
     * Story 14.1 / 19.5 — the code-side view of the permissions this module grants to a membership role
     * ({@code MANAGER}, {@code EMPLOYEE}, {@code VIEWER}). The declaration itself lives in the manifest
     * ({@code rolePermissions}, one explicit entry per role); a module that also states it in code returns the same set
     * here — checked at boot by {@code ModulePermissions}, which fails on any difference. {@code null} (the default):
     * no code-side statement, the manifest alone applies. {@code OWNER} holds every permission of every module,
     * {@code SUPER_ADMIN} none (it is not a tenant user).
     */
    default Set<String> permissionsFor(Role role) {
        return null;
    }

    /** Registers the capabilities listed in the manifest {@code provides}. Called once at boot. */
    default void registerCapabilities(CapabilityRegistrar registrar) {
    }

    /** Registers the contributions to the points listed in the manifest {@code extensionPoints.fills}. Once, at boot. */
    default void registerExtensions(ExtensionRegistrar registrar) {
    }
}
