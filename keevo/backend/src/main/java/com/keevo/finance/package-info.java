// Story 20.11 — the « Finance » module (Bref finance summary, FUS-FR26): premium, no table. Reads the revenue through
// the single revenue source (core capability shared.capability.revenue, Story 20.14 — one adapter), the expense
// totals through "expenses :: api" and the store names through "store :: api".
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared", "store :: api", "expenses :: api"}
)
package com.keevo.finance;
