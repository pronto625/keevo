# Sprint Change Proposal — Data Export Module & Detailed Audit Log

**Date:** 2026-10-11
**Requested by:** Toor (product owner)
**Workflow:** bmad-correct-course (incremental mode, 6/6 proposals approved)
**Status:** Approved by Toor and applied 2026-10-11 (PRD, architecture, UX, epics-remediation-audit.md, sprint-status.yaml)
**Scope classification:** Moderate (new V1 epic + backlog reorganisation; PRD/architecture/UX additions, no replan)

---

## 1. Issue Summary

Two new product requirements, plus a status-truth clean-up found by the 2026-10-11 alignment audit:

1. **Data export module.** The owner must be able to export *all* business data (products, stock, movements, sales, closures, inventories, contacts, team, audit log) in the format of their choice (CSV, Excel), with optimised generation and PDF templates. Today only FR55 exists ("export reports as PDF and Excel", paid plan), with two unstarted backlog stories (`17-6-exports-pdf-excel` in the refonte track, `v1s-14-7-export-pdf-excel` in the V1 track).
2. **Detailed audit log.** An audit line must be clickable and show the full detail: who did what, on what, when, where, before/after values and context.
3. **Sprint-status truth.** The alignment audit (`audit-coherence-2026-10-11.md`, 30 findings) shows statuses that do not match the code, missing UI-2/UI-3 keys, and an unrecorded product decision on employee transfers.

**Evidence (code, 2026-10-11, `deploy@78dc9e6`):**
- Backend has `commons-csv` only; no Excel or PDF library. App has `share_plus`.
- The app's "Audit" screen (`features/audit/presentation/page/audit_page.dart`) shows stock movements (`StockHistoryWidget`), not the audit log. The real `audit_log` (37 action types) is backend-only.
- `audit_log` stores `user_id, entity_type, entity_id, action, value_before, value_after, occurred_at`: no store, readable label, device or source.
- Commit `4302eba` let employees start transfers from any store, contradicting FR36 and v1s-12-8 AC4, recorded in no story.

## 2. Impact Analysis

**Epics**
- New V1 epic **v1s-epic-18 "Export de données & Audit détaillé"** (tag C: shipped in V1, re-absorbed as-is by the modular refonte).
- `17-6-exports-pdf-excel` and `v1s-14-7-export-pdf-excel`: superseded by v1s-epic-18.
- Refonte: 17-6 notes the absorption; future modules contribute an `ExportDatasetProvider` instead of touching the export module.
- FR67 (17-3 employee activity) can later build on the enriched audit log.
- Ordering: after the running V1 stabilisation epics; 18.6 → 18.7 first (no dependency on export).

**Artifacts**
- PRD: FR55 and FR84 rewritten; FR94–FR98 added; Billing Model aligned (CSV all plans, Excel/PDF Premium).
- Architecture: new module `reporting/export`; audit data model enriched.
- UX: new journeys "Exporter mes données" and "Historique des actions"; current "Audit" screen renamed "Mouvements de stock".
- Technical: new dependencies (Apache POI SXSSF, Thymeleaf + OpenHTMLtoPDF, to confirm in 18.1), temporary file storage with signed links, Flyway + tenant DDL migration for `audit_log`, FCM notification type "export ready".

## 3. Recommended Approach

**Direct Adjustment (Option 1)**: add one epic to the V1 track using the existing tag system (A/B/C). No rollback, no MVP reduction.

- **Why V1 with tag C:** the app is in production and the V1 track is the delivery path; the refonte is not started. Building `reporting/export` as a self-contained hexagonal module with per-domain dataset providers (same pattern as the sync `DeltaEntityProvider`) means the refonte lifts it without rewriting.
- **Effort:** Medium-High (7 stories; 18.1–18.3 backend-heavy). **Risk:** Low-Medium (memory on large exports mitigated by streaming; PDF fidelity validated per template).
- **Gating:** CSV free (data portability), Excel/PDF/background jobs Premium.
- **Generation:** both server-side (complete, multi-store, large volumes, templates) and on-device (CSV, offline, local data only).

## 4. Detailed Change Proposals (approved)

### 4.1 PRD (`prd.md`)

*Functional Requirements — Rapports & Dashboard*

OLD:
- **FR55:** Un propriétaire peut exporter des rapports en PDF et Excel *(Plan Payant)*

NEW:
- **FR55:** Un propriétaire peut exporter ses données et rapports dans le format de son choix : CSV *(tous plans)*, Excel et PDF *(Plan Payant)*
- **FR94:** Un propriétaire peut exporter l'intégralité des données de son entreprise (produits, stocks, mouvements, ventes, clôtures, inventaires, clients, fournisseurs, équipe, journal d'audit), filtrées par boutique et par période
- **FR95:** Le système peut générer des documents PDF à partir de modèles à l'identité de la boutique (rapport de fin de journée, rapport d'inventaire, état de stock, liste des ventes)
- **FR96:** Le système peut générer les exports volumineux en arrière-plan et notifier le propriétaire quand le fichier est prêt à télécharger ou partager
- **FR97:** Un utilisateur peut exporter en CSV les données présentes sur son appareil, même hors ligne

*Functional Requirements — Sécurité & Audit*

OLD:
- **FR84:** Le système peut maintenir un journal d'audit complet de toutes les modifications (stock, prix, ventes, transferts) avec identité, horodatage et valeurs avant/après

NEW:
- **FR84:** Le système peut maintenir un journal d'audit complet de toutes les modifications (stock, prix, ventes, transferts) avec identité, horodatage, boutique, objet concerné (libellé lisible), appareil, source (en ligne / synchronisation) et valeurs avant/après
- **FR98:** Un propriétaire peut consulter le journal d'audit, le filtrer (période, boutique, employé, type d'action, objet) et ouvrir chaque ligne pour voir le détail complet : qui a fait quoi, sur quoi, quand, où, et les valeurs avant/après champ par champ

*Billing Model*

OLD: Premium (Payant) : … rapports avancés, exports PDF/Excel, support prioritaire.
NEW: Premium (Payant) : … rapports avancés, exports Excel et PDF à modèles, exports volumineux en arrière-plan, support prioritaire. *(L'export CSV des données reste disponible sur tous les plans — portabilité des données.)*

### 4.2 Architecture (`architecture.md`)

Domain tree, `reporting/`: add `export/  # MODULE: Data export (CSV / Excel / PDF templates)`.

New section **Export Module (`reporting/export`)** (FR55, FR94–FR97):
- **Domain-contributed datasets:** each domain exposes its exportable dataset through the `ExportDatasetProvider` port (key, typed columns, paginated query filtered by tenant, store and period), mirroring the sync `DeltaEntityProvider`. The export module reads no table directly; a new module declares its provider without changing the export module.
- **Format engines** behind the `ExportRenderer` port: `CsvRenderer` (commons-csv, UTF-8 with BOM, `;` separator for direct opening in French-locale Excel), `XlsxRenderer` (Apache POI SXSSF streaming, one sheet per dataset), `PdfRenderer` (HTML/CSS templates rendered to PDF).
- **PDF templates:** versioned HTML templates in code (Thymeleaf), shared brand header (store name and logo, period, generation date, XAF), rendered with an HTML-to-PDF library (OpenHTMLtoPDF). Not user-editable in V1.
- **Sync vs background:** below a threshold (e.g. 5,000 rows) the file is returned directly; above it an `ExportJob` (PENDING, RUNNING, READY, FAILED, EXPIRED) runs in the background, the file is stored temporarily (object storage, or local disk in V1) and downloaded through a signed link valid 24 h; the owner is notified by FCM.
- **Gating:** CSV all plans; Excel, PDF and background jobs Premium (`PlanGuard`, error `PLAN_FEATURE_REQUIRED`).
- **Security:** owner-only, tenant-isolated; every export audited (`DATA_EXPORTED`: datasets, format, filters, row count); per-tenant rate limit (FR93).
- **Local export (app):** CSV generated on device from Drift (same column schema as the server), shared via `share_plus`; works offline, local data only.

Audit Trail, data model addition: `audit_log` gains `store_id` (nullable), `entity_label` (readable label at action time), `device_id`, `source` (`ONLINE` | `SYNC` | `SYSTEM`), `metadata` jsonb (reason, justification, context). `value_before` / `value_after` become structured field-by-field JSON for new entries (old entries stay readable). New endpoint `GET /api/v1/audit/{id}` and list filters. Immutability (NFR13) unchanged.

### 4.3 UX (`ux-design-specification.md`)

Reports journey (l.615): "… → Export PDF/Excel (plan payant)" becomes "… → bouton **Exporter** (pré-rempli avec la vue en cours)".

New journey **"Exporter mes données"** (owner), from Settings or from Reports, Stock, Sales, Audit:
1. **Quoi:** checkboxes per dataset (Produits, Stocks, Mouvements, Ventes, Clôtures, Inventaires, Clients, Fournisseurs, Équipe, Journal d'audit), shortcut "Tout".
2. **Filtres:** store (one or all), period (Aujourd'hui, 7 jours, Mois, Personnalisé).
3. **Format:** CSV, Excel, PDF; **Premium** badge on Excel and PDF, tap in Free plan opens the offer sheet. With PDF, choose a template (Fin de journée, Inventaire, État de stock, Ventes).
4. **Exporter:** small volume → immediate download then share sheet (WhatsApp, e-mail, files); large volume → "Votre export se prépare, nous vous prévenons dès qu'il est prêt", then notification and entry in **Mes exports** (available 24 h).
5. **Offline:** only "CSV — données de cet appareil", with the note "Contient uniquement les données synchronisées sur cet appareil".

New journey **"Historique des actions"** (owner; principle "la traçabilité comme bouclier", wording *historique* and *protection*):
- **List:** one line per action, type icon, readable sentence ("**Awa** a modifié le prix de **Pagne wax 6 yards**"), store and time; grouped by day, infinite scroll; chip filters for period, store, employee, action type, object search.
- **Clickable line → detail:** Qui (name, role, device), Quoi (action), Sur quoi (object, with a link to the product, sale or employee if it still exists), Quand et où (exact time, store, source "En ligne" or "Synchronisé depuis l'appareil le…"), Changements (field-by-field Avant → Après table, XAF amounts, quantities in units), Contexte (reason or justification).
- **Exporter** this view (pre-filled export journey).
- The current "Audit" screen (stock movements history) is kept and renamed **Mouvements de stock**.

### 4.4 Epic and stories (`epics/epics-remediation-audit.md`)

New epic **Epic 18: Export de données & Audit détaillé (Piste V1, tag C)**. Refs FR55, FR84, FR94–FR98. Refonte absorption: native, `reporting/export` and the `ExportDatasetProvider`s are taken as-is; supersedes 17-6 and v1s-14-7.

- **18.1 Socle du module export (backend) [C]:** ports `ExportDatasetProvider` / `ExportRenderer` and registry; `CsvRenderer`, `XlsxRenderer` (SXSSF); `POST /api/v1/exports` owner-only (sync below threshold, else `ExportJob` + signed 24 h link + FCM), `GET /api/v1/exports`, `GET /api/v1/exports/{id}/file`; gating, `DATA_EXPORTED` audit, rate limit, expired-file purge. AC: employee 403; no cross-tenant data; 50,000 rows streamed without exhausting memory; expired file → 410.
- **18.2 Jeux de données exportables [C]:** one provider per domain (products with variants and prices, stock levels, movements, sales and lines, closures, inventory sessions and counts, clients, suppliers, team without sensitive data, audit log). AC: store/period filters honoured; stable documented columns shared with 18.5; integer XAF; WAT dates.
- **18.3 Modèles PDF [C]:** `PdfRenderer` (Thymeleaf → OpenHTMLtoPDF), shared brand header; templates EOD, inventory gaps, stock state, sales list. AC: EOD PDF figures equal the EOD report of the same closure (same `ClosureWindow`); accents render; multi-page headers and page numbers; A4.
- **18.4 Écran « Exporter » (app) [C]:** UX journey, Premium badge and offer sheet, download + share, **Mes exports**, "export ready" notification opening the file, pre-filled Export buttons on Reports, Stock, Sales, Audit. AC: Free plan Excel/PDF → offer sheet; background export survives app close; clear retryable error.
- **18.5 Export local hors ligne (CSV) [C]:** CSV from Drift with the 18.2 column schema, share. AC: airplane mode works; file marked "données de cet appareil"; opens in French Excel; 10,000 rows without UI jank (isolate).
- **18.6 Audit enrichi (backend) [C]:** migration (tenant DDL + Flyway) adding `store_id, entity_label, device_id, source, metadata` + indexes (`occurred_at`, `store_id`, `user_id`, `action`); all audit producers fill them; field-by-field JSON before/after; sync entries carry `source=SYNC` and device; `GET /api/v1/audit` filtered with cursor paging and resolved actor name; `GET /api/v1/audit/{id}`. AC: old rows readable; immutability unchanged (NFR13); employee 403.
- **18.7 Journal d'audit consultable (app) [C]:** "Historique des actions" list (readable sentences, filters, infinite scroll), clickable detail (qui/quoi/sur quoi with link/quand et où/source/avant-après/contexte), Export button; current "Audit" screen renamed "Mouvements de stock". AC: owner-only; offline shows last known state; unknown action types fall back to a generic rendering.
- **Order:** 18.6 → 18.7; then 18.1 → 18.2 → 18.4 → 18.3 → 18.5.

### 4.5 Sprint status (`implementation-artifacts/sprint-status.yaml`)

*Epic 18 (V1 track, after v1s-epic-16):*
```yaml
  v1s-epic-18-export-audit: backlog
  v1s-18-1-export-module-foundation: backlog
  v1s-18-2-export-datasets: backlog
  v1s-18-3-pdf-templates: backlog
  v1s-18-4-export-screen-app: backlog
  v1s-18-5-local-offline-csv-export: backlog
  v1s-18-6-audit-enrichment-backend: backlog
  v1s-18-7-audit-log-screen-app: backlog
  v1s-epic-18-retrospective: optional
```
`17-6-exports-pdf-excel` and `v1s-14-7-export-pdf-excel` stay `backlog` with a "superseded by v1s-epic-18" comment.

*Status truth (alignment audit 2026-10-11, §8.1):*
- Add `UI-2-onboarding-soft-modern-rebrand: done` and `UI-3-rebrand-rollout-and-bug-hunt: done` after UI-1.
- 8-3 backlog → done (delivered via 3-5 / v1s-12-2); 11-6 ready-for-dev → done (via v1s-12-2).
- 11-1 done → in-progress; 11-2 and 11-4 review → in-progress (code only on branch `refractoring`).
- v1s-14-5 done → in-progress (Flutter FR91/FR92 screens missing); v1s-14-11 done → in-progress (Flutter employee edit, FR66, missing). Existing stories reopened, no new keys.
- v1s-12-4 backlog → done (obsolete: Next 16 uses `proxy.ts`).
- v1s-epic-13 and v1s-epic-15 in-progress → done.
- 7-4 stays `review` until a code review pass.
- Comments updated (no status change): 4-5, 8-4, 8-5, 9-2, 9-7, 9-8, 11-5, v1s-12-8, v1s-16-3.
- New backlog keys:
  - `v1s-12-10-sync-rbac-residual` (PROMOTE_PRODUCT, TOGGLE_CATEGORY, inventory session not scoped to the employee's store)
  - `v1s-12-11-transfer-employee-own-store` (owner decision 2026-10-11: an employee may only transfer from their own store; revert the opening of commit 4302eba on REST and sync; FR36 and v1s-12-8 AC4 stand)
  - `v1s-16-11-app-error-retry` (retry buttons on error screens)
  - `v1s-17-1-dashboard-build-ci` (fix or remove pages 9-7/9-8 + dashboard CI; critical before the next dashboard release)

**Noted, not planned now:** a complete roles & privileges module (fine-grained permissions beyond OWNER/EMPLOYEE) will be scoped later by the owner.

## 5. Implementation Handoff

- **Scope:** Moderate.
- **PO / SM (this workflow):** apply the approved edits to PRD, architecture, UX, `epics-remediation-audit.md` and `sprint-status.yaml`.
- **Developer agent:** create story files with `bmad-create-story` / implement with `bmad-build` in order 18.6 → 18.7 → 18.1 → 18.2 → 18.4 → 18.3 → 18.5; then `v1s-12-11` and `v1s-12-10` (security), `v1s-17-1` before the next dashboard release.
- **Architect (light):** confirm PDF and Excel libraries and the temporary file storage choice at the start of 18.1.
- **Success criteria:** every dataset exportable in CSV on all plans and in Excel/PDF on Premium; the PDF EOD equals the WhatsApp EOD; any audit line opens a detail answering who/what/on what/when/where/before-after; sprint-status matches the code on `deploy`.

## 6. Approval & Execution Log

- 2026-10-11 — Proposal approved by Toor (incremental mode, 6/6). Decision recorded: an employee may only transfer from their own store (v1s-12-11). Roles & privileges module noted for later scoping.
- Applied: `prd.md` (FR55, FR84 rewritten; FR94–FR98; Billing Model), `architecture.md` (`reporting/export`, Export Module, Audit Log Enrichment, FR mapping), `ux-design-specification.md` (Reports journey, "Exporter mes données", "Historique des actions"), `epics/epics-remediation-audit.md` (Epic 18, stories 18.1–18.7), `sprint-status.yaml` (epic v1s-18, superseded 17-6 / v1s-14-7, status truth from the alignment audit, follow-up keys).
- Project rule adopted: `deferred-work.md` stays empty; deferred items go to their owning story (`_bmad/custom/bmad-{code-review,build,build-auto,retrospective}.toml`). Existing backlog of deferred items being redistributed.
