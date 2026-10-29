// observed graph 2026-10-29 (12.5); catalog/crm edges narrowed in 12.2a, inventory edges in 12.2b — tightened in 12.2x
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"crm :: api", "identity", "messaging", "shared", "store", "subscription"}
)
package com.keevo.catalog;
