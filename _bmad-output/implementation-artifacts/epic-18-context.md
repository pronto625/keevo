# Epic 18 Context: Export de données & Audit détaillé (Piste V1, tag C)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Let the owner take all of their business data out of Keevo, in the format they choose, and understand every action the system records. Server-side export covers the full, multi-store data (CSV, Excel, branded PDF templates, large exports run in the background). On-device export produces a CSV of local data, even offline. The audit log gains who, what, on what, where and how fields, and the app gets a clickable "Historique des actions" screen. This is tag C work: it ships in V1 and the modular refonte takes it over unchanged. That is why `reporting/export` is a self-contained hexagonal module and each domain contributes its own dataset provider. The epic replaces the old `17-6-exports-pdf-excel` and `v1s-14-7-export-pdf-excel` stories.

## Stories

- Story 18.1: Socle du module export (backend)
- Story 18.2: Jeux de données exportables
- Story 18.3: Modèles PDF
- Story 18.4: Écran « Exporter » (app)
- Story 18.5: Export local hors ligne (CSV)
- Story 18.6: Audit enrichi (backend)
- Story 18.7: Journal d'audit consultable (app)

## Requirements & Constraints

- **Formats and gating:** CSV is available on every plan, for data portability. Excel, PDF templates and background exports are Premium. A Free plan that asks for them gets `PLAN_FEATURE_REQUIRED` from the backend and the offer sheet in the app.
- **Datasets:** products (with variants and prices), stock levels, movements, sales with lines, closures, inventory sessions and counts, clients, suppliers, team (no sensitive data), and the audit log. Each can be filtered by store (one or all) and by period.
- **Data conventions:** amounts are integer XAF and dates are in WAT. Column schemas are stable and documented, and server and on-device CSV use the same schema.
- **PDF templates:** Fin de journée, Rapport d'inventaire (gaps), État de stock and Liste des ventes. Each has a shared brand header (store, logo if present, period, generation date). Format is A4, accents must render, and multi-page documents repeat headers and number pages. End-of-day PDF figures must equal the end-of-day report for the same closure.
- **Large exports:** these run in the background, the owner is notified when the file is ready, and the file stays available for 24 h. Downloading an expired file returns 410. A 50,000-row export must stream without running out of memory.
- **Offline export:** CSV only, built from data on the device and marked « données de cet appareil ». It must open directly in French-locale Excel. 10,000 rows must generate without UI jank.
- **Audit fields:** every audit entry records identity, timestamp, store, a readable object label, device, source (online or sync) and field-by-field before/after values.
- **Audit access:** the owner can filter the log (period, store, employee, action type, object) and open the full detail of any entry.
- **Audit immutability:** users can never modify or delete audit entries. Entries written before the migration must stay readable.
- **Security:** export and audit-log access are owner-only, and employees get 403 or a hidden screen. Exports are tenant-isolated, so no export may ever contain another tenant's rows. Exports are rate-limited per tenant.

## Technical Decisions

- **Tenancy:** each tenant has its own schema (`kv_xxxxxx`). Audit-log schema changes need both the tenant DDL and a Flyway migration.
- **Export module (`reporting/export`):** the module never reads a table directly.
  - Each domain implements the `ExportDatasetProvider` port: key, label, typed columns, and a paginated read filtered by tenant, store and period. It mirrors the sync `DeltaEntityProvider`.
  - A new domain adds a provider without changing the module.
- **Renderers:** the `ExportRenderer` port (Strategy pattern, with a registry) has three implementations:
  - `CsvRenderer`: commons-csv, UTF-8 with BOM, `;` separator.
  - `XlsxRenderer`: Apache POI SXSSF streaming, one sheet per dataset, frozen headers, XAF and date formats.
  - `PdfRenderer`: versioned Thymeleaf templates in code, rendered with OpenHTMLtoPDF. Templates are not user-editable in V1.
  - Confirm the Excel and PDF libraries and the temporary storage choice at the start of 18.1.
- **Endpoints:**
  - `POST /api/v1/exports` is OWNER-only. Below a threshold (about 5,000 rows) it returns the file directly. Above it, it creates an `ExportJob` (PENDING, RUNNING, READY, FAILED, EXPIRED) that runs in the background. The file is stored temporarily (local disk in V1), downloaded through a signed 24 h link, and the owner gets an FCM "export ready" notification.
  - `GET /api/v1/exports` lists exports (Mes exports).
  - `GET /api/v1/exports/{id}/file` downloads one.
  - Expired files are purged.
- **Export audit:** every export writes a `DATA_EXPORTED` audit entry with the datasets, format, filters and row count.
- **App-side export:** the app builds the CSV from Drift in an isolate and shares it through `share_plus`.
- **Audit enrichment:**
  - New `audit_log` columns: `store_id` (nullable), `entity_label` (label at action time), `device_id`, `source` (`ONLINE` | `SYNC` | `SYSTEM`) and `metadata` jsonb (reason, justification, context).
  - New indexes: `occurred_at`, `store_id`, `user_id`, `action`.
  - New entries store `value_before`/`value_after` as field-by-field JSON. Old entries keep their existing format.
  - Every audit producer fills the new fields. Sync-originated entries carry `source=SYNC` and the device.
  - The audit trail follows the pattern of domain events feeding `AuditEventListener`.
- **Audit API:**
  - `GET /api/v1/audit` accepts filters (period, store, user, action, entity), uses cursor paging and resolves the actor name.
  - `GET /api/v1/audit/{id}` returns the detail view.
- **Items routed into 18.6:**
  - Audit snapshots silently become `'[]'` when JSON serialization fails in the sale services (`ValidateSaleService`, `CorrectSaleService`, `RecordSaleService`). Log and alert on that failure instead.
  - `audit_log` has no database-level immutability. Add a BEFORE UPDATE/DELETE trigger and a REVOKE through Flyway (V10). The owner decided this protection is delivered here.
  - Each item must be resolved or rejected, with the reason recorded next to it.

## UX & Interaction Patterns

- **"Exporter mes données" flow:**
  - Opens from Settings, or from an **Exporter** button on Rapports, Stock, Ventes or Historique des actions. The button pre-fills the flow with the current view.
  - Steps: pick datasets with checkboxes (plus a « Tout » shortcut), then filters (store; period: Aujourd'hui, 7 jours, Mois, Personnalisé), then format.
  - Format choices are CSV, Excel and PDF. Excel and PDF carry a Premium badge. Choosing PDF adds a template choice.
  - A small export downloads, then opens the share sheet. A large export shows « Votre export se prépare, nous vous prévenons dès qu'il est prêt », then a notification that opens the file, plus an entry in **Mes exports**.
  - Offline, the only option is « CSV — données de cet appareil », with the note « Contient uniquement les données synchronisées sur cet appareil ».
  - Errors show a clear message with a retry. Background exports survive closing the app.
- **"Historique des actions" screen (owner-only):**
  - Wording frames traceability as protection ("historique", "protection").
  - **List:** one line per action, with a type icon, a readable sentence (« **Awa** a modifié le prix de **Pagne wax 6 yards** »), store and time. Lines are grouped by day with infinite scroll. Filter chips: period, store, employee, action type, object search.
  - **Detail:** Qui (name, role, device), Quoi, Sur quoi (links to the product, sale or employee if it still exists), Quand et où (exact time, store, source « En ligne » or « Synchronisé depuis l'appareil le… »), Changements (Avant → Après table, XAF amounts, quantities in units), Contexte.
  - Has an Exporter button. Offline, it shows the last known state. Unknown action types get a generic rendering.
- **Rename:** the current app "Audit" screen (stock movement history) stays and is renamed **Mouvements de stock**.

## Cross-Story Dependencies

- **Order:** 18.6 → 18.7 first (immediate value, no export dependency), then 18.1 → 18.2 → 18.4 → 18.3 → 18.5.
- 18.7 uses the enriched audit API from 18.6.
- 18.2 builds on the ports and registry from 18.1.
- 18.3 builds on the renderer registry from 18.1 and must reuse the same `ClosureWindow` as the existing end-of-day report.
- 18.4 uses the 18.1 endpoints and FCM notification, the 18.2 datasets and the 18.3 templates.
- 18.4's Exporter button on the audit screen depends on 18.7.
- 18.5 uses the 18.2 column schema.
- The audit-log dataset in 18.2 depends on the enriched columns from 18.6.
- **Outside this epic:** relies on the existing plan gating (`PlanGuard`), FCM notifications, and per-tenant rate limiting. Employee activity reporting (FR67, refonte 17-3) can later build on the enriched audit log.
