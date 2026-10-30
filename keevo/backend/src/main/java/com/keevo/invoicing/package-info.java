// Story 14.1 — the « Facturation » module: standalone (requires core only); depends on the shared core only —
// never on pos / crm / catalog code (enhancements arrive through capabilities and catalogue events, 14.3/14.4).
// 14.3: "crm :: api" for the ClientLookupPort capability interface (and its ClientSummary) only — resolved per call
// through CapabilityRegistry.find, never injected; works with crm DISABLED (free-text client).
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared", "crm :: api"}
)
package com.keevo.invoicing;
