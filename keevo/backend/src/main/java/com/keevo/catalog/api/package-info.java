/**
 * Public API of the {@code catalog} module (Story 12.2a).
 *
 * <p>Only query/command ports, immutable records and API enums live here. Implementations are
 * internal adapters ({@code catalog.*.adapter.in.api}) delegating to the existing use cases and
 * repositories. Other modules depend on {@code "catalog :: api"} (its events: {@code shared.event.catalog}, Story 12.3),
 * never on catalog internals.
 *
 * <p>{@link com.keevo.catalog.api.InitialStockPort} works the other way round: a port
 * the catalog <em>requires</em> and the stock-owning module implements (Story 12.2b), so that the
 * CSV import seeds stock without catalog depending on inventory.
 *
 * <p><b>Tenant convention (12.2d, aligned here in 12.2e):</b> every method takes the tenant schema
 * explicitly as its first {@code String tenantSchema} parameter; it must be the schema of the current
 * tenant context ({@code TenantContext.requireCurrent}): null, blank or another schema fails fast
 * ({@link IllegalStateException}). The parameter is only checked: the repositories behind the
 * adapters keep reading the bound tenant. Callers pass the tenant they act for (sync handlers: their
 * {@code tenantId} argument; others: {@code TenantContext.getCurrentTenant()}).
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.catalog.api;
