---
title: '21-9 — External MCP server (personal access tokens, paid module mcp-access)'
type: 'feature'
created: '2026-11-10'
status: 'done'
baseline_revision: '12442aa3812d9b37d118b50e7f356d6e339a9393'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-21-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-21-8-couche-outils-ia-mcp.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-21-4-ai-assistant.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** A Premium owner cannot plug their own AI client (Claude, ChatGPT, their accountant's) into Keevo: the 21-8 tool layer is only reachable from the in-app assistant, and nothing authenticates an external client, binds its session, limits it or counts it (two items routed from the 21-8 review).

**Approach:** A new paid module `mcp-access` (package `com.keevo.mcp`) exposing an MCP server over Streamable HTTP (stateless) at `POST /api/v1/mcp`, wired from the MCP Java SDK transport of the Spring AI 1.1.8 line (no starter, no auto-config) with a Keevo handler that serves `initialize` / `ping` / `tools/list` / `tools/call` through `AiToolRegistry` (channel `MCP`). Clients authenticate with owner-created personal access tokens (create / list / revoke, scoped to tools, expiring, stored as SHA-256 only, shown once); a shared credential filter binds the token owner's authentication and tenant for the request exactly like the JWT filter and clears them after; each token and tenant is rate limited and each successful tool call counts one `AI_CREDITS`.

## Boundaries & Constraints

**Always:**
- Only an OWNER creates, lists and revokes tokens (`@PreAuthorize("hasRole('OWNER')")`, `@RequiresEntitlement("mcp-access")` GET → READ, others WRITE). A token acts as its owner and never beyond: effective tools = token scope ∩ `allowedTools()` of the owner's live session (role, permissions, modules evaluated on every request); a tool outside the scope is refused without calling it.
- Every MCP request: token by SHA-256 hash (unique index, one statement), expiry checked, then the 16-11 access state of the owner (membership active, role still OWNER, company not DELETED, `tokens_valid_after` later than the token's creation → refused), foundation ensured, then contexts bound (`TenantContext`, `SecurityContextHolder` with `ROLE_OWNER` + `AuthDetails`, MDC) on the request thread and cleared in `finally`. Revocation deletes the row: the next request is refused on every instance (no cache).
- Module gate on every MCP request: `mcp-access` not entitled → HTTP 403 `MODULE_NOT_ENTITLED` (or `MODULE_DISABLED_BY_OWNER`), French message, no JSON-RPC processing, no data.
- Rate limit per token and per tenant (fixed 1-minute windows, Caffeine, O(1)): over → HTTP 429 `RATE_LIMIT_EXCEEDED` French + `Retry-After`, no data.
- AI quota: `tools/call` checks `AI_CREDITS` (rolled-back increment, exact 19-8 refusal) before calling and consumes 1 credit only when the outcome is OK; refused / invalid / error / out-of-scope calls and `initialize` / `tools/list` / `ping` are free. Quota refused → tool result `isError` with « Quota de votre offre atteint » and nothing called.
- Secrets: token = `kvp_` + 43 base64url chars (32 random bytes, `SecureRandom`); only the hash and the last 4 characters are stored; the clear token is returned once by the creation response and never logged. Hash comparison constant time (`MessageDigest.isEqual`).
- Token lifetime: 7, 30, 90 (default) or 365 days, never unlimited; ≤ 10 tokens per owner per company; name 1–60 chars; scope 1–16 tool names among the owner's allowed tools at creation.
- Additive only (new endpoints, new module, new table); French labels; no technical data in user-facing output (token ids are API handles only, tool labels shown in French).

**Never:** a starter or auto-configuration of Spring AI MCP (fixed server-wide tool list, would not filter per token); SSE sessions or server state between requests; write tools; tokens for MANAGER / EMPLOYEE / VIEWER; storing or logging a clear token; a token that outlives its owner's role, membership or company.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Create token | OWNER, BUSINESS + mcp-access enabled, `{name, tools:[revenue_summary], expiresInDays:30}` | 201 `{id, name, token:"kvp_…", hint, tools[{name,label}], createdAt, expiresAt}`; token shown once | — |
| Create — bad scope | tool unknown / not allowed to the owner / empty / > 16, days not in {7,30,90,365} | 400 `VALIDATION_ERROR` French | nothing stored |
| Create — limit | 10 tokens already | 409 `MCP_TOKEN_LIMIT` | — |
| Member manages tokens | EMPLOYEE | 403 `FORBIDDEN` | — |
| initialize / tools/list | valid token | protocol answer; tools = scope ∩ owner's allowed tools | — |
| tools/call in scope | `revenue_summary` | `CallToolResult` text = tool JSON; audit `MCP`; 1 credit | — |
| tools/call out of scope | tool not in token | `isError` « Cet outil n'est pas autorisé pour cet accès » | no credit, no call |
| Tool refused | owner lost module | `isError` with the REST French message | no credit |
| Quota exhausted | AI_CREDITS used up | `isError` « Quota de votre offre atteint » | tool not called |
| Missing / unknown / expired / revoked token | | 401 `MCP_TOKEN_INVALID` French | no data |
| Owner no longer OWNER, membership off, sessions revoked after creation, company deleted | | 401 (`MCP_TOKEN_INVALID` / `ACCOUNT_INACTIVE`) | no data |
| Plan without mcp-access (FREE) | valid token | 403 `MODULE_NOT_ENTITLED` | no data |
| Rate limit | 31st request / minute on a token | 429 `RATE_LIMIT_EXCEEDED` + `Retry-After` | no data |
| Unknown method | `resources/list` | JSON-RPC error -32601 French | — |

</intent-contract>

## Code Map

- `keevo/backend/pom.xml` -- Spring AI BOM 1.1.8 already imported; add `io.modelcontextprotocol.sdk:mcp-spring-webmvc` + `mcp-json-jackson2` at 0.18.3 (the SDK version `spring-ai-mcp` 1.1.8 pins; not in the BOM), excluding `com.networknt:json-schema-validator` (schema validation unused).
- SDK (read-only): `io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport` (RouterFunction; POST runs `handler.handleRequest(...).block()` on the request thread; requires `Accept: application/json, text/event-stream`), `McpStatelessServerHandler`, `McpSchema` (`InitializeResult`, `ListToolsResult`, `Tool.builder().inputSchema(mapper, json)`, `CallToolRequest`, `CallToolResult.builder()`, `JSONRPCResponse`, `ErrorCodes`, `ProtocolVersions`), `io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper`.
- `shared/infrastructure/security/JwtAuthFilter.java` -- reference for the session shape and checks (access state, tva, DELETED, platform schema, foundation, MDC, finally clear); `errorBody(code)`; `shouldNotFilter` gains the alternate-credential attribute.
- `shared/infrastructure/security/SecurityConfig.java:162` -- adds the credential filter before `jwtAuthFilter`.
- `shared/infrastructure/security/access/AccessStateService.get`, `AccessState` -- 16-11 state (cached ≤ 30 s, evicted cluster-wide on change).
- `shared/module/EntitlementService.assertEnabled` -- `MODULE_NOT_ENTITLED` / `MODULE_DISABLED_BY_OWNER` refusal with French message; `ModuleProvisioner.ensureFoundation`.
- `shared/ai/AiToolRegistry` (`allowedTools()`, `call(name, json, MCP)`), `AiToolDescriptor.inputSchema` -- the only data access; audit `public.ai_tool_calls` channel `MCP` already allowed by V64.
- `assistant/chat/application/service/AssistantChatService.java:285-320` -- quota pattern (rolled-back check, consume in own tx, race → deliver uncounted); `UsageQuotaPort`, `UsageMetric.AI_CREDITS`.
- `identity/appversion/application/service/AppVersionCheckRateLimiter.java` -- Caffeine fixed-window limiter template.
- `shared/ai/internal/AiToolCallPurgeJob.java` -- ShedLock nightly purge template.
- `assistant/module/AiAssistantModule.java`, `assistant/package-info.java`, `resources/keevo/modules/ai-assistant/module.json`, `db/migration/V65__ai_assistant_module.sql`, `db/modules/README.md` -- module + offer + owner-disable templates.
- `src/test/java/com/keevo/architecture/ModuleIdsAtGoLivePinTest.java` -- add `mcp-access` (V66).
- `shared/domain/exception/ErrorCode.java`, `shared/infrastructure/web/ErrorMessages.java`, `GlobalExceptionHandler.domainCodeToHttpStatus` -- new codes.
- `shared/infrastructure/web/AuditEventListener.java:583-615` + `shared/infrastructure/export/AuditLabels.java` -- audit of token creation / revocation (template: `DeviceSessionClosedEvent`).
- `src/test/java/com/keevo/shared/ai/AiToolsFullContextTest.java`, `assistant/**/AiAssistantFullContextTest.java` -- full-context fixtures (tenants, owners, plans, tokens).

## Tasks & Acceptance

**Execution:**
- `keevo/backend/pom.xml` -- MCP SDK dependencies (above).
- `shared/infrastructure/security/CredentialAuthenticator.java` (SPI) + `CredentialAuthFilter.java` -- SPI: `handles(request)`, `resolve(request)` → owner (userId, schema, issuedAt) or refusal, `revokedCode()`, `afterAuthentication(request)` → refusal or null (runs with contexts bound). Filter: marks the request so `JwtAuthFilter` skips it, resolves, applies the JWT filter's access-state checks for an OWNER, binds and clears contexts, writes refusals as `{domainCode, error}` (401 with `WWW-Authenticate: Bearer`).
- `JwtAuthFilter.java` -- `shouldNotFilter` true when the alternate-credential attribute is set. `SecurityConfig.java` -- inject `List<CredentialAuthenticator>`, add `CredentialAuthFilter` before the JWT filter.
- `ErrorCode` / `ErrorMessages` / `GlobalExceptionHandler` -- `MCP_TOKEN_INVALID` 401, `MCP_TOKEN_LIMIT` 409, `MCP_TOKEN_NOT_FOUND` 404.
- `db/migration/V66__mcp_access.sql` -- `public.mcp_access_tokens` (id, tenant_id FK tenants CASCADE, user_id FK users CASCADE, name, token_hash BYTEA UNIQUE, hint, tools TEXT[], created_at, expires_at, last_used_at; index `(tenant_id, user_id, created_at DESC)`, index `(expires_at)`); grant `mcp-access` to TEAM, BUSINESS, BUSINESS_TRIAL, PREMIUM, PREMIUM_TRIAL; owner-disable for existing tenants.
- `resources/keevo/modules/mcp-access/module.json`, `mcp/package-info.java`, `mcp/module/McpAccessModule.java` -- paid premium module, requires core, no permissions.
- `mcp/token/**` -- `McpAccessToken` record, `McpTokenSecrets` (generate, SHA-256, hint), `McpAccessTokenRepository` port + `JdbcMcpAccessTokenRepository` (insert, list by owner, count, delete by id+owner, find by hash joined to `tenants.schema_name`, touch last_used, purge expired), `McpAccessTokenService` (create/list/revoke, validation, events), `McpTokenPurgeJob` (expired > 30 days), `McpAccessTokenController` (`GET /api/v1/mcp/tools`, `GET|POST /api/v1/mcp/tokens`, `DELETE /api/v1/mcp/tokens/{id}`).
- `shared/event/catalog/auth/McpAccessTokenChangedEvent.java` + `AuditEventListener` + `AuditLabels` -- « Accès IA externe créé » / « Accès IA externe révoqué » (name only).
- `mcp/server/**` -- `McpServerConfig` (JacksonMcpJsonMapper, `WebMvcStatelessServerTransport` at `/api/v1/mcp` with a context extractor reading the session attribute, handler set, `RouterFunction` bean), `McpTokenAuthenticator` (SPI impl: format check, hash lookup, expiry; after auth: rate limit, `assertEnabled(mcp-access)`, last-used touch ≥ 1 min), `McpRateLimiter`, `McpRequestSession` (token id + scope), `KeevoMcpServerHandler` (methods, quota, scope, JSON-RPC errors; never throws).
- `application.yml` -- `keevo.mcp.rate-limit.token-per-minute: 30`, `tenant-per-minute: 60`.
- `ModuleIdsAtGoLivePinTest` -- add `mcp-access`. Regenerate `docs/entitlements/endpoints.md`.
- `keevo/contracts/mcp-access.json` -- token API + MCP endpoint contract for the RN app.
- Tests: `McpTokenSecretsTest`, `McpRateLimiterTest`, `KeevoMcpServerHandlerTest` (scope, quota, unknown method, error mapping with a stub registry), `CredentialAuthFilterTest` (refusals, binding and clearing), `McpServerFullContextTest` (HTTP: create/list/revoke, initialize/list/call with a token, out-of-scope, revoke → 401 at once, FREE → 403, role change / tva → 401, rate limit 429, quota counted once per OK call, two tokens of two tenants in parallel see only their data).

**Acceptance Criteria:**
- Given an owner on a plan with `mcp-access` and a token scoped to two tools, when an MCP client sends `initialize`, `tools/list`, `tools/call`, then it lists exactly those two tools (when still allowed to the owner), each call is audited with channel `MCP`, and one AI credit is counted per successful call only.
- Given the owner revokes the token, when the client calls again, then the answer is 401 `MCP_TOKEN_INVALID` immediately, with no data.
- Given two tokens of two companies used concurrently, when each calls the same tool, then each sees only its own company's data and no context leaks to the next request of the thread.

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 43 findings — high 0, medium 9, low 17, false 13, maybe-false 4
- findings:
  - `[low]` `reject` (intent) quota counted per served tools/call, not per MCP request — documented decision (Design Notes « Quota unit », server instructions); initialize/list/ping carry no data and no model cost.
  - `[false]` `reject` (intent) existing tenants get MODULE_DISABLED_BY_OWNER rather than MODULE_NOT_ENTITLED — required by db/modules/README.md; Free plan → MODULE_NOT_ENTITLED proven live and in `moduleNotEntitled`; owner-disabled now pinned by `moduleDisabledByOwner`.
  - `[false]` `reject` (intent) per-token revocation is a row delete, not the 16-11 cutoff — the guarantee (refused at the next request on every instance) is the same and the 16-11 cutoff also applies (`ownerChanges`).
  - `[false]` `reject` (intent) no SSE stream — the intent allowed hand wiring with the decision recorded (McpServerConfig, Design Notes); Streamable HTTP is the transport current MCP clients use.
  - `[low]` `reject` (intent) tests use MockMvc, not a real MCP client — live check drove the real server with curl (initialize / tools/list / tools/call); MockMvc runs the real filter chain.
  - `[low]` `reject` (intent) per-tenant limit only unit-tested — `McpRateLimiterTest.limits` covers it with a clock; per-token limit end to end.
  - `[low]` `reject` (intent) lifecycle simulated with SQL + cache eviction — the real flows evict the same cache (16-11); the filter reads only that state.
  - `[medium]` `patch` (intent / verification-gap 1) purge job untested — added `purgeExpired` full-context test (33 days purged, 13 days and live kept).
  - `[false]` `reject` (intent) no owner screen — API-only rule; Mobile section + key `m21-9-serveur-mcp-externe`.
  - `[low]` `reject` (nfr11) two quota transactions per call — existing project pattern (21-4, messaging); change would be project-wide.
  - `[low]` `patch` (nfr11) last-use write can repeat in a burst — `touch` now conditional in SQL (`last_used_at < now − 1 min`).
  - `[low]` `reject` (nfr11) authenticator search twice per request — one string comparison.
  - `[false]` `reject` (nfr11) wording of catalogue cost — accurate per the reviewer.
  - `[medium]` `patch` (blind) failed tokens never braked, each costs a lookup — per-IP brake on failed authentications (20/min, 429 before any lookup), `failedAuthenticationBrake` + limiter test.
  - `[medium]` `patch` (blind / edge) tokens cut by `tokens_valid_after` listed as active and counted in the cap — `deleteCutOff` (one DELETE on the owner index) on list and create; asserted in `ownerChanges`.
  - `[false]` `reject` (blind / edge) demoted owner's tokens revive on re-promotion — no code path changes an OWNER membership role (no ownership transfer); the filter refuses any non-OWNER anyway.
  - `[medium]` `patch` (blind) listing hides that a tool is no longer available — `ToolView.available`, contract updated, asserted in `ownerRightsAndQuota`.
  - `[low]` `reject` (blind) MCP route not in endpoints.md — router function outside the controller scan; the gate is pinned by `moduleNotEntitled` / `moduleDisabledByOwner`.
  - `[medium]` `patch` (blind) contract refusal list incomplete, GET 405 claim wrong without a token — contract lists 401 variants, 503, 500, JSON-RPC errors, read-only grace, GET behaviour.
  - `[low]` `reject` (blind) 422 vs 400 for validation, hard-coded numbers in messages — project convention (body validation 422, service 400, as 21-4); messages match the constants.
  - `[low]` `patch` (blind / VG other) stale `McpToolLabelsTest` reference — javadoc points to `McpServerFullContextTest#everyToolHasALabel`.
  - `[low]` `patch` (blind) migration limits looser than code — V66 (uncommitted) now `hint VARCHAR(4)`, tools 1–16, expiry ≤ 365 days.
  - `[low]` `reject` (blind / edge) limiter increments token counter before the tenant check; Retry-After fixed 60 — conservative and bounded; fixed window ≤ 60 s.
  - `[low]` `reject` (blind) double quota transaction / race — same as nfr11 row; race logged, served uncounted (21-4 pattern).
  - `[low]` `patch` (blind / edge) trailing slash routed to the JWT filter — `handles` accepts `/api/v1/mcp/`; OPTIONS preflight is answered by the CORS filter earlier in the chain (false part).
  - `[low]` `reject` (blind) `MCP-Protocol-Version` header and `structuredContent` unused — optional in the spec for a stateless server; text content is what clients read.
  - `[low]` `reject` (blind) last use recorded before dispatch — means « last authenticated contact »; harmless.
  - `[maybe-false]` `reject` (blind) concurrent creations against the advisory lock untested — lock + count + insert in one transaction is the 21-4 pattern; low if wrong (11 tokens).
  - `[medium]` `patch` (VG 2 / VG other / edge) served result turned into INTERNAL_ERROR when counting fails after the call — `consume` never throws; `consumeRaceServesTheResult`.
  - `[medium]` `patch` (VG 3) owner-disabled refusal not exercised — `moduleDisabledByOwner` full-context test.
  - `[medium]` `patch` (VG 4 / edge) filter 500/503 paths untested; unexpected exception escaped as raw 500 — filter catches any authentication failure → JSON 500; `infrastructureFailures` test.
  - `[medium]` `patch` (edge) `mcpUrl` built from the request behind a proxy — uses `KEEVO_PUBLIC_BASE_URL` like the invoice links, request address as fallback.
  - `[low]` `reject` (edge) `@Size` on the raw tool list counts duplicates — a client sends distinct names; 422 is clear.
  - `[false]` `reject` (edge) out-of-scope / over-quota calls not audited — the AC audits tool calls; refused-before-call attempts read no data; the HTTP access log keeps the request.
  - `[false]` `reject` (edge) parity with JWT path (no write block for SUSPENDED, no session id) — MCP is read-only; documented in `CredentialAuthFilter`.
  - `[maybe-false]` `reject` (blind) purge timing with no test of the 30-day boundary at the job level — covered by `purgeExpired` (33 vs 13 days).
  - `[false]` `reject` (intent) expired token refusal not read line by line — `invalidTokens` asserts 401 after `expires_at` in the past.
  - `[false]` `reject` (intent) physical company deletion untested — FK `ON DELETE CASCADE` on tenants and users (V66); DELETED status tested.
  - `[false]` `reject` (intent) isolation not on a container pool — the filter clears in `finally` on whatever thread runs it; MockMvc runs the same filter code concurrently (20 calls, 4 threads).
  - `[maybe-false]` `reject` (blind) GET without valid access returns 401 not 405 — true and now documented in the contract (not a defect).
  - `[false]` `reject` (blind) OPTIONS preflight gets 401 — CorsFilter precedes the credential filter and answers preflights.
  - `[maybe-false]` `reject` (nfr11) cache invalidation — no token cache; access state cache is 16-11's (evicted cluster-wide).
  - `[false]` `reject` (edge) duplicate of VG other (consume rethrow) — grouped with the consume patch above.

## Design Notes

- **Transport decision:** Spring AI 1.1.8's `spring-ai-starter-mcp-server-webmvc` auto-configures a server with one fixed tool list built at startup; Keevo needs the list of the token and the owner's live rights per request, and no auto-config in the app (21-4 rule). So the SDK transport the starter uses (`WebMvcStatelessServerTransport`, Streamable HTTP, protocol 2025-11-25, no server session) is wired manually and the request handler is Keevo's (`McpStatelessServerHandler`). Stateless fits: every POST carries its token, nothing to share between instances, SSE `GET` answers 405 (allowed by the spec).
- **Patterns:** Strategy/SPI (`CredentialAuthenticator`) so `shared` binds sessions for a credential owned by a module without depending on it; Template (filter does the shared access checks, authenticator the credential); Registry reuse (21-8) so REST and MCP gates cannot drift; Command dispatch on the JSON-RPC method; Repository (JDBC); fixed-window limiter (Caffeine, bounded).
- **Cost per MCP request:** 1 indexed SELECT (`token_hash` unique, PK join on tenants), access state from the 16-11 cache (0–1 query), 1 entitlement read (registry cache), at most 1 `last_used_at` UPDATE per token per minute; `tools/list` = `allowedTools()` (O(tools) in memory after one `enabledModules` read) ∩ scope (hash set); `tools/call` = 1 rolled-back quota tx + the tool's bounded use case + 1 audit insert + 1 quota tx. Token listing = 1 indexed query (≤ 10 rows); create = 1 count + 1 insert; revoke = 1 DELETE by PK + owner; purge = 1 set-based DELETE on `expires_at`. Rate limiter O(1) per request, memory bounded (100 000 keys).
- **Plans (decision):** `mcp-access` granted to TEAM, BUSINESS, BUSINESS_TRIAL and the archived complete plans PREMIUM / PREMIUM_TRIAL (« owner Premium » of the brief = the top tiers of the 21-10 grid); not FREE nor PRO (PRO keeps the in-app assistant). Data, editable by the super-admin.
- **Revocation (decision):** the row is deleted and never cached, so refusal is immediate on all instances; like 16-11 sessions, the owner's revocation cutoff (`tokens_valid_after`, set by « tout déconnecter », role change, removal) also kills tokens created before it.
- **Quota unit:** one `AI_CREDITS` per successful `tools/call` (the external model's own cost is the client's).
- **After review (2026-11-10):** failed authentications are braked per IP (20/min, 429 before any lookup); tokens cut by the owner's `tokens_valid_after` are deleted when the owner lists or creates (never shown as working, never in the cap); the token list and the revocation stay open to the owner whatever the plan (`@EntitlementExempt`), creation and the tool list need the module; `ToolView.available` flags a tool the owner lost; `mcpUrl` uses `KEEVO_PUBLIC_BASE_URL`; counting a served call never turns it into an error; any unexpected authentication failure answers the JSON 500; `last_used_at` written conditionally in SQL; V66 bounds aligned with the code (hint 4, tools 1–16, expiry ≤ 365 days).

## Verification

**Commands:**
- `kmvn.sh keevo/backend clean compile` -- BUILD SUCCESS.
- `wt-pkgs.sh keevo/backend 21-9 mcp` then `shared`, `architecture`, `identity`, `assistant` -- all green.

**Manual checks (if no CLI):**
- Local dev backend: curl `initialize` / `tools/list` / `tools/call` with a token of KV-EW02A2 after granting the module; revoke → 401 at once; a FREE company's token → 403 `MODULE_NOT_ENTITLED`.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit `7e98bea5`, voir `sprint-status.yaml`, clé `m21-9-serveur-mcp-externe`). Contrat : `keevo/contracts/mcp-access.json`. Conventions d'erreur et d'affichage : spec-16-11 ; protocole de synchro : spec-16-10 (rien à synchroniser ici : écran en ligne uniquement).

**Ce qui est fait côté API :** l'owner crée des « accès IA externes » (jetons personnels) pour brancher Claude, ChatGPT ou l'assistant de son comptable sur ses données Keevo via le serveur MCP `POST /api/v1/mcp`. Chaque accès est limité à des données choisies, expire (7, 30, 90 ou 365 jours), est révocable à tout moment (refus immédiat), n'est stocké que haché (le jeton en clair n'est rendu qu'une fois). Module payant `mcp-access` (offres TEAM, BUSINESS, essai BUSINESS, PREMIUM) ; désactivé par défaut pour les entreprises existantes (« Mes modules »). Chaque appel d'outil réussi compte un crédit IA de l'offre.

**À utiliser** (owner uniquement ; code dans `keevo/backend/src/main/java/com/keevo/mcp/token/adapter/in/rest/McpAccessTokenController.java`) :
- `GET /api/v1/mcp/tools` → `{data: [{name, label}]}` — données proposables (module requis : 403 `MODULE_NOT_ENTITLED` « Ce module n'est pas inclus dans votre abonnement » → afficher l'offre ; 403 `MODULE_DISABLED_BY_OWNER` → renvoyer vers « Mes modules »).
- `GET /api/v1/mcp/tokens` → `{data: [{id, name, hint, tools[{name,label}], createdAt, expiresAt, lastUsedAt, expired}]}` — toujours ouvert à l'owner (même sans le module).
- `POST /api/v1/mcp/tokens` `{name, tools[], expiresInDays?}` → 201 `{data: {accessToken, token, mcpUrl}}`. Erreurs : 400 `VALIDATION_ERROR` (message français à afficher tel quel : durée, données indisponibles, nom), 422 `VALIDATION_ERROR` (nom vide, aucune donnée), 409 `MCP_TOKEN_LIMIT` « Vous avez atteint le nombre maximum d'accès IA externes : révoquez-en un avant d'en créer un autre. », 403 module.
- `DELETE /api/v1/mcp/tokens/{id}` → 204 ; 404 `MCP_TOKEN_NOT_FOUND` « Accès IA externe introuvable » (rafraîchir la liste).
- Autres rôles : 403 `FORBIDDEN` → l'écran n'est pas proposé.

**À implémenter côté mobile :**
1. Écran « Accès IA externe » dans Paramètres (owner seulement, visible si le module est actif ; sinon carte d'offre).
2. Liste : nom, données accessibles (labels), « Créé le », « Expire le » / badge « Expiré », « Dernière utilisation » (ou « Jamais utilisé »), indice « …x9Qa ». Jamais l'`id` ni les `name` techniques d'outils.
3. Création : nom, cases à cocher des données (`label`), durée (7 j, 30 j, 90 j par défaut, 1 an). Au succès : afficher une seule fois le jeton et l'adresse `mcpUrl` avec boutons « Copier » et l'avertissement « Ce jeton ne sera plus affiché : copiez-le maintenant ». Ne jamais stocker le jeton (ni cache, ni log, ni analytics).
4. Révocation avec confirmation « Révoquer cet accès ? L'assistant connecté perdra l'accès immédiatement. ».
5. Aide courte : comment coller l'adresse et le jeton dans Claude / ChatGPT (en-tête `Authorization: Bearer <jeton>`). Pas de partage automatique (pas de feuille de partage).
6. En ligne uniquement : hors connexion, message « Connexion requise ».

**Implémentation de référence :** aucune dans `keevo/app` (client de test uniquement).

**Critères d'acceptation mobile / scénarios de test :**
- Given un owner BUSINESS avec le module actif, When il crée un accès « Comptable » sur « Chiffre d'affaires et marge » pour 30 jours, Then le jeton `kvp_…` et l'adresse s'affichent une fois et l'accès apparaît dans la liste sans le jeton.
- Given cet accès, When `curl -X POST <mcpUrl> -H "Authorization: Bearer <jeton>" -H "Accept: application/json, text/event-stream" -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'`, Then seul `revenue_summary` est listé ; après « Révoquer », le même appel répond 401 `MCP_TOKEN_INVALID`.
- Given un employé, When il ouvre les paramètres, Then l'écran n'apparaît pas (API 403).
- Given 10 accès, When l'owner en crée un 11e, Then le message 409 s'affiche tel quel.

**Clé de suivi mobile :** `m21-9-serveur-mcp-externe`.

## Dashboard super-admin (autre dépôt)

Aucune nouvelle route super-admin : l'offre `mcp-access` est une ligne de plus dans le catalogue d'offres déjà éditable (21.10, `profile_modules`) et les compteurs de crédits IA existants (19.8) incluent les appels MCP. Pas de clé `d21-9`.

## Auto Run Result

Status: done

**Summary.** External MCP server (FUS-FR39): paid module `mcp-access` (package `com.keevo.mcp`), `POST /api/v1/mcp` in stateless Streamable HTTP through the MCP Java SDK transport of the Spring AI 1.1.8 line (`WebMvcStatelessServerTransport`, SDK 0.18.3, wired by hand: the starter's startup-time tool list cannot be filtered per token, and no auto-configuration enters the app), Keevo handler on `AiToolRegistry` (channel MCP), owner personal access tokens (create shown once / list / revoke; SHA-256 only; scope ⊂ owner's tools; 7/30/90/365 days; 10 per owner), shared `CredentialAuthenticator` SPI + `CredentialAuthFilter` binding the owner's session per request like the JWT filter, rate limits (token 30, company 60, failed auth 20 per IP per minute), 1 AI credit per served call. Commit `7e98bea5`.

**Files.** `pom.xml` (mcp-spring-webmvc + mcp-json-jackson2 0.18.3); `mcp/**` (module, tokens domain/port/JDBC/service/REST/purge job/labels, server config/handler/authenticator/limiter/session); `shared/infrastructure/security` (SPI, filter, JwtAuthFilter skip attribute, SecurityConfig wiring); error codes `MCP_TOKEN_INVALID` 401 / `MCP_TOKEN_LIMIT` 409 / `MCP_TOKEN_NOT_FOUND` 404; audit event + labels; V66; module manifest; `application.yml`; contract `keevo/contracts/mcp-access.json`; regenerated `docs/entitlements/endpoints.md`; tests (5 new classes, module pins updated).

**Review.** 5 layers, 43 findings: 15 patched (medium 9, low 6), 0 deferred, 28 rejected (13 false, 4 maybe-false, 11 low — reasons in the triage log). Follow-up review recommended: 9 medium entries patched; the unverified risk is the new shared credential filter on the security chain (only owner credentials, but every request passes `shouldNotFilter`) and the per-IP brake behind the production proxy (`X-Forwarded-For` last hop).

**Verification.** Clean compile; packages after the last fix: mcp 24, architecture 139, assistant 40, identity 866, shared 1 349 — 0 failure (shared/module 555 and the security / config / persistence sub-packages also run after the pin updates). Live keevo_dev (profile dev, WhatsApp noop, FCM off): V66 applied (5 plans granted, 377 owner-disable rows); new BUSINESS_TRIAL company: tools listed in French, token created (32-byte hash, clear token nowhere in DB/list), curl initialize 2025-06-18 / tools/list (2 tools) / tools/call ×2 (credits 0 → 2, audit MCP OK ×2), out of scope → isError French, no token / JWT → 401 MCP_TOKEN_INVALID, revoke → 401 at once, admin downgrade to FREE → 403 MODULE_NOT_ENTITLED, list still open, creation 403, audit « Accès IA externe créé / révoqué ». kv_11b5zm untouched except the V66 owner-disable row.

**Residual risks.** Rate-limit counters are per instance (single-instance deployment, like the other limiters). No SSE stream (clients needing legacy HTTP+SSE cannot connect). Set `KEEVO_PUBLIC_BASE_URL` in prod so `mcpUrl` shows the public address.
