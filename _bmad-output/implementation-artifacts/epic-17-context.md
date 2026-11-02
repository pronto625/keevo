# Epic 17 Context: Complétion Fonctionnelle & Gating Produit (Phase 7)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Close the remaining product functional requirements and deliver the decided Premium features, each gated by the entitlement engine, so Keevo is functionally complete before the refonte goes live. Most of the epic already shipped through the V1 track and is present on `refonte/modulaire` (17.1, 17.3, 17.4, 17.5 done; 17.6 superseded by the V1 exports epic). The V1 residues and the stock-tracking request are now done on the API side too (17.10 product counter, 17.11 optional initial stock, 17.12 per-product stock tracking, 17.13 creating a product with an archived product's name) and report-config gating (17.8). What remains: price-change notification (17.7), leaderboard visibility for employees (17.2), and the end-to-end regression that is the **go-live gate** (17.9). Since 2026-11-01 this repo delivers **the backend API only** (`keevo/backend`); mobile and dashboard parts become handoff sections for other teams.

## Stories

- Story 17.1: Annulation / correction de vente — done
- Story 17.2: Leaderboard vendeurs (résidu : « Afficher aux employés »)
- Story 17.3: Suivi d'activité des employés — done
- Story 17.4: Feedback / signalement in-app — done
- Story 17.5: Santé système, alertes proactives, push global — done
- Story 17.6: Exports PDF / Excel — done (remplacée)
- Story 17.7: Notification owner sur changement de prix par un employé (Premium)
- Story 17.8: Gating « configuration des rapports » (Free fixe / Premium configurable) — done (API ; mobile en handover)
- Story 17.9: Régression E2E multi-surface (gate go-live)
- Story 17.10: Compteur « Produits » juste sur l'écran Abonnement — done (API ; mobile en handover)
- Story 17.11: Stock initial dès la création d'un produit — done (API ; mobile en handover)
- Story 17.12: Suivi de stock activable par produit — done (API ; mobile en handover)
- Story 17.13: Créer un produit au nom d'un produit archivé — done (API ; mobile en handover)

## Requirements & Constraints

- **Delivery scope**: backend API only. `keevo/app` (Flutter) and `keevo/dashboard` are basic test clients, not deliverables. Story text written with Flutter work in mind (UI conditioning, Drift columns, `pubspec.yaml` bumps) becomes **mobile handover** to the React Native app (repo AI/bref). Deliver the API, then describe client behaviour in the spec.
- **Every spec ends with** « ## Mobile (React Native — dépôt AI/bref) » and/or « ## Dashboard super-admin (autre dépôt) »: what the API does, endpoints/fields/error codes, client requirements, test scenarios. Each gets a separate key in `sprint-status.yaml` (`m<key>` in the MOBILE block, `d<key>` in the DASHBOARD block). API conventions: spec-16-11 Mobile section; sync protocol: spec-16-10 Mobile section; error messages: spec-16-14 (French catalogue, opt-in `DomainException.userFacing`).
- **V1 2.0.1 compatibility is mandatory**: installed V1 apps keep running against the new backend. Changes must be additive — no existing response shape, error code or sync reason may change in a breaking way (17.9 verifies this). New fields need safe defaults for clients that never send them.
- French labels only; no UUIDs or raw codes in screens, exports or audit detail.
- Free/Premium gating goes through the entitlement engine (`@RequiresEntitlement`, module states); Free gets a fixed, sensible behaviour, never an error loop.
- **17.7**: Premium owner gets WhatsApp/push when an employee overrides a sale price; Free gets nothing.
- **17.2**: owner setting « Afficher le leaderboard aux employés » — OFF by default, confirmation on activation, anonymised ranking for employees once ON; employee access stays 403 while OFF; Premium-gated.
- **17.9**: on staging, critical paths green — signup→onboarding, offline sale→sync, transfer, inventory, WhatsApp report, module activation/deactivation, auto-invoicing, Free/Premium gating — plus V1 2.0.1 compatibility. Must pass before the canary (16.7).
- **Delivered product/stock rules later stories and 17.9 must preserve**:
  - `currentProducts` (subscription) = products ACTIVE and not archived; any transition that makes a product count as active (restore, draft promotion) is checked against the plan's product limit.
  - Initial stock at creation is optional: Q > 0 → `CREATE_PRODUCT` then `RECORD_STOCK_ENTRY`; replaying the same movement id → `DUPLICATE`.
  - `products.track_stock` (default `true`): untracked products (or any product when module `inventory` is DISABLED) get no availability check, no stock movement, no threshold alert, and are excluded from transfers (`STOCK_NOT_TRACKED`), inventories, stock exports and stock value. Toggle via `PUT /api/v1/products/{id}/stock-tracking` / sync `SET_PRODUCT_STOCK_TRACKING`, audited « Suivi du stock activé / désactivé ».
  - Creating a product with an archived product's name restores that product (limit checked, audited) on sync (`MERGED_INTO:<id>` + `conflictData.restored`), REST (200 + `restored: true`) and CSV import (row warning); at the limit → `PLAN_LIMIT_EXCEEDED`, product stays archived. Never an invisible product.
- **Delivered report-config rules (17.8) to preserve**:
  - With `report-config` DISABLED (Free, or trial/Premium ended > 7 days, no à-la-carte row), every report/alert job reads fixed defaults via the single tenant-preferences query port: EOD report on, 20:00 WhatsApp (auto-closure at 20:00 WAT); weekly Sunday 20:00 WhatsApp; inventory report WhatsApp; stock alerts on, push; trends on. Weekly/trends/alerts stay subject to their own modules.
  - The stored `report_preferences` row is never written or erased — it comes back on return to Premium. READ_ONLY (grace) keeps stored values, not editable; unreadable module state → stored values, not editable, WARN.
  - `GET /api/v1/tenant/preferences` returns effective values plus additive `reportConfigEditable` (one row read, one state read so they never disagree). PUT stays gated: 403 `MODULE_NOT_ENTITLED` / `MODULE_READ_ONLY`.
  - Known asymmetry kept for compatibility: GET returns times as `HH:mm`, PUT requires `HH:mm:ss`.
  - The no-row transition rule already reads `profile_modules` (16.8); 13.10a's rule is retired.

## Technical Decisions

- Modules communicate only through public APIs/events; no direct cross-context imports (ArchUnit/Modulith verify is blocking). Owner notifications go through `messaging` with owner resolution via the existing owner-recipient port (blank phone → in-app only).
- `SalePriceOverriddenEvent` already exists and is only audited; 17.7 adds a gated listener rather than a new event.
- Leaderboard core (`GET /api/v1/reporting/leaderboard`, OWNER-only, gated `advanced-reports`) already exists; 17.2 adds the setting and the anonymised employee view.
- The report-config endpoint is already gated `report-config`; plan profiles (`plan_profiles` + `profile_modules`) are the source of truth for plan composition.
- Sync: new operations or fields must be additive; employee scope is passed through the pull command.
- `track_stock` is the single, durable product-level switch, reused by the Keevo × Bref fusion (story 20.1).
- Chunked tests only (one package/folder at a time); local backend with WhatsApp noop and FCM off.

## Cross-Story Dependencies

- Remaining execution order: 17.7 → 17.2 → 16.6 → 10-8 (ops) → **17.9** (go-live gate) → 16.7 (canary).
- 17.2 and 17.7 depend on the epic 13 entitlement engine and the 16.1 module lifecycle.
- 17.9 extends the existing Python E2E scripts and dashboard tests (15.7), requires staging (10-8 runbooks executed), and must cover the delivered 17.8 and 17.10–17.13 behaviours and V1 compatibility.
- Mobile handover keys `m17-8` and `m17-10` … `m17-13` remain backlog for the RN team (fusion RN app story 22.10 depends on 17.11/17.12).
