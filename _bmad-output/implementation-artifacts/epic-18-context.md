# Epic 18 Context: Export de données & Audit détaillé (Piste V1, tag C)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Let the owner take all of their business data out of Keevo, in the format they choose, and understand every action the system records. Server-side export covers the full, multi-store data (CSV, Excel, branded PDF templates, large exports run in the background). On-device export produces a CSV of local data, even offline. The audit log gains who, what, on what, where and how fields, and the app gets a clickable "Historique des actions" screen. This is tag C work: it ships in V1 and the modular refonte takes it over unchanged, so `reporting/export` is a self-contained hexagonal module and each domain contributes its own dataset provider. The epic replaces the old `17-6-exports-pdf-excel` and `v1s-14-7-export-pdf-excel` stories.

## Stories

- Story 18.1: Socle du module export (backend) — done
- Story 18.2: Jeux de données exportables — done
- Story 18.3: Modèles PDF
- Story 18.4: Écran « Exporter » (app) — in progress
- Story 18.5: Export local hors ligne (CSV)
- Story 18.6: Audit enrichi (backend) — done
- Story 18.7: Journal d'audit consultable (app) — done

## Requirements & Constraints

- **Formats and gating:** CSV on every plan (data portability). Excel, PDF and background exports are Premium (`PREMIUM` or `PREMIUM_TRIAL` with an active subscription; no subscription = FREE). Free plans get 403 `PLAN_FEATURE_REQUIRED` (`details.feature` = `EXPORT_XLSX` / `EXPORT_PDF`) from the backend and the offer sheet in the app.
- **Datasets:** every business dataset is exportable, filterable by store (one or all) and period, with only matching rows included — limited to the filters each dataset declares.
- **Data conventions:** amounts are integer XAF, dates in WAT (`Africa/Douala`). Column schemas are stable and documented; server CSV and on-device CSV share the same schema.
- **PDF templates:** Fin de journée, Rapport d'inventaire (gaps), État de stock, Liste des ventes. Shared brand header (store, logo if present, period, generation date). A4, correct accents, repeated headers and page numbers on multi-page documents. End-of-day PDF figures must equal the end-of-day report of the same closure.
- **Large exports:** run in the background, owner notified when ready, file kept 24 h; expired download returns 410. 50,000 rows must stream without exhausting memory.
- **Export screen (18.4):** Free plan picking Excel/PDF opens the offer sheet; a background export survives closing the app and is listed in Mes exports; an export error shows a clear message with a retry.
- **Offline export (any user):** CSV only, from device data, marked « données de cet appareil », opens directly in French-locale Excel; 10,000 rows without UI jank.
- **Audit:** every entry records identity, timestamp, store, readable object label, device, source (online/sync) and field-by-field before/after. Owner can filter (period, store, employee, action, object) and open full detail. Entries are immutable; pre-migration entries stay readable.
- **Security:** server export and audit-log access are owner-only (EMPLOYEE 403 / hidden screen). Exports are tenant-isolated and rate-limited per tenant.

## Technical Decisions

- **Tenancy:** schema per tenant (`kv_xxxxxx`). New tenant tables/columns need tenant DDL (`TenantSchemaProvisioner` + `TenantSchemaSyncService`) and a public Flyway migration (entities validated against `public`). Last migration is V16 (`export_jobs.criteria`); next is V17.
- **Dataset port:** `ExportDatasetProvider` in `shared/application/port/export` (avoids Modulith cycles; the export module never reads domain tables). Contract: `key()`, French `label()`, typed `columns()` (`TEXT, INTEGER, AMOUNT_XAF, DECIMAL, BOOLEAN, DATE, DATETIME`), `count`, keyset `readPage` with opaque cursor, plus `supportsStoreFilter()`, `supportsPeriodFilter()`, `criteriaKeys()`. `ExportFilter` carries store, period and a `criteria` map. Providers live in their owning module (`<module>/adapter/out/export/`) on the shared `NativeKeysetExportDataset` helper (native SQL via tenant-routed `EntityManager`).
- **Delivered datasets (16):** `products`, `categories`, `stock_levels`, `stock_movements`, `stock_transfers`, `clients`, `suppliers`, `sales`, `sale_items`, `day_closures`, `inventory_sessions`, `inventory_counts`, `employees` (no credentials), `stores`, `reports`, `audit_log` (« Journal d'audit »). Event datasets filter the period on their event column; reference datasets (products, categories, stock_levels, clients, suppliers, employees, stores) ignore the period. Archived/cancelled rows are exported with a status column. No variant dataset (no server-side variant catalogue). Column keys camelCase, French labels, enum codes rendered as French labels. The schema is pinned by `ExportDatasetCatalogTest` — the contract for 18.5.
- **Criteria:** only `audit_log` declares criteria (`userId`, `action`, `entityType`, `entityId`, `q` ≤ 100); results equal `GET /api/v1/audit` with the same filters. Criteria are request-global: sending a criterion that any requested dataset does not declare, or a malformed value, returns 400 `VALIDATION_ERROR`. Criteria are persisted on background jobs.
- **Renderers:** `ExportRenderer` registry (streaming). CSV: UTF-8 BOM, `;`, CRLF, multi-dataset = zip of `<key>.csv`, formula-injection guard; cell formats `dd/MM/yyyy`, `dd/MM/yyyy HH:mm:ss` WAT, decimal comma, Oui/Non (18.5 must reuse them). XLSX: POI SXSSF, one sheet per dataset, frozen bold header, `#,##0 "XAF"`. PDF (18.3): Thymeleaf + `openhtmltopdf-pdfbox` 1.1.22, versioned templates in code; until then a PDF request returns 400 `EXPORT_FORMAT_UNSUPPORTED`.
- **Export API:** `POST /api/v1/exports` — DIRECT (≤ 5,000 rows, or any FREE CSV, streamed in the response) vs BACKGROUND Premium `ExportJob` (PENDING → RUNNING → READY/FAILED → EXPIRED), local-disk storage, 24 h retention, hourly purge, FCM `EXPORT_READY` / `EXPORT_FAILED` with deep link `/exports/{id}`. `GET /api/v1/exports` (Mes exports: background jobs only, signed `downloadUrl` when READY), `/{id}`, `/{id}/file`, `/datasets` (key, label, columns, `storeFilter`, `periodFilter`, `criteria` keys), public HMAC-signed `/download?token=`. Errors: 404 `EXPORT_NOT_FOUND`, 409 `EXPORT_NOT_READY`, 410 `EXPORT_EXPIRED`, 429 `RATE_LIMIT_EXCEEDED` / `EXPORT_QUEUE_FULL`, 400 `EXPORT_DATASET_UNKNOWN`. Every export emits `DATA_EXPORTED` audit (format, datasets, filters, criteria, row count, mode).
- **Audit (18.6/18.7):** `audit_log` has `store_id`, `entity_label`, `device_id`, `source` (`ONLINE`|`SYNC`|`SYSTEM`), `metadata` jsonb; DB-level immutability triggers. `GET /api/v1/audit` (filters + `q`, cursor) and `/{id}`. App: `auditEntries` sync pull OWNER-only, enriched rows in Drift, filter model `AuditFilter` (period, store, employee, action, object type, `q`) in `keevo/app/lib/features/audit/domain/model/audit_filter.dart`.
- **App-side export:** files shared via `share_plus`; on-device CSV built from Drift in an isolate.

## UX & Interaction Patterns

- **"Exporter mes données":** entry from Settings or a pre-filled **Exporter** button on Rapports, Stock, Ventes, Historique des actions. Steps: datasets (checkboxes + « Tout ») → filters (store; Aujourd'hui, 7 jours, Mois, Personnalisé) → format (CSV, Excel and PDF with Premium badge; PDF adds a template choice). Small export: download then share sheet. Large: « Votre export se prépare, nous vous prévenons dès qu'il est prêt », notification opens the file, entry in **Mes exports**. Offline: only « CSV — données de cet appareil » with note « Contient uniquement les données synchronisées sur cet appareil ». Errors: clear message + retry.
- **Historique des actions (delivered, owner-only):** readable sentence list grouped by day, filter chips, detail Qui / Quoi / Sur quoi / Quand et où / Changements / Contexte. Its **Exporter** button is owned by 18.4.

## Cross-Story Dependencies

- Order: 18.6 → 18.7 (done), 18.1 → 18.2 (done) → 18.4 → 18.3 → 18.5.
- 18.4 consumes the 18.1 endpoints/FCM types and the 18.2 datasets (read `GET /api/v1/exports/datasets` for labels and applicable filters). PDF is not available until 18.3 (server returns `EXPORT_FORMAT_UNSUPPORTED`). Routed items to resolve or reject with a recorded reason: (1) « Exporter » in the app bar of `audit_log_page.dart`, pre-filled with `audit_log` and the current `AuditFilter` mapped to store/period/criteria; (2) send `criteria` only when « Journal d'audit » is the sole dataset — drop them when the user adds other datasets.
- 18.3 plugs a `PdfRenderer` into the 18.1 registry and must reuse the existing end-of-day `ClosureWindow`.
- 18.5 reuses the 18.2 column schema verbatim (keys, labels, types per `ExportDatasetCatalogTest`) and each provider's code→French label maps, plus the 18.1 CSV cell formats.
- Outside the epic: plan gating (subscription module), FCM notifications, per-tenant rate limiting; employee activity reporting (refonte 17-3) may later build on the enriched audit log.
