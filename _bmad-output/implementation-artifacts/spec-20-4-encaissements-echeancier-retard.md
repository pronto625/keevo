---
title: '20-4 Invoice collections — deposit, partial payments, schedule, overdue job'
type: 'feature'
created: '2026-11-06'
status: 'done'
baseline_revision: 'e5a48f85210c3869e821017e2859e30dff14d9f0'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-3-devis.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The invoice PDF and public link show neither deposits, payments, balance due nor the schedule.
    evidence: |-
      InvoiceDocumentUseCase and the templates are untouched by 20.4. Routed to 20-7 (rendering templates, public links).
    severity: medium
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
- Spec section `## Auto Run Result

Status: done

**Change:** `invoice_payments` (invoicing V10: received payments and scheduled instalments) + stored `invoices.payment_state` recomputed under the invoice row lock; amount due = total TTC − credited; invariant « rows ≤ amount due » (409 `OVERPAYMENT`); legal status moves to PAID with `paid_at`/`payment_method` when fully paid; OVERDUE set only by the daily job (07:00 WAT, ShedLock, ENABLED tenants, superseded and stored-PAID excluded) with ONE owner notification per tenant per run; credit notes refused when more was received than the new due, otherwise unpaid instalments trimmed latest first; draft edits below scheduled instalments refused; past due dates refused; V1 `pay`/`PAY_INVOICE` settle everything; cancel refused once a payment is received; legacy paid invoices read as one payment; REST `POST /invoices/{id}/payments`, `…/payments/{paymentId}/settle`, `?paymentState=`; sync `ADD_INVOICE_PAYMENT` / `SETTLE_INVOICE_PAYMENT`; calculation cases `keevo/contracts/invoice-payment-cases.json` run through the server's own code path.

**Files (main):** `db/modules/invoicing/V10__invoice_payments.sql`; domain `PaymentState`, `InvoicePayment`, `PaymentStateCalculator`, `Invoice` rules; `InvoicePaymentRepository` + JDBC; `InvoicePaymentUseCase`, `InvoiceLifecycleUseCase`, `IssueCreditNoteUseCase`, `ManageDraftInvoiceUseCase`, `InvoiceQueryUseCase` (batched credit lines and payments); `InvoiceController` + DTOs, `InvoiceSyncHandler`; `InvoiceOverdueJob` + `InvoicesOverdueEvent` + messaging `InvoiceOverdueNotificationListener`; `ErrorCode.OVERPAYMENT`; `shared/domain/XafAmounts`; audit labels; contracts (`invoice-payment-cases.json`, `invoice-sync-payloads.json` `paymentOperations`, `export-datasets.json`); `docs/entitlements/endpoints.md`; Flutter test client audit mirror 2.0.27+99.

**Review:** 25 triage rows (42 reviewer findings): 19 patched (high: credit notes vs payments; medium: payment-state index, batched credit lines, contract cases on the served path, draft edit below instalments, past due dates, superseded/READ_ONLY in the job, fully credited invoices, missing tests; low: French labels, audit fallback, one XAF formatter, DTO clock, ordering, dead `Invoice.pay`, mobile note on `updatedAt`), 1 deferred (PDF/public link show neither deposits nor balance → 20-7), 5 rejected with reasons in the triage log. Accepted deviations: OVERDUE only by the job (one notification per invoice); credit-note refusal only on ISSUED invoices (PAID and POS-corrected invoices keep today's credit-note behaviour).

**Follow-up review recommended:** true — a high entry was patched (credit notes vs recorded payments: refusal + instalment trimming are new rules touching the credit-note flow).

**Verification:**
- Full backend suite package by package: 6,178 tests; 1 failure (`RegistryEventSerializationTest` lacked a round-trip fixture for the new after-commit `InvoicesOverdueEvent`), fixture added and test re-run green.
- Live on keevo_dev (WhatsApp noop, FCM/Expo off, overdue cron every 30 s via environment), test tenant with invoicing activated for the check then deactivated: issue `FAC-2026-000003` 100 000 → UNPAID; deposit 30 000 → PARTIAL; instalment 40 000 tomorrow → PARTIAL; payment 90 000 → 409 `OVERPAYMENT` « reste dû : 30 000 FCFA »; instalment backdated in the local DB → job « 1 invoice newly overdue, 1 owner notification », state « En retard », inbox « Factures en retard — 1 facture(s) en retard de paiement : FAC-2026-000003 », next run no new notification; V1 `pay` → status PAID, paid 100 000, `paidAt` set, 3 payment rows.

**Residual risks:** V10 applies lazily per tenant on first invoicing call (tested on V1–V9 data, not on a prod copy); the routed PDF item.

## Mobile (React Native — dépôt AI/bref)` and sprint key `m20-4-encaissements-echeancier-retard` (finalize).

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

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée sur `refonte/modulaire` / `develop` (commit à reporter ici après l'intégration). Conventions générales : spec-16-11 ; protocole de synchro : spec-16-10.

**Ce qui est fait côté API :** une facture émise peut recevoir un acompte, des paiements partiels et un échéancier. Le serveur calcule l'état de paiement (Non payée, Partiellement payée, Payée, En retard) sous verrou, refuse tout montant qui dépasserait ce qui reste dû, et passe la facture au statut « Payée » dès qu'elle est entièrement réglée. Chaque matin (7 h, heure de Douala), un traitement passe « En retard » les factures dont une échéance est dépassée et prévient le propriétaire par une notification. Les factures payées avant cette version apparaissent avec un seul paiement. Un paiement ne fait jamais passer une facture « En retard » : seul le traitement du matin le fait (et prévient le propriétaire).

**À utiliser :**
1. Champs ajoutés à chaque facture (REST, pull `invoices`) : `paymentState` (`UNPAID` | `PARTIAL` | `PAID` | `OVERDUE`, `null` pour un brouillon, une facture annulée ou un devis), `paymentStateLabel` (libellé français), `amountPaid` (somme reçue), `amountDue` (total TTC moins les avoirs), `payments` = liste `{id, amount, dueDate, paidAt, method, note, legacy}` (une échéance non réglée a `dueDate` et `paidAt: null` ; `legacy: true` = paiement unique d'une ancienne facture, sans `id`).
2. Enregistrer un paiement reçu (l'acompte est simplement le premier) : `POST /api/v1/invoices/{id}/payments` `{"paymentId": "<uuid choisi par l'appareil>", "amount": 30000, "paidAt": "2026-11-06", "method": "Mobile Money", "note": "Acompte"}` — facture émise seulement ; `paidAt` ni dans le futur ni avant la date d'émission.
3. Programmer une échéance : même appel avec `dueDate` à la place de `paidAt` (brouillon ou facture émise), date du jour ou future (passée → 422). Exactement une des deux dates. Un avoir réduit ou supprime d'abord les échéances non réglées (la plus lointaine en premier) ; il est refusé (409) si le montant déjà encaissé dépasse le reste dû après l'avoir. Modifier un brouillon sous le total de ses échéances → 409 `OVERPAYMENT`.
4. Régler une échéance : `POST /api/v1/invoices/{id}/payments/{paymentId}/settle` `{"paidAt"?: "2026-11-06", "method"?: "Espèces"}` (aujourd'hui par défaut) ; déjà réglée → 200 sans changement.
5. Solder la facture d'un coup : `POST /api/v1/invoices/{id}/pay` (inchangé) règle toutes les échéances et ajoute un paiement du reste.
6. Hors ligne : `ADD_INVOICE_PAYMENT {invoiceId, paymentId, amount, paidAt? | dueDate?, method?, note?}` et `SETTLE_INVOICE_PAYMENT {invoiceId, paymentId, paidAt?, method?}` (entityId = la facture) ; renvoyée à l'identique → APPLIED sans doublon ; même `paymentId` avec un autre contenu → REJECTED `VALIDATION_ERROR`.
6b. Un paiement ou une échéance ajouté sur un brouillon fait avancer son `updatedAt` : pour la modification suivante, l'app doit envoyer comme `expectedUpdatedAt` le `updatedAt` renvoyé par l'appel de paiement (sinon 409 `OPTIMISTIC_LOCK`).
7. Liste filtrée : `GET /api/v1/invoices?paymentState=OVERDUE` (ou `UNPAID`, `PARTIAL`, `PAID`) ; autre valeur → 400. La notification « Factures en retard » ouvre ce lien (`/invoices?paymentState=OVERDUE`).
8. Erreurs : montant au-delà du reste dû → 409 `OVERPAYMENT` « Le montant dépasse ce qui reste dû sur la facture (reste dû : X FCFA) », rien n'est enregistré ; date de paiement invalide → 400 `INVOICE_PAYMENT_DATE_INVALID` ; paiement reçu sur un brouillon ou une facture payée/annulée → 409 `INVOICE_INVALID_TRANSITION` ; sur un devis → 409 « Un devis doit être converti en facture » ; montant nul, deux dates ou aucune → 422 `VALIDATION_ERROR` ; échéance inconnue → 404 `INVOICE_NOT_FOUND` ; annulation d'une facture avec un paiement reçu → 409 `INVOICE_INVALID_TRANSITION` « Des paiements sont enregistrés sur cette facture : établissez un avoir ».
Cas de calcul partagés : `keevo/contracts/invoice-payment-cases.json` ; contrat de synchro : `keevo/contracts/invoice-sync-payloads.json` (`paymentOperations`). Code : `InvoiceController.java`, `InvoicePaymentUseCase.java`, `PaymentStateCalculator.java`, `adapter/in/sync/InvoiceSyncHandler.java`.

**À implémenter côté mobile :**
1. Sur une facture : bandeau d'état (libellé français et couleur), « Payé : X / Y FCFA », liste des paiements et des échéances (date, montant, mode ; échéance en retard mise en évidence).
2. Actions « Encaisser » (montant prérempli avec le reste dû, date du jour modifiable, mode), « Programmer une échéance », « Régler » sur une échéance, « Solder ».
3. Générer le `paymentId` sur l'appareil et mettre l'opération en file hors ligne ; afficher l'état local recalculé avec les cas du contrat jusqu'à la synchro.
4. Liste « En retard » (filtre `paymentState=OVERDUE`) et ouverture depuis la notification.
5. Aucune donnée technique à l'écran (ni identifiants, ni codes).

**Critères d'acceptation mobile :**
- Given une facture émise de 100 000 FCFA, when l'utilisateur encaisse un acompte de 30 000, then elle s'affiche « Partiellement payée », payé 30 000 / 100 000.
- Given un montant supérieur au reste dû, when l'utilisateur valide, then le message du serveur s'affiche et rien n'est ajouté.
- Given une échéance dépassée, when le traitement du matin est passé, then la facture est « En retard » et le propriétaire reçoit « Factures en retard ».
- Mise en place : `curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"paymentId":"'$(uuidgen)'","amount":30000,"paidAt":"'$(date +%F)'","method":"Mobile Money"}' $API/api/v1/invoices/$INVOICE_ID/payments`.

**Clé de suivi mobile :** `m20-4-encaissements-echeancier-retard`.
