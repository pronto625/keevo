// 12.2f: shared (the future core) no longer reaches into business-module internals.
// 12.3: the inter-module event contracts live in shared.event.catalog (the future core.event.catalog) and carry
// catalogue-level types only (MovementKind, SectorKind, InventoryScopeKind, StoreKind, DayClosureSummary), so the
// former "X :: events", "commerce :: api" and "store :: api" entries are gone. What remains:
// - identity :: api (identity is the core-to-be — accepted until core is carved out; also PlanType carried by the
//   catalogue plan events, and — 12.6a — by the entitlement capability: shared.capability.entitlement.PlanView and
//   PlanChangeRecorder carry identity.api.PlanType, accepted until 16.8 introduces configurable plan profiles).
// The list is closed on purpose: any new edge from shared is reported as not-allowed and fails
// ApplicationModulesBaselineTest.
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN,
    allowedDependencies = {"identity :: api"}
)
package com.keevo.shared;
