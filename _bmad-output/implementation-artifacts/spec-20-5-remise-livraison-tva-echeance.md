---
title: '20-5 Invoice discount, delivery fee, per-invoice VAT, due date, copied client data — Bref-identical totals'
type: 'feature'
created: '2026-11-06'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-4-encaissements-echeancier-retard.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Bref invoices carry a discount, a delivery fee (outside the VAT base), their own VAT rate or an exemption, a due date, more client data (RCCM, email, contact, website) and line references; Keevo invoices have none of these, so Bref invoices cannot be reproduced (FUS-FR19). Bref and Keevo also compute VAT differently: Bref works on prices without VAT and rounds VAT once on the whole invoice (`tax = Math.round((subtotal − discount) × rate)`, `total = net + tax + delivery`); Keevo rounds VAT per line and, by default, works on VAT-inclusive prices. Existing Keevo invoices (legal, numbered) must not change.

**Approach:** Add the fields (invoicing V11, additive). Keep the calculation mode tied to the invoice's `prices_include_vat` snapshot: **prices without VAT → Bref formula exactly** (the mode of tenants coming from Bref); **prices with VAT (Keevo default, POS) → today's per-line calculation**, extended with discount and delivery in a way that gives today's result when both are 0. Default VAT rate per seller country from Bref's table. Publish a 50-case calculation contract built from the real Bref dump and from cases computed with Bref's exact formula.

## Boundaries & Constraints

**Always:**
- **Additive** (FUS-NFR1): absent new fields = today's behaviour; an invoice without discount and delivery, with the seller's rate, gives exactly today's totals (existing invoices and POS invoices unchanged, existing tests keep passing).
- **New invoice fields**: `discount_amount` (≥ 0, whole XAF, ≤ subtotal, else 422), `delivery_fee` (≥ 0), `vat_rate` (nullable percent 0–100, 2 decimals; null = seller rate), `vat_exempt` (boolean; true → no VAT on the document, mention « Exonéré de TVA »), `due_date` (nullable date, not before the issue date once issued), `client_rccm` (≤ 50), `client_email` (≤ 255, valid), `client_contact_name` (≤ 120), `client_website` (≤ 200). New line fields: `product_id` (nullable UUID, no cross-module FK, not validated against the catalog), `reference` (nullable, ≤ 100). Accepted on draft create/update and quotes (REST + `CREATE_INVOICE`/`UPDATE_INVOICE`), frozen at issue like the rest.
- **Calculation — prices without VAT (`prices_include_vat = false`), Bref formula:** line HT = HALF_UP(quantity × unit price); subtotal = Σ line HT; net = subtotal − discount; VAT base = net × (Σ STANDARD line HT / subtotal) (= net when every line is STANDARD, Bref's only case); VAT = HALF_UP(base × rate / 100) once on the document (rate = 0 when `vat_exempt` or the seller is not VAT-registered); total TTC = net + VAT + delivery. Line rows keep their own HT; line VAT/TTC columns hold the per-line split for display only (sum may differ by rounding from the document VAT — the document totals are authoritative).
- **Calculation — prices with VAT (Keevo default):** lines exactly as today (per-line HALF_UP extraction); then, if discount > 0, the document TTC before delivery = Σ line TTC − discount and the VAT is reduced proportionally: VAT = HALF_UP(Σ line VAT × (Σ line TTC − discount) / Σ line TTC), HT = TTC − VAT; delivery added after, outside VAT. With discount = 0 and delivery = 0 the result is identical to today.
- **Totals model**: `InvoiceTotals` gains `subtotal`, `discount`, `delivery` (so every comparison of totals — e.g. 20.3 quote conversion VAT-drift check — includes them); `total_ttc` includes the delivery fee (20.4 amount due = total TTC − credited keeps working). DB CHECKs: totals ≥ 0, discount ≤ subtotal.
- **Default VAT rate per country** (Bref table, shared constant): CM 19.25; SN, CI, BJ, ML, BF, TG, GN, GA 18; CD, KE 16; GH 15; NG 7.5; any other country 0. The seller's country is the tenant's oldest active store's `country` (store API port); it seeds `invoicing_settings.vat_rate` when settings are created and is the fallback when an invoice has no rate and settings have none. Existing settings rows are never rewritten. Currency stays XAF / « FCFA » everywhere (Bref behaviour; non-XAF currencies stay the documented gap E1).
- **Due date and overdue**: the 20.4 daily job also treats an ISSUED invoice whose `due_date` < today (WAT) and amount paid < amount due as overdue (same single notification per tenant).
- **Credit notes on a document with discount or delivery**: only a full credit note (all lines, full quantities) is accepted and it credits exactly the invoice's total TTC; a partial one → 409 (French « Avoir partiel impossible sur une facture avec remise ou livraison : établissez un avoir total »).
- **PDF / response / pull**: `InvoiceResponseDto` gains every new field and `subtotal`, `discountAmount`, `deliveryFee`, `vatRate` (effective), `vatExempt`, `dueDate`; the PDF shows Sous-total, Remise (if > 0), TVA (if > 0), Livraison (if > 0), Total, « Échéance » (if set), the client's RCCM/email/contact/website when present, line references.
- **Reference cases**: `keevo/contracts/invoice-calculation-cases.json` with ≥ 50 cases (inputs: mode, lines, discount, delivery, rate, exempt, treatments; expected: subtotal, HT, VAT, TTC). Sources: every invoice of the local Bref dump `/home/toor/Downloads/bd_local_20261103_213348.sql.gz` (19 documents, amounts only — no names, phones or other personal data), and the rest computed by running Bref's own formula (`api-bref/src/invoice/invoice.service.ts:68-85`, `utils/line-total.ts`) on varied inputs, including exact .5 VAT cases (e.g. (300000 − 15000) × 0.1925 = 54862.5 → 54863), discounts, deliveries, exempt, each country rate, decimal quantities. A backend test runs every case through the domain calculator; the RN app reuses the file (FUS-NFR8).

**Never:**
- No change to issued invoices, to the numbering, or to POS automatic invoices' totals.
- No percent discount on the API (Bref's percent is an app-side conversion to an amount).
- No public mirror (invoicing is tenant-only). No dashboard API. No deliverable Flutter feature. No personal data from the Bref dump in any committed file.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Bref case 1 (HT mode, CM) | subtotal 317 700, discount 2 500, delivery 2 000 | VAT 60 676, TTC 377 876 | — |
| Bref .5 case | subtotal 300 000, discount 15 000, delivery 2 500 | VAT 54 863, TTC 342 363 | — |
| Keevo default unchanged | TTC mode, no discount/delivery | totals identical to today | — |
| TTC mode with discount | lines TTC 119 250 (VAT 19 250), discount 19 250 | TTC 100 000, VAT HALF_UP(19 250 × 100 000 / 119 250) | — |
| Exempt | `vatExempt: true` | VAT 0, mention « Exonéré de TVA » | — |
| Own rate | `vatRate: 18` on a CM seller | 18 % applied | rate > 100 → 422 |
| Discount too big | discount > subtotal | refused | 422 |
| Default rate SN | new tenant, oldest store SN | settings rate 18 | — |
| Due date overdue | ISSUED, due_date yesterday, unpaid | job marks OVERDUE | — |
| Partial credit on discounted invoice | partial lines | refused | 409 |
| V1 body | no new fields | as today | — |

</intent-contract>

## Code Map

Paths under `keevo/backend/src/main/` unless stated.
- `java/com/keevo/invoicing/invoice/domain/model/VatCalculator.java` -- `lines(...)` L28-53 (per-line: TTC mode L40-43, HT mode L44-47, `franc()` HALF_UP L72-78), `totals()` L55-65, `vatMention()` L68-70. New document-level step lives here or in a sibling pure class.
- Same folder: `InvoiceTotals.java` L4, `InvoiceLine.java` L9-18, `LineInput.java` L33-54 (3-decimal quantity), `VatTreatment.java` L9-11, `InvoicingSettings.java` L67-104 (`DEFAULT_VAT_RATE` L80, defaults L102), `ClientInfo.java` L9, `Invoice.java` (`draft()` L113-120, `withDraftContent` L124-129, `quote()` L152, `conversionDraft()` L205-210, `issue()` L274-284), `CreditNote.java` L210, `PaymentStateCalculator.java` L47-48,152.
- `java/com/keevo/invoicing/invoice/application/usecase/ConvertQuoteUseCase.java` L88-93 (totals comparison), `InvoiceLifecycleUseCase.java` L87-108 (seller snapshot), `InvoicePaymentUseCase` + `JdbcInvoicePaymentRepository.markOverdue` (20.4 job SQL — add the invoice due-date condition).
- DDL: `resources/db/modules/invoicing/V1__invoicing.sql` (`invoicing_settings` L18-32, `invoices` L34-73, `invoice_items` L78-91); latest V10 → new `V11__invoice_discount_delivery_vat_due.sql` (idempotent, `SET LOCAL lock_timeout`, no data UPDATE).
- Store country: `resources/db/modules/store/V2__stores_bref_fields.sql` L20, `java/com/keevo/store/api/StoreCountry.java` (constant only), `store/api/StoreView.java` L32, `StoreQueryPort` (add or reuse a call for the oldest active store's country).
- REST/sync DTOs: `java/com/keevo/invoicing/invoice/adapter/in/rest/dto/InvoiceRequestDto.java` L30-56, `InvoiceClientDto.java`, `InvoiceLineRequestDto.java` L19-32, `InvoiceResponseDto.java` L41-81 (Client L93, Line L100); `adapter/in/sync/InvoiceSyncHandler.java` L69-70,161,165; `adapter/in/sync/InvoiceDeltaProvider.java` L95; contract `keevo/contracts/invoice-sync-payloads.json` (test `src/test/java/com/keevo/invoicing/invoice/adapter/in/sync/InvoiceSyncHandlerTest.java`).
- PDF: `java/com/keevo/invoicing/invoice/adapter/out/pdf/InvoicePdfRenderer.java` L78-89,134-136, `java/com/keevo/shared/infrastructure/pdf/PdfFormats.java` L28, `resources/pdf/templates/invoice.html`.
- Bref (read-only): `api-bref/src/invoice/invoice.service.ts` L52-148 (create calc L68-85), L584-655 (update keeps rate), `utils/line-total.ts` L6-9, `invoice/constants/vat-rates.constant.ts` L5-26, `invoice/invoice-pdf.service.ts` L19,171-177,266-271, `invoice-item.entity.ts`; dump `/home/toor/Downloads/bd_local_20261103_213348.sql.gz` (read with `zcat`, never commit personal data); synthetic fixture `_bmad-output/planning-artifacts/research/fusion-mesures/validation/fixture-bref-synthetique.sql` L35-52.

## Tasks & Acceptance

**Execution:**
- `V11__invoice_discount_delivery_vat_due.sql` -- columns on `invoices` and `invoice_items`, CHECKs.
- Shared VAT-rate-by-country constant (Bref table) + seller country lookup + settings default.
- Domain calculator: HT-mode Bref formula, TTC-mode extension, extended `InvoiceTotals`; `Invoice` fields and rules (discount ≤ subtotal, due date ≥ issue date at issue, exempt); credit-note full-only rule.
- Persistence, use cases, REST/sync DTOs, delta, PDF, conversion comparison, 20.4 job due-date condition.
- `keevo/contracts/invoice-calculation-cases.json` (≥ 50 cases as specified) + backend test; sync contract entries for the new fields; Flutter test client kept compiling (bump `pubspec` if touched).
- Tests: unit (calculator both modes incl. .5 cases, unchanged results without discount/delivery), use cases, full context REST + sync, Postgres V11 on existing invoices (unchanged), job with due date, credit-note rule.
- Spec section `## Mobile (React Native — dépôt AI/bref)` and sprint key `m20-5-remise-livraison-tva-echeance` (finalize).

**Acceptance Criteria:**
- Given the 50 calculation cases, when the backend test runs them, then every expected total matches exactly.
- Given existing invoices and the full backend suite, when the new build runs, then their totals are unchanged and the suite passes package by package.
- Given a live local backend (WhatsApp noop, FCM/Expo off), when an HT-mode draft with discount, delivery and due date is created, issued and downloaded as PDF, then the totals equal Bref's and the PDF shows Remise, Livraison and Échéance.

## Design Notes

Pattern: **strategy by pricing mode** inside one pure calculator (`HT_BREF` / `TTC_KEEVO`), chosen from the invoice's frozen `prices_include_vat`; the strategies share line computation and differ only at the document step, so existing behaviour is the TTC strategy with discount = delivery = 0. Country rates are a constant lookup table (no query per invoice); the seller country is read once per settings creation / issue.

Cost: unchanged per invoice (O(lines)); no extra query on list/pull (new columns read in the same rows).

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='com/keevo/invoicing/**/*Test*'` -- expected: pass
- Full suite package by package (`run-pkgs-wait.sh`) -- expected: 0 failures
- Live: local backend on keevo_dev (WhatsApp noop, FCM/Expo off), invoicing activated on the test tenant for the check then deactivated -- expected: as in the AC.
