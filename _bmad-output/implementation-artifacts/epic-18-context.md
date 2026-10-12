# Epic 18 Context: Export de données & Audit détaillé (Piste V1, tag C)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Let the owner take all of their business data out of Keevo, in the format they choose, and understand every action the system records. Server-side export covers the full, multi-store data (CSV, Excel, branded PDF templates, large exports run in the background). On-device export produces a CSV of local data, even offline. The audit log gains who, what, on what, where and how fields, and the app gets a clickable "Historique des actions" screen. This is tag C work: it ships in V1 and the modular refonte takes it over unchanged, so `reporting/export` is a self-contained hexagonal module and each domain contributes its own dataset provider. The epic replaces the old `17-6-exports-pdf-excel` and `v1s-14-7-export-pdf-excel` stories.

## Stories

- Story 18.1: Socle du module export (backend) — done
- Story 18.2: Jeux de données exportables
- Story 18.3: Modèles PDF
- Story 18.4: Écran « Exporter » (app)
- Story 18.5: Export local hors ligne (CSV)
- Story 18.6: Audit enrichi (backend) — done
- Story 18.7: Journal d'audit consultable (app) — done

## Requirements & Constraints

- **Formats and gating:** CSV on every plan (data portability). Excel, PDF and background exports are Premium (`PREMIUM` or `PREMIUM_TRIAL` with an active subscription; no subscription = FREE). Free plans get 403 `PLAN_FEATURE_REQUIRED` (`details.feature` = `EXPORT_XLSX` / `EXPORT_PDF`) from the backend and the offer sheet in the app.
- **Datasets:** products (with variants and prices), stock levels, movements, sales with lines, closures, inventory sessions and counts, clients, suppliers, team (no sensitive data), audit log. Each is filterable by store (one or all) and period; only matching rows may be included.
- **Data conventions:** amounts are integer XAF, dates in WAT (`Africa/Douala`). Column schemas are stable and documented; server CSV and on-device CSV share the same schema.
- **PDF templates:** Fin de journée, Rapport d'inventaire (gaps), État de stock, Liste des ventes. Shared brand header (store, logo if present, period, generation date). A4, correct accents, repeated headers and page numbers on multi-page documents. End-of-day PDF figures must equal the end-of-day report of the same closure.
- **Large exports:** run in the background, owner notified when ready, file kept 24 h; expired download returns 410. 50,000 rows must stream without exhausting memory.
- **Offline export (any user, per its requirement):** CSV only, from device data, marked « données de cet appareil », opens directly in French-locale Excel; 10,000 rows without UI jank.
- **Audit:** every entry records identity, timestamp, store, readable object label, device, source (online/sync) and field-by-field before/after. Owner can filter (period, store, employee, action, object) and open full detail. Entries are immutable; pre-migration entries stay readable.
- **Security:** server export and audit-log access are owner-only (EMPLOYEE 403 / hidden screen). Exports are tenant-isolated and rate-limited per tenant.

## Technical Decisions

- **Tenancy:** schema per tenant (`kv_xxxxxx`). New tenant tables need tenant DDL (`TenantSchemaProvisioner` + `TenantSchemaSyncService`) and a public Flyway template (entities are validated against `public`). Last migration is V15 (`export_jobs`).
- **Dataset port (18.1):** `ExportDatasetProvider` lives in `shared/application/port/export` (avoids Modulith cycles; the export module never reads tables). Contract: `key()` (`[a-z][a-z0-9_]{0,39}`, unique, checked at startup), French `label()`, typed `columns()` (`TEXT, INTEGER, AMOUNT_XAF, DECIMAL, BOOLEAN, DATE, DATETIME`), `count(ExportFilter)`, `readPage(filter, cursor, limit)` returning rows in column order plus an opaque keyset `nextCursor` (null when exhausted). Providers live in their owning domain module (`<module>/adapter/out/export/`), read only through the tenant-routed JPA `EntityManager` (never `JdbcTemplate`), use strictly increasing keyset pages (e.g. `(created_at, id)`), read-only transaction per call. Demo provider: `stores`.
- **Renderers:** `ExportRenderer` registry (streaming `RenderSession`). CSV: UTF-8 BOM, `;`, CRLF, multi-dataset = zip of `<key>.csv`, formula-injection guard (skips numeric-like values). Cell formats 18.5 must reuse: `dd/MM/yyyy`, `dd/MM/yyyy HH:mm:ss` WAT, decimal comma, Oui/Non. XLSX: POI SXSSF 5.4.1, one sheet per dataset, frozen bold header, `#,##0 "XAF"`. PDF (18.3): Thymeleaf + `io.github.openhtmltopdf:openhtmltopdf-pdfbox` 1.1.22, versioned templates in code; until then a PDF request returns 400 `EXPORT_FORMAT_UNSUPPORTED`.
- **Export flow:** `POST /api/v1/exports` — DIRECT (≤ 5,000 rows, or any FREE CSV, streamed on the request thread) vs BACKGROUND Premium `ExportJob` (PENDING → RUNNING → READY/FAILED → EXPIRED) on a local-disk `ExportFileStorage`, 24 h retention, hourly purge, FCM `EXPORT_READY` / `EXPORT_FAILED` with deep link `/exports/{id}`. Other routes: `GET /api/v1/exports` (Mes exports: background jobs only, signed `downloadUrl` when READY), `/{id}`, `/{id}/file`, `/datasets` (key, label, columns), public HMAC-signed `/download?token=`. Errors: 404 `EXPORT_NOT_FOUND`, 409 `EXPORT_NOT_READY`, 410 `EXPORT_EXPIRED`, 429 `RATE_LIMIT_EXCEEDED` / `EXPORT_QUEUE_FULL`, 400 `EXPORT_DATASET_UNKNOWN`. Every export emits `DATA_EXPORTED` audit (format, dataset labels, filters, row count, mode).
- **Audit enrichment (18.6):** `audit_log` gained `store_id`, `entity_label`, `device_id`, `source` (`ONLINE`|`SYNC`|`SYSTEM`), `metadata` jsonb and indexes; new entries store field-by-field JSON before/after. Producers flow through domain events → `AuditEventListener`. DB-level immutability via BEFORE UPDATE/DELETE/TRUNCATE triggers. `GET /api/v1/audit` (filters + `q`, cursor paging, actor resolved) and `GET /api/v1/audit/{id}`.
- **Audit app (18.7):** `auditEntries` sync pull is OWNER-only; enriched rows kept in Drift for offline list; filter model `AuditFilter` (period, store, employee, action, object type, `q`). Sales show « Vente de X XAF » (no receipt number).
- **App-side export:** CSV built from Drift in an isolate, shared via `share_plus`.

## UX & Interaction Patterns

- **"Exporter mes données":** entry from Settings or a pre-filled **Exporter** button on Rapports, Stock, Ventes, Historique des actions. Steps: datasets (checkboxes + « Tout ») → filters (store; Aujourd'hui, 7 jours, Mois, Personnalisé) → format (CSV, Excel and PDF with Premium badge; PDF adds a template choice). Small export: download then share sheet. Large: « Votre export se prépare, nous vous prévenons dès qu'il est prêt », notification opens the file, entry in **Mes exports**. Offline: only « CSV — données de cet appareil » with note « Contient uniquement les données synchronisées sur cet appareil ». Errors: clear message + retry. Background exports survive app close.
- **Historique des actions (owner-only, delivered):** readable sentence list grouped by day, filter chips, detail Qui / Quoi / Sur quoi / Quand et où / Changements / Contexte. Its **Exporter** button is owned by 18.4.

## Cross-Story Dependencies

- Order: 18.6 → 18.7 (done), 18.1 (done) → 18.2 → 18.4 → 18.3 → 18.5.
- 18.2 adds one provider per domain on the 18.1 port; the audit-log dataset uses the 18.6 enriched columns. Its column schemas are the contract for 18.5.
- 18.3 plugs a `PdfRenderer` into the 18.1 registry and must reuse the existing end-of-day `ClosureWindow`.
- 18.4 consumes the 18.1 endpoints/FCM types, 18.2 datasets and 18.3 templates. Routed item: add « Exporter » to the app bar of `audit_log_page.dart`, pre-filled with the « Journal d'audit » dataset and the current `AuditFilter` (map it to export filters) — resolve or reject with a recorded reason.
- 18.5 reuses the 18.2 column schema and the 18.1 CSV cell formats.
- Outside the epic: plan gating (subscription module), FCM notifications, per-tenant rate limiting; employee activity reporting (refonte 17-3) may later build on the enriched audit log.
