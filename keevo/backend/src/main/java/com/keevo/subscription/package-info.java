// observed graph 2026-10-29 (12.5, re-derived after stock/contact moves); identity/store/subscription in 12.2d; messaging/feedback/reporting in 12.2e — tightened in 12.2x
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"identity :: api", "messaging :: api", "shared"}
)
package com.keevo.subscription;
