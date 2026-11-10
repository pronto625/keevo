---
title: '21-8 — AI tool layer on Keevo use cases (MCP-ready)'
type: 'feature'
created: '2026-11-10'
status: 'done'
baseline_revision: 'f98da324f53a139497e8e02213de600d1aaae33e'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-21-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      storeId returned to the model must never reach the user's screen (prompt rule + output filter) — routed to 21-4.
    evidence: |-
      list_stores / stock_by_store return storeId as the chaining key; only the tool description forbids displaying it.
    location: >-
      keevo/backend/src/main/java/com/keevo/store/store/adapter/in/mcp/StoreMcpTools.java
    severity: medium
  - summary: >-
      MCP transport must bind the token's actor and tenant per request, plus rate limit and AI quota — routed to 21-9.
    evidence: |-
      AiToolRegistry reads the calling thread's security and tenant contexts and limits nothing; 21-9 AC requires
      per-token auth and quota.
    location: >-
      keevo/backend/src/main/java/com/keevo/shared/ai/internal/AiToolRegistryService.java
    severity: medium
  - summary: >-
      REST multi-store stock reads apply no store scope for members — routed to 16-13 (business need or gap).
    evidence: |-
      MultiStoreStockController overview / store detail read any store for EMPLOYEE, MANAGER, VIEWER; the 21.8 tools
      are pinned to the member's store.
    location: >-
      keevo/backend/src/main/java/com/keevo/inventory/stock/adapter/in/web/MultiStoreStockController.java:55
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** The in-app assistant (21-4) and the external MCP server (21-9) need one read-only tool layer over Keevo use cases (no generated SQL, FUS-FR39); today only the empty `AuditMcpPlaceholder` and `.gitkeep` folders exist.

**Approach:** Add Spring AI 1.1.8 (`spring-ai-model` only, the Boot 3.5 line) and annotate one `{Module}McpTools` bean per business module (`adapter/in/mcp/`) with `@Tool`; a shared `AiToolRegistry` discovers them, lists the tools the current session may call, invokes one by name with JSON args (through the Spring proxy, so `@PreAuthorize` / `@RequiresPermission` / `@RequiresEntitlement` run exactly as on REST), times and audits every call in `public.ai_tool_calls`, and exposes each tool as a Spring AI `ToolCallback` for 21-4 / 21-9.

## Boundaries & Constraints

**Always:**
- Actor, tenant, role and store come only from the session (`SecurityContextHolder` + `TenantContext`, as the JWT filter sets them); no tool parameter names a tenant, schema, actor, user, role, access or scope (coverage test).
- Every tool method carries the same gates as the REST handler it mirrors: `@RequiresEntitlement(module, READ)`, the same `@RequiresPermission`, the same role rule as `@PreAuthorize`, and the same store/employee scope code path (`DocumentAccess`, `CurrentClientAccess`, `CurrentExpenseAccess`, finance access, leaderboard sharing rule, sales "store member sees only own sales").
- Results: dedicated result records per tool, French labels for codes, amounts in FCFA, no technical ids except `storeId` (the only chaining key a model needs to pass back to a store filter; never meant to be displayed — 21-4's prompt must say so). Every list is bounded (caps ≤ 50 rows, explicit `truncated`/`hasMore`).
- Audit row per call: tool, channel, actor id, tenant, outcome (`OK`/`REFUSED`/`INVALID`/`ERROR`), error code, duration ms, time — never arguments or results.
- Additive only: existing REST responses, Flutter V1 2.0.x behaviour unchanged.

**Never:** write tools (read-only story; a future write tool must require explicit confirmation); HTTP/MCP transport, model provider, quotas (21-4 / 21-9); SQL generated from model output; new SQL where an existing use case answers (exceptions: the collections aggregate, see Design Notes).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Owner lists tools | OWNER, plan with reporting, profitability, crm… | `allowedTools()` = every tool whose module is enabled and whose role/permission rule passes | — |
| Employee lists tools | EMPLOYEE, invoicing enabled | sales, invoices (own), clients, products, stock tools; no reporting/finance/expenses/stores | — |
| Call allowed tool | `call("financial_result", {"period":"2026-10"})` | JSON of the summary (no ids except storeId); audit OK | — |
| Module disabled | finance not entitled | `AiToolResult` error `MODULE_NOT_ENTITLED`, French message, no data | audit REFUSED |
| Role refused | EMPLOYEE calls `financial_result` | error `FORBIDDEN` « Vous n'avez pas les droits nécessaires » | audit REFUSED |
| Unknown tool / bad JSON / bad date | `call("x")`, `{"from":"10/10"}` | error `VALIDATION_ERROR`, French message | audit INVALID |
| Employee unpaid list | EMPLOYEE (creator scope) | only documents they created (DocumentScope like REST) | — |
| Leaderboard not shared | EMPLOYEE, sharing off | `FORBIDDEN` « Le classement n'est pas partagé avec les employés. » | audit REFUSED |
| No session | no auth or no tenant | `FORBIDDEN`, nothing invoked | audit skipped (no tenant) |

</intent-contract>

## Code Map

- `keevo/backend/pom.xml` -- Spring Boot 3.5.0 parent; add `spring-ai-bom` 1.1.8 (import) + `spring-ai-model`.
- `shared/infrastructure/web/mcp/AuditMcpPlaceholder.java` -- delete (replaced).
- `shared/module/infrastructure/EntitlementAspect.java`, `PermissionMethodSecurityConfig.java` -- runtime gates applied by proxy; reused as-is.
- `shared/module/EntitlementService.isEnabled`, `application/ModulePermissions.permissionsOf/rolesOf` -- listing filter.
- `shared/infrastructure/security/CurrentActor` -- session actor (owner, store member, assigned store, actor id).
- `shared/infrastructure/web/ErrorMessages.userMessage`, `InvalidRequestParameterException.getUserMessage`, `DomainException` -- French error mapping.
- `subscription/plan/adapter/in/scheduling/UsageCounterPurgeJob.java` -- template for the nightly purge (ShedLock, set-based DELETE).
- REST handlers mirrored (scope code copied by calling the same helpers): `reporting/dashboard/adapter/in/rest/DashboardController` (OWNER, `reporting`), `reporting/profitability/adapter/in/rest/ProfitabilityController` (OWNER, `profitability`), `reporting/leaderboard/adapter/in/rest/LeaderboardController` (4 roles, `advanced-reports`, sharing rule), `commerce/sale/adapter/in/rest/SaleHistoryController` (4 roles, `pos`, member → own sales), `invoicing/invoice/adapter/in/rest/InvoiceController#list` (`INVOICE_VIEW`, `DocumentAccess.forList`, `InvoiceQueryUseCase.list/credits/collections`), `crm/contact/adapter/in/web/ClientController#listClients` (4 roles, `crm`, `CurrentClientAccess`, `executePage` keyset), `catalog/product/adapter/in/web/ProductController` (4 roles, `catalog`, `ProductQueryPort.findAllActive`), `inventory/stock/adapter/in/web/MultiStoreStockController` (4 roles, `multi-store`), `expenses/expense/adapter/in/rest/ExpenseController#list` (`EXPENSE_VIEW`, `CurrentExpenseAccess`), `finance/summary/adapter/in/rest/FinanceController` (`FINANCE_VIEW`, `FinanceAccess`), `store/store/adapter/in/rest/StoreController#listStores` (OWNER, `store`).
- `invoicing/invoice/domain/port/out/InvoiceRepository.Filter` + `adapter/out/persistence/JdbcInvoiceRepository.list` -- single `paymentState`; extended with a state set. `invoice_payments` + partial index `idx_invoice_payments_paid_at` (module V17) serve the collections range.
- `src/test/java/com/keevo/architecture/EndpointEntitlementCoverageTest.java` -- pattern for the tool coverage test + generated table.
- `src/test/.../finance/FinanceFullContextTest.java`, `TestAccess` -- full-context test template (Testcontainers, tenant factory, tokens).

## Tasks & Acceptance

**Execution:**
- `keevo/backend/pom.xml` -- import `org.springframework.ai:spring-ai-bom:1.1.8`, add `spring-ai-model` -- `@Tool`, schema generation, `ToolCallback` contract.
- `shared/ai/AiToolRegistry.java`, `AiToolDescriptor.java`, `AiToolResult.java`, `AiToolChannel.java` (new, public API) -- `allTools()`, `allowedTools()`, `allowedToolCallbacks(channel)`, `call(name, jsonArgs, channel)`.
- `shared/ai/internal/AiToolRegistryService.java` -- discovers beans (user class in `..adapter.in.mcp..`, simple name `*McpTools`) with `@Tool` methods after singletons; builds definitions with `ToolDefinitions.from(method)` and invokers with `MethodToolCallback` on the proxy; duplicate names fail startup; listing = `@PreAuthorize` expression evaluated with `DefaultMethodSecurityExpressionHandler` + permissions + `EntitlementService.enabledModules` (read once per listing); call = precheck, invoke, serialize with the app `ObjectMapper`, cap 64 KB, map errors to French, audit.
- `shared/ai/internal/KeevoToolCallback.java` -- Spring AI `ToolCallback` delegating to `call` (never throws; error JSON for the model).
- `shared/ai/internal/AiToolCallAudit.java` (port) + `JdbcAiToolCallAudit.java` + `AiToolCallPurgeJob.java` -- insert (own transaction), nightly purge > 180 days.
- `db/migration/V64__ai_tool_calls.sql` -- `public.ai_tool_calls` (company as `tenant_id` FK, resolved from the schema in the INSERT) + index `(tenant_id, called_at DESC)` + index `(called_at)` for the purge.
- `store/store/adapter/in/mcp/StoreMcpTools.java` -- `list_stores`.
- `reporting/dashboard/adapter/in/mcp/ReportingMcpTools.java` -- `activity_overview`, `revenue_summary`, `compare_revenue_periods`, `store_performance`, `seller_ranking`.
- `reporting/leaderboard/application/service/LeaderboardAudience.java` -- sharing/store/anonymisation rule extracted from `LeaderboardController` (controller delegates; constructor unchanged).
- `commerce/sale/adapter/in/mcp/SalesMcpTools.java` -- `sales_history`.
- `invoicing/invoice/adapter/in/mcp/InvoicingMcpTools.java` -- `unpaid_invoices`, `unpaid_call_list`, `invoice_collections`; `InvoiceRepository.Filter` gains `Set<PaymentState> paymentStates`; `InvoiceQueryUseCase.collected(...)` + `InvoicePaymentRepository.collected(...)` (one aggregate query, methods beyond 20 summed as « Autres moyens »); `InvoiceRepository.outstandingByClient(...)` (one set-based statement for the call list).
- `crm/contact/adapter/in/mcp/ClientMcpTools.java` -- `search_clients`.
- `catalog/product/adapter/in/mcp/ProductMcpTools.java` -- `search_products`; `ProductQueryPort.searchActive` → `ProductRepository.searchActive` → JPQL bounded by `Pageable`; `db/modules/catalog/V3__products_search_indexes.sql` (btree `(lower(name), id)` on non-archived, trigram on name / sku / barcode).
- `inventory/stock/adapter/in/mcp/StockMcpTools.java` -- `stock_by_store`, `low_stock_products` (store member pinned to its store).
- `commerce/sale/adapter/out/persistence/entity/SaleJpaEntity.java` -- `@BatchSize(100)` on `items` (one lines query per history page).
- `shared/ai/AiToolArguments.java` -- date range checks shared by the tools (ordered, years 2000–2100).
- `expenses/expense/adapter/in/mcp/ExpenseMcpTools.java` -- `list_expenses`.
- `finance/summary/adapter/in/mcp/FinanceMcpTools.java` -- `financial_result`.
- `src/test/java/com/keevo/architecture/AiToolCoverageTest.java` + `keevo/backend/docs/ai/tools.md` -- every `@Tool` method is in an `*McpTools` bean under `adapter.in.mcp`, gated READ by a shipped module (or justified `@EntitlementExempt`), declared permissions exist, no forbidden parameter (method params and record components, recursive), `readOnly` everywhere; table regenerated with `-Dkeevo.aitools.regenerate=true`.
- Tests: `shared/ai` unit tests (registry discovery, listing filter, error mapping, audit, size cap), `LeaderboardAudience` test, per-module mapping tests where logic exists (call list grouping), `AiToolsFullContextTest` (owner/employee/manager scopes, module disabled, other tenant isolation, audit rows).
- `_bmad-output/planning-artifacts/architecture.md` -- rule 10 + anti-pattern + MCP section updated; `shared/infrastructure/web/mcp/` removed.

**Acceptance Criteria:**
- Given the application starts, when the registry is built, then it holds the 16 tools with French descriptions and a JSON schema, and fails startup on a duplicate tool name.
- Given any `@Tool` method in production code, when `AiToolCoverageTest` runs, then it fails on a missing/WRITE gate, an unknown module or permission, a forbidden parameter, or a stale `docs/ai/tools.md`.
- Given an owner, an employee and a manager of two stores, when they call the same tools, then the rows they get are exactly those the matching REST endpoints return for them; another tenant's data never appears.
- Given a tool call, when it ends (any outcome), then one `public.ai_tool_calls` row exists with tool, channel, actor, outcome and duration and no argument/result content.

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 42 findings — high 3, medium 16, low 18, false 4, maybe-false 1
- findings:
  - `[medium]` `[patch]` (blind) unpaid_call_list built from the 200 newest outstanding invoices — replaced by one set-based statement over every outstanding invoice (`InvoiceRepository.outstandingByClient`), no sample, no `truncated`.
  - `[false]` `[reject]` (blind) SQL unpaid filter vs computed due can disagree — `InvoicePaymentUseCase.refreshAfterCredit` recomputes the stored state after a credit note (fully credited ⇒ PAID ⇒ excluded); a payment does the same.
  - `[low]` `[reject]` (blind) 31-day default of sales_history vs 30 elsewhere — copies `SaleHistoryController` exactly (`minusDays(30)`); harmless.
  - `[medium]` `[patch]` (blind) no bound on date ranges — `AiToolArguments.checkPeriod` (ordered, years 2000–2100, French 400) in every dated tool; spans are single set-based aggregates or paged reads, so no span cap.
  - `[false]` `[reject]` (blind) unmapped enums leak raw codes — `PaymentMode` has only CASH / MOBILE_MONEY, `SaleStatus` 3 values all mapped, `computeStatus` returns only the 3 mapped levels.
  - `[medium]` `[defer]` (blind) storeId UUIDs reach the model, kept off screen only by the description — `storeId` is the documented chaining key; the assistant's prompt and an output filter belong to 21-4 → routed to 21-4.
  - `[maybe-false]` `[patch]` (blind) employees can read other stores' stock — REST multi-store endpoints apply no scope (by design or gap: unknown); the tools now pin a member to their store (stricter); REST question routed to 16-13.
  - `[medium]` `[patch]` (blind) coverage test misses employee/seller/member names, role-or-permission gate, UUID in results — all three checks added with fixtures.
  - `[low]` `[reject]` (blind) list_expenses NUL message — same branch and message as `ExpenseController#list` (mirrored on purpose).
  - `[high]` `[patch]` (blind) search_products loads the whole catalogue — bounded SQL search (`ProductQueryPort.searchActive`, `LIMIT n+1`, catalog V3 trigram + btree indexes); description says « non archivés, brouillons compris ».
  - `[low]` `[patch]` (blind) audit gaps — null tool name / channel now audited (`?`, INTERNAL); no-session calls stay unaudited (spec matrix: nothing to attach them to).
  - `[medium]` `[patch]` (blind) tools without tests — full-context tests added for search_products, seller_ranking (shared / not shared, anonymised, store filter ignored), stock scope, paid / overdue filters, collections filters, purge (DB + unit).
  - `[medium]` `[patch]` (blind) payment-method list truncated without the totals adding up — methods beyond 20 summed into « Autres moyens » (`Collected.otherCount/otherAmount`).
  - `[medium]` `[defer]` (blind) no rate limit / quota on the MCP channel — quotas per question belong to 21-4, per token / tenant on MCP to 21-9 → routed to 21-9.
  - `[low]` `[reject]` (blind) minor inconsistencies — `KeevoZones.WAT` is Africa/Douala (same zone), `gateOf` resolves like `EntitlementAspect`, RESULT_TOO_LARGE as INVALID and char-based cap are cosmetic, `LeaderboardAudience` built in place keeps the controller constructor (tests) stable.
  - `[low]` `[patch]` (edge) null domain code NPE in failureOf — guarded.
  - `[low]` `[patch]` (edge) failureOf itself throwing escapes call() — wrapped, falls back to ERROR.
  - `[low]` `[patch]` (edge) null tool name violates NOT NULL in audit — recorded as `?`.
  - `[low]` `[patch]` (edge) null channel NPE in audit — defaults to INTERNAL (call and callbacks).
  - `[low]` `[reject]` (edge) invalid tenant string in allowedTools — `TenantContext` is only bound by the JWT filter / tenant routing with validated schemas; a guard adds a branch for an unreachable state.
  - `[medium]` `[patch]` (edge) OFFSET race between the two call-list pages — moot: one statement now.
  - `[false]` `[reject]` (edge) unpaid list rows with 0 due — stored state refreshed after credit notes and payments (`refreshAfterCredit`, `afterChange`).
  - `[medium]` `[patch]` (edge) byMethod truncated without flag — same fix as the « Autres moyens » bucket.
  - `[low]` `[patch]` (edge) negative first-period revenue gives a meaningless percentage — `null` when the base is ≤ 0.
  - `[medium]` `[patch]` (edge) extreme dates end as INTERNAL_ERROR — `AiToolArguments.checkPeriod` (2000–2100) + conversion exceptions mapped to VALIDATION_ERROR.
  - `[false]` `[reject]` (edge) no audit for refused no-session calls vs AC — the spec's I/O matrix states « audit skipped (no tenant) »; a row needs a company.
  - `[low]` `[reject]` (edge) V64 has tenant_id instead of the spec's tenant_schema — the code is coherent (FK + cascade like usage_counters); fix would only edit the spec (Code Map updated as documentation).
  - `[low]` `[reject]` (edge) 64 000 chars vs 64 KB — cosmetic bound on model context, either unit is fine.
  - `[medium]` `[patch]` (verification) paymentStates filter untested — fully paid invoice excluded and overdueOnly asserted in AiToolsFullContextTest.
  - `[medium]` `[patch]` (verification) collected store filter / period start untested — storeId=B → 0, from=tomorrow → 0, 1900 → VALIDATION_ERROR.
  - `[medium]` `[patch]` (verification) seller_ranking tool untested — full-context sellerRanking test (refused until shared, anonymised colleagues, own row `me`, owner names).
  - `[low]` `[patch]` (verification) purge untested — AiToolCallPurgeJobTest (cutoff now − 180 d, never throws) + DB assertion (old row deleted, fresh kept).
  - `[low]` `[reject]` (intent) params are flat instead of the `…Query` records — documented decision: the records carry tenant / actor (rule 8), which AC2 forbids as parameters; the tools build them.
  - `[medium]` `[patch]` (intent) stock tools without employee scope — pinned to the member's store (see blind finding).
  - `[medium]` `[defer]` (intent) registry reads the ambient session, not a given user — 21-9 must bind the token's actor and tenant (security + tenant contexts) before `call`/`allowedTools`; 21-4 must stay on the request thread → routed to 21-9 (and noted for 21-4).
  - `[low]` `[reject]` (intent) no chat client / MCP transport exercised — out of scope by the intent (21-4 / 21-9 consume the layer); the Spring AI `ToolCallback` adapter is tested.
  - `[low]` `[reject]` (intent) new queries beyond existing use cases — only where no use case answered (collections, call list, bounded product search), each a single set-based statement.
  - `[high]` `[patch]` (FUS-NFR11) unbounded catalogue load in search_products — see blind finding (bounded SQL + catalog V3 indexes).
  - `[high]` `[patch]` (FUS-NFR11) N+1 on sale lines in history pages — `@BatchSize(size = 100)` on `SaleJpaEntity.items` (one lines query per page, REST history too).
  - `[medium]` `[patch]` (FUS-NFR11) call list from a capped sample with per-page counts and line loads — one set-based statement (see blind finding).
  - `[low]` `[patch]` (FUS-NFR11) paymentStates IN cannot use the index order — comment corrected (range per state + top-N sort of the few outstanding rows); a new partial index rejected (few rows per tenant).
  - `[low]` `[patch]` (FUS-NFR11) one module-state lookup per tool off the request thread — `enabledModules(tenant)` read once per listing.

## Design Notes

- **Spring AI decision:** Boot 3.5.0 ⇒ Spring AI 1.1.x (1.1 GA targets Boot 3.5; 2.x needs Boot 4). Only `spring-ai-model` (annotations, `ToolDefinitions`, `JsonSchemaGenerator`, `MethodToolCallback`, `ToolCallback`) — no auto-configuration, no model starter, no MCP starter (21-4 / 21-9 add theirs on the same BOM).
- **Params deviation:** existing `…Query` records carry `tenantId` / `ActorId` (architecture rule 8) or `ClientAccess`, so exposing them would break the "no actor/tenant parameter" AC. Tools take flat, documented parameters (`@ToolParam`, French descriptions) and build the existing `…Query` exactly like the REST adapter does from request parameters.
- **Patterns:** Registry + Proxy (gates are the existing Spring AOP/method-security interceptors, so REST and tools cannot drift); Adapter per module (`*McpTools`); Policy object `LeaderboardAudience` shared by REST and tool; Template purge job.
- **Cost:** listing = O(tools) in-memory checks after one `enabledModules` read (also off the request thread) and the cached permission map. One call = the mirrored use case's queries (all bounded pages: invoices ≤ 50/page with batched credits/ledgers, clients keyset ≤ 50, expenses ≤ 50, sales ≤ 50 with lines batch-fetched) + 1 INSERT audit. `unpaid_call_list` = one statement (outstanding invoices via `idx_invoices_payment_state_list`, correlated credit / payment sums on `idx_credit_notes_invoice` / `idx_invoice_payments_invoice` — the formula of `markOverdue` — grouped by client, totals by window functions, `LIMIT n`). `compare_revenue_periods` = 2 summary aggregates. `search_products` = one `LIKE` query bounded by `limit + 1` (catalog V3 indexes). `invoice_collections` = 1 aggregate `GROUP BY method` on `invoice_payments` joined to `invoices` (scope predicate from `DocumentScope.sql`), range on `paid_at` served by `idx_invoice_payments_paid_at`. New SQL only where no use case answered (collections, call list, bounded product search).
- **Stricter than REST (review 2026-11-10):** the purchase price (`search_products`) goes to the owner only, and a store member's stock tools are pinned to their own store (the REST multi-store endpoints apply no store scope — gap routed to 16-13). Everything else mirrors the REST handler's scope code path.
- **Audit:** calls without a session (no actor or tenant) are refused and not audited (nothing to attach them to); unnamed tool → `?`, null channel → INTERNAL.
- **Threading:** tools read the calling thread's security and tenant context; 21-4 must run tool calls on the request thread (non-streaming) or propagate both contexts.

## Verification

**Commands:**
- `kmvn.sh keevo/backend clean compile` -- BUILD SUCCESS.
- `wt-pkgs.sh keevo/backend 21-8 shared architecture reporting invoicing` then `commerce crm catalog inventory expenses finance store` -- all green.

**Manual checks (if no CLI):**
- Local dev backend boots and logs `AI tool registry: 16 tools` (live check); full-context test proves scoped results and audit rows on PostgreSQL.

## Mobile (React Native — dépôt AI/bref) et Dashboard super-admin

Aucun changement visible pour l'app ni pour le dashboard : 21.8 n'ajoute aucun endpoint HTTP (la couche d'outils est interne, consommée par 21.4 et 21.9 qui exposeront les routes) et ne modifie aucune réponse REST existante (le refactor `LeaderboardController` → `LeaderboardAudience` garde exactement les mêmes réponses, tests REST inchangés et verts). Pas de clé `m21-8` ni `d21-8`. Les outils listés dans `keevo/backend/docs/ai/tools.md` serviront les sujets guidés de l'assistant (21.4) : la section Mobile de 21.4 décrira les routes.

## Auto Run Result

Status: done

**Summary.** Read-only AI tool layer on the Keevo use cases (FUS-FR39, D8): Spring AI 1.1.8 (`spring-ai-model` only, the Boot 3.5 line; swagger annotations pinned to springdoc's), 16 `@Tool` methods in 9 `*McpTools` beans (`adapter/in/mcp/`), `shared.ai.AiToolRegistry` (list the tools the session may call, call one by name with JSON, Spring AI `ToolCallback`s for 21.4 / 21.9), gates = the REST interceptors through the bean proxy + the REST scope helpers, audit `public.ai_tool_calls` (V64, 180-day purge), `AiToolCoverageTest` + generated `docs/ai/tools.md`, `AuditMcpPlaceholder` and the `.gitkeep` rule 10 replaced in architecture.md.

**Files.** `pom.xml` (Spring AI BOM + model); `shared/ai/*` (API, registry, callback, audit, purge, argument checks); 9 tool beans (store, reporting, commerce, invoicing, crm, catalog, inventory, expenses, finance); `LeaderboardAudience` (17.2 rule shared by REST and tool); invoicing `Filter.paymentStates`, `collected`, `outstandingByClient`; catalog `searchActive` + module V3 (search indexes); `SaleJpaEntity` `@BatchSize`; V64; tests (`AiToolCoverageTest`, `AiToolRegistryServiceTest`, `AiToolCallPurgeJobTest`, `LeaderboardAudienceTest`, `AiToolsFullContextTest` 12 cases); `SchedulerLockCoverageTest` and the tenant shape reference updated.

**Review.** 5 layers, 42 findings: 26 patched (high 3, medium 13, low 10), 3 deferred and routed (21-4 storeId display + request thread; 21-9 token-bound session + rate limit / quota; 16-13 REST stock store scope, story reopened), 13 rejected (4 false, 9 low or out of scope / documented decisions — see triage log).

**Follow-up review recommended: true** — 3 high patched (bounded SQL product search + catalog V3 trigram indexes, sale lines batch fetch, set-based call list): the call-list SQL re-states `PaymentStateCalculator` (total − credit notes − received payments) and deserves a second look on instalment and partially credited invoices; the trigram indexes were built on 373 keevo_dev tenants at startup — watch the build time on production-size catalogues at go-live.

**Verification.** Clean compile; packages after the last fix — shared 1 344, architecture 137, reporting 461, invoicing 465, commerce 268, crm 240, catalog 371, inventory 412, expenses 41, finance 21, store 128: 0 failure. Live keevo_dev (profile dev, WhatsApp noop, FCM off): V64 applied, catalog V3 applied to 373 tenants, `AI tool registry: 16 tools` at boot, refactored `GET /reporting/leaderboard` 200 for a freshly registered owner; tools have no HTTP surface yet, so their live behaviour is proved by `AiToolsFullContextTest` on PostgreSQL (scopes, gates, isolation, audit). kv_11b5zm data untouched (schema upgrade only).

**Residual risks.** The registry trusts the calling thread's contexts (documented, routed to 21-4 / 21-9); `storeId` is shown to models (routed to 21-4).
