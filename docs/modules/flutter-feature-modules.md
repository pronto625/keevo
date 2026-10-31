# Flutter features → modules

Story 15.8. Every Flutter feature (`keevo/app/lib/features/<folder>`) and module
package (`keevo/app/packages/keevo_<x>`) is attached to a backend module (manifest
id, `keevo/backend/src/main/resources/keevo/modules/<id>/module.json`).

- **Code table**: `kFeatureModules` in `keevo/app/lib/core/modules/feature_modules.dart`.
- **Check**: `keevo/app/test/core/modules/feature_modules_test.dart` fails when a
  feature folder or module package is missing from the table, when a module id is
  not a manifest id, or when a surface route does not belong to its module in the
  route registry (`lib/core/router/module_routes.dart`, itself checked against
  `keevo/contracts/module-routes.json`, generated from the manifests).

## Gating rules

- A feature's **main module** is free (always on) except `contact` (`crm`, free but
  can be disabled by a super-admin) and `profitability` / `keevo_invoicing`
  (premium): the whole feature follows its module.
- A feature's **premium surfaces** (another module's screens or actions inside it)
  are gated:
  - a surface with a route → the route is in its module's manifest `routes`
    (+ contract regeneration, `ModuleRoutesContractFileTest`
    `-Dmodule.routes.contract.write=true`); the router's module guard sends a
    non-readable module's route (deep links included) to « module non activé »;
    the in-page entry (tab, tile, button, link) is hidden with
    `isRouteAvailable(ref, route)`;
  - an entry with no route of its own → hidden with
    `isModuleAvailable(ref, moduleId)` (readable), or `isModuleActive(ref,
    moduleId)` when it writes (READ_ONLY grace → hidden);
  - a server-side surface (`serverSide`, e.g. `alerts` push notifications) is
    produced and gated by the server only; the app just displays it.
- Same rule everywhere: nothing known yet → shown (the backend 403 and its French
  message stay the fallback); `READ_ONLY` (grace) → shown, except write
  actions; otherwise hidden.
- No module id on screen: the « module non activé » page shows the module's French
  name.

## Table

| Feature | Main module | Gated surfaces (module — entry) |
|---|---|---|
| `account` | core | — |
| `audit` | core | — |
| `auth` | core | — |
| `catalog` | catalog | crm — supplier card of the product (`/suppliers/:id`, no chevron/tap without CRM); multi-store — « Vue stock multi-boutiques » (`/stock/overview`, otherwise the free local « Stock bas » list; tooltip and tap share `canOpenStockOverview`) |
| `contact` | crm | (the whole feature: `/clients…`, `/suppliers…`) |
| `dashboard` | reporting | multi-store — low-stock tile opens `/stock/overview` only with the module (otherwise the local list). `/dashboard/store/:storeId` stays free (local data + `/dashboard/summary`, `reporting`) |
| `debug` | core | debug builds only: `/debug/…` routes not mounted in release (`buildAppRoutes(includeDebug: kDebugMode)`) |
| `export` | reporting | exports — « Mes exports » (`/exports`, `/exports/:id`). `/exports/new` and `/exports/local` (CSV) stay free |
| `feedback` | core | — |
| `help` | core | — |
| `identity` | core | (tenant preferences; the report preferences screen is under `settings`) |
| `inventory` | inventory | counting — « Inventaire » (`/inventory`, counting, gap report; after validation without the module: back to Plus); multi-store — overview and transfers (`/stock/overview`, `/stock/transfers`) |
| `notifications` | messaging | alerts — owner alerts (stock, product changes), server-side push only |
| `onboarding` | core | — |
| `pos` | pos | crm — checkout client selector (no client sent once CRM is not readable); invoicing — « Facturer » (server-driven extension slot `pos.checkout.post-sale-actions`) |
| `profitability` | profitability | multi-store — « Boutiques » (`/reports/boutiques`); advanced-reports — « Équipe » seller leaderboard (`/reports/equipe`) |
| `reports` | reporting | profitability — « Marges » tab; multi-store — « Boutiques » tab; advanced-reports — « Équipe » tab |
| `settings` | core | report-config — « Rapports & WhatsApp » (`/settings/reports`); advanced-reports — weekly report settings, its « Aperçu » and the trend alerts (shown only when the module is active); alerts — « Alertes Stock Bas » channel (shown only when the module is active); crm, exports, counting, multi-store, invoicing — the matching tiles |
| `stores` | store | — |
| `sync_indicator` | sync | — |
| `team` | core | — |
| `keevo_invoicing` (package) | invoicing | (the whole package: `/invoicing…`) |

## Local data (Drift, gap G14)

The local Drift database keeps **all** its tables whatever the tenant's modules
(e.g. clients, inventory sessions, `module_records` of invoicing). Tables of an
inactive module stay on the device: harmless, never deleted (data is kept for
life, a re-enabled module finds them again and re-pulls from scratch). Gating is
done on the **UI** (hidden entries), the **routes** (module guard) and the
**sync** (Story 12.7: the server skips the delta providers and refuses the
operations of a disabled module) — not on the local schema.

## Orientation

No tablet layout in the app: portrait everywhere (Android
`android:screenOrientation="portrait"`, iOS `UISupportedInterfaceOrientations`
portrait, iPad portrait + upside down with `UIRequiresFullScreen`), checked by
`test/core/notification/ios_push_config_test.dart`.
