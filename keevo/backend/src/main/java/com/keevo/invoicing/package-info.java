// Story 14.1 — the « Facturation » module: standalone (requires core only); depends on the shared core only —
// never on pos / crm / catalog code (enhancements arrive through capabilities and catalogue events, 14.3/14.4).
// 14.3: "crm :: api" for the ClientLookupPort capability interface (and its ClientSummary) only — resolved per call
// through CapabilityRegistry.find, never injected; works with crm DISABLED (free-text client).
// 20.5: "store :: api" (core module, always on) for the seller's country — StoreQueryPort, read once per settings
// default (the VAT rate of the country of the oldest active store).
// 20.6: "catalog :: api" for the ProductLookupPort capability (document → catalogue products), resolved per call
// through CapabilityRegistry.find like ClientLookupPort; "identity :: api" for TenantSaleFlowQueryPort (the default of
// the derive-from-documents preference follows the tenant's sale flow). Neither module depends on invoicing.
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared", "crm :: api", "store :: api", "catalog :: api", "identity :: api"}
)
package com.keevo.invoicing;
