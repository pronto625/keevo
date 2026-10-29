// observed graph 2026-10-29 (12.5); catalog/crm edges narrowed to named interfaces in 12.2a; identity/store/subscription in 12.2d — tightened in 12.2x
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"catalog :: api", "shared", "store :: api", "subscription :: api"}
)
package com.keevo.inventory;
