---
title: '15.5b — Offline invoices (sync operations + pull + app queue)'
type: 'feature'
created: '2026-10-31'
status: 'done'
baseline_revision: 'b7cee2a174d1d7ecfe0a5502dc2168ee2fca1453'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-15-5-ui-module-facturation.md'
  - '{project-root}/keevo/app/packages/README.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Invoices are the only business records that need the network: everything else in Keevo (sales, products, clients, stock) works offline and syncs later. A merchant without connection cannot create or collect an invoice, and invoices are never on the device.

**Approach:** Make invoices offline-first like the other entities. Backend: sync operations `CREATE_INVOICE`, `UPDATE_INVOICE`, `ISSUE_INVOICE`, `PAY_INVOICE`, `CANCEL_INVOICE` handled by the invoicing module (`moduleId() = "invoicing"`, same gating as other module operations) and an `invoices` `DeltaEntityProvider` pulled to the device (+ keyset index). App: invoices stored locally, the `keevo_invoicing` screens read the local copy and enqueue operations in the existing sync queue; numbering stays server-side.

## Boundaries & Constraints

**Always:**
- The client generates the invoice UUID; every operation is idempotent on the server (re-sent operation = same result, no duplicate invoice, no second number).
- Numbering stays on the server: an invoice issued offline is shown « Émise — numéro attribué à la synchronisation » until the pull brings its number; the PDF and WhatsApp send stay online-only (French message offline).
- Server rules are unchanged and enforced at sync: an operation rejected at sync (e.g. payment date invalid, invoice already paid, permission, module disabled → held as `MODULE_INACTIVE` like other module ops) uses the existing rejection/quarantine flow with a French message.
- Order: operations of one invoice apply in queue order (create → update → issue → pay); the sync handler resolves the invoice by its client id.
- The module package does not depend on the app: `keevo_core` provides the generic pieces — a local store for module records (generic table `module_records(module, entity, id, json, updated_at, pending)` or equivalent in `AppDatabase`, schema 31 → 32 with migration) and a port to enqueue sync operations and to merge pulled entities per entity type (registered through `KeevoModuleUi`). `RestSyncService` (app shell) dispatches pulled `invoices` rows to the registered merger.
- A pulled server row replaces the local copy unless the local copy has pending operations (then it is merged after they sync, as for other entities).
- Online actions keep working; when offline the screens write locally + enqueue instead of failing.
- Credit notes stay online-only (owner action needing the server's remaining quantities); credit notes created on the server appear on pulled invoices.
- Routed optional item: a global credit-note list endpoint / export dataset is not built (no screen or export needs it) — record the rejection.
- Bump `keevo/app/pubspec.yaml` 2.0.14+86 → 2.0.15+87.

**Never:**
- No client-side numbering, no offline PDF generation, no offline credit notes.
- No change to invoice numbering rules or to existing sync operations of other modules.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create offline | no network, new invoice | draft stored locally « En attente de synchronisation »; `CREATE_INVOICE` queued | — |
| Issue offline | local draft, no network | « Émise — numéro attribué à la synchronisation »; `ISSUE_INVOICE` queued | — |
| Sync | queue create+issue+pay | server creates, numbers (FAC-…), pays; pull brings number/status | rejection → French message, quarantine flow |
| Duplicate send | same `CREATE_INVOICE` twice | one invoice | idempotent |
| Pay date invalid at sync | `paidAt` before issue | operation rejected with the French reason | existing rejection UI |
| Module disabled | invoicing DISABLED | ops held `MODULE_INACTIVE`; pull skips invoices | resumes when re-enabled (15.1 full pull) |
| Online | network available | same flows, applied immediately | — |
| PDF offline | no network | « Disponible une fois connecté » | — |

</intent-contract>

## Code Map

- Backend sync SPI `keevo/backend/src/main/java/com/keevo/shared/application/port/sync/` (`SyncOperationHandler`, `AbstractSyncOperationHandler`, `SyncOperation`, `SyncOperationResult`); examples `crm/contact/adapter/in/sync/CreateClientSyncHandler.java`, `SupplierSyncHandler.java` (module ops with `moduleId()`).
- Delta providers `keevo/backend/src/main/java/com/keevo/sync/sync/domain/port/in/DeltaEntityProvider.java`, examples `sync/sync/application/provider/SupplierDeltaProvider.java`, `DayClosureDeltaProvider.java`; keyset indexes `shared/infrastructure/persistence/TenantSchemaProvisioner.SYNC_KEYSET_INDEXES` + `SyncKeysetIndexJob.java`; module-gated pull (13.10a).
- Invoicing use cases (create/update/issue/pay/cancel) under `keevo/backend/src/main/java/com/keevo/invoicing/invoice/application/usecase/` — handlers call them; client-provided id support on create (idempotent).
- Docs/contracts likely touched: `docs/entitlements/endpoints.md` (generated), sync op docs if any, `keevo/contracts/` files.
- App: `keevo/app/packages/keevo_core/lib/storage/app_database.dart` (schemaVersion 31, migrations), `sync_queue` table, `lib/sync/` (held operations, rejection messages — add French labels for the new op types), `module/keevo_module_ui.dart`, `module/module_ports.dart`; app shell `keevo/app/lib/core/sync/rest_sync_service.dart` (push/pull, entity merge), `lib/core/di/providers.dart`; package `keevo/app/packages/keevo_invoicing/lib/**` (data source → repository with local + remote).
- Tests: backend sync handler tests (per handler + full-context sync push/pull), app `test/core/sync/*` patterns, package tests.

## Tasks & Acceptance

**Execution:**
- backend -- 5 handlers (idempotent, module-gated), invoice create with client id, `invoices` delta provider + keyset index, tests (handler unit + full-context push/pull incl. duplicate and rejection).
- keevo_core -- module record store + migration 31→32, enqueue/merge ports, French labels for new op types, tests.
- app shell -- pull dispatch to registered mergers, push of module ops, port wiring in `buildRootScope`.
- keevo_invoicing -- local-first repository (read local, write local + enqueue; online fast path allowed), pending states in list/detail, offline messages for PDF/send/credit notes, merger registration in the descriptor, tests for every I/O row.
- pubspec 2.0.15+87.

**Acceptance Criteria:**
- Given no network, when the merchant creates, issues or pays an invoice, then it is stored locally, queued, and applied at the next sync with the server-side number.
- Given the same operation pushed twice, then the server keeps one invoice and one number.
- Given touched backend packages and Flutter folders, when tests run one at a time, then all pass.

## Review Triage Log

### 2026-10-31 — Review pass
- verdicts: 44 findings — high 0, medium 9, low 27, false 8, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind/edge) deleted drafts stay forever on other devices (hard delete, delta has no tombstone) — soft-delete drafts (`deleted_at`, module migration) and pull tombstones that the merger deletes locally; 404 on refresh deletes the local copy.
  - `[medium]` `[patch]` (blind) invoice screen waits for the network before showing the local copy — local first, refresh in background.
  - `[low]` `[patch]` (blind/edge) 502/503/504 and TLS/handshake errors not treated as unreachable — unreachable for the idempotent create.
  - `[low]` `[patch]` (blind) offline issue skips compliance warnings — local checks (client name, lines) + confirmation text « Conformité vérifiée à la synchronisation ».
  - `[medium]` `[patch]` (blind/edge) a rejected invoice op triggers a full pull and its dependent queued ops are pushed and rejected one by one — drop the record's later queued ops, refresh that invoice (GET) when online, full pull only as fallback.
  - `[low]` `[reject]` (blind) draft warnings stale after seller-settings change — refreshed at next draft edit/open (online); cosmetic.
  - `[low]` `[patch]` (blind) offline-created draft cannot be deleted before sync — delete locally and drop its queued ops.
  - `[low]` `[patch]` (blind) « Facturer » after a sale online-only — look up the local invoice by `saleId` first.
  - `[low]` `[reject]` (blind/edge) list decodes every invoice on each change / no index — tenant invoice volumes are small; revisit with volume.
  - `[low]` `[patch]` (blind/edge) local search not accent-insensitive and ignores the title — shared `search_fold` + title.
  - `[low]` `[patch]` (blind) local `updatedAt` ignores the injected clock; pulled rows restamped now — pass `updatedAt` through.
  - `[low]` `[reject]` (blind) migration test weak — the migration is a single `createTable`; full-suite DB tests open schema 32.
  - `[low]` `[patch]` (edge) refresh races a queued write — re-check queued inside the transaction.
  - `[medium]` `[patch]` (edge/claim) concurrent duplicate create (online retry + queued op) hits the PK — catch the duplicate key and return the existing invoice.
  - `[medium]` `[patch]` (edge) back-dated payment queued behind an unsynced issue is rejected at sync — while the issue is not on the server, the picker allows only today and no `paidAt` is sent.
  - `[low]` `[patch]` (edge) invalid local transitions / blank cancel reason queued — checked locally before queuing.
  - `[low]` `[patch]` (edge) quarantined op leaves a pending record bypassed online — pending records count as queued.
  - `[low]` `[patch]` (edge) rejected offline create silently deletes the invoice — rejection history entry naming the client/amount in French.
  - `[low]` `[patch]` (edge) `local == null` with queued ops → « introuvable » — show « en attente de synchronisation ».
  - `[low]` `[patch]` (edge) older pulled snapshot overwrites a newer local copy — skip when older.
  - `[low]` `[patch]` (edge) unknown `vatTreatment` over REST → 500 — mapped to a French 400.
  - `[medium]` `[patch]` (vg) send/supersede `updated_at` bumps untested — delta tests.
  - `[medium]` `[patch]` (vg) keyset continuation never run on Postgres — two-page delta test.
  - `[medium]` `[patch]` (vg) `UPDATE_INVOICE` append-only rule untested — queue test.
  - `[medium]` `[patch]` (vg) `AppModuleSyncQueue` untested (transaction atomicity, unsynced ids) — adapter test + root scope assertion.
  - `[low]` `[patch]` (intent) device payloads and server parsing never tested together — shared JSON fixtures in `keevo/contracts/` asserted by the app builder test and parsed by a backend handler test; device run done separately.
  - `[low]` `[reject]` (vg other) non-pending CREATE rejection goes to the refresh branch — intended.
  - `[false]` `[reject]` (intent) hybrid write model instead of always-local — "Given no network" is met; online immediate apply keeps server validation interactive.
  - `[false]` `[reject]` (intent) issued shown locally before sync — the intent's « numéro attribué à la synchronisation » state.
  - `[false]` `[reject]` (intent) label wording longer — same text with the status prefix.
  - `[false]` `[reject]` (intent) generic storage in keevo_core — module packages cannot own Drift tables of the shared DB (15.4c boundary).
  - `[false]` `[reject]` (intent) `DeltaEntityProvider` moved to shared — needed for the module boundary (invoicing depends only on shared).
  - `[false]` `[reject]` (intent) routed credit-note item recorded outside the patch — epics doc is the record.
  - `[false]` `[reject]` (intent) no device run — done live on the Redmi after the patches.
  - `[false]` `[reject]` (intent) tests split per layer — fixture patch joins them.

## Verification

**Commands:**
- `runpkg.sh invoicing`, `sync`, `shared`, `architecture`, `acceptance` -- expected: 0 failures.
- `cd keevo/app && dart run melos generate && flutter analyze lib test` + package analyzes -- expected: 0 errors/warnings.
- `cd keevo/app && bash tool/test_chunked.sh` + `packages/*` tests -- expected: all pass.
- `cd keevo/app && flutter build apk --debug` -- expected: success.

**Manual checks:**
- Live on dev backend: push a `CREATE_INVOICE` + `ISSUE_INVOICE` + `PAY_INVOICE` batch through `/api/v1/sync` (owner kv_zggt7g), then pull `invoices`; re-push the same batch → no duplicate.

## Auto Run Result

Status: done

**Summary:** Invoices are offline-first. Backend: `InvoiceSyncHandler` (CREATE/UPDATE/ISSUE/PAY/CANCEL_INVOICE, `moduleId() = "invoicing"`, REST DTO validation and permission mapping, idempotent create on a client id via `INSERT … ON CONFLICT`), `InvoiceDeltaProvider` (REST-shaped rows, tombstones for soft-deleted drafts, upgrades the tenant's invoicing schema before reading), migrations V6 (sync keyset index, `SYNC_KEYSET_INDEXES` entry with `moduleId`) and V7 (draft soft delete), `DeltaEntityProvider` moved to `shared.application.port.sync`; sends/supersede/credit notes bump `updated_at`. keevo_core: `module_records` store (schema 32), merger/queue ports, French labels. App shell: module rows merged on pull, targeted refresh on rejection, rejection history for refused offline creates, one full pull for upgraded devices. keevo_invoicing: local-first repository (online fast path, offline write + queue in one transaction), pending/« numéro attribué à la synchronisation » states, offline guards, phone normalisation (+237), shared JSON payload contract `keevo/contracts/invoice-sync-payloads.json`. Credit-note list endpoint/export rejected (no screen or export needs them). App 2.0.15+87.

**Review:** 44 findings — 9 medium patched (tombstones, local-first screen, rejection handling, concurrent create, back-dated pay behind queued issue, 4 verification gaps), ~20 lows patched, rest rejected with reasons.

**Live device test (Redmi 23106RN0DA, dev backend, WhatsApp noop) found and fixed a real bug:** the pull never upgraded an already-provisioned tenant's invoicing schema (V5 → V7), so `deleted_at` was missing and no invoice reached the device. After the fix: online CRM-picked invoice FAC-2026-000010 created → issued → PDF → paid Mobile Money; airplane mode: draft → issue (« numéro attribué à la synchronisation ») → pay (date locked to today) → back online → FAC-2026-000011 PAID with VAT recomputed; the 9 older invoices pulled; « Facturer » after a POS sale opened FAC-2026-000012; invoicing DISABLED → « Factures » entry hidden (row removed afterwards, tenant_modules back to 331). Also fixed from the device test: free-text phone normalised to +237…, offline client search says « Hors ligne ».

**Follow-up review recommended:** true — many medium patches on sync paths; unverified risk: multi-device scenarios (tombstones, concurrent edits) only covered by tests.

**Verification:** backend 16 packages one at a time — invoicing 170 … all 0 failures (invoicing 170, sync 491, shared 1053, architecture 32, acceptance 4, commerce 260, reporting 404, messaging 168, crm 158, catalog 247, inventory 376, identity 446, admin 517, store 60, subscription 70, feedback 52); Flutter analyze 0 errors/warnings (app, keevo_core, keevo_invoicing); keevo_core / keevo_invoicing (97) / app chunked tests all pass; `flutter build apk --debug` OK.
