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
 * tenant ({@code public.tenant_modules}, {@link com.keevo.shared.module.TenantModuleStatus}). The
 * {@code DependencyResolver}, activation and entitlement gating come with Stories 13.3+.
 */
package com.keevo.shared.module;
