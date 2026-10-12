---
title: 'v1s-18-3 — PDF templates (EOD, inventory, stock, sales) + PDF in the app Export screen'
type: 'feature'
created: '2026-10-12'
status: 'done'
baseline_revision: '84131bbba04f9f035a3788f256ba8070d990f0ca'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-18-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-v1s-18-1-export-module-foundation.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** FR95 asks for branded PDF documents (Fin de journée, Rapport d'inventaire, État de stock, Liste des ventes), but the 18-1 renderer registry has no PDF renderer (`POST /api/v1/exports` with `PDF` → 400 `EXPORT_FORMAT_UNSUPPORTED`) and the app shows PDF as a disabled « Bientôt » tile guarded in three places.

**Approach:** Add a document-style `PdfExportRenderer` (Thymeleaf XHTML → OpenHTMLtoPDF 1.1.22, embedded Liberation Sans) to the 18-1 registry. A PDF template is selected by the single requested dataset key (`day_closures` → Fin de journée, `inventory_counts` → Rapport d'inventaire, `stock_levels` → État de stock, `sales` → Liste des ventes), so the request contract, jobs table, audit and gating stay unchanged. The app enables the PDF tile: Free → offer sheet; Premium → a single-choice template list replaces the dataset checkboxes.

## Boundaries & Constraints

**Always:**
- PDF stays Premium (`EXPORT_PDF`, existing gate in `ExportService`); OWNER-only; tenant scoping through the existing providers / tenant-routed `EntityManager`.
- EOD figures come from `EndOfDayReportBuilder.build(storeId, ClosureWindow, …)` with the closure's window computed by ONE shared factory `ClosureWindow.forClosure(closure, previous)` that `CloseDayService` also uses (same window as the WhatsApp EOD of that closure).
- Bounded memory: a PDF holds at most `keevo.export.pdf-max-rows` (default 2000) data rows, and the EOD template at most `keevo.export.pdf-max-closures` (default 31) closures; a larger request is refused BEFORE the rate-limit slot with 422 `EXPORT_PDF_TOO_LARGE` (`details.limit`, `details.rows`) and a clear French message; rows arriving beyond the limit during rendering are not buffered (render stops reading and prints « Document limité aux N premières lignes »).
- A4 portrait, brand header on every page (business name, store or « Toutes les boutiques », period or « Situation au … », « Généré le dd/MM/yyyy à HH:mm » WAT via injected `Clock`), table headers repeated on each page, « Page x / y » footer, amounts as integer XAF grouped with a non-breaking space (`12 500 XAF`), dates WAT, accents correct (embedded font, subset).
- Templates are versioned classpath XHTML (`src/main/resources/pdf/templates/*.html`), escaped by Thymeleaf `th:text` (never `th:utext` on data).
- App: every change under `keevo/app/` bumps `pubspec.yaml` version (1.1.34+33 → 1.1.35+34); no hard-coded colours (AppTheme / ColorScheme tokens).

**Never:**
- No new request field, no Flyway migration, no change to the CSV/XLSX output or the 18-2 column schema.
- No generic "any dataset" PDF: a PDF request for another dataset, or for more than one dataset → 400 `EXPORT_FORMAT_UNSUPPORTED` (`details.format=PDF`, `details.dataset`).
- No logo rendering: no logo exists in the store/tenant model (header leaves it out; recorded decision).
- No user-editable templates; no Spring MVC Thymeleaf view resolver (core `org.thymeleaf:thymeleaf` with a standalone `TemplateEngine` only).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| EOD | Premium, `datasets=[day_closures]`, store + day of one closure | 200 `application/pdf`, one section per closure with the builder's figures (sales count, revenue, cash, MoMo, avg basket, pending, low stock, top 3, employees) | — |
| Sales | `datasets=[sales]`, period | table of sales + totals by status and by payment mode, grand total | — |
| Stock | `datasets=[stock_levels]` | grouped by store, value per store + grand total, header « Situation au … » | — |
| Inventory | `datasets=[inventory_counts]`, period | one block per session: theoretical / counted / gap, non-zero gaps highlighted, session totals | — |
| Empty | any template, no rows | valid one-page PDF with header and « Aucune donnée pour cette période » | — |
| Too large | rows > limit (or closures > 31) | nothing streamed, no quota used | 422 `EXPORT_PDF_TOO_LARGE` |
| Wrong dataset | PDF + `products`, or 2 datasets | — | 400 `EXPORT_FORMAT_UNSUPPORTED` |
| Free plan | FREE + PDF | — | 403 `PLAN_FEATURE_REQUIRED` (`EXPORT_PDF`); app opens the offer sheet before sending |

</intent-contract>

## Code Map

- `keevo/backend/pom.xml` -- `org.thymeleaf:thymeleaf` (Boot-managed 3.1.3) and `io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.22` ALREADY added and resolved in `~/.m2` (pdfbox 3.0.3 transitively → use it in tests for `PDFTextStripper`).
- `reporting/export/domain/port/out/ExportRenderer.java` -- row-stream strategy; add sub-interface `DocumentExportRenderer` (same package) receiving `(OutputStream, List<ExportDatasetProvider>, ExportFilter, AtomicLong progress)` plus `validate(providers)` and `checkSize(providers, filter, countedRows)`.
- `reporting/export/application/service/ExportStreamingWriter.java:51` -- `write(...)`: delegate to `DocumentExportRenderer.render` when the renderer is one (used by both the direct path and `ExportJobRunner`).
- `reporting/export/application/service/ExportService.java:117-150` -- after `rendererRegistry.require`: for a document renderer, `validate` then count + `checkSize` BEFORE `rateLimiter.acquireRequest`; reuse that count for the direct/background decision.
- `reporting/export/application/service/ExportRendererRegistry.java` -- discovers the new bean unchanged.
- `reporting/export/adapter/out/render/{CsvExportRenderer,XlsxExportRenderer,ExportCells}.java` -- conventions to mirror (WAT, `toLong`, `NonClosingOutputStream`); new code in `reporting/export/adapter/out/render/pdf/`.
- `reporting/export/adapter/out/config/ExportProperties.java` -- add `pdfMaxRows` (2000), `pdfMaxClosures` (31).
- `reporting/export/adapter/in/rest/dto/ExportDatasetDto.java` + `ExportController` datasets endpoint -- add nullable `pdfTemplate` (French template label) so the app lists templates from the server catalogue.
- `reporting/report/application/service/EndOfDayReportBuilder.java:72` `build(storeId, ClosureWindow, storeName, date, closeTime, isAutomatic)` → `EndOfDayReportData`; `DailyReportGenerator` = reference usage.
- `commerce/sale/domain/model/ClosureWindow.java` -- add `static forClosure(DayClosure c, Optional<DayClosure> previous)` reproducing `CloseDayService.java:96-101` (previous: `p.closedAt+1µs, c.closedAt, p.receivedAt, c.receivedAt`; none: WAT midnight of `c.closedAt`'s date, `c.closedAt`, null, `c.receivedAt`); refactor `CloseDayService` to call it with `receivedAt = now` (behaviour unchanged).
- `commerce/sale/domain/port/out/DayClosureRepository.java` (+ adapter under `commerce/sale/adapter/out/persistence`) -- add `findById(UUID)` and `findPreviousForStore(UUID storeId, Instant before)` (greatest `closed_at` < before).
- Providers giving rows (column keys are the contract): `commerce/sale/adapter/out/export/{DayClosuresExportDatasetProvider (id, closureDate, closedAt, store…), SalesExportDatasetProvider}`, `catalog/stock/adapter/out/export/StockLevelsExportDatasetProvider` (keyset store, product), `inventory/counting/adapter/out/export/InventoryCountsExportDatasetProvider` (keyset session started_at, session id → rows grouped by session). Read rows by column KEY (index from `columns()`), never by position constants.
- Header data: `identity/auth/domain/port/out/TenantRepository.findBySchemaName(TenantContext…)` → `Tenant.getName()`; `store/store/domain/port/out/StoreRepository.findById`.
- `shared/domain/exception/ErrorCode.java:127-133` + `shared/infrastructure/web/GlobalExceptionHandler.java` (`FR_MESSAGES` :107, status switch :269 for 422) -- add `EXPORT_PDF_TOO_LARGE`.
- Font: `/usr/share/fonts/truetype/liberation/LiberationSans-{Regular,Bold}.ttf` (SIL OFL 1.1, fonts-liberation 2.1.5) → copy to `src/main/resources/pdf/fonts/` with `OFL.txt` (from `/usr/share/doc/fonts-liberation/copyright`).
- Tests to mirror: `src/test/java/com/keevo/reporting/export/adapter/out/render/XlsxExportRendererTest.java`, `application/service/ExportJobRunnerTest.java`, `integration/` (Postgres seeding for providers), `adapter/in/rest/ExportControllerTest.java`.
- App: `keevo/app/lib/features/export/domain/model/export_models.dart` (`ExportDataset.fromJson`, `buildExportRequest` :223-235 guard), `presentation/page/export_page.dart` (`_selectFormat` :98, `_showOffer`, `_datasetTiles`, `_filterTiles`, `_formatTiles` :533-573), `data/remote_export_datasource.dart` :74 guard, `presentation/page/my_exports_page.dart` :101 and :312 (PDF relaunch blocked), `domain/exception/export_exception.dart` (message switch); tests under `keevo/app/test/features/export/`.

## Tasks & Acceptance

**Execution:**
- `commerce/sale/domain/model/ClosureWindow.java`, `CloseDayService.java`, `DayClosureRepository.java` + adapter -- shared `forClosure` factory, two finder methods -- one window definition for closure and PDF.
- `reporting/export/domain/port/out/DocumentExportRenderer.java`, `ExportStreamingWriter.java`, `ExportService.java`, `ExportProperties.java`, `ErrorCode`/`GlobalExceptionHandler` -- document-renderer path, pre-quota validation and size check, 422 code with French message « Ce PDF dépasserait la limite de {limit} lignes : réduisez la période, choisissez une boutique ou exportez en Excel ».
- `reporting/export/adapter/out/render/pdf/` (`PdfExportRenderer`, `PdfTemplate` enum key→label/template/limit, `PdfDocumentFactory` Thymeleaf+OpenHTMLtoPDF with font registration, per-template model builders, `PdfHeader`), `src/main/resources/pdf/templates/{layout fragments, end-of-day, inventory, stock, sales}.html`, `src/main/resources/pdf/fonts/` -- the four templates per the I/O matrix.
- `ExportDatasetDto` + controller -- `pdfTemplate` label.
- Backend tests: `PdfExportRendererTest` (each template from synthetic providers → `PDDocument` loads, A4 media box, text contains accents « Clôture », « Écart », « Généré le », totals in XAF, « Page 1 / N » with N>1 for a multi-page sales list and table header present on page 2; empty case; limit truncation note), `ClosureWindowTest` (`forClosure` with/without previous equals the CloseDayService window), `ExportServiceTest`/controller tests (PDF wrong dataset 400, two datasets 400, too large 422 without quota consumption, FREE 403), and one Postgres integration test: seeded closure + sales (incl. a late offline sale) → EOD PDF text contains the same revenue / count / cash / MoMo as `EndOfDayReportBuilder.build` with `ClosureWindow.forClosure`.
- App `export_models.dart` -- `ExportDataset.pdfTemplate`; `buildExportRequest` accepts PDF only with exactly one dataset that has a `pdfTemplate` (ArgumentError otherwise); remove the « not available » guard.
- App `remote_export_datasource.dart` -- remove the PDF guard.
- App `export_page.dart` -- PDF tile enabled with Premium badge on non-Premium; Free (premium == false) + PDF → offer sheet titled « Le PDF est réservé au plan Premium »; selected PDF shows section « Modèle » with single-choice tiles (keys `export-template-<datasetKey>`) instead of dataset checkboxes, preselected when the current selection contains exactly one templated dataset; filters enabled from the chosen template's dataset flags; submit disabled until a template is chosen; switching back to CSV/Excel restores the dataset selection.
- App `my_exports_page.dart` -- allow relaunching PDF jobs; `export_exception.dart` -- map `EXPORT_PDF_TOO_LARGE` to the server message (fallback « Ce PDF est trop volumineux : réduisez la période ou exportez en Excel »).
- App tests: update `export_models_test`, `remote_export_datasource_test`, `export_page_test` (Free + PDF opens offer sheet; Premium + PDF shows templates and sends `datasets=[<template key>]`, format PDF), `my_exports_page_test`; `keevo/app/pubspec.yaml` → `1.1.35+34`.

**Acceptance Criteria:**
- Given a closure, when its store and day are exported as the « Fin de journée » PDF, then the PDF's sales count, revenue, cash, Mobile Money, pending and top-product figures equal `EndOfDayReportBuilder.build` for `ClosureWindow.forClosure` of that closure (late offline sales included).
- Given any template, when rendered, then the file is a valid A4 PDF whose extracted text keeps French accents, repeats the table header on each page and ends each page with « Page x / y ».
- Given a Free plan in the app, when the user taps PDF, then the Premium offer sheet opens and no request is sent; given Premium, when choosing « État de stock » and Exporter, then `POST /api/v1/exports` carries `{"datasets":["stock_levels"],"format":"PDF"}` and the file is shared.

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 34 findings — high 0, medium 5, low 22, false 7, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind) EOD PDF recomputes figures live: low stock = current stock, sale statuses current, so an old closure's PDF can differ from the WhatsApp EOD — low-stock line labelled « (à ce jour) » + per-document footnote stating figures are recomputed on each closure's window with statuses/stock at generation time.
  - `[low]` `[patch]` (blind) 422 message says « lignes » for the closure limit — `details.unit` + « {limit} {unit} » message.
  - `[low]` `[patch]` (blind) `withDetails` substitutes placeholders for every DomainException incl. English fallback — restricted to FR_MESSAGES templates + unit test.
  - `[low]` `[reject]` (blind) count before rate limiter lets a Premium owner trigger COUNT queries without quota — OWNER-only authenticated path, one indexed COUNT per request; preserving 18-1's « refused requests do not burn quota » rule outweighs it; a throttle adds new surface.
  - `[low]` `[patch]` (blind) Sales/Stock PDF match copied French labels (« Validée », « Annulée », « OK ») — test pins them to the provider-side constants.
  - `[medium]` `[patch]` (blind) inventory totals: theoretical sums uncounted lines — theoretical summed over counted lines only.
  - `[low]` `[patch]` (blind) totals partial when truncated, not said — truncation note now states totals cover only the listed lines (truncation only happens if data grew after the pre-check).
  - `[false]` `[reject]` (blind) one-day EOD can miss the day's closure (period on closed_at vs closure_date) — CloseDayService derives closure_date from closedAt in WAT (CloseDayService.java:80), so both agree.
  - `[low]` `[reject]` (blind) three WAT zone constants (Lagos/Douala) — both UTC+1 without DST, identical instants; no named harm.
  - `[low]` `[patch]` (blind) OFL.txt is Debian's copyright file — trimmed to Liberation copyright + SIL OFL 1.1 text. (The .ttf files being absent from the diff is `false`: the diff excluded binaries; they are committed.)
  - `[low]` `[patch]` (blind) missing tests — added: withDetails unit test, unknown-plan PDF 403 widget test; rejected low: background-PDF test (writer delegation already covered by ExportStreamingWriterTest, job runner path unchanged), no-template / pre-catalogue states (submit stays disabled, user picks a template).
  - `[low]` `[patch]` (blind) epic-18-context.md stale — lines updated.
  - `[low]` `[patch]` (blind) header falls back to « Keevo » without a business name — empty fallback (ExportJobRunner sets TenantContext, ExportJobRunner.java:76).
  - `[low]` `[patch]` (edge) stock groups split when two stores share a name (keyset by name) — grouped by storeId.
  - `[medium]` `[patch]` (edge) inventory theoretical total — same root cause as the blind row above.
  - `[low]` `[patch]` (edge) 422 unit — same as blind row.
  - `[medium]` `[patch]` (edge) EOD low-stock/pending at generation time — same as blind row.
  - `[low]` `[patch]` (edge) XML-illegal control characters in user text break the XHTML parse (500) — stripped in PdfFormats.text + test.
  - `[low]` `[reject]` (edge) count before rate limiter — same reasoning as blind row.
  - `[low]` `[reject]` (edge) pdf limits configured ≤ 0 — operator misconfiguration only, defaults 2000/31; guard adds surface.
  - `[false]` `[reject]` (edge) PdfFormats parse exceptions on odd cell types — the provider contract (ExportDatasetProvider javadoc) fixes the value types; the four providers return Long/Instant/String.
  - `[low]` `[patch]` (edge) inventory session cut mid-way looks complete — covered by the truncation note change.
  - `[low]` `[reject]` (edge) catalogue refresh removing a template keeps « Exporter » enabled but inert — requires a server downgrade during the session; no harm beyond a no-op tap.
  - `[false]` `[reject]` (edge) `_lastRequest` keeps the PDF request after a plan gate — the retry card renders only when `_error != null` (export_page.dart:452) and the plan-gate path never sets `_error`.
  - `[low]` `[patch]` (verification-gap) unknown plan + PDF + server 403 untested — widget test added.
  - `[low]` `[patch]` (verification-gap) 422 unit — same as blind row.
  - `[false]` `[reject]` (verification-gap) font files missing from the diff — binaries were excluded from the staged diff only; they exist and are committed.
  - `[low]` `[patch]` (verification-gap) withDetails global — same as blind row.
  - `[false]` `[reject]` (intent) closure-addressed vs period-addressed EOD — recorded design decision; a closure is reached through its store + day (integration test).
  - `[medium]` `[patch]` (intent) PDF vs report actually received — same root cause as the blind EOD row; employee-scoped divergence is `false`: the owner's WhatsApp EOD is the store-level build (DailyReportGenerator, actorId null).
  - `[low]` `[reject]` (intent) per-page header/footer asserted only on the sales PDF — all templates share layout.html; A4 + header asserted on every template.
  - `[low]` `[patch]` (intent) no big-volume test — test rendering pdf-max-rows (2000) rows added.
  - `[false]` `[reject]` (intent) logo dropped untested — no logo field exists in Store/Tenant (grep), recorded decision.
  - `[false]` `[reject]` (intent) test/core untouched — the lot ran green (468 tests); no core code changed.

## Design Notes

- **Template = dataset key** keeps 18-1's request/job/audit contract: a PDF job persists `datasets=[key]`, `format=PDF`, so « Mes exports » and relaunch work unchanged; no template column, no migration.
- **EOD by period, not by closure id:** the app has no closure picker; « Aujourd'hui » + store selects that day's closure, a longer period yields one section per closure (capped at 31).
- **Size check before quota:** PDF is Premium, so a count exists; doing it before `acquireRequest` honours 18-1's rule that refused requests do not burn the hourly quota. With 2000 ≤ `sync-row-threshold` (5000) a PDF is normally direct; a background PDF still works through `ExportJobRunner` → writer.
- **Rendering sketch:** `PdfRendererBuilder().useFastMode().useFont(supplier, "Keevo Sans", 400, NORMAL, true)…withHtmlContent(xhtml, null).toStream(out).run()`; CSS `@page { size: A4; margin: 28mm 12mm 16mm; @top-center { content: element(header) } @bottom-right { content: "Page " counter(page) " / " counter(pages) } }`, header `position: running(header)`, `thead { display: table-header-group }`, `table { -fs-table-paginate: paginate }`.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -o test -Dtest=<new and touched test classes> -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures.
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh backend reporting commerce shared architecture sync` -- expected: every lot OK.
- `cd keevo/app && flutter analyze` -- expected: 0 errors, 0 warnings.
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh app test/core test/features/export` -- expected: both lots OK.

## Auto Run Result

Status: done

**Summary:** PDF export (Premium) via a document renderer plugged into the 18-1 registry: Thymeleaf XHTML → OpenHTMLtoPDF 1.1.22 with embedded Liberation Sans (OFL). Template = the single requested dataset: `day_closures` → Fin de journée (figures from `EndOfDayReportBuilder` over the shared `ClosureWindow.forClosure`, also used by `CloseDayService`), `inventory_counts` → Rapport d'inventaire, `stock_levels` → État de stock, `sales` → Liste des ventes. A4, running brand header, repeated table headers, « Page x / y », XAF totals. Bounded: 2000 rows / 31 closures, refused before the quota with 422 `EXPORT_PDF_TOO_LARGE`. App: PDF enabled (Premium badge, Free → offer sheet, « Modèle » single choice from the catalogue's `pdfTemplate`), PDF jobs relaunchable, version 1.1.35+34.

**Files changed:**
- `keevo/backend/pom.xml` — thymeleaf + openhtmltopdf-pdfbox 1.1.22.
- `commerce/sale/domain/model/ClosureWindow.java`, `CloseDayService.java`, `DayClosureRepository` (+ adapter, Spring repo) — shared window factory, `findById`, `findPreviousForStore`.
- `reporting/export/domain/port/out/DocumentExportRenderer.java`, `ExportService.java`, `ExportStreamingWriter.java`, `ExportProperties.java`, `application.yml` — document path, pre-quota validation/size check, limits.
- `reporting/export/adapter/out/render/pdf/*` + `resources/pdf/{templates,fonts}` — renderer, models, templates, font + licence.
- `ExportController` / `ExportDatasetDto` — `pdfTemplate`.
- `ErrorCode`, `GlobalExceptionHandler` — `EXPORT_PDF_TOO_LARGE` (422), placeholder fill for French messages only.
- App `features/export` (models, datasource, export page, my exports, exception) + tests; `pubspec.yaml`.
- `epic-18-context.md` — refreshed.

**Review:** 34 findings (high 0, medium 5, low 22, false 7). Patched entries: EOD figures at generation time (medium — labelled + footnote), inventory totals (medium), 422 unit, withDetails scope (+ test), label pinning test, truncation note, stock grouping by store id, XML control characters, header fallback, OFL file, big-volume test, unknown-plan PDF gate widget test, epic context. Rejected: count before rate limiter (keeps 18-1 quota rule), WAT constants, limits ≤ 0, catalogue downgrade, extra background-PDF test, per-template page checks; false: closure_date vs closed_at, parse exceptions, `_lastRequest` replay, missing fonts, logo, test/core, period-addressed EOD. Deferred: none.

**Follow-up review:** recommended (`true`) — two medium entries were patched (EOD generation-time labelling, inventory totals) together with stock grouping by store id; these patches were verified by tests but not re-read by independent review layers.

**Verification:** backend lots reporting 338/0, commerce 226/0, shared 452/0, architecture 6/0, sync 431/0 (commerce/sync ran before the review patches, which did not touch them); `PdfEndOfDayPostgresIntegrationTest` green (Postgres). App: `flutter analyze` 0 errors / 0 warnings (614 infos), test/core 468 OK, test/features/export 68 OK, audit + global stock pages 15 OK.

**Residual risks:** EOD PDF of an old closure shows current low stock / sale statuses (labelled); PDF rendering is in-memory up to 2000 rows (tested); count queries for refused PDFs do not consume quota.

