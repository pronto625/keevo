---
title: 'v1s-18-5 — Export local hors ligne (CSV « données de cet appareil »)'
type: 'feature'
created: '2026-10-12'
status: 'done'
baseline_revision: '50a033938d7833a98f1e9261787927b013992e37'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-18-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Without network the « Exporter » screen (18-4) only shows a disabled tile « CSV — données de cet appareil »: an owner in airplane mode cannot take any data out (FR97). The on-device CSV must be indistinguishable in shape from the server CSV (18-1 cell formats, 18-2 column schema and French label maps), and nothing today stops the app copy of that schema from drifting from the server.

**Approach:** A shared contract file `keevo/contracts/export-datasets.json` (16 datasets: key, label, filters, columns key/label/type, plus the code→French label maps) checked by a backend test against the real providers and by an app test against a Dart const catalogue. The app gets a local export flow: a reader that pages each dataset out of Drift (same columns, order, filters and labels as the server providers), a CSV writer running in a worker isolate (server cell formats, zip when several datasets), a « CSV — données de cet appareil » page at `/exports/local`, reached from the now-enabled offline tile, that writes a temp file and opens the share sheet.

## Boundaries & Constraints

**Always:**
- **RBAC:** OWNER only. The route `/exports/local` sits under the existing `/exports` owner-only prefix and is declared before `/exports/:id`. Rationale: the UX journey covering FR94–FR97 is « Exporter mes données (propriétaire) », the architecture marks the export module owner-only, and employee devices hold the catalogue's buy prices and the team list; the PRD wording « un utilisateur » is satisfied for the owner (decision recorded in Design Notes).
- **Contract file** `keevo/contracts/export-datasets.json` (UTF-8, pretty-printed, stable key order): `{"datasets":[{key,label,storeFilter,periodFilter,criteria:[sorted],columns:[{key,label,type}]}…in ExportDatasetCatalogTest GOLDEN order], "labels":{"<dataset>.<columnKey>":{code:label}}, "constants":{"ownerLabel":"Propriétaire","stockStatus":["Rupture","Stock bas","OK"]}}`. Label entries: `products.status`, `stock_movements.type`, `stock_transfers.status`, `sales.status`, `sales.paymentMode`, `sale_items.saleStatus`, `inventory_sessions.scope`, `inventory_sessions.status`, `inventory_counts.sessionStatus`, `employees.status`, `reports.type`, `reports.deliveryStatus`, `audit_log.actorRole`, `audit_log.source`.
- **Backend test** `ExportContractFileTest` (same package as `ExportDatasetCatalogTest`, plain unit test, reuses `ExportDatasetCatalogTest.allProviders()`): builds the expected JSON from the providers (metadata + columns) and from the label maps read by reflection (`static final Map<String,String>` fields: provider classes + `SaleLabels` + `InventoryLabels`, mapping table column→(class, field) in the test; `ExportValues.OWNER_LABEL`; stock state strings of `StockLevelsExportDatasetProvider`) and asserts it equals the parsed file at `../contracts/export-datasets.json` (path resolved from the backend module dir). It also fails when an export provider/labels class declares a `Map<String,String>` static field that the mapping table does not list. With `-Dexport.contract.write=true` it rewrites the file instead (documented in the class Javadoc). No production backend change.
- **App catalogue** `localExportCatalog` (Dart const, same order as the contract) + `exportLabels` maps; app test `test/features/export/domain/local_export_contract_test.dart` reads `../contracts/export-datasets.json` (from the app package dir) and asserts deep equality of datasets, columns, label maps and constants. A server-side change therefore breaks the backend test until the JSON is updated, then breaks the app test until the Dart catalogue follows.
- **Cell formats (identical to `CsvExportRenderer` / `ExportCells`):** UTF-8 BOM at the start of every CSV; `;` separator; CRLF record separator; header row = column labels; null → empty cell; TEXT → formula guard (leading `= + - @ \t \r` gets a `'` prefix unless the whole value matches `^[+-]?[\d\s().]+$`); INTEGER / AMOUNT_XAF → plain integer; DECIMAL → plain decimal with `,`; BOOLEAN → `Oui`/`Non`; DATE → `dd/MM/yyyy` of the WAT date; DATETIME → `dd/MM/yyyy HH:mm:ss` in WAT. WAT = UTC+01:00 fixed (no DST in Africa/Douala): `value.toUtc().add(const Duration(hours: 1))` — never the device time zone. Quoting = commons-csv `QuoteMode.MINIMAL`: quote when the value is empty and first in the record, its first char code ≤ `#` (0x23), it contains `;`, `"`, CR or LF, or its last char code ≤ space; `"` doubled inside quotes.
- **Several datasets** → one zip of `<key>.csv` entries (dataset order of the catalogue); one dataset → a plain `.csv`. File name `keevo-donnees-appareil-<key|export>-<yyyyMMdd-HHmm WAT>.<csv|zip>` (the « données de cet appareil » marking travels in the file name and the share subject = file name). Written under `<temp>/exports/<µs>/` like 18-4 (reuse its 24 h purge helper or an equivalent best-effort purge); partial folder deleted on failure.
- **Rows:** same row set, ordering and filter semantics as each server provider (Design Notes table) — store filter only for datasets with `storeFilter`, period filter (inclusive bounds, on the dataset's event column) only for datasets with `periodFilter`; audit_log newest first, every other dataset ascending on its keyset columns.
- **Bounded memory / no jank:** the Drift DB stays in the main isolate (SQLCipher override is not inherited by background isolates — `app_database.dart:392`); the reader fetches pages of ≤ 500 rows (`LIMIT/OFFSET` with a total order ending in `id`) with per-page id lookups (products, clients, suppliers, aggregates) — only small reference maps (stores, categories, actor names) are preloaded. Each page is sent as a list of rows of sendable primitives (`String`, `int`, `bool`, `DateTime`, `null`) to a long-lived worker isolate that formats cells and appends to the file (and builds the zip with `package:archive` `ZipFileEncoder` from the per-dataset temp CSVs). The UI awaits between pages and shows a running row count.
- **Available datasets only:** the local page lists the catalogue datasets with their local row count (`SELECT COUNT(*)` on the backing table); a dataset with 0 rows is shown disabled with « Aucune donnée sur cet appareil » and is never exported. « Tout » selects every available dataset.
- **UI copy (French):** tile in `export_page.dart` enabled, subtitle « Contient uniquement les données synchronisées sur cet appareil », tap → `context.push('/exports/local', extra: ExportPrefill(datasets, storeId, from, to))` from the current form state. Tile shown when offline (existing banner) and also under the error card when the last error is `NETWORK_ERROR`. Local page title « Données de cet appareil », info banner (key `local-export-scope-note`): « Export généré sur ce téléphone, sans connexion. Il reflète les données présentes sur cet appareil — y compris celles pas encore envoyées — et peut être incomplet (historique des ventes et mouvements limité aux 30 derniers jours, journal limité à ce qui a été synchronisé). » Format shown as a fixed « CSV » chip. Button « Générer le CSV » (key `local-export-submit`), busy label « Génération… N lignes ». Success → sharer then SnackBar « Fichier prêt ». Failure → error card with « Réessayer » (key `local-export-retry`), message « Impossible de générer le fichier » (storage error: « Espace de stockage insuffisant ou inaccessible »).
- Store picker and period presets identical to the Exporter screen (reuse/extract its widgets or logic; Aujourd'hui / 7 jours / Mois / Personnalisé / Toutes les dates); prefill applied; pickers disabled when no selected dataset supports them.
- UI-3 look (`cs.surface`, `SoftIconBadge`, `AppTheme` tokens), no colour literal outside `app_theme.dart`. Code/comments in English.
- `keevo/app/pubspec.yaml`: `archive: ^4.0.9` promoted from transitive to direct dependency (`flutter pub get --offline`); version `1.1.35+34` → `1.1.36+35`. No codegen (no new Drift table/column, no freezed/riverpod generator).
- Tests only through targeted `flutter test <files>` / `run-tests-chunked.sh app <dirs>` and `mvn -o test -Dtest=…` (never a full suite, never two suites in parallel).

**Never:**
- No network call in the local flow; no change to the server CSV/renderers/providers; no XLSX/PDF locally; no employee access.
- Never load a whole dataset in memory; never open the Drift DB from a background isolate; never write outside the app temp dir; no storage permission.
- No new Drift schema version, no backend migration.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Offline tile | exportOnline false | tile `export-local-csv-entry` enabled, subtitle « Contient uniquement… », tap opens `/exports/local` with current selection/store/period | — |
| Network error | launch → `NETWORK_ERROR` | error card + local tile shown | — |
| Single dataset | products selected, 3 products | `keevo-donnees-appareil-products-<ts>.csv`: BOM, header `Identifiant;Référence;Nom;…`, CRLF, 3 rows, sharer called | — |
| Multi dataset | products + sales | `.zip` with `products.csv`, `sales.csv`, each starting with BOM | — |
| Cell formats | price 1500, archived true, createdAt 2026-10-12T23:30Z, name `=SUM(A1)`, phone `+237 699` | `1500`, `Oui`, `13/10/2026 00:30:00`, `'=SUM(A1)`, `+237 699` | — |
| Labels | sale status CANCELLED, payment MOBILE_MONEY, report DAILY_COMBINED, unknown code X | `Annulée`, `Mobile Money`, `Fin de journée (toutes boutiques)`, `X` | — |
| Empty dataset | 0 local rows for reports | tile disabled « Aucune donnée sur cet appareil », not exported | — |
| Store/period | store A, period 7 jours, sales in A and B, old/new | only A sales within the inclusive bounds; products ignore the period | — |
| Volume | 10,000 sales | 10,000 data rows written, pages ≤ 500 | — |
| Write failure | writer throws `FileSystemException` | error card « Espace de stockage… », partial folder deleted, Réessayer re-runs | — |
| Contract drift | app catalogue label differs from JSON | `local_export_contract_test` fails | — |
| Employee | role EMPLOYEE opens `/exports/local` | redirected to `/pos` | — |

</intent-contract>

## Code Map

- `keevo/backend/src/test/java/com/keevo/reporting/export/ExportDatasetCatalogTest.java` -- GOLDEN schema + `allProviders()` (public static) + `describe()`; reuse in the new contract test.
- Backend label maps (READ ONLY, reflect): `catalog/product/.../ProductsExportDatasetProvider` STATUS; `catalog/stock/.../StockMovementsExportDatasetProvider` TYPES; `StockTransfersExportDatasetProvider` STATUS; `StockLevelsExportDatasetProvider` (stock state strings); `commerce/sale/adapter/out/export/SaleLabels` STATUS, PAYMENT_MODE; `inventory/counting/adapter/out/export/InventoryLabels` SCOPE, STATUS; `identity/employee/.../EmployeesExportDatasetProvider` STATUS; `reporting/report/.../ReportsExportDatasetProvider` TYPES, DELIVERY; `shared/infrastructure/export/AuditLogExportDatasetProvider` ROLES, SOURCES; `shared/infrastructure/export/ExportValues.OWNER_LABEL`.
- `keevo/backend/src/main/java/com/keevo/reporting/export/adapter/out/render/CsvExportRenderer.java`, `ExportCells.java` -- the cell/quoting rules to mirror.
- `keevo/app/lib/core/storage/*_table.dart`, `app_database.dart` -- Drift tables (money int XAF, `DateTime` stored as ISO text, bools). `AppDatabase.forTesting()` for tests. DB opened in main isolate only (`:392`).
- `keevo/app/lib/core/di/providers.dart:29` `appDatabaseProvider`, `currentUserRoleProvider`.
- `keevo/app/lib/features/export/presentation/page/export_page.dart` -- `:427-448` offline banner + disabled tile to enable; `_ErrorCard` `:745`; `_pickPeriod` `:215`, `enum _Period` `:34`, store picker; `_selection`/store/period state for the prefill.
- `keevo/app/lib/features/export/presentation/provider/export_providers.dart` -- `exportTempDirProvider`, `exportFileSharerProvider`, `exportOnlineProvider`, `exportStoreOptionsProvider` (reuse).
- `keevo/app/lib/features/export/data/remote_export_datasource.dart:117-172` -- `_writeFile` per-download folder + `_purgeOld` (extract the purge into a shared helper if reused).
- `keevo/app/lib/features/export/domain/model/export_models.dart:158` `ExportPrefill`; `domain/exception/export_exception.dart` (`storage`, `NETWORK_ERROR`).
- `keevo/app/lib/core/router/app_router.dart:695-714` -- add `/exports/local` before `/exports/:id`; `_ownerOnlyPrefixes` `:153` already covers it.
- Tests to extend: `test/features/export/presentation/export_page_test.dart:378` (offline tile now enabled), `test/core/router/export_routes_test.dart`, `test/core/router/global_auth_redirect_test.dart` (add `/exports/local`), helpers `test/features/export/export_test_helpers.dart`.

## Tasks & Acceptance

**Execution:**
- `keevo/contracts/export-datasets.json` -- generated by the backend test in write mode, then committed.
- `keevo/backend/src/test/java/com/keevo/reporting/export/ExportContractFileTest.java` -- compare/write mode per Always (Jackson `ObjectMapper` from test classpath, `LinkedHashMap` ordering).
- `keevo/app/lib/features/export/domain/local/local_export_schema.dart` -- `LocalColumnType` (TEXT, INTEGER, AMOUNT_XAF, DECIMAL, BOOLEAN, DATE, DATETIME), `LocalExportColumn`, `LocalExportDataset`, `localExportCatalog`, `exportLabels`, `exportOwnerLabel`, stock state constants, `exportLabel(map, code)` (null→null, unknown→code).
- `keevo/app/lib/features/export/domain/local/csv_cells.dart` -- pure `formatCsvCell(type, value)`, `safeCsvText`, `csvRecord(List<String>)` (minimal quoting, `;`, CRLF), `toWat(DateTime)`.
- `keevo/app/lib/features/export/data/local/local_export_reader.dart` -- `LocalExportReader(AppDatabase)`: `Future<Map<String,int>> counts()`, `Stream<List<List<Object?>>> pages(String key, LocalExportFilter filter, {int pageSize = 500})` implementing the Design Notes table.
- `keevo/app/lib/features/export/data/local/local_csv_writer.dart` -- worker-isolate writer: `start(Directory dir)`, `beginDataset(key, columns)`, `addRows(rows)`, `endDataset()`, `finish() → File`, `abort()`; zip when >1 dataset; `FileSystemException` → `ExportException.storage`.
- `keevo/app/lib/features/export/application/local_export_service.dart` (or `data/local/`) -- orchestrates reader → writer per selected dataset, progress callback, returns the file, deletes the folder on failure.
- `keevo/app/lib/features/export/presentation/provider/local_export_providers.dart` -- `localExportReaderProvider`, `localExportCountsProvider` (autoDispose), `localExportServiceProvider`.
- `keevo/app/lib/features/export/presentation/page/local_export_page.dart` -- page per Always (keys `local-export-dataset-<key>`, `local-export-all`, `local-export-scope-note`, `local-export-submit`, `local-export-retry`).
- `keevo/app/lib/features/export/presentation/page/export_page.dart` -- enable the tile (offline + NETWORK_ERROR), push with prefill.
- `keevo/app/lib/core/router/app_router.dart` -- route `/exports/local`.
- `keevo/app/pubspec.yaml` -- `archive: ^4.0.9`, version `1.1.36+35`.
- Tests (`keevo/app/test/features/export/`): `domain/local_export_contract_test.dart`; `domain/csv_cells_test.dart` (every type, WAT midnight crossing, guard incl. phone/negative numbers, quoting cases); `data/local_export_reader_test.dart` (in-memory DB: each dataset's columns/derivations from the Design Notes table, store + period filters, ordering, labels, unknown code, empty, paging 1,201 rows with pageSize 500); `data/local_csv_writer_test.dart` (BOM, CRLF, header, zip entries via `ZipDecoder`, storage error); `data/local_export_service_test.dart` (10,000 sales → 10,000 rows, folder cleaned on failure); `presentation/local_export_page_test.dart` (scope note, counts, disabled empty dataset, Tout, prefill, submit → sharer, error + Réessayer); update `export_page_test.dart` offline test (tile enabled + navigates) and add NETWORK_ERROR tile test; router tests for `/exports/local` (route resolves to `LocalExportPage`, EMPLOYEE redirected).

**Acceptance Criteria:**
- Given airplane mode, when the owner taps « CSV — données de cet appareil » and « Générer le CSV », then a CSV (or zip) is written and the share sheet is opened, with no network call.
- Given the generated file opened in French-locale Excel, then columns split on `;`, accents display correctly (BOM), dates read `dd/MM/yyyy`, and the headers equal the server CSV headers for the same dataset.
- Given 10,000 local sales, when exporting « Ventes », then the file has 10,000 data rows, rows are read in pages ≤ 500 and cells are formatted/written in a worker isolate.
- Given a column label changed in a backend provider, when backend tests run, then `ExportContractFileTest` fails; given the JSON updated without the app catalogue, then `local_export_contract_test` fails.

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 34 findings — high 0, medium 5, low 18, false 8, maybe-false 3
- findings:
  - `[false]` `[reject]` (intent) « Marked données de cet appareil » only in the file name — the marking is on the shared artifact (file name + share subject) and the page title; an in-file marker row would break the server-identical schema the same intent requires.
  - `[maybe-false]` `[reject]` (intent) French Excel checked at byte level only — needs a device/Excel run; bytes verified against commons-csv 1.10.0 output; would be low if real.
  - `[low]` `[reject]` (intent) DB reads/aggregation stay on the main isolate — forced by SQLCipher (`app_database.dart:392`, override not inherited by background isolates); pages ≤ 500 awaited between frames; moving the DB off-isolate is out of reach without reworking the DB bootstrap.
  - `[low]` `[reject]` (intent) No end-to-end offline → share test — layered tests (tile → route, page with fake service + sharer, service with real reader/writer) cover the chain; integration needs a device.
  - `[medium]` `[patch]` (intent) « Available locally » ignores filters — grouped with the empty-result rows below.
  - `[low]` `[reject]` (intent) Contract pins metadata, not value derivations / cell rendering — the intent's contract item is « JSON des colonnes »; cell rules mirrored and checked against commons-csv; a cross-language CSV golden is extra machinery.
  - `[low]` `[reject]` (intent) Tile note « synchronisées sur cet appareil » vs page note « y compris pas encore envoyées » — the tile wording is mandated verbatim by the routed item; the page note gives the precise scope.
  - `[false]` `[reject]` (intent) RBAC OWNER vs PRD « un utilisateur » — decision recorded in the spec: the UX journey covering FR97 is owner-scoped and the architecture marks export owner-only.
  - `[false]` `[reject]` (intent) Tile also shown after NETWORK_ERROR — additive to the offline entry point, recorded design decision, tested.
  - `[low]` `[reject]` (blind) OFFSET paging without a transaction may skip/repeat rows if sync writes meanwhile — the flow is used offline (no pull sync) from a dedicated screen; wrapping an async generator in one Drift transaction is non-trivial.
  - `[low]` `[reject]` (blind) `ORDER BY julianday()` re-sorts each page — keyset paging over the same expression still sorts; 10k-row SQLite sort is a few ms per page.
  - `[low]` `[reject]` (blind) stock_levels threshold lacks the product fallback — products have no threshold column on the device (recorded local gap).
  - `[low]` `[patch]` (blind) day_closures comment claims server parity for closureDate — comment corrected (WAT date of `closed_at`, approximation).
  - `[medium]` `[patch]` (blind) Audit criteria silently dropped in the local flow — criteria forwarded in the prefill and a note `local-export-criteria-note` shown.
  - `[low]` `[patch]` (blind) Leaving the page mid-generation orphans the file — generated folder deleted when the page is no longer mounted.
  - `[medium]` `[patch]` (blind) Counts ignore filters, header-only CSV shared — zero-row result: folder deleted, no share, message « Aucune donnée ne correspond à ces filtres sur cet appareil ».
  - `[low]` `[reject]` (blind) Notices contradict — same as the intent row.
  - `[false]` `[reject]` (blind) Prefilled datasets with no local rows vanish silently — they stay listed, disabled, with « Aucune donnée sur cet appareil ».
  - `[low]` `[reject]` (blind) Contract does not pin CSV output — same as the intent row.
  - `[low]` `[reject]` (blind) Period presets use device midnight, not WAT — same convention as 18-4 / audit (rejected there with reasons); target devices run in WAT.
  - `[low]` `[patch]` (blind) Writer/purge failure paths barely tested — local purge test added (grouped with the verification-gap row); worker-side failure tests rejected (errors are caught and mapped in the worker).
  - `[false]` `[reject]` (blind) pageSize ≥ 999 breaks SQLite variable limit — the only caller uses 500; SQLCipher 4 limit is 32766.
  - `[low]` `[reject]` (blind) `XFile` without mimeType — pre-existing 18-4 sharer, extension-based detection works on Android/iOS share sheets.
  - `[false]` `[reject]` (blind) Contract test path relative to cwd — `flutter test` always runs from the package root.
  - `[maybe-false]` `[reject]` (edge) Worker isolate death hangs `_call` — every command runs inside try/catch with a reply; only a VM-level crash would hang, which also kills the app; would be low.
  - `[false]` `[reject]` (edge) `message as List` outside try — the only sender is `LocalCsvWriter._call`, always a List.
  - `[low]` `[reject]` (edge) OFFSET paging during sync — same as the blind row.
  - `[medium]` `[patch]` (edge) Zero matching rows → header-only CSV shared — same fix as the blind row.
  - `[low]` `[patch]` (edge) Pop during generation leaves orphan file — same fix as the blind row.
  - `[medium]` `[patch]` (edge) Audit criteria dropped — same fix as the blind row.
  - `[false]` `[reject]` (edge, claim) Reader « same filters » claim vs audit criteria — the spec claims store/period parity only; criteria now surfaced by the note.
  - `[low]` `[patch]` (verification-gap) Store/period wiring unpinned for 7 datasets — filtered fixtures added in `local_export_reader_test.dart`.
  - `[low]` `[patch]` (verification-gap) Local 24 h purge untested — service test added.
  - `[maybe-false]` `[reject]` (verification-gap) PDF mode prefill forwards the CSV selection — local export is CSV-only, forwarding the dataset selection is the sensible mapping; would be low.

## Design Notes

**Decisions.** RBAC owner-only (see Always). Local export offered when offline or when the server is unreachable, not as a third format online (UX: « Hors ligne : seul CSV — données de cet appareil est proposé »). DB reads stay on the main isolate in small awaited pages; formatting + file I/O + zip in the worker isolate — this is the only isolate split compatible with SQLCipher here.

**Local derivations** (server semantics from the 18-2 providers; local gaps noted). Actor name = latest `employees` row with `userId == id` → `"first last"` trimmed (non-empty), else `users` row with role OWNER → `Propriétaire`, else null. Store name via `stores` map. `stockStatus`: q≤0 → `Rupture`; t>0 && q≤t → `Stock bas`; else `OK`. Order/period column per dataset:

| key | order (asc) / period col / store col | local notes |
|---|---|---|
| products | createdAt,id / – / store only scopes `stockQuantity` (sum of stock_levels.quantity) | minimumThreshold null (not on device); suppliers = names sorted, `", "` |
| categories | createdAt,id | parent = parent name |
| stock_levels | store name, product name, id / – / storeId | threshold = sl.min>0 ? sl.min : 0; stockValue = q≤0 ? 0 : q×buyPrice |
| stock_movements | createdAt,id / createdAt / storeId | type labels; notes = reason |
| stock_transfers | occurredAt,id / occurredAt / source OR destination | names from stores/products, fallback to snapshots |
| sales | coalesce(occurredAt,createdAt),id / same / storeId | employee = actor(employeeId); itemCount = Σ item quantity; status null → empty |
| sale_items | sale date, saleId, item id / sale date / sale.storeId | product = item productName snapshot |
| day_closures | closedAt,id / closedAt / storeId | closureDate = WAT date of closedAt |
| inventory_sessions | startedAt,id / startedAt / storeId | counted/gaps/totalGap over counts with physical≠null |
| inventory_counts | session startedAt, sessionId, id / session startedAt / session storeId | gap = physical−theoretical or null |
| reports | createdAt,id / createdAt / storeId | store = store name ?? storeName; actor = actor(actorId); reportDate DATE |
| clients | createdAt,id | salesCount/totalSpent/lastPurchaseAt over local COMPLETED sales |
| suppliers | createdAt,id | productCount = product_suppliers links |
| employees | createdAt,id / – / storeId | phone = users.phoneNumber where users.id = userId, else null |
| stores | createdAt,id / – / id | type raw code |
| audit_log | occurredAt DESC, id DESC / occurredAt / storeId | actor: employee name → metadata.actorLabel → role fallback (`Le propriétaire`/`Un employé`/`Un utilisateur`); actorRole label from users/employees; action/entityType raw |

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -o test -Dtest=ExportContractFileTest,ExportDatasetCatalogTest -DforkCount=1 -DargLine=-Xmx1536m` -- expected: pass.
- `cd keevo/app && flutter pub get --offline && flutter analyze` -- expected: 0 errors, 0 warnings.
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh app test/features/export` -- expected: all pass.
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh app test/core` -- expected: all pass.

## Auto Run Result

Status: done

**Summary:** On-device CSV export (FR97). A shared contract `keevo/contracts/export-datasets.json` (16 datasets, columns, 14 code→French label maps, constants) is checked against the real providers by the backend `ExportContractFileTest` and against the Dart catalogue by the app `local_export_contract_test`. The app reads each dataset from Drift in pages of at most 500 rows on the main isolate, because SQLCipher can't be opened from another isolate. A worker isolate formats the cells exactly like `CsvExportRenderer` (BOM, `;`, CRLF, WAT dates, Oui/Non, formula guard, commons-csv minimal quoting) and zips several datasets. The new « Données de cet appareil » page (`/exports/local`, OWNER) shows a scope note, local counts and the store/period filters, writes a temp file and opens the share sheet. The tile on the Exporter screen is enabled when offline or after a NETWORK_ERROR.

**Files changed:**
- `keevo/contracts/export-datasets.json` -- shared column / label contract (generated).
- `keevo/backend/src/test/java/com/keevo/reporting/export/ExportContractFileTest.java` -- compare/write contract test; `ExportDatasetCatalogTest.java` -- `goldenKeys()` accessor.
- `keevo/app/lib/features/export/domain/local/{local_export_schema,csv_cells}.dart` -- catalogue, labels, cell rules.
- `keevo/app/lib/features/export/data/local/{local_export_reader,local_csv_writer,local_export_service}.dart` -- Drift pages, isolate writer, orchestration.
- `keevo/app/lib/features/export/data/export_temp_files.dart` -- shared 24 h purge (remote datasource now uses it).
- `keevo/app/lib/features/export/presentation/page/local_export_page.dart`, `provider/local_export_providers.dart`, `widget/export_form_widgets.dart` (pickers extracted from `export_page.dart`).
- `keevo/app/lib/features/export/presentation/page/export_page.dart` -- tile enabled + prefill (incl. criteria); `domain/exception/export_exception.dart` -- `localFailed`, `isStorage`.
- `keevo/app/lib/core/router/app_router.dart` -- `/exports/local`.
- `keevo/app/pubspec.yaml` -- `archive: ^4.0.9`, 1.1.36+35.
- Tests: 6 new files under `test/features/export/`, plus `export_page_test`, `export_routes_test`, `global_auth_redirect_test` and `export_test_helpers` updated.

**Review:** 34 findings (0 high, 5 medium, 18 low, 8 false, 3 maybe-false). Patches cover 4 medium root causes: the zero-row result, dropped audit criteria (reported by several reviewers), plus related low items (orphan file on pop, closureDate comment, filter tests for 7 datasets, local purge test). Nothing was deferred. The rejected findings and their reasons are in the triage log.

**Follow-up review recommendation:** true. On this first pass, two or more medium entries were patched. Unverified risk: the patched page flows (zero-row discard, criteria note, unmounted discard) were reviewed only through their own tests, not by a second review pass.

**Verification:**
- Backend `ExportContractFileTest` + `ExportDatasetCatalogTest`: 4/4 pass, and a mutated label fails the test.
- `flutter analyze`: 0 errors, 0 warnings (614 infos).
- Chunked app batches after patches:
  - `test/features/export`: 130 pass
  - `test/core`: 469 pass

**Residual risks:**
- No device run yet (share sheet, real SQLCipher DB, frame timing at 10k rows).
- Main-isolate page queries re-sort with `julianday()` on each page.
- Local data gaps compared with the server: no product threshold, `closureDate` derived from `closed_at`, sales and movements purged after 30 days. These are disclosed in the scope note, except the threshold.
- RBAC is OWNER-only, against the PRD wording « un utilisateur ». This is a decision, recorded in the spec.
