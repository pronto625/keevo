// observed graph 2026-10-29 (12.5, re-derived after stock/contact moves); commerce edges narrowed in 12.2c; identity/store/subscription in 12.2d — tightened in 12.2x
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"commerce :: api", "commerce :: events", "identity :: api", "messaging", "shared", "store :: api", "subscription :: api"}
)
package com.keevo.reporting;
