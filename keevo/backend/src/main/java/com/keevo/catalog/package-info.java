// observed graph 2026-10-29 (12.5); catalog/crm edges narrowed in 12.2a, inventory edges in 12.2b; identity/store/subscription in 12.2d; messaging/feedback/reporting in 12.2e — tightened in 12.2x
// 12.6a: notification (WhatsApp/push) and entitlement (plan, limits, plan history) contracts are core capabilities (shared.capability.*) — no edge to messaging/subscription for them.
// 12.6a: draft tracking via catalogue events (DraftProductPending/Validated) and onboarding seeding via TenantOnboardedEvent — no edge to messaging; drafts counter endpoint served by messaging.
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"crm :: api", "shared", "store :: api", "subscription :: api"}
)
package com.keevo.catalog;
