/**
 * Public API of the {@code subscription} module (Story 12.2d): the admin plan changes
 * ({@link com.keevo.subscription.api.PlanAdministrationPort}) and the product-count SPI.
 *
 * <p>Only ports, immutable records and API enums live here. Implementations are internal,
 * package-private adapters ({@code subscription.plan.adapter.in.api}) delegating to the existing
 * services and repositories. {@code PlanAdministrationPort} documents its own propagation (activation
 * REQUIRES_NEW; plan read / change MANDATORY). Other modules depend on {@code "subscription :: api"} (its
 * events: {@code shared.event.catalog}, Story 12.3), never on subscription internals.
 *
 * <p>Story 12.6a: the tenant's current plan, the plan-limit checks and the plan-change recorder are core
 * capabilities ({@code com.keevo.shared.capability.entitlement}), implemented here — the modules that create
 * stores, products or employees no longer depend on subscription.
 *
 * <p>{@link com.keevo.subscription.api.ProductCountPort} works the other way round — <em>required</em>
 * by subscription and implemented by catalog, the owner of products (same pattern as
 * {@code catalog.api.InitialStockPort}, 12.2b).
 *
 * <p><b>Tenant convention (12.2d):</b> every tenant-scoped method takes the tenant schema
 * explicitly as its first {@code String tenantSchema} parameter.
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.subscription.api;
