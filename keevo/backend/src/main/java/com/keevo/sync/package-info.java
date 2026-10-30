// observed graph 2026-10-29 (12.5); catalog/crm edges narrowed in 12.2a, inventory in 12.2b, commerce in 12.2c; identity/store/subscription in 12.2d; messaging/feedback/reporting in 12.2e — tightened in 12.2x
// 14.3: crm :: api removed — the client/supplier sync handlers live in crm (SPI in shared.application.port.sync)
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"catalog :: api", "commerce :: api", "feedback :: api", "identity :: api", "inventory :: api", "shared", "store :: api"}
)
package com.keevo.sync;
