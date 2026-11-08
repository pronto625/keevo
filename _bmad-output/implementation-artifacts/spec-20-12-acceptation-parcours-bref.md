---
title: '20-12 Acceptance test — the Bref journey on Keevo'
type: 'chore'
created: '2026-11-08'
status: 'done'
baseline_revision: '13016b1b0e7bbb94ca6a7d463eea68ce045d4b4e'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/epics-fusion-keevo-bref.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** epic 20 delivered the Bref capabilities story by story (quotes, payments, overdue job, public links, expenses, finance, reporting, document store), each proven by its own tests. Gate G1 needs one proof that the day of a Bref owner works end to end on the Keevo API, and that the Keevo POS journey is still intact.

**Approach:** one full-context acceptance class, `BrefJourneyAcceptanceFullContextTest`, replays the journey through the real HTTP chain: production security filter, JWTs issued by the OTP sign-in, module gates, PostgreSQL and the real 19.8 quota counters. Every step is asserted on the REST surface the app uses, and the amounts are checked by hand. A symmetric Keevo POS scenario runs in a second tenant. Code changes only if a real bug blocks the journey.

## Boundaries & Constraints

**Always:**
- Use only the endpoints listed in the Mobile sections of specs 20-3 to 20-14, plus `/auth/otp/*`, `/auth/create-company`, `/onboarding/complete`, `/tenants/me/modules/{id}/activation`, `/subscription/me` and `/admin/subscriptions/{schema}/activate`. The super-admin path stands in for a plan purchase until epic 21.
- Set up data with no SQL, with two exceptions: the OTP code hash, because the code is never readable, and the overdue job, which is built with a fixed clock (the next morning, 08:00 WAT) from the context beans.
- The steps share one tenant: each step needs the previous one to have passed.
- No real SMS, email or WhatsApp. The test context forces WhatsApp to noop and has no `.env`. The SMS step swaps in a capturing fake sender.
- Assert the quota counters with `GET /subscription/me` (`quotas[].used`, MONTH period), never by reading a table.

**Never:**
- No production change made only to make a test pass.
- No migration.
- No edit to `sprint-status.yaml` or `deferred-work.md`.
- No mobile or server work.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Sign-in | new phone, OTP code | account with no company → company → shop « Documents » → PRO → invoicing/expenses/finance ENABLED, preset DOCUMENT | — |
| Quote | 1 line 100 000 HT, VAT 19.25 % | DRAFT, `DV-XXXXXXXX`, no number, TTC 119 250, documents used 0 | — |
| Conversion | convert, then convert again | `FAC-<year>-000001` ISSUED today, documents used 1; replay → 200, same invoice, still 1 | — |
| Deposit + instalment | 50 000 received, 69 250 due today | PARTIAL, two rows | — |
| Overdue | job at D+1 08:00 WAT | OVERDUE, listed under `?paymentState=OVERDUE`, inbox « Factures en retard » « 1 facture(s) en retard de paiement : FAC-… » | — |
| Payment | settle the instalment | PAID, amountPaid 119 250 | — |
| SMS (20-8) | `send {channel: SMS}` | one SMS carrying the public link (the link opens the PDF), sendCount 1, SMS used 1 | — |
| Expense + finance | rent 30 000 today | CA TTC 119 250, VAT 19 250, CA HT 100 000, paid 119 250, expenses 30 000, result 70 000, margin 70 %, expense ratio 30 %; dashboard todayCA 119 250 | — |
| Public link | `public-link`, anonymous GET | PDF with the number and the client | — |
| Converted quote, invoice cancelled | 2nd quote → convert → cancel the invoice | quote stays CONVERTED and points to the cancelled `FAC-…-000002`; convert → 200, same invoice; cancelling the quote → 409; a new quote is created; revenue unchanged; documents used 2 | — |
| Keevo POS | sale 2 × 1 000 → bridge → day closure | one PAID automatic invoice `FAC-…-000001` in the sale's store; closure 1 sale / 2 000; dashboard todayCA 2 000 (not 4 000); finance TTC 2 000; documents used 0 | — |

</intent-contract>

## Code Map

- `keevo/backend/src/test/java/com/keevo/acceptance/BrefJourneyAcceptanceFullContextTest.java` -- new: the journey, ordered steps, the POS scenario.
- `keevo/backend/src/test/java/com/keevo/acceptance/ModularVisionAcceptanceTest.java` -- style reference (ordered steps sharing one tenant, French assertion messages).
- `keevo/backend/src/main/java/com/keevo/invoicing/invoice/adapter/in/scheduling/InvoiceOverdueJob.java` -- built with a fixed `Clock`.
- `api-bref/src/invoice/invoice.service.ts` (`validate`, `cancel`) -- the reference for the routed item (read-only).

## Tasks & Acceptance

**Execution:**
- `keevo/backend/src/test/java/com/keevo/acceptance/BrefJourneyAcceptanceFullContextTest.java` -- write the 10 Bref steps and the POS scenario -- the epic 20 proof.
- The routed item -- check Bref, then record the decision (Design Notes) and assert Keevo's current behaviour in step 9.

**Acceptance Criteria:**
- Given a DOCUMENT tenant with the PRO profile, when the scenario runs (OTP login → shop → quote → conversion → deposit → instalment → overdue → payment → SMS → expense → finance → public link), then every step succeeds, the quotas are counted, and the revenue and result are exact.
- Given a POS tenant, when the Keevo scenario runs (POS sale → automatic invoice → day closure), then it stays green and the revenue is counted once.

## Design Notes

**Routed item (review 20-3): a quote whose converted invoice is cancelled.** Bref reference, read-only (`api-bref/src/invoice/invoice.service.ts`):
- `validate` turns the quote into the sale **in place**: same row, `type: QUOTE → SALE`.
- `cancel` sets that sale to `CANCELLED`, rotates its public token, and reverts the CRM and catalogue counters.
- There is no duplicate, re-quote or reopen endpoint. The only « duplicate » in the Bref app is `duplicateItem`, which copies a line inside the invoice form (`features/invoice/components/add-invoice/step-articles.tsx`).
- So in Bref, a quote whose sale is cancelled is gone: the owner creates a new quote by hand.

**Decision:** no change. Keevo already gives the same outcome:
- The quote stays `CONVERTED` and points to the cancelled invoice.
- Converting again returns that invoice (idempotent, no new number), and cancelling the quote is refused (409).
- The owner creates a new quote, as in Bref. Step 9 asserts all of this.

**Proposal (not implemented, for the user to decide, beyond Bref parity):** `POST /api/v1/invoices/{id}/duplicate`.
- Body `{"id"?: <device uuid>}`, on a quote or an invoice in any status.
- Result: a new DRAFT quote with the same client, lines, discount, delivery, VAT choice and layout, in the caller's store. No signatures, no payments.
- No number, no quota, no derivation. Idempotent on the device id. Plus a sync operation `DUPLICATE_DOCUMENT`.
- Additive, no migration (a plain insert through `ManageDraftInvoiceUseCase`).
- If wanted, the owning story is the RN documents screen in epic 23, with the backend part added there.

**Why a second tenant for POS:** a Keevo merchant starts with the « Caisse » flow. Sharing the Bref tenant would mix the two revenues and hide a double count.

## Verification

**Commands:**
- `cd keevo/backend && bash kmvn.sh -o -q test -Dtest='BrefJourneyAcceptanceFullContextTest' -Dsurefire.failIfNoSpecifiedTests=false` -- expected: 11 run, 0 failures, 0 skipped.
- `cd keevo/backend && bash kmvn.sh -o -q test -Dtest='com/keevo/acceptance/**/*Test*'` -- expected: pass (run 2026-11-08 after 20-8: ModularVision 4/4, BrefJourney 11/11).
- `cd keevo/backend && bash kmvn.sh -o -q test -Dtest='com/keevo/invoicing/**/*Test*'` -- expected: pass.

## Auto Run Result

`BrefJourneyAcceptanceFullContextTest`, run 2026-11-08 after rebasing onto 20-8 / 20-13 (`0ff80d02`): 11 run, 0 failures, 0 skipped.

| # | Journey step | Assertions | Result |
|---|---|---|---|
| 1 | OTP login by phone → company → shop « Documents » → PRO → modules activated by the owner → seller profile | `memberships` empty, preset `DOCUMENT`, invoicing/expenses/finance/reporting ENABLED, documents 0 / SMS 0 | ✅ |
| 2 | Quote → conversion | `DV-XXXXXXXX`, no number, TTC 119 250, quota 0; convert → `FAC-<year>-000001` ISSUED today, HT 100 000 / VAT 19 250, quota 1; replay → 200, same invoice, quota 1; quote CONVERTED | ✅ |
| 3 | Deposit 50 000 + instalment 69 250 due today | PARTIAL, 2 rows, amountDue 119 250 | ✅ |
| 4 | Overdue job at D+1 08:00 WAT (fixed clock) | OVERDUE « En retard », `?paymentState=OVERDUE` = this invoice, inbox « Factures en retard » « 1 facture(s) en retard de paiement : FAC-… »; a second run sends no second notification | ✅ |
| 5 | Instalment settled | PAID, amountPaid 119 250, `paidAt` set | ✅ |
| 6 | SMS sending (20-8), REST `send {channel: SMS}`, fake provider | one SMS to the given number, link in the text opens the PDF, sendCount 1, `lastSentChannel` SMS, one `invoice_send_requests` row SENT, SMS used 1 | ✅ |
| 7 | Rent expense 30 000 → finance + dashboard | TTC 119 250, VAT 19 250, CA HT 100 000, paid 119 250, unpaid 0, 1 invoice, expenses 30 000, result 70 000, margin 70 %, expenses 30 %, « Loyer »; dashboard todayCA 119 250 | ✅ |
| 8 | Public link | 30 days, anonymous PDF with `FAC-…-000001` and « Société Ndolo » | ✅ |
| 9 | Converted quote whose invoice is cancelled (routed item) | `FAC-…-000002` cancelled with its number kept; quote stays CONVERTED and points to it; convert → 200, same invoice; cancelling the quote → 409; new DRAFT quote; documents 2; revenue unchanged | ✅ |
| 10 | End of day | documents 2, SMS 1; invoices `000001 PAID` + `000002 CANCELLED`; quotes CONVERTED, CONVERTED, DRAFT; finance and dashboard unchanged | ✅ |
| K | Keevo POS: sale 2 × 1 000 → automatic invoice → day closure | invoice PAID 2 000 `FAC-…-000001` in the sale's store; closure 1 sale / 2 000; dashboard todayCA 2 000 (not 4 000); finance TTC 2 000, 1 transaction; documents 0 | ✅ |

**Bugs found:** none. The journey passed with no production change. One test-side finding: a day closure deliberately leaves out sales received in the previous 5 seconds (`ClosureWindow.COMMIT_SAFETY_MARGIN`, v1s-13-13). The next closure counts them as late arrivals. This is intended, so the POS scenario closes the day after that margin, as at the end of a real day.

**After 20-8 was merged:**
- Step 6 is enabled. The fake SMS sender is swapped into `SmsInvoiceChannel` and `TenantMessaging` (field `smsSender`), as in `InvoiceSmsEmailSendingFullContextTest`.
- Steps 9 and 10 expect 1 SMS.
- No `keevo.public-base-url` property is set. An online REST send builds the link from the request (`PublicBaseUrl`), which gives an absolute `http://localhost/…` link. `InvoiceSmsEmailSendingFullContextTest` does not set the property either; it overrides `publicBaseUrl` only on the outbox, for the offline path. A test property would also start a second Spring context, which this machine cannot afford.
