package com.keevo.shared.module;

import java.util.UUID;

/**
 * Story 19.13 — the owner's activation of the modules of their plan (« Paramètres › Mes modules »), apart from the
 * entitlement ({@code tenant_modules}): a module is usable only when the plan includes it AND the owner did not
 * disable it. An owner-disabled module is {@link ModuleState#DISABLED} for every gate (REST, sync push / pull,
 * {@link ModuleScoped} listeners, scheduler gates); its data is kept and comes back when it is re-activated.
 */
public interface OwnerModuleActivation {

    /**
     * Activates or disables {@code moduleId} for the tenant, serialized per tenant. Rules:
     * <ul>
     *   <li>unknown or {@code platform} module → {@code MODULE_NOT_FOUND} (404);</li>
     *   <li>module the owner may not toggle ({@link ModuleManifest#ownerCanToggle()}) → {@code MODULE_NOT_TOGGLEABLE}
     *       (409);</li>
     *   <li>module outside the plan → {@code MODULE_NOT_IN_PLAN} (409, « Mon plan »);</li>
     *   <li>same value → no-op ({@code false}: nothing written, no audit);</li>
     *   <li>disable while an activated, in-plan module requires it → {@code MODULE_HAS_DEPENDENTS} (409);</li>
     *   <li>activate while a module it requires is owner-disabled → {@code MODULE_REQUIREMENT_DISABLED} (409).</li>
     * </ul>
     *
     * @return {@code true} when the choice changed (saved and audited)
     */
    boolean setActivation(String tenantSchema, String moduleId, boolean activated, UUID actorId);
}
