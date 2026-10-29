/**
 * Public API of the {@code crm} module — clients and suppliers (Story 12.2a).
 *
 * <p>Only query/command ports and immutable records live here. Implementations are internal
 * adapters ({@code crm.contact.adapter.in.api}) delegating to the existing use cases and
 * repositories. Other modules depend on {@code "crm :: api"} (and {@code "crm :: events"}).
 *
 * <p><b>Tenant convention (12.2d, aligned here in 12.2e):</b> every method takes the tenant schema
 * explicitly as its first {@code String tenantSchema} parameter; it must be the schema of the current
 * tenant context ({@code TenantContext.requireCurrent}): null, blank or another schema fails fast
 * ({@link IllegalStateException}). The parameter is only checked: the repositories behind the
 * adapters keep reading the bound tenant. Callers pass the tenant they act for (sync handlers: their
 * {@code tenantId} argument; others: {@code TenantContext.getCurrentTenant()}).
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.crm.api;
