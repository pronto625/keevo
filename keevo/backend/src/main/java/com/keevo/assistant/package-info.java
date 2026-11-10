// Story 21.4 — the « Assistant IA » module (Bref « Bref Point », FUS-FR27 / FR39): saved conversations (own table,
// db/modules/ai-assistant), guided topics served by the AI tools of 21.8 for free, and free-text questions answered
// by a model that calls those tools (never SQL). Depends on the shared core only: every business datum is read through
// shared.ai.AiToolRegistry (the tools carry the REST gates and scopes), the AI quota through
// shared.capability.entitlement.UsageQuotaPort.
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared"}
)
package com.keevo.assistant;
