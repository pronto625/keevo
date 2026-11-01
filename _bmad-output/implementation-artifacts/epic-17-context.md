# Epic 17 Context: Complétion Fonctionnelle & Gating Produit (Phase 7)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Close the remaining product functional requirements and deliver the decided Premium features, each gated by the entitlement engine, so Keevo is functionally complete before the refonte goes live. Most of the epic already shipped through the V1 track and is present on `refonte/modulaire` (17.1, 17.3, 17.4, 17.5 done; 17.6 superseded by the V1 exports epic). What remains is residual or new: product counter on the subscription screen, optional initial stock at product creation, per-product stock tracking, report-config gating, price-change notification, leaderboard visibility for employees, and the end-to-end regression that is the **go-live gate**. Since 2026-11-01 this repo delivers **the backend API only** (`keevo/backend`); mobile and dashboard parts become handoff sections for other teams.

## Stories

- Story 17.1: Annulation / correction de vente — done
- Story 17.2: Leaderboard vendeurs (résidu : « Afficher aux employés »)
- Story 17.3: Suivi d'activité des employés — done
- Story 17.4: Feedback / signalement in-app — done
- Story 17.5: Santé système, alertes proactives, push global — done
- Story 17.6: Exports PDF / Excel — done (remplacée)
- Story 17.7: Notification owner sur changement de prix par un employé (Premium)
- Story 17.8: Gating « configuration des rapports » (Free fixe / Premium configurable)
- Story 17.9: Régression E2E multi-surface (gate go-live)
- Story 17.10: Compteur « Produits » juste sur l'écran Abonnement
- Story 17.11: Stock initial dès la création d'un produit
- Story 17.12: Suivi de stock activable par produit

## Requirements & Constraints

- **Delivery scope**: backend API only. `keevo/app` (Flutter) and `keevo/dashboard` are basic test clients, not deliverables — no feature work ships there. 17.10, 17.11, 17.12, 17.8, 17.7 and 17.2 were written with Flutter app work in mind (form fields, Drift columns, local migrations, `pubspec.yaml` bumps, UI conditioning): those app parts are now **mobile handover only** to the React Native app (repo AI/bref). Deliver the API, then describe client behaviour in the spec.
- **Every spec ends with** « ## Mobile (React Native — dépôt AI/bref) » and/or « ## Dashboard super-admin (autre dépôt) »: what the API does, endpoints/fields/error codes to use, client requirements, test scenarios. Each gets a separate tracking key in `sprint-status.yaml` (`m<key>` in the MOBILE block, `d<key>` in the DASHBOARD block). API conventions: spec-16-11 Mobile section; sync protocol: spec-16-10 Mobile section; error messages: spec-16-14 (French catalogue, opt-in `DomainException.userFacing`).
- **V1 2.0.1 compatibility is mandatory**: installed V1 apps keep running against the new backend. Changes must be additive — no existing response shape, error code or sync reason may change in a way that breaks V1 (17.9 verifies this). New fields need safe defaults for V1 clients that never send them.
- French labels only; no UUIDs or raw codes in screens, exports or audit detail (e.g. audit label « Suivi du stock activé / désactivé »).
- Free/Premium gating goes through the entitlement engine (`@RequiresEntitlement`, module states); Free must get a fixed, sensible behaviour, never an error loop.
- **17.10**: « Produits » usage must equal the number of active products (online: server `currentProducts` from `GET /api/v1/subscription/me`; offline: consistent local count).
- **17.11**: initial quantity is **optional**, even for tracked products; empty → stock 0, no movement, creation never blocked. If Q > 0 → one STOCK_ENTRY movement for the active store, same transaction as creation (online and via sync). Hidden for untracked products.
- **17.12**: `products.track_stock BOOLEAN NOT NULL DEFAULT true` (tenant Flyway migration; existing products unchanged). Setting belongs to the product (all variants, all stores), never derived from onboarding sector or `StoreType`. Exposed in product API, CSV import (optional `suivi_stock`, default `oui`) and sync delta. Untracked: no availability check, no SALE movement (online, offline, pending-sale validation, correction/cancellation), no `stock_levels` row, no threshold alert, excluded from transfers, inventory sessions, stock exports and stock value. Enabling: optional starting quantity for the active store (STOCK_ENTRY), others start at 0. Disabling: explicit confirmation, levels kept frozen, history readable. Without module `inventory` all products behave as untracked. Owner-only (manager per existing permissions), audited.
- **17.8**: with `report-config` DISABLED, the end-of-day report uses the fixed default hour/channel and ignores `report_preferences` stored during a trial; replace the 13.10a transition rule (plan → paid modules without row) with `profile_modules`.
- **17.7**: Premium owner gets WhatsApp/push when an employee overrides a sale price; Free gets nothing.
- **17.2**: owner setting « Afficher le leaderboard aux employés » — OFF by default, confirmation on activation, anonymised ranking for employees; endpoint stays 403 for employees while OFF; Premium-gated.
- **17.9**: on staging, critical paths green — signup→onboarding, offline sale→sync, transfer, inventory, WhatsApp report, module activation/deactivation, auto-invoicing, Free/Premium gating — plus V1 2.0.1 compatibility. Must pass before the canary (16.7).

## Technical Decisions

- Modules communicate only through public APIs/events; no direct cross-context imports (ArchUnit/Modulith blocking). Notifications go through `messaging` with owner resolution via the existing owner-recipient port.
- `SalePriceOverriddenEvent` already exists and is only audited; 17.7 adds a gated listener rather than a new event.
- Leaderboard core (`GET /api/v1/reporting/leaderboard`, OWNER-only, gated `advanced-reports`) already exists; 17.2 adds the setting and the anonymised employee view.
- Report-config endpoint already gated `report-config`; plan profiles (`plan_profiles` + `profile_modules`) are the source of truth for plan composition.
- Sync: new operations or fields must be additive to existing sync operations; employee scope is passed through the pull command.
- `track_stock` is shared with the Keevo × Bref fusion (FUS-FR13, story 20.1 reuses it; Bref imports will default to `false`) — design it as the single, durable product-level switch.
- Chunked tests only (one package/folder at a time); local backend with WhatsApp noop and FCM off.

## Cross-Story Dependencies

- Execution order: 16.14 → 17.10 → 17.11 → 17.12 → 17.8 → 17.7 → 17.2 → 16.6 → 10-8 (ops) → **17.9** (go-live gate) → 16.7 (canary).
- 17.12 follows 17.11 and reuses its initial-stock mechanism (STOCK_ENTRY) when tracking is enabled.
- 17.12 must respect 17.1 correction/cancellation, inventory sessions, transfers, exports and alert flows; the fusion (story 20.1, RN app story 22.10) depends on it.
- 17.8 depends on 16.8 plan profiles; 17.2/17.7/17.8 depend on the epic 13 entitlement engine and the 16.1 module lifecycle.
- 17.9 extends the existing Python E2E scripts and dashboard tests (15.7) and requires staging (10-8 runbooks executed).
