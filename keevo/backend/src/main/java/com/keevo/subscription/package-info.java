// observed graph 2026-10-29 (12.5, re-derived after stock/contact moves); identity/store/subscription in 12.2d — tightened in 12.2x
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"identity :: api", "messaging", "shared"}
)
package com.keevo.subscription;
