---
title: '19.7b — Fusion plans: FREE/PRO/TEAM/BUSINESS as data, PREMIUM → TEAM, 1-month trial, FREE 1 employee'
type: 'feature'
created: '2026-11-04'
status: 'ready-for-dev'
baseline_revision: 'bb4e8828621d3b36cf286bc7aa298edd4a1e694f'
review_loop_iteration: 0
followup_review_recommended: false
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
