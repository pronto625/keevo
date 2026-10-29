// observed graph 2026-10-29 (12.5); catalog/crm edges narrowed to named interfaces in 12.2a; identity/store/subscription in 12.2d; messaging/feedback/reporting in 12.2e — tightened in 12.2x
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"catalog :: api", "messaging :: api", "shared", "store :: api", "subscription :: api"}
)
package com.keevo.identity;
