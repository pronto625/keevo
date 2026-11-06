---
title: '20-4 Invoice collections — deposit, partial payments, schedule, overdue job'
type: 'feature'
created: '2026-11-06'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-3-devis.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** A Keevo invoice is either unpaid (ISSUED) or fully paid (PAID, one `paid_at`). Bref merchants record a deposit, partial payments and a payment schedule, and need to know who owes what and who is late (FUS-FR20). Bref has no overpayment guard and no overdue job; Keevo adds both.

**Approach:** New tenant table `invoice_payments` (invoicing module V10) holding both scheduled instalments (`due_date`, not yet paid) and payments (`paid_at`). The server recomputes a stored `payment_state` on the invoice under the invoice row lock at every payment change, never lets recorded amounts exceed what is owed, and moves the legal status to PAID (with the legacy `paid_at` / `payment_method`) when the invoice is fully paid. A daily job marks OVERDUE invoices and notifies the owner. Legacy paid invoices are read as one payment without any data migration. Calculation cases are published in `keevo/contracts/`.

## Boundaries & Constraints

**Always:**
- **Amounts** are whole XAF (`long`), `amount > 0`. **Amount due** = `totalTtc − creditedTtc` (credit notes reduce what the client owes). **Amount paid** = sum of rows with `paid_at`. Invariant checked under the lock: the sum of ALL rows (paid + scheduled) ≤ amount due, else 409 `OVERPAYMENT` (new ErrorCode, French « Le montant dépasse ce qui reste dû sur la facture (reste dû : X FCFA) »), nothing written.
- **payment_state** (stored on `invoices`, nullable, recomputed under `FOR UPDATE` on every payment change and by the job), order: `PAID` if amount paid ≥ amount due; else `OVERDUE` if a row without `paid_at` has `due_date` < today (Africa/Douala); else `PARTIAL` if amount paid > 0; else `UNPAID`. French labels: Payée, En retard, Partiellement payée, Non payée. Only for `kind = INVOICE` with status ISSUED or PAID; DRAFT/CANCELLED/quotes → `paymentState: null`.
- **Legacy read** (no destructive migration, no data UPDATE in module scripts): an invoice with no `invoice_payments` row is presented as: PAID status → one payment `{amount: amountDue, paidAt: paid_at, method: payment_method, legacy: true}` and state PAID; ISSUED → no payment, state UNPAID (or OVERDUE never, since it has no due date). A stored NULL `payment_state` is derived that way at read.
- **Becoming PAID**: when the state reaches PAID on an ISSUED invoice, the legal status moves to PAID in the same transaction with `paid_at` = the latest payment's `paid_at` and `payment_method` = its method (V1 Flutter keeps reading `status`/`paidAt`/`paymentMethod`); the existing `InvoicePaidEvent` is published.
- **Operations** (all on `kind = INVOICE`; quotes refused with the 20.3 code; permission `INVOICE_SEND`, entitlement `invoicing`; device-generated `paymentId`, idempotent):
  - `POST /api/v1/invoices/{id}/payments` `{paymentId?, amount, paidAt?(date), dueDate?(date), method?(≤60), note?(≤500)}`: exactly one of `paidAt` / `dueDate` (a payment received, or a scheduled instalment). Scheduled instalments allowed on DRAFT and ISSUED; received payments only on ISSUED. `paidAt` follows today's rules (not in the future, not before the issue date → `INVOICE_PAYMENT_DATE_INVALID`). A deposit (acompte) is simply the first received payment.
  - `POST /api/v1/invoices/{id}/payments/{paymentId}/settle` `{paidAt?, method?}`: marks a scheduled instalment as paid (default today); already settled → 200 no-op.
  - Sync `ADD_INVOICE_PAYMENT {invoiceId, paymentId, amount, paidAt?, dueDate?, method?, note?}` and `SETTLE_INVOICE_PAYMENT {invoiceId, paymentId, paidAt?, method?}`: replay with the same content → APPLIED without a second row; same `paymentId` with different content → REJECTED (`VALIDATION_ERROR`).
  - Existing `POST /invoices/{id}/pay` and sync `PAY_INVOICE` (V1) keep their contract and now **settle the invoice**: every scheduled instalment becomes paid on that date/method and a remainder payment is added for the balance (Bref `settle`), state PAID, status PAID.
- **Cancel**: an ISSUED invoice with at least one received payment cannot be cancelled → 409 `INVOICE_INVALID_TRANSITION` (« Des paiements sont enregistrés sur cette facture : établissez un avoir »). Without received payments, cancel works as today (scheduled rows stay, state null afterwards).
- **Daily job** (`@Scheduled` cron Africa/Douala, `@SchedulerLock`, registered in `SchedulerLockCoverageTest`, gated like the other jobs): per live tenant with the invoicing tables, ONE set-based UPDATE moving ISSUED invoices to `payment_state = 'OVERDUE'` when an unpaid row is past due and the stored state differs (RETURNING numbers); a newly-overdue set produces ONE owner notification per tenant per run through an event consumed in the messaging side (`PushNotifier.notifyOwners`, type `INVOICE_OVERDUE`, title « Factures en retard », body « 3 facture(s) en retard de paiement : FAC-2026-000012, … », deep link `/invoices?paymentState=OVERDUE`, no UUIDs); per-tenant failure logged, others continue.
- **Reads**: `InvoiceResponseDto` (REST + pull) gains `paymentState`, `paymentStateLabel`, `amountPaid`, `amountDue`, `payments[]` (`id, amount, dueDate, paidAt, method, note, legacy`); list filter `paymentState=` (400 on unknown); payments batch-loaded for a page (no per-invoice query); every payment change bumps the invoice `updated_at` so the pull carries it.
- **Audit**: payment received / instalment scheduled / instalment settled, French labels with amounts and the invoice number, mirrored in `export-datasets.json` (and the Flutter test-client mirror, `pubspec` bump).
- **Contracts**: `keevo/contracts/invoice-payment-cases.json` — calculation cases (inputs: totalTtc, creditedTtc, rows, today; expected: state, amountPaid, amountDue, or error `OVERPAYMENT`), including deposit = total, several instalments, one overdue, credit note reducing the due, cancellation refused with payments; a backend test runs every case through the domain calculator. `invoice-sync-payloads.json` gains the two new operations.

**Never:**
- No per-payment delete/edit in this story; no change to POS automatic invoices beyond going through the same `pay` path (they stay instantly PAID); no change to numbering, credit-note rules or quotes.
- No public mirror (invoicing tables are tenant-only). No dashboard API. No deliverable Flutter feature.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Deposit | ISSUED 100 000, received 30 000 | PARTIAL, paid 30 000, due 100 000 | — |
| Deposit = total | received 100 000 | PAID, status PAID, `paid_at` set | — |
| Schedule | DRAFT, 2 instalments 50 000 each | rows stored; after issue state UNPAID | — |
| Overpayment | paid 80 000 + new 30 000 | nothing written | 409 `OVERPAYMENT` |
| Schedule exceeding due | rows sum > due | nothing written | 409 `OVERPAYMENT` |
| Overdue | instalment due yesterday, unpaid | job → OVERDUE + 1 owner notification for the tenant | — |
| Settle late instalment | OVERDUE, settle it, rest paid | PARTIAL or PAID per amounts | — |
| Credit note | total 100 000, credited 20 000, paid 80 000 | PAID | — |
| Legacy paid | PAID, no rows | one legacy payment, state PAID | — |
| V1 pay | ISSUED with partial 30 000 + instalment | instalment settled + remainder, PAID | — |
| Replay | same `paymentId` same content | one row, APPLIED/200 | different content → REJECTED/409 |
| Cancel with payment | ISSUED + received payment | refused | 409 `INVOICE_INVALID_TRANSITION` |
| Quote | payment on a quote | refused | 409 (20.3 code) |
| Payment date | future / before issue | refused | 400 `INVOICE_PAYMENT_DATE_INVALID` |

</intent-contract>

## Code Map

`K` = `keevo/backend/src/main/java/com/keevo`, `INV` = `K/invoicing/invoice`, `R` = `keevo/backend/src/main/resources/db/modules/invoicing`.
- `R/V1__invoicing.sql:59-64,71` -- totals BIGINT, `paid_at`, `payment_method VARCHAR(60)`, CHECK `status <> 'PAID' OR paid_at IS NOT NULL`. Latest script V9; new `R/V10__invoice_payments.sql` (table, FK to invoices, CHECKs: amount > 0, exactly one of paid_at/due_date set at insert is enforced in Java — the row later has both after settle; indexes `(invoice_id)` and partial `(due_date) WHERE paid_at IS NULL`; `invoices.payment_state VARCHAR(10)` + CHECK + partial index for the job/filter). Idempotent, `SET LOCAL lock_timeout`, no data UPDATE.
- Domain: `INV/domain/model/Invoice.java` (`pay` :287/:298-322 with WAT date rules and noon/issuedAt clamp :324-327, `assertInvoice`, `cancel` :338-353), `InvoiceStatus.java:4-14`, `InvoiceTotals.java` (long XAF), `CreditNote.java:85,166-172` (credited amounts). New pure calculator for payment state (unit-tested, used by the contract cases).
- Use cases: `INV/application/usecase/InvoiceLifecycleUseCase.java:123-138` (`pay`: lock `findByIdForUpdate` → `markPaid` → `InvoicePaidEvent`), cancel :143-144 (`hasCreditNotes`), `InvoiceQueryUseCase.java:33-48` (`Credits` batch), `InvoiceSaleUseCase.java:124-145` (POS paid invoices through `pay`).
- Persistence: `INV/adapter/out/persistence/JdbcInvoiceRepository.java` (`findByIdForUpdate` :82, `markPaid` :193, list SQL :256-260, `insertIfAbsent` pattern ~:128-136).
- REST: `INV/adapter/in/rest/InvoiceController.java` (pay :220-234 `INVOICE_SEND`, cancel :237-241 `INVOICE_MANAGE`, list filters :126-135, status parse :310-319), `dto/PayInvoiceRequestDto.java`, `dto/InvoiceResponseDto.java:35-71,97-117`.
- Sync: `INV/adapter/in/sync/InvoiceSyncHandler.java` (ops :40-73, validate :110, PAY apply :147, DTO validation via `read(p, Dto.class)`), `INV/adapter/in/sync/InvoiceDeltaProvider.java:69-98` (batch loads), `sync/sync/application/strategy/ConflictStrategyRegistry.java` (invoice ops AppendOnly — keep), replay DUPLICATE in `SyncPushService.java:461,491`; same-id-different-content precedent `StockAdjustSyncHandler.java:77-87`.
- Errors: `K/shared/domain/exception/ErrorCode.java:186-196` (add `OVERPAYMENT` 409), `ErrorMessages.java:131-140`, `GlobalExceptionHandler.java:358`.
- Job + notification: template `K/messaging/notification/application/service/NotificationRetentionJob.java:78-125` (`liveTenants()`, per-tenant `TransactionTemplate`, `Report`), `ModuleExpiryMaterializer` + `ModuleExpiryNotificationListener.java:48-50,64,129` (event → AFTER_COMMIT listener → `notifyOwners`), `K/shared/capability/notification/PushNotifier.java`, `NotificationPayload.java:16-32`, style `StockAlertNotificationListener.java:204-215`; flag `K/shared/infrastructure/config/SchedulingConfig.java:19`; test `keevo/backend/src/test/java/com/keevo/shared/infrastructure/config/SchedulerLockCoverageTest.java`.
- Audit: `K/shared/infrastructure/web/AuditEventListener.java`, `K/shared/infrastructure/export/AuditLabels.java`, events `K/shared/event/catalog/invoicing/`, `keevo/contracts/export-datasets.json`.
- Contracts: `keevo/contracts/invoice-sync-payloads.json` (PAY entries :70-84), read by `InvoiceSyncHandlerTest.java:250-253` (`Path.of("..","contracts",...)`); Flutter `app/packages/keevo_invoicing/test/invoice_repository_test.dart`.
- Bref reference (read-only): `api-bref/src/invoice/infrastructure/persistence/relational/entities/invoice-payment.entity.ts:13-47`, `repositories/invoice.repository.ts:389-538` (lock, settle, addPayment, recalculateStatus), `invoice.service.ts:106-124,675-694,715-775`.

## Tasks & Acceptance

**Execution:**
- `R/V10__invoice_payments.sql` -- table, `payment_state` column, indexes, CHECKs.
- Domain calculator + `Invoice` rules (cancel with payments, settle-all on legacy pay) -- pure, unit-tested.
- Repository: payments insert-if-absent, settle, batch load by invoice ids, state update, job UPDATE … RETURNING.
- Use cases: add payment, settle instalment, `pay` rewired to settle-all, cancel guard; each under the invoice lock, bumping `updated_at`, recomputing the state, moving to PAID when reached.
- REST + DTOs + list filter; sync handler ops + contract entries; delta provider fields.
- `InvoiceOverdueJob` (invoicing) + event + owner-notification listener (messaging side), `SchedulerLockCoverageTest` entry.
- Audit events/labels + export contract + Flutter test-client mirror + `pubspec` bump; `docs/entitlements/endpoints.md` regenerated.
- `keevo/contracts/invoice-payment-cases.json` + backend test running every case.
- Tests: unit (calculator, rules), use-case per matrix row, full-context REST + sync (replay, conflicting replay, V1 pay settling, legacy read), Postgres (V10 on an existing schema with PAID/ISSUED/CANCELLED invoices — nothing altered; job marks overdue once and notifies once; concurrent payments on one invoice never exceed the due).
- Spec section `## Mobile (React Native — dépôt AI/bref)` and sprint key `m20-4-encaissements-echeancier-retard` (finalize).

**Acceptance Criteria:**
- Given the invoicing module on a tenant, when the matrix scenarios run over REST and sync, then every row holds and responses are supersets of today's.
- Given existing invoices, when the new build runs, then V10 applies without altering them and legacy PAID invoices read as one payment.
- Given the full backend suite, when run package by package, then 0 failures.
- Given a live local backend (WhatsApp noop, FCM/Expo off), when a deposit, an instalment due in the past, the overdue job and a V1 pay run on a test invoice, then PARTIAL → OVERDUE (one owner notification in the inbox) → PAID with `paid_at` are observed.

## Design Notes

Pattern: **aggregate + pure policy**. `PaymentStateCalculator` (pure function of due, rows, today) is the single source of the state, used by the use cases, the job's Java side (if any) and the contract cases; the database stores its result for filtering and the job. All mutations go through one application service holding the invoice row lock (`FOR UPDATE`) so concurrent devices serialise; idempotency rests on the device `paymentId` (`ON CONFLICT DO NOTHING` + content comparison).

Cost: a payment change = 1 locked read + payments of that invoice (small) + 1 insert/update + 1 invoice update; list/pull = +1 batched payments query per page; job = 1 UPDATE … RETURNING per tenant using the partial `due_date` index, + 1 notification per tenant with newly overdue invoices.

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='com/keevo/invoicing/**/*Test*'` -- expected: pass
- `cd keevo/backend && mvn -o -q test -Dtest='com/keevo/messaging/**/*Test*,com/keevo/shared/infrastructure/config/*Test*,com/keevo/sync/**/*Test*'` -- expected: pass
- Full suite package by package (`run-pkgs-wait.sh`) -- expected: 0 failures
- Live: local backend on keevo_dev (WhatsApp noop, FCM/Expo off), invoicing activated on the test tenant for the check then deactivated -- expected: as in the AC.
