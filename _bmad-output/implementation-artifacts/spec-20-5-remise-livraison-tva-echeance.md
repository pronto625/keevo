---
title: '20-5 Invoice discount, delivery fee, per-invoice VAT, due date, copied client data — Bref-identical totals'
type: 'feature'
created: '2026-11-06'
status: 'done'
baseline_revision: '99bbc269b83475a18f7b15cfb01f549d8c4e33c4'
review_loop_iteration: 0
followup_review_recommended: true
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
- Spec section `## Auto Run Result

Status: done

**Change:** invoices gain discount, delivery fee (outside the VAT base), own VAT rate or exemption, due date, copied client RCCM/email/contact/website, line product id and reference (invoicing V11, index V12). Calculation by frozen pricing mode: prices without VAT → Bref's formula exactly (VAT rounded once on the net, delivery after); prices with VAT (Keevo default, POS) → today's per-line calculation, discount reducing VAT proportionally, identical to today without discount/delivery. Default VAT rate from the oldest active store's country (Bref table; unknown country → 19.25). Overdue job also uses the invoice due date. Credit notes on adjusted or prices-without-VAT invoices credit exactly the invoice totals (full) or the exact remainder (final); partial credit notes on discount/delivery invoices refused. PDF shows Sous-total/Remise/Livraison only when relevant, « Total TVA » as before, Échéance, client fields, line references. 62 calculation cases published in `keevo/contracts/invoice-calculation-cases.json` (19 from the Bref dump, amounts only).

**Files (main):** `db/modules/invoicing/V11__invoice_discount_delivery_vat_due.sql`, `V12__invoices_due_date_index.sql`; domain `VatCalculator.compute`, `InvoiceTerms`, `InvoiceTotals`, `ClientInfo`, `InvoiceLine`/`LineInput`, `Invoice`, `CreditNote`; `shared/domain/VatRates`; `StoreQueryPort.oldestActiveStoreCountry`; `JdbcInvoicingSettingsRepository`, `JdbcInvoicePaymentRepository.markOverdue` (CTE); `ConvertQuoteUseCase`, `ManageDraftInvoiceUseCase`, `InvoiceClientResolver`, crm `ClientSummary` rccm/email; REST/sync DTOs; invoice and credit-note PDF renderers/templates; contracts `invoice-calculation-cases.json`, `invoice-sync-payloads.json` `termsOperations`.

**Review:** 19 triage rows (38 reviewer findings): 15 patched (high: credit notes vs document rounding; medium: overdue job index/CTE, credit-note PDF and totals, legacy reprint layout, unknown-country default, quote past due date at conversion, bean validation + sync, HT-mode line columns; low: store port query, dead overload, CRM fields, due date at save, single-call refusal tests), 0 deferred, 4 rejected with reasons (HT drafts recomputed with Bref rounding accepted — invoicing not live in prod, issued invoices frozen).

**Follow-up review recommended:** true — a high entry was patched (credit notes now credit document totals or exact remainders on HT/adjusted invoices; new path touching legal documents).

**Verification:**
- Full backend suite package by package: 6,218 tests, 0 failures.
- 62 calculation cases pass through `Invoice.draft`.
- Live on keevo_dev (WhatsApp noop, FCM/Expo off), test tenant with invoicing activated and settings switched to VAT-registered prices without VAT for the check, then restored and deactivated: Bref case (317 700, discount 2 500, delivery 2 000, due +30 days) issued as `FAC-2026-000004` with HT 315 200, TVA 60 676, TTC 377 876, subtotal 317 700, due 2026-12-06; PDF shows Échéance, RCCM, Réf. RIZ-25, Remise, Total HT, Total TVA, Livraison, Total TTC.

**Residual risks:** V11/V12 apply lazily per tenant; the overdue job skips tenants below V11 until their next invoicing call; a closing credit note on a prices-without-VAT invoice may print lines whose sum differs by 1–2 F from its document totals.

## Mobile (React Native — dépôt AI/bref)` and sprint key `m20-5-remise-livraison-tva-echeance` (finalize).

**Acceptance Criteria:**
- Given the 50 calculation cases, when the backend test runs them, then every expected total matches exactly.
- Given existing invoices and the full backend suite, when the new build runs, then their totals are unchanged and the suite passes package by package.
- Given a live local backend (WhatsApp noop, FCM/Expo off), when an HT-mode draft with discount, delivery and due date is created, issued and downloaded as PDF, then the totals equal Bref's and the PDF shows Remise, Livraison and Échéance.

## Review Triage Log

### 2026-11-06 — Review pass
- verdicts: 19 entries (38 reviewer findings, duplicates grouped) — high 1, medium 8, low 10, false 0, maybe-false 0
- findings:
  - `[medium]` `[patch]` (patterns#1 / blind#9) overdue job OR defeats the new index; pg_attribute probe per tenant; index lacks superseded_at — CTE of candidates + UPDATE … FROM; module-version gate; V12 index with superseded_at IS NULL
  - `[low]` `[patch]` (patterns#2) catalog query per tenant per run — grouped with patterns#1
  - `[low]` `[patch]` (patterns#3 / blind#10 / edge#6) countryVatRate loads all active stores, ignores null createdAt — narrow store port method ORDER BY created_at NULLS LAST, id LIMIT 1
  - `[low]` `[reject]` (patterns#4) 28-component Invoice copied by hand — no defect; a copy builder is a later refactor
  - `[medium]` `[reject]` (blind#1 / intent R1 / edge#8) HT mode rounds VAT once even without discount/delivery: re-saved pre-20.5 HT drafts move by ~1 F, old HT quotes refused at conversion until re-saved — accepted reading R1b: the intent requires Bref's formula in HT mode; invoicing is not live in prod, issued invoices are frozen, the quote refusal carries a re-save message
  - `[high]` `[patch]` (blind#2 / edge#1 / edge#2 / VG#2 / VG other) HT-mode credit notes summed per line differ from the document total (1 F left due, possible false overdue) — full credit = invoice totals; remaining = invoice TTC − credited; final credit takes the exact remainder; tests
  - `[medium]` `[patch]` (blind#3 / patterns#4b / edge#7) credit-note PDF and totals drop delivery/subtotal (3-arg InvoiceTotals) — full totals carried; credit-note PDF prints the same rows as the invoice
  - `[medium]` `[patch]` (blind#4) reprinting a pre-20.5 invoice changes its layout (Sous-total always, TVA row hidden at 0) — rows only with adjustments; TVA row as before
  - `[medium]` `[patch]` (blind#5 / VG other / edge#6) unknown country gives a 0 % default rate — unknown country → no table entry → 19.25 fallback
  - `[medium]` `[patch]` (blind#6 / edge#4) quote due date copied to the invoice blocks conversion with an invoice-worded error — checked before conversion with a quote-worded 409
  - `[medium]` `[patch]` (blind#7 / edge#5) new request fields lack bean validation; sync bypasses mapping; English reference message — bean validation, sync REJECTED with French reason
  - `[low]` `[patch]` (blind#8 / VG other) PaymentStateCalculator.markedByJob 5-arg overload unused — deleted
  - `[low]` `[patch]` (blind#11 / VG#1) CRM resolution ignores RCCM/email; new client fields untested in the resolver — filled from CRM when absent; resolver tests
  - `[low]` `[patch]` (blind#12) due date only checked at issue — refused before today at draft/quote save
  - `[medium]` `[patch]` (blind#13 / intent R2) HT-mode PDF line TVA/TTC columns cannot add up to the document totals — per-line TVA/TTC hidden in HT mode
  - `[low]` `[patch]` (blind#14) full-context tests send refused requests twice — single call asserts status, body and nothing written
  - `[low]` `[reject]` (intent R3) settings row not seeded, only in-memory defaults — a settings row is written by the owner's first save; the default is used until then — same observable rate
  - `[low]` `[reject]` (intent R4) calculation cases could be circular — generated by running Bref's formula with float vs exact-decimal agreement checked; the 19 dump cases are Bref's stored results
  - `[low]` `[reject]` (intent) .5 Bref case only checked at calculator level — the API path uses the same calculator; Bref case 1 is checked end to end

## Design Notes

Pattern: **strategy by pricing mode** inside one pure calculator (`HT_BREF` / `TTC_KEEVO`), chosen from the invoice's frozen `prices_include_vat`; the strategies share line computation and differ only at the document step, so existing behaviour is the TTC strategy with discount = delivery = 0. Country rates are a constant lookup table (no query per invoice); the seller country is read once per settings creation / issue.

Cost: unchanged per invoice (O(lines)); no extra query on list/pull (new columns read in the same rows).

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='com/keevo/invoicing/**/*Test*'` -- expected: pass
- Full suite package by package (`run-pkgs-wait.sh`) -- expected: 0 failures
- Live: local backend on keevo_dev (WhatsApp noop, FCM/Expo off), invoicing activated on the test tenant for the check then deactivated -- expected: as in the AC.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée sur `refonte/modulaire` (commit à reporter ici une fois poussé). Conventions générales : spec-16-11 ; protocole de synchro : spec-16-10.

**Ce qui est fait côté API :** une facture ou un devis accepte une remise, des frais de livraison, son propre taux de TVA ou une exonération totale, une date d'échéance, les coordonnées complètes du client (RCCM, email, contact, site web) et, par ligne, le produit du catalogue et une référence. Le calcul est celui de Bref quand les prix sont saisis hors taxe (TVA arrondie une seule fois sur le document, livraison hors TVA) ; avec des prix TTC (mode Keevo par défaut), le résultat est inchangé sans remise ni livraison, et la remise réduit la TVA au prorata. Le taux par défaut est celui du pays de la plus ancienne boutique active (CM 19,25 %, SN/CI/BJ/ML/BF/TG/GN/GA 18 %, CD/KE 16 %, GH 15 %, NG 7,5 % ; un autre pays garde 19,25 %). Une facture émise dont l'échéance est dépassée et qui n'est pas entièrement payée passe « En retard » au traitement du matin (comme une échéance de 20.4).

**À utiliser :**
1. Création / modification (REST `POST /api/v1/invoices`, `PUT /api/v1/invoices/{id}`, synchro `CREATE_INVOICE` / `UPDATE_INVOICE`, factures et devis) — champs ajoutés, tous facultatifs : `discountAmount` (FCFA entiers ≥ 0, au plus le sous-total), `deliveryFee` (≥ 0), `vatRate` (taux du document en %, 0 à 100, 2 décimales ; absent = taux du vendeur), `vatExempt` (`true` = aucune TVA, mention « Exonéré de TVA »), `dueDate` (`yyyy-MM-dd`) ; `client.rccm` (≤ 50), `client.email` (≤ 255, valide), `client.contactName` (≤ 120), `client.website` (≤ 200) ; par ligne `productId` (produit du catalogue, non vérifié) et `reference` (≤ 100). Une remise en pourcentage se convertit en montant dans l'app (l'API ne prend qu'un montant).
2. Une modification remplace tout le contenu : un champ absent est remis à zéro (pas de remise, pas de livraison, taux du vendeur, pas d'échéance). Toujours renvoyer tous les champs du document.
3. Lecture (REST et pull `invoices`) : `subtotal` (somme des lignes telles que saisies — HT ou TTC selon `pricesIncludeVat` — avant remise), `discountAmount`, `deliveryFee`, `vatRate` (taux appliqué, 0 si exonéré), `vatRateOverride` (taux propre demandé, `null` = taux du vendeur), `vatExempt`, `dueDate`, `vatMention` (« Exonéré de TVA » / « TVA non applicable »), les nouveaux champs client et ligne. `totalTtc = totalHt + totalVat + deliveryFee` ; `amountDue` (20.4) part de ce `totalTtc`. Avec des prix HT, les colonnes TVA/TTC des lignes sont une répartition indicative : seuls les totaux du document font foi.
4. Réglages : `GET /api/v1/invoicing/settings` renvoie, tant que le propriétaire n'a rien enregistré, le taux du pays de la boutique (à proposer dans le formulaire).
5. Avoir sur une facture avec remise ou livraison : seul l'avoir total est accepté (`POST /invoices/{id}/credit-notes` sans `lines`, ou toutes les lignes en entier) ; il crédite exactement le total TTC de la facture. Sur une facture à prix HT sans remise ni livraison, un avoir partiel reste possible (calculé par ligne) ; l'avoir qui solde la facture crédite exactement ce qui reste (total de la facture moins les avoirs précédents), donc le reste dû tombe à 0.
6. Erreurs : remise au-delà du sous-total, taux hors 0–100 ou à plus de 2 décimales, montant négatif, email invalide, référence de plus de 100 caractères, échéance déjà passée à l'enregistrement → 422 `VALIDATION_ERROR` (message français dans `error`) ; échéance devenue antérieure à la date d'émission → 422 à l'émission (aucun numéro consommé) ; conversion d'un devis dont l'échéance est passée → 409 `INVOICE_INVALID_TRANSITION` « La date d'échéance de ce devis est passée : modifiez-la avant de le convertir. » ; avoir partiel sur une facture avec remise ou livraison → 409 `INVOICE_INVALID_TRANSITION` « Avoir partiel impossible sur une facture avec remise ou livraison : établissez un avoir total ». En synchro : `REJECTED` `VALIDATION_ERROR`.
Cas de calcul partagés : `keevo/contracts/invoice-calculation-cases.json` (62 cas : les 19 documents de la copie Bref, la formule Bref sur des cas variés dont les arrondis à ,5 et chaque pays, et les modes Keevo) ; contrat de synchro : `keevo/contracts/invoice-sync-payloads.json` (`termsOperations`). Code : `VatCalculator.java`, `InvoiceTerms.java`, `InvoiceRequestDto.java`, `InvoiceResponseDto.java`.

**À implémenter côté mobile :**
1. Formulaire facture / devis : remise (montant ou % converti en montant), frais de livraison, choix « TVA du pays / autre taux / exonéré », date d'échéance, champs client RCCM, email, contact, site web, référence par ligne (et produit choisi dans le catalogue).
2. Récapitulatif : Sous-total, Remise, TVA, Livraison, Total — calculés localement avec les cas du contrat jusqu'à la réponse du serveur, puis les montants du serveur.
3. Avoir : sur une facture avec remise ou livraison, proposer uniquement l'avoir total.
4. Aucune donnée technique à l'écran (ni identifiants de produit, ni codes).

**Critères d'acceptation mobile :**
- Given des prix HT, un sous-total de 317 700, une remise de 2 500 et une livraison de 2 000 (Cameroun), when la facture est enregistrée, then TVA 60 676 et total 377 876 s'affichent, comme dans Bref.
- Given « Exonéré » coché, when la facture est enregistrée, then TVA 0 et la mention « Exonéré de TVA ».
- Given une facture avec remise, when l'utilisateur demande un avoir partiel, then seul l'avoir total est proposé.
- Mise en place : `curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"client":{"name":"Client test","rccm":"RC/DLA/2021/A/77"},"lines":[{"description":"Article","quantity":2,"unitPrice":150000}],"discountAmount":2500,"deliveryFee":2000,"dueDate":"'$(date -d '+30 days' +%F)'"}' $API/api/v1/invoices`.

**Clé de suivi mobile :** `m20-5-remise-livraison-tva-echeance`.
