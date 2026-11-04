---
title: '19.8 — Atomic usage counters and plan quotas (documents, SMS, emails, AI credits)'
type: 'feature'
created: '2026-11-04'
status: 'ready-for-dev'
baseline_revision: '8d37567dbb282ac50cec44ec1f7e8452b7c600b9'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-7b-plans-fusion.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Bref meters what each owner consumes: documents per month, SMS, emails, and AI credits per month with a daily cap. FREE also gets a lifetime allowance of 5 SMS. Bref counts with an atomic conditional UPDATE, so several devices cannot overshoot a quota. Keevo has no usage counter. The fusion needs counters per tenant and period that are race-free and never block a core write (POS sale, payment). The quota values must be data that can be edited without a redeploy (ADR-05, FUS-FR8). They start from Bref's `plan-policy.constant.ts`. Purchased packs, such as extra SMS, must be consumed once the plan quota is used up.

**Approach:**
- **Data:** a public table of quotas per plan, keyed by plan, metric and period kind, seeded from the Bref grid; a public `usage_counters` table; a public `usage_credits` table for packs.
- **Service:** `UsageQuotaService.assertAndIncrement(tenant, metric, n)` resolves the tenant's plan quotas. It increments each period counter with one guarded upsert. When a monthly or lifetime quota is exhausted, it falls back to the pack. When nothing is left, it throws `QUOTA_EXCEEDED`.
- **First consumer:** issuing an invoice (REST and sync) counts one document. The SMS, email and AI senders come with 19.9 and the AI stories and use the same port.
- **Visibility:** `/subscription/me` gains a quotas list, and admin plan profiles show and edit quotas.

## Boundaries & Constraints

**Always:**

- **Public Flyway `V44__usage_quotas.sql`.** Idempotent, public tables only.
  - **`plan_quotas`**
    - Columns: `plan_type` (FK `plans(code)`), `metric` (CHECK `DOCUMENTS|SMS|EMAILS|AI_CREDITS`), `period` (CHECK `MONTH|DAY|LIFETIME`), `quota_limit` (int, NOT NULL, CHECK ≥ 0), `updated_at`.
    - Primary key `(plan_type, metric, period)`.
    - No row means unlimited.
  - **`usage_counters`**
    - Columns: `tenant_id` (FK tenants, ON DELETE CASCADE), `metric`, `period_key` (varchar 16: `2026-11` for a month, `2026-11-04` for a day, `LIFETIME`), `used` (bigint ≥ 0), `updated_at`.
    - Primary key `(tenant_id, metric, period_key)`.
  - **`usage_credits`**
    - Columns: `tenant_id` (FK, cascade), `metric`, `remaining` (int ≥ 0), `updated_at`.
    - Primary key `(tenant_id, metric)`.
  - **Seed** (`ON CONFLICT DO NOTHING`):

    | Plan | Quotas |
    |---|---|
    | FREE | DOCUMENTS MONTH 300; SMS LIFETIME 5; EMAILS MONTH 100; AI_CREDITS MONTH 0; AI_CREDITS DAY 0 |
    | PRO | SMS MONTH 20; AI_CREDITS MONTH 500; AI_CREDITS DAY 20 |
    | TEAM | SMS MONTH 50; AI_CREDITS MONTH 750; AI_CREDITS DAY 30 |
    | BUSINESS | SMS MONTH 80; AI_CREDITS DAY 50 |
    | BUSINESS_TRIAL | same as BUSINESS |
    | PREMIUM_TRIAL (archived, trials running) | same as BUSINESS |
    | PREMIUM (archived) | same as TEAM |

- **Metrics and periods are concepts, the values are data.** `UsageMetric {DOCUMENTS, SMS, EMAILS, AI_CREDITS}` and `QuotaPeriod {MONTH, DAY, LIFETIME}` are enums. Limits are rows.
- **Period keys:**
  - computed in `KeevoZones.WAT` (Africa/Douala) from the injected `Clock`;
  - per-country zones are story 20.5;
  - `resetAt` is the start of the next day or month in WAT, and null for LIFETIME.
- **`UsageQuotaService.assertAndIncrement(UUID tenantId, UsageMetric metric, int n)`:**
  - **Transaction:** runs in the caller's transaction (`Propagation.MANDATORY`), so a later failure of the caller rolls the count back.
  - **Plan resolution:** reads the tenant's current plan the way `PlanLimitGuard` does, then reads its quota rows for the metric (one indexed query).
  - **Per period, in a fixed order (DAY, MONTH, LIFETIME):** one statement, `INSERT … SELECT … WHERE n <= limit ON CONFLICT (pk) DO UPDATE SET used = usage_counters.used + EXCLUDED.used WHERE usage_counters.used + EXCLUDED.used <= limit RETURNING used`. No row returned means the quota is exhausted for that period.
  - **When MONTH or LIFETIME is exhausted:**
    - one guarded `UPDATE usage_credits SET remaining = remaining - n WHERE … AND remaining >= n RETURNING remaining`;
    - success means allowed, and the period counter is not incremented past its limit;
    - failure means `QUOTA_EXCEEDED`.
  - **When DAY is exhausted:** `QUOTA_EXCEEDED` at once. The daily cap is an anti-abuse cap and is never paid from packs.
  - **No quota row for the metric:** the counter is still incremented without a limit, so that « Mon plan » can show usage.
  - **`n` must be ≥ 1.**
- **`QUOTA_EXCEEDED`:**
  - New `ErrorCode`, HTTP 403 (the plan-limit family), French message « Quota de votre offre atteint ».
  - Details: `{metric, period, limit, used, resetAt, planCode, planLabel}`.
  - Sync: `AbstractSyncOperationHandler.rejectionDetails` also carries these details in `conflictData` for `QUOTA_EXCEEDED`.
- **Read-only `UsageQuotaService.status(tenantId)`:** per metric and quota, `{metric, period, limit, used, remaining, resetAt}` plus `credits` per metric. One query for the quotas and one for the current counters.
- **Documents:**
  - **What counts:** `InvoiceLifecycleUseCase.issue` (REST `POST /invoices/{id}/issue` and sync `ISSUE_INVOICE`) counts 1 DOCUMENTS before numbering, in the same transaction. A refusal leaves the draft a draft.
  - **What does not count:** automatic invoices and credit notes from the POS bridge (`InvoiceSaleUseCase`, `SaleCreditNoteUseCase`), manual credit notes, and draft creation.
  - **Replay:** a replayed sync op is already DUPLICATE before the handler runs, so it is never counted twice.
- **Core writes are never quota-checked:** sales, payments, stock, sync of non-document ops.
- **Additive API:**
  - `GET /subscription/me` gains `quotas: [{metric, period, limit, used, remaining, resetAt}]` and `credits: {metric: remaining}`.
  - Admin plan-profile GET items gain `quotas: [{metric, period, limit}]`.
  - `PUT /admin/plan-profiles/{plan}` accepts an optional `quotas` list. Absent keeps the stored quotas. A given list replaces the plan's quotas. Validation: known metric and period, limit ≥ 0, no duplicate (metric, period). An invalid list → 422 `PLAN_PROFILE_INVALID` with a French reason. Quota edits apply to the next increment.
- **Housekeeping:**
  - **Purge job:** a ShedLock job, nightly in WAT, deletes DAY counters older than 40 days and MONTH counters older than 25 months. LIFETIME counters are kept.
  - **Tenant deletion:** `TenantDeletionService` purges `usage_counters` and `usage_credits` explicitly, as it does for its siblings.
  - **Packs:** an internal port `UsageCredits.add(tenantId, metric, n)` (upsert) exists for 21.x payments. There is no purchase endpoint here.
- **Tests:**
  - **Concurrency:** 10 threads of `assertAndIncrement` on a limit of 5 → exactly 5 succeed, `used = 5` (Testcontainers).
  - **Periods:** DAY then MONTH, with no partial count when the second check fails.
  - **Pack fallback:** packs consumed after a MONTH or LIFETIME quota; packs not used for DAY.
  - **Unlimited:** still counted.
  - **Period rollover:** a clock moved to the next month gives a fresh counter, and `resetAt` is correct.
  - **Invoices:**
    - issue counts 1;
    - issue over quota → 403 `QUOTA_EXCEEDED` with details, and the invoice stays DRAFT;
    - sync `ISSUE_INVOICE` over quota → REJECTED with `conflictData`;
    - a replayed `ISSUE_INVOICE` op is not counted twice;
    - a POS sale with documents exhausted still completes and its automatic invoice is created.
  - **`/subscription/me`:** quotas and credits present; V1 fields unchanged.
  - **Admin:** PUT quotas, Keep semantics, invalid list → 422.
  - **V44:** idempotent.
- **FUS-NFR11:**
  - an increment is one quota read plus one upsert per quota period (≤ 2) plus at most one credits update;
  - no per-tenant loops except the purge, which is a single set-based DELETE per period kind;
  - the primary keys serve every lookup.
- **Live check:**
  - keevo_dev boot, V44 applied;
  - issue an invoice through the API on a tenant: the counter goes up, and `/subscription/me` shows the quotas;
  - force a FREE-like limit on a test plan to see the 403 and its details.
  - The Redmi is not needed (no V1 screen changes); if it is used, message the nkap session first.

**Never:**
- **No SMS, email or AI sending:** that is 19.9 and the AI stories.
- **No pack purchase or pricing:** 21.x.
- **The FREE `invoicing` grant is not in this story.** It moves to 19.13, where new modules arrive disabled by default (FUS-UX7). Granting it now would make invoicing appear in V1 apps for FREE tenants, and would make every POS sale create an automatic invoice.
- **No counting** of automatic, bridge or credit-note documents.
- **No blocking** of sales, payments or stock.
- **No change** to V1 response values.
- **No Flutter work.**

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Under quota | DOCUMENTS MONTH 300, used 10 | issue OK, used 11 | — |
| Quota reached | used 300, no credits | issue refused, draft kept | 403 `QUOTA_EXCEEDED` {DOCUMENTS, MONTH, 300, 300, resetAt 1st of next month WAT} |
| Pack fallback | SMS MONTH used = limit, credits SMS 10 | allowed, credits 9 | — |
| Daily cap | AI DAY 20 reached, credits AI 50 | refused (packs never pay DAY) | 403 `QUOTA_EXCEEDED` period DAY |
| Lifetime | FREE SMS LIFETIME 5 used | refused unless credits | 403, resetAt null |
| Unlimited | BUSINESS DOCUMENTS (no row) | allowed, counter +1 | — |
| Race | 10 concurrent, limit 5 | 5 succeed, used = 5 | 5 × `QUOTA_EXCEEDED` |
| Sync replay | ISSUE_INVOICE replayed | DUPLICATE, counter unchanged | — |
| POS sale, documents exhausted | sale + bridge invoice | both succeed, not counted | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/resources/db/migration/V43__plans_fusion.sql` -- style for V44 (idempotent, guarded); `public.data_migrations` there too.
- `keevo/backend/src/main/java/com/keevo/invoicing/invoice/application/usecase/InvoiceLifecycleUseCase.java:55` (`issue`) -- add the DOCUMENTS increment before numbering; REST `InvoiceController.java:199-201` and sync `adapter/in/sync/InvoiceSyncHandler.java` (ISSUE_INVOICE ~:105-136) both reach it.
- `keevo/backend/src/main/java/com/keevo/invoicing/invoice/application/usecase/InvoiceSaleUseCase.java:87-143` (`paidInvoice`), `SaleCreditNoteUseCase.java`, `IssueCreditNoteUseCase.java` -- must NOT count (check they do not go through `InvoiceLifecycleUseCase.issue`; if they share code, count only on the user path).
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/service/SyncPushService.java:181,369-404` -- dedupe before handler, one tx per op (read-only evidence); `shared/application/port/sync/AbstractSyncOperationHandler.java:47-61` (`rejectionDetails`) -- add `QUOTA_EXCEEDED`; `sync/sync/adapter/in/rest/dto/SyncOperationResultDto.java:9-13` doc.
- `keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java:136-147`, `shared/infrastructure/web/GlobalExceptionHandler.java:349-363` (403 group), `shared/infrastructure/web/ErrorMessages.java:30` -- new code, status, French message.
- `keevo/backend/src/main/java/com/keevo/subscription/plan/application/service/PlanLimitGuard.java` -- how the tenant's current plan and its catalogue definition are resolved (reuse, do not duplicate); place the new service in the `subscription/plan` module (`UsageQuotaService`, port in `shared/capability/entitlement` so invoicing can depend on it without a module cycle; check `architecture` tests).
- `keevo/backend/src/main/java/com/keevo/shared/domain/time/KeevoZones.java:9` (WAT), `shared/infrastructure/config/ClockConfig.java:16-18` (Clock bean).
- `keevo/backend/src/main/java/com/keevo/subscription/plan/application/service/GetSubscriptionService.java` + `adapter/in/rest/dto/SubscriptionResponse.java` -- additive `quotas`, `credits`.
- `keevo/backend/src/main/java/com/keevo/shared/module/PlanProfileAdmin.java:52-116`, `shared/module/application/PlanProfileAdminService.java:114`, `admin/plan/adapter/in/rest/AdminPlanProfileController.java:84-114` (`FIELDS` whitelist) -- quotas on view + optional patch (same Keep/Set pattern as `LimitPatch`).
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/activity/UserActivityPurgeJob.java:35-38` -- template for the purge job (cron zone Africa/Douala, `@SchedulerLock`).
- `keevo/backend/src/main/java/com/keevo/identity/auth/application/service/TenantDeletionService.java:262-303` -- add the two public tables.
- Bref reference (read-only): `/home/toor/Project/FreeLance/AI/api-bref/src/entitlement/quota.service.ts` (FREE SMS lifetime 5), `entitlement.service.ts:414-440` (atomic invoice quota), `invoice/invoice.controller.ts:255-292` (quote not counted until validated → 20.3).

## Tasks & Acceptance

**Execution:**
- `db/migration/V44__usage_quotas.sql` -- three tables + seed -- quotas as data.
- `shared/capability/entitlement/` (`UsageMetric`, `QuotaPeriod`, `UsageQuotaPort`, `UsageCredits`) + `subscription/plan` implementation (`UsageQuotaService`, JDBC adapter) -- atomic counting.
- `ErrorCode`/`GlobalExceptionHandler`/`ErrorMessages`/`AbstractSyncOperationHandler` -- `QUOTA_EXCEEDED`.
- `InvoiceLifecycleUseCase.issue` -- documents consumer.
- `GetSubscriptionService`/`SubscriptionResponse`, plan-profile admin service/controller/view -- additive read and edit.
- Purge job, `TenantDeletionService` -- housekeeping.
- Tests per the intent's Tests bullet (matrix rows each covered).
- `_bmad-output/implementation-artifacts/spec-19-8-compteurs-usage-quotas.md` -- after the commit (finalize step, not the implementer), French « Mobile » and « Dashboard » sections and `m19-8` / `d19-8` sprint keys.

**Acceptance Criteria:**
- Given a tenant whose plan has DOCUMENTS MONTH 300 with 300 used, when the app issues an invoice (REST or sync), then it gets `QUOTA_EXCEEDED` with `metric`, `limit`, `resetAt`, and the invoice stays a draft.
- Given the same tenant, when a POS sale is completed, then the sale and its automatic invoice succeed.
- Given a V1 2.0.1 app, when it reads `/subscription/me`, then every V1 field is unchanged (only `quotas` and `credits` are added).

## Spec Change Log

## Review Triage Log

## Design Notes

- **Pattern:** guarded atomic upsert (optimistic, set-based) instead of read-check-write, so a race can never overshoot without locks held across calls. Quotas as data rows (strategy by metric and period): metrics stay code concepts because each one has a consumer in code, while limits are data (ADR-05). The pack fallback is a chain: plan quota, then pack, then refuse.
- **Why `plan_quotas` is a separate table** and not profile columns: a metric can have several periods (AI: month and day), new metrics need no DDL on profiles, and the existing profile join would multiply its rows.
- **Why documents count at issue,** not at draft creation: a Keevo draft is not a document yet (it can be deleted). Issuing is what Bref counts as a created invoice. Quotes (20.3) will count at quote → invoice conversion, as Bref does.
- **Cost per issue:** 1 quota read (PK prefix), 1 upsert (PK), and possibly 1 credits update. `/subscription/me` adds 2 small queries.
- **FREE invoicing moved to 19.13:** the 19.7b routing note assumed the grant ships with this quota. With FUS-UX7 (new modules disabled by default), the grant belongs where the owner-disabled state exists. The quota is ready before that.

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='com/keevo/<pkg>/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` for each package (one at a time; check `free -m` ≥ 4 GB available first) -- expected: 0 failures.

**Manual checks:**
- Local backend on keevo_dev (WhatsApp noop, FCM off): V44 success; issue an invoice via the API → `usage_counters` row for DOCUMENTS; `/subscription/me` shows `quotas`; with a test plan quota of 1 the second issue → 403 `QUOTA_EXCEEDED` with French message and details.
