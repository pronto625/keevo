// observed graph 2026-10-29 (12.5, re-derived after stock/contact moves); identity/store/subscription in 12.2d; messaging/feedback/reporting in 12.2e — tightened in 12.2x
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"feedback :: api", "identity :: api", "messaging :: api", "shared", "subscription :: api", "subscription :: events"}
)
package com.keevo.admin;
