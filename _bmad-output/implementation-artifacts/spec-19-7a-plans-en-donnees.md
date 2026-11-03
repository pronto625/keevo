---
title: '19-7a Plans are data — plan catalogue in the database, behaviour driven by the plan kind, no closed list of plan codes in code or constraints; identical behaviour with the three current plans'
type: 'refactor'
created: '2026-11-03'
status: done
baseline_revision: '45550e84e1fd9a1303a9201f0507644f39c0578d'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Plan kind change and archived/visible enforcement, per-plan revenue prices
    evidence: |-
      Routed to 21-10 (epics-fusion-keevo-bref.md, story 21.10, Deferred items 2026-11-04).
    location: >-
      JdbcPlanCatalog.save, AdminSubscriptionController, RevenueMetrics
    severity: medium
  - summary: >-
      activatePremium hard-codes PREMIUM; activation does not tie expiresAt to the plan kind (pre-existing)
    evidence: |-
      Routed to 19-7b (epics-fusion-keevo-bref.md, story 19.7 note, Deferred items 2026-11-04).
    location: >-
      AdminPlanService.java:170, AdminSubscriptionController.java:63
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Plans are a closed list in code and data: the enum `PlanType` (FREE, PREMIUM_TRIAL, PREMIUM — 48 production files, 55 test files), 9 `PlanType.valueOf`/`values()` sites that break on any other code, string comparisons on plan codes in SQL and services (expiry, module rows, revenue, admin filters), CHECK constraints on the codes (`plan_profiles` V33, `tenant_plan_events` V17, tenant `subscriptions` in every schema), and the trial length written as `INTERVAL '6 months'` in SQL. ADR-05 requires that nothing of the commercial offer be hard-coded: a plan created as data must work everywhere without a deploy (19.7b seeds FREE/PRO/TEAM/BUSINESS; 21.10 adds the super-admin CRUD).

**Approach:** Add a public plan catalogue (`plans`: code, French label, description, order, visibility, archive date, **kind** ∈ FREE / TRIAL / PAID) and a one-row `plan_settings` (free plan code, trial plan code, trial length in months). Every behaviour that today depends on a plan code depends on the plan's **kind**, read from the catalogue; the V1 compatibility value is derived from the kind (FREE → `FREE`, TRIAL → `PREMIUM_TRIAL`, PAID → `PREMIUM`). `PlanType` stops being a closed enum: a plan code value object resolved through the catalogue, with the three current codes kept as well-known constants only for seeds, fallbacks and tests. Constraints on codes are replaced by foreign keys to the catalogue. Seed = the three current plans, so behaviour is unchanged.

## Boundaries & Constraints

**Always:**
- Public Flyway `V42__plan_catalogue.sql` (idempotent, public only): table `plans (code varchar(30) PK CHECK code ~ '^[A-Z][A-Z0-9_]{1,29}$', label varchar(80) NOT NULL, description text, sort_order int NOT NULL DEFAULT 0, visible boolean NOT NULL DEFAULT true, kind varchar(10) NOT NULL CHECK kind IN ('FREE','TRIAL','PAID'), archived_at timestamptz, created_at/updated_at timestamptz NOT NULL DEFAULT now())`; seed `FREE` (« Gratuit », FREE), `PREMIUM_TRIAL` (« Essai Premium », TRIAL), `PREMIUM` (« Premium », PAID) — labels exactly as today's French labels; table `plan_settings (id smallint PK CHECK id = 1, free_plan_code FK plans, trial_plan_code FK plans, trial_months int NOT NULL CHECK > 0, updated_at)` seeded (FREE, PREMIUM_TRIAL, 6). Drop `plan_profiles_plan_type_check` and the V17 `from_plan`/`to_plan` CHECKs; add FKs `plan_profiles.plan_type → plans(code)`, `tenant_plan_events.from_plan/to_plan → plans(code)`, `tenants.plan_type → plans(code)` (codes are never deleted, only archived). The kinds FREE/TRIAL/PAID are the only fixed list (they are concepts, not offer content).
- Tenant schema: module migration `db/modules/core/V2__subscriptions_plan_code.sql` drops the `subscriptions.plan_type` CHECK (look up the real constraint name in `V1__core_baseline.sql`; idempotent `DROP CONSTRAINT IF EXISTS`), widens the column to varchar(30), keeps the default; it runs through the 16-12 foundation upgrade (new tenants in-transaction, existing ones at boot/`ensureFoundation`). No public DDL for tenant tables.
- Code: a `PlanCatalog` port (application) with a JDBC adapter reading `plans`/`plan_settings` (cached in memory with explicit invalidation on write; reads are per-plan lookups of a few rows) gives `require(code)`, `find(code)`, `kind(code)`, `freePlan()`, `trialPlan()`, `trialMonths()`, `v1Code(code)`, `all()`. `PlanType` becomes a value type (record/final class) keeping `FREE`, `PREMIUM_TRIAL`, `PREMIUM` constants and `name()` so most callers compile unchanged; the 9 `valueOf`/`values()` sites go through the catalogue (unknown code → existing 404/422 domain errors); `isFree()/isPremium()/isPremiumTrial()`-style predicates and every string comparison on codes (`SubscriptionExpiryService` DOWNGRADE_SQL, `PlanModuleRows`, `ModuleStateRules`, `JdbcTenantModuleStateStore`, `TenantModuleStatePort`, `ModuleLifecycleService`, `PlanLimitGuard`/`PlanView`, `AdminPlanService`, `AdminCatalogService` PAID alias, `RevenueMetrics`/`AdminRevenueService`, `JdbcPushAudienceAdapter`, `JdbcGlobalNotificationRepository`, `PlanExpiryReminderService`, `PlanWhatsAppMessages`, labels in `PlanProfileAdminService`/`ProductsCsvWriter`/`AdminAlertService`) use the **kind** (SQL: join `public.plans`), never a literal code. Grep gate in a test: no `"PREMIUM_TRIAL"`, `'PREMIUM'`, `"FREE"`… plan literals in `src/main/java` outside the value type constants, the catalogue adapter's fallback and the V1 mapping.
- Trial: registration seeds the subscription with `plan_settings.trial_plan_code` and `NOW() + trial_months months` (replaces both `INTERVAL '6 months'` seeds in `TenantSchemaProvisioner`); the downgrade target of the expiry job is `plan_settings.free_plan_code`. Same values as today → identical behaviour.
- Limits: `plan_profiles` keyed by plan code; a plan without a profile row → limits of the FREE plan profile + ERROR log (fail closed for paid limits, as the 16.8 fallback intends); the enum's built-in limits disappear (no limit in code).
- V1 compatibility: `GET /subscription/me` `planType` and `PLAN_LIMIT_EXCEEDED` `details.plan` return the plan's V1 code (derived from kind); every other response unchanged. Admin APIs keep returning codes (unchanged values today).
- Tests: the whole existing suite stays green (behaviour identical, by package); new tests: catalogue adapter and cache invalidation; a **fourth plan inserted as data** (e.g. `TEST_PAID`, kind PAID, with a profile) assigned to a tenant behaves like PREMIUM end to end (limits from its profile, paid modules from `profile_modules`, expiry job downgrades it to the free plan, `/subscription/me` says `PREMIUM`, admin list/filters count it as paid, revenue counts it); a TRIAL-kind plan behaves like PREMIUM_TRIAL; FK refuses an unknown code; core V2 drops the tenant CHECK on new and existing tenants; trial length change in `plan_settings` changes the next registration's `expires_at`; grep gate.
- FUS-NFR11: kind resolution in SQL by join (no per-row lookups), catalogue cached (tiny table), no N+1 introduced in the jobs.
- Live check: local backend boot (keevo_dev upgraded by V42 and core V2 through the foundation upgrade), registration of a new owner through the API (trial 6 months), Redmi: subscription screen shows the same plan as before for KV-EW02A2.

**Never:** no new plan seeded besides the three current ones (19.7b); no super-admin CRUD endpoint for plans (21.10); no change to limits, modules, prices, trial length values or user-visible behaviour; no change to response shapes; no Flutter work; no deletion of plan codes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Existing tenants after deploy | FREE / PREMIUM_TRIAL / PREMIUM | everything identical (limits, modules, expiry, reminders, admin, revenue, `/subscription/me`) | — |
| New registration | trial settings (PREMIUM_TRIAL, 6) | subscription PREMIUM_TRIAL, expires in 6 months | — |
| 4th plan as data (PAID) | tenant moved to `TEST_PAID` with a profile | behaves as paid; `/subscription/me` → `PREMIUM`; expiry → free plan | — |
| Plan without profile | tenant on a code without `plan_profiles` row | FREE limits | ERROR log |
| Unknown code in admin path | `PUT /admin/plan-profiles/NOPE` | existing 404 `PLAN_PROFILE_NOT_FOUND` | — |
| Unknown code in data | insert tenant/event with a code not in `plans` | refused by FK | constraint violation |
| Trial length changed | `plan_settings.trial_months = 1` | next registration expires in 1 month | — |
| Catalogue edited | label/kind changed in DB through the port | next read sees it (cache invalidated) | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/identity/api/PlanType.java` -- enum to turn into a value type (constants FREE/PREMIUM_TRIAL/PREMIUM, built-in limits removed).
- `valueOf`/`values()` sites (9): `shared/capability/entitlement/PlanProfiles.java:30`, `admin/tenant/adapter/in/rest/AdminSubscriptionController.java:61`, `admin/plan/adapter/in/rest/AdminPlanProfileController.java:141`, `subscription/plan/adapter/out/persistence/impl/SubscriptionRepositoryAdapter.java:48`, `subscription/plan/application/service/ChangeTenantPlanService.java:104`, `PlanExpiryReminderService.java:133`, `SubscriptionExpiryService.java:180`, `identity/auth/adapter/out/persistence/impl/TenantRepositoryAdapter.java:101`, `shared/module/application/PlanProfileAdminService.java:73`.
- String-typed plan logic: `shared/module/application/PlanModuleRows.java:45-46,95-153`, `ModuleStateRules.java:74,107-123`, `adapter/out/persistence/JdbcTenantModuleStateStore.java:79`, `application/port/TenantModuleStatePort.java:93`, `ModuleLifecycleService.java:128`, `JdbcPlanModuleRowStore.java:75`.
- Registration/trial: `identity/auth/application/service/TenantFactory.java:87-110`, `shared/infrastructure/persistence/TenantSchemaProvisioner.java:48-68,244` (`SEED_SUBSCRIPTION*`, `PLAN_PROFILES_PRESENT`), `identity/auth/adapter/in/bootstrap/AdminAccountInitializer.java:59,95`.
- Transitions: `SubscriptionExpiryService.java:65-77,171-183` (DOWNGRADE_SQL), `ChangeTenantPlanService.java:42-104`, `ActivatePlanService.java:146,156`, `PlanTypeRealignJob.java:56`, `shared/capability/entitlement/PlanChangeRecorder.java` + `JdbcPlanChangeRecorder.java:24-46`, `Subscription.java:21,62-64`.
- Limits: `subscription/plan/application/service/PlanLimitGuard.java:37-91`, `PlanView.java:17-49`, `shared/capability/entitlement/PlanProfile.java:25-72`, `JdbcPlanProfileStore.java:47-114`.
- V1 surface: `subscription/plan/adapter/in/rest/SubscriptionController.java:32,52`, `dto/SubscriptionResponse.java:10`, `SubscriptionPlanAdapter.java:42-58`; `PLAN_LIMIT_EXCEEDED` details (`PlanLimitGuard.java:91`), sync pass-through `SyncOperationResultDto.java:12-13`.
- Admin/labels/analytics: `admin/subscription/adapter/in/rest/AdminTenantPlanController.java:89-113`, `AdminPlanService.java:147-202,270`, `AdminTenantService.java:296,583,682`, `AdminCatalogService.java:125-152`, `ListTenantsQuery.java:9`, `AdminOperationsService.java:282,323`, `AdminSyncMonitoringService.java:100,143,202`, `JdbcAlertSourcesAdapter.java:121`, `PlanProfileAdminService.java:252-256`, `ProductsCsvWriter.java:27-30`, `AdminAlertService.java:365-367`, `PlanWhatsAppMessages.java:22-55`, `admin/revenue/domain/model/RevenueMetrics.java:44-46,94,166,189,216-220`, `AdminRevenueService.java:286-288`, `GlobalNotificationTarget.java:9-11`, `JdbcGlobalNotificationRepository.java:107-114`, `JdbcPushAudienceAdapter.java:59`.
- DB: `db/migration/V1__baseline_public.sql:288-297,364`, `V17__tenant_payments_plan_reminders.sql:40-48`, `V22__tenant_suspension_payment_void.sql:21-23`, `V33__plan_profiles.sql:17-45`, `V21__global_notifications.sql:14` (kinds, keep), `V29__module_registry.sql:41` (module tier, unrelated); tenant `db/modules/core/V1__core_baseline.sql:50-60,274`; latest public migration V41.
- Tests pinning plans: `PlanTypeTest`, `PlanProfilePostgresIntegrationTest:203,280`, `PlanModuleRowsTest:178`, trial length tests `TenantMigrationIntegrationTest:102-128`, `TenantFactoryTest:213`, `PremiumGatingFullContextTest:44`, `ModuleConsoleFullContextTest:248`; heavy users `PlanLimitGuardTest`, `ExportServiceTest`, `ActivatePlanServiceTest`, `AdminPlanServiceTest`, `SubscriptionApiAdaptersTest`, `TenantFactoryTest`.

## Tasks & Acceptance

**Execution:**
1. `V42__plan_catalogue.sql` and `db/modules/core/V2__subscriptions_plan_code.sql`.
2. `PlanCatalog` port + JDBC adapter (cache + invalidation), `PlanType` value type, kind-based predicates.
3. Replace the 9 closed-list sites and every literal/string comparison listed in the Code Map by catalogue/kind lookups (SQL joins on `public.plans`).
4. Trial and downgrade from `plan_settings`; limits without enum fallback.
5. V1 mapping at `/subscription/me` and `PLAN_LIMIT_EXCEEDED`.
6. Tests (existing green + new matrix tests + grep gate), live check, Design Notes (decision table: kind semantics per behaviour), no Mobile/Dashboard section unless a response changes (none expected; state it), sprint-status.

**Acceptance Criteria:**
- Given the three current plans, when the full suite runs and the app is used, then nothing observable changes.
- Given a plan inserted as data with kind PAID and a profile, when a tenant is on it, then limits, modules, expiry, admin counts, revenue and the V1 `planType` behave as for PREMIUM without any code change.
- Given the code base, when grepped, then no plan code literal drives behaviour outside the value type constants, the catalogue fallback and the V1 mapping.

## Spec Change Log

## Review Triage Log

### 2026-11-04 — Review pass
- verdicts: 50 findings — high 0, medium 22, low 20, false 6, maybe-false 2
- findings:
  - Intent-alignment:
    - `[low]` `[defer]` AdminPlanService.activatePremium still grants the PREMIUM constant (allow-listed in the gate) — routed to 19-7b (plan becomes a parameter).
    - `[false]` `[reject]` Gate allow-list includes module-tier files (PlanModuleRows, ModuleLifecycleService, TenantModuleStatePort, JdbcTenantModuleStateStore) — their "FREE"/"PREMIUM" are module tiers (V29), not plan codes.
    - `[low]` `[reject]` Never-read profile store returns no limits (fail-open tail) — only reachable with the database down since boot, where every tenant read fails anyway; a restrictive default would put limits back in code (forbidden by the intent).
    - `[false]` `[reject]` Admin `plan=` filter / activation accept any catalogue code — that is the intent (a plan created as data works everywhere); response shape unchanged.
    - `[low]` `[reject]` Cache TTL + miss reload beyond explicit invalidation — needed for multi-instance freshness; invalidation on write through the port holds (patched race below).
    - `[false]` `[reject]` Live check not visible in the diff — performed and recorded under Verification (keevo_dev V42 + core V2 on 344 schemas, API registration, Redmi).
  - Blind hunter:
    - `[medium]` `[patch]` TenantRepositoryAdapter/SubscriptionRepositoryAdapter strict resolve throws for codes unknown to a degraded catalogue — stored rows now mapped shape-only with PlanType.of.
    - `[medium]` `[patch]` invalidate() nulls the snapshot so a failed reload falls to SEED — invalidate now marks stale and keeps the last catalogue.
    - `[medium]` `[patch]` plan_settings targets not checked against kind — saveSettings validates free=FREE, trial=TRIAL, not archived.
    - `[medium]` `[defer]` Changing the kind of a plan in use does not resync module rows — the intent allows kind edits through the port; guard/resync belongs to the CRUD → 21-10.
    - `[medium]` `[defer]` archived_at/visible not enforced on assignment — no archived plan exists yet; enforcement with the CRUD → 21-10.
    - `[medium]` `[defer]` Activation accepts a TRIAL/PAID plan with expiresAt null — pre-existing (old controller passed expiresAt unchecked for PREMIUM too) → 19-7b.
    - `[low]` `[defer]` activatePremium hard-codes PREMIUM — same root as intent row 1 → 19-7b.
    - `[false]` `[reject]` core V2 width guard narrows TEXT columns — subscriptions.plan_type is VARCHAR(20) in core V1 baseline (atttypmod > 0); V42 public columns are all VARCHAR(20), widening only.
    - `[false]` `[reject]` profile_modules.plan_type lacks FK — it references plan_profiles(plan_type) (V33:27), which now references plans.
    - `[low]` `[patch]` Unknown-code handling inconsistent; AdminCatalogService INNER JOIN drops tenants outside the catalogue — LEFT JOIN, null kind → FREE (previous behaviour). Listener orElse(PAID) kept: an expired previous plan is trial or paid by construction.
    - `[low]` `[patch]` warm() failure skips the per-plan read — warm isolated in its own try.
    - `[low]` `[reject]` lastRead keeps profiles of deleted plans — plan codes are never deleted (archive only); fallback use only.
    - `[low]` `[reject]` normalizePlanFilter English message lists codes — pre-existing message style on an admin-only 422; dashboard maps codes.
    - `[medium]` `[defer]` Revenue has no per-plan price — prices are 21-10 scope → 21-10.
    - `[low]` `[reject]` Grep gate does not scan .sql/make_interval — gate scoped to src/main/java by the intent; settings drive the interval.
    - `[false]` `[reject]` No end-to-end test on a non-seed plan — PlansAreDataFullContextTest covers TEST_PAID/TEST_TRIAL end to end.
  - Patterns/complexity:
    - `[medium]` `[patch]` Invalidation race in reload caches pre-write rows for the TTL — generation counter.
    - `[medium]` `[patch]` Every reader blocks on reload (second pool connection) — tryLock, readers keep the stale snapshot while one thread reloads.
    - `[medium]` `[patch]` Profile store calls the catalogue while holding a connection — kinds/free plan resolved before getConnection.
    - `[medium]` `[patch]` PlanProfileAdminService.list one query per plan — batch read of all profiles in one query.
    - `[low]` `[reject]` Expiry job scans every tenant schema — pre-existing; the tenant schema subscription is the source of truth and public.tenants.plan_type may lag, so narrowing on it could miss an expiry.
    - `[low]` `[reject]` countCurrentPlans per tenant schema — pre-existing; same source-of-truth reason.
    - `[low]` `[reject]` TenantSchemaProvisioner three scalar subqueries — once per registration, PK lookups.
    - `[maybe-false]` `[reject]` AdminCatalogService INNER JOIN — same root as blind row, patched there.
    - `[low]` `[reject]` V42 without lock_timeout — prod tables tiny; a lock timeout would fail the deploy, worse than a short wait.
  - Edge-case hunter:
    - `[medium]` `[patch]` SubscriptionRepositoryAdapter strict resolve — grouped with blind row 1.
    - `[medium]` `[patch]` ChangeTenantPlanService.currentPlan strict resolve blocks moving a tenant off a bad code — PlanType.of.
    - `[medium]` `[patch]` invalidate then failing reload → SEED — grouped, patched.
    - `[medium]` `[patch]` reload race — grouped, patched.
    - `[low]` `[patch]` miss reload unthrottled during an outage (loadedAt backdated) — throttled on last attempt time.
    - `[low]` `[reject]` Free plan without profile row → no limits — FREE profile is seeded by V33 and profiles are never deleted; unreachable in normal operation.
    - `[medium]` `[patch]` plan_settings kinds — grouped, patched.
    - `[medium]` `[defer]` archived/expiresAt on activation — grouped with blind rows → 21-10 / 19-7b.
    - `[low]` `[patch]` Codes PAID/ALL collide with admin filter keywords — CHECK code NOT IN ('ALL','PAID') in V42.
    - `[medium]` `[defer]` kind change via save — grouped → 21-10.
    - `[maybe-false]` `[patch]` AdminCatalogService INNER JOIN — grouped, patched.
    - `[low]` `[reject]` PlanProfiles.profile(String) returns fallback for unknown codes — callers pass codes from profile_modules/catalogue; ERROR log is the intended signal.
  - Verification gap (pre-verified):
    - `[medium]` `[patch]` Profile store fault fallback untested — test added on failing DataSource.
    - `[medium]` `[patch]` free_plan_code never a non-seed plan in tests — TEST_FREE expiry/downgrade test.
    - `[medium]` `[patch]` AdminPlanService renewal/already-free on data plans untested — TEST_PAID/TEST_FREE cases.
    - `[low]` `[patch]` AdminCatalogService plan class/filter untested — cases added.
    - `[low]` `[patch]` Push audience/announcements on data plans untested — data-plan tenant added.
    - `[medium]` `[patch]` PlanType JSON form not pinned — legacy payload test.
    - `[low]` `[patch]` Other: fail-open free limits — see intent row 3 (reject); warm blocking — patched; inner joins — patched.

## Design Notes

- Why a **kind** column: behaviours (free limits, paid modules, trial expiry, V1 value, revenue) depend on « is it free / trial / paid », not on the plan's name; three kinds are a business concept, plans are offer content (ADR-05). 19.7b then moves the trial onto the subscription; the TRIAL kind stays for the V1 mapping of running PREMIUM_TRIAL subscriptions.
- `PlanType` kept as a value type name to limit churn across ~100 files; constants remain for seeds/tests only.
- Pattern: catalogue as a cached repository behind a port (read-mostly, tiny), Strategy-free kind switch; SQL joins keep jobs set-based.

- Decision table — what each behaviour reads (kind from `public.plans`, never a code):

  | Behaviour | Before | Now |
  |---|---|---|
  | Undecided paid module in a profile | `planType != FREE` | kind ≠ FREE (`PlanProfile.grantsByDefault`) |
  | Plan-derived module rows / transition rule | code ∈ {PREMIUM, PREMIUM_TRIAL} / = FREE / = PREMIUM_TRIAL | kind ∈ {TRIAL, PAID} / FREE / TRIAL (SQL `LEFT JOIN public.plans`) |
  | Nightly expiry | `plan_type IN ('PREMIUM_TRIAL','PREMIUM')` → `'FREE'` | `kind IN (TRIAL, PAID)` (join) → `plan_settings.free_plan_code`; free profile + target read once per run |
  | Registration | `PREMIUM_TRIAL`, `INTERVAL '6 months'` ×2 | `plan_settings` trial plan + `make_interval(months => trial_months)`, read once by `TenantFactory` and handed to the seed |
  | Limits | `plan_profiles`, enum fallback | `plan_profiles`; no row → free plan's profile (ERROR); read fault → last profile read; never read → no limit (ERROR) |
  | `/subscription/me` `planType`, `PLAN_LIMIT_EXCEEDED.details.plan` | code | V1 code of the kind (FREE→FREE, TRIAL→PREMIUM_TRIAL, PAID→PREMIUM) |
  | Reminders / admin alerts / « expired not free » alert | code lists | kind ∈ {TRIAL, PAID} (join); trial text when kind TRIAL |
  | Admin tenant list `plan` filter | closed list + `PAID` alias | any catalogue code, `PAID` = kinds TRIAL+PAID (sub-select on `public.plans`), error lists the catalogue |
  | Admin catalogue `tenantPlan` / filter | code ∈ {PREMIUM, PREMIUM_TRIAL} → PAID | kind FREE → FREE, else PAID (join) |
  | Revenue (MRR stop, churn, conversion, current counts) | codes PREMIUM / FREE / PREMIUM_TRIAL | kinds PAID / FREE / TRIAL (events and subscriptions joined) |
  | Global push targets PREMIUM / TRIAL / FREE | `plan_type = code` | plan kind PAID / TRIAL / FREE (sub-select); stored target names unchanged |
  | Admin downgrade, `ADMIN_DOWNGRADE` cause | `PlanType.FREE` | `plan_settings.free_plan_code`; cause by target kind FREE |
  | Admin « activate Premium » | `PlanType.PREMIUM` | unchanged target (the seeded Premium — legacy action, 19.7b makes the plan a parameter); renewal when current kind is PAID |
  | Labels (profiles admin, alerts) | Java switch | `plans.label` |

- Cache: `JdbcPlanCatalog` loads the whole catalogue (2 queries) into an immutable snapshot; dropped on every write through `PlanCatalogStore` (and after commit), TTL 60 s (other instances), reload once on a miss older than 1 s; load fault → last snapshot kept, else the V42 seed (`PlanCatalog.SEED`, ERROR). Hot paths do one map lookup.
- `PlanType` keeps its JSON form (plain code string) through `PlanTypeJacksonConfig`: durable events (Modulith publication registry) carrying a plan serialise and replay as before, including publications written before the change.
- Unknown code paths: admin profile endpoints → 404 `PLAN_PROFILE_NOT_FOUND` (unchanged); admin tenant list filter → 422 `VALIDATION_ERROR` (unchanged); `POST /admin/subscriptions/{schema}/activate` → `UnknownPlanException` (an `IllegalArgumentException`, as `valueOf` was — unchanged); module rules → « unknown plan » = DISABLED (unchanged).
- Mobile / Dashboard: no response shape or value changes for the three seeded plans — no « Mobile (React Native) » or « Dashboard super-admin » section, no `m19-7a` / `d19-7a` key. (A plan code created as data would appear raw in admin lists until the dashboard reads labels — 21.10 territory.)

## Auto Run Result

- **Summary:** plans are data. Public catalogue `plans` (kind FREE/TRIAL/PAID) + `plan_settings` (free plan, trial plan, trial months) in V42, CHECKs replaced by FKs, reserved codes ALL/PAID refused; tenant `subscriptions.plan_type` CHECK dropped by core V2 through the foundation upgrade. `PlanType` is a value type; every behaviour depends on the plan's kind (SQL joins `public.plans`); V1 value derived from the kind; trial and downgrade target read from `plan_settings`; no limit left in code.
- **Files:** V42__plan_catalogue.sql, db/modules/core/V2__subscriptions_plan_code.sql; shared/module (PlanCatalog, PlanCatalogStore, JdbcPlanCatalog, JdbcPlanProfileStore, PlanProfileAdminService), shared/capability/entitlement (PlanType, PlanKind, PlanDefinition, PlanSettings, PlanProfile(s)), PlanTypeJacksonConfig, TenantSchemaProvisioner; subscription (expiry, reminders, plan change, limit guard, WhatsApp messages, repository adapter); identity TenantRepositoryAdapter; admin (plan, subscription, tenant list filter, catalog, revenue, alerts, notifications/push audience); tests incl. PlansAreDataFullContextTest, JdbcPlanCatalogPostgresIntegrationTest, PlanCodeLiteralGateTest.
- **Review:** 50 findings — patched 25 rows (medium 15, low 10) in 10 fix groups (catalogue cache: stale-not-null invalidation, generation counter, tryLock readers, miss-reload throttle; settings kind validation; reserved codes; shape-only mapping of stored codes; profile store connection/warm isolation; batch profile read; LEFT JOIN in admin catalog; 6 verification-gap tests). Deferred 8 rows → 21-10 (kind change resync, archived/visible enforcement, per-plan revenue prices) and 19-7b (activatePremium constant, expiresAt vs kind — pre-existing). Rejected 17 rows (false 6, maybe-false 2 grouped, low 9) with reasons in the triage log.
- **Follow-up review recommended: true** — first pass patched 15 medium entries; the catalogue cache concurrency (ReentrantLock tryLock + generation counter + stale marking) was rewritten after review and has not been re-reviewed.
- **Verification:** full suite by package green — 5,058 tests, 0 failure, 0 error (acceptance 4, admin 572, architecture 34, catalog 306, commerce 263, crm 158, feedback 52, identity 523, inventory 410, invoicing 170, messaging 192, reporting 421, shared 1170, store 60, subscription 87, sync 635). Live: local backend (WhatsApp noop, FCM off) booted, V42 re-applied on keevo_dev (reserved-code CHECK and `fk_tenants_plan` validated, plans FREE/TRIAL/PAID seeded), core V2 on all tenant schemas; API registration KV-NCO30Y → PREMIUM_TRIAL expiring +6 months; KV-EW02A2 `/subscription/me` → PREMIUM_TRIAL, 2027-05-01, unlimited (same as before); Redmi test client logged in and loads the dashboard (subscription screen check cut short: the phone was handed back to another session's run).
- **Residual risks:** two orphan tenant schemas (no tenant row) keep the old CHECK; a plan created as data shows its raw code in the dashboard until 21.10; if plan profiles were never readable since boot, no limit applies (ERROR log).
