---
title: '19.14 — Owner-defined roles: custom permission sets per tenant, store restriction, evaluated by the single permission policy (REST, sync, AI tools)'
type: 'feature'
created: '2026-11-11'
status: 'done'
baseline_revision: '7e72c570120108aa82d023396e0b5a41bb82fd07'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-5b-droits-fins-gerant-lecteur.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-16-13-scope-employe-donnees-synchro.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Rights are a fixed role → permission mapping (`ModulePermissions.permissionsOf(roles)` over the manifests' `rolePermissions`, stories 16-13 / 19-5b). The owner cannot create a role (« Caissier », « Magasinier ») nor choose what it may see or do (user request 2026-11-08: « l'owner pourra créer des rôles et donner leurs visibilités »). Two gaps routed from 19-5b remain: the sync pull sends every sale of the store to an EMPLOYEE (REST shows his own), and team management by a delegate is REST-only.

**Approach:** A per-tenant custom role (public table, V78) = name + base role (MANAGER / EMPLOYEE / VIEWER, the « type de base » that keeps the baseline behaviour: selling, read-only for a viewer, V1 compatibility) + chosen permissions (from the manifests of the tenant's enabled modules, French catalogue) + optional store restriction. Assigned by the owner to a member. The access state (16.11, read per request, evicted on change) carries the member's custom role; the JWT filter puts its permission set on the authentication, and the ONE evaluation point `ModulePermissions.permissionsOf(Authentication)` returns it instead of the built-in mapping. Every existing call site (`@RequiresPermission`, `ActorPermissions`, `PurchasePriceVisibility`, `PullScope` resolution, AI tool filter, extension slots, expense/invoice sync handlers) switches to that method — no rule moves. Built-in roles stay the default when no custom role is assigned (V1 2.0.1 and RN unaffected).

## Boundaries & Constraints

**Always:**
- Effective permissions: OWNER → every declared permission (unchanged). Store member without custom role → manifest `rolePermissions` of its role (unchanged). Store member with custom role → the role's permissions ∩ declared permissions, restricted to `_VIEW` permissions when the membership role is VIEWER; EMPTY (fail closed) when the role's base differs from the membership role or the member's store is outside the role's store list.
- New permissions (manifests, French labels): `pos` `SALE_CREATE` « Créer des ventes » → MANAGER, EMPLOYEE (gates `POST /sales`, `POST /sales/{id}/validate`, sync `CREATE_SALE`); `invoicing` `DOCUMENT_STORE_VIEW` « Voir les documents de toute la boutique » → MANAGER, VIEWER (replaces `isManager() || isViewer()` in `DocumentAccess`). Built-in results identical to today.
- Catalogue `GET /api/v1/roles/permissions` (OWNER): grantable permissions of the tenant's enabled modules, grouped by module display name, French labels, flag « lecture seule ». `FINANCE_VIEW` is not grantable (finance summary is tenant-wide, not store-scoped): a request with it → 400.
- Role CRUD (OWNER only, `@PreAuthorize("hasRole('OWNER')")`, audited `ROLE_CREATED|ROLE_UPDATED|ROLE_DELETED|ROLE_ASSIGNED` with French labels): `GET /api/v1/roles` (built-in presets « Gérant », « Vendeur », « Lecteur » with their permissions + custom roles with member count, one grouped query), `POST /api/v1/roles`, `PUT /api/v1/roles/{id}` (base role immutable), `DELETE /api/v1/roles/{id}` (409 when assigned). Validation (400 `VALIDATION_FAILED`, French): name 1–60 chars, unique per tenant case-insensitive (409 `ROLE_NAME_TAKEN`), base role ∈ {MANAGER, EMPLOYEE, VIEWER}, every permission grantable and of an enabled module, VIEWER base → `_VIEW` only, store ids = existing stores of the tenant, ≤ 50 roles per tenant. Unknown id → 404 `ROLE_NOT_FOUND`.
- Assignment `PUT /api/v1/employees/{employeeId}/custom-role` `{roleId}` (OWNER only; `null` clears): target must be a store member (owner/self → 400 « Le propriétaire a déjà tous les droits. »), store inside the role's list; when the base role differs, the membership role changes through `ChangeEmployeeRoleUseCase` (anti-lockout, session revocation unchanged). Any built-in role change through the existing role endpoint clears the custom role.
- `GET /api/v1/roles/me` (any tenant member): `{customRole: {id, name} | null, permissions: [codes]}` — what the app uses to show/hide actions.
- Sales pull (routed): a restricted pull without `STORE_SALES_VIEW` gets only `store_id = :store AND employee_id = :actor` sales; the visibility marker gains `;ss=0` (and `;ds=0` without `DOCUMENT_STORE_VIEW`) so such devices re-seed once; holders keep the 16-13 marker (no re-seed for MANAGER/VIEWER).
- Team over sync (routed): `CREATE_EMPLOYEE`, `DEACTIVATE_EMPLOYEE`, `REACTIVATE_EMPLOYEE` follow the REST team rule (owner, or `TEAM_MANAGE` delegate on EMPLOYEE/VIEWER members of his store; deactivation recorded `byManager`); `REASSIGN_EMPLOYEE` stays owner-only. A delegate never manages a member whose custom role grants `TEAM_MANAGE`.
- LWW pre-check `UPDATE_EXPENSE`/`DELETE_EXPENSE`: `EXPENSE_CREATE`/`EXPENSE_DELETE` holders instead of `isManager()`.
- Cache: membership trigger (V36) already broadcasts `u:<user>`; V78 adds a trigger on `tenant_roles` broadcasting `t:<schema>`.
- Additive only for V1 2.0.1; French user messages, no codes on screen.

**Never:** no widening of stock reads beyond the member's store, no change to OWNER rights, to expenses (owner + manager, open question left as is), to the plan ceiling; delegates never assign custom roles; no security-context read in providers/repositories; no per-row queries; no Flutter/dashboard work.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected | Error |
|---|---|---|---|
| Owner creates « Caissier » EMPLOYEE base, `SALE_CREATE` + `EXPENSE_VIEW` | — | 201 | — |
| Cashier sells, lists expenses, reads documents | role assigned | 200, 200, 403 (no `INVOICE_VIEW`) | — |
| Cashier pulls | — | sales: his own; expenses rows; `purchasePriceHidden` | — |
| « Magasinier » without `SALE_CREATE` posts a sale / pushes `CREATE_SALE` | — | — | 403 / REJECTED FORBIDDEN |
| EMPLOYEE role granted `PURCHASE_PRICE_VIEW` | GET /products | real buyPrice | — |
| VIEWER base with `EXPENSE_CREATE` | POST /roles | — | 400 |
| Role restricted to store A, member of B | assign | — | 400 |
| Member reassigned to B after assignment | any check | no permission | 403 where required |
| Duplicate name (case) | POST | — | 409 ROLE_NAME_TAKEN |
| Delete assigned role | DELETE | — | 409 |
| Manager/employee calls /roles | — | — | 403 |
| Employee without custom role pulls | old device marker | reseed once, own sales | — |
| Manager delegate deactivates seller over sync | — | APPLIED, flag byManager | — |

</intent-contract>

## Code Map

- `shared/module/application/ModulePermissions.java` -- add `permissionsOf(Authentication)` + `customRoleGrant` reading; keep `permissionsOf(roles)`.
- `shared/module/CustomRoleGrant.java` (new) -- value object + `Carrier` interface implemented by `AuthDetails` (no shared.module → infrastructure dependency).
- Call sites to switch: `shared/module/infrastructure/PermissionAuthorizationManager.java:42`, `shared/infrastructure/security/ActorPermissions.java:49`, `PurchasePriceVisibility.java:53,68`, `sync/sync/adapter/in/rest/SyncController.java:260`, `shared/ai/internal/AiToolRegistryService.java:209,323`, `shared/module/adapter/in/rest/TenantExtensionsController.java:70`, `expenses/expense/adapter/in/sync/ExpenseSyncHandler.java:176`, `invoicing/invoice/adapter/in/sync/InvoiceSyncHandler.java:280`, `catalog/product/application/usecase/UpdateProductUseCase.java:148` (role-string check → actor policy).
- `shared/infrastructure/security/access/AccessState{,Loader}.java` -- LEFT JOIN `public.tenant_roles` on `m.custom_role_id`; `JwtAuthFilter.java:314-320` -- compute the grant (base match, store list).
- `shared/infrastructure/security/AuthDetails.java` -- `customRoleId`, `customRoleName`, `customPermissions`.
- `db/migration/V78__tenant_custom_roles.sql` (new).
- `identity/role/**` (new, hexagonal like `identity/appversion`): model `CustomRole`, ports, `CustomRoleService`, `AssignCustomRoleService`, JDBC store, `RoleController`, `EmployeeCustomRoleController`.
- `shared/module/application/PermissionCatalog.java` (new) -- French labels, grantable flag, grouped view.
- `identity/employee/application/service/ChangeEmployeeRoleService.java` -- clear custom role; `identity/role/domain/port/out/CustomRoleStore.assign(…, null)` -- clears the custom role.
- `identity/employee/adapter/in/rest/EmployeeTeamScope.java` -- implements new `identity/api/TeamManagementPolicy`; refuses targets holding `TEAM_MANAGE` via custom role; `EmployeeRoleResolver` + `EmployeeResponseDto` -- additive `customRoleId`, `customRoleName`.
- `sync/sync/application/handler/EmployeeSyncHandler.java`, `identity/api/EmployeeCommandPort.java`, `identity/employee/adapter/in/api/EmployeeCommandAdapter.java` -- team rule over sync.
- `sync/sync/application/provider/SaleDeltaProvider.java`, `DeltaPageQuery.java`, `SyncPushService.java:774` (marker).
- `invoicing/invoice/application/usecase/DocumentAccess.java:118` -- `DOCUMENT_STORE_VIEW`.
- `commerce/sale/adapter/in/rest/SaleController.java`, `PendingSaleController.java:64`, `sync/sync/application/handler/SaleSyncHandler.java` -- `SALE_CREATE`.
- `sync/sync/application/strategy/LastWriteWinsStrategy.java:126` -- expense permissions.
- `resources/keevo/modules/{pos,invoicing}/module.json`; `shared/infrastructure/export/AuditLabels.java`; `shared/domain/exception/ErrorCode.java` + `ErrorMessages`.

## Tasks & Acceptance

**Execution:**
- V78 + access state + filter + grant + `ModulePermissions.permissionsOf(Authentication)` + call-site switch -- the single evaluation point.
- Manifests + `PermissionCatalog` + labels pinned by a test (every declared permission labelled).
- `identity/role` module: CRUD, assignment, `/roles/me`, audit, errors.
- Role-change clearing; employee DTO enrichment (one set query).
- Sales pull filter + marker; team rule on sync; `DOCUMENT_STORE_VIEW`; `SALE_CREATE`; LWW expense check.
- Tests: unit (ModulePermissions grant, catalogue, role service validation, assignment, sale provider SQL, marker, sync employee handler, sale handler), full-context `CustomRolesFullContextTest` (Caissier/Magasinier REST + sync matrix, store restriction, built-in unchanged), existing suites green.
- Docs: `docs/entitlements/endpoints.md`, `docs/ai/tools.md` (if changed), epics 19.14 ticks, sprint-status, Mobile section.

**Acceptance Criteria:**
- Given the interim AC (owner creates « Caissier » with « Créer des ventes » and « Voir les dépenses »), when an employee with that role signs in, then he sells and reads expenses on REST and sync, and is refused documents, purchase prices and merges.
- Given no custom role anywhere, when the existing role-matrix suites run (19.5, 19.5b, 16-13, 20-10, 20-14), then all pass unchanged.
- Given the full suite run package by package (shared, identity, sync, commerce, invoicing, catalog, inventory, expenses, architecture, admin), then all green.

## Spec Change Log

## Review Triage Log

### 2026-11-11 — Review pass
- verdicts: 41 findings — high 1, medium 9, low 25, false 6, maybe-false 0
- findings (design layer):
  - `[medium]` `[patch]` own-sales pull has no index matching `(store_id, employee_id) + keyset updated_at` — every employee device re-seeds once on deploy through it — pos module `V3__sales_store_employee_sync_index.sql` `(store_id, employee_id, updated_at, CAST(id AS text))`, javadoc corrected.
  - `[low]` `[patch]` `enabledModules` recomputed per requested permission — read once per request (`PermissionCatalog.isGrantable(Set, code)`), codes deduplicated before checking.
  - `[low]` `[reject]` store names fetched up to three times per update — rare owner action, ≤ 50 ids, one keyed query each.
  - `[low]` `[reject]` custom permission set rebuilt per check — ≤ 15 entries, O(k) without query; caching adds state for no measurable gain.
  - `[low]` `[reject]` redundant tenant eviction (explicit + trigger) — keeps same-instance eviction synchronous (the trigger NOTIFY is asynchronous); rare owner action.
  - `[low]` `[reject]` three keyed reads per delegated team operation over sync — scales with the batch, no loop over the team (accepted by the reviewer).
- findings (edge-case layer):
  - `[medium]` `[patch]` sync `VALIDATE_SALE` bypassed `SALE_CREATE` while REST validate requires it — `ValidateSaleSyncHandler` now refuses an actor without it (owner unchanged).
  - `[medium]` `[patch]` `PUT /roles/{id}` without `permissions` silently removed every right — the list is now required on update (French 400); `storeIds` absent = every store, documented as full replacement in the Mobile section.
  - `[low]` `[reject]` narrowing `storeIds` leaves members outside without rights — DD2 fail closed, decided; the owner sees the role and its stores on each member.
  - `[low]` `[reject]` reassigning a member out of his role's stores — same DD2 (the role stops applying, never widens).
  - `[medium]` `[patch]` assignment not serialized with role edit/delete (FK error 500, stale store list) — `CustomRoleAssignmentService` takes the same `roles:<schema>` lock.
  - `[high]` `[patch]` `ON DELETE RESTRICT` on `custom_role_id` would block a company deletion cascading to `tenant_roles` — V78 FK is `ON DELETE SET NULL` (the API still refuses deleting an assigned role, 409).
  - `[low]` `[patch]` re-assigning the role already held was audited — returns before writing or auditing.
  - `[low]` `[patch]` `allMatch` on an empty role set returned the grant to a role-less principal — `roles.isEmpty()` fails closed.
  - `[low]` `[reject]` team rule reads the target's raw role permissions, not its effective grant — errs restrictive (a listed `TEAM_MANAGE` keeps the member out of delegates' hands).
  - `[low]` `[reject]` team screen shows the role name of a role that does not apply — the owner chose the store list; additive `applies` flag not needed for the decision.
- findings (blind layer):
  - `[medium]` `[patch]` assign/delete race — same row as the lock above.
  - `[low]` `[reject]` members silently lose rights on narrowing/reassign — DD2 (same as above).
  - `[medium]` `[patch]` PUT missing fields — same row as above.
  - `[medium]` `[patch]` role impossible to edit once a module is disabled — permissions the role already holds stay accepted on update (`held`), only new ones need an enabled module.
  - `[low]` `[patch]` delegates see other delegates in the team list then get 403 — `isVisibleTeamMember` hides members whose custom role grants `TEAM_MANAGE` (enrichment already loaded).
  - `[low]` `[reject]` audit misses the custom role cleared by a built-in role change / previous role — `EMPLOYEE_ROLE_CHANGED` is audited for a real change; the same-role clear is rare.
  - `[low]` `[reject]` no `applies` flag in `/roles/me` / team list — `/roles/me` already returns the effective (empty) permissions.
  - `[false]` `[reject]` VIEWER pending-sale validation newly refused — a VIEWER never writes: `ViewerWriteGuardFilter` already answers 403 on that POST (19.5).
  - `[low]` `[patch]` evaluator did not drop owner-reserved permissions — `permissionsOf(Authentication)` drops `FINANCE_VIEW` from a custom grant (pinned in `ActorPermissionsTest`).
  - `[low]` `[patch]` missing tests — added: owner membership 400, delegate-on-delegate (sync + REST), delegate `CREATE_EMPLOYEE` foreign store (unit), PUT without list; trigger eviction and SQL binding covered by the full-context run (real Postgres).
  - `[low]` `[patch]` spec lacked Mobile/Dashboard sections, sprint-status, endpoints.md — added at finalize.
  - `[low]` `[reject]` permission codes repeated in several classes — each pinned by `ActorPermissionsTest.callSiteConstantsAreDeclared`; module boundaries forbid a shared constant class.
  - `[low]` `[reject]` test-only constructors / `default deactivate(…, byManager)` — project convention; the single implementation overrides it.
  - `[low]` `[reject]` update always writes and evicts — rare owner action.
- findings (verification-gap layer):
  - `[medium]` `[patch]` delegate-on-delegate rule untested — `CustomRolesFullContextTest.teamOverSync` (sync REJECTED + REST 403).
  - `[medium]` `[patch]` delegate `CREATE_EMPLOYEE` over sync untested — `EmployeeSyncHandlerTest.story1914_delegate_createInAnotherStore_refusedBeforeAnyWrite`.
  - `[low]` `[patch]` purchase-price write guard with a custom role untested — `UpdateProductUseCaseTest` (custom EMPLOYEE with the checkbox writes, custom MANAGER without does not).
  - `[low]` `[patch]` `DocumentAccess` / LWW only tested with built-in roles — custom-role cases in `DocumentAccessTest.customRoleScopes` and `LastWriteWinsStrategyTest.story1914_*`.
  - `[low]` `[reject]` (other) raw vs effective team permission — same as the edge row.
- findings (intent layer, descriptive):
  - `[low]` `[reject]` AI/MCP, exports, stock reads not exercised with a custom role — they all evaluate through `ActorPermissions` / `PurchasePriceVisibility` → `permissionsOf(Authentication)`, pinned by unit tests.
  - `[low]` `[reject]` interim AC sync half only negative — sale handler holder path covered by `SaleSyncHandlerTest`; expenses pull by `ExpenseDeltaProvider` on `PullScope.holds` (same evaluator).
  - `[false]` `[reject]` « portée par boutique » as multi-store visibility (R2') — the user's own sentence fixes a member to his store's stock; DD5.
  - `[false]` `[reject]` plain EMPLOYEE devices pull fewer sales — mandated by the routed 19-5b item (REST/AI already showed his own); additive marker, one re-seed.
  - `[false]` `[reject]` expenses made grantable — the brief says « becomes a configurable permission »; built-in defaults untouched; open question reported.
  - `[false]` `[reject]` Code Map names (`RolePermissionGrant`, `clearCustomRole`) differ — naming only; the code uses `CustomRoleGrant` and `CustomRoleStore.assign(…, null)`.
  - `[false]` `[reject]` intersection with declared, not enabled modules — a disabled module's endpoints and pulls are already gated by entitlements; the permission alone grants nothing.

### 2026-11-11 — Review pass (Reprise 11/11/2026: expenses owner-only, finance grantable, invitations after 2 refusals)
- verdicts: 37 findings — high 0, medium 5, low 26, false 6, maybe-false 0
- findings (design layer):
  - `[low]` `[reject]` `;ex=0` re-seeds every member device once, not only managers' — accepted and documented (decision R2): the refonte is not live, every member device re-seeds at go-live anyway (19-14 `;ss/;ds`); gating on the tenant's module would add an entitlement read to the marker for a one-off.
  - `[low]` `[reject]` visibility marker built by hand (`ss`/`ds`/`ex`) instead of from the providers' `requiredPermission()` — a format change re-seeds again and the pp/ss/ds parts are not provider permissions; constants pinned by `ActorPermissionsTest`.
  - `[false]` `[reject]` stale `FINANCE_VIEW` in persisted roles now honoured — the old `isGrantable` refused it at write time, no row can carry it.
- findings (intent layer, descriptive):
  - `[false]` `[reject]` spec / epic / 20-10 updates absent from the diff — they are in the same change set (`_bmad-output` files, outside the backend diff given to the reviewers).
  - `[low]` `[reject]` re-seed wider than « the manager » — same as the design row (R2).
  - `[low]` `[reject]` V1 2.0.1 never exercised as V1 — V1 has no expense screen and calls no expense route; the REST 403 is the generic `@RequiresPermission` path (French message asserted in `expensesOwnerOnlyByDefault`).
  - `[low]` `[reject]` device purge not server-side — the protocol has no purge command: the re-seed marker plus the Mobile instruction (point 8) is the documented path, as for 16-13/19-14 sales.
  - `[low]` `[patch]` cumulative vs per-cycle refusal counting not pinned beyond the 2nd refusal — `thirdRefusal_waitsAgain` added; rule written in spec-19-6b (count never decays, acceptance resets).
  - `[low]` `[reject]` backfill of existing DECLINED rows to 1 not in the intent — direct application of the decision (one known refusal), decision R1.
  - `[medium]` `[patch]` member `financial_result` (AI/MCP) not tested — see verification row.
  - `[false]` `[reject]` reporting AI tools (`revenue_summary`, `activity_overview`, `store_performance`) not scoped — they are not the finance summary nor gated by `FINANCE_VIEW` (reporting permissions, owner-only, unchanged).
- findings (verification-gap layer):
  - `[medium]` `[patch]` `financial_result` as a member holding « Voir la situation financière » never run — `AiToolsFullContextTest.financeGate` now runs it with a custom grant: listed, no `byStore`, another store's filter ignored, a sale in another store leaves the figures unchanged.
  - `[low]` `[reject]` V82 backfill untested — one-shot `UPDATE` on a small table, no migration-data harness; checked live (V82 applied on `keevo_dev_t1114r`).
  - `[medium]` `[patch]` `upsert(... in2Days)` asserted `false` on a live PENDING renewed after 24 h — expectation corrected to `true` (renewed, message due).
  - `[low]` `[patch]` `storeId` parameter of `financial_result` still said « absent = toutes les boutiques » — now « pour le propriétaire … ignoré pour un membre autorisé ».
  - `[low]` `[patch]` marker comment/test comment said only the manager re-seeds — comments corrected (every member device re-seeds once).
- findings (blind layer):
  - `[low]` `[reject]` V82 lifts running 30-day waits — that is the user decision applied to existing data (one known refusal), R1.
  - `[low]` `[patch]` refusal count never decays, third refusal untested — test added and rule documented (privacy-protective: someone who refused twice is not pestered again).
  - `[false]` `[reject]` migration number gap V79 → V82 — V82 is the number reserved for this story by the orchestrator (V80/V81 reserved by parallel stories).
  - `[low]` `[reject]` `FINANCE_VIEW` alone shows the store's expense totals and revenue without `EXPENSE_VIEW`/`STORE_SALES_VIEW` — aggregates are the financial result the owner chose to show; decision R4 (catalogue label « Voir la situation financière »).
  - `[low]` `[patch]` `;ex=0` comment narrower than the effect — same patch as above.
  - `[medium]` `[patch]` positive member paths untested (AI) — AI test added; member without store already `FinanceSummaryServiceTest.member` (`FinanceAccess.member(null)` → empty).
  - `[low]` `[reject]` EMPLOYEE-based role with expense write rights untested — `ExpenseAccess.member(store)` does not depend on the base role; the MANAGER-base fixture exercises the same path.
  - `[low]` `[reject]` re-invitation within 24 h after a first refusal sends no message — the existing one-message-per-day anti-spam rule the user kept; documented in 19-6b.
  - `[false]` `[reject]` owners not told that managers lose expenses — the refonte is not live and V1 2.0.1 has no expenses: no manager uses them in production.
  - `[low]` `[reject]` migration comment repeats 20 / 30 / 2 — SQL comments cannot reference Java constants; values match the code.
  - `[low]` `[patch]` weak assertions — French message asserted on the store-limited assignment, return value asserted on the last upsert.
  - `[low]` `[reject]` `grantExpenses` fixture writes roles by SQL — valid V78 row; the API path is covered by `CustomRolesFullContextTest`.
  - `[false]` `[reject]` renewal of a DECLINED row keeps `responded_at` — the upsert sets `responded_at = NULL` on renewal (unchanged SQL).
  - `[low]` `[patch]` broken javadoc sentence in `MembershipInvitationService` — reflowed.
- findings (edge-case layer):
  - `[medium]` `[patch]` in2Days assertion — same row as verification (corrected).
  - `[low]` `[reject]` 90-day retention purge resets the count — retention is a privacy rule (19-11c); 90 days > the 30-day wait; documented in 19-6b.
  - `[low]` `[reject]` count never decays after the wait — decision (privacy-protective), tested and documented.
  - `[low]` `[reject]` tenants without expenses re-seed too — same as the design row (R2).
  - `[low]` `[patch]` `storeId` parameter text — same patch.
  - `[low]` `[patch]` Mobile section had no expense purge and said managers/viewers are not re-seeded — « Reprise 11/11/2026 — à faire côté mobile » added (points 7–9, scenarios).
  - `[low]` `[reject]` « one refusal is free » vs third refusal — same decision row.

## Design Notes

**Patterns.** Policy + Strategy of grant source: the authentication carries an optional custom grant (value object resolved once per request from the cached access state); `ModulePermissions.permissionsOf(Authentication)` is the single evaluator choosing the grant source (owner → all, custom → custom ∩ declared ∩ viewer filter, else manifest mapping). Repository (JDBC) for roles in `public`; Command services for create/update/delete/assign, serialized per tenant by `TransactionLockPort` (name uniqueness, role cap). Team policy exposed as an `identity.api` port so REST and sync share one rule.

**Complexity.** Per request: zero extra query (custom role joined in the existing access-state statement, cached ≤ 30 s, evicted by triggers). Evaluation O(k), k ≤ 20 permissions. Role list: one query (≤ 50 roles, member count by the partial index `ix_utm_custom_role`). Assignment: keyed reads (employee, membership, role) + one UPDATE. Employee list: +1 set query (custom role names for the page's users). Sales pull: own sales served by `idx_sales_store_employee_occurred (store_id, employee_id, …)`, keyset on `updated_at` over that member's rows only. Store-id validation: one `id = ANY(?)` query. Index: unique `(tenant_id, lower(name))`, partial `user_tenant_memberships(custom_role_id) WHERE NOT NULL`.

**Decisions taken by default:** DD1 a custom role has an immutable base role (Gérant / Employé / Lecteur) that keeps the non-permission baseline (selling unless `SALE_CREATE` is removed, viewer read-only, team targets, V1 legacy guard). DD2 out-of-store or base-mismatch → no permission (fail closed), never the built-in defaults. DD3 assignment and role editing are owner-only (no privilege escalation by a delegate). DD4 `FINANCE_VIEW` not grantable until finance is store-scoped. DD5 documents scope « toutes les boutiques » for a member not offered (own / store only). DD6 roles are not plan-gated (core). DD7 expenses unchanged (owner + manager by default; who sees expenses is now configurable per custom role) — open product question reported, not decided.

## Verification

**Commands:**
- `scratchpad/wt-pkgs.sh <worktree>/keevo/backend t1914 <pkg>` for shared, identity, sync, commerce, invoicing, catalog, inventory, expenses, architecture, admin -- expected: 0 failures (regenerate `endpoints.md` with `-Dkeevo.entitlements.regenerate=true`).

**Manual checks:**
- Live on port 4614, DB `keevo_dev_t1914` (WhatsApp noop, FCM off, then dropped): owner creates « Caissier », assigns it to a seller; seller sells 200, expenses 200, documents 403, `/roles/me` lists permissions; owner removes `SALE_CREATE` → sale 403; sync pull of a plain employee returns only his sales.


## Auto Run Result

Status: done

**Summary.** Owner-defined roles: `public.tenant_roles` (V78: name, immutable base role MANAGER/EMPLOYEE/VIEWER, permissions chosen among the manifests of the company's enabled modules, optional store list) assigned through `user_tenant_memberships.custom_role_id`. The access state (16.11) joins the role in its single statement; `JwtAuthFilter` puts a `CustomRoleGrant` on `AuthDetails` (empty — fail closed — when the base differs or the member's store is outside the list); THE evaluator `ModulePermissions.permissionsOf(Authentication)` returns custom ∩ declared, minus owner-reserved, read-only for a viewer, else the built-in manifest mapping. Every call site (`@RequiresPermission`, `ActorPermissions`, `PurchasePriceVisibility`, sync pull scope, AI tool filter, extension slots, expense/invoice sync handlers, product write guard) now asks it. New default-preserving permissions `SALE_CREATE` (REST sale + validate, sync `CREATE_SALE`/`VALIDATE_SALE`) and `DOCUMENT_STORE_VIEW` (`DocumentAccess`). API: `/api/v1/roles` (catalogue, list with presets, CRUD, `/me`), `PUT /api/v1/employees/{id}/custom-role`, additive `customRoleId`/`customRoleName` on employees. Routed 19-5b items: own-sales pull without `STORE_SALES_VIEW` (marker `;ss=0`/`;ds=0` → one re-seed for such devices only; pos V3 index) and team management over sync through the `TeamManagementPolicy` port (delegate deactivations recorded `byManager`, reassignment owner-only, a delegate never manages another delegate).

**Files.** New: V78 migration, pos module V3 index, `shared/module/CustomRoleGrant`, `shared/module/application/PermissionCatalog`, `identity/api/TeamManagementPolicy`, `identity/employee/adapter/in/api/TeamManagementPolicyAdapter`, `identity/role/**` (model, store port, JDBC store, 2 services, 2 controllers), tests `CustomRolesFullContextTest`, `PermissionCatalogTest`, `CustomRoleServiceTest`, `CustomRoleAssignmentServiceTest`, `CustomRoleGrantResolutionTest`. Changed: `ModulePermissions`, `ActorPermissions`, `PurchasePriceVisibility`, `AuthDetails`, `JwtAuthFilter`, `AccessState(+Loader)`, `PermissionAuthorizationManager`, `TenantExtensionsController`, `AiToolRegistryService`, `SyncController`, `SyncPushService`, `SaleDeltaProvider`, `SaleSyncHandler`, `ValidateSaleSyncHandler`, `EmployeeSyncHandler`, `LastWriteWinsStrategy`, `ExpenseSyncHandler`, `InvoiceSyncHandler`, `DocumentAccess`, `UpdateProductUseCase`, `SaleController`, `PendingSaleController`, `EmployeeTeamScope`, `EmployeeRoleResolver`, `EmployeeController`, `EmployeeResponseDto`, `ChangeEmployeeRoleService`, `EmployeeCommandPort(+Adapter)`, `ErrorCode`, `ErrorMessages`, `GlobalExceptionHandler`, `AuditLabels`, manifests `pos`/`invoicing`; docs `endpoints.md`; tenant shape reference; tests adjusted.

**Review.** 41 findings over 5 layers: high 1, medium 9, low 25, false 6. Patched: the high (FK `RESTRICT` would block company deletion → `SET NULL`), 6 distinct medium (VALIDATE_SALE bypass, PUT without rights list, assign/delete race, role uneditable after a module is disabled, own-sales index, missing tests for delegate rules) and 10 low. Deferred: none. Rejected with reasons in the triage log (DD2 fail closed on narrowing/reassign, cosmetic perf, naming).

**Follow-up review: recommended (true)** — one high and several medium patched in this pass; the unverified risk is the cross-instance cache eviction of a role change (trigger `t:<schema>` NOTIFY) — the tests evict locally by hand, a multi-instance check was not run.

**Verification.** Chunked Maven runs (tag t1914), all green after fixes: identity 964, sync 700 (then `sync/sync/application` 553 after the last patch), commerce 273, invoicing 475, catalog 373, expenses 41, architecture 171 (incl. `CustomRolesFullContextTest` 7), admin 711, shared 1397 → 4 expected assertion updates (manifests, role mapping, tenant shape reference) then `shared/module` 558 + `shared/infrastructure/persistence` 125 green; `endpoints.md` regenerated. Live (port 4614, DB `keevo_dev_t1914`, WhatsApp noop, FCM off, dropped after): real registration, member created and logged in by username; catalogue grouped by module in French (no finance); « Caissier » created and assigned; `/roles/me` = EXPENSE_VIEW + SALE_CREATE; expenses 200, invoices 403; duplicate name 409 `ROLE_NAME_TAKEN`; delete assigned 409 `ROLE_IN_USE`; employees list shows « Caissier »; purchase price hidden then shown right after ticking « Voir les prix d'achat » (no re-login); sale allowed (422 stock, same as the owner) then 403 after unticking « Créer des ventes »; PUT without rights list → French 400; member on `/roles` → 403; taking the role back restores the built-in rights.

**Residual risks.** Expenses: behaviour unchanged (built-in OWNER + MANAGER; a custom role may tick them) — the « owner only vs owner + manager » question stays open for the user (sprint-status says « propriétaire seul », epics and `fix(20-10)` say owner + manager). Every EMPLOYEE device re-seeds once at deploy (own sales only). Members whose store leaves their role's store list lose every right until the owner reassigns a role (DD2). V78 must be deployed with V72–V77 (out-of-order numbering across parallel stories).

## Reprise 11/11/2026 — user decisions (expenses, finance, invitations)

**Intent (explicit user decisions, not defaults).**
1. **Expenses = owner only by default.** The built-in MANAGER no longer holds `EXPENSE_VIEW/CREATE/DELETE` (manifest `rolePermissions`, hence REST, sync push, pull, AI/MCP `list_expenses`); a custom role may tick them for a manager (store scope of 20.10 unchanged). V1 2.0.1 compatibility: a V1 manager gets a clean French 403 (V1 has no expense screen anyway); sync stops sending expenses to a member without `EXPENSE_VIEW` and the device is re-seeded once (visibility marker `;ex=0`) so it purges its local expenses.
2. **Invitations (19-6b): the 30-day wait applies only after 2 refusals** of the same person for the same company (`decline_count`, public migration V82; an acceptance resets it; a cancellation does not count). After one refusal the company may invite again at once, within the existing anti-spam limits (20 invitations / 24 h per company, one message / 24 h per person).
3. **« Voir la situation financière » (`FINANCE_VIEW`) becomes grantable** to a custom role (DD4 reversed — the owner's choice). A member holding it reads `GET /api/v1/finance/summary` and the AI tool `financial_result` for **his own store only** (DD5: no « all stores » reach for a member): totals, comparison, series and categories computed on that store, no `byStore`, a `storeId=` filter ignored (same convention as the expenses list); a member without store gets an empty summary. The owner sees everything as before.

**Code Map.** `resources/keevo/modules/expenses/module.json` (MANAGER `[]`); `expenses/**` javadoc + `ExpenseSyncHandler.ROLE_REFUSED` « Les dépenses sont réservées au propriétaire : demandez-lui le droit de les gérer. »; `keevo/contracts/expense-sync-payloads.json`; `sync/.../SyncPushService.ScopeKey.visibilityMarker` (`;ex=0`, constant `EXPENSE_VIEW_PERMISSION` pinned by `ActorPermissionsTest`); `shared/module/application/PermissionCatalog` (owner-reserved set removed) + `ModulePermissions.permissionsOf(Authentication)` (no owner-reserved filter); `finance/**` javadoc, `FinanceMcpTools` description; `identity/employee/domain/model/MembershipInvitation.DECLINES_BEFORE_COOLDOWN = 2`, `JdbcMembershipInvitationRepository` (upsert guard `decline_count < 2 OR responded_at < cooldown`, `markResponded` counts / resets), `db/migration/V82__membership_invitations_decline_count.sql`.

**Design Notes.** Patterns unchanged: manifest mapping as the single default-rights source + the single evaluator `ModulePermissions.permissionsOf(Authentication)` (no call site touched for expenses and finance); the finance store pin stays in `FinanceAccess`/`FinanceSummaryService` (Policy value object). Invitation state machine: the refusal counter is one more SQL guard in the existing conditional upsert (no read-then-write). Complexity: no new query anywhere — the marker is O(1), the upsert and `markResponded` stay single keyed statements on the unique `(tenant_id, user_id)` / primary key; V82 is a metadata-only `ADD COLUMN … DEFAULT 0` plus one `UPDATE` of the (small) DECLINED subset. The finance summary of a member is the existing store-scoped path (`storeScopedTaxed` + `store_id = ?`, expenses `store_id = ?`): same 2–3 grouped queries.

**Decisions taken by default (this reprise).** R1 an acceptance resets the refusal count, a cancellation leaves it unchanged; existing DECLINED rows count as one refusal (they become re-invitable). R2 the device of every member without `EXPENSE_VIEW` (built-in MANAGER, EMPLOYEE, VIEWER) is re-seeded once at deploy (marker `;ex=0`) — before go-live, so no extra cost in production. R3 a member's `storeId=` filter on the finance summary is ignored (pinned to his store), as for expenses and clients, not refused. R4 a member granted « Voir la situation financière » without « Voir les dépenses » still sees his store's expense totals inside the summary (they are part of the financial result the owner chose to show). R5 once a person has refused twice, each later refusal starts a new 30-day wait (no decay; privacy-protective); the 90-day retention (19-11c) deleting the row resets everything.

**Reprise result.** Review: 5 layers, 37 findings (high 0, medium 5, low 26, false 6) — patched: the wrong in2Days expectation, the untested member `financial_result` (AI test added), the third-refusal case, the `storeId` parameter text, comments on the fleet-wide re-seed, the Mobile section, weak assertions, a broken javadoc; nothing deferred; the rest rejected with reasons (triage log above). Follow-up review: `false` (no high patched; the medium ones are test/documentation fixes, not behaviour).

**Reprise verification.** Chunked Maven runs (tag t1114r), all green: expenses 41, finance 21, identity 968, architecture 173 (1 skipped, pre-existing), sync 701, shared 1 397 (shared/module 558, shared/ai 27), acceptance 15, assistant 55, admin 721; `docs/ai/tools.md` regenerated (`financial_result` texts), `endpoints.md` unchanged (no endpoint change). Live (port 4682, DB `keevo_dev_t1114r`, WhatsApp noop, FCM off, then dropped): V82 column present; built-in manager — `/roles/me` without `EXPENSE_*`, `GET /expenses` 403 « Vous n'avez pas les droits nécessaires », push `CREATE_EXPENSE` REJECTED FORBIDDEN « Les dépenses sont réservées au propriétaire : demandez-lui le droit de les gérer. », pull 0 expense; catalogue lists « Voir la situation financière »; role « Gérant comptable » (FINANCE_VIEW + EXPENSE_VIEW) assigned → summary of his store only (4 000 of expenses, `byStore` empty), unchanged after an expense of 3 000 in a second store and with `?storeId=` of that store, owner sees 7 000 over 2 stores, expenses 200; invitations — invite 202, refusal 1 → re-invite visible (1 pending), refusal 2 → re-invite silent (0 pending), row `DECLINED|2`.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit `ad26989f`). Conventions : spec-16-11 ; synchro : spec-16-10. **Clé de suivi mobile :** `m19-14-roles-permissions-personnalises`.

### Ce qui est fait côté API

- Le propriétaire crée des rôles (« Caissier », « Magasinier »…) : un nom, un **type de base** (Gérant, Employé ou Lecteur — non modifiable ensuite), des **droits** cochés parmi ceux des modules de son entreprise (libellés français), et éventuellement les **boutiques** où le rôle s'applique.
- Il attribue un rôle à un membre ; tous les contrôles (REST, synchro, outils IA) lisent les droits du rôle. Sans rôle personnalisé, rien ne change (Gérant / Employé / Lecteur par défaut).
- Nouveaux droits par défaut, identiques au comportement actuel : « Créer des ventes » (gérant, employé) et « Voir les documents de toute la boutique » (gérant, lecteur).
- Synchro : un membre **sans** « Voir les ventes de toute la boutique » (l'employé par défaut) ne reçoit plus que **ses** ventes ; son appareil est re-semé une fois (`reseed: true`). Le gérant et le lecteur ne sont pas re-semés.
- Synchro : un gérant (ou un rôle avec « Gérer l'équipe de sa boutique ») peut pousser `CREATE_EMPLOYEE` (dans sa boutique), `DEACTIVATE_EMPLOYEE`, `REACTIVATE_EMPLOYEE` pour les employés/lecteurs de sa boutique ; `REASSIGN_EMPLOYEE` reste au propriétaire.

### À utiliser

Toutes les réponses sont enveloppées `{"success", "data"}` ; erreurs `{"domainCode", "error"}` (message français à afficher tel quel). Code : `identity/role/adapter/in/rest/RoleController.java`, `EmployeeCustomRoleController.java`.

1. `GET /api/v1/roles/me` (tout membre) → `{customRole: {id, name} | null, permissions: ["EXPENSE_VIEW", "SALE_CREATE", …]}` — **source des droits de l'écran** : afficher / masquer les actions selon `permissions` (codes jamais affichés). Le propriétaire reçoit toutes les permissions.
2. `GET /api/v1/roles/permissions` (propriétaire) → `[{module, moduleLabel, permissions: [{code, label, readOnly}]}]` : l'éditeur affiche `moduleLabel` et `label` ; un rôle « Lecteur » ne peut cocher que `readOnly: true`.
3. `GET /api/v1/roles` (propriétaire) → `{builtIn: [{baseRole, name, permissions: [{code, label}]}], custom: [{id, name, baseRole, baseRoleLabel, permissions: [{code, label}], storeIds | null, memberCount}]}` — `builtIn` sert de modèle (« copier les droits du Gérant »).
4. `POST /api/v1/roles` `{name, baseRole: "MANAGER"|"EMPLOYEE"|"VIEWER", permissions: [codes], storeIds: null | [ids]}` → **201** rôle. `PUT /api/v1/roles/{id}` : **remplacement complet** — `name` et `permissions` obligatoires (liste complète, même vide), `storeIds` absent ou `null` = toutes les boutiques ; `baseRole` ne change pas. `DELETE /api/v1/roles/{id}`.
5. `PUT /api/v1/employees/{employeeId}/custom-role` `{roleId}` (propriétaire) ; `{roleId: null}` ou corps vide = retour au rôle par défaut. Si le type de base diffère du rôle actuel du membre, son rôle change (ses sessions sont fermées comme pour un changement de rôle). Choisir un rôle intégré via `PATCH /api/v1/employees/{id}/role` retire le rôle personnalisé.
6. `GET /api/v1/employees` : champs additifs `customRoleId`, `customRoleName` (null sans rôle personnalisé).
7. Erreurs : **400/422** `VALIDATION_FAILED` avec messages « Le nom du rôle doit faire entre 1 et 60 caractères. », « Choisissez le type de base du rôle : Gérant, Employé ou Lecteur. », « Le type de base d'un rôle ne peut pas être modifié : créez un nouveau rôle. », « Un des droits choisis n'est pas disponible pour votre entreprise. », « Un rôle de type Lecteur ne peut recevoir que des droits de consultation. », « Une des boutiques choisies est introuvable. », « Indiquez les droits du rôle (la liste complète, même vide), et ses boutiques s'il en a. », « Ce rôle n'est pas valable dans la boutique de ce membre. », « Le propriétaire a déjà tous les droits. », « Votre entreprise a atteint le nombre maximal de rôles (50). » ; **409** `ROLE_NAME_TAKEN` « Un autre rôle porte déjà ce nom », `ROLE_IN_USE` « Ce rôle est attribué à des membres… » ; **404** `ROLE_NOT_FOUND` « Rôle introuvable » ; **403** `FORBIDDEN` pour un non-propriétaire. Synchro : `CREATE_SALE` / `VALIDATE_SALE` sans « Créer des ventes » → `REJECTED` `FORBIDDEN` ; `POST /api/v1/sales` → 403.

### À implémenter côté mobile

1. **Droits de l'écran** : au démarrage et au renouvellement de session, lire `GET /roles/me`, le mettre en cache avec le profil ; masquer Vendre, Dépenses, Documents, Prix d'achat, Fusionner, Équipe, Inventaires selon les codes (`SALE_CREATE`, `EXPENSE_VIEW`/`EXPENSE_CREATE`/`EXPENSE_DELETE`, `INVOICE_*`, `PURCHASE_PRICE_VIEW`, `PRODUCT_MERGE`, `TEAM_MANAGE`, `INVENTORY_SESSION_MANAGE`, `STORE_SALES_VIEW`, `DOCUMENT_STORE_VIEW`).
2. **Hors ligne** : ne jamais mettre en file une vente si `SALE_CREATE` manque (elle serait refusée et mise en quarantaine).
3. **Paramètres › Équipe › Rôles** (propriétaire) : liste (rôles intégrés en modèles + rôles créés, nombre de membres), éditeur (nom, type de base, droits groupés par module avec cases à cocher, boutiques « Toutes » ou sélection), suppression refusée si attribué (afficher le message de l'API).
4. **Fiche membre** : sélecteur de rôle = Gérant / Employé / Lecteur / rôles créés ; afficher `customRoleName` sinon le libellé du rôle intégré.
5. **Synchro** : un employé sans « Voir les ventes de toute la boutique » reçoit un re-seed unique : purger ses ventes locales des autres vendeurs (règle de purge de spec-16-13, étendue aux ventes d'autres vendeurs).
6. Aucun code technique à l'écran (seulement `label`, `moduleLabel`, `baseRoleLabel`).

### Reprise 11/11/2026 — à faire côté mobile (API livrée, commit `07b358a4`)

Ce qui change côté API (décisions de l'utilisateur) :
- **Dépenses : propriétaire seul par défaut.** Le gérant intégré ne reçoit plus `EXPENSE_VIEW/CREATE/DELETE` dans `GET /roles/me` ; `GET/POST/PUT/DELETE /api/v1/expenses` → **403** `FORBIDDEN` « Vous n'avez pas les droits nécessaires » ; push `CREATE_/UPDATE_/DELETE_EXPENSE` → `REJECTED` `FORBIDDEN`, `conflictData.message` « Les dépenses sont réservées au propriétaire : demandez-lui le droit de les gérer. » ; le pull ne contient plus de dépenses. Un rôle personnalisé peut cocher « Voir les dépenses », « Saisir et modifier des dépenses », « Supprimer des dépenses » (sa boutique seulement).
- **Synchro :** le marqueur de visibilité gagne `;ex=0` pour tout membre sans « Voir les dépenses » → **chaque appareil de membre (gérant, employé, lecteur) reçoit une fois `reseed: true`** au déploiement de la refonte (le paragraphe « Le gérant et le lecteur ne sont pas re-semés » ci-dessus ne vaut plus).
- **« Voir la situation financière »** (`FINANCE_VIEW`) apparaît dans `GET /roles/permissions` (module « Finance ») et peut être coché ; le membre qui l'a voit `GET /api/v1/finance/summary` **de sa boutique seulement** (pas de `byStore`, le paramètre `storeId` est ignoré) ; sans boutique : résumé vide.

À implémenter (suite de la liste ci-dessus) :
7. Masquer l'écran Dépenses (menu, raccourcis, file hors ligne) quand `GET /roles/me` ne contient pas `EXPENSE_VIEW` — y compris pour un gérant ; ne jamais mettre en file une opération de dépense sans `EXPENSE_CREATE` / `EXPENSE_DELETE`.
8. Sur un `reseed: true`, si `EXPENSE_VIEW` est absent : **purger toutes les dépenses locales** (et la file d'opérations de dépenses en attente, en affichant « Les dépenses sont réservées au propriétaire »).
9. Afficher l'écran Finance quand `permissions` contient `FINANCE_VIEW` ; pour un membre, titre « Situation de ma boutique », pas de sélecteur de boutique ni de tableau par boutique.

Scénarios :
- Given un gérant sans rôle personnalisé, When il ouvre l'app après le déploiement, Then l'appareil est re-semé, ses dépenses locales disparaissent et l'écran Dépenses est masqué.
- Given le propriétaire coche « Voir la situation financière » dans le rôle « Gérant comptable » d'un gérant de la boutique B, When le gérant ouvre Finance, Then il voit les chiffres de B seulement.

### Implémentation de référence

Aucune (keevo/app n'est qu'un client de test).

### Critères d'acceptation mobile / scénarios de test

- Given le propriétaire crée « Caissier » (type Employé, « Créer des ventes » + « Voir les dépenses ») et l'attribue à un vendeur, When le vendeur ouvre l'app, Then il voit Vendre et Dépenses, pas Documents ni prix d'achat.
- Given « Magasinier » sans « Créer des ventes », When le membre ouvre la caisse, Then l'action Vendre est masquée et aucune vente hors ligne n'est mise en file.
- Given un rôle attribué, When le propriétaire tente de le supprimer, Then « Ce rôle est attribué à des membres… » s'affiche.
- Mise en place curl : `POST /api/v1/roles` (jeton propriétaire) puis `PUT /api/v1/employees/{id}/custom-role` puis `GET /api/v1/roles/me` (jeton du membre).

## Dashboard super-admin (autre dépôt)

Aucun changement de l'API super-admin (pas de clé `d`).
