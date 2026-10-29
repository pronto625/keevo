/**
 * Public API of the {@code reporting} module (Story 12.2e) — <b>intentionally empty</b>.
 *
 * <p>This named interface is a boundary pin: reporting is in the published-module architecture
 * assertion, so any other module reaching into reporting internals fails it. No business module
 * calls reporting today. The only former consumer, identity's onboarding « test report », goes
 * through the inverted port {@code identity.api.DailyReportTextPort}, declared by identity and
 * implemented by reporting ({@code reporting.report.adapter.in.api}) — reporting already depends on
 * {@code identity :: api}, so this keeps the graph free of an {@code identity ↔ reporting} cycle.
 * Future reporting ports and records are published here.
 *
 * <p><b>Tenant convention (12.2d/12.2e):</b> tenant-scoped methods take {@code String tenantSchema}
 * first and require it to be the schema of the current tenant context
 * ({@code TenantContext.requireCurrent}).
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.reporting.api;
