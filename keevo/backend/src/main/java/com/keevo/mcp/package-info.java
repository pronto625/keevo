// Story 21.9 — the « Accès IA externe » module (mcp-access, paid, FUS-FR39): an MCP server (Streamable HTTP,
// stateless, POST /api/v1/mcp) over the read-only AI tools of 21.8, for the AI client of the owner's choice, and the
// owner's personal access tokens that authenticate it (create / list / revoke, scoped to tools, expiring, hashed).
// Depends on the shared core only: data through shared.ai.AiToolRegistry (the tools carry the REST gates and scopes),
// the session through the shared security SPI (CredentialAuthenticator), the AI quota through
// shared.capability.entitlement.UsageQuotaPort.
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared"}
)
package com.keevo.mcp;
