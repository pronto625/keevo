// observed graph 2026-10-29 (12.5); catalog/crm edges narrowed to named interfaces in 12.2a; identity/store/subscription in 12.2d; messaging/feedback/reporting in 12.2e — tightened in 12.2x
// 12.6a: notification (WhatsApp/push) and entitlement (plan, limits, plan history) contracts are core capabilities (shared.capability.*) — no edge to messaging/subscription for them.
// 12.6a: onboarding category seeding is a TenantOnboardedEvent consumed by catalog — no edge to catalog.
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared", "store :: api"}
)
package com.keevo.identity;
