// 12.2f: shared (the future core) no longer reaches into business-module internals. What remains:
// - identity :: api (identity is the core-to-be — accepted until core is carved out);
// - the events the audit listener consumes, including the enums they carry, reached through ":: events"
//   (inventory MovementType and InventoryScope, identity SectorType);
// - two api types those events carry (commerce DayClosureSummary, store StoreType).
// The event edges move to core.event.catalog in 12.3.
// The list is closed on purpose: subscription, messaging, sync, feedback and admin are deliberately
// absent — any new edge from shared is reported as not-allowed and fails ApplicationModulesBaselineTest.
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN,
    allowedDependencies = {"catalog :: events", "commerce :: api", "commerce :: events", "crm :: events",
        "identity :: api", "identity :: events", "inventory :: events", "reporting :: events", "store :: api",
        "store :: events"}
)
package com.keevo.shared;
