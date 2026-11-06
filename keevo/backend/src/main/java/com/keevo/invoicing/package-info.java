// Story 14.1 — the « Facturation » module: standalone (requires core only); depends on the shared core only —
// never on pos / crm / catalog code (enhancements arrive through capabilities and catalogue events, 14.3/14.4).
// 14.3: "crm :: api" for the ClientLookupPort capability interface (and its ClientSummary) only — resolved per call
// through CapabilityRegistry.find, never injected; works with crm DISABLED (free-text client).
// 20.5: "store :: api" (core module, always on) for the seller's country — StoreQueryPort, read once per settings
// default (the VAT rate of the country of the oldest active store).
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared", "crm :: api", "store :: api"}
)
package com.keevo.invoicing;
