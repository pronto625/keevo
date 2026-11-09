// Story 21.1 — module « payments »: self-service plan payment of the owner (Mobile Money through MyCoolPay first;
// StoreKit / RevenueCat (21.2), account credit and SMS packs (21.3) next). Platform billing data only (public tables
// payment_requests and the tenant_payments ledger), no tenant DDL, no module manifest (paying is open to every owner,
// whatever the plan). Prices come from the offer catalogue (subscription :: api OfferPricing), the plan is applied
// through the 9-2 unit of work (subscription :: api PlanAdministrationPort), the company through identity :: api.
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared", "identity :: api", "subscription :: api"}
)
package com.keevo.payments;
