// Story 14.1 — the « Facturation » module: standalone (requires core only); depends on the shared core only —
// never on pos / crm / catalog code (enhancements arrive through capabilities and catalogue events, 14.3/14.4).
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared"}
)
package com.keevo.invoicing;
