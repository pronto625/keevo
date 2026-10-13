---
title: '9-2 — Paid plan activation, payment history, expiry reminders and tenant suspension (super admin)'
type: 'feature'
created: '2026-10-13'
status: 'done'
baseline_revision: 'a9b47f5a84ba02c4446aea9dc7a0243247cc8185'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-9-context.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Toor can only activate a plan through a raw API call (`POST /api/v1/admin/subscriptions/{schemaName}/activate`, story 1.6) — no dashboard UI, no record of the off-platform payment (FR78 « historique des paiements »), no reminder before a paid plan expires, and no way to suspend or reactivate a tenant (epic 9 story 9.2, FR78, FR81 « abonnement expirant dans <7 jours »).

**Approach:** Add a super-admin plan-management flow on the tenant detail drawer: activate Premium with an expiry date and a recorded payment, downgrade to Free, suspend (reason required) / reactivate, payment history; a daily job sends WhatsApp reminders 7 and 1 days before a Premium expiry and lists expiring/expired plans for Toor; activation and downgrade notify the owner on WhatsApp. Each recorded payment (amount, method, covered period) is what 9-3 will use for MRR.

## Boundaries & Constraints

**Always:**
- Plan values are the existing `PlanType` (`FREE`, `PREMIUM_TRIAL`, `PREMIUM`); the epic's "PAID" means `PREMIUM`. Labels in the dashboard: « Gratuit », « Essai Premium », « Premium ».
- New Flyway migration `public.tenant_payments` (id uuid pk, tenant_id uuid not null → tenants, amount_xaf integer > 0, method varchar check in (`MOBILE_MONEY`,`CASH`,`BANK_TRANSFER`,`OTHER`), paid_at timestamptz not null, period_start date not null, period_end date not null (`>= period_start`), note varchar(500) null, recorded_by uuid not null, created_at timestamptz default now()), index on (tenant_id, paid_at desc). Payments are append-only (no update/delete endpoint).
- Same migration: `public.tenant_plan_events` (id uuid pk, tenant_id uuid not null, from_plan varchar(20), to_plan varchar(20) not null, cause varchar(20) check in (`ADMIN_ACTIVATION`,`ADMIN_DOWNGRADE`,`EXPIRY`,`REGISTRATION`), occurred_at timestamptz not null default now(), actor_id uuid null, reason varchar(500) null), index (tenant_id, occurred_at desc) and (occurred_at). A row is written by every plan change in this story and at tenant registration (provisioning) (premium activation incl. legacy endpoint, manual downgrade, expiry downgrade per tenant) and backfilled once by the migration with a `REGISTRATION` row per existing tenant (`to_plan` = its current plan read is impossible cross-schema in SQL → backfill `to_plan='PREMIUM_TRIAL'` at `tenants.created_at`). 9-3 computes churn and conversions from it.
- Endpoints under `/api/v1/admin/tenants/{tenantId}` (tenant UUID, as the dashboard uses), each with `CurrentActor.requireSuperAdmin()`:
  - `POST /plan/premium` body `{ expiresAt (date, > today WAT), amountXaf, method, paidAt (default now), note }` → in one transaction: insert payment (`period_start` = today WAT or the current Premium expiry if later — renewal extends, `period_end` = expiresAt), set subscription `PREMIUM`/`ACTIVE`/expires_at = end of `expiresAt` day WAT with Premium limits (reuse `ActivatePlanService` logic, keyed by schema resolved from the UUID); then WhatsApp to owner: « 🎉 Votre compte Keevo Premium est activé jusqu'au <dd/MM/yyyy>. Profitez de toutes les fonctionnalités ! ».
  - `POST /plan/free` body `{ reason }` → subscription `FREE` with FREE limits, `expires_at` null; WhatsApp « Votre abonnement Keevo est repassé au plan Gratuit. Vos données sont conservées. ».
  - `POST /suspend` body `{ reason (required, 3–500 chars) }` → `tenants.status = SUSPENDED` (only from `ACTIVE`; `DELETION_PENDING`/`DELETED` → 409 `INVALID_STATE`), then `revokeAllSessions` for every membership of the tenant so the next refresh carries the new status (existing read-only enforcement in `JwtAuthFilter`, Flutter `SuspensionBanner`).
  - `POST /reactivate` → `SUSPENDED` → `ACTIVE`, sessions revoked the same way.
  - `GET /payments` → list newest first `{ paidAt, amountXaf, method, periodStart, periodEnd, note, recordedByLabel }` (recordedBy shown as « Super admin », never an id).
- `public.tenants.plan_type` (read by the admin tenant list, its PAID/FREE filter and later 9-3/9-4/9-5 counts) is today never updated after registration: every plan change (both activation endpoints, manual downgrade, nightly expiry downgrade) also updates `public.tenants.plan_type` in the same unit of work, and the migration's companion one-shot startup job (idempotent, ShedLock) realigns `public.tenants.plan_type` from each schema's `subscriptions.plan_type` once.
- Every admin action writes an admin audit trace (existing pattern used by admin force-delete/cancel-deletion if present; else a `log.info` + Spring application event `PlanActivatedEvent` / `PlanDowngradedEvent` / `TenantSuspendedEvent` / `TenantReactivatedEvent` with actorId, tenantId, timestamps, reason).
- Daily job (ShedLock, after the existing 02:00 downgrade, e.g. 08:00 WAT): for `PREMIUM` subscriptions expiring in exactly 7 or 1 calendar days (WAT) send « ⏰ Votre abonnement Keevo Premium expire le <date>. Contactez-nous pour renouveler. » once per (tenant, threshold, expiry date) — idempotent via a `public.plan_expiry_reminders` table or a unique key. The existing downgrade job publishes `PlanExpiredEvent` per downgraded tenant and the owner gets « Votre abonnement Keevo Premium a expiré : votre compte est passé au plan Gratuit. Vos données sont conservées. ».
- `GET /api/v1/admin/subscriptions/alerts` → tenants whose Premium/Trial expires within 7 days (`daysLeft`) and those downgraded in the last 7 days, with tenant name and plan label data; dashboard shows them as a banner list on the Tenants page: « ⚠️ <Nom> — plan expire dans N jours ».
- WhatsApp sends go through `WhatsAppPort` + `OwnerRecipientResolver` after commit; a send failure is logged and never fails the admin action.
- Dashboard: tenant drawer gets a « Plan & paiements » section: current plan + expiry, buttons « Passer en Premium » (modal: date picker, montant XAF, moyen de paiement select with French labels, note), « Repasser en Gratuit » (confirm + reason), « Suspendre » (confirm + reason required) / « Réactiver », payment history table. French labels only, no ids; success/error toasts; queries invalidated after each action. Tenants list plan/status columns use labels.
- The legacy `POST /api/v1/admin/subscriptions/{schemaName}/activate` stays working (no breaking change).

**Never:** No online payment integration; no change to free-plan limits; no deletion/edit of payments; no Flutter full-screen block (PRD: suspension = read-only, already implemented by v1s-14-9).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Activate from FREE | expiresAt +30 d, 10 000 XAF, MOBILE_MONEY | payment row (period today→expiresAt), plan PREMIUM ACTIVE, owner WhatsApp | — |
| Renewal before expiry | PREMIUM until D+5, new expiresAt D+35 | period_start = D+5, plan expiry D+35 | — |
| Invalid input | expiresAt ≤ today, amount ≤ 0, unknown method | 422 `VALIDATION_ERROR`, nothing written | dashboard shows field error |
| Unknown tenant | random UUID | 404 `TENANT_NOT_FOUND` | — |
| Suspend ACTIVE | reason "fraude" | status SUSPENDED, all member sessions revoked | — |
| Suspend DELETION_PENDING | — | 409 `INVALID_STATE` | toast |
| Suspend without reason | blank | 422 `VALIDATION_ERROR` | — |
| Reminder 7 d | PREMIUM expiring in 7 WAT days | one WhatsApp; second run same day sends nothing | owner phone missing → logged, skipped |
| Expiry | PREMIUM expired at 02:00 run | downgraded FREE + `tenant_plan_events` row cause EXPIRY + owner WhatsApp + appears in alerts as expired | — |
| Stale public plan | public.tenants.plan_type = PREMIUM_TRIAL, schema says FREE | realigned to FREE by the one-shot job; list filter « Gratuit » shows it | — |
| Registration | new tenant registers | `tenant_plan_events` row REGISTRATION → PREMIUM_TRIAL | — |
| WhatsApp down | `sendReport` throws | action succeeds, warning logged | — |
| Non-admin | OWNER JWT | 403 `FORBIDDEN` | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/admin/tenant/adapter/in/rest/AdminSubscriptionController.java` -- legacy activate (schemaName); keep.
- `keevo/backend/src/main/java/com/keevo/subscription/plan/application/service/ActivatePlanService.java` -- REQUIRES_NEW + TenantContext switch pattern to update the tenant-schema `subscriptions` row; reuse for premium/free.
- `.../subscription/plan/application/service/SubscriptionExpiryService.java` + `shared/infrastructure/scheduling/SubscriptionExpiryScheduler.java` -- daily downgrade (02:00, ShedLock); has `TODO emit SubscriptionDowngradedEvent` → publish `PlanExpiredEvent` per downgraded schema (`UPDATE … RETURNING` or select-before-update).
- `.../subscription/plan/domain/model/{Subscription,PlanType}.java` -- plan limits.
- `.../identity/auth/domain/model/TenantStatus.java`, `TenantRepository` -- tenant status change.
- `.../identity/auth/domain/port/out/TokenRevocationPort.java` -- `revokeAllSessions(userId, schema)`; members from `public.user_tenant_memberships`.
- `.../shared/infrastructure/security/JwtAuthFilter.java:177-196` -- SUSPENDED read-only enforcement (unchanged).
- `.../identity/auth/domain/port/out/OwnerRecipientResolver.java`, `.../messaging/whatsapp/domain/port/out/WhatsAppPort.java` -- owner phone + send; see `DayClosureWhatsAppListener` (v1s-14-16) for the after-commit listener pattern.
- `.../admin/tenant/application/service/AdminTenantService.java`, `adapter/in/rest/AdminTenantController.java` -- force-delete/cancel-deletion actions pattern (UUID path, guards, errors).
- `keevo/backend/src/main/resources/db/migration/` -- next is `V17__…`.
- `keevo/dashboard/src/components/tenants/TenantDetailDrawer.tsx`, `TenantTable.tsx`, `hooks/useTenants.ts`, `lib/api/admin-tenants.ts`, `types/tenant.ts`, `app/tenants/page.tsx` -- add section, mutations, alerts banner.
- `keevo/dashboard/src/lib/labels.ts` -- add `paymentMethodLabel`, `tenantStatusLabel` (created by 9-8).

## Tasks & Acceptance

**Execution:**
- `db/migration/V17__tenant_payments_plan_reminders.sql` -- tables above (payments, plan events, reminders).
- `admin/tenant/**` (or a new `admin/subscription` sub-package mirroring hexagonal layout) -- use cases `ActivatePremiumUseCase`, `DowngradeToFreeUseCase`, `SuspendTenantUseCase`, `ReactivateTenantUseCase`, `ListTenantPaymentsUseCase`, `ListPlanAlertsUseCase`; controller endpoints; DTOs; persistence adapter (JdbcTemplate on `public.tenant_payments`).
- `subscription/plan/**` -- expiry event publication; `PlanExpiryReminderService` + scheduler (ShedLock).
- `messaging/whatsapp/**` or `admin/**` listener -- after-commit WhatsApp messages for activation, downgrade, expiry, reminders.
- Tests: service tests per matrix row (renewal period, validations, state guards, revocation of every member, idempotent reminders, WhatsApp failure isolated), controller tests (403, 422, 404, 409, `jsonPath` contract), migration covered by an existing Testcontainers integration test pattern (`PostgresIntegrationTest`) for insert + check constraints.
- Dashboard: `types/tenant.ts`, `lib/api/admin-tenants.ts`, `hooks/useTenants.ts` (mutations), `components/tenants/{PlanSection,ActivatePremiumDialog,SuspendDialog,PaymentHistory,PlanAlertsBanner}.tsx`, `TenantDetailDrawer.tsx`, `app/tenants/page.tsx`; component tests (form validation, labels, no ids, mutation calls).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` handled by the orchestrator (not the implementer).

**Acceptance Criteria:**
- Given a FREE tenant in the dashboard, when Toor activates Premium with a payment, then the drawer shows plan « Premium » until the chosen date and the payment in the history, and the owner app shows Premium after its next subscription refresh.
- Given a suspended tenant, when its owner uses the app after the next token refresh, then writes are refused (`ACCOUNT_SUSPENDED`) and the suspension banner shows; after « Réactiver » writes work again.

## Spec Change Log

### 2026-10-13 — Orchestrator decision during review (no loopback)
- Trigger: review finding « trials get no expiry reminder » (Boundaries named PREMIUM only).
- Decision (user's standing instruction: decide from the docs — FR81 « abonnement expirant dans <7 jours »): PREMIUM_TRIAL subscriptions get the reminder too, with a trial-specific text; applied as a patch.

## Review Triage Log

### 2026-10-13 — Review pass
- verdicts: 57 findings — high 0, medium 9, low 31, false 7, maybe-false 0 (+ intent rows descriptive)
- findings:
  - `[medium]` `[patch]` (blind) renewal overlaps one day and accepts a zero-extension payment — periodStart = currentEnd + 1, `expiresAt <= currentEnd` → 422.
  - `[medium]` `[patch]` (blind) reminder claim consumed when nothing was sent — claim deleted when no send, retried next run.
  - `[medium]` `[patch]` (blind) missed daily run skips a threshold — range claims (1 < d ≤ 7 → 7, 0 ≤ d ≤ 1 → 1).
  - `[medium]` `[patch]` (blind) trials get no reminder — orchestrator decision from FR81 (« abonnement expirant dans <7 jours »): PREMIUM_TRIAL included with a trial-specific text.
  - `[low]` `[patch]` (blind) alerts query per schema on every load — FREE tenants skipped via synced `public.tenants.plan_type`; staleTime 60 s.
  - `[medium]` `[patch]` (blind) stale EXPIRED alerts after renewal, inconsistent tenant filters — NOT EXISTS later event; DELETED and DELETION_PENDING excluded in both branches.
  - `[false]` `[reject]` (blind) different queries read different subscription rows — one subscription row per tenant schema (`Subscription` javadoc, provisioning seeds one).
  - `[low]` `[reject]` (blind) no lasting suspension record — spec fallback is log + Spring events; reason kept in the `[ADMIN AUDIT]` log.
  - `[low]` `[patch]` (blind) plan changes on DELETION_PENDING — 409 INVALID_STATE; buttons disabled.
  - `[low]` `[patch]` (blind) renewal dialog lacks current expiry and payment date — min date from current expiry, optional « Date du paiement ».
  - `[low]` `[patch]` (blind) alerts banner hides failures / plan type — error state + retry, plan label.
  - `[low]` `[patch]` (blind) very large amount misleading message — « Montant trop élevé », `@Max`.
  - `[low]` `[patch]` (blind) weak migration constraints — FK + CHECKs, explicit ON DELETE RESTRICT on payments (V17 not deployed).
  - `[low]` `[patch]` (blind) legacy endpoint pollutes history — no event/public update when the plan is unchanged.
  - `[low]` `[patch]` (blind) loadPlan swallows errors, unquoted schema, WAT duplicated — WARN with cause, quoted schema; WAT duplication rejected (two constants, same value).
  - `[medium]` `[patch]` (edge) zero-extension renewal — same as blind row 1.
  - `[low]` `[patch]` (edge) overlapping day — same as blind row 1.
  - `[low]` `[patch]` (edge) DELETION_PENDING plan changes — same as blind row 9.
  - `[low]` `[patch]` (edge) amount above int — same as blind row 12.
  - `[low]` `[patch]` (edge) paidAt far in the past — older than 366 days → 422.
  - `[medium]` `[patch]` (edge) stale EXPIRED alert — same as blind row 6.
  - `[low]` `[patch]` (edge) filter mismatch — same as blind row 6.
  - `[low]` `[patch]` (edge) NOW() vs Clock — Clock instant bound.
  - `[low]` `[reject]` (edge) revocation before commit may re-cache old cutoff — bounded by the 5-min cache TTL, which is the NFR12 contract; same pattern as `DeactivateEmployeeService`.
  - `[false]` `[reject]` (edge) revoke throws mid-loop → rollback — atomic rollback is the intended behaviour (nothing half-suspended).
  - `[medium]` `[patch]` (edge) missed threshold — same as blind row 3.
  - `[medium]` `[patch]` (edge) claim blocks retry — same as blind row 2.
  - `[low]` `[patch]` (edge) CLAIM_SQL exception aborts run — per-tenant guard with the 9-7 skip/rethrow rule.
  - `[low]` `[reject]` (edge) unknown plan_type → 500 — values written only by the app through `PlanType`; CHECK added on event columns.
  - `[low]` `[patch]` (edge) orphan schema when recordEvent fails at registration — moved inside the cleanup try.
  - `[low]` `[reject]` (edge) realign race with a concurrent admin change — startup-only one-shot job, sub-second window, next change realigns.
  - `[low]` `[patch]` (edge) legacy spurious events — same as blind row 14.
  - `[low]` `[patch]` (edge) alerts error looks like no alerts — same as blind row 11.
  - `[low]` `[patch]` (edge) English « Validation failed » — French fallback.
  - `[low]` `[patch]` (edge) stale server field error — cleared on edit.
  - `[low]` `[patch]` (edge) Premium button with unreadable subscription — disabled.
  - `[low]` `[patch]` (edge) two stacked drawers — selected tenant lifted to page state.
  - `[medium]` `[patch]` (gap) alerts SQL never run on PostgreSQL — integration test.
  - `[medium]` `[patch]` (gap) raw plan code in list unpinned, stale PAID fixtures — integration assertion + fixtures.
  - `[medium]` `[patch]` (gap) detail `subscription` untested — integration assertion.
  - `[low]` `[patch]` (gap) V17 backfill never run on existing tenants — test re-runs the backfill statement on seeded tenants.
  - `[low]` `[reject]` (gap other) parsePaidAt untested — now reached by the dashboard field; covered by the dialog → API path tests added.
  - `[false]` `[reject]` (intent) R1 vs R2 surfaces — end-to-end WhatsApp/JWT effects rely on existing tested paths (`JwtAuthFilter` SUSPENDED, `RefreshTokenService` reads live tenant status, `DayClosureWhatsAppListener` after-commit pattern).
  - `[false]` `[reject]` (intent) R3a PAID filter groups trial — kept for compatibility, labelled « Premium et essai »; precise plan filters come with 9-1 completion.
  - `[false]` `[reject]` (intent) R3b manual downgrades absent from alerts — matrix row ties « expired » alerts to expiry.
  - `[false]` `[reject]` (intent) R3c legacy endpoint without WhatsApp — Boundaries keep it as-is except history/public plan.
  - `[false]` `[reject]` (intent) R3f short renewal rejected — a renewal ending before the current expiry would shorten a paid period; consistent with the renewal rule.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.admin.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.subscription.**,com.keevo.messaging.**,com.keevo.architecture.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='*IntegrationTest' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures (Docker required)
- `cd keevo/dashboard && npm run lint && npx tsc --noEmit && npm test && npm run build` -- expected: all succeed

## Auto Run Result

Status: done

**Summary:** Story 9-2 delivered. Toor manages plans from the tenant drawer: activate/renew Premium with a recorded payment (amount, method, payment date, covered period), downgrade to Free with a reason, suspend (reason required) / reactivate with session revocation, payment history, plan alerts banner (expiring within 7 days / expired in the last 7 days). Every plan change keeps `public.tenants.plan_type` in sync and writes `public.tenant_plan_events` (also at registration and on nightly expiry); owners get WhatsApp messages after commit (activation, downgrade, expiry, reminders at 7 and 1 days for Premium and trial, retried when not delivered). V17 adds `tenant_payments`, `tenant_plan_events` (backfilled), `plan_expiry_reminders`; a startup job realigns stale public plans.

**Files changed:** backend `admin/subscription/**` (new), `subscription/plan/**` (events, recorder, ChangeTenantPlanService, WhatsApp listener, reminders), `ActivatePlanService`, `SubscriptionExpiryService`, `TenantFactory`, `PlanTypeRealignJob`, `PlanExpiryReminderScheduler`, `AdminTenantService` (+ detail subscription), `ErrorCode.INVALID_STATE` → 409, `TenantSchemaSyncService` global tables, `V17__tenant_payments_plan_reminders.sql`; tests (unit, controller, Postgres IT, `SchedulerLockCoverageTest`); dashboard `types/tenant.ts`, `lib/api/admin-tenants.ts`, `lib/labels.ts`, `hooks/useTenants.ts`, `components/tenants/{PlanSection,ActivatePremiumDialog,SuspendDialog,PaymentHistory,PlanAlertsBanner,planDates,TenantDetailDrawer,TenantTable,…}`, `app/tenants/page.tsx` + tests.

**Review:** 57 findings — patched 9 medium entries and 31 low (grouped), deferred 0; rejected 17 (false 7, low with reasons). One orchestrator decision logged (trial reminders, FR81).

**Follow-up review recommended:** true — 9 medium entries patched; unverified risk: the reminder claim/delete-on-failure path and the deferred FK at registration are covered by unit tests and one IT each, not by a full registration → expiry → reminder run.

**Verification:** dashboard lint, tsc, `npm test` (18 files, 97 tests), build — green. Backend one package at a time: admin 156, subscription 60, messaging 126, identity 373, shared 508, architecture 6 — 0 failures, 0 errors (Postgres ITs included).

**Residual risks:** suspension takes effect at the next token refresh (≤ 5 min cache, NFR12); the legacy `/subscriptions/{schema}/activate` stays without payment/WhatsApp by design.

