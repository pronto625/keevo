---
title: '20-3 Quotes (kind = QUOTE) — create, edit with conflict check, cancel, convert to an issued invoice'
type: 'feature'
created: '2026-11-06'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-2-crm-etendu-clients-par-etablissement.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Bref merchants make quotes, edit them, then validate them into invoices (FUS-FR18). Keevo invoicing only knows invoices (DRAFT → ISSUED → PAID / CANCELLED) with a legal gapless `FAC-` numbering and a DOCUMENTS usage quota consumed at issue. A quote must never take a legal number nor consume the quota; its conversion must.

**Approach:** Add a `kind` (`INVOICE` | `QUOTE`) to `invoices` (invoicing module V8). A quote lives in status `DRAFT` (editable), and ends `CONVERTED` (linked to the new invoice) or `CANCELLED` (no number). Conversion creates a **new** invoice row with the quote's client and lines, issued through the existing issue sequence (lock, quota DOCUMENTS, gapless `FAC-` number, `issue_date` = conversion date in WAT), and links both rows, in one transaction, idempotently. Edits accept an optional `expectedUpdatedAt` (409 on mismatch). REST + sync (`CONVERT_QUOTE`) + pull + PDF + audit follow.

## Boundaries & Constraints

**Always:**
- Additive for V1 apps (FUS-NFR1): an absent `kind` means `INVOICE`; existing request bodies and responses keep working (responses only gain `kind`, `quoteReference`, `convertedInvoiceId`, `convertedAt`, `sourceQuoteId`); `GET /api/v1/invoices` without `kind` returns invoices only (unchanged list for V1); `kind=QUOTE` returns quotes only; `kind=ALL` returns both. The sync pull carries quotes with `kind` (V1 apps ignore unknown fields; any action they attempt on a quote is refused by the server with the codes below).
- A quote never has `number`, `issue_date`, `issued_at`; never consumes `invoice_counters` nor the DOCUMENTS quota; never publishes `InvoiceIssuedEvent`. Its display reference is `quoteReference = "DV-" + first 8 hex chars of its id, upper case` (Bref parity: computed, not stored, no counter).
- Quote lifecycle: create (`kind: "QUOTE"`) → `DRAFT`; edit/delete as a draft (`assertEditable`, soft delete unchanged); `cancel` a DRAFT quote → `CANCELLED` (reason required ≤ 500, like invoices, `cancelled_at` set, no number); `convert` a DRAFT quote → `CONVERTED`. Issue, pay, send, credit-note on a quote → 409 `INVOICE_INVALID_TRANSITION` (French message: « Un devis doit être converti en facture »). Edit/cancel/convert of a CONVERTED or CANCELLED quote → 409 `INVOICE_NOT_EDITABLE` / `INVOICE_INVALID_TRANSITION` as today's state rules.
- Conversion (`POST /api/v1/invoices/{quoteId}/convert`, optional body `{invoiceId}`; sync `CONVERT_QUOTE {quoteId, invoiceId}`), one transaction: lock the quote (`FOR UPDATE`); if it is already `CONVERTED` → return the existing invoice (200, idempotent, no second number, no second quota use); if not DRAFT/QUOTE → 409; quota DOCUMENTS `assertAndIncrement` (counted) — `QUOTA_EXCEEDED` leaves the quote untouched; insert the new invoice (`kind INVOICE`, given `invoiceId` or a new UUID, client and lines copied, `source_quote_id = quoteId`) and issue it through the existing issue path (seller snapshot, VAT, number, `issue_date` = today WAT, `InvoiceIssuedEvent`); set quote `status CONVERTED`, `converted_invoice_id`, `converted_at`. A unique partial index on `source_quote_id` makes a concurrent double conversion fail instead of producing two invoices. Permission: same as issue (`INVOICE_SEND`), entitlement `invoicing`.
- Optimistic edit check: `PUT /api/v1/invoices/{id}` and sync `UPDATE_INVOICE` accept optional `expectedUpdatedAt` (ISO instant); when present and different from the locked row's `updated_at` (compared at microsecond precision) → 409 `OPTIMISTIC_LOCK` (existing code, French message) with no write; absent → today's behaviour. Applies to quotes and draft invoices alike.
- An issued invoice stays non-editable (existing `assertEditable` refusal kept and tested).
- PDF of a quote: title « DEVIS DV-XXXXXXXX », no legal number line, mention « Devis — en attente de validation », file name `devis-DV-XXXXXXXX.pdf`. Quotes cannot be sent (WhatsApp/link) in this story.
- Audit (French labels, no UUIDs): quote created, converted (« Devis converti en facture FAC-… »), cancelled; labels mirrored in `keevo/contracts/export-datasets.json` and the Flutter test-client mirror.
- Sync contract `keevo/contracts/invoice-sync-payloads.json` gains: create quote, update with `expectedUpdatedAt`, convert, cancel quote; the backend contract test reads them.
- FUS-NFR11: list by kind uses an index `(kind, status, created_at)`; conversion is a constant number of queries (no per-line query loop beyond today's issue path).

**Never:**
- No change to the `FAC-` numbering, the counter table, POS automatic invoices, credit notes, or reporting rules; no quote numbering series.
- No public mirror (invoice tables are tenant-only, no JPA entity). No dashboard API. No deliverable Flutter feature (test-client mirrors only, `pubspec` bump if touched).
- No in-place mutation of a quote into an invoice (Bref does that; Keevo keeps two rows).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create quote | `kind:"QUOTE"`, client, lines | 201 DRAFT quote, no number, `quoteReference DV-…`, counter and quota untouched | — |
| V1 create | body without `kind` | invoice draft as today | — |
| Edit with stale check | `expectedUpdatedAt` ≠ row | 409 `OPTIMISTIC_LOCK`, nothing written | — |
| Edit without check | no `expectedUpdatedAt` | applied | — |
| Convert | DRAFT quote, quota available | 201/200 invoice ISSUED, next gapless `FAC-` number, `issue_date` today WAT, quote CONVERTED + links, quota +1 | — |
| Convert replay | quote already CONVERTED (REST or `CONVERT_QUOTE` replay) | same invoice returned, no new number, quota unchanged | — |
| Convert over quota | DOCUMENTS exhausted | quote unchanged, no number taken | 403 `QUOTA_EXCEEDED` |
| Issue/pay/send a quote | DRAFT quote | refused | 409 `INVOICE_INVALID_TRANSITION` |
| Cancel quote | DRAFT quote + reason | CANCELLED, no number | missing reason 400 |
| Convert cancelled quote | CANCELLED | refused | 409 |
| Edit issued invoice | ISSUED | refused as today | 409 `INVOICE_NOT_EDITABLE` |
| Lists | default / `kind=QUOTE` / `kind=ALL` | invoices only / quotes only / both | bad kind 400 |

</intent-contract>

## Code Map

Paths under `keevo/backend/src/main/`; `R` = `resources/db/modules/invoicing`, `J` = `java/com/keevo/invoicing/invoice`.
- `R/V1__invoicing.sql:34-73` -- `invoices` (status CHECK `:37-38` `IN ('DRAFT','ISSUED','PAID','CANCELLED')`; row CHECKs `:70-72` unnamed: non-DRAFT requires number + issue_date + issued_at; CANCELLED requires cancelled_at + reason), `invoice_items` `:78-91`, `invoice_counters` `:94-97`. V2–V7 add columns (V7 `deleted_at`). New `R/V8__invoices_kind_quote.sql`: `kind`, `converted_invoice_id`, `converted_at`, `source_quote_id`, status CHECK + row CHECK relaxed for `kind='QUOTE'` (drop the unnamed constraints by catalog lookup, idempotent, `SET LOCAL lock_timeout`), index `(kind, status, created_at)`, unique partial index on `source_quote_id`. Keep `TenantSchemaProvisioner.SYNC_KEYSET_INDEXES` untouched.
- Domain: `J/domain/model/InvoiceStatus.java:9-13` (labels; add CONVERTED « Converti »), `J/domain/model/Invoice.java` (`draft()` :91, `withDraftContent` :102, `assertEditable` :110-114, `assertIssuable` :138, `issue()` :153, `pay` :177, `cancel` :216-226, `assertSendable` :306) -- add kind, quote rules, convert.
- Persistence: `J/domain/port/out/InvoiceRepository.java` (`Filter` :85), `J/adapter/out/persistence/JdbcInvoiceRepository.java` (status-guarded updates :128/:145/:198/:158/:166 + `expectOne`; list SQL :203-239; counter upsert :244-253; `findByIdForUpdate` :65; `delta()` :263-289).
- Use cases: `J/application/usecase/ManageDraftInvoiceUseCase.java` (create :208-232 idempotent, update :235, delete :246), `J/application/usecase/InvoiceLifecycleUseCase.java` (issue :79-102: lock, assertIssuable, quota :93, number :59-61 `FAC-%04d-%06d`, issue_date WAT :90, `InvoiceIssuedEvent`) -- conversion reuses this path inside one transaction.
- REST: `J/adapter/in/rest/InvoiceController.java` (endpoints table: POST :99, GET list :115-153 + `parseStatus` :280, PUT :182, DELETE :191, issue :200, pay :213, cancel :227, pdf :238, send :249; `@RequiresPermission` + `@RequiresEntitlement(InvoicingModule.ID)`), DTOs `J/adapter/in/rest/dto/InvoiceRequestDto.java:21-27`, `CancelInvoiceRequestDto`, `InvoiceResponseDto.java:30-60`. Permissions `J/../module/InvoicingModule.java:28-31`.
- Errors: `shared/domain/exception/ErrorCode.java:92-93` (OPTIMISTIC_LOCK 409), `:186-196` (INVOICE_*), `shared/infrastructure/web/ErrorMessages.java:131-140,153`, `GlobalExceptionHandler.java:179-190`.
- Concurrency pattern to mirror: `commerce/sale/adapter/out/persistence/jpa/SaleSpringRepository.java:109-112`, `ValidateSaleService.java:239-247`. Bref: `/home/toor/Project/FreeLance/AI/api-bref/src/utils/assert-fresh-update.ts:12-19`.
- Quota: `shared/capability/entitlement/UsageQuotaPort.java:52/58`, impl `subscription/plan/application/service/UsageQuotaService.java:72-84` (`Propagation.MANDATORY`).
- Sync: `J/adapter/in/sync/InvoiceSyncHandler.java` (ops :53-57, permission map :60-65, validate :96-102, switch :110-130), `sync/sync/application/strategy/ConflictStrategyRegistry.java` (invoice ops are AppendOnly — keep), `J/adapter/in/sync/InvoiceDeltaProvider.java` (rows = `InvoiceResponseDto` :87-91), contract `keevo/contracts/invoice-sync-payloads.json`, test `src/test/java/com/keevo/invoicing/invoice/adapter/in/sync/InvoiceSyncHandlerTest.java:110,246-249`.
- PDF: `J/adapter/out/pdf/InvoicePdfRenderer.java:58-60`, file name `J/application/usecase/InvoiceDocumentUseCase.java:101-103`.
- Audit: `shared/infrastructure/web/AuditEventListener.java:935-990`, `shared/infrastructure/export/AuditLabels.java:107-112,137`, events `shared/event/catalog/invoicing/`, contract `keevo/contracts/export-datasets.json:1074-1078,1132`, test `src/test/java/com/keevo/reporting/export/ExportContractFileTest.java`.
- Bref reference (read-only): `api-bref/src/invoice/domain/invoice.ts:15-31` (QUOTE/SALE, `DV-` display ref), `invoice.controller.ts:257-260,280-294`, `invoice.service.ts:200-245,558-595`, `invoice-pdf.service.ts:82-101`.

## Tasks & Acceptance

**Execution:**
- `R/V8__invoices_kind_quote.sql` -- columns, constraints, indexes as above.
- Domain (`Invoice`, `InvoiceStatus`, a `InvoiceKind` enum, quote reference helper) -- quote rules and `convert`, pure and unit-tested.
- Repository port + JDBC adapter -- kind in insert/select/list filter, convert writes (status-guarded), `expectedUpdatedAt` compare under lock.
- Use cases -- create quote, update with optional check, cancel quote, new `ConvertQuoteUseCase` (single transaction, idempotent).
- REST -- `kind` on create, `kind` list filter (default INVOICE, `QUOTE`, `ALL`), `expectedUpdatedAt` on PUT, `POST /{id}/convert`, response fields; PDF branch; `docs/entitlements/endpoints.md` regenerated.
- Sync -- `kind` on CREATE_INVOICE, `expectedUpdatedAt` on UPDATE_INVOICE, `CONVERT_QUOTE` (permission as ISSUE), contract file entries + test.
- Events/audit/labels + export contract + Flutter test-client mirror (bump `pubspec.yaml` if changed).
- Tests: unit (domain rules, quote reference), use-case (each matrix row), REST controller, sync handler (incl. replay), Postgres integration (V8 on an existing schema with ISSUED/CANCELLED invoices: constraints still enforced for invoices, quotes allowed; gapless numbering across a conversion and an ordinary issue; concurrent double conversion → one invoice).
- Spec section `## Mobile (React Native — dépôt AI/bref)` and sprint key `m20-3-devis` (finalize).

**Acceptance Criteria:**
- Given a tenant with the invoicing module active, when the matrix scenarios run over REST and sync, then every row holds and responses are supersets of today's.
- Given existing tenants, when the new build boots, then V8 applies on every schema without altering existing invoices, and the full backend suite passes package by package.
- Given a live local backend on keevo_dev (WhatsApp noop, FCM/Expo off), when a quote is created, edited with a stale `expectedUpdatedAt`, converted twice and its PDF downloaded, then 409 / one invoice with the next `FAC-` number / « DEVIS » PDF are observed.

## Design Notes

Pattern: **state machine on the aggregate + application service for the cross-row operation**. `Invoice` gains kind-aware guards (a quote cannot issue/pay/send; an invoice cannot convert); `ConvertQuoteUseCase` orchestrates lock → quota → create+issue → link in one transaction, reusing the issue path so numbering and quota stay single-sourced. Idempotency rests on the quote's `CONVERTED` state read under the row lock, with the unique `source_quote_id` index as the last guard.

Cost: convert = 1 locked read + 1 quota upsert + 1 counter upsert + invoice insert + items insert (batch) + 2 updates — constant; list by kind = one indexed query with the existing pagination; edit check adds no query (compare on the already-locked row).

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='com/keevo/invoicing/**/*Test*'` -- expected: pass
- `cd keevo/backend && mvn -o -q test -Dtest='com/keevo/reporting/export/**/*Test*,com/keevo/sync/**/*Test*,com/keevo/architecture/**/*Test*'` -- expected: pass
- Full suite package by package (`run-pkgs-wait.sh`) -- expected: 0 failures
- Live: local backend on keevo_dev with WhatsApp noop, FCM/Expo off; curl create/edit/convert/pdf on a test tenant with invoicing activated -- expected: as in the AC.
