// observed graph 2026-10-29 (12.5, re-derived after stock/contact moves); identity/store/subscription in 12.2d; messaging/feedback/reporting in 12.2e — tightened in 12.2x
// 12.6a: notification (WhatsApp/push) and entitlement (plan, limits, plan history) contracts are core capabilities (shared.capability.*) — no edge to messaging/subscription for them.
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"crm :: api", "expenses :: api", "feedback :: api", "identity :: api", "reporting :: revenue", "shared",
        "store :: api", "subscription :: api"}
)
// 21.6: "store :: api" = the store activities (StoreCategory) a platform campaign segment names.
// 20.13: "reporting :: revenue" = the 20.9 revenue SQL (RevenueRelations — 20-14 moved only the port to
// shared.capability.revenue, the SQL stays in reporting), read by admin.platformrevenue only;
// "expenses :: api" = expense category / payment labels of the cross-account expense list; "crm :: api" = customer
// writes of the super-admin (decision A1).
package com.keevo.admin;
