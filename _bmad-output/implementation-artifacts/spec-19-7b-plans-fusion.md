---
title: '19.7b — Fusion plans: FREE/PRO/TEAM/BUSINESS as data, PREMIUM → TEAM, 1-month trial, FREE 1 employee'
type: 'feature'
created: '2026-11-04'
status: 'done'
baseline_revision: 'c9234c67723e11c53ad6dd183bf4ac45cbe402b3'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-7a-plans-en-donnees.md'
  - '{project-root}/keevo/backend/src/main/resources/db/modules/README.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** After 19.7a plans are data, but the catalogue still holds only Keevo's FREE / PREMIUM_TRIAL / PREMIUM. The fusion decisions require the Bref grid as starting data (ADR-05): FREE, PRO, TEAM, BUSINESS. Existing PREMIUM tenants must move to TEAM with no loss and the same end date (ADR-06). FREE allows 1 employee, and tenants already above that keep their employees (ADR-03). New sign-ups get a 1-month trial of the most complete plan, and running 6-month trials go to term (ADR-04). Bref's member limit is per shop. Two items were routed here from the 19.7a review. First, « Activer Premium » still grants the PREMIUM constant. Second, plan activation does not tie `expiresAt` to the plan kind.

**Approach:** One public migration seeds the fusion plans and their profiles and modules, adds a per-store employee limit to profiles, and adds a default paid plan to `plan_settings`. It points the trial at a new TRIAL-kind plan `BUSINESS_TRIAL` (« Essai Business », profile = BUSINESS) for 1 month and archives PREMIUM and PREMIUM_TRIAL. A one-shot boot job moves every PREMIUM subscription to TEAM, keeping `expires_at`. The job traces each move with a new plan-event cause and resyncs module rows. Admin activation takes the paid plan as a parameter, defaulting to the setting, and both activation paths validate plan kind, archive state and `expiresAt`. V1 apps keep their mapping (paid → PREMIUM, trial → PREMIUM_TRIAL, free → FREE). Responses gain only additive fields.

## Boundaries & Constraints

**Always:**
- Public Flyway `V43__plans_fusion.sql` is idempotent, public only, and touches data with guards only.
  - **Plans** (code, label, kind, sort_order, visible):
    - FREE « Gratuit », FREE, 0
    - PRO « Pro », PAID, 10
    - TEAM « Team », PAID, 20
    - BUSINESS « Business », PAID, 30
    - BUSINESS_TRIAL « Essai Business », TRIAL, 40
    - PREMIUM_TRIAL: sort 90, `visible=false`, archived
    - PREMIUM: sort 91, `visible=false`, archived
    - Upserts use `ON CONFLICT DO NOTHING`, except FREE: its label is set only if it is still the seeded value.
  - **New column** `plan_profiles.max_employees_per_store int NULL` with CHECK > 0 (null = unlimited).
  - **Profiles** (stores / employees / per-store / products; null = unlimited). An existing FREE row is updated only where it still holds the V33 values (employees 2):

    | Plan | Stores | Employees | Per store | Products |
    |---|---|---|---|---|
    | FREE | 1 | 1 | 1 | 500 |
    | PRO | 3 | null | 3 | null |
    | TEAM | 8 | null | 8 | null |
    | BUSINESS | null | null | null | null |
    | BUSINESS_TRIAL | null | null | null | null |
  - **`profile_modules`**, from the existing registry only:
    - PRO = invoicing, exports, report-config.
    - TEAM, BUSINESS and BUSINESS_TRIAL = the 8 paid modules: advanced-reports, alerts, counting, exports, invoicing, multi-store, profitability, report-config.
  - **`plan_settings`:**
    - new column `default_paid_plan_code` (FK plans), set to TEAM;
    - `trial_plan_code` becomes BUSINESS_TRIAL and `trial_months` becomes 1. These two are updated only if still (PREMIUM_TRIAL, 6).
  - **`tenant_plan_events` cause CHECK:** add `PLAN_MIGRATION`.
- `PlanSettings` / `JdbcPlanCatalog` carry `defaultPaidPlan()`. `saveSettings` refuses it unless the plan is PAID and not archived, using the 19.7a validation style.
- **Per-store employee limit:**
  - **Where it applies:** create employee, reactivate employee, and update employee when `storeId` changes.
  - **Check:** count the ACTIVE employees of the target store, excluding the employee being moved. When `count >= max_employees_per_store`, refuse with `PLAN_LIMIT_EXCEEDED`.
  - **Error details** keep the V1 shape `{entity:"employees", limit, current, plan}` and add only `scope:"store"`.
  - The tenant-wide employee check is unchanged and runs first.
  - Nobody is ever deactivated. Tenants above a limit keep their members, and only additions are refused (ADR-03 grandfathering).
- **One-shot job `PlanMigrationJob`:**
  - **Pattern:** modelled on `PlanTypeRealignJob`. It runs on `ApplicationReadyEvent` on a daemon thread, under ShedLock, gated by `keevo.scheduling.enabled`.
  - **Scope:** for each non-deleted tenant except the system tenant, in one transaction per tenant: `UPDATE subscriptions SET plan_type = TEAM WHERE plan_type = PREMIUM`, keeping `expires_at` and status.
  - **When a row was moved:**
    - sync `public.tenants.plan_type`;
    - record a `PLAN_MIGRATION` event PREMIUM → TEAM (actor null, reason « Fusion : Premium devient Team »);
    - run `planModuleRows.sync`.
  - **Idempotence:** idempotent, so a rerun moves nothing. A failure on one tenant is logged and does not stop the others.
  - The mapping (PREMIUM → TEAM) is a declared one-shot data migration constant, allow-listed in `PlanCodeLiteralGateTest` with that reason.
- **`POST /admin/tenants/{tenantId}/plan/premium`:**
  - `ActivatePremiumRequest` gains an optional `planCode`. If absent, it uses `plan_settings.default_paid_plan_code`.
  - The plan must exist, be of kind PAID and not be archived. Otherwise → 422 `VALIDATION_ERROR`, « Ce plan ne peut pas être attribué ».
  - Renewal, payment and event behaviour are unchanged, now keyed on the chosen plan.
  - The `PlanType.PREMIUM` constant leaves `AdminPlanService`, as does its gate allow-list entry.
- **`POST /admin/subscriptions/{schema}/activate` (`ActivatePlanService`):**
  - Refuse an archived plan (« Ce plan ne peut pas être attribué »).
  - A FREE-kind plan requires `expiresAt` null (« Pas de date d'expiration pour un plan gratuit »).
  - TRIAL and PAID plans require `expiresAt` to be present and in the future (« Date d'expiration requise et future pour ce plan »).
  - All three refusals → 422 `VALIDATION_ERROR`.
- **Additive responses:**
  - `GET /subscription/me` gains `planCode`, `planLabel` and `planKind` (`FREE|TRIAL|PAID`), plus `maxEmployeesPerStore`.
  - `planType` stays the V1 value.
  - `GET /admin/plan-profiles` items gain `label`, `kind`, `archived` and `maxEmployeesPerStore`.
  - `PUT /admin/plan-profiles/{plan}` accepts an optional `maxEmployeesPerStore`.
- **Tests:**
  - the whole suite stays green, run by package;
  - V43 is idempotent (run twice), and an edited FREE profile is not overwritten;
  - the job moves PREMIUM to TEAM with the same `expires_at`, the event and the module rows, and a rerun is a no-op;
  - a PREMIUM_TRIAL tenant is untouched and still expires to FREE;
  - a new registration gets BUSINESS_TRIAL for 1 month, `/subscription/me` returns `planType=PREMIUM_TRIAL`, `planLabel=« Essai Business »` and the 8 modules in trial, and expiry gives FREE;
  - grandfathering: a FREE tenant with 3 active employees keeps them and a 4th is refused; the same for reactivation;
  - per-store limit on PRO: a 4th employee in one store is refused with `scope:"store"`, while the same employee can go into another store;
  - activation: the default plan is TEAM, `planCode` PRO works, and archived / FREE / TRIAL / unknown codes give 422;
  - `expiresAt` versus kind on the activate endpoint;
  - `saveSettings` refuses a non-PAID default paid plan.
- **FUS-NFR11:**
  - the job does one statement per tenant, one shot only, with no per-row loop;
  - the per-store count is one indexed count (`idx_employees_store_id` exists);
  - catalogue lookups go through the 19.7a cache.
- **Live check:** boot on keevo_dev (V43 applied, job run: no PREMIUM subscription left, events present); register through the API → BUSINESS_TRIAL, 1 month; Redmi: the subscription screen still shows a Premium trial for the V1 app. Message the nkap-cc session before using the Redmi.

**Never:**
- no deactivation of employees, stores or products;
- no usage quotas (documents, SMS, emails, AI credits) and no FREE invoicing grant: 19.8 owns quotas and the FREE `invoicing` grant with its 300 documents/month;
- no prices or billing cycles (21.10 / 21.1);
- no new module ids;
- no super-admin plan CRUD (21.10);
- no change to the V1 values `planType` / `details.plan`;
- no Flutter work;
- no deletion of plan codes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| PREMIUM tenant at boot | subscription PREMIUM, expires 2027-01-31 | TEAM, same expiry, 8 modules ACTIVE, event PLAN_MIGRATION; V1 still `PREMIUM` | tenant failure logged, others continue |
| New sign-up | settings (BUSINESS_TRIAL, 1) | BUSINESS_TRIAL, expires +1 month, V1 `PREMIUM_TRIAL`; after expiry FREE | — |
| Running 6-month trial | PREMIUM_TRIAL until 2027-04 | unchanged until its end, then FREE | — |
| FREE over limit | 3 active employees | all kept; create/reactivate → `PLAN_LIMIT_EXCEEDED` {entity employees, limit 1, current 3, plan FREE} | 403 as today |
| PRO store full | store A has 3 active employees | add/move into A refused `scope:"store"`; into B accepted | — |
| Activate default | `/plan/premium` without planCode | TEAM | — |
| Activate bad plan | planCode FREE / BUSINESS_TRIAL / PREMIUM (archived) / NOPE | 422 « Ce plan ne peut pas être attribué » | — |
| Activate expiry mismatch | FREE + expiresAt, or PAID without / past expiresAt | 422 with the French message | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/resources/db/migration/V42__plan_catalogue.sql` -- catalogue, `plan_settings`, FKs (19.7a); V43 follows its idempotent style. Causes CHECK lives in `V22__tenant_suspension_payment_void.sql:21-23`; profiles/modules seed in `V33__plan_profiles.sql:34-44`.
- `keevo/backend/src/main/java/com/keevo/shared/module/adapter/out/persistence/JdbcPlanCatalog.java` -- `SETTINGS_SQL`, `UPSERT_SETTINGS_SQL`, `saveSettings` validation: add `default_paid_plan_code`.
- `keevo/backend/src/main/java/com/keevo/shared/capability/entitlement/` -- `PlanSettings` (+ defaultPaidPlan), `PlanCatalog` (+ `defaultPaidPlan()`), `PlanProfile`/`PlanProfiles` (+ `maxEmployeesPerStore`), `PlanChangeCause` (+ `PLAN_MIGRATION`), `PlanLimitCheck` (+ per-store check).
- `keevo/backend/src/main/java/com/keevo/shared/module/adapter/out/persistence/JdbcPlanProfileStore.java` -- read/FIND_ALL/UPSERT the new column.
- `keevo/backend/src/main/java/com/keevo/subscription/plan/application/service/PlanLimitGuard.java` (`checkLimit` ~96-114) -- per-store check with `scope:"store"` detail; same refusal path.
- `keevo/backend/src/main/java/com/keevo/identity/employee/application/service/CreateEmployeeService.java:89-94`, `ReactivateEmployeeService.java:84-90`, `UpdateEmployeeService.java:98-103` -- call the per-store check (count ACTIVE in target store; employee already ACTIVE in that store excluded); new count port next to `EmployeeCountAdapter.java:28`.
- `keevo/backend/src/main/java/com/keevo/subscription/plan/adapter/in/bootstrap/PlanTypeRealignJob.java` -- template for `PlanMigrationJob` (same package): ShedLock, ready event, scheduling flag, per-tenant CTE; then `JdbcPlanChangeRecorder` + `PlanModuleRowSync.sync`.
- `keevo/backend/src/main/java/com/keevo/admin/subscription/application/service/AdminPlanService.java:103-148` (`activatePremium`, `PlanType premium = PlanType.PREMIUM` l.121) and `admin/subscription/adapter/in/rest/dto/ActivatePremiumRequest.java` -- optional `planCode`.
- `keevo/backend/src/main/java/com/keevo/subscription/plan/application/service/ActivatePlanService.java:113-144` -- kind/archive/expiresAt validation.
- `keevo/backend/src/main/java/com/keevo/subscription/plan/application/service/GetSubscriptionService.java:65` + `SubscriptionResponse` -- additive fields.
- `keevo/backend/src/main/java/com/keevo/shared/module/application/PlanProfileAdminService.java` + `admin/plan/adapter/in/rest/AdminPlanProfileController.java` -- additive list fields, optional `maxEmployeesPerStore` on PUT.
- `keevo/backend/src/test/java/com/keevo/architecture/PlanCodeLiteralGateTest.java:38-52` -- remove `AdminPlanService`, add `PlanMigrationJob` (reason: one-shot ADR-06 migration).
- `keevo/backend/src/test/java/com/keevo/.../PlansAreDataFullContextTest.java` -- extend for fusion scenarios.
- Read-only facts: tenant `employees.store_id NOT NULL` + `idx_employees_store_id` (`db/modules/core/V1__core_baseline.sql:194,208`); module scripts may not UPDATE data nor touch public (`db/modules/README.md:14-26`) — hence a boot job; downgrades never deactivate (expiry, `downgradeToFree`, `ChangeTenantPlanService`).
- Bref reference (read-only): `/home/toor/Project/FreeLance/AI/api-bref/src/entitlement/constants/plan-policy.constant.ts` (shops / members per shop 1·3·8·∞), labels Gratuit/Pro/Team/Business (`src/plans/plans.service.ts`).

## Tasks & Acceptance

**Execution:**
- `db/migration/V43__plans_fusion.sql` -- seed plans/profiles/modules/settings, new columns, cause CHECK -- starting data ADR-03/04/05/06.
- `shared/capability/entitlement/*`, `JdbcPlanCatalog`, `JdbcPlanProfileStore` -- default paid plan + per-store limit in the model -- data, not code.
- `PlanLimitGuard`, employee services, count adapter -- per-store check -- Bref member rule.
- `PlanMigrationJob` (+ `PlanChangeCause.PLAN_MIGRATION`) -- PREMIUM → TEAM -- ADR-06.
- `AdminPlanService`, `ActivatePremiumRequest`, `ActivatePlanService` -- plan parameter, validation -- routed 19.7a items.
- `GetSubscriptionService`/`SubscriptionResponse`, `PlanProfileAdminService`/controller -- additive fields.
- Tests per the intent's Tests bullet; gate allow-list update.
- `_bmad-output/implementation-artifacts/spec-19-7b-plans-fusion.md` -- after the commit (finalize step, not the implementer), append the French « Mobile (React Native — dépôt AI/bref) » and « Dashboard super-admin (autre dépôt) » sections and the `m19-7b` / `d19-7b` sprint keys -- project rule: additive `/subscription/me` fields, `scope:"store"`, `planCode` on activation, plan-profile list fields.

**Acceptance Criteria:**
- Given keevo_dev after boot, when querying all tenant schemas, then no subscription has plan PREMIUM, each moved tenant has one PLAN_MIGRATION event and `public.tenants.plan_type = TEAM`.
- Given a V1 2.0.1 app on a TEAM or BUSINESS_TRIAL tenant, when it reads `/subscription/me` or gets `PLAN_LIMIT_EXCEEDED`, then it sees `PREMIUM` / `PREMIUM_TRIAL` exactly as before.
- Given the dashboard calls `/plan/premium` without `planCode`, when it succeeds, then the tenant is on TEAM.

## Spec Change Log

## Review Triage Log

### 2026-11-04 — Review pass
- verdicts: 36 findings — high 0, medium 11, low 16, false 7, maybe-false 2
- findings:
  - Patterns/complexity:
    - `[medium]` `[patch]` PlanMigrationJob scans every tenant on every boot forever — done-marker table `public.data_migrations`, skip when present.
    - `[low]` `[patch]` StoreEmployeeLimit locks and reads the plan before knowing a per-store limit exists — plan read first, early return on unlimited.
    - `[low]` `[patch]` Two count round-trips on create/reactivate — one query with two FILTER counts.
    - `[low]` `[patch]` V43 cause CHECK validated under ACCESS EXCLUSIVE — NOT VALID + VALIDATE.
    - `[low]` `[patch]` Flag argument `perStoreGiven` in PlanProfileAdmin.update — sealed LimitPatch type.
    - `[low]` `[patch]` GetSubscriptionService two catalogue lookups — one definition() call.
  - Verification gap:
    - `[medium]` `[patch]` PLAN_MIGRATION exclusion in RevenueMetrics untested — RevenueMetricsTest case added.
    - `[medium]` `[patch]` PlanMigrationJob guard and per-tenant failure isolation untested — guard + isolation tests.
    - `[medium]` `[patch]` PlanCatalog.SEED still the V42 offer (default paid PREMIUM) — SEED mirrors V43.
  - Intent alignment:
    - `[medium]` `[patch]` Renewal narrowed to same-plan (R3) — restored kind-based renewal (spec: behaviour unchanged; ADR-06 no loss).
    - `[false]` `[reject]` `/plan/premium` response planType now TEAM — admin APIs return catalogue codes (19.7a intent); the V1 boundary is the tenant app surfaces only.
    - `[false]` `[reject]` Per-store check also on ReassignStoreService — it is an "update when storeId changes" path; covering it is the intent.
    - `[false]` `[reject]` MIGRATE_SQL also writes legacy max_* columns — same as every other plan-change path (expiry, activation); expires_at and status kept.
    - `[false]` `[reject]` RevenueMetrics ignores PLAN_MIGRATION — required so the migration is not counted as conversion/churn (tested by the gap patch).
    - `[false]` `[reject]` FREE label clause had nothing to act on — V42 label already « Gratuit »; sort order guarded.
    - `[medium]` `[patch]` SEED default paid PREMIUM — grouped with the SEED patch.
    - `[low]` `[patch]` Test surface (job via migrateAll only, failure isolation) — grouped with the gap tests; boot wiring mirrors PlanTypeRealignJob.
    - `[false]` `[reject]` Live check not in the diff — performed (keevo_dev: 4 tenants migrated, registration BUSINESS_TRIAL +1 month); Redmi recorded under Verification.
  - Blind hunter:
    - `[medium]` `[patch]` Paid-to-paid switch loses paid days — grouped with the renewal patch.
    - `[medium]` `[patch]` Activation WhatsApp text says « Premium » for any plan — event carries the granted plan; label or neutral wording.
    - `[medium]` `[patch]` SEED fallback — grouped.
    - `[maybe-false]` `[reject]` Migration moves EXPIRED/SUSPENDED PREMIUM rows — the move is a rename of the plan whatever the status; a single subscription row per tenant schema; module sync handles status as for PREMIUM.
    - `[low]` `[patch]` Fallback target profile written into migrated rows — job refuses a fallback profile.
    - `[medium]` `[patch]` Boot scan forever — grouped with the done-marker.
    - `[false]` `[reject]` `/activate` refusing archived PREMIUM breaks legacy callers — refusing archived plans is the intent; V1 apps never call admin endpoints.
    - `[low]` `[patch]` Per-store refusal names V1 `PREMIUM` — V1 `plan` kept; additive `planCode`/`planLabel`.
    - `[low]` `[patch]` ActivatePlanService uses Instant.now() and inline FQNs — injected Clock, imports.
    - `[low]` `[patch]` planCode @Size message misleading, no format check — @Pattern « Code de plan invalide ».
  - Edge-case hunter:
    - `[medium]` `[patch]` Renewal onto another paid plan — grouped.
    - `[low]` `[patch]` V43 trial switch skipped when months edited, leaving the archived PREMIUM_TRIAL — code switched whenever PREMIUM_TRIAL, months only when 6.
    - `[low]` `[patch]` Job accepts an archived TEAM — guard refuses archived.
    - `[low]` `[patch]` Fallback target profile — grouped.
    - `[low]` `[reject]` PUT without per-store on a plan without stored profile saves unlimited — every catalogue plan has a profile row (V33/V43).
    - `[maybe-false]` `[reject]` Null tenant in StoreEmployeeLimit — TenantSchema.validate rejects null before any lock; employee endpoints always run in a tenant context.
    - `[low]` `[patch]` SEED — grouped.
    - `[low]` `[reject]` AC "no PREMIUM in any schema" vs deleted/failed tenants — deleted tenants are out of scope by design; failures are logged and retried at next boot (no done-marker on failure).

## Design Notes

- **Trial as a TRIAL-kind plan, not a subscription flag.** The epic note suggested a trial status on the subscription. That would split the effective kind over the plan code and a flag in about 15 kind-based sites: SQL on `public.tenants`, events, revenue conversion and push audiences. It would also lose the trial → paid event when the plan code stays the same. A dedicated TRIAL-kind plan whose profile mirrors BUSINESS keeps the 19.7a model intact, with one rule: behaviour follows the plan's kind. ADR-04 asks only for a 1-month trial of the most complete plan, with modifiable values, and this design meets it. The admin keeps BUSINESS_TRIAL aligned through the profile endpoints. The epic note is updated to match.
- **Pattern:** catalogue/settings as data (strategy by kind, unchanged). A one-shot idempotent migration job (template method of `PlanTypeRealignJob`). The per-store check is a second guard in the existing `PlanLimitCheck` port.
- **Cost:**
  - **Job:** one UPDATE statement per tenant, run once; event and module sync only for moved tenants (about 3 in prod).
  - **Employee create/move:** +1 indexed `count(*)`.
  - **Activation:** catalogue map lookups. `/subscription/me`: one catalogue lookup for the label.
- **Grandfathering** needs no code. The guard already refuses on `current >= max`, and no downgrade path deactivates anyone.
- **Quotas** are 19.8. Bref's 300 documents, 5 SMS, 100 emails and AI credits live in `usage_counters` and profile quota columns. The FREE `invoicing` grant must ship with the documents quota, or Keevo FREE would get unlimited invoices.

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='com/keevo/<pkg>/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` for each package (one at a time) -- expected: 0 failures.

**Manual checks:**
- Local backend (WhatsApp noop, FCM off) on keevo_dev: Flyway V43 success; `PlanMigrationJob` log; SQL: no `PREMIUM` in any `kv_*.subscriptions`; registration via API → BUSINESS_TRIAL +1 month; `/subscription/me` V1 fields unchanged + new fields.

## Auto Run Result

- **Summary:** the fusion offer is data. V43 seeds FREE « Gratuit » / PRO « Pro » / TEAM « Team » / BUSINESS « Business » / BUSINESS_TRIAL « Essai Business » (TRIAL, profile = BUSINESS); PREMIUM and PREMIUM_TRIAL archived; profiles with the new per-store employee limit (FREE 1/1/1, PRO 3/–/3, TEAM 8/–/8, BUSINESS unlimited); settings trial BUSINESS_TRIAL 1 month, default paid plan TEAM; cause PLAN_MIGRATION; `public.data_migrations` done-marker. `PlanMigrationJob` (one-shot, ShedLock, done-marker) moves PREMIUM subscriptions to TEAM keeping expiry, with event and module resync. Per-store employee limit on create / reactivate / update / reassign. `/plan/premium` takes an optional `planCode` (default TEAM); both activation paths refuse archived / wrong-kind plans and check `expiresAt` against the kind. WhatsApp plan texts use the plan label. Additive fields on `/subscription/me`, `PLAN_LIMIT_EXCEEDED` details and admin plan profiles; V1 values unchanged.
- **Review:** 36 findings — patched 27 rows (medium 11, low 16) in 15 fix groups (kind-based renewal restored, plan label in WhatsApp texts, SEED = V43, migration job guards + done-marker, V43 trial switch and NOT VALID constraint, lock-after-limit and single count query, sealed LimitPatch, single catalogue lookup, additive planCode/planLabel in limit details, injected Clock, planCode format, 3 test gaps). Rejected 9 (false 7, maybe-false 2) with reasons in the triage log. Nothing deferred.
- **Follow-up review recommended: true** — first pass patched 11 medium entries; the renewal rule, the migration job (guards, done-marker, failure isolation) and the WhatsApp texts changed after review and were not re-reviewed.
- **Verification:** full suite by package green — 5,077 tests, 0 failure (acceptance 4, admin 586, architecture 34, catalog 306, commerce 263, crm 158, feedback 52, identity 525, inventory 410, invoicing 170, messaging 192, reporting 421, shared 1172, store 60, subscription 89, sync 635); four outdated expectations (old trial / default paid plan) updated. Live keevo_dev (WhatsApp noop, FCM off): V43 re-applied, settings (FREE, BUSINESS_TRIAL, 1, TEAM), PREMIUM/PREMIUM_TRIAL archived, 4 PLAN_MIGRATION events, no PREMIUM subscription left in any live tenant schema, done-marker written, second boot moved 0; API registration KV-9D6KUN → `planType=PREMIUM_TRIAL`, `planCode=BUSINESS_TRIAL`, « Essai Business », expires +1 month; Redmi V1 app (KV-EW02A2, running 6-month trial): « Essai Premium », expires 01/05/2027, unlimited — unchanged.
- **Residual risks:** a dashboard still calling `/admin/subscriptions/{schema}/activate` with `PREMIUM` now gets 422 (archived) — it must send TEAM or use `/plan/premium`; BUSINESS_TRIAL's profile is a copy of BUSINESS (keep both aligned until 21.10); quotas and the FREE invoicing grant are 19.8.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée, commit `42783992` (branche `refonte/modulaire`, poussée aussi sur `develop`).

**Ce qui est fait côté API**
- Les offres de la fusion sont en base. Grille de départ :

  | Code | Libellé | Boutiques | Membres par boutique | Modules payants |
  |---|---|---|---|---|
  | `FREE` | Gratuit | 1 | 1 | aucun |
  | `PRO` | Pro | 3 | 3 | factures, exports, réglage des rapports |
  | `TEAM` | Team | 8 | 8 | les 8 modules payants |
  | `BUSINESS` | Business | illimité | illimité | les 8 modules payants |
  | `BUSINESS_TRIAL` | Essai Business | illimité | illimité | les 8 modules payants |

  Le plan `BUSINESS_TRIAL` est un essai.
- Un nouvel inscrit reçoit 1 mois d'« Essai Business », puis passe en Gratuit. Les essais Premium de 6 mois déjà en cours vont à leur terme.
- Les comptes Premium sont passés sur Team, avec la même date de fin.
- La limite de membres par boutique s'applique à l'ajout, à la réactivation et au changement de boutique d'un employé. Un compte déjà au-dessus d'une limite garde tout le monde, mais ne peut rien ajouter.
- Les valeurs V1 sont inchangées : `planType` vaut toujours `FREE`, `PREMIUM_TRIAL` ou `PREMIUM`.

**À utiliser**
- **`GET /api/v1/subscription/me`** (`keevo/backend/src/main/java/com/keevo/subscription/plan/adapter/in/rest/dto/SubscriptionResponse.java`). Champs ajoutés :
  - `planCode` : le plan réel (`TEAM`, `BUSINESS_TRIAL`…) ;
  - `planLabel` : le nom français à afficher ;
  - `planKind` : `FREE`, `TRIAL` ou `PAID` ;
  - `maxEmployeesPerStore` : `null` = illimité.

  `planType` reste la valeur V1 ; ne pas l'afficher dans la nouvelle app.
- **Refus pour limite atteinte.** HTTP 403, `domainCode` = `PLAN_LIMIT_EXCEEDED`, message « Limite de votre plan atteinte ». Les `details` contiennent :
  - `entity` : `stores`, `products` ou `employees` ;
  - `limit` et `current` ;
  - `plan` : la valeur V1 ;
  - champs ajoutés : `planCode`, `planLabel`, et `scope: "store"` quand c'est la limite par boutique qui bloque.

  Ce refus vient de `PlanLimitGuard.java`. Il est renvoyé aussi dans le résultat d'une opération de synchronisation.

**À implémenter côté mobile**
1. L'écran Abonnement affiche `planLabel`, jamais un code. Utiliser `planKind` pour le style : « Essai » si `TRIAL`, « Gratuit » si `FREE`.
2. Pour un essai, afficher la date de fin (`expiresAt`) et, à l'approche de l'échéance, une invitation à choisir une offre.
3. Afficher la limite par boutique quand `maxEmployeesPerStore` n'est pas nul.
4. Sur un refus `PLAN_LIMIT_EXCEEDED` :
   - si `scope` vaut `"store"` : « Cette boutique a atteint sa limite de {limit} membres avec l'offre {planLabel}. » ;
   - sinon : « Limite de l'offre {planLabel} atteinte ({current}/{limit}). ».

   Proposer de voir les offres, sans afficher de code technique.
5. Mettre ces champs en cache comme le reste du profil d'abonnement, et les relire au retour en ligne.

**Implémentation de référence :** aucune (l'app Flutter de ce dépôt ne sert qu'aux contrôles et ne lit pas les nouveaux champs).

**Critères d'acceptation mobile / scénarios de test**
- **Nouvel inscrit.** Étant donné un compte créé par `POST /api/v1/auth/register`, quand l'app ouvre Abonnement, alors elle affiche « Essai Business » et une date de fin à +1 mois.
- **Limite par boutique.** Étant donné un compte Pro dont une boutique a 3 employés actifs, quand l'owner y ajoute un 4e employé, alors l'app affiche le message de limite par boutique et la création n'a pas lieu. Ajouter le même employé dans une autre boutique réussit.
- **Ancien compte Premium.** Étant donné ce compte, quand l'app ouvre Abonnement, alors elle affiche « Team » avec la même date de fin qu'avant.

**Clé de suivi mobile :** `m19-7b-plans-fusion`.

## Dashboard super-admin (autre dépôt)

**Statut :** API livrée, commit `42783992`.

**Ce qui est fait côté API**
- **« Activer Premium » choisit le plan payant.**
  - Il n'y a plus de plan Premium à vendre. Le plan attribué par défaut est Team.
  - Un plan payant encore en cours est prolongé à partir de sa fin, comme avant.
- **Les deux chemins d'activation refusent un plan qui ne convient pas** : plan archivé, mauvaise nature de plan, ou date d'expiration incohérente avec le plan.
- **Les profils de plans ont un nouveau réglage** : le nombre maximum de membres par boutique.

**À utiliser**
- **`POST /api/v1/admin/tenants/{tenantId}/plan/premium`**
  - Corps : `{expiresAt "AAAA-MM-JJ", amountXaf, method, paidAt?, note?, planCode?}`. Le champ `planCode` est optionnel ; absent, c'est Team.
  - 422 `VALIDATION_ERROR` :
    - « Ce plan ne peut pas être attribué » : plan inconnu, archivé, gratuit ou essai ;
    - « Code de plan invalide » : format du code.
  - La réponse `planType` donne maintenant le vrai code du plan (`TEAM`, `PRO`…).
  - Fichiers : `keevo/backend/src/main/java/com/keevo/admin/subscription/adapter/in/rest/AdminTenantPlanController.java` et `keevo/backend/src/main/java/com/keevo/admin/subscription/application/service/AdminPlanService.java`.
- **`POST /api/v1/admin/subscriptions/{schema}/activate`**, réponses 422 `VALIDATION_ERROR` :
  - « Ce plan ne peut pas être attribué » : plan archivé, dont `PREMIUM` et `PREMIUM_TRIAL` ;
  - « Pas de date d'expiration pour un plan gratuit » ;
  - « Date d'expiration requise et future pour ce plan ».
- **`GET /api/v1/admin/plan-profiles`**
  - Chaque plan gagne `kind` (`FREE`, `TRIAL` ou `PAID`), `archived` et `maxEmployeesPerStore`. `label` existait déjà.
  - `PUT /api/v1/admin/plan-profiles/{plan}` accepte `maxEmployeesPerStore` (optionnel : absent = valeur gardée ; `null` explicite = illimité).

**À implémenter côté dashboard**
1. Le bouton « Activer Premium » devient « Activer une offre ». Il propose un choix parmi les plans `kind = PAID` non archivés de `GET /admin/plan-profiles`, avec Team présélectionné, et envoie `planCode`.
2. Afficher partout le `label` du plan, jamais le code. Les plans archivés n'apparaissent pas dans les listes de choix.
3. Ajouter « Membres par boutique » à l'écran d'édition d'un profil de plan.
4. Garder « Essai Business » aligné sur « Business » : c'est une copie, elle n'est pas synchronisée automatiquement avant la 21.10.

**Critères d'acceptation / scénarios de test**
- **Activation par défaut.** Étant donné un compte Gratuit, quand le super-admin active une offre sans choisir de plan, alors le compte passe sur Team.
- **Plan archivé.** Étant donné qu'on envoie `PREMIUM` à `/activate`, alors on reçoit 422 « Ce plan ne peut pas être attribué ».

**Clé de suivi dashboard :** `d19-7b-plans-fusion`.
