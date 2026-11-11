# Epic 24 Context: Migration Bref par bascule unique

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->
<!-- 2026-11-11: rewritten after the user decision ADR-10 (one-shot cutover instead of waves), epics-fusion-keevo-bref.md Annexe D. -->

## Goal

Bring every Bref Point owner and all their data into Keevo **in one cutover** on go-live day, checked to the franc. On the day the new React Native app ships: stop Bref, dump its database, restore the dump in a separate database (read-only for the tool), **one command imports every owner** (the `DATABASE` source built in 24.2) with reconciliation and a report, then the RN app points at Keevo. If something goes wrong before the "go" decision, turn Bref back on: nothing in it was changed. Stories 24.2–24.10 build the tool; 24.12 is a full dress rehearsal on a copy of Bref prod that measures the downtime; 24.13 is the cutover runbook. The cutover itself is running 24.9 on go-live day. Epic 25 then retires api-bref for good.

## Stories

- Story 24.1: *(retired 2026-11-11)* api-bref — migration status, freeze and owner export
- Story 24.2: `migration` module — batches, mappings, identity, stores, members (done)
- Story 24.3: Import products (merged by key) and clients (per establishment)
- Story 24.4: Import documents, payments and expenses
- Story 24.5: Import monetisation (ADR-07)
- Story 24.6: Import annex data and client-portal index
- Story 24.7: Identity bridge and collision cases
- Story 24.8: Automatic, blocking reconciliation
- Story 24.9: Cutover command — import every owner of a restored Bref database, reconciliation, global activation, report; rollback = turn Bref back on
- Story 24.10: Redirect old Bref public links to Keevo
- Story 24.11: *(retired 2026-11-11)* OTA pre-migrator
- Story 24.12: Dress rehearsal on a copy of Bref prod, downtime measured
- Story 24.13: Cutover runbook and communication
- Stories 24.14–24.16: *(retired 2026-11-11)* waves

## Requirements & Constraints

- **Per-owner, idempotent**: inside the cutover command each owner is imported in its own batch; re-running does nothing or fills in what is missing. All Bref UUIDs are kept. Every imported row is traced (target table/id, source table/id, checksum, note). One owner failing does not stop the others.
- **Source**: the dump taken after Bref is stopped, restored in a separate database, read through a read-only user (`DATABASE` source). The `EXPORT` JSON source (contract `keevo/contracts/bref-export.json`) stays for tests and single-owner imports; nothing in api-bref produces it any more. No change is requested from api-bref.
- **Reconciliation blocks activation**. It checks:
  - row counts per table; merges are allowed only when the report explains them
  - Σ non-cancelled sales per establishment and civil month
  - Σ collected, Σ expenses, outstanding amounts
  - a 20-document field-by-field sample (random picks plus the 5 largest)
  - plan, end date and credit
  - 5 public tokens (302 → 200)

  Tolerance is ±1 currency unit per document for sums and 0 for counts, expenses, the sample and the subscription. Any gap sets `RECONCILIATION_FAILED`. Activation is global: an owner with an undocumented gap blocks the "go". Inconsistent Bref values are **kept as-is and flagged**, never corrected silently.
- **Monetisation** (ADR-07): the plan is the one `SUBSCRIPTION_IN_FORCE` gives on the cutover date (Apple grace included), never `user.plan`.
- **Rollback** until the "go": delete every imported tenant (schema, memberships, and only the accounts created by the import), purge their migration records/batches, turn Bref back on. Pre-existing linked Keevo accounts are never deleted. After the "go", turning Bref back on would lose writes made on Keevo.
- **External continuity**: old Bref public links redirect (301/302) to the Keevo public page through `legacy_public_token`. No dual-delivery webhook router: RevenueCat, MyCoolPay and Meta webhooks are repointed to Keevo during the downtime (user action in the runbook).
- **Local Bref mutations** not synced before the stop are an accepted residual risk; the announcement asks owners to open the app online before the stop.
- **Performance/NFR**: average owner < 60 s; one planned Bref downtime, measured by 24.12; metrics per cutover and per owner, logs correlated by `tenantId` / `migrationBatchId`; one archived report viewable in the dashboard.
- **No technical data in UI**: reports and dashboard pages show no UUIDs or raw codes, only French labels.
- Civil dates are imported as-is. KPIs use the invoice's civil `date`, not `createdAt`.
- The Bref schema changes over time (journal 18.3): the importer must work with and without the unreleased `develop` commits; review the journal before each story and right before the cutover.

## Technical Decisions

- **Keevo module `migration`** (platform category, SUPER_ADMIN, temporary), built in 24.2: `public.migration_batches`, `public.migration_records(target_table, target_id, source_table, source_id, checksum, note)`, `BrefSourcePort` (EXPORT and DATABASE), `BrefSnapshotValidator` (blocking cases before any write), pipeline of ordered `ImportStep`s.
- **Import order** (each step idempotent, `INSERT … ON CONFLICT (id) DO NOTHING`, set-based):
  1. identity/tenant (`seedDefaultStore=false`, `MIGRATION_BREF` event, tenant `SUSPENDED` until activation)
  2. stores (logos; the first store feeds the invoicing settings — 24.4)
  3. members
  4. categories (sector seed + « Produits importés ») — 24.3
  5. products — 24.3
  6. clients — 24.3
  7. quotes then invoices, with items and payments — 24.4
  8. expenses — 24.4
  9. monetisation — 24.5
  10. annex modules (WhatsApp, AI, notifications) — 24.6
  11. portal index — 24.6
  12. module provisioning + audit `TENANT_IMPORTED {source: bref, counts}` — 24.6
- **Identity**: owner created with the Bref UUID or linked (24.7); member bcrypt hashes copied; roles admin→MANAGER, seller→EMPLOYEE, viewer→VIEWER.
- **Tenant settings through existing ports, never direct SQL** (`TenantSaleFlowCommandPort`, `ModuleActivationRulesPort`, `StoreLogoUseCase`).
- **Products**: one tenant product per `productKey` across stores (`track_stock=false`). The other source ids point to it in `migration_records`. Products with `mergedIntoId` are imported archived and redirected.
- **Clients** (ADR-08): imported per establishment, no merging across establishments, sharing off by default.
- **Documents**: dedicated invoicing import port (no `issue`, no quota, no bridge events); `legacy_reference = BRF-<first 8 chars>`, `legacy_public_token`, civil `issue_date`, original timestamps; 90-day public links and `attachLegacy`.
- **Monetisation**: `external_subscriptions`, `tenant_payments`, `tenant_plan_events` with `MIGRATION_BREF`; already-processed RevenueCat events mapped so nothing is replayed.
- **Annex**: WABA token re-encrypted; `InvoicePhoneIndex.indexAll` per batch of imported invoices.
- **Mandatory rule**: right pattern and optimal complexity — no N+1, set-based SQL, keyset pagination, an index for every new access path.
- **Delivery split**: this repo is backend only. Super-admin dashboard pages (24.8, 24.9) belong to another repo: specs need a "Dashboard" and a "Mobile" section with `d<story>` / `m<story>` keys.

## UX & Interaction Patterns

- Dashboard: cutover progress per owner (importé → réconcilié) with durations, readable reconciliation diff, go / rollback actions, archived report.
- Messages (WhatsApp/SMS/email, fr and en, through platform campaigns 21.6): announcement with the stop time and "open the app online before the stop", reminder, "c'est fait", incident.

## Cross-Story Dependencies

- Import targets come from epics 19, 20 and 21 (identity, roles, plans, invoicing import port, payments, portal index).
- Epic 23: the RN app is published (23.12) on cutover day.
- Within the epic: 24.7 is used by 24.2; 24.8 gates the global activation of 24.9; 24.10 relies on `legacy_public_token` (24.4); 24.12 runs 24.2–24.10 end to end following 24.13 and archives its results in `research/fusion-mesures/`.
- Rollback uses `TenantDeletionService.deleteNow`.
