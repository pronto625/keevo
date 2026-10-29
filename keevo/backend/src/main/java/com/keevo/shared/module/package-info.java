/**
 * Module registry of the core (Story 13.1, spec-socle §2–§3) — published API.
 *
 * <p>{@link com.keevo.shared.module.ModuleManifest} is the validated model of a {@code keevo/modules/<id>/module.json}
 * manifest; {@link com.keevo.shared.module.ModuleManifestLoader} loads them from the classpath and fails fast on an
 * invalid set ({@link com.keevo.shared.module.InvalidModuleManifestException}); the catalogue
 * ({@code public.module_definitions}) is seeded at boot by {@code shared.module.application.ModuleCatalogSeeder}
 * through the {@code shared.module.application.port.ModuleDefinitionPort} port.
 *
 * <p>Since Story 13.2 the validated set is held once by {@code shared.module.application.ModuleCatalog}, and
 * {@link com.keevo.shared.module.ModuleRegistry} answers which modules are installed and which are active for a
 * tenant ({@code public.tenant_modules}, {@link com.keevo.shared.module.TenantModuleStatus}). Since
 * Story 13.3 {@link com.keevo.shared.module.DependencyResolver} plans activations (missing {@code requires} first,
 * each step checked by {@link com.keevo.shared.module.ModuleActivationEligibility}) and checks deactivations
 * (blocked by active dependents). Since Story 13.5 the effective {@link com.keevo.shared.module.ModuleState} of a
 * module (real-time expiry, read-only grace) is computed once and shared by the registry and
 * {@link com.keevo.shared.module.EntitlementService}; {@link com.keevo.shared.module.RequiresEntitlement} gates use
 * cases / controllers declaratively. Since Story 13.6 {@link com.keevo.shared.module.CapabilityRegistry} resolves, per
 * tenant, the optional {@link com.keevo.shared.module.Capability capabilities} that provider beans declare with
 * {@link com.keevo.shared.module.ProvidesCapability} (cross-checked with the manifests' {@code provides} at boot).
 * Activation itself comes with Story 13.8.
 */
package com.keevo.shared.module;
