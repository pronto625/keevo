---
title: '19-11c — Other super-admin screens and jobs without a per-account loop'
type: 'refactor'
created: '2026-11-11'
status: 'done'
baseline_revision: '7e72c570120108aa82d023396e0b5a41bb82fd07'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-11b-ecrans-admin-sans-boucle-par-compte.md'
warnings: ['oversized', 'multiple-goals']
deferred: []
---

<intent-contract>

## Intent

**Problem:** After 19-11b, seven super-admin reads and three background jobs still run one statement per tenant schema (tenant list enrichment + « dernière activité » filter, sync monitoring, revenue current plans + churn GMV, alerts feed, health probe, plan-expiry alerts, global push audience, feedback inbox labels), and the 19-11b screens lack the indexes that make their per-branch reads bounded (catalogue order, trigram search, last-activity legs, adoption probes); the catalogue CSV export holds every product of every tenant in memory. Routed from 19-11b (brief 19.11c) and, for retention of answered invitations, from review 19-6b (R1, FUS-NFR11).

**Approach:** Same pattern as 19-11b: tenants from one public query, table presence in one catalogue query (`CrossTenantSql.withPresence`), figures from chunked `UNION ALL` statements (`CrossTenantSql.query`, 100 tenants each, per-tenant retry) merged in Java; public per-tenant facts in one `GROUP BY`. Indexes as idempotent module migrations (pos, core, catalog, inventory, reporting) and one public migration V79. The export streams chunk by chunk to the HTTP response. A nightly set-based purge removes invitations that will never become memberships.

## Boundaries & Constraints

**Always:**
- Response shapes, field names, sort orders, filters, French messages unchanged (additive only; dashboard in another repo).
- Same business definitions (last activity = `TenantLastActivitySql`, plan = latest subscription row, alert thresholds, statuses in scope of each screen, tenant display name as each screen read it before).
- Statement count of every endpoint/job = constant + ⌈T/100⌉ per figure; never a statement per tenant except the per-tenant retry of a failing chunk.
- A module table absent from a schema is never a statement error (figure 0 / NULL / branch omitted); a broken schema is skipped with a WARN (tenant id only); connection / resource failures fail the call.
- Schema names interpolated only after `TenantSchema.validate` (≤ 20 lines above, local helper); tenant ids as parsed-UUID literals; user input bound.
- Module migrations idempotent forever (`IF NOT EXISTS`, `lock_timeout`), no data change.

**Never:** no summary table, no cache; no change in `identity` beyond the invitation purge (new job + one port/adapter method), nothing in `shared/security`; no offset-to-keyset contract change on `/admin/catalog/products`; no change to `keevo/app` / `keevo/dashboard`; no public migration other than V79.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Count does not grow | 3 then 12 seeded tenants | Each endpoint/job issues the same number of JDBC statements | — |
| Last-activity filter | 60 candidates, `lastActivityFrom` set | Same ids and order (newest tenant first), total = matches | candidate without schema skipped |
| Sync tables missing | tenant without `sync_conflicts_log` | failed ops kept, conflicts 0 | no statement error |
| Revenue plans | tenant with no `subscriptions` table | not counted (as before) | — |
| Export | 2 tenants, filters | Every matching row once, CSV unchanged per row; rows grouped by company (name order), most recent first | 422 on bad filter before any byte |
| Invitation purge | DECLINED/CANCELLED answered > 90 d, PENDING expired > 90 d, ACCEPTED old | first three deleted, ACCEPTED and recent kept | one DELETE per run |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/admin/crosstenant/CrossTenantSql.java` -- add `forEachChunk` (rows handed per chunk, not accumulated) used by `query` and the export.
- `.../admin/crosstenant/TenantTable.java` -- add narrow specs: `STORES_ACTIVE`, `EMPLOYEE_NAMES`, `SYNC_OPS_STATUS`, `SYNC_CONFLICTS`, `SYNC_ERRORS`, `SUBSCRIPTION_EXPIRY`, `DEVICE_TOKENS`.
- `.../admin/TenantLastActivitySql.java` -- sales leg as `MAX(COALESCE(...))` capped by CASE (index-servable), same value.
- `.../admin/tenant/application/service/AdminTenantService.java:107-180,330-420` -- page enrichment (2 unions), activity filter (1 union over candidates).
- `.../admin/sync_monitoring/application/service/AdminSyncMonitoringService.java:47-110` -- overview + list loops.
- `.../admin/revenue/application/service/AdminRevenueService.java:358-395` -- `countCurrentPlans`, `gmvBefore` (churn).
- `.../admin/alerts/adapter/out/persistence/JdbcAlertSourcesAdapter.java:114-145` -- expired plans, report failures.
- `.../admin/platform/adapter/out/persistence/JdbcPlatformProbeAdapter.java:62-78` -- sync refusals.
- `.../admin/subscription/application/service/AdminPlanService.java:330-347,412-430` -- expiring alerts.
- `.../admin/notification/adapter/out/persistence/JdbcPushAudienceAdapter.java` + `PushAudiencePort` + `GlobalNotificationDispatcher.java:100-115` -- tokens of all tenants by chunk.
- `.../admin/feedback/application/service/AdminFeedbackLabelService.java:96-140` -- employee names in one union.
- `.../admin/catalog/application/service/AdminCatalogService.java` -- `lower(p.name) LIKE` (trigram), streamed `export(query, sink)`; `adapter/in/rest/AdminCatalogController.java:63-90`, `ProductsCsvWriter` -- write to the response.
- `.../identity/employee/domain/port/out/MembershipInvitationRepository.java`, `.../adapter/out/persistence/impl/JdbcMembershipInvitationRepository.java`, new `.../application/service/MembershipInvitationRetentionJob.java` -- purge.
- `keevo/backend/src/main/resources/db/modules/{pos/V3,core/V4,catalog/V4,inventory/V3,reporting/V2}` -- tenant indexes; `db/migration/V79__admin_cross_tenant_indexes.sql` -- public indexes.
- Tests: unit tests of the touched services (mock `CrossTenantSql` as in 19-11b), new `AdminScreensCrossTenant2IntegrationTest` (statement count 3 vs 12, missing table, broken schema, filter), `SchedulerLockCoverageTest` pin, invitation purge PostgreSQL test.

## Tasks & Acceptance

**Execution:**
- `CrossTenantSql`, `TenantTable`, `TenantLastActivitySql` -- shared building blocks -- reuse across services.
- `AdminTenantService` -- schemas from the list query, `withPresence` for the page, one union for store count + last activity, one union for owner names (a broken employees table only loses names), filter = union over candidates with the range in SQL -- O(1)+⌈n/100⌉ statements.
- `AdminSyncMonitoringService` -- per-tenant counts union + one `GROUP BY tenant_id` on `user_sync_state` -- same figures.
- `AdminRevenueService` -- current plan kinds union; churn GMV union with per-branch window.
- `JdbcAlertSourcesAdapter`, `JdbcPlatformProbeAdapter`, `AdminPlanService` -- unions, output order kept (tenant name / days left).
- `PushAudiencePort.forEachTenantTokens` + adapter + dispatcher + `countDeviceTokens` -- one union per chunk.
- `AdminFeedbackLabelService` -- one union for names.
- `AdminCatalogService` + controller + CSV writer -- trigram-friendly search, streamed export.
- Migrations -- indexes listed in Design Notes.
- Invitation purge -- port method, adapter DELETE, nightly ShedLock job, scheduler pin.
- Tests + `docs/entitlements/endpoints.md` regenerate.

**Acceptance Criteria:**
- Given 3 then 12 provisioned tenants, when each endpoint/job read of this story is called, then the number of JDBC statements is identical.
- Given a tenant lacking an optional table, when its screen is read, then no statement fails and its other figures are kept.
- Given the existing PostgreSQL suites of 9-1/9-3/9-5/9-8, when they run on the new code, then their assertions hold (or the change is a recorded decision).
- Given a catalogue export, when downloaded, then the CSV has the same header and one row per matching product.

## Spec Change Log

## Review Triage Log

### 2026-11-11 — Review pass
- verdicts: 36 findings — high 0, medium 7, low 19, false 4, maybe-false 6
- findings:
  - Intent alignment
    - `[low]` `[reject]` Statement count measured at service/adapter level, not HTTP — controllers are thin delegations (no SQL); same choice as 19-11b.
    - `[low]` `[reject]` Broken-schema skip asserted on one path only (health) — the skip is one shared mechanism (`CrossTenantSql.forEachChunk` retry, `CrossTenantSqlChunkTest`), every reader goes through it; 19-11b covered it on three more screens.
    - `[maybe-false]` `[reject]` EXPLAIN runs hand-written SQL shaped like production, not the production text — the shapes are copied from the production legs (same expressions / predicates); would be settled by EXPLAIN on the generated union; the planner matches expressions, not text. If-true grade low.
    - `[low]` `[reject]` Behaviour changes (literal `%`/`_`, streamed export) — decisions D1/D3, documented in the Dashboard section; wire contract unchanged.
    - `[false]` `[reject]` identity / shared boundary crossed — the intent excludes the 19-14 area (roles, security); the purge is three new identity files + one pinned scheduler line, no roles / security code.
    - `[low]` `[reject]` Purge is a separate job, not added to « the platform retention job » — there is no single platform retention job (the notification one is per-tenant, messaging-owned); a public-table purge in identity keeps module ownership (D5).
  - FUS-NFR11 layer
    - `[medium]` `[reject]` Indexes built in module migrations (SHARE lock on tenant tables) — same established path as pos V2 (20.9), catalog V3 (21.8), crm V3/V4, invoicing V14/V17 after 16.12; tenant tables are per-shop sized, `lock_timeout = 10s` makes a busy tenant's module upgrade fail and retry at the next `ensureFoundation`, never a long write block. Deployment note added to the Auto Run Result (build on a quiet hour). The keyset-index registry is for the sync (cursor, id::text) shape only.
    - `[medium]` `[patch]` Export holds one 100-tenant chunk — `forEachChunk(tenants, maxChunk, …)` overload, export uses 10 tenants per statement (`EXPORT_CHUNK`).
    - `[low]` `[reject]` No partial index for FAILED sync operations — 7-day window already served by `idx_sync_ops_log_processed`; a new index on a hot sync table would cost more than it saves.
    - `[low]` `[reject]` Own tenants query + `withPresence` in alerts / revenue — constant +1 statement; keeps the screens' own scope and display name (contract).
    - `[low]` `[reject]` Presence query checks every spec — pre-existing 20-13 design, constant per call, O(T × specs) catalogue probes; not a loop.
    - `[low]` `[reject]` `deviceTokens(tenant)` now 2 statements and unused in production — kept as the port's single-company API (tests, future callers); documented.
    - `[low]` `[reject]` Catalogue list still OFFSET — decision D2 (contract), each branch now an index scan of offset+size rows.
    - `[low]` `[reject]` Detail enrichment 3 statements instead of 2 — single tenant, constant.
  - Blind hunter
    - `[medium]` `[patch]` Export cut mid-stream looks complete — controller writes a last line « Export interrompu : fichier incomplet, relancez l'export » then rethrows; test `exportProducts_failureMidStream_marksTheFileIncomplete`.
    - `[low]` `[patch]` Controller comment « same file as before » — corrected (grouped by company).
    - `[medium]` `[patch]` « One chunk » memory bound — same root cause as the FUS export item (10 tenants per statement).
    - `[medium]` `[reject]` Non-concurrent index builds — same as the FUS item.
    - `[low]` `[reject]` Presence cost grows with specs — same as the FUS item.
    - `[low]` `[reject]` Single-tenant paths +1 statement — same as FUS items.
    - `[low]` `[patch]` Stale comments / dead code — dispatcher Javadoc, `skipInvalid` folded into the tenant filter (WARN kept), `ProductsCsvWriter.write(List)` removed, last-activity audit comment made exact.
    - `[false]` `[reject]` Epics ticks / sprint-status / spec not in diff — spec and sprint-status are committed with the story (finalize); keyset rationale is D2.
    - `[low]` `[patch]` Retention settings not in `application.yml` — `keevo.invitation-retention.{cron,days}` with `KEEVO_INVITATION_RETENTION_*` env variables.
    - `[maybe-false]` `[reject]` Multi-chunk merge not exercised on PostgreSQL — chunk iteration and retry covered by `CrossTenantSqlChunkTest`; per-chunk merges are associative sums / maps keyed by tenant; if-true low.
    - `[false]` `[reject]` Feedback loses names of deleted companies — `labels()` already answered « Entreprise supprimée » / « Utilisateur inconnu » for a deleted company before this change (tenantName null branch).
    - `[maybe-false]` `[patch]` OpenAPI no longer documents text/csv — `@ApiResponse(content = @Content(mediaType = "text/csv"))` added; endpoints table unchanged (route same, architecture test green).
  - Edge case hunter
    - `[medium]` `[patch]` Truncated 200 CSV on mid-stream failure — same root cause as the blind-hunter item.
    - `[low]` `[patch]` `deviceStats()` SQL error now fails the whole list — fail-soft restored (WARN, empty device columns), resource failures still rethrown.
    - `[low]` `[patch]` Dead `skipInvalid` — same root cause as the cleanup item.
    - `[low]` `[patch]` Orphan `ProductsCsvWriter.write` — removed.
    - `[maybe-false]` `[reject]` `deviceTokens` could bring N+1 back — documented as single-company; no loop caller.
    - `[false]` `[reject]` Spec names `deviceTokensByTenant` — fix would edit the spec (wording only).
    - `[maybe-false]` `[reject]` System tenant feedback loses names — KV-ADMIN never submits app feedback (alerts / inbox exclude it); if-true low.
  - Verification gap
    - `[medium]` `[patch]` `forEachTenantTokens` grouping never checked on PostgreSQL — `forEachTenantTokens_groupsTokensByCompanyInOrder` (two companies, one without tokens, one broken; order and own sorted tokens asserted).
    - `[low]` `[patch]` Earliest employee row for feedback names untested — second, later row added in `AdminFeedbackLabelServicePostgresIntegrationTest.labels`.
    - `[maybe-false]` `[patch]` Export mid-stream failure — same root cause as the blind-hunter item.


## Design Notes

**Pattern.** Composite chunked query (20-13/19-11b) — reasoning of 19-11b stands (arbitrary windows, single source of truth, lock budget). Streaming export = Iterator/sink over chunks (no full list, no global sort).

**Cost (T tenants holding the tables, c = 100, P page size).**
- Tenant list: count 1 + list 1 + presence 1 + metrics ⌈P/c⌉ + owners ⌈P/c⌉. Activity filter: candidates 1 + presence 1 + ⌈C/c⌉ (each branch: 4 index probes).
- Sync overview/list: 1–2 public + 1 tenants + 1 presence + ⌈T/c⌉. Revenue plans: 1 + 1 + ⌈T/c⌉; churn: + ⌈K/c⌉ (K churned).
- Alerts expired / report failures, health refusals, plan alerts: 1 + 1 + ⌈T/c⌉ each. Push: 1 + 1 + ⌈T/c⌉ (memory = all tokens, few per tenant). Feedback: 2 + 1 + ⌈S/c⌉ (S ≤ page).
- Export: 1 tenants + 1 presence + ⌈T/10⌉ (`EXPORT_CHUNK`), memory = the rows of 10 companies.
- Purge: 1 DELETE per night.

**Indexes.** pos V3 `sales ((COALESCE(occurred_at, created_at)))` (last-activity max, adoption window); core V4 partial `audit_log (occurred_at DESC)` on merchant rows; catalog V4 `products (updated_at DESC NULLS LAST, id)` (= branch order); inventory V3 `inventory_sessions (started_at)`, `stock_transfers (occurred_at)`; reporting V2 `reports (delivery_status, (COALESCE(last_attempt_at, created_at)))`; V79 public `user_daily_activity (tenant_id, first_seen_at)`, `user_sync_state (tenant_id)`, partial `membership_invitations ((COALESCE(responded_at, expires_at))) WHERE status <> 'ACCEPTED'`.

**Decisions (default, conservative).**
- D1 — The CSV export rows are grouped by company (company name order), most recently updated first inside a company: a global order would need all rows in memory. Same columns and rows.
- D2 — Catalogue keeps offset paging (contract); with the new index each branch reads offset+size index entries; deep pages are a human-paced console use.
- D3 — Catalogue search: `%` and `_` typed by the user are literal characters (escaped), matching lower-case like ILIKE did.
- D4 — Sync monitoring: a connection failure now fails the screen instead of reading 0 (9-7 rule, same as the other screens).
- D6 — A malformed company id in the catalogue filters answers 422 « Compte invalide » before any CSV byte (was a SQL error, 500).
- D7 — The new tenant indexes ship as idempotent module migrations, like pos V2 / catalog V3 (not the concurrent keyset-index job): per-shop tables, `lock_timeout = 10s` makes a busy tenant's upgrade retry later instead of blocking writes. Deploy at a quiet hour.
- D5 — Invitation retention (routed 19-6b → 19-11): DECLINED / CANCELLED answered and PENDING expired more than 90 days ago are deleted nightly (03:55 Douala, ShedLock); ACCEPTED rows kept (identifier kind shown to the company). 90 d > the 30-day re-invite block.

## Verification

**Commands:**
- `wt-pkgs.sh <worktree>/keevo/backend t1911c admin.tenant admin.sync_monitoring admin.revenue admin.alerts admin.platform admin.subscription admin.notification admin.feedback admin.catalog admin.crosstenant admin.analytics admin.operations identity.employee architecture shared.infrastructure` -- expected: all green.

**Manual checks (if no CLI):**
- Live on `keevo_dev_t1911c` (port 4611): each touched endpoint answers 200; CSV export downloads; log shows no cross-tenant error.

## Mobile (React Native — dépôt AI/bref)

Statut : rien à faire côté mobile. Cette story ne touche que des écrans et tâches super-admin (`/api/v1/admin/**`) et une purge nocturne côté serveur ; aucune réponse lue par l'app ne change. Pas de clé `m19-11c`.

## Dashboard super-admin (autre dépôt)

Statut : API livrée sans changement de contrat (mêmes routes, mêmes champs, mêmes tris et filtres, mêmes messages). Pas de clé `d19-11c` : rien à implémenter.

Ce qui change, à connaître seulement :
- `GET /api/v1/admin/catalog/products/export` : même fichier CSV (mêmes colonnes, mêmes libellés français, BOM, `;`), mais les lignes sont regroupées par entreprise (ordre alphabétique des entreprises), les plus récemment modifiées d'abord dans chaque entreprise (avant : toutes entreprises mêlées). Le fichier arrive en flux (téléchargement progressif) ; une erreur de filtre répond toujours en JSON avant tout octet.
- `GET /api/v1/admin/catalog/products` et l'export : dans la recherche, `%` et `_` tapés sont des caractères ordinaires (avant : jokers). Même recherche « contient », insensible à la casse.
- `GET /api/v1/admin/sync/**` (vue d'ensemble, liste) : une panne de base répond maintenant en erreur (5xx) au lieu d'afficher des zéros — afficher le message d'erreur générique.
- Les écrans comptes, synchronisation, revenus, alertes, santé et les alertes d'offres répondent avec un nombre de requêtes base constant jusqu'à 100 entreprises (puis +1 par tranche de 100) : aucun changement d'usage.
- Invitations : les invitations refusées, annulées ou expirées depuis plus de 90 jours sont supprimées chaque nuit (03:55, heure de Douala). Aucun écran super-admin ne les affiche.

## Auto Run Result

Status: done.

**Summary.** The remaining super-admin reads and jobs no longer run a statement per company: tenant list (figures of the page in 2 unions, « dernière activité » filter in 1 union with the range in SQL), sync monitoring (1 union + 1 `GROUP BY`), revenue (current plans and churn GMV in unions), alerts feed, health refusals, plan-expiry alerts, global push audience (`forEachTenantTokens`, 100 companies per statement), feedback names (1 union). Indexes for the per-branch reads (pos V3, core V4, catalog V4, inventory V3, reporting V2, public V79), last-activity sales leg made index-servable, catalogue search on the trigram form (`lower(name) LIKE`, literal `%`/`_`), streamed CSV export (10 companies per statement, « Export interrompu » line if cut). Routed 19-6b item done: nightly purge of refused / cancelled / expired invitations (> 90 days).

**Files.** `admin/crosstenant/CrossTenantSql` (`forEachChunk`, chunk-size overload), `TenantTable` (7 narrow specs), `TenantLastActivitySql`; services `AdminTenantService`, `AdminSyncMonitoringService`, `AdminRevenueService`, `AdminPlanService`, `AdminFeedbackLabelService`, `AdminCatalogService` (+ controller, CSV writer); adapters `JdbcAlertSourcesAdapter`, `JdbcPlatformProbeAdapter`, `JdbcPushAudienceAdapter` (+ port, dispatcher); identity: `MembershipInvitationRetentionJob` + port + JDBC adapter; migrations V79 + 5 module scripts; `application.yml` (`keevo.invitation-retention`); tests: `AdminOtherScreensCrossTenantIntegrationTest` (statement count 3 vs 12, real SQL, EXPLAIN on the new indexes), `MembershipInvitationRetentionPostgresIntegrationTest`, rewritten unit tests of every touched service, tenant-shape reference, scheduler pin, foundation core version.

**Review.** 36 findings (medium 7, low 19, false 4, maybe-false 6). Patched: export memory bound, cut export marked, OpenAPI text/csv, push grouping PostgreSQL test, earliest-employee-name test, sync device figures fail-soft, retention config, stale comments / dead code. Rejected with reasons in the triage log (index build path = D7, keyset = D2, constant extra statements). Nothing deferred. Own verification also fixed 5 interpolation-guard hits and the foundation core version.

**Follow-up review:** recommended — two or more medium entries patched; unverified risk: the module index builds on the production tenants (lock wait on a busy `sales` / `audit_log`) and the streamed export behaviour behind the production proxy (partial download + last line).

**Verification.** `wt-pkgs.sh … t1911c`: admin/tenant 49, admin/sync_monitoring 20, admin/revenue 63, admin/subscription 70, admin/alerts 45, admin/platform 35, admin/notification 65, admin/feedback 20, admin/catalog 22, admin/crosstenant 22, admin/analytics 90, admin/operations 54, identity/employee 250, shared/infrastructure 669, architecture 160 (1 skipped, pre-existing) — all green. Live on `keevo_dev_t1911c` (port 4611, WhatsApp noop, FCM off): 14 endpoints 200 with 3, 12 then 21 accounts; export CSV header OK; malformed company id 422 « Compte invalide »; V79 applied, new indexes present in every tenant schema; no ERROR in the log; backend stopped by port, DB dropped.

**Residual risks.** Index builds at the first boot after deployment (D7); catalogue deep pages still OFFSET (D2); presence query cost grows with the number of table specs (20-13 design).

