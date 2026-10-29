/**
 * Public API of the {@code inventory} module (Story 12.2b): stock, transfers and counting.
 *
 * <p>Only query/command ports, immutable records and API enums live here. Implementations are
 * internal adapters ({@code inventory.*.adapter.in.api}) delegating to the existing services and
 * use cases; they carry no transaction of their own, so a port call joins the caller's
 * transaction (a sale and its stock movements stay atomic). Other modules depend on
 * {@code "inventory :: api"} (and {@code "inventory :: events"}), never on inventory internals.
 *
 * <p><b>Tenant convention (12.2d, aligned here in 12.2e):</b> every method takes the tenant schema
 * explicitly as its first {@code String tenantSchema} parameter; it must be the schema of the current
 * tenant context ({@code TenantContext.requireCurrent}): null, blank or another schema fails fast
 * ({@link IllegalStateException}). The parameter is only checked: the repositories behind the
 * adapters keep reading the bound tenant. Callers pass the tenant they act for (sync handlers: their
 * {@code tenantId} argument; others: {@code TenantContext.getCurrentTenant()}).
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.inventory.api;
