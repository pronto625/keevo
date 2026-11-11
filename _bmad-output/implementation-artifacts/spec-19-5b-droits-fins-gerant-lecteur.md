---
title: '19.5b — Fine rights of MANAGER and VIEWER as module permissions (store sales, inventory sessions, team sessions, reactivation, product merge, quote end)'
type: 'feature'
created: '2026-11-11'
status: 'done'
baseline_revision: '81c0d3fa14411fc6872f6e5ac864a4507b5734d9'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-5-roles-manager-viewer-permissions.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-16-13-scope-employe-donnees-synchro.md'
warnings: [oversized]
deferred:
  - summary: >-
      Sync pull of sales ignores STORE_SALES_VIEW (an EMPLOYEE pulls every sale of his store).
    evidence: |-
      16-13 scoped the sales provider to the store; REST and the AI tool now give an EMPLOYEE his own sales. Routed to 19-14.
    location: >-
      keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/SaleDeltaProvider.java
    severity: medium
  - summary: >-
      Team management by a delegate is REST-only; sync employee operations stay owner-only.
    evidence: |-
      EmployeeSyncHandler refuses non-owners; EmployeeCommandAdapter records deactivations as owner-level. Restrictive, safe. Routed to 19-14.
    location: >-
      keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/EmployeeSyncHandler.java
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Story 19.5 made MANAGER and VIEWER « never less restricted than an EMPLOYEE » and routed nine fine-right questions to 19-5b (epics-fusion-keevo-bref.md § Story 19.5b): a MANAGER only sees his own sales and cannot cancel a seller's inventory session nor see/close his sellers' device sessions, may reactivate a member the owner deactivated; a VIEWER has an empty sales history; any seller may irreversibly merge products; an employee can delete a draft quote but not cancel it with a reason; plan-cap counting of MANAGER/VIEWER is unconfirmed. Four items (expenses, documents per role, public-link rotation, invoice store filter) were decided by the user on 2026-11-08 and delivered by 20-10 / 20-14.

**Approach:** Each remaining rule becomes a module permission declared in a manifest (`rolePermissions`, like 16-13's `PURCHASE_PRICE_VIEW`) and evaluated through ONE component, `ActorPermissions` (current actor → `ModulePermissions`), or `@RequiresPermission`. Fixed safe defaults now; story 19-14 replaces only the role → permission mapping.

## Boundaries & Constraints

**Always:**
- New permissions (OWNER holds all implicitly): `pos` `STORE_SALES_VIEW` → MANAGER, VIEWER; `counting` `INVENTORY_SESSION_MANAGE` → MANAGER; `catalog` `PRODUCT_MERGE` → MANAGER; `core` `TEAM_MANAGE` → MANAGER. EMPLOYEE gets none of them.
- Sales history (REST `GET /api/v1/sales/history`, AI tool `list_sales`): one adapter policy `SalesReadScope`. Holder of `STORE_SALES_VIEW` that is a store member → the sales of HIS store (optional `employeeId` filter honoured); another `storeId` → 403 `FORBIDDEN` « Vous ne pouvez consulter que les ventes de votre boutique. »; store member without the permission → own sales only, same 403 for another store; OWNER unchanged. Store member without store → 403 (REST) / French invalid-request (tool). `GetSalesHistoryService` no longer reads the role: `employeeId == null` → store-wide, else filtered (the adapter decides). Sync sales pull already store-wide for members (16-13) — unchanged.
- Inventory session cancel (`POST /inventory/sessions/{id}/cancel`): holder of `INVENTORY_SESSION_MANAGE` may cancel any session of his store (store member) or any session (owner); others only their own session; a session of another store → 403. Session lists are already store-wide for members (16-13) — unchanged.
- Product merge (`POST /products/{id}/merge`): `@RequiresPermission(PRODUCT_MERGE)` → EMPLOYEE 403 `FORBIDDEN`. Archive unchanged.
- Team: `EmployeeTeamScope` decides the non-owner team manager by `TEAM_MANAGE` (no more `isManager()`); same target rule as 19.5 (EMPLOYEE/VIEWER members of his store). Device sessions of a member (`GET|DELETE /api/v1/employees/{employeeId}/sessions[/{sessionId}]`) go through `teamScope.requireManageable` instead of owner-only.
- Reactivation: public migration `V77` adds `user_tenant_memberships.deactivated_by_manager BOOLEAN NOT NULL DEFAULT FALSE`; a deactivation through the team rule by a non-owner sets it, any other deactivation (owner, sync, super-admin, removal) clears it, reactivation clears it. A non-owner team manager may reactivate only when the flag is true, else 403 `FORBIDDEN` « Ce membre a été désactivé par le propriétaire : lui seul peut le réactiver. » Existing inactive rows (default false) → owner only.
- Quote end: cancelling a QUOTE requires `INVOICE_CREATE` (same as deleting it); cancelling an INVOICE keeps `INVOICE_MANAGE`. REST: endpoint annotated `INVOICE_CREATE`, then one policy (`DocumentCancelPermission`) reads the kind (one PK read) and requires `INVOICE_MANAGE` for an invoice; sync `CANCEL_INVOICE` uses the same policy. Document visibility (20-14) still applies first (employee: own quotes only).
- Plan ceiling: every ACTIVE member counts (MANAGER, EMPLOYEE, VIEWER) — confirmed rule, pinned by a test, no code change.
- VIEWER POST reads: no change — the only POST read (`POST /exports`) is owner-only; the assistant is already allow-listed. Documented.
- French user messages, no technical data; additive only for V1 2.0.1 (no response shape change).

**Never:**
- No scattered role checks for these rules (`isManager()`, role strings); no owner-only surface widened beyond the above; no change to OWNER or EMPLOYEE behaviour except merge (EMPLOYEE loses it, decided) and own-store guard on sales history; no per-tenant roles (19-14); no Flutter/dashboard work.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected | Error |
|---|---|---|---|
| MANAGER of A, sales history A | sales by 2 sellers | both sellers' sales | — |
| VIEWER of A, sales history A | same | both sellers' sales | — |
| EMPLOYEE of A, sales history A | same | own sales only | — |
| Member of A, history of B | — | — | 403 French message |
| MANAGER cancels seller's session in A | IN_PROGRESS | 200 cancelled | — |
| MANAGER cancels session in B | — | — | 403 |
| EMPLOYEE cancels colleague's session | — | — | 403 (unchanged) |
| EMPLOYEE merges products | — | — | 403 FORBIDDEN |
| MANAGER lists/closes seller's devices | seller of A | 200 | — |
| MANAGER lists owner's devices / seller of B | — | — | 403 |
| MANAGER reactivates member he deactivated | flag true | 200 | — |
| MANAGER reactivates owner-deactivated member | flag false | — | 403 French message |
| EMPLOYEE cancels own draft quote | INVOICE_CREATE | 200 CANCELLED | — |
| EMPLOYEE cancels issued invoice | — | — | 403 FORBIDDEN (unchanged) |
| VIEWER created at plan ceiling | max reached | — | PLAN_LIMIT_EXCEEDED |

</intent-contract>

## Code Map

- `shared/infrastructure/security/PurchasePriceVisibility.java` -- 16-13 pattern to mirror (ObjectProvider<ModulePermissions>, fail closed).
- `shared/module/application/ModulePermissions.java:97-125` -- `permissionsFor`, `permissionsOf`, `rolesOf`; boot validation (VIEWER perms end with `_VIEW`).
- `resources/keevo/modules/{pos,counting,catalog,core}/module.json` -- `permissions` + `rolePermissions`.
- `commerce/sale/adapter/in/rest/SaleHistoryController.java:80-95`, `commerce/sale/adapter/in/mcp/SalesMcpTools.java:76-95`, `commerce/sale/application/service/GetSalesHistoryService.java`, `commerce/sale/domain/port/in/GetSalesHistoryUseCase.java:29` (drop `role`), test `GetSalesHistoryServiceTest`.
- `inventory/counting/application/service/CancelInventorySessionService.java:38`, `domain/port/in/CancelInventorySessionCommand.java`, `adapter/in/rest/InventorySessionController.java:107-116`.
- `catalog/product/adapter/in/web/ProductController.java:170` -- merge endpoint.
- `identity/employee/adapter/in/rest/EmployeeTeamScope.java:115-124` (`requireManagerStore`), `EmployeeController.java:155-178`, `identity/auth/adapter/in/rest/DeviceSessionController.java:73-103`.
- `identity/employee/domain/port/in/DeactivateEmployeeCommand.java`, `application/service/DeactivateEmployeeService.java:104`, `ReactivateEmployeeService`, `identity/auth/domain/port/out/UserMembershipRepository.java`, `auth/adapter/out/persistence/impl/UserMembershipRepositoryAdapter.java:66-85`, `entity/UserTenantMembershipJpaEntity.java`, `db/migration/V77__membership_deactivated_by_manager.sql`.
- `invoicing/invoice/adapter/in/rest/InvoiceController.java:276`, `adapter/in/sync/InvoiceSyncHandler.java:105-115,217,269`, `application/usecase/DocumentAccess.java` (scope first), `InvoiceRepository.java:218` (`ownerOf` — add the kind).
- `subscription/plan/...`, `identity/employee/application/service/CreateEmployeeService.java` -- plan ceiling counts every active employee row.

## Tasks & Acceptance

**Execution:**
- `shared/infrastructure/security/ActorPermissions.java` (new) -- the single « does the current actor hold P » policy over `ModulePermissions`, fail closed.
- Manifests -- the four permissions and MANAGER/VIEWER mapping.
- `commerce/sale/adapter/in/SalesReadScope.java` (new) + controller + tool + service + port -- store-wide sales for holders, own-store guard.
- Counting command/service/controller -- `INVENTORY_SESSION_MANAGE` rule with store check.
- `ProductController` -- `@RequiresPermission(PRODUCT_MERGE)`.
- `EmployeeTeamScope`, `EmployeeController`, `DeviceSessionController` -- `TEAM_MANAGE`, device sessions through the team rule, reactivation rule.
- Membership port/adapter/entity, `DeactivateEmployeeCommand`/service, `ReactivateEmployeeService`, `V77` -- deactivated-by-manager flag.
- `invoicing/.../DocumentCancelPermission.java` (new), `InvoiceController`, `InvoiceSyncHandler`, `InvoiceRepository.ownerOf` (+ kind) -- quote end rule.
- Tests -- unit (policies, services), full-context role matrix (`FineRightsFullContextTest`: sales history, inventory cancel, merge, device sessions, reactivation, quote cancel), ceiling test, manifest validation green.
- Docs -- `docs/entitlements/endpoints.md`, `docs/ai/tools.md` regenerated; epics 19.5b items ticked + 19.14 hooks; sprint-status; Mobile section.

**Acceptance Criteria:**
- Given owner, manager, two employees and a viewer of store A (and a store B), when each calls the matrix above on REST (and sync for quote cancel), then results match the matrix and OWNER results are unchanged.
- Given the full suite run package by package (commerce, inventory, catalog, identity, invoicing, shared, sync, subscription, architecture), then all green.

## Spec Change Log

## Review Triage Log

### 2026-11-11 — Review pass
- verdicts: 33 findings — high 1, medium 3, low 19, false 4, maybe-false 0, plus the intent audit (6 descriptive divergences, logged as rows 28–33)
- findings:
  - `[low]` `[patch]` (design 1) device-session endpoints read the employee row twice for a delegate — `requireManageableUser` returns the user id resolved by the team check; `DeviceSessionController.userOf` reads it only for the owner.
  - `[low]` `[patch]` (design 2, edge 7) cancel path reads the invoice row twice (`ownerOf` then `isQuote`) — `DocumentScope.Owner` carries `quote` (same PK query), `DocumentAccess.requireInvoice` returns it, `DocumentCancelPermission.require(id, known)` reuses it; `isQuote` kept only for an unrestricted actor without INVOICE_MANAGE (unreachable with shipped roles).
  - `[low]` `[patch]` (design 3) `ActorPermissions` builds a role set and resolves the provider on every call — owner detected on the authorities stream, mapping resolved once into a field.
  - `[low]` `[reject]` (design note) `DocumentCancelPermission` in application layer reads the security context — same pattern as the existing `DocumentAccess` (20.14) it pairs with; moving both is a 20.14-wide refactor, no named harm.
  - `[low]` `[reject]` (design note) two `deactivate` overloads on the port — one implementation; a default method is cosmetic.
  - `[low]` `[reject]` (design note) tenant lookup per `EmployeeRoleResolver.membership` — pre-existing keyed lookup, noted.
  - `[low]` `[patch]` (design note, blind 6) `requireCurrentActorHolds` unused and put a raw code in a message — removed.
  - `[high]` `[patch]` (edge 1, edge 5) a delegate re-deactivating a member the OWNER deactivated flipped `deactivated_by_manager` to true, then could reactivate him — the adapter now only LOWERS the flag on an already inactive membership; full-context test (manager re-deactivates, flag stays false, reactivation 403).
  - `[low]` `[patch]` (edge 2, edge 6, vg other 1) unknown id answered 403 instead of the not-found error, contrary to the javadoc — unknown kind now passes through (`Optional.empty` → return); unreachable today since `requireInvoice` 404s first.
  - `[low]` `[patch]` (edge 3, blind 3) OWNER short-circuit accepts an undeclared permission name — `ActorPermissions.isDeclared` + `ActorPermissionsTest.callSiteConstantsAreDeclared` pins every call-site constant against the shipped manifests.
  - `[medium]` `[patch]` (edge 4) `toEntity` dropped the flag on `save` of an existing membership — flag copied into the entity.
  - `[low]` `[patch]` (blind 1) `SalesReadScope` returned an unrestricted read to a non-owner non-member (SUPER_ADMIN / future role) — now FORBIDDEN (fail closed); `@PreAuthorize` already excluded it.
  - `[false]` `[reject]` (blind 2) `ActorPermissions` and `PermissionAuthorizationManager` disagree — both read `ModulePermissions.permissionsOf`; the only difference is the owner short-circuit, equal to `permissionsOf(OWNER)` = every declared permission, and declared names are now pinned (row above). Without the mapping bean the app does not boot.
  - `[low]` `[reject]` (blind 4) English role-named internal messages in `EmployeeTeamScope` — not user-facing (the handler renders the French FORBIDDEN message); 19-14 renames scopes anyway.
  - `[low]` `[reject]` (blind 5) English internal messages in `DocumentCancelPermission` / `CancelInventorySessionService` — not user-facing (generic French FORBIDDEN rendered).
  - `[medium]` `[defer]` (blind 7, intent c) team rule not mirrored in sync (`EmployeeSyncHandler` owner-only), `EmployeeCommandAdapter` always 2-arg — restrictive direction, safe; routed to 19-14 (team permission on sync push).
  - `[false]` `[reject]` (blind 8) quote cancel wider than drafts — a quote is DRAFT → CONVERTED/CANCELLED only (20.3); a converted quote is refused by the domain (409, `QuoteFullContextTest.convertAndReplay`).
  - `[low]` `[reject]` (blind 9) layering of `DocumentCancelPermission` — same row as design note above.
  - `[low]` `[reject]` (blind 10) member without store on inventory cancel gets the generic FORBIDDEN, not the French NO_STORE text — same 403, rare (store members are always assigned a store at creation).
  - `[low]` `[reject]` (blind 11) missing tests (SalesReadScope unit, VIEWER on merge/sessions, manager quote) — VIEWER writes are blocked by the 19.5 filter (parameterised test over every write endpoint); SalesReadScope covered through `SaleHistoryControllerTest` (4 roles, other store) and `AiToolsFullContextTest`.
  - `[low]` `[reject]` (blind 12) owner test reuses the manager id — assertion still distinguishes `byManager=false`; cosmetic.
  - `[low]` `[patch]` (blind 13) merge javadoc omitted the VIEWER — completed; spec/tool name `sales_history` corrected in `SalesReadScope` javadoc.
  - `[false]` `[reject]` (blind 14) teardown leaves the test tenant — same convention as every `FullContextIntegrationTest` (16-13 test included), disposable test database.
  - `[medium]` `[patch]` (vg 1) DELETE device-session endpoints untested for out-of-team callers — `FineRightsFullContextTest.deviceSessions` now refuses another store's seller, a colleague manager and a seller, on both DELETE forms.
  - `[low]` `[reject]` (vg other 2) `InvoiceSyncHandlerTest.cancel` name vs provisioner call — provisioning is idempotent and reachable by an employee anyway.
  - `[medium]` `[defer]` (intent c) sync pull sends every sale of the store to an EMPLOYEE (16-13 scope) while REST/AI give him his own — pre-existing (16-13 decision « sales of S »); routed to 19-14 (`STORE_SALES_VIEW` in `PullScope` for the sales provider).
  - `[false]` `[reject]` (intent a) expenses not owner-only — user decision recorded in `fix(20-10)` 5701ee1d « propriétaire et gérant (écart assumé avec Bref) », and 19-14 brief « owner + manager »; DD7, reported to the user.
  - `[low]` `[reject]` (intent b) `PurchasePriceVisibility` not routed through `ActorPermissions` — 16-13's policy, same mapping, constant pinned by the new test; merge into one evaluator left to 19-14.
  - `[low]` `[reject]` (intent b) structural owner / store-member checks remain in the scopes — they encode « which store », not a fine right; 19-14 hook documented.
  - `[low]` `[reject]` (intent e) non-additive changes (merge 403, other-store sales history 403) — merge decided (DD2); a member asking another store previously got an empty page of his own sales, V1 always sends its own store.
  - `[false]` `[reject]` (intent f) docs uncommitted / tools.md missing — regenerated and committed in the finalize commit (tools.md changed by the tool description).
  - `[low]` `[patch]` (intent f) spec lacked Mobile/Dashboard sections, sprint-status not updated — added at finalize.
  - `[low]` `[reject]` (intent d) no test of sync pull under new permissions — no new pull rule in this story (see deferred row).

### Deferred items routed 2026-11-11
- [ ] [Deferred-from: review 19-5b, 2026-11-11] Sync pull of sales ignores `STORE_SALES_VIEW` (an EMPLOYEE pulls every sale of his store, REST shows his own) [keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/SaleDeltaProvider.java] → 19-14
- [ ] [Deferred-from: review 19-5b, 2026-11-11] Team management by a delegate is REST-only: sync `CREATE/REASSIGN/(DE|RE)ACTIVATE_EMPLOYEE` stay owner-only and `EmployeeCommandAdapter` records deactivations as owner-level [keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/EmployeeSyncHandler.java] → 19-14

## Design Notes

**Patterns.** Policy object behind module permissions (same as 16-13's `PurchasePriceVisibility`): declarative mapping in manifests (boot-validated), one evaluator `ActorPermissions`; adapter-level scope objects (`SalesReadScope`, `EmployeeTeamScope`) resolve the actor once and hand plain values to use cases (no security read in services, except the existing `DocumentAccess` pattern). **19-14 hook:** owner-defined roles replace `ModulePermissions.permissionsOf` (role → permission set); `STORE_SALES_VIEW` « Voir les ventes de toute la boutique », `INVENTORY_SESSION_MANAGE` « Gérer les inventaires de la boutique », `PRODUCT_MERGE` « Fusionner des produits », `TEAM_MANAGE` « Gérer l'équipe de sa boutique », quote cancel = `INVOICE_CREATE`; no call site changes. The `deactivated_by_manager` flag generalises to « deactivated by a non-owner ».

**Complexity.** Permission checks: O(1) set lookups on cached immutable sets. Sales history: unchanged queries (`findByStoreIdAndDateRange` / by employee), no extra query. Inventory cancel: the existing PK read. Device sessions: two keyed reads (employee, membership) as 19.5. Reactivation: one keyed membership read. Quote cancel: one PK read (`ownerOf` extended with the kind) instead of none for owners (owner path skipped). V77: metadata-only `ADD COLUMN … DEFAULT FALSE` (PostgreSQL ≥ 11, no rewrite).

**Decisions taken by default (no user answer, safest coherent choice):** DD1 VIEWER sees his store's sales (aligned with the 2026-11-08 « gérant/lecteur = leur boutique » document rule). DD2 product merge = OWNER + MANAGER. DD3 quote cancel = same right as quote delete. DD4 owner-deactivated members stay owner-only to reactivate; no owner alert needed. DD5 MANAGER/VIEWER count in the plan ceiling. DD6 VIEWER POST reads: nothing to open. DD7 expenses stay OWNER + MANAGER (user decision recorded in `fix(20-10)` 5701ee1d; the brief's « propriétaire seul » wording predates it).

## Verification

**Commands:**
- `scratchpad/wt-pkgs.sh <worktree>/keevo/backend t195b <pkg>` for commerce, inventory, catalog, identity, invoicing, shared, sync, subscription, architecture -- expected: 0 failures.

**Manual checks:**
- Live on port 4595 / `keevo_dev_t195b`: manager reads store sales, cancels a seller's inventory session, lists the seller's devices; employee merge → 403; manager reactivation of an owner-deactivated member → 403.

## Auto Run Result

Status: done

**Summary.** The open 19.5b questions become module permissions with safe fixed defaults, evaluated by one component (`ActorPermissions`, or `@RequiresPermission`): `STORE_SALES_VIEW` (pos; MANAGER, VIEWER) — store-wide sales history on REST and the `sales_history` AI tool through `SalesReadScope` (EMPLOYEE keeps his own sales; another store → 403 French); `INVENTORY_SESSION_MANAGE` (counting; MANAGER) — cancel a seller's inventory session of his store; `PRODUCT_MERGE` (catalog; MANAGER) — merge refused to EMPLOYEE; `TEAM_MANAGE` (core; MANAGER) — `EmployeeTeamScope` decides by permission, members' device sessions follow the team rule; V77 `user_tenant_memberships.deactivated_by_manager` — a delegate only reactivates members a delegate deactivated (owner-deactivated stay owner-only, and a re-deactivation can never raise the flag); quote cancel = `INVOICE_CREATE` (same right as deleting it), invoice cancel unchanged (`DocumentCancelPermission`, REST + sync). Confirmed: MANAGER/VIEWER take a seat in the plan ceiling (pinned test); VIEWER POST reads: nothing to open. Already delivered and verified: expenses owner + manager (20-10), documents per role and public-link rotation (20-14).

**Files.** New: `shared/infrastructure/security/ActorPermissions`, `commerce/sale/adapter/in/SalesReadScope`, `invoicing/invoice/application/usecase/DocumentCancelPermission`, `db/migration/V77__membership_deactivated_by_manager.sql`, tests `ActorPermissionsTest`, `ShippedActorPermissions`, `architecture/FineRightsFullContextTest`. Changed: manifests `pos`, `counting`, `catalog`, `core`; sales history controller/tool/service/port; counting cancel command/service/controller; `ProductController`; `EmployeeTeamScope`, `EmployeeController`, `EmployeeRoleResolver`, `DeviceSessionController`; membership model/port/adapter/entity, `DeactivateEmployeeCommand`/service; `InvoiceController`, `InvoiceSyncHandler`, `DocumentAccess`, `DocumentScope.Owner` (+kind), `InvoiceRepository`/`JdbcInvoiceRepository`; docs `endpoints.md`, `tools.md`; tests updated (sales, counting, employees, invoicing, manifests).

**Review.** 33 findings (5 layers): high 1, medium 3, low 19 (+ intent rows), false 4. Patched: the high (delegate laundering an owner's deactivation), 2 medium (flag lost on save, untested DELETE device-session refusals) and 10 low (double reads, fail-closed scope, unknown id pass-through, pinned permission names, unused method, docs). Deferred → 19-14: 2 (sync pull of sales vs `STORE_SALES_VIEW`; team operations over sync). Rejected with reasons in the triage log.

**Follow-up review: recommended (true)** — one high and two medium entries patched; the unverified risk is the membership-flag rule under concurrent deactivations (owner and manager racing on the same member) — both paths are single-row updates without a lock, last writer wins.

**Verification.** Chunked Maven runs (tag t195b): commerce 273, inventory 446, catalog 373, identity 872, invoicing 471, architecture 151, sync 691, subscription 153, admin 690, shared 1382 (1 manifest assertion updated, `shared/module` rerun 555) — 0 failure after the reruns. Live (port 4595, DB `keevo_dev_t195b`, WhatsApp noop, FCM off, then dropped): real registration and member creation; sales history A: manager 2, viewer 2, employee 1; manager on B → 403 « Vous ne pouvez consulter que les ventes de votre boutique. »; seller cancels a colleague's inventory → 403, manager → 200 CANCELLED; seller merge → 403, manager → 200; manager lists a seller's devices 200, another store's seller 403; owner deactivates seller1 → manager reactivation 403 « Ce membre a été désactivé par le propriétaire : lui seul peut le réactiver. »; manager deactivates seller2 (flag true) then reactivates 200; owner reactivates seller1 200.

**Residual risks.** Expenses stay OWNER + MANAGER (DD7) although the orchestration brief says « owner only » — the 2026-11-08 user decision recorded in `fix(20-10)` says owner + manager; confirm with the user. The two sync gaps routed to 19-14. V77 is out of order relative to V72–V76 reserved for parallel stories (deploy all together, or enable Flyway out-of-order).

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commits `6c6ef633` et `f545cce3`). Conventions : spec-16-11 ; synchro : spec-16-10. **Clé de suivi mobile :** `m19-5b-droits-fins-gerant-lecteur`.

**Ce qui est fait côté API :** droits fins par défaut (modifiables plus tard par la 19-14) — le gérant et le lecteur voient toutes les ventes de leur boutique ; le gérant annule l'inventaire d'un vendeur de sa boutique, voit et ferme les appareils connectés de ses vendeurs et lecteurs, fusionne des produits ; un membre désactivé par le propriétaire n'est réactivé que par lui ; un vendeur peut annuler son propre devis ; la fusion de produits est refusée au vendeur.

**À utiliser :**
1. `GET /api/v1/sales/history?storeId=<sa boutique>&employeeId=<facultatif>` — gérant et lecteur : toutes les ventes de la boutique (filtre vendeur possible) ; vendeur : ses ventes seulement. Autre boutique → **403** `FORBIDDEN` « Vous ne pouvez consulter que les ventes de votre boutique. » ; membre sans boutique → 403 « Votre compte n'est rattaché à aucune boutique : demandez au propriétaire de vous en attribuer une. » (`commerce/sale/adapter/in/SalesReadScope.java`).
2. `POST /api/v1/inventory/sessions/{id}/cancel` — gérant : toute session de sa boutique ; session d'une autre boutique → 403 « Vous ne pouvez gérer que les inventaires de votre boutique. » ; vendeur : ses sessions seulement (403 sinon, message générique « Vous n'avez pas les droits nécessaires »).
3. `POST /api/v1/products/{id}/merge` — propriétaire et gérant ; vendeur → 403 `FORBIDDEN` « Vous n'avez pas les droits nécessaires ».
4. `GET /api/v1/employees/{employeeId}/sessions`, `DELETE /api/v1/employees/{employeeId}/sessions[/{sessionId}]` — propriétaire, ou gérant pour un vendeur / lecteur de sa boutique ; sinon 403.
5. `PATCH /api/v1/employees/{employeeId}/reactivate` — gérant sur un membre désactivé par le propriétaire → 403 « Ce membre a été désactivé par le propriétaire : lui seul peut le réactiver. » ; sur un membre qu'un gérant a désactivé → 200.
6. `POST /api/v1/invoices/{id}/cancel` `{reason}` et synchro `CANCEL_INVOICE` — un **devis** : vendeur (ses devis), gérant, propriétaire ; une **facture** : propriétaire seul (inchangé ; 403 / `REJECTED FORBIDDEN`).
7. Aucun changement de forme de réponse (V1 2.0.1 inchangée).

**À implémenter côté mobile :**
1. Historique des ventes : pour un gérant ou un lecteur (`memberRole`), afficher les ventes de toute la boutique avec un filtre « Vendeur » ; pour un vendeur, ses ventes (comportement actuel). Toujours envoyer `storeId` = sa boutique.
2. Inventaire : bouton « Annuler l'inventaire » visible pour le gérant sur toute session en cours de sa boutique ; pour un vendeur, seulement sur ses sessions.
3. Produits : masquer « Fusionner » pour le vendeur et le lecteur.
4. « Mon équipe » du gérant : entrée « Appareils connectés » sur la fiche d'un vendeur / lecteur (liste, déconnecter un appareil, tout déconnecter) ; sur un 403 de réactivation, afficher le message de l'API tel quel.
5. Devis : bouton « Annuler le devis » (motif obligatoire) pour le vendeur sur ses devis brouillons ; « Annuler la facture » reste réservé au propriétaire. Hors ligne, ne pas mettre en file une annulation de facture d'un non-propriétaire.
6. Afficher les messages d'erreur de l'API tels quels ; aucun libellé technique (`STORE_SALES_VIEW`, etc.) à l'écran.

**Implémentation de référence :** aucune (keevo/app n'est qu'un client de test).

**Critères d'acceptation mobile / scénarios de test :**
- Given un gérant de la boutique A et deux vendeurs ayant vendu, When il ouvre l'historique, Then il voit les ventes des deux vendeurs et peut filtrer par vendeur.
- Given un lecteur, When il ouvre l'historique, Then il voit les ventes de sa boutique (lecture seule).
- Given un vendeur, When il ouvre un produit, Then l'action « Fusionner » n'apparaît pas.
- Given un membre désactivé par le propriétaire, When le gérant tente de le réactiver, Then le message « Ce membre a été désactivé par le propriétaire : lui seul peut le réactiver. » s'affiche.
- Given un vendeur et son devis brouillon, When il l'annule avec un motif, Then le devis passe « Annulé ».
- Mise en place : `POST /api/v1/employees` avec `"role":"MANAGER"` / `"VIEWER"` / `"EMPLOYEE"` et un `username`, puis connexion par identifiant.

## Dashboard super-admin (autre dépôt)

Aucun changement de l'API super-admin dans cette story (pas de clé `d`).
