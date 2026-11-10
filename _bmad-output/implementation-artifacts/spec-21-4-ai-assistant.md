---
title: '21-4 — AI assistant module (tool-based, Bref Point parity)'
type: 'feature'
created: '2026-11-10'
status: 'done'
baseline_revision: '8ff29e87d04bef5245bd3bd280cbb02128b36acf'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-21-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-21-8-couche-outils-ia-mcp.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Assistant conversations of a removed or deleted member are never purged — routed to 16-6.
    evidence: |-
      ai_conversations rows are keyed by user_id only; no member-removal or account-deletion path deletes them
      (only the tenant purge does); they can hold client names and phones from guided answers.
    location: >-
      keevo/backend/src/main/java/com/keevo/assistant/conversation/adapter/out/persistence/JdbcAiConversationRepository.java
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Bref's « Bref Point » assistant (guided topics + free-text questions + saved conversations) has no Keevo equivalent; 21-8 delivered the read-only tool layer but nothing calls it, and Bref's free text generates SQL (forbidden, FUS-FR39).

**Approach:** New tenant module `ai-assistant` (package `com.keevo.assistant`): Bref's conversation CRUD on `kv_x.ai_conversations`, guided topics computed for free by the 21-8 tools (no model, no credit), and `POST /api/v1/ai/chat` running a bounded tool-calling loop on the request thread against a configurable model port (OpenAI / Gemini / Ollama through one OpenAI-compatible Spring AI adapter, a scripted fake for dev/tests, disabled by default), counting one AI credit (19-8) per answered question only.

## Boundaries & Constraints

**Always:**
- Tools only through `AiToolRegistry` (`allowedTools()` / `call(name, json, ASSISTANT)`), on the HTTP request thread (security + tenant contexts are the request's); never SQL from model output.
- Quota `AI_CREDITS` checked before the model call (in a rolled-back transaction: exact 19-8 refusal, nothing kept) and consumed (`assertAndIncrementForSchema`, own transaction) only after a final answer; never on disabled provider, provider failure, tool `ERROR`, loop overflow, concurrent question, validation error. Guided topics and conversation CRUD never count.
- One free-text question at a time per company (tenant): second concurrent call → 429 `AI_QUESTION_IN_PROGRESS`, released in `finally`.
- Bounds: message ≤ 2 000 chars, history ≤ 20 entries (user/assistant roles only, Bref), history fed to the model ≤ 12 000 chars (most recent kept), ≤ 4 model rounds, ≤ 8 tool calls, each tool result fed to the model ≤ 6 000 chars and ≤ 24 000 in total (truncated with a French marker), answer ≤ 4 000 chars, max output tokens 800, provider timeouts connect 10 s / read 60 s, ≤ 1 retry on 429/5xx.
- System prompt (French): Keevo assistant, today's date (WAT), FCFA, tools only, never invent figures, never show an identifier (storeId or any id), refuse prompt-injection. Output filter removes every UUID from the reply (and generated titles) before it leaves the server.
- Conversations are per user (actor id) inside the tenant schema; list bounded (100 most recent), ≤ 200 per user, payload bounds (Bref: messages/aiHistory ≤ 500 items, title ≤ 120, messageCount 0–10 000) plus ≤ 512 KB serialized.
- API keys from environment only, never logged; no question, answer or personal data in logs (only outcome, rounds, tool count, duration).
- Additive only: new endpoints, new module, no existing response changed. French labels, no technical data in guided-topic output (no ids except the `storeId` filter the app passes in).

**Never:** SQL generation/execution from the model; streaming or async tool execution; real model calls in tests or the live check; write tools; MCP transport (21-9); the `queryResults` raw dump of Bref (omitted: it would carry ids).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Free text answered | owner PRO, provider fake, quota left | 200 `{reply, creditsRemaining}`; tools called via registry (audited ASSISTANT); 1 credit counted | — |
| Provider disabled | default config | 503 `AI_UNAVAILABLE` « Les questions libres ne sont pas encore disponibles… utilisez les sujets proposés » | nothing counted |
| Quota exhausted | FREE plan (AI 0) or day cap reached | 403 `QUOTA_EXCEEDED` (19-8 details), model never called | nothing counted |
| Concurrent question | same company, call in flight | 429 `AI_QUESTION_IN_PROGRESS` | nothing counted |
| Provider fails | timeout / 5xx after retry | 502 `AI_PROVIDER_ERROR` French | nothing counted |
| Tool error | a tool call ends `ERROR` / loop over limits | 503 `AI_ANSWER_FAILED` French | nothing counted |
| Tool refused | employee asks finance | refusal JSON `{"erreur": "…"}` handed to the model; answer delivered, counted | — |
| History too long | 21 entries / message > 2000 | 400 `VALIDATION_ERROR` | nothing counted |
| Guided topic | `GET /ai/topics/revenue?period=month` | stats from tools, French labels, no model call, no credit | tool refused → 403 with the tool's French message |
| Module disabled | owner disabled ai-assistant | every /ai route 403 `MODULE_DISABLED_BY_OWNER` | — |
| Other user's conversation | id of another user / tenant | 404 `AI_CONVERSATION_NOT_FOUND` (GET, PUT); DELETE idempotent 204 | — |

</intent-contract>

## Code Map

- `shared/ai/AiToolRegistry.java`, `AiToolDescriptor`, `AiToolResult`, `AiToolChannel.ASSISTANT` -- the only data access of the module (21-8).
- `shared/ai/internal/KeevoToolCallback.java` -- error JSON shape `{"erreur": …}` reused for refused tools.
- `shared/capability/entitlement/UsageQuotaPort.java` (`assertAndIncrementForSchema` MANDATORY tx, `statusForSchema`), `UsageMetric.AI_CREDITS`, `UsageStatus.QuotaUsage.remaining`, `credits` -- quota; V44 seeds AI_CREDITS per plan (FREE 0, PRO 500/20, TEAM 750/30, BUSINESS ∞/50…).
- `shared/domain/exception/ErrorCode.java`, `shared/infrastructure/web/GlobalExceptionHandler.domainCodeToHttpStatus`, `ErrorMessages` -- add `AI_QUESTION_IN_PROGRESS` 429, `AI_UNAVAILABLE` 503, `AI_PROVIDER_ERROR` 502, `AI_ANSWER_FAILED` 503, `AI_CONVERSATION_NOT_FOUND` 404, `AI_CONVERSATION_LIMIT` 409.
- `shared/module/RequiresEntitlement` (GET → READ, others WRITE, both provision module tables), `EntitlementAspect`, `KeevoModule`, `ModuleManifestLoader` -- module wiring; template `expenses/module/ExpensesModule.java`, `expenses/package-info.java`.
- `payments/checkout/adapter/out/gateway/PaymentGatewayConfig.java` -- strategy-by-property template (disabled default, fake only under dev/test, errors name the variable).
- `expenses/expense/adapter/out/persistence/JdbcExpenseRepository.java` -- JDBC on the tenant schema (search_path set by the request).
- `resources/keevo/modules/expenses/module.json`, `db/modules/expenses/V1__expenses.sql`, `db/migration/V56__*.sql` -- manifest, module DDL, grant + owner-disable migration templates; `db/modules/README.md` rules.
- `src/test/java/com/keevo/architecture/ModuleIdsAtGoLivePinTest.java` -- add `ai-assistant` (V65).
- `src/test/java/com/keevo/shared/ai/AiToolsFullContextTest.java` -- fixture helpers (tenant, members, tokens, invoices via API, sales via SQL).
- api-bref `src/ai/ai.controller.ts`, `ai.service.ts`, `dto/*`; bref app `features/bref-point/hooks/use-bref-point.ts` -- contract and guided topics to port (read-only).

## Tasks & Acceptance

**Execution:**
- `keevo/backend/pom.xml` -- add `spring-ai-openai` (BOM 1.1.8) -- OpenAI-compatible chat model for the three providers.
- `assistant/package-info.java`, `assistant/module/AiAssistantModule.java`, `resources/keevo/modules/ai-assistant/module.json` -- module `ai-assistant` (category premium like every paid module — invariant of the module tests, paid → plan rows, requires core, no permissions; all four roles).
- `db/modules/ai-assistant/V1__ai_conversations.sql` -- table + index `(user_id, updated_at DESC, id)`; idempotent.
- `db/migration/V65__ai_assistant_module.sql` -- grant to every `plan_profiles` row (FREE included: guided topics are free in Bref; free text bounded by AI_CREDITS quotas); owner-disable for existing tenants.
- `assistant/conversation/**` -- domain record, port, `JdbcAiConversationRepository`, `AiConversationService` (CRUD, per-user, bounds, title: given, else model title within 20/h/user, else first 60 chars).
- `assistant/chat/**` -- `AssistantModel` port (+ `AssistantModelException`), `AssistantChatService` (in-flight guard, quota check/consume, loop, caps, UUID filter), `AssistantPrompt` (system prompt).
- `assistant/chat/adapter/out/model/**` -- `AssistantModelConfig` (property `keevo.ai.provider`: disabled | fake (dev/test only) | openai | gemini | ollama; key missing for openai/gemini → disabled + WARN), `DisabledAssistantModel`, `OpenAiCompatibleAssistantModel` (Spring AI `OpenAiChatModel`, internal tool execution off), `ScriptedAssistantModel` (+ `resources/keevo/ai/fake-assistant-script.json`).
- `assistant/guided/**` -- `GuidedTopic` catalogue, `AssistantPeriod` (Bref periods, WAT), `GuidedTopicService` (tools → French stats), list of available topics from `allowedTools()`.
- `assistant/adapter/in/rest/AiAssistantController.java` + DTOs -- `POST /api/v1/ai/chat`, `GET|POST /api/v1/ai/conversations`, `GET|PUT|DELETE /api/v1/ai/conversations/{id}`, `DELETE /api/v1/ai/conversations/batch`, `GET /api/v1/ai/topics`, `GET /api/v1/ai/topics/{topic}`.
- `application.yml` -- `keevo.ai.*` from `KEEVO_AI_PROVIDER`, `KEEVO_AI_API_KEY`, `KEEVO_AI_MODEL`, `KEEVO_AI_BASE_URL`; `application-dev.yml` unchanged (dev runs `KEEVO_AI_PROVIDER=fake` explicitly).
- `keevo/contracts/ai-assistant.json` -- endpoints, shapes, errors, topics, parity corpus (≥ 30 questions: tool steps, answer template, expected figures).
- Tests: unit (`AssistantChatServiceTest` limits/quota/in-flight/filter, `ScriptedAssistantModelTest`, `AssistantModelConfigTest`, `AssistantPeriodTest`, `OpenAiCompatibleAssistantModelTest` mapping with a stub ChatModel), `AiAssistantFullContextTest` (HTTP: CRUD, chat with fake calling a tool on the request thread, quota counted once / not counted on failures, topics, isolation employee / disabled module / other tenant), `AiAssistantParityCorpusTest` (contract corpus, exact figures, no UUID), `ModuleIdsAtGoLivePinTest`; regenerate `docs/entitlements/endpoints.md`.

**Acceptance Criteria:**
- Given the corpus of `keevo/contracts/ai-assistant.json`, when each question is replayed through `POST /api/v1/ai/chat` with the scripted provider, then the expected tools are called (audited channel ASSISTANT) and every expected figure appears exactly in the reply, which contains no UUID.
- Given an employee, a disabled tool module or another tenant, when a guided topic or a free-text question would touch those data, then the tool refuses or filters exactly as REST and no other tenant's or store's data appears.
- Given a successful answer, a refused quota, a provider failure, a tool error or a concurrent question, when the usage counters are read, then exactly one AI credit was counted for the success and none for the others.

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 47 findings — high 2, medium 17, low 28, false 0, maybe-false 0
- findings:
  - `[high]` `[patch]` (blind) VIEWER blocked from chat and conversation writes by `ViewerWriteGuardFilter` — the 5 assistant writes added to `ALLOWED_WRITES` (a question only reads, conversations are the viewer's own), `ViewerAllowListPinTest` pinned, full-context `viewerAndManager` test.
  - `[medium]` `[patch]` (blind) revenue `compare=true` ignores `compare_revenue_periods` in topic tools / refusal — tool added to REVENUE; refusal now comes from the first needed tool the session may not call (never re-invokes an allowed tool).
  - `[medium]` `[patch]` (blind) PUT without messages / aiHistory / messageCount wipes the chat — absent fields keep the stored value (COALESCE), contract updated, test renames a conversation and keeps its messages.
  - `[medium]` `[patch]` (blind) slow provider holds the request and the company slot for minutes — 90 s deadline per question checked before each model call (502), read timeout 45 s, titles on a separate model (10 s, no retry).
  - `[low]` `[patch]` (blind) answer lost when credits run out between check and consume — the answer is delivered uncounted (`creditsRemaining` 0), unit test `quotaRace`.
  - `[medium]` `[patch]` (blind) model titles unmetered on plans without AI credit; limiter map unbounded — titles only when the company still has a credit (`AssistantChatService.hasCredit`, rolled-back check), idle users dropped beyond 1 000 tracked.
  - `[medium]` `[defer]` (blind) conversations of a removed / deleted member never purged → routed to 16-6 (personal-data purge owner); the cap race of the same finding → patched (advisory lock + count + insert in one transaction).
  - `[low]` `[patch]` (blind) guided details print raw numbers (« 12500 FCFA », « +12.345678 % ») — `fcfa()` / `percent()` French formatting, asserted in `guidedFigures`.
  - `[low]` `[patch]` (blind) search on a topic without search says « too long » — own message « La recherche n'est pas proposée pour ce sujet ».
  - `[low]` `[patch]` (blind) KEEVO_AI_* missing from `.env.example`, `/v1` base-URL trap — documented in `.env.example`, trailing `/` or `/v1` stripped, `AssistantModelConfigTest.settings`.
  - `[medium]` `[patch]` (blind) tool-call cap fails instead of answering; cut JSON — over the cap nothing runs and the next round offers no tool; oversize result → valid `{"erreur": …}`; spent budget → no more calls, model answers (unit tests `toolCallLimit`, `tooLarge`, `totalBudget`).
  - `[medium]` `[patch]` (edge) PUT partial body wipes content — same fix as blind.
  - `[low]` `[patch]` (edge) 200-conversation cap not atomic — `insertUnderCap` (per-user advisory lock, one transaction).
  - `[low]` `[patch]` (edge) quota race after the answer — same fix as blind.
  - `[medium]` `[patch]` (edge) no overall deadline — same fix as blind.
  - `[medium]` `[patch]` (edge) title generation blocks creation ~2 min — dedicated title model, 10 s, no retry.
  - `[low]` `[patch]` (edge) refusal() re-invokes an allowed tool with {} — refusal from the first not-allowed tool only, else plain FORBIDDEN.
  - `[low]` `[patch]` (edge) revenue compare refusal opaque — same fix as blind.
  - `[low]` `[patch]` (edge) seller_ranking truncated not flagged — text « Classement limité aux N premiers vendeurs. ».
  - `[low]` `[reject]` (edge) period silently ignored on topics « à date » — the Bref app sends a period with overdue / customers; ignoring it is the compatible behaviour (contract: period none).
  - `[low]` `[reject]` (edge) `creditsRemaining` null when the usage read fails — a failed read is logged and rare; null already means « no limit to show », the app refreshes « Mon plan » after each answer anyway.
  - `[low]` `[patch]` (edge) base URL with /v1 → /v1/v1 — same fix as blind.
  - `[low]` `[patch]` (edge) titleCalls grows unbounded — same fix as blind.
  - `[medium]` `[patch]` (verification) HTTP statuses of the AI codes untested — `GlobalExceptionHandlerTest.aiCodes`.
  - `[medium]` `[patch]` (verification) V65 FREE grant unpinned — assertion in `ModuleOwnerActivationMigrationPostgresIntegrationTest` (every plan profile has ai-assistant granted).
  - `[medium]` `[patch]` (verification) overdue / collections / sellers / shops checked for 200 only — `guidedFigures` asserts their stats and rows.
  - `[low]` `[patch]` (verification) 24 000 total budget untested — `totalBudget`.
  - `[medium]` `[patch]` (verification) provider wiring unverified — `AssistantModelConfig.settings` + `AssistantModelConfigTest.settings` (URL, path, model, token cap per provider; key never in `toString`).
  - `[medium]` `[patch]` (verification) no VIEWER test — `viewerAndManager`.
  - `[high]` `[patch]` (verification other) VIEWER blocked — same fix as blind.
  - `[low]` `[patch]` (verification other) cap exceedable — same fix as edge.
  - `[medium]` `[reject]` (intent a) guided topics on new endpoints instead of the app's stats calls — the intent allows « a 21.8 tool (or an existing stats endpoint) »; Keevo has no Bref invoice-stats endpoint, the RN app is being ported to Keevo (mobile key m21-4 documents the new calls).
  - `[low]` `[reject]` (intent b) Keevo envelope / paths instead of Bref raw bodies — every ported Bref surface follows Keevo's conventions (expenses, finance…); differences listed in the contract.
  - `[low]` `[reject]` (intent c) corpus templates vs model behaviour, no migrated conversations — no Bref conversation is migrated yet (wave 24.x); the corpus proves tool routing and exact figures on the fixture as the AC asks (« simulated provider for the expected tool calls »).
  - `[low]` `[reject]` (intent d) 20 messages enforced on history only — identical to Bref (app cap + history ≤ 20).
  - `[medium]` `[patch]` (intent e) manager store scope through the assistant untested — `viewerAndManager` (manager sees only its store's debtor, chat and topic).
  - `[low]` `[reject]` (intent f) one OpenAI-compatible adapter — Bref's own design (OpenAI SDK + base URL for Gemini / Ollama).
  - `[low]` `[reject]` (intent g) 429 / tool-error paths not full-context — covered on the real service in `AssistantChatServiceTest` (`oneAtATime`, `toolError`); statuses in `GlobalExceptionHandlerTest`.
  - `[medium]` `[patch]` (FUS-NFR11 1) no overall time limit — same fix as blind.
  - `[low]` `[patch]` (FUS-NFR11 2) cut JSON, wasted rounds — same fix as blind.
  - `[low]` `[patch]` (FUS-NFR11 3) title map unbounded — same fix as blind.
  - `[low]` `[reject]` (FUS-NFR11 4) input tokens per question (~45k worst case) — bounded by the caps, stable prefix for provider caching; lowering further would starve multi-tool questions.
  - `[low]` `[patch]` (FUS-NFR11 5) check / consume window — same fix as blind.
  - `[low]` `[reject]` (FUS-NFR11 6) in-memory guards assume one instance — production runs one backend instance (documented in the class); an advisory lock would hold a connection during the model call.
  - `[low]` `[reject]` (FUS-NFR11 7) each save rewrites the whole chat — Bref's contract, bounded by 500 items / 512 KB.
  - `[low]` `[patch]` (FUS-NFR11 8) cap check not atomic — same fix as edge.
  - `[low]` `[patch]` (FUS-NFR11 9) extra audited refusal call; regexes compiled per call — refusal fix as edge; `sanitize` patterns precompiled.

## Design Notes

- **Patterns:** Ports & adapters (model provider = Strategy chosen by property, Null Object when disabled, scripted Test Double); Template-free bounded agent loop (Iterator with hard caps) in the application service; Registry reuse (21-8) so REST and assistant share gates; Repository (JDBC) for conversations; per-tenant in-flight guard (`ConcurrentHashMap.newKeySet`, single-instance deployment like Bref; documented).
- **Model loop:** `respond(system, messages, tools)` → text or tool calls; each call → `registry.call(..., ASSISTANT)` synchronously; `ERROR` aborts, `REFUSED`/`INVALID` become `{"erreur": message}` for the model.
- **Guided topics (free):** `diagnostic` (month: revenue_summary + invoice_collections + unpaid_call_list), `revenue` (revenue_summary), `overdue` (unpaid_invoices overdueOnly + unpaid_call_list totals), `collections` (invoice_collections), `sellers` (seller_ranking), `shops` (store_performance), `customers` (unpaid_call_list, or unpaid_invoices with `search`), `call_list` (unpaid_call_list 30); `compare=true` → compare_revenue_periods (current vs Bref previous period); `storeId` drill-down on revenue / collections / sellers / overdue. A topic is listed iff at least one of its tools is allowed (so an employee keeps the invoice-based diagnostic, like a Bref seller); the allowed tools add their stats; when none answers, the main tool's own refusal (French REST message) is returned. Periods = Bref: today, week (last 7 days), month (1st → today), year (1 Jan → today), WAT.
- **Cost:** chat = 1 status-like check (rolled back), ≤ 4 provider calls, ≤ 8 tool calls (each the 21-8 bounded use case + 1 audit insert), 1 quota transaction; conversation list = 1 indexed query `LIMIT 100` (no JSON columns); get/put/delete = 1 PK statement scoped by user; batch delete = 1 `id = ANY(?)` statement; create = 1 count (index) + 1 insert; guided topic = 1–3 tool calls. No loops over rows or tenants.
- **Plans (decision):** ai-assistant granted to every plan (FREE included, like Bref Point's tab); free text gated by AI_CREDITS quotas of 19-8 (FREE 0 → `QUOTA_EXCEEDED`, upgrade path in the app). Default provider: disabled.
- **Implementation decisions:** one OpenAI-compatible adapter (`spring-ai-openai`, no auto-config; brings `spring-webflux`/`spring-retry` transitively, servlet app unchanged) serves the three providers, like Bref's OpenAI SDK + base URL; env vars `KEEVO_AI_PROVIDER`, `KEEVO_AI_API_KEY`, `KEEVO_AI_MODEL`, `KEEVO_AI_BASE_URL`. Body validation answers Keevo's 422 `VALIDATION_ERROR` (Bref 400); query/content checks 400. The scripted model exposes `useScript`/`reset` for tests (one shared Spring context). Corpus expectations are exact display strings (« **150 000 FCFA** ») plus the audited tool sequence.
- **After review (2026-11-10):** a question has a 90 s deadline (checked before each model call; provider read timeout 45 s, one retry); titles use a separate model call (10 s, no retry) and only when the company still has an AI credit (no unmetered model use on FREE); over 8 tool calls or once 24 000 characters were fed, the model answers without tools; an oversize result is a valid `{"erreur": …}`, never a cut JSON; credits used up between check and consume → the answer is delivered uncounted; a VIEWER may ask and keep conversations (`ViewerWriteGuardFilter` allow-list); PUT keeps absent fields; the 200-conversation cap is serialised per user (advisory lock + count + insert in one transaction).
- **Title deviation:** without a title and without remaining credit Bref refuses creation; Keevo falls back to the first 60 characters (never blocks a guided conversation).

## Verification

**Commands:**
- `kmvn.sh keevo/backend clean compile` -- BUILD SUCCESS.
- `wt-pkgs.sh keevo/backend 21-4 assistant architecture shared admin identity subscription` -- all green.

**Manual checks (if no CLI):**
- Local backend (dev, WhatsApp noop, FCM off, `KEEVO_AI_PROVIDER=fake`): topics, CRUD and a free-text question on a test tenant; with the default provider the chat answers 503 `AI_UNAVAILABLE`.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit `82085cd2`, branche `refonte/modulaire` et `develop`). Conventions : spec-16-11 ; protocole de synchronisation : spec-16-10.

### Ce qui est fait côté API
- Module `ai-assistant` (« Assistant IA ») : inclus dans **toutes** les offres (Gratuit compris), désactivé par défaut pour les entreprises qui existaient avant (l'owner l'active dans « Mes modules »). Tous les rôles (propriétaire, gérant, employé, lecteur — le lecteur peut poser des questions et garder ses conversations).
- Les **sujets guidés** de Bref Point sont calculés par le serveur (`GET /api/v1/ai/topics/{topic}`), **gratuits** (aucun crédit IA), avec les droits de chaque rôle (un employé ne voit que ses documents, les sujets propriétaire lui sont refusés).
- Les **questions libres** (`POST /api/v1/ai/chat`) : le modèle appelle des outils Keevo (jamais de SQL), **1 crédit IA par réponse obtenue** (rien en cas d'erreur), **une question à la fois par entreprise**.
- Les **conversations** sont enregistrées par utilisateur (mêmes formes que Bref : `messages` et `aiHistory` sont vos tableaux, stockés tels quels).
- Contrat complet (routes, corps, erreurs, sujets, corpus de parité) : `keevo/contracts/ai-assistant.json`. Code : `keevo/backend/src/main/java/com/keevo/assistant/` (contrôleurs `chat/adapter/in/rest/AiChatController.java`, `conversation/adapter/in/rest/AiConversationController.java`, `guided/adapter/in/rest/GuidedTopicController.java`).

### À utiliser
Toutes les réponses sont dans l'enveloppe Keevo `{ data }` ; une erreur est `{ error: "<message français à afficher>", code, domainCode, details? }`.

| Appel | Corps / paramètres | Réponse `data` |
|---|---|---|
| `GET /api/v1/ai/topics` | — | `{ topics: [{id, label, takesPeriod, takesStore, takesCompare, takesSearch}], periods: [{id, label, previousLabel}], freeTextAvailable }` |
| `GET /api/v1/ai/topics/{topic}` | `period` = `today` \| `week` \| `month` (défaut) \| `year` ; `compare=true` ; `storeId` (propriétaire) ; `search` (sujet `customers`) | `{ topic, title, text, period: {id, label, from, to} \| null, stats: [{key, label, value, unit: FCFA\|COUNT\|PERCENT}], rows: [{label, amount, detail, phone}] }` |
| `POST /api/v1/ai/chat` | `{ message (1–2000), history?: [{role: "user"\|"assistant", content ≤ 2000}] ≤ 20 }` | `{ reply, creditsRemaining }` (`null` = illimité) |
| `GET /api/v1/ai/conversations` | — | `[{id, title, messageCount, createdAt, updatedAt}]` (100 plus récentes) |
| `POST /api/v1/ai/conversations` | `{ firstMessage, title? }` | 201 `{id, title, messageCount, messages: [], aiHistory: [], createdAt, updatedAt}` |
| `GET /api/v1/ai/conversations/{id}` | — | la conversation complète |
| `PUT /api/v1/ai/conversations/{id}` | `{ messages?, aiHistory?, messageCount?, title? }` (champ absent = valeur conservée) | 204 |
| `DELETE /api/v1/ai/conversations/{id}` | — | 204 (idempotent) |
| `DELETE /api/v1/ai/conversations/batch` | `{ ids: [1..100] }` | 204 |

Sujets (`topic`) : `diagnostic` (mois en cours), `revenue`, `overdue`, `collections`, `sellers`, `shops`, `customers`, `call_list` (liste d'appel, avec `phone`).

Erreurs et réaction de l'app :
- 422 `VALIDATION_ERROR` (corps invalide : question vide ou > 2000 caractères, historique > 20, rôle autre que user/assistant…) et 400 `VALIDATION_ERROR` (sujet ou période inconnus, filtre non proposé) → afficher `error`.
- 403 `QUOTA_EXCEEDED` (`details.metric = AI_CREDITS`, `period`, `limit`, `resetAt`, `planLabel`) → afficher `error` + action « Changer d'offre » (équivalent de `errors.planAiCreditsExhausted` de Bref). L'offre Gratuite n'a pas de crédit IA : les questions libres y sont refusées ainsi, les sujets guidés restent disponibles.
- 403 `MODULE_DISABLED_BY_OWNER` / `MODULE_NOT_ENTITLED` / `MODULE_READ_ONLY` → masquer l'onglet ou afficher `error` (« Mes modules »).
- 403 (`FORBIDDEN`, `MODULE_NOT_ENTITLED`) sur un sujet → afficher `error` ; ne pas proposer ce sujet (utiliser la liste de `GET /topics`).
- 404 `AI_CONVERSATION_NOT_FOUND` → retirer la conversation de la liste locale.
- 409 `AI_CONVERSATION_LIMIT` (200 conversations) → proposer de supprimer d'anciennes conversations.
- 429 `AI_QUESTION_IN_PROGRESS` → « Une question est déjà en cours… » ; réessayer après la réponse.
- 502 `AI_PROVIDER_ERROR`, 503 `AI_UNAVAILABLE` / `AI_ANSWER_FAILED` → message d'erreur dans le chat (`isError`), aucun crédit consommé ; si `freeTextAvailable = false`, masquer la saisie libre.

### À implémenter côté mobile
1. Remplacer les appels Bref `/v1/ai/*` par `/api/v1/ai/*` et lire `data` dans l'enveloppe Keevo.
2. Construire les puces d'accueil à partir de `GET /api/v1/ai/topics` (ne plus filtrer par rôle dans l'app) ; masquer la saisie libre si `freeTextAvailable` est faux.
3. Remplacer le calcul local des sujets (statistiques de factures) par `GET /api/v1/ai/topics/{topic}` : afficher `title`, `text`, puis `stats` (formater `value` selon `unit` : FCFA → « 450 000 FCFA », PERCENT → « 42,9 % », COUNT → nombre) et `rows` (`label`, `amount` en FCFA, `detail`, bouton d'appel si `phone`).
4. Comparaison : rappeler le même sujet avec `compare=true` (sujets `revenue` et `collections`). Descente par boutique : passer `storeId` (liste des boutiques de l'app, jamais affiché). Clients : `customers?search=<nom>`.
5. Questions libres : envoyer `history` (20 derniers messages user/assistant), afficher `reply` (Markdown simple, gras), rafraîchir le quota (« Mon plan ») après une réponse ; `creditsRemaining` peut servir d'indication.
6. Conversations : mêmes appels que Bref (création, sauvegarde après chaque échange, suppression unitaire et groupée) ; le plafond de 20 messages reste géré par l'app comme dans Bref.
7. Hors ligne : sujets et questions nécessitent le réseau (message « Connexion requise ») ; la liste des conversations peut rester en cache local.
8. Aucune donnée technique à l'écran : jamais d'identifiant, uniquement les libellés français fournis.

### Implémentation de référence
Aucune dans `keevo/app` (application Flutter = client de test). Référence fonctionnelle : `features/bref-point/*` du dépôt AI/bref.

### Critères d'acceptation mobile / scénarios de test
- Étant donné un propriétaire (offre Pro, module activé), quand il touche « Chiffre d'affaires › Ce mois », alors l'écran affiche le CA et la marge du mois sans consommer de crédit (`GET /api/v1/ai/topics/revenue?period=month`).
- Étant donné un employé, quand il ouvre l'assistant, alors les puces « Boutiques » et « Chiffre d'affaires » n'apparaissent pas et « Liste d'appel » ne montre que ses clients.
- Étant donné une question libre, quand le serveur répond, alors la réponse s'affiche et le compteur « Crédits IA » de « Mon plan » augmente de 1 ; en cas d'erreur 502/503 il ne bouge pas.
- Étant donné l'offre Gratuite, quand l'utilisateur pose une question libre, alors le message de quota et l'action « Changer d'offre » s'affichent ; les sujets guidés restent utilisables.
- Préparation curl : `TOKEN=…` (connexion d'un compte de test) puis `curl -H "Authorization: Bearer $TOKEN" https://<api>/api/v1/ai/topics` et `curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"message":"Combien me doivent mes clients ?"}' https://<api>/api/v1/ai/chat`.

### Clé de suivi mobile
`m21-4-ai-assistant` (bloc MOBILE de sprint-status.yaml).

## Dashboard super-admin (autre dépôt)

Aucune nouvelle route super-admin : le module `ai-assistant` apparaît automatiquement dans l'écran « Offre » (modules par plan, données de V65 : inclus dans tous les plans) et dans les modules de la fiche entreprise, comme les autres modules. Les crédits IA restent les quotas `AI_CREDITS` déjà éditables (19-8). Pas de clé `d21-4`.

## Auto Run Result

Status: done

**Summary.** New module `ai-assistant` (« Assistant IA », Bref « Bref Point », FUS-FR27 / FR39), package `com.keevo.assistant`: conversations per user in `kv_x.ai_conversations` (Bref CRUD + bulk delete), guided topics answered for free by the 21-8 tools (`GET /api/v1/ai/topics[/{topic}]`, 8 topics, Bref periods and comparison, store drill-down, call list), free-text questions (`POST /api/v1/ai/chat`) answered by a model that calls the allowed tools on the request thread through `AiToolRegistry` — never SQL — with hard bounds, one AI credit (19-8) per answered question only, one question at a time per company, every UUID filtered from replies. Provider behind a port: OpenAI / Gemini / Ollama through one OpenAI-compatible Spring AI adapter (`spring-ai-openai` 1.1.8), disabled by default, scripted fake for dev/tests. Contract + 32-question parity corpus in `keevo/contracts/ai-assistant.json`.

**Files.** `pom.xml` (spring-ai-openai); `assistant/**` (module, chat service + prompt + model port and adapters, conversation service + JDBC repository, guided topic service, 3 controllers); `ErrorCode` / `GlobalExceptionHandler` / `ErrorMessages` (6 AI codes); `ViewerWriteGuardFilter` (5 assistant writes); `application.yml` + `.env.example` (`KEEVO_AI_*`); `keevo/modules/ai-assistant/module.json`; `db/modules/ai-assistant/V1__ai_conversations.sql`; `db/migration/V65__ai_assistant_module.sql`; `keevo/ai/fake-assistant-script.json`; `docs/entitlements/endpoints.md` (regenerated, 9 routes); tests (assistant unit + `AiAssistantFullContextTest` 9 cases + `AiAssistantParityCorpusTest`; module-catalogue counts, go-live pin, viewer pin, handler statuses, FREE grant pin).

**Decisions.** Plans: ai-assistant granted to every plan, FREE included (Bref Point is a tab of every account); free text bounded by the AI_CREDITS quotas (FREE 0 → `QUOTA_EXCEEDED`; PRO 500 / month · 20 / day; TEAM 750 · 30; BUSINESS unlimited · 50). Default provider: disabled. Manifest category premium (invariant: every paid module is premium). Guided topics on new Keevo endpoints (no Bref stats endpoint exists in Keevo). VIEWER may ask and keep conversations.

**Review.** 5 layers, 47 findings — high 2 (same root: VIEWER blocked), medium 17, low 28. Patched entries: high 2, medium 15, low 18 (deadline, title model + credit gate, budgets without cut JSON, PUT keeps absent fields, atomic cap, quota race, compare tool / refusal, formatting, URL normalisation, env template, tests for statuses, FREE grant, four topics, budget, provider settings, VIEWER and MANAGER). Deferred 1 → 16-6 (purge of a removed member's conversations; 16-6 reopened). Rejected 11 with reasons (triage log).

**Follow-up review recommended: true** — patched high: the VIEWER read-only rule now lets 5 assistant writes through (safe only because every tool is read-only — a future write tool must revisit the allow-list); provider timeouts, retry and deadline were never exercised against a real model (fake only, by rule).

**Verification.** Clean compile. Packages after the last fix: assistant 40, architecture 137, shared 1 345, admin 690 — 0 failure; earlier in the run (code unchanged since): identity 866, subscription 153, acceptance 15. Parity corpus: 32/32 questions, expected tools audited, exact figures, no UUID. Live keevo_dev (profile dev, WhatsApp noop, FCM off): V65 applied; fake provider — topics (8), diagnostic, comparison, conversation create/save/list, two questions answered with tools audited ASSISTANT, AI credits 0 → 2, no UUID in the store answer, module table created on first use; default provider — `freeTextAvailable=false`, chat 503 `AI_UNAVAILABLE`, guided topic 200, title falls back, nothing counted. kv_11b5zm: schema upgrade only (V65 owner-disable row), no data touched.

**Residual risks.** In-memory guards (one question per company, title limiter) assume one backend instance; conversations of removed members linger until 16-6; real-provider latency and output quality are unmeasured.
