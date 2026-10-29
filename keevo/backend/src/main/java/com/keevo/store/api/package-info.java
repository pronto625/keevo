/**
 * Public API of the {@code store} module (Story 12.2d): the tenant's stores and warehouse.
 *
 * <p>Only query/command ports, immutable records and API enums live here. Implementations are
 * internal, package-private adapters ({@code store.store.adapter.in.api}) delegating to the
 * existing use cases and repository; they carry no transaction of their own. Every call — reads,
 * {@code createWithId} and the update / deactivate use cases (REQUIRED) — joins the caller's
 * transaction. Other modules depend on {@code "store :: api"} (and
 * {@code "store :: events"}), never on store internals.
 *
 * <p><b>Tenant convention (12.2d):</b> every tenant-scoped method takes the tenant schema
 * explicitly as its first {@code String tenantSchema} parameter; it must be the schema of the
 * current tenant context ({@code TenantContext.requireCurrent}): null, blank or another schema fails
 * fast.
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.store.api;
