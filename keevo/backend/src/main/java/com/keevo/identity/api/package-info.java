/**
 * Public API of the {@code identity} module (Story 12.2d): tenants, tenant preferences, users and
 * employees as seen by the other modules.
 *
 * <p>Only query/command ports, immutable records and API enums live here. Implementations are
 * internal, package-private adapters ({@code identity.*.adapter.in.api}) delegating to the existing
 * repositories and use cases; they carry no transaction of their own. Reads and the employee use
 * cases (REQUIRED) join the caller's transaction. Other modules depend on {@code "identity :: api"} (its events: {@code shared.event.catalog}, Story 12.3), never on identity internals.
 *
 * <p><b>Tenant convention (12.2d):</b> every tenant-scoped method takes the tenant schema
 * ({@code kv_xxxxxx}) explicitly as its first {@code String tenantSchema} parameter. Adapters that
 * delegate to tenant-bound JPA repositories require it to be the schema of the current tenant
 * context ({@code TenantContext.requireCurrent}): null, blank or another schema fails fast.
 *
 * <p>{@link com.keevo.identity.api.PlanType} stays a tenant attribute published here until 16.8
 * replaces it with configurable plan profiles.
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.identity.api;
