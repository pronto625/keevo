---
title: '9-2 — Paid plan activation, payment history, expiry reminders and tenant suspension (super admin)'
type: 'feature'
created: '2026-10-13'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
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

## Review Triage Log

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.admin.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.subscription.**,com.keevo.messaging.**,com.keevo.architecture.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='*IntegrationTest' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures (Docker required)
- `cd keevo/dashboard && npm run lint && npx tsc --noEmit && npm test && npm run build` -- expected: all succeed
