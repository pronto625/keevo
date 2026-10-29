/**
 * Public API of the {@code commerce} module (Story 12.2c): sales and day closures.
 *
 * <p>Only query/command ports, immutable records and API enums live here (plus the
 * {@link com.keevo.commerce.api.ClosureWindow} value object reporting builds its windows with).
 * Implementations are internal adapters ({@code commerce.sale.adapter.in.api}) delegating to the
 * existing use cases and repositories; they carry no transaction of their own. The use cases are
 * {@code @Transactional} REQUIRED: a port call joins the caller's transaction when there is one
 * (the sync push's per-op transaction) and opens one otherwise, so a sale and its stock movements,
 * or a closure, commit or roll back together. Other modules
 * depend on {@code "commerce :: api"} (and {@code "commerce :: events"}), never on commerce
 * internals.
 *
 * <p><b>Tenant convention (12.2d, aligned here in 12.2e):</b> every method takes the tenant schema
 * explicitly as its first {@code String tenantSchema} parameter; it must be the schema of the current
 * tenant context ({@code TenantContext.requireCurrent}): null, blank or another schema fails fast
 * ({@link IllegalStateException}). The parameter is only checked: the repositories behind the
 * adapters keep reading the bound tenant. Callers pass the tenant they act for (sync handlers: their
 * {@code tenantId} argument; others: {@code TenantContext.getCurrentTenant()}).
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.commerce.api;
