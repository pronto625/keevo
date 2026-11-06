// observed graph 2026-10-29 (12.5, re-derived after stock/contact moves) — tightened in 12.2x
// 20.2: store :: api — clients per store (ADR-08): visible store set and store of a new client
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared", "store :: api"}
)
package com.keevo.crm;
