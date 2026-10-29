// observed graph 2026-10-29 (12.5); catalog/crm edges narrowed in 12.2a, inventory in 12.2b, commerce in 12.2c; identity/store/subscription in 12.2d — tightened in 12.2x
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"catalog :: events", "commerce :: api", "commerce :: events", "identity :: api", "inventory :: events", "shared", "store :: api"}
)
package com.keevo.messaging;
