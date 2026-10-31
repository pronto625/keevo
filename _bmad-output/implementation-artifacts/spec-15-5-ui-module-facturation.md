---
title: '15.5 — Invoicing module UI (list, create, detail, PDF, WhatsApp, pay, credit notes, seller settings)'
type: 'feature'
created: '2026-10-31'
status: 'done'
baseline_revision: '340669ca728fc562dae69249e4630396796315ad'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-15-4c-extraction-keevo-invoicing.md'
  - '{project-root}/keevo/app/packages/README.md'
warnings: ['multiple-goals']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The invoicing backend (Epic 14: invoices, numbering, PDF, WhatsApp send, CRM client picker, credit notes, seller fiscal settings) has no app UI beyond the read-only invoice opened from a sale (15.3). A merchant cannot list, create, issue, send, collect or credit an invoice from the app (UX-DR5).

**Approach:** Build the invoicing screens inside the `keevo_invoicing` package (15.4c pattern: routes through `invoicingModuleUi`, ports for HTTP/file sharing, `deferred` screens): list with search and date range, manual creation/edit of drafts, detail with actions (issue, pay with a chosen payment date, send by WhatsApp, PDF preview, cancel, credit note), credit notes per invoice, and the seller fiscal settings screen; plus the small backend additions the screens need (list search/date filters, back-dated payment date). Offline invoice operations are split out to Story 15.5b.

## Boundaries & Constraints

**Always:**
- Online-only screens: when the server cannot be reached, the screens show the existing French offline/error widgets; nothing is queued (15.5b).
- Entry point « Factures » in the owner/employee navigation where other business screens live (settings « Gestion »/« Consultation » tiles pattern), visible only when route `/invoicing` is available (15.2 `isRouteAvailable`); route `/invoicing` (list) mounted by the package (manifest already declares it), `/invoicing/new`, `/invoicing/:id` (existing), `/invoicing/:id/edit`, `/invoicing/settings` — every new route added to the invoicing manifest `routes` + contract file + `kModuleRoutes` (15.2 contract test).
- Actions follow the backend permissions: owner = all; employee = `InvoicingModule.employeePermissions()` (create, view, send — not manage). The app hides cancel, credit note and seller settings for employees; any 403 still renders the French message.
- Client: CRM picker when `GET /api/v1/invoices/clients` answers `crmAvailable: true` (search as you type), otherwise free-text name + phone (14.3 contract).
- Before issuing, show the invoice's `complianceWarnings` (French texts from the server) and let the owner fix the seller settings or confirm.
- Payment: `POST /{id}/pay` gains an optional `paidAt` date (backend: not in the future, not before the issue date; default now) and the app offers a date picker (today by default) + payment method.
- List: backend `GET /api/v1/invoices` gains `search` (number or client name, case-insensitive, trimmed, ≤ 100 chars) and `from`/`to` (issue date, Africa/Douala, inclusive, from ≤ to) — validated with French 400 messages; app shows status filter chips, search field, date range, infinite scroll.
- PDF preview: download `GET /{id}/pdf` (and `/credit-notes/{id}/pdf`) and open it with the existing sharer port (no new PDF viewer dependency); WhatsApp send: `POST /{id}/send` with the client phone prefilled and editable.
- Credit notes (owner, ISSUED/PAID invoice): full, or per line with quantities ≤ remaining (the DTO's `creditNotes`/`creditedTtc` give what is left), reason required; list of the invoice's credit notes with number, amount, date, PDF; badges « Remplacée » (`supersededAt`) and « Entièrement créditée » (`fullyCredited`).
- Seller settings screen (`GET/PUT /api/v1/invoicing/settings`, owner): legal name, address, phone, NIU, RCCM, VAT registered, VAT rate, prices include VAT, payment details — server validation messages shown per field.
- French labels everywhere, amounts in FCFA with the app's formatter, no ids/codes on screen; status labels from the server `statusLabel`.
- Bump `keevo/app/pubspec.yaml` 2.0.13+85 → 2.0.14+86.

**Never:**
- No offline queueing / sync operations for invoices (15.5b).
- No global credit-note list screen nor credit-note export dataset (no screen needs them: credit notes are shown per invoice) — recorded in 15.5b's brief as optional.
- No change to invoice numbering, PDF templates or backend permissions.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Search | `search="fac-2026-0000"`, `from=2026-10-01`, `to=2026-10-31` | invoices matching number or client name, issued in the range, newest first | `from > to` → 400 « La date de début doit précéder la date de fin » |
| Create (CRM on) | picker returns clients | client chosen from CRM; lines; draft saved | unknown client id → 404 French message |
| Create (CRM off) | `crmAvailable:false` | free-text name/phone | — |
| Issue with warnings | draft, seller NIU missing | warnings listed before confirm; owner can open settings | — |
| Pay back-dated | ISSUED, `paidAt` = yesterday | PAID with that date | future date or before issue → 400 French |
| Send | ISSUED, phone edited | « Facture envoyée » + `sendCount` incremented | provider error → French message |
| Credit note partial | PAID, 1 of 3 units on line 2 | AV-… created, remaining updated | qty > remaining → server French message |
| Employee | employee session | no cancel / credit note / settings actions | 403 → French |
| Offline | no network | French offline message, nothing queued | — |

</intent-contract>

## Code Map

- Backend `keevo/backend/src/main/java/com/keevo/invoicing/invoice/adapter/in/rest/InvoiceController.java:105-135` (list: status, saleId, page, size), `:186` pay, `:222` send; `dto/PayInvoiceRequestDto.java` (paymentMethod only); `dto/InvoiceResponseDto.java` (fields incl. `complianceWarnings`, `creditNotes`, `creditedTtc`, `fullyCredited`, `supersededAt`, `statusLabel`); `CreditNoteController.java` (`POST /invoices/{id}/credit-notes` `{reason, lines[{position, quantity}]}`, `GET /credit-notes/{id}[/pdf]`); `InvoicingSettingsController.java` + `dto/InvoicingSettingsDto.java`; `module/InvoicingModule.java:41` employee permissions; repository/query use case behind `queries.list(...)` (add search/date filters with proper SQL params — keyset or offset as today).
- Backend docs: `docs/entitlements/endpoints.md` (generated) and `keevo/contracts/module-routes.json` (generated from manifests, `ModuleRoutesContractFileTest` — regenerate with `-Dmodule.routes.contract.write=true`); invoicing manifest `keevo/backend/src/main/resources/keevo/modules/invoicing/module.json` (`routes`, bump version).
- App package `keevo/app/packages/keevo_invoicing/lib/`: `invoicing_module.dart` (descriptor), `data/remote_invoice_datasource.dart`, `domain/model/invoice.dart`, `presentation/page/invoice_detail_page.dart` (minimal, to extend), `presentation/provider/invoicing_providers.dart`, `presentation/open_sale_invoice.dart`.
- App shell: `keevo/app/lib/core/router/module_routes.dart` (`kModuleRoutes` = contract), `lib/features/settings/presentation/page/settings_page.dart` (tiles + `isRouteAvailable`), `lib/core/modules/registered_modules.dart`.
- `keevo_core`: `widget/app_error_widget.dart`, `widget/deferred_widget.dart`, `module/module_ports.dart`, theme/formatters (FCFA formatting helpers used by POS screens — reuse).

## Tasks & Acceptance

**Execution:**
- backend -- list filters (`search`, `from`, `to`) + validation + tests; `paidAt` on pay + domain rule + tests; manifest routes + version; regenerate contract/endpoints docs.
- `keevo_invoicing` -- data source methods (list with filters, create/update/delete draft, issue, pay, cancel, send, settings get/put, credit note create/get/pdf, clients suggest), models, providers, pages: `InvoiceListPage`, `InvoiceFormPage`, `InvoiceDetailPage` (extended), `CreditNoteFormSheet`, `InvoicingSettingsPage`; routes in the descriptor (deferred).
- app shell -- « Factures » entry (settings/navigation) gated by `isRouteAvailable('/invoicing')`; `kModuleRoutes` updated with the new routes.
- tests -- package widget/provider tests for each I/O row (fake Dio adapter), app entry visibility test, contract tests green.
- `keevo/app/pubspec.yaml` -- 2.0.14+86.

**Acceptance Criteria:**
- Given Invoicing active, when the owner opens « Factures », then they see the list, create an invoice (CRM picker if active, else free text), preview its PDF and send it by WhatsApp.
- Given an issued invoice, when the owner records a back-dated payment or a partial credit note, then the invoice shows « Payée » with that date / the credit note and the remaining amount.
- Given Invoicing inactive, then the « Factures » entry is absent and its routes are guarded (15.2).
- Given touched backend packages and Flutter folders, when tests run one at a time, then all pass.

## Review Triage Log

### 2026-10-31 — Review pass
- verdicts: 41 findings — high 0, medium 6, low 26, false 9, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind/edge) back-dated payment on the issue day can land before `issuedAt` (noon < issue time) — `paidAt = max(noon, issuedAt)`; domain test.
  - `[medium]` `[patch]` (blind/edge) audit trail never records the chosen payment day — `InvoicePaidEvent` carries `paidAt`; audit detail shows « payée le … » in French.
  - `[low]` `[reject]` (blind) search not accent-insensitive — needs the `unaccent` extension across tenant schemas; current behaviour matches the other app searches.
  - `[low]` `[reject]` (blind) no index for the new filters — per-tenant invoice volumes are small (hundreds); revisit with real volumes.
  - `[medium]` `[patch]` (blind/edge) device "today" vs Douala "today" → default payment refused as future — send `paidAt` only when the user picked another day; date bounds computed in Africa/Douala.
  - `[medium]` `[patch]` (blind/edge) credit-note remaining quantity uses floating subtraction (0.3 − 0.1) — compare in thousandths.
  - `[low]` `[reject]` (blind) one request per credit note to rebuild credited quantities — invoices carry very few credit notes; the server re-validates.
  - `[low]` `[patch]` (blind) dead list overloads (`InvoiceQueryUseCase.list(status, saleId…)`, repository default) — removed.
  - `[low]` `[patch]` (blind/vg) missing tests: `escapeLike` `_`/`\`, list provider races/dedupe/end, filter equality, quantity parsing — added.
  - `[low]` `[patch]` (blind) payment method preselected « Espèces » — no preselection, choice required.
  - `[low]` `[patch]` (blind) settings: VAT rate required when not VAT-registered; no range check; duplicated « Factures » tile — rate optional when not registered, 0–100 check, single tile builder.
  - `[low]` `[reject]` (blind) no caps on quantity decimals / max values — the server validates (`@Digits`), French message shown.
  - `[low]` `[patch]` (edge) NUL character in search → 500 — rejected with a French 400.
  - `[low]` `[patch]` (edge) unknown `vatTreatment` crashes the edit dropdown — fall back to standard.
  - `[low]` `[patch]` (edge) credit-note sheet dismissible while saving — not dismissible while saving.
  - `[low]` `[patch]` (edge) `loadMore` result after a filter change merges stale pages — dropped when the filter changed.
  - `[low]` `[patch]` (edge) automatic retry on every scroll tick after a page error — retry only on the explicit « Réessayer ».
  - `[low]` `[reject]` (edge) offset paging may skip an invoice when a draft is deleted meanwhile — pull-to-refresh restores it; keyset paging out of scope.
  - `[low]` `[patch]` (edge) audit event without the paid day — same as blind audit row.
  - `[low]` `[patch]` (edge) paidAt before issuedAt — same as blind row.
  - `[low]` `[patch]` (edge) device today — same as blind row.
  - `[low]` `[patch]` (edge) float credit quantities — same as blind row.
  - `[medium]` `[patch]` (vg) date range wiring screen → provider → datasource untested — widget test asserting `from`/`to` and their removal.
  - `[low]` `[patch]` (vg) back-dated pay through the date picker untested — widget test picking yesterday.
  - `[low]` `[patch]` (vg) infinite scroll dedupe / stale page untested — provider tests.
  - `[low]` `[patch]` (vg) `_` escaping untested — `search=_` assertion.
  - `[low]` `[reject]` (intent) entry point only in Settings — the app's business screens (clients, suppliers, transfers, exports) are reached the same way; 15.8 owns navigation mapping.
  - `[false]` `[reject]` (intent) PDF via share/open sheet instead of an in-app viewer — spec decision (no new PDF dependency); the OS opens the PDF.
  - `[false]` `[reject]` (intent) WhatsApp server-side send — the 14.2 contract.
  - `[false]` `[reject]` (intent) CRM flag only tested with mocked JSON — backend 14.3 tests cover the flag; live check done.
  - `[false]` `[reject]` (intent) credit-note list endpoint/export not added — no screen needs them; routed to 15.5b as optional.
  - `[false]` `[reject]` (intent) settings round trip not tested in this diff — endpoints unchanged (14.1 tests); live GET checked.
  - `[false]` `[reject]` (intent) role enforcement backend not in diff — unchanged 14.1 permissions.
  - `[false]` `[reject]` (intent) no end-to-end test from entry to send — covered piecewise + live API check.
  - `[false]` `[reject]` (intent) entitlements reading (i) — the 15.2 gating is the integration.
  - `[false]` `[reject]` (intent) credit-note PDF opener untested — covered by the opener test patch above? no: kept as low — see next row.
  - `[low]` `[patch]` (intent) credit-note PDF opener / button untested — opener test added.

## Verification

**Commands:**
- `bash /tmp/claude-1000/-home-toor-Project-FreeLance-AI-Keevo/b3540a1e-019a-42f0-9dea-6ba6965d948e/scratchpad/runpkg.sh invoicing` then `shared`, `architecture`, `acceptance` -- expected: 0 failures.
- `cd keevo/app/packages/keevo_invoicing && flutter analyze && flutter test` -- expected: pass.
- `cd keevo/app && flutter analyze lib test` and `bash tool/test_chunked.sh` -- expected: 0 errors/warnings, all chunks pass.

**Manual checks:**
- Live on dev backend (owner kv_zggt7g, WhatsApp provider noop): list with search/date filters, create → issue → pay back-dated → partial credit note via the API calls the app makes.

## Auto Run Result

Status: done

**Summary:** Full invoicing UI in `keevo_invoicing`: list (status chips, search, issue-date range, infinite scroll), draft create/edit/delete (CRM picker when `crmAvailable`, else free text), detail (issue with compliance warnings + « Compléter mes informations », pay with a chosen day and method, WhatsApp send with editable phone, PDF via the share/open port, cancel, credit notes full/per line with PDF and badges « Remplacée » / « Entièrement créditée »), seller settings screen; owner-only actions hidden for employees. « Factures » tile in settings gated by `isRouteAvailable('/invoicing')`. Backend: list `search` + `from`/`to` (French 400s, LIKE escaping, NUL refused), optional `paidAt` (not future, not before issue, never before `issuedAt`; audited as « payée le … »), new error `INVOICE_PAYMENT_DATE_INVALID`, invoicing manifest 1.4.0 with 5 routes (contract regenerated). Offline invoices split to 15.5b. App 2.0.14+86.

**Review:** 41 findings — 6 medium patched (pay before issuedAt, audit payment day, device vs Douala "today", float credit quantities, untested range wiring and back-dated picker), ~18 lows patched, rest rejected with reasons (accent-insensitive search, indexes, per-credit-note requests, quantity caps).

**Follow-up review recommended:** true — six medium patches; unverified risk: the screens on a real device (adb unauthorized) and the WhatsApp send with the real provider (only the noop 503 path was exercised live).

**Verification:** backend invoicing 131 / shared 1050 / architecture 32 / acceptance 4 / reporting 404 — 0 failures; keevo_invoicing analyze clean, 62 tests pass; app analyze 0 errors/warnings, `tool/test_chunked.sh` all chunks pass; `flutter build apk --debug` OK. Live (dev kv_zggt7g, WhatsApp noop): search/date filters + `from > to` French 400; FAC-2026-000009 created → issued → future/before-issue payment refused in French → paid today; partial credit note AV-2026-000003; `search=_` → 0; send → 503 « L'envoi WhatsApp n'est pas disponible pour le moment » (noop provider); settings GET OK.
