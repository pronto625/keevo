---
title: '13.8b — App: held operations stay visible (sales, stock) + French labels for module audit entries'
type: 'feature'
created: '2026-10-30'
status: 'ready-for-dev'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-7-sync-module-aware.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-5-entitlement-service.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-8a-module-lifecycle-api.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Since 12.7/13.5 an operation refused with `MODULE_INACTIVE` or `MODULE_READ_ONLY` is **held** on the device (kept, retried). Live test 12.7 showed that while a sale is held, pulls overwrite the local views: the held sale disappeared from « Mes Ventes » and the stock showed the server value (19 instead of 18) until the module came back — the cashier may think the sale is lost and re-enter it (double sale, wrong stock). Now that 13.8a lets the super-admin disable a module (and 13.5 lets a module expire on its own), this is the blocking item before any module is disabled or expires in production. Separately, the owner's history (`/api/v1/audit`) and exports now receive `MODULE_ACTIVATED` / `MODULE_DEACTIVATED` entries with no French labels.

**Approach:** Keep local-only data of held operations through pulls and show held operations explicitly in the lists; add the French audit labels in the three places kept equal by contract tests.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- **Root cause first**: the implementer identifies exactly how the held sale vanished from « Mes Ventes » and how the stock was overwritten (pull reconciliation deleting/replacing local-only rows? list reading server data? stock level replaced by the server value?) and records it in Implementation Notes before fixing.
- **Data rules while an operation is held** (`MODULE_INACTIVE` / `MODULE_READ_ONLY`, and their held follow-ups):
  - the local entity it created or changed (sale, client, product, store, stock movement…) is never deleted or replaced by a pull;
  - local stock deltas of held stock-affecting operations (sales, adjustments, entries, transfers) stay applied on top of the server stock: displayed stock = server stock + pending/held local deltas (same rule the app already applies to pending operations, extended to held ones);
  - when the held operation finally applies, the next pull converges without double counting (deterministic movement ids already guarantee it — verify).
- **Visibility**: « Mes Ventes » lists held sales with a badge « En attente — module non activé » (or « En attente — abonnement expiré » for `MODULE_READ_ONLY`); same badge on a held client/product/supplier/store in its list; the sync queue screen already lists them (keep). No technical code, id or reason string shown; French only.
- **Audit labels**: add `MODULE_ACTIVATED` « Module activé », `MODULE_DEACTIVATED` « Module désactivé », entity type `Module` « Module », and every field key written by 13.8a (`module`, `statut`, `finAbonnement`, `entreprise`, `donnees`) with French labels, in the server `AuditLabels` (`ACTIONS`, `ENTITY_TYPES`, `FIELDS`), `keevo/contracts/export-datasets.json`, and the app `AuditPresenter` maps; contract tests green.
- **App version**: bump `pubspec.yaml` 2.0.3+74 → 2.0.4+75.

## Boundaries & Constraints

**Always:** offline-first rules unchanged (Drift + `sync_queue` in one transaction); money operations never lost; no technical data in the UI; Flutter tests by folder (`test/core/sync`, the touched `test/features/...` folders); backend contract tests for audit labels green; live test on the Redmi.

**Never:** no change to backend sync semantics, no new endpoint, no deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Held sale + pull | sale held `MODULE_INACTIVE`, pull runs | sale still listed with badge; stock = server − 1 | — |
| Held client + pull | client held | client still listed with badge | — |
| Held then applied | module re-enabled, retry applies | badge gone; stock = server value; no duplicate sale/movement | — |
| Read-only hold | `MODULE_READ_ONLY` | badge « abonnement expiré » | — |
| Normal pending sale | online, not held | unchanged behaviour | — |
| Audit entry | `MODULE_ACTIVATED` in history/export | « Module activé », French field labels | — |

</frozen-after-approval>

## Code Map

- `keevo/app/lib/core/sync/rest_sync_service.dart` (pull reconciliation, held handling), `sync_rejection_policy.dart`, `sync_monitoring_providers.dart`.
- Sales list: `keevo/app/lib/features/pos/...` (« Mes Ventes »), stock display (catalog/inventory providers).
- Audit: `keevo/app/lib/features/audit/presentation/audit_presenter.dart`; backend `keevo/backend/src/main/java/com/keevo/shared/infrastructure/export/AuditLabels.java`; `keevo/contracts/export-datasets.json` + their contract tests.

## Tasks & Acceptance

**Execution:**
- [ ] Root-cause note (how pulls hid the sale / reset the stock).
- [ ] Pull/data rules for held operations + tests (held sale survives a pull, stock delta kept, convergence after apply).
- [ ] Badges in « Mes Ventes » and entity lists + widget tests.
- [ ] Audit labels in the three places + contract tests; pubspec bump.
- [ ] Tick the Story 13.8 Flutter routed item and the 13.8a audit-labels item with evidence.

**Acceptance Criteria:**
- Given a held sale, when pulls run, then the sale stays visible with its badge and the stock keeps its local decrement; when the module comes back, the sale applies once and the stock converges.
- Given `flutter test` on the touched folders and the backend contract tests, then all pass; live Redmi scenario passes.

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Verification

**Commands:**
- `cd keevo/app && flutter test test/core/sync` + touched `test/features/...` folders -- expected: green
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.shared.**' -DforkCount=1 -DargLine=-Xmx1536m` (contract tests) -- expected: green
- Live (Redmi): crm/pos DISABLED row → offline sale (with client) → reconnect → pulls → sale visible with badge, stock −1 → module back → applied once, stock converged.
