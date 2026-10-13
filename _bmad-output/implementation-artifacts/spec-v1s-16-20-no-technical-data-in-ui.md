---
title: 'v1s-16-20 No technical data visible to the end user (exports, action history, screens)'
type: 'feature'
created: '2026-10-13'
status: 'done'
baseline_revision: '509eef4b82c8bfc65079419718b2217dcff537c1'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** A shop owner (tech literacy 4/10) sees technical data in Keevo: every server export (CSV/XLSX) starts with UUID « Identifiant » columns, the audit export prints raw action codes (`CATEGORY_RENAMED`), class-like object types (`StockMovement`), raw device ids and before/after JSON; the offline local CSV mirrors that; the action-history detail shows a device-id fragment, a « Code » row and JSON for nested values; sync screens, the blocked-operations export, transfer/stock/inventory screens and error helpers fall back to UUIDs, raw enum codes, ISO dates, English exception text and JSON.

**Approach:** Remove id columns from every export dataset (replace the sale id by a readable reference where the user needs to cross-check sales ↔ sale lines), translate every code server side through one French label table shared with the app via `keevo/contracts/export-datasets.json`, render audit before/after as a readable French « Changements » text, clean every app screen listed in the inventory, and add automated guards (backend export cells/headers; app key screens) that fail on a UUID, a SNAKE_CASE code or JSON. Write the rule into the PRD (NFR) and UX spec.

## Boundaries & Constraints

**Always:**
- Ids stay allowed internally: queries, keyset cursors, navigation (`/pos/sales-history/<id>`), filter criteria (`userId`, `entityId`), DB columns, JSON API fields. Only what is *displayed* or *written into an export file* changes.
- When no readable label exists, omit the field/row rather than show an id; an unknown enum code is humanized (`MOBILE_MONEY` → « Mobile money »), never printed raw.
- Server is the source of truth for labels; the contract file is regenerated with `mvn -o test -Dtest=ExportContractFileTest -Dexport.contract.write=true` (never hand-edited), then the app catalogue/presenter is updated until `local_export_contract_test.dart` passes. `ExportDatasetCatalogTest` GOLDEN updated in the same change.
- Local CSV (18-5) stays column-identical to the server (same keys, labels, types, label maps, humanize fallback, same audit « Changements » rendering rules).
- Bump `keevo/app/pubspec.yaml` 1.1.38+37 → 1.1.39+38. No hardcoded colors (AppTheme tokens). `flutter analyze` 0 error / 0 warning.
- Tests only in chunks (`/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh`, targeted `-Dtest=` / `flutter test <files>`); never a full suite, never in parallel.

**Never:** change REST API JSON shapes or DB schema (no Flyway migration); remove the `entityId`/`userId` export criteria; touch the dashboard (Next.js) or the super-admin app; edit `deferred-work.md`; hand-edit `export-datasets.json`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Export any dataset | seeded tenant (integration fixture), CSV + XLSX | no header/cell matches UUID regex, `^[A-Z]+(_[A-Z]+)+$`, or starts with `{`/`[` | guard test fails naming dataset/column/value |
| Sale reference | sale 2026-10-12T13:32:05Z, store « Boutique Centre » | `sales.reference` = `sale_items.sale` = « Vente du 12/10/2026 à 14:32:05 — Boutique Centre » (WAT) | store null → « Vente du 12/10/2026 à 14:32:05 » |
| Audit export row | `PRICE_OVERRIDDEN`, entityType `PriceOverride`, before `{"price":1000,"productId":"<uuid>"}`, after `{"price":1500}`, source SYNC, deviceId set | action « Prix modifié en caisse », type « Prix de vente », changes « Prix : 1 000 XAF → 1 500 XAF », source « Synchronisé », no device column | malformed JSON → changes empty |
| Unknown code | `stores.type` = `KIOSK`, audit action `FOO_BAR` | « Kiosk », « Foo bar » | — |
| Inventory adjustment notes | stock movement notes `INVENTORY:<uuid>` | « Ajustement d'inventaire » (export + app stock history + audit context) | — |
| Audit detail (app) | SYNC entry with deviceId, actor « Awa », unknown action, nested map value | « Appareil de Awa » (row omitted when no deviceId), no « Code » row, nested map row omitted, no `…abcd1234` | — |
| Blocked operation export text | CREATE_SALE payload with unknown product id, CASH, store unknown locally | « Produit inconnu sur cet appareil », « Paiement : Espèces », « Boutique inconnue sur cet appareil », no « Données brutes » JSON | — |
| Backend error message with UUID | Dio error body `message: "Transfer <uuid> has status COMPLETED"` | `appErrorMessage` returns a generic French message | — |

</intent-contract>

## Code Map

Backend (`keevo/backend/src/main/java/com/keevo/`):
- `shared/infrastructure/export/ExportValues.java` -- `frLabel` L68 falls back to raw code → humanize; add `humanize`, `isTechnical(String)` helpers (UUID / SNAKE regex), `saleReference(Instant, String store)`, `slug(String label)`, `INVENTORY_NOTE` rule (`INVENTORY:` prefix → « Ajustement d'inventaire »).
- `shared/infrastructure/export/AuditLogExportDatasetProvider.java` -- COLUMNS L50-63, `row` L102: drop `id`, `entityId`, `deviceId`, `valueBefore`, `valueAfter`; translate `action`, `entityType`; add `changes` (« Changements ») and `details` (« Détails », from metadata context keys). Criteria unchanged.
- NEW `shared/infrastructure/export/AuditLabels.java` -- the shared French table: `ACTIONS` (every code written by `shared/infrastructure/web/AuditEventListener.java` L139-666 incl. `DATA_EXPORTED`, plus app-only `EMPLOYEE_CREATED`), `ENTITY_TYPES` (singular nouns incl. `Export` « Export de données », `Employee` « Employé »), `FIELDS`, `VALUES`, `CONTEXT` (static `Map<String,String>`), `HIDDEN_KEYS` (Set). Seed from the app `audit_presenter.dart` maps (catalogue labels L120-298, `_typeNouns` L320, `_fieldLabels` L516, `_codes` L608, `_contextLabels`, `_hiddenChangeKeys`/`_hiddenContextKeys`) and extend: values STOCK_ENTRY, IN_TRANSIT, PENDING_SYNC, CONFLICT, CASH, MOBILE_MONEY, STORE, WAREHOUSE, every `identity/onboarding/domain/model/SectorType` value, export modes (`DataExportedEvent.MODE_*`); fields format, rowCount, from, to, mode, sectorType, schemaName; hidden: schemaName, ip, tenantId, datasets, criteria, and any key `id` / ending `Id` / `Ids`. `COMPLETED` → « Terminé(e) ».
- NEW `shared/infrastructure/export/AuditChangesFormatter.java` -- pure: `changes(before, after)` → « Champ : avant → après » joined « ; » (creation: « Champ : valeur »); `details(metadata, valueAfter)`. Rules = app `AuditPresenter.changes/context/formatValue`: hidden keys and UUID values dropped, nested maps dropped, lists → « n élément(s) » (`fieldsChanged` → field labels joined), bool Oui/Non, amounts « 1 500 XAF », `*At`/from/to dates WAT `dd/MM/yyyy HH:mm`, code-shaped strings via `VALUES` else humanize, INVENTORY notes rule.
- Providers (drop listed columns, keep the others in order): `catalog/product/.../ProductsExportDatasetProvider` (id), `catalog/category/.../CategoriesExportDatasetProvider` (id), `catalog/contact/.../ClientsExportDatasetProvider` + `SuppliersExportDatasetProvider` (id), `identity/employee/.../EmployeesExportDatasetProvider` (id), `store/store/.../StoresExportDatasetProvider` (id; `type` via new `TYPES` map STORE « Boutique » / WAREHOUSE « Entrepôt »), `catalog/stock/.../StockLevelsExportDatasetProvider` (storeId, productId), `StockMovementsExportDatasetProvider` (id; notes INVENTORY rule), `StockTransfersExportDatasetProvider` (id), `commerce/sale/adapter/out/export/SalesExportDatasetProvider` (id → `reference` « Référence de la vente » first column), `SaleItemsExportDatasetProvider` (saleId → `sale` « Vente » reference; productId dropped), `DayClosuresExportDatasetProvider` (id), `inventory/counting/.../InventorySessionsExportDatasetProvider` (id), `InventoryCountsExportDatasetProvider` (sessionId, productId), `reporting/report/.../ReportsExportDatasetProvider` (id). Ids may still be selected internally only if the PDF models need them — check `reporting/export/adapter/out/render/pdf/EndOfDayPdfModel.java:54` (`row.text("id")`), `InventoryPdfModel.java:60` (`sessionId`), `StockPdfModel.java:37` (`storeId`): switch them to a non-exported lookup (e.g. closure date+store, session startedAt+store, store name) or an internal hidden channel; PDF output must stay identical.
- `reporting/export/adapter/out/render/CsvExportRenderer.java:79` -- zip entry `key + ".csv"` → `slug(label) + ".csv"`; `reporting/export/application/service/ExportService.java:257` `fileName` → `keevo-<slug(label)|export>-<stamp>.<ext>`.
- Keep `stock_levels.stockStatus` constants; `ExportValues.OWNER_LABEL` unchanged.

Tests / contract:
- `backend/src/test/java/com/keevo/reporting/export/ExportDatasetCatalogTest.java` -- GOLDEN L37 updated.
- `backend/src/test/java/com/keevo/reporting/export/ExportContractFileTest.java` -- `LABEL_FIELDS`: add `stores.type`, `audit_log.action` (AuditLabels.ACTIONS), `audit_log.entityType` (AuditLabels.ENTITY_TYPES); `LABEL_CLASSES` += AuditLabels; new top-level `audit` section `{fields, values, context, hiddenKeys(sorted)}`; regenerate `keevo/contracts/export-datasets.json`.
- `backend/src/test/java/com/keevo/reporting/export/integration/ExportDatasetsPostgresIntegrationTest.java` -- existing assertions on removed columns/raw codes (e.g. L507 `;PRODUCT_UPDATED;Product;`) updated; NEW guard test `noTechnicalDataInAnyExport`: every provider, CSV+XLSX, headers and every cell checked against UUID `[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-…{12}`, `^[A-Z]+(_[A-Z]+)+$`, leading `{`/`[`; seed covers an audit row with JSON before/after, a SYNC device, an INVENTORY note, a WAREHOUSE store.
- NEW unit tests `AuditChangesFormatterTest`, `ExportValuesTest` additions (humanize, saleReference, slug); `PdfExportRendererTest` / `PdfEndOfDayPostgresIntegrationTest` / `ExportServiceTest` / `CsvExportRenderer` tests adjusted.

App (`keevo/app/`):
- `lib/features/export/domain/local/local_export_schema.dart` -- catalogue, `exportLabels`, `exportLabel` (humanize fallback), new audit maps from contract; `lib/features/export/data/local/local_export_reader.dart` -- per-dataset mappers (stores L411, `_audit` L682-709, sale reference, notes rule); `local_csv_writer.dart:162,198` -- slug file names; `test/features/export/domain/local_export_contract_test.dart` -- also asserts audit maps vs contract `audit` + action/entityType labels.
- `lib/features/audit/presentation/audit_presenter.dart` -- expose maps compared to the contract (catalogue labels == `labels["audit_log.action"]`, nouns == `audit_log.entityType`, fields/values/context/hidden == `audit`); add `DATA_EXPORTED`, `Export`; drop `Map → jsonEncode` (omit row); hide `*Id`/`*Ids`/UUID values; humanize unknown codes; `deviceLabel` → « Appareil de <actor> » / null; SYNC source « Synchronisé depuis l'appareil de <actor> »; INVENTORY notes rule. `page/audit_entry_detail_page.dart:139,146` -- device row, remove « Code » row. `page/audit_log_page.dart:439` -- unknown entityType chip humanized. `widget/stock_history_widget.dart:232-259` -- actor fallback « Un utilisateur », actions via `AuditPresenter.actionInfo`, STOCK_ENTRY.
- `lib/core/sync/sync_rejection_messages.dart:64,105` -- no code in message, generic operation fallback; `features/sync_indicator/presentation/widget/sync_indicator.dart:72-85` check.
- `lib/core/sync/sync_quarantine_service.dart:290` storeLabel fallback without id; `exportText` L208-280: payment label, product fallback without id, drop « Données brutes » JSON block (keep a readable recap of every payload line).
- `features/sync_indicator/presentation/page/sync_settings_page.dart` -- devices L175-205 (« Cet appareil » / « Autre appareil », no id), history L296-300 (French type/status, friendly message), conflict sheet L396-410 (French labels, no entityId, formatted date, no JSON), pending queue L490-508 (no id); `sync_conflict_log_page.dart:112`; `core/sync/domain/sync_conflict.dart:43-54`; `core/sync/sync_trigger_notifier.dart:198,217,229` + `core/sync/diagnostics/sync_diagnostic_runner.dart:63-69` messages in French without exception text.
- `features/inventory/presentation/page/transfer_history_page.dart:428,451,459,490-498,645`; `features/catalog/presentation/page/stock_history_page.dart:297-324`; `features/catalog/presentation/widget/stock_level_widget.dart:46`; inventory scope `widget/active_session_banner.dart:50`, `widget/session_history_card.dart:51`, `page/inventory_gap_report_page.dart:62`.
- `lib/core/widget/app_error_widget.dart:18-63` -- no code / status code / message containing UUID or SNAKE code; `transfer_form_bottom_sheet.dart:194`, `create_draft_product_bottom_sheet.dart:142`, `contact/presentation/page/supplier_form_page.dart:117` → `appErrorMessage`. `features/export/presentation/page/my_exports_page.dart:303` fallback « Export ».
- Read-only (verified clean): sale detail / pending sale detail, notification card, PDF templates.

## Tasks & Acceptance

**Execution:**
- `keevo/backend/.../ExportValues.java`, NEW `AuditLabels.java`, NEW `AuditChangesFormatter.java` -- helpers + shared table + formatter -- one source of French labels.
- 16 provider files + PDF models listed above -- drop/replace columns, translate codes, keep PDF output -- AC 1.
- `CsvExportRenderer.java`, `ExportService.java` -- slugged French file/entry names -- users see the zip entry and file names.
- Backend tests + `keevo/contracts/export-datasets.json` (regenerated) -- golden, contract, formatter unit tests, integration guard -- AC 3.
- App export files + contract test -- local CSV aligned -- AC 1.
- App audit presenter/pages/widget -- AC 2.
- App sync / quarantine / settings / conflict / transfer / stock / inventory / error-helper files -- AC 4.
- NEW `keevo/app/test/helpers/no_technical_text.dart` -- `expectNoTechnicalText(WidgetTester)` scanning every `Text`/`RichText`/`SelectableText` plain text for UUID, `\b[A-Z]+(_[A-Z]+)+\b`, `{"`, short hex ids like `…[0-9a-f]{8}`; plus widget/unit tests using it for: audit detail + list, blocked operations section + `exportText`, sync settings page, transfer history, stock history, inventory session widgets; local export reader cells test (seeded Drift DB, every dataset) -- AC 3.
- `_bmad-output/planning-artifacts/prd.md` -- new NFR35 « Aucune donnée technique visible » (Utilisabilité block, after NFR27); `ux-design-specification.md` -- anti-pattern 7 + rule paragraph -- AC 5.
- `keevo/app/pubspec.yaml` -- 1.1.39+38.

**Acceptance Criteria:**
- Given any export (server CSV/XLSX/PDF or local CSV) of any dataset, when it is opened, then no header, file name or cell contains a UUID, a raw SNAKE_CASE code or JSON.
- Given the action history and an action detail, when the owner views them, then only French labels appear (no id, code, device id or JSON), and the server audit export uses the same labels as the app (contract test).
- Given a new displayed text or export cell containing a UUID or SNAKE_CASE code in the guarded screens/datasets, when tests run, then the guard fails.
- Given sync, blocked-operation, transfer, stock, inventory screens and error snackbars, when fed ids/codes/exception text, then they render French labels or omit the field.
- Given prd.md and ux-design-specification.md, when read, then the rule is stated as a permanent NFR / UX rule.

## Spec Change Log

## Review Triage Log

### 2026-10-13 — Review pass
- verdicts: 46 findings — high 0, medium 15, low 23, false 8, maybe-false 0
- findings:
  - `[low]` `[patch]` (intent A) PDF compliance not enforced by any test — added a PDF text guard (PDFBox text, isTechnical per line) on seeded data.
  - `[false]` `[reject]` (intent B) sale detail / notifications untouched — inventory verified no id/code is rendered there (status/payment mapped, server-written French title/body); nothing to change.
  - `[medium]` `[patch]` (intent C) backend guard only whole-cell SNAKE / leading JSON, app guard differs — grouped with blind #2: isTechnical now finds embedded codes and JSON.
  - `[low]` `[reject]` (intent D) app guard is opt-in per screen — covers the key screens named by the intent (audit, sync, quarantine, transfer, stock, inventory, errors, local export); a global guard over every screen is not required by the AC.
  - `[false]` `[reject]` (intent E) contract carries the audit rendering table — this is the requested « table de libellés partagée ».
  - `[false]` `[reject]` (intent F) version literal — HEAD was 1.1.38+37, the intent asks to bump it; 1.1.39+38 is the bump.
  - `[false]` `[reject]` (intent G) process items not visible in the diff — inventory is in this spec (Problem/Code Map), tests ran via the chunk script (Verification).
  - `[medium]` `[patch]` (blind 1) app guard misses single-word codes and truncated id forms — denylist of known single-word codes + truncated hex patterns added.
  - `[medium]` `[patch]` (blind 2) server guard weaker than app guard — see intent C.
  - `[medium]` `[patch]` (blind 3) notes other than INVENTORY: keep UUIDs — grouped with edge 1/2: notes strip every UUID both sides.
  - `[false]` `[reject]` (blind 4) *Id fields deleted instead of replaced by names — the intent rule is « si aucun libellé n'existe, omets le champ »; snapshots carry no names for these ids; the readable label (entityLabel) remains.
  - `[low]` `[reject]` (blind 5) date-only / offsetless dates and double formatting differ server vs app — audit snapshot dates are written by `str(Instant)` (always `Z`); no producer of offsetless values found; fix adds parsing branches.
  - `[low]` `[reject]` (blind 6) slug differs for œ/æ — no dataset label contains these letters (16 fixed labels); unreachable today.
  - `[low]` `[reject]` (blind 7) sale reference not unique within the same second — requires two sales same store same second; a sequence would need a window function that breaks keyset paging; documented in Design Notes.
  - `[low]` `[reject]` (blind 8a) other devices all read « Autre appareil » — no human device name exists for sync device ids (Design Notes); omission is the rule.
  - `[medium]` `[patch]` (blind 8b) currentDeviceIdProvider caches null before the id is written — grouped with edge 7 / VG 4: shared key constant + autoDispose.
  - `[low]` `[reject]` (blind 9) queue tile / exportText lose context — readable recap keeps store, products, quantities, prices, payment; product name for unknown local product cannot be recovered from an id anyway.
  - `[low]` `[reject]` (blind 10a) humanize duplicated six times — cosmetic divergence (camelCase split) only on fallback paths of unknown codes; consolidating crosses core→features layering; not worth the churn here.
  - `[low]` `[patch]` (blind 10b) session_history_card unknown status uses the scope table — humanize instead.
  - `[low]` `[patch]` (blind 11) syncEventTitle fallback prints English status — statuses translated, generic title otherwise.
  - `[low]` `[reject]` (blind 12) guard tests absent for StockLevelWidget/SyncConflictLogPage/MyExportsPage… — fallbacks fixed in code; guard covers the key screens; StockHistoryWidget fixtures added under VG 5.
  - `[low]` `[patch]` (blind 13) « disponible : » lost its non-breaking space — restored.
  - `[medium]` `[patch]` (edge 1) server notes « Annulation vente <uuid> » — see blind 3.
  - `[medium]` `[patch]` (edge 2) app notes with UUID — see blind 3.
  - `[low]` `[patch]` (edge 3) `winnerTimestamp` English label — French label added.
  - `[low]` `[patch]` (edge 4) conflict entity fallback English (« Unknown ») — generic « Élément ».
  - `[medium]` `[patch]` (edge 5) legacy DIAGNOSTIC OK message rewritten as a connection problem — event-aware fallback.
  - `[medium]` `[patch]` (edge 6) ABANDON entries rewritten as « nouvel essai automatique » — grouped with edge 5 / VG other 2.
  - `[medium]` `[patch]` (edge 7) currentDeviceIdProvider null cached — see blind 8b.
  - `[low]` `[patch]` (edge 8) history list shows a SNAKE/JSON entityLabel the export hides — same isTechnical rule in `_readableLabel`.
  - `[false]` `[reject]` (edge 9) DATA_EXPORTED label falls back to dataset keys — `datasetLabels` is built from the same providers list as `datasets` (ExportService.publish), sizes always equal.
  - `[low]` `[reject]` (edge 10) parseInstant offsetless — see blind 5.
  - `[low]` `[reject]` (edge 11) sale reference collision — see blind 7.
  - `[low]` `[reject]` (edge 12) user value « PACK_6 » humanized — uppercase snake product names are improbable for the target users; restricting to code keys would let unknown codes print raw.
  - `[low]` `[patch]` (edge 13) `body['error'] as String?` TypeError on Map — read only when String.
  - `[false]` `[reject]` (edge 14) internal key collision in PdfRowReader — no internal key equals a column key (storeId/sessionId/id are no longer columns); no reachable collision.
  - `[false]` `[reject]` (edge 15) « Données brutes » removal blocks re-entry of CREATE_PRODUCT/CREATE_CLIENT — only money/stock-ledger ops are quarantined (`moneyLedgerOperations`), whose recap (store, product, quantity, prices, payment) is rendered.
  - `[medium]` `[patch]` (VG 1) real-provider hidden ids for Stock/Inventory PDFs untested — integration assertions on trailing ids + PDF rendering from seeded data.
  - `[low]` `[patch]` (VG 2) inventory scope label unpinned — formatter + gap report tests.
  - `[medium]` `[patch]` (VG 3) syncEventMessage/Title unpinned — unit tests added.
  - `[medium]` `[patch]` (VG 4) device id key duplicated, never exercised — shared constant.
  - `[low]` `[patch]` (VG 5) StockHistoryWidget PRICE_OVERRIDDEN / STOCK_ENTRY untested — fixtures added.
  - `[medium]` `[patch]` (VG other 1) appErrorMessage discards readable domain exception messages — use the exception `message` when readable.
  - `[medium]` `[patch]` (VG other 2) ABANDON / substring matches in syncEventMessage — see edge 5.
  - `[low]` `[reject]` (VG other 3) sale reference collision — see blind 7.
  - `[low]` `[patch]` (orchestrator) unchanged fields listed « A → A » in Changements — equal before/after rows skipped both sides.

## Design Notes

- Sale reference uses seconds to stay distinguishable in busy stores (brief example extended): `Vente du dd/MM/yyyy à HH:mm:ss — <store>` in WAT. Inventory counts need no reference: `sessionStartedAt` + `store` already identify the session.
- Unknown code → humanize (not omission) so a newly added status still reads as French-ish words instead of vanishing; the guard regex only flags underscore codes, single-word codes like `CSV` are formats users know.
- Device ids have no human name server side (FCM `device_name` is unrelated to the sync device id): the app shows « Appareil de <actor> »; exports drop the column.
- Blocked-operations « Exporter » loses its raw JSON block: the readable recap (date, store, products, quantities, prices, payment, amount) is what the owner re-enters; ids stay in the local DB for Renvoyer/Rattacher.
- Shared table lives server side (`AuditLabels`) and reaches the app through the contract file; parity is enforced by the two contract tests rather than runtime loading (the backend jar cannot read `keevo/contracts`).

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -o test -Dtest=ExportContractFileTest -Dexport.contract.write=true -DforkCount=1 -DargLine=-Xmx1536m` then targeted `-Dtest=ExportDatasetCatalogTest,ExportContractFileTest,AuditChangesFormatterTest,ExportDatasetsPostgresIntegrationTest,PdfExportRendererTest,ExportServiceTest,PdfEndOfDayPostgresIntegrationTest` -- expected: 0 failure.
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh backend reporting shared architecture catalog commerce inventory identity store` (sequential) -- expected: every lot OK.
- `cd keevo/app && flutter analyze` -- expected: 0 error, 0 warning.
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh app test/core test/features/export test/features/audit test/features/sync_indicator test/features/pos test/features/inventory test/features/catalog test/features/contact` -- expected: every lot OK.

## Auto Run Result

Status: done

**Summary:** No technical data reaches the shop owner any more. Server CSV/XLSX exports dropped every id column (sales/sale lines share a readable « Vente du … à HH:mm:ss — Boutique » reference), every code is translated through one shared French table (`AuditLabels`, published in `keevo/contracts/export-datasets.json` and mirrored by the app, parity enforced by `ExportContractFileTest` + `local_export_contract_test`), the audit export renders « Changements » / « Détails » instead of ids/device/JSON, notes lose ids, file and zip entry names use French slugs; PDFs read ids through hidden `internalKeys()` only. App: local CSV aligned, action history/detail, sync settings/history/conflicts/queue, blocked operations, transfer/stock/inventory screens and error helpers show French labels or omit the field. Guards: backend `noTechnicalDataInAnyExport` + `pdfHiddenIdsAndNoTechnicalText` (CSV, XLSX, sheet names, file names, PDF text); app `test/helpers/no_technical_text.dart` used on the key screens and the local export reader. NFR35 in prd.md, anti-pattern 7 + rule in ux-design-specification.md. App 1.1.39+38.

**Files (main):** backend `shared/infrastructure/export/{ExportValues,AuditLabels,AuditChangesFormatter,AuditLogExportDatasetProvider}.java`, 15 dataset providers, `ExportDatasetProvider.internalKeys`, `PdfRowReader`, `CsvExportRenderer`, `ExportService.fileName`; contract JSON; app `features/audit/domain/{audit_labels,audit_format}.dart`, `audit_presenter.dart`, export local schema/reader/writer/service, `core/sync/{sync_event_logger,sync_quarantine_service,sync_rejection_messages,domain/sync_conflict}.dart`, `sync_settings_page.dart`, `app_error_widget.dart`, inventory scope label, transfer/stock pages; tests listed in the diff.

**Review:** 46 findings — 27 patched (15 medium, 12 low; 0 high), 0 deferred, 19 rejected (8 false, 11 low not worth the complexity: date parsing variants, œ slugs, same-second sale reference collision, device names unavailable, humanize duplication, opt-in guard coverage, uppercase user names) — reasons in the triage log.

**Follow-up review recommended: true** — patched counts: high 0, medium 15, low 12. Named unverified risk: the word-based masking of legacy sync-history messages and the app guard denylist were rewritten in the patch round and only checked by unit tests on synthetic strings, not on real legacy rows.

**Verification:** backend chunks reporting 362, shared 466, architecture 6, catalog 394, commerce 226, inventory 160, identity 361, store 40 — 0 failure/0 error; app chunks core 485, export 138, audit 87, sync_indicator 63, pos 229, inventory 186, catalog 149, contact 37 — all passed; `flutter analyze` 625 infos, 0 warning, 0 error.

**Residual risks:** backend and app must ship together (column sets and file names changed; backend deploy is manual); sale reference collides for two sales in the same store within the same second; other sync devices read « Autre appareil » (no owner name available).
