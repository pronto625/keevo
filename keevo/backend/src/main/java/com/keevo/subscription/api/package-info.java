/**
 * Public API of the {@code subscription} module (Story 12.2d): the tenant's current plan, the plan
 * limit checks and the admin plan changes.
 *
 * <p>Only ports, immutable records and API enums live here. Implementations are internal,
 * package-private adapters ({@code subscription.plan.adapter.in.api}) delegating to the existing
 * services and repositories; they carry no transaction of their own, so a plan read and a limit
 * check run in the caller's transaction, before its guarded write. {@code PlanAdministrationPort}
 * documents its own propagation (activation REQUIRES_NEW; plan read / change MANDATORY). Other modules depend on
 * {@code "subscription :: api"} (and {@code "subscription :: events"}), never on subscription
 * internals.
 *
 * <p>{@link com.keevo.subscription.api.ProductCountPort} works the other way round — <em>required</em>
 * by subscription and implemented by catalog, the owner of products (same pattern as
 * {@code catalog.api.InitialStockPort}, 12.2b). {@link com.keevo.subscription.api.PlanChangeRecorder}
 * is an ordinary provided port: implemented by subscription, used by identity at registration.
 *
 * <p><b>Tenant convention (12.2d):</b> every tenant-scoped method takes the tenant schema
 * explicitly as its first {@code String tenantSchema} parameter.
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.subscription.api;
