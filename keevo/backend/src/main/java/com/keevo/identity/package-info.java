// observed graph 2026-10-29 (12.5); catalog/crm edges narrowed to named interfaces in 12.2a — tightened in 12.2x
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"catalog :: api", "messaging", "reporting", "shared", "store", "subscription"}
)
package com.keevo.identity;
