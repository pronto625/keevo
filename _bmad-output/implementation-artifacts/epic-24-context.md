# Epic 24 Context: Migration Bref par vagues

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Bring every Bref Point owner and all their data into Keevo, one owner at a time, checked to the franc, with rollback possible until activation. Stories 24.1–24.10 build the migration tool (P1c, validated by dry-run, a go-live prerequisite together with 24.12); stories 24.11–24.16 run it in production waves (P4, only after go-live). api-bref keeps serving non-migrated owners (strangler per owner). Once all owners are migrated, api-bref can be shut down (epic 25).

## Stories

- Story 24.1: api-bref — migration status, freeze and owner export
- Story 24.2: `migration` module — batches, mappings, identity, stores, members
- Story 24.3: Import products (merged by key) and clients (per establishment)
- Story 24.4: Import documents, payments and expenses
- Story 24.5: Import monetisation (ADR-07)
- Story 24.6: Import annex data and client-portal index
- Story 24.7: Identity bridge and collision cases
- Story 24.8: Automatic, blocking reconciliation
- Story 24.9: Wave management in the dashboard and rollback
- Story 24.10: External continuity — public links and webhooks
- Story 24.11: OTA pre-migrator in the current Bref app
- Story 24.12: Full dry-run on a production copy
- Story 24.13: Runbook and communication
- Story 24.14: Pilot waves
- Story 24.15: Mass waves (2 to 6)
- Story 24.16: Stragglers and global freeze

## Requirements & Constraints

- **Per-owner, idempotent**: re-running an owner's import does nothing or fills in what is missing. All Bref UUIDs are kept. Every imported row is traced (target table/id, source table/id, checksum, note).
- **Freeze**: while an owner is migrating, api-bref rejects every write by the owner or their members (HTTP and replayed mutations) with 423 `ACCOUNT_MIGRATING`. Reads stay allowed and unfreeze restores the previous state. Freezing is refused while a device still has pending mutations. Local rows that have no server equivalent are shown to the owner. A `migrated` owner can no longer log in to api-bref (`ACCOUNT_MIGRATED` plus the store link).
- **Reconciliation blocks activation**. It checks:
  - row counts per table; merges are allowed only when the report explains them
  - Σ non-cancelled sales per establishment and civil month
  - Σ collected, Σ expenses, outstanding amounts
  - a 20-document field-by-field sample (random picks plus the 5 largest)
  - plan, end date and credit
  - 5 public tokens (302 → 200)

  Tolerance is ±1 currency unit per document for sums and 0 for counts, expenses, the sample and the subscription. Any gap sets `RECONCILIATION_FAILED`. Inconsistent Bref values (a total that differs from the recomputed total by more than 1, or a non-integer amount) are **kept as-is and flagged** in the note and the report. They are never corrected silently.
- **Monetisation** (ADR-07): the plan is the one that `SUBSCRIPTION_IN_FORCE` gives on the migration date (Apple grace included), never `user.plan`. An owner without a subscription in force arrives in FREE with no extra grace. Credit, SMS packs and the month's counters carry over to the franc.
- **Rollback** is possible until activation: delete the imported tenant (schema, memberships, and only the accounts created for the import), purge its migration records, and unfreeze api-bref. Pre-existing linked Keevo accounts are never deleted.
- **External continuity**:
  - Bref public links answer with a 302 to the Keevo public page through `legacy_public_token`.
  - The RevenueCat/MyCoolPay/Meta webhooks go through a dual-delivery router with a redelivery queue. Each backend ignores owners it does not know.
  - WABA accounts keep working.
- **Performance/NFR**:
  - an average owner imports in < 60 s
  - freeze → activation takes < 15 min, with no planned global downtime
  - metrics per wave (duration, failures, gaps), logs correlated by `tenantId` / `migrationBatchId`
  - every wave produces an archived report that can be viewed in the dashboard
- **No technical data in UI**: wave reports and dashboard pages show no UUIDs or raw codes, only French labels.
- Civil dates are imported as-is. KPIs use the invoice's civil `date`, not `createdAt`.
- The Bref schema changes over time (journal 18.3): the importer must work with and without the unreleased `develop` commits. Review the Bref journal before each story and each wave.
- Waves (24.15): only after 21.2 sandbox validation for Apple. Mass-wave reconciliation failure rate < 1%. No wave starts while the previous one has an open high-severity incident. 24.15 (first activated wave) is the point of no return.

## Technical Decisions

- **New Keevo module `migration`** (platform category, SUPER_ADMIN, temporary). It reuses `TenantFactory`, `TenantSchemaProvisioner`, `ModuleProvisioner`, `PlanChangeRecorder`, `ModuleLifecycleService`, the module ports and audit. New tables: `public.migration_batches` and `public.migration_records(target_table, target_id, source_table, source_id, checksum, note)`, indexed on `(source_table, source_id)`.
- **Input**: a per-owner JSON export from api-bref (`GET /v1/admin/migration/owners/:id/export`, superadmin, streaming, checksum, frozen owners only). Keevo gets no direct access to the Bref database. The status is exposed on `GET /v1/auth/me` and `/v1/auth/me/migration` as `{status, scheduledAt, redirectBundle, minVersion}`. `user.migration_status` is one of none/scheduled/frozen/migrated.
- **Import order** (each step idempotent, one transaction per step, JDBC batches of 1,000, `INSERT … ON CONFLICT (id) DO NOTHING`):
  1. identity/tenant (`seedDefaultStore=false`, `MIGRATION_BREF` event)
  2. stores (logos; the first store feeds the invoicing settings)
  3. members
  4. categories (sector seed + « Produits importés »)
  5. products
  6. clients
  7. quotes then invoices, with items and payments
  8. expenses
  9. monetisation
  10. annex modules (WhatsApp, AI, notifications)
  11. portal index
  12. module provisioning (`ModuleProvisioner.provisionActiveModules`) + audit `TENANT_IMPORTED {source: bref, counts}`
- **Identity**:
  - Owner created with the Bref UUID, or linked by normalized phone (E.164 via `PhoneNumbers.normalize`, default = the establishment's country) and then by verified email. When linked, the Keevo hash is kept and an OWNER membership is added, so the owner sees two businesses at login.
  - A taken username, or one equal to an existing phone, blocks the owner before any write.
  - Member bcrypt hashes are copied. Roles map admin→MANAGER, seller→EMPLOYEE, viewer→VIEWER.
  - Bref `social_id`/`provider` go to `public.user_authenticators`.
- **Tenant settings through existing ports, never direct SQL**: `TenantSaleFlowCommandPort.set(schema, DOCUMENT)` then `ModuleActivationRulesPort.applyInitialRules(schema)`, and store logos via `StoreLogoUseCase`. Still open (24.2): which modules match the owner's Bref plan, and how suppliers (part of `crm`) are hidden from a Bref owner.
- **Products**: one tenant product per `productKey` across stores (`track_stock=false`). The other source ids point to it in `migration_records`. Products with `mergedIntoId` are imported archived and redirected.
- **Clients** (ADR-08): imported per establishment, no merging across establishments, sharing off by default.
- **Documents**:
  - Inserted through a dedicated invoicing import port. Never through `InvoiceLifecycleUseCase.issue`, so no quota consumption and no bridge events.
  - Fields: `legacy_reference = BRF-<first 8 chars of UUID>`, `legacy_public_token`, `issue_date` = the civil date, and the original `created_at`/`updated_at`.
  - Items resolved by `product_key`. Payments go to `invoice_payments`, `payment_state` is recomputed, then the client/product counters.
  - Imported tenants get 90-day public links (`DocumentPreferences.IMPORTED_LINK_DAYS`) and `InvoicePublicLinkUseCase.attachLegacy`. Paper sizes map 53 mm→58 and 110 mm→A4.
- **Monetisation**:
  - Feed `external_subscriptions` and `tenant_payments`, and write `tenant_plan_events` with `MIGRATION_BREF`.
  - Map the already-processed RevenueCat events into `revenuecat_webhook_event` (`subjectKey`→`original_transaction_id`, `outcome='APPLIED'`) so nothing is replayed.
  - Still open: how plans paid with account credit show up in revenue metrics.
- **Annex**: the WABA token is re-encrypted with the Keevo key. Call `InvoicePhoneIndex.indexAll(schema, entries)` for each batch of imported invoices that have a phone, because the 21.7 backfill runs only once.
- **Delta sync**: imported rows keep their original timestamps. This is safe because migrated devices always full-pull.
- **Mandatory rule**: right pattern and optimal complexity — no N+1, set-based SQL for import and reconciliation, keyset pagination, an index for every new access path. The review blocks on any violation.
- **Delivery split**: this repo is backend only. 24.1 (api-bref) and 24.11 (current Bref app OTA) are changes in the Bref repos. Super-admin dashboard pages (24.8, 24.9) belong to another repo, so their specs need a "Dashboard super-admin" section and a "Mobile" section with an `m<story>` key.

## UX & Interaction Patterns

- Old Bref app (24.11): a migration banner (with the date when scheduled), a forced `syncAll()` + `resumePausedMutations()`, a report of the pending-mutation count, and a list of unsynced local rows. A `migrated` owner sees « Votre compte a été migré, mettez à jour l'application » with the store link.
- Dashboard:
  - wave timeline per owner (planifié → gelé → exporté → importé → réconcilié → activé) with the duration of each step
  - the reconciliation report page shows a readable diff
  - freeze refusals show their reason
- Messages (WhatsApp/SMS/email, in fr and en): J-3 announcement, reminder, activation confirmation with the store link, incident.

## Cross-Story Dependencies

- Import targets come from epics 19, 20 and 21:
  - identity, roles, plans and versions (19.6 tenant picker, 19.12 sale flow, 19.13 activation rules, 19.14 roles)
  - invoicing import port and legacy links (20.x)
  - payments/RevenueCat (21.2, 21.3)
  - portal index (21.7)
- Epic 23 (unified app published) is required before the first wave. 24.11 and 24.13–24.16 come only after go-live.
- Within the epic:
  - 24.1 feeds 24.2–24.6.
  - 24.7 is used by 24.2.
  - 24.8 gates the activation step of 24.9.
  - 24.9 drives api-bref freeze/migrated (24.1) and refuses to freeze when the 24.11 counts show pending mutations.
  - 24.10 relies on the `legacy_public_token` written by 24.4 and on RevenueCat events not being replayed (24.5).
  - 24.12 runs 24.1–24.10 end to end and archives its results in `research/fusion-mesures/`.
  - 24.13 is reviewed against 24.12. Then come 24.14 (decision to continue), 24.15 (collision wave = 24.7 cases) and 24.16.
- Rollback uses `TenantDeletionService.deleteNow`. Epic 25 needs epic 24 finished (no `none`/`scheduled` owners left on api-bref).
