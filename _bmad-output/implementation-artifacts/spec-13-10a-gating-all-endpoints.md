---
title: '13.10a — Enforce the Free/Premium classification on every existing endpoint, sync op and job (backend)'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: '08f68d494c661fa4098fa85d2c63eea568654189'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/plan-refonte-modulaire.md'
  - '{project-root}/spec-socle-modulaire.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-5-entitlement-service.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-9-keevomodule-crm-reporting.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The Free/Premium classification validated on 15/06/2026 (plan-refonte-modulaire.md §6.1) is defined but not enforced: in V1 only exports check the plan; a Free tenant uses transfers, counting, weekly reports, alerts, profitability and report configuration (MOD-FR14, gap G2). Story 13.10 is split: **13.10a = backend enforcement** (this spec); 13.10b = the app handling the new 403s (French message, entry points hidden).

**Approach:** Model each premium capability of §6.1 as a paid **feature module** (manifest, no tables), annotate every REST handler with the module that owns it (`@RequiresEntitlement`, READ/WRITE) or an explicit exemption, map sync operations and scheduled/event jobs to the same modules, and add a tested endpoint→entitlement table. A **transition rule** keeps today's behaviour for every tenant until the 16.5 backfill materialises `tenant_modules` rows.

**Decisions (autonomous run, user delegated all choices 2026-10-28; classification per plan §6.1):**
- **Paid feature modules** (`free: false`, category `premium`, no migrations, French display names): `multi-store` (multi-store stock overview + inter-store transfers; requires `core`, `store`, `inventory`), `counting` (assisted inventory; requires `core`, `inventory`), `advanced-reports` (weekly report, trends, leaderboard; requires `core`, `reporting`), `report-config` (report preferences/configuration, FR60; requires `core`, `reporting`), `alerts` (automatic stock alerts + owner notifications such as price changes; requires `core`, `messaging`), `profitability` (requires `core`, `reporting`), `exports` (PDF/Excel exports; requires `core`, `reporting`). Free keeps: core, catalog (plan limit), pos (sale/closure), inventory base (stock, thresholds), store (1 store via plan limit), crm basic, end-of-day report (fixed), sync, messaging base. Plan limits (stores, products, employees) stay `PLAN_LIMIT_EXCEEDED` via the existing guard.
- **Transition rule** (single place: `ModuleStateRules`; the tenant's `plan_type`/subscription state is read in the same row query): a **paid** module with **no row** is ENABLED iff the tenant's current plan is PREMIUM or an unexpired PREMIUM_TRIAL, else DISABLED. Rows (from 13.8a or the 16.5 backfill) always win. Free modules keep the 13.2 rule. Net effect today: Premium/trial tenants keep everything, Free tenants lose the §6.1 premium features — exactly the classification; the 16.5 backfill then writes rows with the same outcome.
- **REST**: every `@RestController` handler method carries `@RequiresEntitlement(module, access)` (GET → READ, other verbs → WRITE) or `@EntitlementExempt(reason)` (auth, onboarding, registration, health, super-admin `/admin/**`, sync push/pull — gated per operation —, owner `GET /tenants/me/modules`, feedback/tutorials = core). Free modules are annotated too (crm, reporting, catalog, pos, inventory, store…) so a DISABLED free module is enforced (routed from 13.9). An architecture test fails the build on any handler without one of the two annotations; a generated **endpoint → entitlement table** (method, path, module, access) is checked in as `docs/entitlements/endpoints.md` and compared by a test (regenerate with a documented flag).
- **Sync**: handlers of premium operations declare the premium module: `STOCK_TRANSFER` → `multi-store`; `CREATE_INVENTORY_SESSION`, `SAVE_INVENTORY_COUNT`, `VALIDATE_INVENTORY` → `counting` (12.7 guard then answers `MODULE_INACTIVE`, held on the device).
- **Jobs/events**: the weekly report scheduler and trends → `advanced-reports`; stock-alert and owner-notification listeners → `alerts`; exports job → `exports`; `@ModuleScoped`/the 13.9 reporting gate pattern; the existing `ExportService` plan check is replaced by the `exports` entitlement (one source).
- **Errors**: `MODULE_NOT_ENTITLED` (403, 13.5 message) — no new code; plan limits unchanged.
- **`@RequiresPermission`** (routed from 13.5): not built here — the existing role checks stay; the first module with its own permissions (Invoicing, 14.1) introduces it (routed).
- **Not here**: Flutter (13.10b), backfill (16.5), plan profiles (16.8), bundles (16.2).

## Boundaries & Constraints

**Always:** a Premium or unexpired-trial tenant sees no change; Free tenants lose only §6.1 premium features; every handler annotated or exempt (build-enforced); all test packages green; architecture + prod-classpath green.

**Never:** no writes to `tenant_modules` outside the lifecycle, no data change, no deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Free tenant, transfer | POST transfer | — | 403 MODULE_NOT_ENTITLED |
| Free tenant, counting session | POST session | — | 403 |
| Free tenant, weekly report / profitability / export / report config | GET/POST | — | 403 |
| Free tenant, sale / product / client / EOD | normal ops | 200 as today | — |
| Free tenant, 2nd store | POST store | — | PLAN_LIMIT_EXCEEDED (unchanged) |
| Premium tenant | all of the above | 200 | — |
| Trial expired | PREMIUM_TRIAL past end | premium → 403 | — |
| Row ACTIVE for a Free tenant | `counting` row ACTIVE (super-admin grant) | 200 | — |
| Row DISABLED for a Premium tenant | `exports` DISABLED | 403 | — |
| Free tenant offline transfer | sync push STOCK_TRANSFER | REJECTED MODULE_INACTIVE | held on device |
| Weekly report job | Free tenant | skipped | — |
| Handler without annotation | new controller | — | build fails |
| crm DISABLED | GET clients | 403 | — |

</frozen-after-approval>

## Code Map

- 53 `@RestController` classes / ~176 handler methods under `keevo/backend/src/main/java/com/keevo/**/adapter/in/rest/`.
- `shared/module/` (`ModuleStateRules`, `JdbcTenantModuleStateStore` row query, `RequiresEntitlement`, aspect, validator), `keevo/modules/*/module.json`.
- `sync/sync/application/handler/*` (moduleId), reporting schedulers/listeners, messaging stock-alert/notification listeners, `reporting/export/application/service/ExportService.java` (plan check).
- `public.tenants.plan_type` / subscription state (see `PlanView`).

## Tasks & Acceptance

**Execution:**
- [x] 7 premium manifests + transition rule in `ModuleStateRules` (+ plan in the row query) + tests (Free/Premium/trial/expired/row overrides).
- [x] `@EntitlementExempt`, annotate all handlers, architecture test, generated `docs/entitlements/endpoints.md` + comparison test.
- [x] Sync handler module ids for premium ops; jobs/listeners gated; `ExportService` plan check replaced.
- [x] Full-context tests for every matrix row (Free vs Premium tenant via real JWTs); route `@RequiresPermission` to 14.1; tick Story 13.10 routed items (crm/reporting REST gating) with evidence.

**Acceptance Criteria:**
- Given a Free tenant, when it calls a §6.1 premium endpoint, then 403 `MODULE_NOT_ENTITLED` in real time; given Premium/trial or a module row granting it, then 200; given the test suite, then every endpoint↔entitlement mapping is covered by the checked-in table.
- Given all backend packages run one at a time + `-Pprod-classpath`, then all pass.

## Implementation Notes

**Progress — task 1 done (2026-10-30).** 7 manifests `src/main/resources/keevo/modules/{multi-store,counting,advanced-reports,report-config,alerts,profitability,exports}/module.json` (category `premium`, `free: false`, no migrations, French names, requires per the decisions). `TenantModuleStatePort`: new `findTenantRows(schema)` → `TenantRows(rows, Plan)` (default = `findRows` + `Plan.NONE`, so test doubles keep the 13.2 rule); `Plan(planType, status, expiresAt)`. `JdbcTenantModuleStateStore.FIND_STATUSES_SQL` reads the plan in the same statement: oldest `<schema>.subscriptions` row (same as `TenantPlanQuery`) via `query_to_xml(format('… %I.subscriptions …'))` guarded by `to_regclass` (missing schema → no plan, no error). `ActiveModulesCache.Entry` carries the plan. `ModuleStateRules.premiumPlan`: subscription `ACTIVE` and `PREMIUM`, or `PREMIUM_TRIAL` with `expires_at` null/after now (exclusive); paid module without row → ENABLED iff premium; rows always win. Tests: `ModuleTransitionRuleTest` (7: free, premium, trial boundary, suspended/expired, rows win both ways + read-only, unknown tenant, legacy port), `ModuleRegistryPostgresIntegrationTest.planReadWithRows` (oldest row, missing schema/table, empty table, trial expiry) + shipped set 17 / 7 paid; catalogue/seeder/manifest tests updated to 17 modules. `com.keevo.shared.module.**` green (325).

**Progress — task 2 annotations in place (2026-10-30), package tests pending.** `shared.module.EntitlementExempt(reason)` (method/type, no runtime effect). All 176 handlers of the 51 production controllers annotated (90 gated, 86 exempt): class-level exempt for every `/api/v1/admin/**` controller (super-admin), auth, onboarding, account, employees, feedback, tutorials, announcements, audit, subscription, `GET /tenants/me/modules`, the signed export download; method-level elsewhere (GET → `READ`, other verbs → `WRITE`). Premium: transfers + `/stock/overview|stores|availability` → `multi-store`; `/inventory/sessions/**` → `counting`; leaderboard + `POST /reports/trigger-weekly` → `advanced-reports`; profitability → `profitability`; `PUT /tenant/report-preferences` + `POST /tenant/report-test` → `report-config`. Free: catalog, pos, crm, inventory (`/products/{id}/stock|threshold`), store, messaging, reporting (dashboard, reports, exports — premium formats checked in `ExportService`, task 3), sync conflicts/devices → `sync`; sync push/pull and `GET /tenant/preferences` exempt. `architecture.EndpointEntitlementCoverageTest` (reflection scan of production `@RestController`s: missing / double / verb-access mismatch / unknown module → fail; self-check on fixtures) + generated `keevo/backend/docs/entitlements/endpoints.md` compared verbatim (regenerate: `-Dkeevo.entitlements.regenerate=true`). `RequiresGraphAcyclicityTest` → 17 manifests.

**Progress — packages green after annotation (2026-10-30):** architecture, catalog 247, commerce 251, crm 127, store 60, messaging 163, inventory (rc 0) — free modules ENABLED without rows, so no behaviour change there.

**Progress — task 3 done (2026-10-30).** Sync: `TransferSyncHandler` → `multi-store`; `InventorySessionSyncHandler` / `InventoryCountSyncHandler` / `InventoryValidationSyncHandler` → `counting` (12.7 guard answers `MODULE_INACTIVE`, held on the device). Jobs/events: `ReportingModuleGate.disabled(registry, schema, job, ids...)` (one `moduleStates` read for several ids); `WeeklyReportScheduler` skips a tenant when `reporting` **or** `advanced-reports` is DISABLED; `SalesTrendDetectionScheduler` (messaging, trends) skips when `advanced-reports` is DISABLED (new `@Autowired` constructor with `ModuleRegistry`, old one = no gate); `@ModuleScoped("alerts")` on `StockAlertNotificationListener.onStockThresholdBreached`, `StockModificationNotificationListener.onStockAdjusted`, `ProductCreationNotificationListener.onProductCreated` / `onCsvImportCompleted` (owner notifications; transfer / draft-sale / day-closure notifications unchanged). Exports: `ExportService` uses `EntitlementService.isEnabled(tenant, "exports")` instead of `TenantPlanQuery` (XLSX/PDF refused with **`MODULE_NOT_ENTITLED`** + `details.feature` — was `PLAN_FEATURE_REQUIRED`; background mode = exports module); `ExportJobRunner` fails a background job `MODULE_NOT_ENTITLED` (EXPORT_FAILED push) when the module is disabled at run time. Tests: `ExportServiceTest` (fake EntitlementService mirroring the transition rule), `ExportJobRunnerTest.moduleDisabledAtRunTime`, `ExportControllerTest` codes, `WeeklyReportSchedulerTest.story1310a_advancedReportsDisabled_tenantSkipped`, `SyncModuleGuardPostgresIntegrationTest` (+3: Free tenant transfer/session `MODULE_INACTIVE` + sale applied; trial passes then expired trial rejected; Free + `counting` row ACTIVE passes; read counting restricted to the request thread because the async `alerts` listeners read the state on their own threads; handler→module mapping updated). reporting 398, sync 531, messaging 163 green.

**Progress — first full pass (2026-10-30):** shared 978, identity 437, subscription 70, feedback 52, inventory 376, architecture 32 green; admin 517 with 1 expected failure fixed — `ModuleAdminFullContextTest.refusals`: deactivating `inventory` on a registration-trial tenant now names « Inventaire assisté », « Multi-boutiques et transferts », « Point de vente » (paid dependents enabled by the transition rule — intended).

**Progress — task 4 done (2026-10-30).** `shared.module.PremiumGatingFullContextTest` (full context, real HTTP chain + JWT, tenant from the production `TenantFactory`, plan changed in its own `subscriptions` row), 9 tests = the REST rows of the matrix: Free → 403 `MODULE_NOT_ENTITLED` (French message) on transfer, multi-store overview, counting session, weekly trigger, leaderboard, profitability, XLSX export, report preferences (no session written); Free → products / clients (list + create) / dashboard / stock / sales history / day closure / daily trigger / CSV export unchanged; Free 2nd store → `PLAN_LIMIT_EXCEEDED`; registration trial and paid PREMIUM → all premium endpoints pass the gate; trial past its end → 403 in real time; Free + `counting` row ACTIVE → counting passes, transfer still 403; trial + `exports` row DISABLED → XLSX 403, CSV 200; `crm` DISABLED → clients 403, products 200. Sync rows in `SyncModuleGuardPostgresIntegrationTest` (task 3), weekly job row in `WeeklyReportSchedulerTest`, "handler without annotation" row in `EndpointEntitlementCoverageTest`. `epics-refonte-modulaire.md`: Story 13.10 crm/reporting REST item ticked with evidence; `@RequiresPermission` item ticked as routed → new Story 14.1 item (decision: not built here, existing role checks kept); 13-5 gating-rollout item annotated (per-verb access done; permission slice → 14.1, Flutter → 13.10b); new 13.10b item (exports now `MODULE_NOT_ENTITLED` instead of `PLAN_FEATURE_REQUIRED`, premium entry points, held sync ops).

**Deviations / decisions recorded.** (1) Plan read in the same statement via `query_to_xml` on the tenant's own `subscriptions` (the plan's expiry lives only there; `public.tenants.plan_type` has none). (2) Transition rule requires the subscription `status = ACTIVE` (same as the former export check); PREMIUM is granted whatever its `expires_at` (spec: "PREMIUM or an unexpired PREMIUM_TRIAL"), the nightly downgrade stays the cleanup. (3) CSV exports stay free (plan §6.1: "Exports PDF/Excel"); the export endpoints are gated `reporting`, the XLSX/PDF formats and the background mode by `exports` inside `ExportService`. (4) `GET /stock/products/{id}/availability` (cross-store availability) is `multi-store`. (5) Employees / audit / account / announcements / preferences read are exempt as `core`. (6) A paid module enabled by the transition rule counts as a dependent at deactivation (deactivating `inventory`/`reporting`/`messaging`/`store` on a Premium tenant is refused while its paid dependents are active) — consistent with the resolver, noted for 16.4.

**Verification (2026-10-30).** 14 packages one at a time (clean surefire reports each), all rc=0, 0 failures/errors: shared 987, sync 531, architecture 32, admin 517, catalog 247, commerce 251, crm 127, feedback 52, identity 437, inventory 376, messaging 163, reporting 398, store 60, subscription 70 (4,248 tests); `mvn -Pprod-classpath test` rc=0. Dev DB, read-only: the new statement run on `kv_q9olrl` (FREE → plan FREE/ACTIVE) and `kv_zggt7g` (PREMIUM_TRIAL until 2027-04-29); over the 332 live dev tenants the transition rule gives premium to 4 PREMIUM + 43 unexpired trials, not to 281 FREE and **3 PREMIUM_TRIAL past their end whose nightly downgrade has not run** (real-time expiry, intended), and KV-ADMIN (`public`) keeps the platform bypass. Not done: dev boot + device (adb) check; nothing committed. Boot effect to expect on deploy: the 13.1 seeder inserts the 7 new rows in `public.module_definitions` (catalogue, no tenant data, no `tenant_modules` write).

### Review fixes (2026-10-30, coordinator review of 13.10a)

**Item 1 done — plan read isolated, fail-safe.** `FIND_STATUSES_SQL` is back to plain module rows (+ `t.id`, `t.code`); `JdbcTenantModuleStateStore.readPlan` reads the plan in separate statements on a **dedicated connection** (outside the caller's transaction, never poisons it), query timeout 3 s: `SELECT … FROM "<schema>".subscriptions ORDER BY created_at ASC, id ASC LIMIT 1` (schema from `TenantSchema.validate`, no dynamic SQL inside SQL) + the tenant's latest `public.tenant_plan_events` row. Missing table (SQLState 42P01) → `Plan.NONE`; any other fault → ERROR log with the tenant code and `Plan.UNREADABLE` → paid modules ENABLED for the request, free modules and rows unaffected. Tests: `ModuleRegistryPostgresIntegrationTest.faultyPlanIsolated` (subscriptions without `status` → UNREADABLE, rows read, crm on, paid granted), tie on `created_at` broken by id, `ModuleTransitionRuleTest.unreadablePlanGrants`.

**Item 2 done — real-time expiry + 7-day grace for rule-covered tenants.** `ModuleStateRules.planState`: PREMIUM / PREMIUM_TRIAL ACTIVE before `expires_at` → ENABLED; ACTIVE or EXPIRED past `expires_at` → READ_ONLY until `expires_at` + 7 d, then DISABLED (PREMIUM now honours `expires_at`); FREE whose latest plan event is the nightly `EXPIRY` downgrade (which erases `expires_at`) → READ_ONLY until that downgrade + 7 d; SUSPENDED / always-Free / admin downgrade → DISABLED. `Plan` gained `expiryDowngradeAt`, `unreadable`. Tests: `ModuleTransitionRuleTest` (trial boundary + grace end exclusive, PREMIUM expiry, EXPIRED status, grace after downgrade, admin downgrade no grace, suspended), PG test (grace from the plan end, EXPIRY event read, a later ADMIN_DOWNGRADE event cancels it), `PremiumGatingFullContextTest.trialExpired_readOnlyGrace` (reads + export 2xx, writes 403 `MODULE_READ_ONLY`) and `premiumExpired_graceOver_refused`.

**Item 3 done — read-style POSTs.** `RequiresEntitlement.readJustification()` (no runtime effect). `EndpointEntitlementCoverageTest`: GET must be READ without justification; another verb is WRITE, or READ **with** a non-blank justification (and a justification on WRITE fails). Applied to `POST /exports` (`reporting`, READ, « produit un fichier à partir des données existantes, sans les modifier ») — shown in `docs/entitlements/endpoints.md`. No other POST only reads (resend writes delivery state, test report / trigger-daily write).

**Item 4 done — premium data through free paths.** (a) `ExportDatasetProvider.moduleId()` (abstract) on the 16 providers (`inventory_sessions`/`inventory_counts` → counting, `stock_transfers` → multi-store, stock levels / movements → inventory, sales / items / closures → pos, clients / suppliers → crm, stores → store, products / categories → catalog, reports → reporting, employees / audit → core); `ExportService` refuses (`MODULE_NOT_ENTITLED`, any format) a dataset whose module is not readable, `ExportJobRunner` re-checks at run time. (No profitability dataset exists.) (b) `DeltaEntityProvider.moduleId()` (abstract) on the 15 providers; `GET /sync/pull` reads `moduleStates` once and skips (empty list, delta done) a provider whose module is DISABLED — READ_ONLY still pulls; unreadable states → everything pulled (WARN). Decision: stock levels stay `inventory` (a downgraded tenant keeps selling in its existing stores; the store limit applies at creation, not retroactively). (c) `ReportController`: `GET /reports/{id}` of a WEEKLY report → `assertEnabled("advanced-reports")`, resend of a WEEKLY report → `assertWritable("advanced-reports")` (listing unchanged, accepted). Tests: `ExportServiceTest.datasetModuleDisabledRefused`, `SyncPullServiceTest.story1310a_*` (2), `ReportControllerTest.story1310a_weeklyReport_*`, `PremiumGatingFullContextTest.freeTenant_premiumDataNotLeaked` (CSV of the 3 premium datasets 403, pull without `inventorySessions` / `stockTransfers` after a session + transfer made on trial) and `dataOwnership` (every dataset / delta mapped).

**Item 5 done — listeners / jobs.** `@ModuleScoped("alerts")` removed from `ProductCreationNotificationListener.onCsvImportCompleted`. `ReportingModuleGate`: a gate id that is not installed → WARN, tenant processed. Tests: `AlertsModuleScopedFullContextTest` (real context: Free tenant → stock-alert listener not invoked, publication completed; trial → invoked; annotation placement incl. CSV import not scoped), `SalesTrendDetectionSchedulerTest.advancedReportsGate` / `…Unreadable`, `WeeklyReportSchedulerTest.story1310a_review_uninstalledGateModule_tenantProcessedWithWarn` (registry mocks of the gate tests now declare the ids installed).

**Item 6 done — coverage test loopholes.** Scans every production `@Controller` (so `@RestController` and `@Controller` + `@ResponseBody`), declared and inherited handlers (`ReflectionUtils.getUniqueDeclaredMethods`); resolves like `EntitlementAspect` (method `getDeclaredAnnotation`, else declaring class, else bean class); fails on method-level `@EntitlementExempt` under a class-level `@RequiresEntitlement`, on an inherited handler only covered by the subclass' class-level gate, on the justification rules. Self-check fixtures for each. `PremiumGatingFullContextTest` premium cases now assert **2xx** (second store created on trial, stock in the source store, tenant preferences saved).

**Verification after the review fixes (2026-10-30).** 14 packages one at a time, clean surefire reports, all rc=0, 0 failures/errors: shared 994, sync 533 (first run: `SyncModuleGuardPostgresIntegrationTest.premiumTrial_…` still expected `MODULE_INACTIVE` one day after the trial end — now `MODULE_READ_ONLY` per item 2, + 8 days → `MODULE_INACTIVE`; rerun green), architecture 32, admin 517, catalog 247, commerce 251, crm 127, feedback 52, identity 437, inventory 376, messaging 168, reporting 401, store 60, subscription 70; `mvn -Pprod-classpath test` rc=0. `docs/entitlements/endpoints.md` regenerated (176 handlers, `POST /exports` READ with its justification). Nothing committed.

**Final verification (orchestrator, 2026-10-30, after review patches).** Implementer: 14 packages one at a time 0 failures + `-Pprod-classpath` green. Live (dev backend, owner of kv_zggt7g via real login): PREMIUM_TRIAL → products/clients/stock overview/inventory sessions 200, `/tenants/me/modules` lists premium modules ENABLED; subscription temporarily set to FREE → products 200, clients 200, `/stock/overview` 403 `MODULE_NOT_ENTITLED` « Ce module n'est pas inclus dans votre abonnement », `/inventory/sessions` 403; subscription restored to PREMIUM_TRIAL; 0 ERROR.

## Spec Change Log

- 2026-10-30 — coordinator review of 13.10a. Frozen intent amended:
  - **Transition rule:** "the tenant's plan_type/subscription state is read in the same row query" → read in **separate statements on a dedicated connection**; a plan read failure grants paid modules for the request (ERROR logged), free modules unaffected.
  - **Transition rule:** "ENABLED iff PREMIUM or an unexpired PREMIUM_TRIAL, else DISABLED" → PREMIUM also honours `expires_at`; a plan ended less than 7 days ago (or the nightly EXPIRY downgrade less than 7 days ago) gives **READ_ONLY**, then DISABLED — same outcome as a backfilled row with `grace_until`.
  - **REST:** "GET → READ, other verbs → WRITE" → a non-GET handler may be READ with an explicit `readJustification` (checked by the coverage test) — `POST /exports`.
  - **Sync:** in addition to push, `GET /sync/pull` skips the delta providers of DISABLED modules (exempt handler, gated per entity).
  - **Exports / reports:** each export dataset requires its owning module (any format); WEEKLY report read / resend requires `advanced-reports`.
  - **Jobs/events:** CSV import completion feedback is not an `alerts` notification.

## Review Triage Log

### 2026-10-30 — Review pass (13-10a)
- verdicts: 2 layers (Blind Hunter 14, Edge+Gap+Intent 13; overlap) — high 4, medium 8, low 8 after dedup
- findings:
  - `[high]` `[patch]` plan subquery inside the module-row statement: one subscriptions fault broke gating of every module — separate read on its own connection, timeout, tie-breaker; failure → paid modules granted for that request (ERROR log), free modules unaffected
  - `[high]` `[patch]` premium data through free paths (CSV export datasets, sync pull providers, weekly report read/resend) — dataset/provider owning module enforced
  - `[high]` `[patch]` PREMIUM ignored `expires_at`; no grace for rule-covered tenants — real-time expiry + 7-day READ_ONLY grace (EXPIRY downgrade date when the nightly job cleared `expires_at`)
  - `[high]` `[defer]` field app versions treat MODULE_* as terminal / export upgrade sheet keyed on `PLAN_FEATURE_REQUIRED` → routed → 16-7 go-live gate + 13-10b
  - `[medium]` `[patch]` exports blocked in grace (POST forced WRITE) — explicit `readJustification` for read-style POSTs
  - `[medium]` `[patch]` coverage test loopholes (`@Controller`, inherited handlers, exemption overriding class gate, annotation lookup) — closed; Premium checks require 2xx
  - `[medium]` `[patch]` CSV-import owner feedback gated as an alert; missing gate tests (alerts listeners, trends scheduler); uninstalled gate module skipped silently — fixed
  - `[medium]` `[defer]` dashboard low-stock tile → 13-10b; stored report preferences with `report-config` off, rule retirement → 16-8; backfill parity → 16-5; deactivation refused by rule-enabled paid dependents → 16-4; leaderboard inside profitability UI → 15-8; re-pull after re-enable, dataset list filtering → 15-1
  - `[low]` `[reject]` signed export links after downgrade; past weekly reports listed; `sync`/`messaging` gates on core-ish endpoints — accepted
  - `[low]` `[reject]` stock levels not filtered per store for downgraded tenants — keeps selling in existing stores (decision)

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.<pkg>.**' -DforkCount=1 -DargLine=-Xmx1536m` for every package -- expected: green
- `mvn -Pprod-classpath test` -- expected: green
- Live: dev backend; Free test tenant (kv_zggt7g is FREE?) → transfer/counting/weekly report refused in the app (French message after 13.10b), sales/clients OK; a Premium tenant unaffected.
