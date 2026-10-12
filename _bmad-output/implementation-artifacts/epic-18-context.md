# Epic 18 Context: Export de données & Audit détaillé (Piste V1, tag C)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Let the owner take all of their business data out of Keevo in the format they choose, and understand every action the system records. Server-side export covers the full multi-store data: CSV, Excel and branded PDF templates, with large exports run in the background. On-device export produces a CSV of local data, even offline. The audit log records who, what, on what, where and how, and the app gets a clickable "Historique des actions" screen. This is tag C work: it ships in V1 and the modular refonte takes it over unchanged, so `reporting/export` is a self-contained hexagonal module and each domain contributes its own dataset provider. The epic replaces the old `17-6` and `v1s-14-7` export stories.

## Stories

- Story 18.1: Socle du module export (backend) (done)
- Story 18.2: Jeux de données exportables (done)
- Story 18.3: Modèles PDF (done)
- Story 18.4: Écran « Exporter » (app) (done)
- Story 18.5: Export local hors ligne (CSV)
- Story 18.6: Audit enrichi (backend) (done)
- Story 18.7: Journal d'audit consultable (app) (done)

## Requirements & Constraints

- **Formats and gating:** CSV is available on every plan (data portability). Excel, PDF and background exports are Premium. On a Free plan the backend returns 403 `PLAN_FEATURE_REQUIRED` and the app opens the offer sheet.
- **Datasets:** every business dataset can be exported, filtered by store (one or all) and by period, within the filters each dataset declares. Only matching rows are included.
- **Data conventions:** amounts are integer XAF and dates are in WAT (`Africa/Douala`). Column schemas are stable and documented, and the server CSV and the on-device CSV share the same schema.
- **Large exports:** they run in the background, the owner is notified when the file is ready, and the file is kept for 24 h. Downloading an expired file returns 410. A 50,000-row export must stream without exhausting memory.
- **Offline export (any user, 18.5):** CSV only, built from device data. The file is marked « données de cet appareil », opens directly in French-locale Excel and is shared through the share sheet. It must work in airplane mode, and 10,000 rows must be generated without UI jank.
- **Audit:** each entry is immutable and records identity, timestamp, store, a readable object label, device, source and field-by-field before/after values. Entries written before the migration stay readable.
- **Security:** server export and the audit log are owner-only: an EMPLOYEE gets 403 and the screens are hidden. Exports are tenant-isolated and rate-limited per tenant.

## Technical Decisions

- **Tenancy:** schema per tenant (`kv_xxxxxx`). New tables or columns need tenant DDL plus a public Flyway migration. The latest migration is V16, so the next one is V17.
- **Dataset port:** `ExportDatasetProvider` lives in `shared/application/port/export` and has `key()`, a French `label()` and typed `columns()` (`TEXT, INTEGER, AMOUNT_XAF, DECIMAL, BOOLEAN, DATE, DATETIME`). It reads pages by keyset, and it declares whether it supports the store filter and the period filter and which criteria keys it accepts. Each provider lives in its owning module (`<module>/adapter/out/export/`).
- **The 16 datasets:** `products`, `categories`, `stock_levels`, `stock_movements`, `stock_transfers`, `clients`, `suppliers`, `sales`, `sale_items`, `day_closures`, `inventory_sessions`, `inventory_counts`, `employees` (no credentials), `stores`, `reports`, `audit_log`.
  - Event datasets filter the period on their event column. Reference datasets (products, categories, stock_levels, clients, suppliers, employees, stores) ignore the period.
  - Archived and cancelled rows are exported with a status column.
  - Column keys are camelCase with French labels, and enum codes are rendered as French labels.
  - The column schema is pinned by `ExportDatasetCatalogTest`. The test does not pin the code→French label maps, which live in each provider.
- **CSV format (server; 18.5 must match it):** UTF-8 with BOM, `;` separator, CRLF line endings, and a guard against formula injection. A multi-dataset export is a zip of `<key>.csv` files. Cells use `dd/MM/yyyy` for dates, `dd/MM/yyyy HH:mm:ss` in WAT for timestamps, a decimal comma and Oui/Non for booleans.
- **Other renderers:** XLSX uses POI SXSSF with one sheet per dataset. PDF uses Thymeleaf and OpenHTMLtoPDF, and the template is the single requested dataset: `day_closures`, `inventory_counts`, `stock_levels` or `sales`. PDF exports are bounded and return 422 `EXPORT_PDF_TOO_LARGE` when too big.
- **Export API:**
  - `POST /api/v1/exports` either streams the file directly (up to 5,000 rows, or any FREE CSV) or creates a Premium background `ExportJob`, which sends FCM `EXPORT_READY` / `EXPORT_FAILED` linking to `/exports/{id}`.
  - Other endpoints: `GET /api/v1/exports` for « Mes exports », `/{id}`, `/{id}/file`, `/datasets` (metadata including `criteria` and `pdfTemplate`), and a signed `/download?token=`.
  - Every export is audited as `DATA_EXPORTED`.
  - Criteria (`userId`, `action`, `entityType`, `entityId`, `q`) are declared only by `audit_log`. They apply to the whole request, so sending a criterion that one of the requested datasets does not declare returns 400.
- **Audit:** `audit_log` has `store_id`, `entity_label`, `device_id`, `source` (`ONLINE`|`SYNC`|`SYSTEM`) and `metadata` jsonb, with DB-level immutability triggers. `GET /api/v1/audit` supports filters and cursor pagination, and `/{id}` returns one entry. The `auditEntries` sync pull is OWNER-only, and the app stores the enriched rows in Drift.
- **App export feature:** lives in `keevo/app/lib/features/export/` (`export_page.dart`, `export_models.dart` with `buildExportRequest`, `remote_export_datasource.dart`). Routes `/exports`, `/exports/new` and `/exports/:id` are OWNER-only. Direct exports are written to a temp file and then shared with `share_plus`.
- **On-device CSV (18.5):** generated from Drift in an isolate, for the datasets available locally, using the server column schema (keys, French labels, types), the server label maps and the server cell formats. The file is shared via `share_plus`.

## UX & Interaction Patterns

- **"Exporter mes données":** opened from Settings, or from a pre-filled Exporter button on Rapports, Stock, Ventes and Historique des actions. The flow goes datasets → filters (store; Aujourd'hui / 7 jours / Mois / Personnalisé) → format (Excel and PDF carry a Premium badge; PDF adds a template choice). A small export downloads and then opens the share sheet. A large one shows « Votre export se prépare… », and its notification opens the file. Errors show a clear message with a retry.
- **Offline:** only « CSV — données de cet appareil » is offered, with the note « Contient uniquement les données synchronisées sur cet appareil ». The entry tile already exists, disabled, under the offline banner (key `export-local-csv-entry`, « Bientôt disponible »).
- **Historique des actions (delivered):** a list of readable sentences grouped by day, with filter chips. The detail view shows Qui / Quoi / Sur quoi / Quand et où / Changements / Contexte.

## Cross-Story Dependencies

- Delivery order: 18.6 → 18.7, then 18.1 → 18.2 → 18.4 → 18.3 → 18.5. Everything except 18.5 is done.
- 18.5 depends on the 18.2 column schema (`ExportDatasetCatalogTest`) and each provider's code→French label maps, the 18.1 CSV cell formats, and the 18.4 « Exporter » screen. Its routed item is to enable the disabled offline tile and route it to the on-device CSV flow while keeping the « synchronisées sur cet appareil » note.
- Dependencies outside the epic: plan gating (subscription module), FCM notifications, per-tenant rate limiting.
