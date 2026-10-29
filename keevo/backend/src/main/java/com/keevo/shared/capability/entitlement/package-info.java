/**
 * Entitlement capability of the core (Story 12.6a, spec-socle §9): the tenant's current plan
 * ({@link com.keevo.shared.capability.entitlement.TenantPlanQuery} / {@link com.keevo.shared.capability.entitlement.PlanView}),
 * the plan-limit checks ({@link com.keevo.shared.capability.entitlement.PlanLimitCheck}) and the global plan
 * history recorded at registration ({@link com.keevo.shared.capability.entitlement.PlanChangeRecorder} /
 * {@link com.keevo.shared.capability.entitlement.PlanChangeCause}).
 *
 * <p>Implemented by {@code subscription} (package-private adapters, no transaction of their own: a plan read
 * and a limit check run in the caller's transaction, before its guarded write; the recorder joins the
 * caller's transaction). For the plan and its limits, modules creating stores, products or employees depend
 * on {@code shared} only, so subscription — which reads identity — no longer closes a cycle with them. Formerly
 * {@code subscription.api.PlanLimitPort} / {@code SubscriptionQueryPort} / {@code PlanView} /
 * {@code PlanChangeRecorder} / {@code PlanChangeCause}.
 *
 * <p>Not everything moved: the product count SPI ({@code subscription.api.ProductCountPort}) stays declared by
 * subscription and implemented by catalog, so catalog and inventory still depend on {@code subscription :: api}
 * for it. That edge points from the provider of the count to subscription and creates no cycle.
 *
 * <p>{@link com.keevo.shared.capability.entitlement.PlanView} and
 * {@link com.keevo.shared.capability.entitlement.PlanChangeRecorder} carry {@code identity.api.PlanType}: plan
 * limits still come from that enum until 16.8 (configurable plan profiles) — the {@code shared → identity :: api}
 * edge is accepted until then.
 *
 * <p><b>Tenant convention:</b> every tenant-scoped method takes the tenant schema explicitly as its first
 * {@code String tenantSchema} parameter.
 */
package com.keevo.shared.capability.entitlement;
