// observed graph 2026-10-29 (12.5); catalog/crm edges narrowed to named interfaces in 12.2a; identity/store/subscription in 12.2d — tightened in 12.2x
// 12.6a: notification (WhatsApp/push) and entitlement (plan, limits, plan history) contracts are core capabilities (shared.capability.*) — no edge to messaging/subscription for them.
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"catalog :: api", "shared", "store :: api", "subscription :: api"}
)
package com.keevo.inventory;
