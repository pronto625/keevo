---
title: '15.3 — ExtensionSlot + lazy loading (« Facturer » at checkout)'
type: 'feature'
created: '2026-10-31'
status: 'done'
baseline_revision: '1a3a3c76f3b617d8b2656d01e749f97bb62d9ede'
review_loop_iteration: 1
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-15-2-routing-dynamique-go-router.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Modules can contribute UI to another module's screen on the backend (14.5: `GET /api/v1/tenants/me/extensions/{pointId}` returns the active modules' contributions filtered by the caller's permissions — today « Facturer » on `pos.checkout.post-sale-actions`), but the app has no way to render them: the checkout never shows « Facturer » and a merchant cannot reach the invoice of a sale.

**Approach:** Server-driven slots (decision for the routed ownership item: the backend `ExtensionRegistry` is the single source of label/order/permission; the app never defines « Facturer » itself). An `ExtensionSlot(pointId, context)` widget fetches the contributions (cached per session, refreshed when module states change) and renders them as buttons; each contribution's `action` key is resolved through an app-side action registry to a handler, loaded with `deferred as` for heavy module code. First handler: `invoicing.open-sale-invoice` opens the sale's automatic invoice.

## Boundaries & Constraints

**Always:**
- Labels come from the server DTO (`label`, French); no technical id or action key is ever shown.
- An action key with no registered handler is not rendered (older app vs newer backend).
- Slot failures (offline, 404 unknown point, 5xx) render nothing and never block the screen; the cache is per session and dropped on sign-out / account switch; it refreshes when `entitlementsProvider`'s readable set changes.
- « Facturer » handler: `GET /api/v1/invoices?saleId=<id>`; found → open the invoice (minimal read-only invoice screen: number, status in French, client, total TTC, « Voir le PDF » which downloads `GET /api/v1/invoices/{id}/pdf` and opens it with the existing share/open path); empty page → message « Facture en cours de création » with « Réessayer » (sale not yet synced or bridge pending); 403/404 → existing French error widgets.
- The sale success page shows the slot only for a completed sale with its id; when the slot has at least one contribution the automatic return to `/pos` is cancelled and a « Nouvelle vente » button is shown. The sale detail page (history) shows the same slot.
- Invoice screen route `/invoicing/:id` is the manifest route (15.2 registry/guard apply).
- Bump `keevo/app/pubspec.yaml` 2.0.9+81 → 2.0.10+82.

**Never:**
- No full invoicing UI (list, creation, WhatsApp send, credit notes, fiscal profile — Story 15.5); the invoice screen here is the minimal read-only view 15.5 will extend.
- No package extraction (15.4c); `deferred as` stays inside `keevo/app`.
- No backend contract change for the extensions endpoint.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Invoicing active | slot answers `[{label:"Facturer", action:"invoicing.open-sale-invoice", order:100}]`, completed sale | « Facturer » button shown; auto-return cancelled | — |
| Invoicing inactive | slot answers `[]` | nothing shown; auto-return to `/pos` as today | — |
| Offline / error | slot request fails | nothing shown; no error UI | retried next session / entitlement change |
| Unknown action | `[{action:"other.thing"}]` | not rendered | — |
| Invoice exists | tap « Facturer », `?saleId=` returns one invoice | invoice screen opens with its number and total | — |
| Invoice pending | `?saleId=` returns empty page | « Facture en cours de création » + « Réessayer » | — |
| Cached slot | same point rendered twice in a session | one request | — |
| Module change | invoicing disabled → entitlements readable set changes | slot cache dropped; next render empty | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/module/adapter/in/rest/TenantExtensionsController.java:62` -- `GET /api/v1/tenants/me/extensions/{pointId}` → `[{id,label,order,action}]` (OWNER/EMPLOYEE; filtered by permission; unknown point → 404 « Point d'extension inconnu »).
- `keevo/backend/src/main/java/com/keevo/invoicing/**/adapter/in/extension/CheckoutInvoiceAction.java` -- « Facturer », action `invoicing.open-sale-invoice`, permission `INVOICE_VIEW`.
- `keevo/backend/src/main/java/com/keevo/invoicing/**/InvoiceController.java` -- `GET /api/v1/invoices?saleId=` (strict UUID), `GET /api/v1/invoices/{id}`, `GET /api/v1/invoices/{id}/pdf`; `InvoiceResponseDto` fields (number, status, clientName, totals).
- `keevo/app/lib/features/pos/presentation/page/sale_success_page.dart` -- auto `context.go('/pos')` after 1.8 s (initState); built from `/pos/success?total=&status=&drafts=` (`app_router.dart:~589`); caller `checkout_page.dart:83` — add the sale id (check that the local sale id equals the server sale id used by sync; if not, use the synced id and show nothing until it exists).
- `keevo/app/lib/features/pos/presentation/page/sale_detail_page.dart` -- history detail: add the slot.
- `keevo/app/lib/features/export/presentation/provider/export_providers.dart:30` -- existing file share/open path (`Share.shareXFiles`) to reuse for the PDF.
- `keevo/app/lib/core/entitlements/` + `core/router/module_routes.dart` (15.1/15.2) -- readable set for cache refresh; `/invoicing/:id` already in the registry (mount the route now).
- `keevo/app/lib/core/widget/app_error_widget.dart` -- French error rendering.
- Routed item (15.3, `@Bean` `List<Extension>` gap): `keevo/backend/src/main/java/com/keevo/shared/module/ModuleBeanAutowireExclusion.java` — document in the integration guide below.

## Tasks & Acceptance

**Execution:**
- `keevo/app/lib/core/extension/extension_slot.dart` (new) -- `ExtensionContribution` model, `extensionContributionsProvider` (family by point id, session cache, dropped on sign-out/account switch and readable-set change), `ExtensionSlot` widget (buttons ordered by `order`, hides unknown actions, nothing on error/loading).
- `keevo/app/lib/core/extension/extension_actions.dart` (new) -- action registry `Map<String, ExtensionActionHandler>`; handler signature receives `BuildContext`, `WidgetRef` and a context map (e.g. `{'saleId': …}`); invoicing handler loaded via `deferred as`.
- `keevo/app/lib/features/invoicing/` (new) -- data source (`?saleId=`, `{id}`, `{id}/pdf`), `open_sale_invoice` action, minimal `InvoiceDetailPage` (`/invoicing/:id`), pending/retry state, French status labels.
- `sale_success_page.dart` / `checkout_page.dart` / `app_router.dart` -- pass sale id, render slot, cancel auto-return when contributions exist, « Nouvelle vente »; mount `/invoicing/:id`.
- `sale_detail_page.dart` -- slot.
- `docs/modules/extension-slots.md` (new) -- integration guide: declare a point (manifest `extensionPoints`), contribute (`@ContributesExtension` + manifest `fills`, permission), app action key + handler registration, and the `@Bean`-method `List<Extension>` pitfall (contribution beans are not autowire candidates outside `shared.module`: inject `ExtensionRegistry` instead).
- tests -- provider (cache, refresh on readable-set change, error → empty), widget (each I/O row), handler (found / empty page / error), success page (auto-return kept vs cancelled).
- `keevo/app/pubspec.yaml` -- 2.0.10+82.

**Acceptance Criteria:**
- Given Invoicing active, when the POS checkout success screen shows the `pos.checkout.post-sale-actions` slot, then « Facturer » appears and opens the sale's invoice flow.
- Given Invoicing inactive, when the same slot renders, then nothing is shown.
- Given touched Flutter folders, when tests run one at a time, then all pass.

## Spec Change Log

### 2026-10-31 — review pass 1
- Trigger: blind + edge + verification-gap: a failed slot fetch was cached for the whole session (spec matrix "retried next session"), so one offline sale hid « Facturer » until the next sign-in in an offline-first app.
- Amended (outside the intent contract, applied incrementally): errors are not cached — the next render of the slot asks again; successes stay cached per session and the cache key covers every module state (not only the readable set).
- Known-bad state avoided: « Facturer » silently missing for a whole session after a network drop.
- KEEP: server-driven ownership, action registry + deferred handler, success-page auto-return logic, invoice lookup sheet, minimal invoice screen, docs guide, all existing tests.

## Review Triage Log

### 2026-10-31 — Review pass
- verdicts: 36 findings — high 0, medium 6, low 22, false 8, maybe-false 0
- findings:
  - `[medium]` `[bad_spec]` (blind/edge claim/vg other) failed slot fetch cached all session — amended: errors not cached.
  - `[low]` `[patch]` (blind) point id reused on the sale detail screen vs the doc's naming rule — doc states the point is shared by the post-sale screens.
  - `[low]` `[reject]` (blind) CANCELLED sale shows « Facturer » — the button opens the sale's invoice (with its credit note), which is what a cancelled sale needs; label belongs to the server.
  - `[low]` `[patch]` (blind) raw status strings in `sale_detail_page.dart` — `SaleStatus` constants.
  - `[medium]` `[patch]` (blind/edge) `findBySale` picks `invoices.first` when no active invoice is on the page — prefer the active one, else the latest by `createdAt`/`issuedAt`.
  - `[low]` `[patch]` (blind/edge) unstable order on ties / missing order = 0 — sort by order then id; missing order sorts last.
  - `[medium]` `[patch]` (blind/edge) double tap runs the handler twice (stacked sheets) — busy flag per slot.
  - `[low]` `[patch]` (blind/edge) `loadLibrary` failure gives no feedback — French snackbar.
  - `[low]` `[patch]` (blind) doc overstates code splitting — doc says the deferral is structural until deferred components are configured (15.4c).
  - `[medium]` `[patch]` (blind/edge) success page can lose « Nouvelle vente » after auto-return was cancelled — the button stays once the return is cancelled.
  - `[low]` `[reject]` (blind) pending sheet does not watch sync — « Réessayer » is explicit; watching sync is new machinery.
  - `[medium]` `[patch]` (blind/edge) invoice PDFs accumulate in temp (another account's PDFs stay on device) — clear `invoices/` temp before writing; empty body → French error.
  - `[low]` `[patch]` (blind/edge) `Invoice.fromJson` throws TypeError — FormatException with French message.
  - `[low]` `[reject]` (blind) `fileNameOf` ignores `filename*=` / accents — the backend sends ASCII `FAC-…pdf` names.
  - `[low]` `[patch]` (blind/vg) missing tests (DeferredWidget error/retry, production handler, PDF opener, CANCELLED detail, double tap, contributions vanishing) — tests added.
  - `[low]` `[reject]` (blind) `/invoicing/:id` not checking `INVOICE_VIEW` — the slot only offers the action to holders; a deep link without it gets the server's 403 French message.
  - `[low]` `[patch]` (blind) DeferredWidget loading state has no back button — AppBar added.
  - `[low]` `[patch]` (edge) READ_ONLY keeps the cached slot (same readable set) — cache key covers every module state.
  - `[low]` `[reject]` (edge) permission change on token renewal keeps stale buttons — role/permission changes go through sign-in in practice.
  - `[low]` `[patch]` (edge) duplicate contribution ids crash the Wrap (duplicate keys) — dedupe by id.
  - `[low]` `[reject]` (edge) slot answering after 4.8 s — the detail page offers the same action; documented fallback.
  - `[low]` `[patch]` (edge) '.'/'..' file name survives sanitising — rejected → fallback name.
  - `[low]` `[patch]` (edge) invoice id not encoded in the pushed route — `Uri.encodeComponent`.
  - `[medium]` `[patch]` (vg) checkout never proven to pass `sale=` — checkout test under a GoRouter asserting the success URI.
  - `[low]` `[patch]` (vg) production fetcher (path + `data` unwrap) untested — fetcher test on a Dio adapter.
  - `[low]` `[patch]` (vg) never-completing slot timeout untested — test pumps past the deadline and lands on the POS.
  - `[low]` `[patch]` (vg) CANCELLED detail slot untested — same as blind tests row.
  - `[low]` `[patch]` (vg) production handler/route only checked by presence — handler test + route child id test.
  - `[low]` `[patch]` (vg) `invoicePdfOpenerProvider` never run — opener test.
  - `[false]` `[reject]` (intent) not a client registry fed at boot — the routed decision allows server-driven; the client registry holds handlers only (no double « Facturer »).
  - `[false]` `[reject]` (intent) slot not in `checkout_page.dart` — the checkout's post-sale step is the success page.
  - `[false]` `[reject]` (intent) 15.5 behaviour pulled in — the per-sale « Facturer » flow and slot cache were routed to whichever story renders the slot; 15.5 keeps list/creation/credit notes.
  - `[false]` `[reject]` (intent) tests stub the fetcher — real fetcher test added (vg row).
  - `[false]` `[reject]` (intent) lazy loading not effective on mobile — doc wording patched; real splitting is 15.4c.
  - `[false]` `[reject]` (intent) pitfall documented, no fail-fast — the intent allows documentation.
  - `[false]` `[reject]` (intent) side surfaces (auto-return, detail page) beyond AC — required to show the slot after a sale.
  - `[false]` `[reject]` (edge claim) "refreshed when module states change" unmet for READ_ONLY — fixed by the cache-key patch.

## Verification

**Commands:**
- `cd keevo/app && flutter test test/core/extension` then `test/features/invoicing`, `test/features/pos`, `test/core/router` -- expected: all pass.
- `cd keevo/app && flutter analyze lib` -- expected: no errors/warnings.

**Manual checks:**
- Live on dev backend (owner kv_zggt7g): `GET /api/v1/tenants/me/extensions/pos.checkout.post-sale-actions` returns « Facturer »; `GET /api/v1/invoices?saleId=<recent sale>` returns its invoice.

## Auto Run Result

Status: done

**Summary:** Server-driven extension slots in the app: `ExtensionSlot(pointId, context)` fetches `GET /api/v1/tenants/me/extensions/{pointId}` (successes cached per session, keyed on every module state; errors not cached), renders the server's French labels, hides unknown action keys, and runs app-side handlers (busy guard, French snackbar on failure). « Facturer » (`invoicing.open-sale-invoice`, loaded `deferred as`) looks up the sale's invoice (`?saleId=`, active one preferred): found → minimal read-only invoice screen `/invoicing/:id` with « Voir le PDF »; empty → « Facture en cours de création » + « Réessayer ». The slot shows on the checkout success screen (auto-return cancelled when actions exist, « Nouvelle vente » kept) and on the sale detail (completed/cancelled). Integration guide `docs/modules/extension-slots.md` documents the `@Bean` `List<Extension>` pitfall.

**Review:** 36 findings — 6 medium (1 bad_spec amended incrementally: errors not cached; 5 patched: active-invoice choice, double tap, success-page trap, temp PDFs, checkout `sale=` untested), 15+ lows patched (dedupe/order, cache key per state, French errors, encoding, AppBar, doc wording, missing tests); the rest rejected with reasons. Implementation also fixed a real bug found by the new tests (DeferredWidget « Réessayer » did nothing).

**Follow-up review recommended:** true — 5 medium patches; unverified risk: autoDispose slot provider + session invalidation on a real device flow (sale offline then back online), not exercised on a phone (adb device unauthorized).

**Verification:** Flutter one folder at a time: core/extension 18, core/widget 59, invoicing 17, pos 294, core/router 117, auth 115, entitlements 28, export 143 — all pass; `flutter analyze lib` 0 errors/warnings. Live (dev kv_zggt7g): the slot endpoint returns « Facturer » for the owner and the test employee; `GET /api/v1/invoices?saleId=` returns FAC-2026-000008 « Payée ».
