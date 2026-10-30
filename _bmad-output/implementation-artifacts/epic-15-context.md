# Epic 15 Context: Frontend Modulaire

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make the Flutter merchant app modular on the client side: the app only shows, routes to and loads the modules the tenant actually has active (dynamic routing, extension slots, lazy-loaded module packages). Modules contributed by Epics 13/14 (notably the pilot Invoicing module) get their UI here, the owner gets a "Mes modules" screen, every existing feature is mapped to a module and gated, and the Next.js super-admin dashboard is made reliable (tests/CI) with Epic 9 completed (revenue, platform analytics, system health). This is what makes the modular offer visible and coherent to a merchant: a Free account never sees Premium screens it cannot use.

## Stories

- Story 15.1: `entitlementsProvider` Flutter (active modules)
- Story 15.2: Dynamic go_router routing by active modules
- Story 15.3: `ExtensionSlot` + lazy loading
- Story 15.4a: Melos workspace + `keevo_core` package
- Story 15.4b: Riverpod 2 → 3 migration (cross-cutting)
- Story 15.4c: Extract pilot module into `keevo_invoicing` package
- Story 15.5: Invoicing module UI
- Story 15.6: "Mes modules" screen (owner)
- Story 15.7: Next.js dashboard — CI/tests + Epic 9 completion
- Story 15.8: Map ALL Flutter features to modules + frontend gating

## Requirements & Constraints

- Frontend only mounts/loads active modules: routes, nav entries and slots of an inactive module are absent or guarded, including deep links; guard sends to home or a "module non activé" screen with an upgrade CTA.
- Entitlements gate everywhere (backend already gates endpoints; frontend gates routes/UI). The backend remains the source of truth; a JWT `modules` claim is meant for offline gating, but that claim is delivered by Epic 16.3 — until then, the API is the source. Offline: honour the last known signed state (consistent with the 7-day grace).
- Module states: `ENABLED`, `READ_ONLY` (paid module expired, 7-day read-only grace, then hidden), `DISABLED`. Data is never deleted on deactivation. Core is never auto-blocked.
- When a module is re-enabled, the app must re-pull that module's entities from scratch (the delta pull skips disabled providers but advances the cursor). Premium export datasets the tenant cannot use must be hidden (`GET /exports/datasets` is not filtered server-side).
- Free tenants: Premium features (multi-store/transfers, assisted inventory, profitability, advanced reports, exports…) are hidden or blocked with an upgrade CTA. The `debug` feature is excluded from release builds.
- Local Drift schema keeps all tables regardless of modules; gating is UI/route/sync only — document this choice.
- V1 has no self-service activation: upgrade CTAs tell the owner to contact support (manual Mobile Money activation by super-admin).
- Invoicing UI must cover: list (search by client name/number, issue-date range), manual creation (CRM client picker if CRM capability active, else free text), PDF preview, WhatsApp send, pay with selectable (back-dated) payment date, seller fiscal profile settings screen with `complianceWarnings` shown before issuing, credit notes (owner; full or per line with quantities, reason required; list per invoice with PDF; badges « Remplacée » via `supersededAt` and « Entièrement créditée » via `fullyCredited`).
- Offline-first invoices: sync ops `CREATE/UPDATE/ISSUE/PAY/CANCEL_INVOICE` with module id `invoicing`, an `invoices` delta provider (+ keyset index entry); numbering stays server-side (offline issue is held until sync).
- No technical data in UI (no UUIDs/raw codes); French labels only.
- Every routed "Deferred-from" item under a story is resolved or explicitly rejected with a reason as part of that story.

## Technical Decisions

- Stack: Flutter with Riverpod, go_router, Drift, Melos workspace; dashboard on Next.js 16 with Vitest.
- Riverpod 2 → 3 migration (15.4b) must happen before package extraction (15.4c) to avoid migrating twice; target `@riverpod`/`Notifier`/`AsyncNotifier`, no legacy `StateNotifier`. App currently on `flutter_riverpod ^2.5.1`, `go_router ^14`.
- Module = Dart package: `packages/keevo_core` (theme, router, di, sync, network moved from `lib/core/`, structural refactor only) then `packages/keevo_invoicing` depending only on `keevo_core`, registering its routes/slots through the registries of 15.2/15.3 and loaded `deferred`. Other features stay in `lib/features/` (gated) until incremental extraction post-pilot.
- Route registry per module; go_router `redirect`/`routes` driven by `entitlementsProvider`. Backend module manifests (`keevo/backend/src/main/resources/keevo/modules/<id>/module.json`) declare `routes` (e.g. invoicing: `/invoicing`, `/invoicing/:id`) — validate their format (leading `/`, go_router syntax) if routing consumes them.
- Module ids (manifests): `core, catalog, pos, store, sync, crm, invoicing, inventory, counting, multi-store, profitability, reporting, advanced-reports, report-config, exports, alerts, messaging, admin`. 15.8 maps the 18+ Flutter features onto these.
- Extension slots: decide ownership once — server-driven contributions (existing endpoint below) vs a Flutter-side registry — and never define the « Facturer » label/action twice. `deferred as` for heavy modules (PDF). Backend note to document or fail fast: a `@Bean`-method `List<Extension>` parameter outside `shared.module` silently receives an empty list.

### Backend surface already available (Epics 13/14)

- `GET /api/v1/tenants/me/modules` — OWNER and EMPLOYEE since 15.1 (`expiresAt`/`graceUntil` null for employees); `[{moduleId, displayName, state, expiresAt, graceUntil}]`. App side: `entitlementsProvider` (`keevo/app/lib/core/entitlements/`) — `isActive(id)` (ENABLED), `isReadable(id)` (ENABLED or READ_ONLY), status `unknown|claim|cached|server`; gate on it in 15.2+.
- `GET /api/v1/tenants/me/extensions/{pointId}` — OWNER/EMPLOYEE; `[{id, label, order, action}]` filtered on active modules and caller permissions; unknown point → 404. Invoicing fills `pos.checkout.post-sale-actions` (action `invoicing.open-sale-invoice`).
- Invoices `/api/v1/invoices`: `POST`, `GET` (list, supports `?saleId=`), `GET /clients`, `GET|PUT|DELETE /{id}`, `POST /{id}/issue|pay|cancel|send`, `GET /{id}/pdf`; `GET /api/v1/invoices/shared` (shared link).
- Credit notes: `POST /api/v1/invoices/{id}/credit-notes`, `GET /api/v1/credit-notes/{id}`, `GET /api/v1/credit-notes/{id}/pdf`. No list endpoint or export dataset yet — add `GET /api/v1/credit-notes` (paged, year filter) and a dataset if the screen needs them.
- Seller fiscal profile: `GET|PUT /api/v1/invoicing/settings`.
- Exports: `GET /api/v1/exports/datasets`, `POST/GET /api/v1/exports`, `/{id}`, `/{id}/file`.
- Admin (dashboard/Epic 16): `GET /api/v1/admin/modules`, `GET /api/v1/admin/tenants/{tenantId}/modules`, `POST .../modules/{moduleId}/activate|deactivate`.
- Gated endpoints answer 403 `MODULE_NOT_ENTITLED` or `MODULE_READ_ONLY`.

### Existing Flutter gating code (13.10b) to build on, not duplicate

- `lib/core/network/module_entitlement_exception.dart` — maps the two 403 codes to `ModuleEntitlementException` (French messages, « Passer à Premium », upgrade route `/settings/subscription`); never ends the session.
- `lib/core/widget/entitlement_upgrade.dart` — upgrade affordance, owner-only action.
- `lib/features/settings/presentation/provider/tenant_modules_provider.dart` — minimal `tenantModuleStatesProvider` (owner-only fetch, `isReadable`, `kMultiStoreModuleId`), explicitly a stub for 15.1 to replace/absorb.
- Router lives in `lib/core/router/app_router.dart`.

### Dashboard (15.7)

- `keevo/dashboard` already has Vitest (~56 test files), ESLint, a `dashboard-ci.yml` workflow and `src/app/{revenus,analytics,sante}` pages — verify against the ACs rather than rebuild. Routed item: `src/app/tenants/page.tsx` is a `'use client'` route page; spec wanted a server shell + client component. Module console (activate/deactivate) belongs to Epic 16.4.

## UX & Interaction Patterns

- Navigation (bar/menu) shows only active modules; consistent empty states when a module is disabled.
- `ExtensionSlot(pointId)` renders nothing when no active module contributes; with Invoicing active the POS checkout shows « Facturer », which opens the sale's auto invoice via `GET /api/v1/invoices?saleId=`; empty page → « Facture en cours de création » with retry. Cache slot contents per session, refreshed with entitlements.
- "Mes modules" (owner): active and available modules, status (TRIAL/ACTIVE/EXPIRED), expiry date, CTA that initiates a support request.
- Blocked Premium features show an upgrade CTA; employees see the message without the action.

## Cross-Story Dependencies

- 15.1 is the foundation for 15.2, 15.3, 15.5, 15.6, 15.8. 15.8 extends 15.2.
- 15.4a → 15.4b → 15.4c, strictly ordered; 15.4c needs the registries of 15.2/15.3; 15.5 lives in `keevo_invoicing` (15.4c).
- Epic 16.3 delivers the JWT `modules` claim consumed by 15.1 (API fallback until then); Epic 16.4 owns the admin module console.
- Relies on Epic 12.7 sync gating, Epic 13 module registry/entitlements/ExtensionRegistry, and Epic 14 invoicing (auto invoice on sale, checkout action, credit notes, CRM picker).
