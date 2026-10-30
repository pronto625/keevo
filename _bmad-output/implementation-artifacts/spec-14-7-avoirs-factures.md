---
title: '14.7 — Credit notes (avoirs): manual full/partial credit notes, automatic credit note on sale cancellation/correction'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: '1bad57ddbd23877fb9db4efec8da338c14d4546a'
route: 'dispatch'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-14-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-14-1-module-facturation-autonome.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-14-4-bridge-pos-facturation.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 14.7. Issued invoices are never modified or deleted, and a PAID invoice cannot be cancelled (409 `INVOICE_INVALID_TRANSITION`). So a merchant has no compliant way to correct or annul a paid invoice. Since 14.4, cancelling or correcting a sale leaves its automatic PAID invoice untouched (routed here).

**Approach:** Add a numbered credit note (« avoir ») in its own gapless series `AV-<yyyy>-<000001>`. It references an ISSUED or PAID invoice, in full or for some lines. Its amounts mirror the referenced lines' VAT treatment and are shown as negative, and the invoice keeps its number and content. The bridge issues credit notes automatically when an auto-invoiced sale is cancelled or corrected. A correction is handled as a full credit note followed by a new invoice for the corrected sale.

**Decisions (autonomous run; the user delegated all choices on 2026-10-28):**
- **Persistence** (`db/modules/invoicing/V4__credit_notes.sql`, additive):
  - `credit_notes(id UUID PK, number VARCHAR(20) UNIQUE NOT NULL, invoice_id UUID NOT NULL REFERENCES invoices(id), reason VARCHAR(300) NOT NULL, total_ht/total_vat/total_ttc BIGINT ≥ 0, issued_at, created_by, source_event VARCHAR(60) NULL UNIQUE)`.
  - `credit_note_items(credit_note_id FK, position, invoice_position, description, quantity NUMERIC(12,3), unit_price, vat_treatment, ht, vat, ttc; UNIQUE(credit_note_id, position))`.
  - `credit_note_counters(year INT PK, last_value)`.
  - `invoices.superseded_at TIMESTAMPTZ NULL`.
  - The V3 unique index on `invoices.source_sale_id` is replaced by a partial unique index `WHERE superseded_at IS NULL`, so a corrected sale can get a new invoice.
  - Amounts are stored positive; « avoir » semantics and the minus sign are presentation.
- **Domain** (`invoicing`): `CreditNote` aggregate created already issued (no draft). Its number comes from its own counter table, per calendar year in Africa/Douala, in the same transaction.
  - **Full credit note**: every line, with the remaining (not yet credited) quantities and amounts.
  - **Partial credit note**: `[{position, quantity}]`. Quantity is > 0, has at most 3 decimals and is ≤ the remaining credited quantity of that invoice line. A line credited up to its remaining quantity takes the remaining HT, VAT and TTC exactly (no rounding drift). Otherwise its amounts are computed like the invoice line, with the same unit price, treatment, invoice VAT snapshot (`vat_registered`, `vat_rate`, `prices_include_vat`) and HALF_UP rounding.
  - Refusals:
    - an empty selection, a line already fully credited, an over-quantity or an unknown position → 422 `CREDIT_NOTE_INVALID` « … » (French reason);
    - a DRAFT or CANCELLED invoice, or one already fully credited → 409 `INVOICE_INVALID_TRANSITION`;
    - a blank reason → 422.
- **Invoice view**: `InvoiceResponseDto` adds `creditedTtc` (sum over its credit notes), `fullyCredited` (= creditedTtc == totalTtc) and `creditNotes: [{id, number, issuedAt, totalTtc, reason}]`. Invoice status is unchanged; an ISSUED invoice can still be cancelled as before, but only if it has no credit note (409 otherwise).
- **Endpoints** (invoicing, `@RequiresEntitlement("invoicing")`):
  - `POST /api/v1/invoices/{id}/credit-notes` `{reason, lines?: [{position, quantity}]}` — WRITE, `@RequiresPermission(INVOICE_MANAGE)` (owner only). No `lines` means a full credit note. Returns the credit note.
  - `GET /api/v1/credit-notes/{id}` — READ, `INVOICE_VIEW`.
  - `GET /api/v1/credit-notes/{id}/pdf` — READ, `INVOICE_VIEW`. Uses the invoice PDF template variant: title « AVOIR » + number, « Avoir sur la facture FAC-… du … », reason, lines and totals with a leading « − », seller snapshot of the invoice.
  - `docs/entitlements/endpoints.md` is regenerated.
- **Events and audit**: `CreditNoteIssuedEvent(creditNoteId, number, invoiceId, invoiceNumber, totalTtc, actorId, tenantId, occurredAt)` in `shared/event/catalog/invoicing`.
  - Manifest `publishes` += `credit-note.issued`; version 1.3.0.
  - Audit `CREDIT_NOTE_ISSUED` « Avoir émis », label = the credit note number, detail « sur la facture FAC-… ».
  - App audit labels mirrored in `keevo/app/lib/features/audit/` (phrase 'émis l'avoir') with a pubspec bump, as in 14.1/14.2.
- **Automatic credit notes (bridge)**: they run only while invoicing and pos are ENABLED (same gates, `NOT_SUPPORTED` listener and provisioning as 14.4).
  - `SaleCancelledEvent` of a sale with an active (non-superseded) auto invoice → full credit note « Annulation de la vente » (actor = the event actor). Idempotency key `source_event = "cancel:<saleId>"`.
  - `SaleCorrectedEvent` → full credit note « Correction de la vente », the invoice gets `superseded_at`, then a new invoice issued and paid from the corrected lines (14.4 mapper and rules). Idempotency key `source_event = "correct:<saleId>:<occurredAt epoch millis>"`, and the credit note + supersede + new invoice share one transaction.
  - To get typed data, `SaleCorrectedEvent` gains `lines` (after-correction, same `Line` record as `SaleCompletedEvent`), `totalAmount`, `clientId` and `paymentMode`, filled by `CorrectSaleService`. Old stored events deserialise with nulls and are skipped with a WARN.
  - No active invoice for the sale (never invoiced, invoicing was off) → nothing.
- **Closes the routed items**: « PAID invoice cannot be cancelled » (a credit note is the path), and the two « sale cancel/correct of an auto-invoiced sale » items.

## Boundaries & Constraints

**Always:**
- Issued invoices are never modified, except the new `superseded_at` marker set only by the correction flow.
- Numbering stays gapless under concurrency and rollback (counter in the same transaction).
- No UUID in PDF, audit or labels.
- French messages.
- Prod-safe additive migration (V4, lazily applied).
- Invoicing still depends only on `shared` + `crm :: api`.
- All backend packages pass one by one, plus prod-classpath; the touched Flutter audit tests pass.

**Never:**
- No WhatsApp sending of credit notes.
- No invoicing UI (15.5).
- No refund or payment tracking on credit notes.
- No DGI e-invoicing.
- No deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected | Error |
|---|---|---|---|
| Full credit note on PAID | owner, reason | `AV-yyyy-000001`, totals = invoice totals, invoice `fullyCredited` true, number/content unchanged | — |
| Partial | `[{position:1, quantity:1}]` of a 3-qty line | amounts for 1 unit; `creditedTtc` updated | — |
| Remaining exact | 3 partial credit notes of 1 unit each on a 3-qty line with odd VAT rounding | sum of credit notes == line TTC exactly | — |
| Over-quantity | quantity > remaining | — | 422 `CREDIT_NOTE_INVALID` |
| Already fully credited | any | — | 409 |
| Draft / cancelled invoice | any | — | 409 |
| Employee | POST | — | 403 |
| Invoicing READ_ONLY | POST | — | 403 `MODULE_READ_ONLY` |
| Cancel ISSUED with a credit note | `POST /invoices/{id}/cancel` | — | 409 |
| Counter per year | first credit note of a year | `AV-<yyyy>-000001`, independent of `FAC` | — |
| PDF | GET pdf | « AVOIR », number, invoice reference, negative totals | — |
| Sale cancelled | auto-invoiced COMPLETED sale cancelled | 1 full credit note « Annulation de la vente »; replay → still 1 | — |
| Sale corrected | auto-invoiced sale, qty 2 → 3 | credit note on the old invoice, old invoice superseded, new PAID invoice with the new total, `?saleId=` returns the new one; replay → no extra document | — |
| Sale never invoiced | cancelled sale without invoice | nothing | — |

</intent-contract>

## Code Map

Paths are relative to `keevo/backend/src/` unless stated otherwise.

**Invoice domain and persistence to mirror**
- `main/java/com/keevo/invoicing/invoice/domain/model/Invoice.java` -- lines, totals, VAT snapshot, status, transitions; `cancel` must refuse when credit notes exist.
- `VatCalculator.java` -- line amount rules.
- `main/java/com/keevo/invoicing/invoice/adapter/out/persistence/JdbcInvoiceRepository.java` -- the counter upsert pattern (`invoice_counters`, gapless) and the targeted UPDATE per transition.
- `main/resources/db/modules/invoicing/V1__invoicing.sql`, `V2__…`, `V3__invoice_source_sale.sql` (unique index to replace).

**Use cases and listeners**
- `InvoiceLifecycleUseCase.java` -- pattern for a numbered transition with events.
- `InvoiceSaleUseCase.java` + `SaleInvoiceMapper.java` + `adapter/in/event/SaleInvoicingListener.java` (14.4) -- bridge gates, `NOT_SUPPORTED` listener, provisioning, idempotency; reuse the mapper for the corrected sale.
- `InvoiceQueryUseCase` / `InvoiceController` / `InvoiceResponseDto` -- the invoice view.

**PDF**
- `InvoicePdfRenderer` + `main/resources/pdf/templates/invoice.html` (14.2) -- add the credit-note variant (separate template `credit-note.html` sharing `layout.html`).

**Events and audit**
- `main/java/com/keevo/shared/event/catalog/invoicing/*` -- existing invoice events.
- `shared/infrastructure/web/AuditEventListener.java` + `AuditLabels` + `ErrorCode` + `GlobalExceptionHandler` (French message for `CREDIT_NOTE_INVALID`).
- `keevo/contracts/export-datasets.json` if audit labels are part of the contract (regenerate with `-Dexport.contract.write=true`).
- App mirror: `keevo/app/lib/features/audit/domain/audit_labels.dart`, `presentation/audit_presenter.dart` + their tests; bump `keevo/app/pubspec.yaml`.

**Sale events**
- `main/java/com/keevo/shared/event/catalog/sale/SaleCancelledEvent.java`.
- `SaleCorrectedEvent.java` -- to enrich.
- `main/java/com/keevo/commerce/sale/application/service/CorrectSaleService.java:131` -- publisher.
- `SaleCompletedLines.java` (14.4) -- helper to reuse.
- `RegistryEventSerializationTest` / `CatalogueEventPayloadTest` -- fixtures.

**Manifest**
- `main/resources/keevo/modules/invoicing/module.json` + `ClasspathModuleManifestsTest`.

## Tasks & Acceptance

**Execution:**
- `V4__credit_notes.sql` + `CreditNote` domain + `JdbcCreditNoteRepository` + counter -- storage and numbering; unit tests for the amounts (full, partial, remaining-exact, refusals).
- `IssueCreditNoteUseCase` + `CreditNoteController` + DTOs + `Invoice.cancel` guard + invoice view fields -- the manual flow. Full-context tests for the manual rows of the matrix.
- `CreditNotePdfRenderer` + `credit-note.html` -- the PDF; a test extracts the text (« AVOIR », number, invoice reference).
- `CreditNoteIssuedEvent` + audit + labels + `ErrorCode`/handler + app mirror + pubspec bump -- events and audit.
- `SaleCorrectedEvent` enrichment + `CorrectSaleService`; `SaleCreditNoteListener` (cancel/correct) reusing the 14.4 gates and mapper -- the automatic flow. Full-context tests through `POST /api/v1/sales/{id}/cancel` and the correction endpoint, with replay.
- Manifest 1.3.0 + manifest tests; `docs/entitlements/endpoints.md` regenerated.
- `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` -- tick the 14.7 routed items.

**Acceptance Criteria:**
- Given a PAID invoice, when the owner issues a credit note for it, then a numbered credit note is created, the invoice keeps its number and content, and the totals net to the credited amount (Interim AC).
- Given pos and invoicing active and an auto-invoiced sale, when the sale is cancelled or corrected, then the accounting documents follow automatically (credit note, and for a correction a new invoice) exactly once.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 33 findings — high 1, medium 6, low 17, false 9, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent 1) No merchant-facing surface — the story's UI is Epic 15; routed explicitly: credit-note screens, list endpoint and export dataset → 15.5.
  - `[false]` `[reject]` (intent 2) « negative totals » stored positive — spec decision: amounts positive, sign is presentation (PDF « − »); the API names the document an « avoir ».
  - `[false]` `[reject]` (intent 3) PAID status unchanged after a full credit note — spec decision (status unchanged, `fullyCredited` flag); invoice content and status immutable by design.
  - `[low]` `[reject]` (intent 4) No explicit net field — `totalTtc − creditedTtc` from two exposed fields.
  - `[false]` `[reject]` (intent 5) `superseded_at` mutation beyond the story — explicitly carved out in the spec; the routed text allows « full credit + new invoice ».
  - `[false]` `[reject]` (intent 6) scope additions (PDF, event fields) — required by the spec's decisions.
  - `[medium]` `[patch]` (verification-gap) Rollback branch of a refused corrected invoice untested — fixed: full-context case (no credit note, no supersede, counter unchanged, publication completed); WARN now carries the reason.
  - `[medium]` `[patch]` (verification-gap, blind) Manifests do not declare `sale.cancelled`/`sale.corrected` — fixed: invoicing consumes, pos publishes (pos 1.4.0), tests updated.
  - `[medium]` `[patch]` (verification-gap, edge, blind) Corrected sale after a manual full credit gets no active invoice — fixed: supersede without a new credit note, then issue the new invoice.
  - `[high]` `[patch]` (edge, blind) Async reordering: cancel before the auto invoice exists → PAID invoice for a cancelled sale; correction before it → stale original lines invoiced; older correction after a newer one → stale lines — fixed: `V5__sale_invoice_state.sql` per-sale state (cancelled / last correction), bridge skips cancelled sales, correction with no invoice issues it from corrected lines, stale corrections skipped; tests for the three orders.
  - `[low]` `[reject]` (edge) Mapper rejects corrected lines → old invoice stays — the mapper only rejects totals inconsistent with their lines (corrupt event); WARN logged.
  - `[low]` `[reject]` (edge) invoicing READ_ONLY / pos off during cancel → invoice stays uncredited — same gates as 14.4 by design; the owner can still issue a manual credit note once writable.
  - `[medium]` `[patch]` (edge, blind) `fullyCredited` DTO rule differs from the domain — fixed: the DTO exposes the domain predicate.
  - `[medium]` `[patch]` (edge) Zero-amount credit note consumes an AV number — fixed: refused 422 before numbering.
  - `[false]` `[reject]` (edge claim) `fullyCredited` formula differs from the spec text for 0-TTC invoices — superseded by the domain-predicate patch.
  - `[false]` `[reject]` (edge claim) « exactly once » broken by reorders — superseded by the ordering patch (high).
  - `[low]` `[patch]` (blind) Superseded invoices indistinguishable in the list — fixed: `supersededAt` exposed.
  - `[low]` `[patch]` (blind) Request quantity not bean-validated — fixed: `@NotNull @Positive @Digits(9,3)`.
  - `[low]` `[patch]` (blind) Number allocation relies on a caller transaction only by Javadoc — fixed: `@Transactional(MANDATORY)`.
  - `[low]` `[reject]` (blind) No credit-note list endpoint / export — routed to 15.5 with the screens.
  - `[low]` `[reject]` (blind) No test for gapless numbering on rollback — same counter pattern as invoices (14.1 tests); the rollback case added above also asserts the counter.
  - `[low]` `[reject]` (blind) No READ_ONLY listener gate test — gate shared with 14.4 (tested there).
  - `[low]` `[reject]` (blind) Concurrent manual + automatic credit notes not tested — both lock the invoice row (`findByIdForUpdate`); concurrent replays already tested.
  - `[low]` `[reject]` (blind) Counter column named `last_value` vs `last_number` — cosmetic; renaming would need another migration.
  - `[low]` `[reject]` (blind) No CHECK `ttc = ht + vat` — computed by `VatCalculator`; same as the invoices table.
  - `[low]` `[reject]` (blind) Dead CSS / no shared layout in `credit-note.html` — `invoice.html` has the same standalone structure; cosmetic.
  - `[low]` `[reject]` (blind) FQN type, long Javadoc line, assignment order — style only.
  - `[low]` `[reject]` (blind) Audit `detail` context generic — label « Détail » is French and mirrored in the app contract.
  - `[low]` `[reject]` (blind) Out-of-order corrections — merged into the high ordering patch (counted there).
  - `[false]` `[reject]` (blind) Manual credit notes on bridge-owned invoices should be blocked — allowed on purpose (the owner may correct any issued invoice); the correction interaction is handled by the supersede patch.
  - `[low]` `[reject]` (blind) Refused correction leaves no audit trace — WARN with reason now logged; a refused corrected content implies a corrupt event.
  - `[false]` `[reject]` (verification-gap other) mapper-failure path test — the mapper rejects only corrupt totals (unit-tested in 14.4).
  - `[low]` `[reject]` (edge) older correction retried — counted with the high ordering patch.

## Verification

**Commands** (from `keevo/backend`, one package at a time):
- `mvn -q -o test -Dtest='com.keevo.<pkg>.**' -DforkCount=1 -DargLine=-Xmx1536m -Dsurefire.failIfNoSpecifiedTests=false` for invoicing, commerce, shared, architecture, acceptance, then every other package -- expected: 0 failures.
- `mvn -q -o -Pprod-classpath test -Dtest='com.keevo.architecture.**'` -- expected: rc 0.
- From `keevo/app`: `flutter test test/features/audit` and `flutter test test/core/export` (or the folder holding `local_export_contract_test.dart`) -- expected: all pass.

**Manual checks:**
- Live on dev, kv_zggt7g:
  - credit note on `FAC-2026-000005` → `AV-2026-000001`, PDF inspected;
  - cancel a fresh auto-invoiced sale → automatic credit note.

## Auto Run Result

Status: done

**Summary.** Credit notes (« avoirs ») in their own gapless series `AV-<yyyy>-<000001>`, full or partial by line, against ISSUED/PAID invoices.
- **Owner endpoint:** `POST /api/v1/invoices/{id}/credit-notes` (owner only, `INVOICE_MANAGE`), plus `GET /api/v1/credit-notes/{id}` and its `/pdf` (« AVOIR », invoice reference, amounts with « − »).
- **Invoice view:** gains `creditedTtc`, `fullyCredited` (domain rule on remaining quantities), `creditNotes` and `supersededAt`. An ISSUED invoice with a credit note can no longer be cancelled.
- **Bridge, cancelled sale:** gets a full credit note « Annulation de la vente ».
- **Bridge, corrected sale:** gets a full credit note « Correction de la vente », then the old invoice is superseded and a new PAID invoice is issued.
- **Ordering:** per-sale state (V5) serialises deliveries and stops reordering from issuing invoices for cancelled sales or stale lines.
- **Events and audit:** `CreditNoteIssuedEvent`, audited « Avoir émis »; app labels mirrored.

**Files.**
- invoicing: `CreditNote`, `CreditNoteRequestDto`/`ResponseDto`, `CreditNoteController`, `IssueCreditNoteUseCase`, `CreditNoteQueryUseCase`, `JdbcCreditNoteRepository`, `CreditNotePdfRenderer` + `pdf/templates/credit-note.html`, `SaleCreditNoteListener` + `SaleCreditNoteUseCase`, `SaleInvoiceStateRepository` (+ JDBC); `Invoice`/`InvoiceLifecycleUseCase`/`InvoiceSaleUseCase`/`InvoiceQueryUseCase`/`JdbcInvoiceRepository`/`InvoiceResponseDto` updated; `V4__credit_notes.sql`, `V5__sale_invoice_state.sql`.
- shared: `CreditNoteIssuedEvent`, enriched `SaleCorrectedEvent`, `ErrorCode`, `GlobalExceptionHandler`, `AuditEventListener`, `AuditLabels`.
- commerce: `CorrectSaleService` fills the corrected-event fields.
- Manifests: invoicing 1.3.0 (`publishes credit-note.issued`, `consumes sale.completed/cancelled/corrected`), pos 1.4.0 (`publishes` the three).
- Contracts and docs: `keevo/contracts/export-datasets.json` and `docs/entitlements/endpoints.md` regenerated.
- App: `audit_labels.dart`, `audit_presenter.dart` + test; pubspec 2.0.7+79.
- Epics doc: 14.7 routed items ticked; credit-note app screens + list/export → 15.5.

**Review.** 33 findings: 10 patched; 0 deferred; 23 rejected (triage log).
- High: async reordering issued invoices for cancelled sales or stale lines.
- Medium: rollback untested, manifests, correction after a manual credit, `fullyCredited` rule, zero-amount credit note.
- Low: `supersededAt`, request validation, MANDATORY transaction.

Follow-up review recommended: **true**. The unverified risk is the V5 per-sale row lock under real offline-sync bursts, together with the new correction-without-invoice behaviour: a sale never invoiced gets an invoice when corrected.

**Verification.**
- After patches, every backend package passed one by one with 0 failures: invoicing 128, commerce 260, shared 1 045, architecture 32, acceptance 4, sync 493, reporting 403, messaging 168, crm 158, catalog 247, inventory 376, identity 446, admin 517, store 60, subscription 70, feedback 52. Prod-classpath rc 0. Flutter audit + export: 244 passed.
- Live (dev kv_zggt7g, noop WhatsApp):
  - credit note on `FAC-2026-000005` → `AV-2026-000001` (2 900); PDF text shows « AVOIR N° AV-2026-000001 », « Avoir sur la facture FAC-2026-000005 du 30/10/2026 », « Motif : Geste commercial », « −2 900 »; invoice still PAID, `fullyCredited` true; second credit note → 409;
  - auto-invoiced sale (`FAC-2026-000007`) cancelled → `AV-2026-000002` « Annulation de la vente » 3 000;
  - after the patches: boot applied invoicing V5 lazily; a new sale was auto-invoiced `FAC-2026-000008`; `tenant_modules` 331 throughout.

**Residual risks.**
- One `sale_invoice_states` row per sale seen by the bridge (no cleanup).
- Dev data: 3 more test sales, 2 credit notes and 2 auto invoices on kv_zggt7g.
