---
title: '19.8 — Atomic usage counters and plan quotas (documents, SMS, emails, AI credits)'
type: 'feature'
created: '2026-11-04'
status: 'done'
baseline_revision: 'fe3b3b632c346db3a5db585b94982f97aca88cd9'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-7b-plans-fusion.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Pack-paid usage has no consumption record; SMS/email senders must count after provider success in a short transaction
    evidence: |-
      Routed to 21-3 (pack ledger / « Mon plan » display) and 19-9 (sender transaction boundary) in epics-fusion-keevo-bref.md, 2026-11-04.
    location: >-
      UsageQuotaService.increment, JdbcUsageCounterStore.consumeCredits
    severity: medium
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

### 2026-11-04 — Review pass
- verdicts: 38 findings — high 0, medium 9, low 19, false 5, maybe-false 5
- findings:
  - Patterns/complexity:
    - `[medium]` `[patch]` Nightly purge filters on length(period_key): full scan, period kind encoded in key length — `period` column + partial index, purge by period.
    - `[low]` `[patch]` Admin update re-reads quotas after the tx — in-tx read, replaced list returned.
    - `[low]` `[patch]` Null dependency instead of a null object in GetSubscriptionService — `UsageQuotaPort.NONE`.
    - `[low]` `[reject]` Tenant resolved twice on /subscription/me — one unique-index lookup, same cost.
    - `[low]` `[defer]` 19.9 senders must increment after provider success in a short tx, never around the HTTP call — routed to 19-9.
    - `[low]` `[patch]` Enum parsing loop / string keys for duplicates — Enum.valueOf, EnumSet keys.
  - Blind hunter:
    - `[false]` `[reject]` FREE AI DAY=0 blocks AI packs — Bref parity: FREE has no AI at all (`aiConversational=false`, quota.service checkAiCredits refuses regardless of packs).
    - `[medium]` `[defer]` Pack-paid usage not recorded (no ledger, « Mon plan » shows used=limit) — pack purchase/consumption history belongs with packs → 21-3.
    - `[medium]` `[patch]` Periods without a limit not counted (DAY-only AI, unlimited counted on MONTH only) — MONTH and DAY always counted; LIFETIME only with a lifetime quota.
    - `[low]` `[patch]` Partial count relies on caller rollback — documented on the port (MANDATORY); REST and sync both roll back per operation (verified SyncPushService one tx per op).
    - `[maybe-false]` `[reject]` Quota plan read from public.tenants may disagree with the subscription — every plan change (expiry, admin, migration, registration) updates both in the same transaction and PlanTypeRealignJob repairs drift; the expiry case is identical on both sources until the job runs. Unknown-code fallback patched (row below).
    - `[low]` `[patch]` String/UUID overloads easy to misuse — schema variants renamed `…ForSchema`.
    - `[low]` `[patch]` Audit log lacks quota before-state — logged before/after in tx.
    - `[low]` `[patch]` Dead null check (List.copyOf NPE) — null element → 422.
    - `[medium]` `[patch]` QUOTA_EXCEEDED and quota items expose only codes — French metricLabel / periodLabel added (project rule).
    - `[false]` `[reject]` V1/Bref clients and the QUOTA_EXCEEDED sync reason — no V1 tenant can reach it (FREE has no invoicing, paid plans unlimited documents); the mobile section documents the handling.
    - `[low]` `[patch]` Admin invalid inputs untested — extra key, missing limit, fractional limit, null element.
    - `[low]` `[patch]` Other untested paths — pack paid once (test), manual credit note not counted (test), purge failure log message, tearDown cleanup.
    - `[medium]` `[patch]` Purge full scan — grouped with the patterns row.
  - Edge-case hunter:
    - `[medium]` `[patch]` Mid-month downgrade starts the monthly quota from zero — grouped with "always count MONTH and DAY".
    - `[low]` `[patch]` Quota added later to DAY ignores past use — grouped (DAY always counted; LIFETIME only on lifetime plans by Bref semantics).
    - `[low]` `[reject]` Pack-paid unit also consumes a LIFETIME allowance — needs MONTH and LIFETIME quotas on the same metric, which no plan has; fixing adds branching for an unreachable case.
    - `[medium]` `[patch]` /subscription/me fails entirely when usage status throws — caught, empty quotas, WARN.
    - `[low]` `[patch]` NULL plan_type → null planCode/unlimited — resolved to the free plan.
    - `[low]` `[patch]` usage_credits.remaining integer overflow — bigint.
    - `[low]` `[patch]` Quotas re-read outside the tx after admin update — grouped.
    - `[low]` `[patch]` v44Idempotent count flaky with leftover test plans — restricted to seeded codes.
  - Verification gap:
    - `[medium]` `[patch]` Tenant deletion purge of usage tables untested — RGPD integration + unit verify.
    - `[medium]` `[patch]` Pack paid once for MONTH+LIFETIME untested — test added.
    - `[low]` `[patch]` Admin quota rejections untested — grouped.
  - Intent alignment:
    - `[false]` `[reject]` Seeded grid not enforced on any real tenant yet — intended (R1): FREE invoicing grant is 19.13, SMS/email/AI consumers 19.9+.
    - `[maybe-false]` `[reject]` Plan source public.tenants vs subscription row — grouped with the blind row.
    - `[maybe-false]` `[reject]` Two-period refusal not tested through REST/sync — only DOCUMENTS is wired (single period); covered at service level with rollback.
    - `[maybe-false]` `[reject]` Concurrency tested at service level, not via endpoints — the atomicity lives in the single SQL statement; endpoints add no shared state.
    - `[false]` `[reject]` Pack fallback tested only at service level — matches the intent (no purchase endpoint).
    - `[low]` `[reject]` Extra PK read on the refusal path beyond the NFR budget — refusal path only, one PK read for the `used` detail.
    - `[false]` `[reject]` Unlimited metrics listed with null limit / credits 0 shown — « Mon plan » visibility, additive.
    - `[maybe-false]` `[reject]` Deletion purge / live check not in tests — deletion now tested (gap patch); live check performed and recorded.

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

## Auto Run Result

- **Summary:** usage is metered per tenant and period with quotas as data. V44 adds `plan_quotas` (seeded from Bref's `plan-policy.constant.ts`: FREE documents 300/month, SMS 5 lifetime, emails 100/month, no AI; PRO/TEAM/BUSINESS SMS 20/50/80 per month, AI 500/750/unlimited per month and 20/30/50 per day), `usage_counters` (with a `period` column and a purge index) and `usage_credits` (packs, bigint). `UsageQuotaService` (port `UsageQuotaPort`, caller's transaction) counts MONTH and DAY always and LIFETIME under a lifetime quota, with one guarded upsert per period, and pays from packs once when a MONTH/LIFETIME quota is exhausted. The DAY cap is never paid from packs. Otherwise it throws `QUOTA_EXCEEDED` (403, French labels in details). Issuing an invoice counts one document; POS bridge invoices and credit notes do not. `/subscription/me` and admin plan profiles show and edit quotas. Nightly purge job; tenant deletion purges the tables.
- **Review:** 38 findings — patched 21 rows (medium 7, low 14) in 12 fix groups (all-period counting, period column + purge index, bigint credits, unknown plan → free quotas, /subscription/me resilience + null object, French labels, `…ForSchema` renames + rollback doc, in-tx admin audit, null quota element → 422, 3 test gaps). Deferred 2 → 21-3 (pack consumption history) and 19-9 (count after provider success, short transaction). Rejected 15 (false 5, maybe-false 5, low 5) with reasons in the triage log.
- **Follow-up review recommended: true** — first pass patched 7 medium entries; the counting rule (MONTH/DAY always, LIFETIME conditional) and the purge schema changed after review.
- **Verification:** full suite by package green — 5,103 tests, 0 failure (acceptance 4, admin 586, architecture 34, catalog 306, commerce 263, crm 158, feedback 52, identity 525, inventory 410, invoicing 179, messaging 192, reporting 421, shared 1172, store 60, subscription 106, sync 635), run with the ≥ 4 GB memory guard. Live keevo_dev (WhatsApp noop, FCM off): V44 re-applied; registration KV-GJHO4C (BUSINESS_TRIAL) → `/subscription/me` quotas with French labels (SMS 80/month, AI 50/day, others unlimited), `resetAt` = 1 Dec 00:00 WAT; draft → issue FAC-2026-000001 → DOCUMENTS counted 1 on MONTH and DAY. Earlier implementer live check: a test plan with a limit of 2 gave 403 `QUOTA_EXCEEDED` with full details, invoice kept as draft.
- **Residual risks:** no real tenant can hit a quota yet (FREE invoicing grant is 19.13; SMS/email/AI consumers 19.9 and later); quotas are read from `public.tenants.plan_type` (kept in step with the subscription by every plan change and the realign job); per-country period zones are 20.5 (WAT for now).

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit de la story 19.8, branche `refonte/modulaire`, poussé aussi sur `develop`).

**Ce qui est fait côté API**
- Les quotas de l'offre sont comptés par client et par période :
  - documents (factures émises) ;
  - SMS ;
  - emails ;
  - crédits IA (par mois et par jour).
- Les valeurs de départ sont celles de Bref. Aujourd'hui, seule l'émission d'une facture consomme un quota (1 document). SMS, emails et IA arriveront avec la 19.9 et les stories IA.
- Un quota épuisé renvoie une erreur explicite. Les ventes en caisse, les paiements et les factures automatiques de la caisse ne sont jamais bloqués.

**À utiliser**
- **`GET /api/v1/subscription/me`** (`keevo/backend/src/main/java/com/keevo/subscription/plan/adapter/in/rest/dto/SubscriptionResponse.java`) gagne deux champs :
  - `quotas` : une liste de `{metric, metricLabel, period, periodLabel, limit, used, remaining, resetAt}`. `limit` et `remaining` valent `null` quand c'est illimité. `resetAt` est la date ISO de remise à zéro, `null` pour un quota à vie.
  - `credits` : `{metric: reste}`, pour les packs achetés.
- **Refus pour quota épuisé :**
  - HTTP 403, `domainCode` `QUOTA_EXCEEDED`, message « Quota de votre offre atteint ».
  - `details` = `{metric, metricLabel, period, periodLabel, limit, used, resetAt, planCode, planLabel}`.
  - En synchronisation : résultat `REJECTED`, raison `QUOTA_EXCEEDED`, mêmes détails dans `conflictData`.
- **Cas concerné aujourd'hui :** `POST /api/v1/invoices/{id}/issue` et l'opération de sync `ISSUE_INVOICE`. La facture reste en brouillon.

**À implémenter côté mobile**
1. Ajouter une section « Ma consommation » dans « Mon plan ».
   - Une ligne par quota, de la forme `{metricLabel} : {used} / {limit} {periodLabel}`. Exemple : « Documents : 12 / 300 par mois ». Écrire « illimité » si `limit` est nul.
   - Afficher le reste des packs, s'il y en a.
2. Sur `QUOTA_EXCEEDED`, afficher « Vous avez atteint votre quota de {metricLabel} {periodLabel} ({used}/{limit}). Il se renouvelle le {resetAt en date locale}. » Ne rien mettre sur le renouvellement si `resetAt` est nul. Proposer de voir les offres.
3. Hors ligne, une facture émise sur l'appareil puis refusée à la synchro pour quota doit redevenir un brouillon, avec ce message. Elle ne doit pas rester « émise ».
4. Ne jamais afficher les codes (`DOCUMENTS`, `AI_CREDITS`, `LIFETIME`), seulement les libellés.

**Implémentation de référence :** aucune.

**Critères d'acceptation mobile / scénarios de test**
- **Consommation affichée.** Étant donné un compte qui vient d'émettre une facture, quand l'app ouvre « Mon plan », alors « Documents » indique 1 utilisé ce mois.
- **Quota épuisé.** Étant donné un plan de test limité à 1 document, quand l'app émet une 2e facture, alors elle affiche le message de quota et la facture reste en brouillon.

**Clé de suivi mobile :** `m19-8-compteurs-usage-quotas`.

## Dashboard super-admin (autre dépôt)

**Statut :** API livrée (commit de la story 19.8).

**Ce qui est fait côté API :** les quotas de chaque plan sont des données, modifiables sans redéploiement.

**À utiliser**
- **`GET /api/v1/admin/plan-profiles`** et **`GET /api/v1/admin/plan-profiles/{plan}`** : chaque plan gagne `quotas`, une liste de `{metric, metricLabel, period, periodLabel, limit}`.
- **`PUT /api/v1/admin/plan-profiles/{plan}`** accepte `quotas`, une liste de `{metric, period, limit}`, en remplacement complet.
  - `quotas` absent : les quotas enregistrés sont gardés. Liste vide : plus aucun quota, donc tout est illimité.
  - Valeurs autorisées :
    - `metric` : `DOCUMENTS`, `SMS`, `EMAILS` ou `AI_CREDITS` ;
    - `period` : `MONTH`, `DAY` ou `LIFETIME` ;
    - `limit` : un entier ≥ 0.
  - Une erreur renvoie 422 `PLAN_PROFILE_INVALID`, avec la raison en français : doublon, valeur inconnue, limite invalide ou champ en trop.

**À implémenter côté dashboard**
1. L'écran d'un plan gagne une section « Quotas ».
   - Une ligne par quota : usage (liste de libellés), période (« par mois », « par jour », « à vie ») et limite.
   - On peut ajouter ou supprimer une ligne. « Illimité » veut dire pas de ligne.
2. Enregistrer en envoyant la liste complète dans `quotas`.

**Critères d'acceptation / scénarios de test :** étant donné le plan Pro, quand on règle les SMS à 30 par mois, alors `GET /admin/plan-profiles/PRO` renvoie SMS 30 par mois, et le prochain envoi compté l'applique.

**Clé de suivi dashboard :** `d19-8-compteurs-usage-quotas`.
