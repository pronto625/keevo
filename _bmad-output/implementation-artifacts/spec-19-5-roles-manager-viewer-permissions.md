---
title: '19.5 — Membership roles MANAGER and VIEWER, permissions per role, VIEWER never writes'
type: 'feature'
created: '2026-11-05'
status: 'done'
baseline_revision: '49eca1b6771fb504f15b6bc9bce8c6e96b1396c2'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
warnings: [oversized]
deferred:
  - summary: >-
      MANAGER/VIEWER read scope and manager team extras need product decisions.
    evidence: |-
      Store-wide sales for MANAGER, POST reads for VIEWER, device sessions, owner-deactivated reactivation, plan cap counting. Routed to new key 19-5b.
    severity: medium
  - summary: >-
      VIEWER on the V1 app loses offline sales (REJECTED is final).
    evidence: |-
      V1 shows VIEWER as EMPLOYEE. Routed to 19.10 (minimum version or refuse V1 login for MANAGER/VIEWER).
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Bref has admin/seller/viewer members; Keevo knows only OWNER and EMPLOYEE, permissions are declared for employees only (invoicing) and most rules are role-string checks, so a Bref « admin » (store manager) or « viewer » (read-only) cannot be represented, and a read-only member could write.

**Approach:** Add MANAGER and VIEWER as membership roles; generalise `KeevoModule.employeePermissions()` into `permissionsFor(Role)` with an explicit per-role declaration in every module manifest validated at boot; treat MANAGER/VIEWER as store-scoped members like EMPLOYEE wherever the code restricts « non-owners »; block every write of a VIEWER at one central point (HTTP and sync); let a MANAGER manage the team of his own store; keep V1 2.0.1 apps safe by showing them the V1 role vocabulary only.

## Boundaries & Constraints

**Always:**
- Roles: `identity/api/Role` gains `MANAGER`, `VIEWER`. Membership role strings accepted: OWNER, MANAGER, EMPLOYEE, VIEWER (public `user_tenant_memberships.role` has no CHECK — no public migration; if the tenant `users.role` CHECK (`db/modules/core/V1__core_baseline.sql:33`) is still written by any code path, a new idempotent core module script widens it; otherwise leave it). Existing memberships untouched.
- One helper decides scoping: `Role.isStoreMember()` = EMPLOYEE, MANAGER, VIEWER. Every place that today restricts with `"EMPLOYEE".equals(role)` (store pinning in `JwtAuthFilter` and `AuthDetails.storeId`, `SessionTokenIssuer` store/password-flag lookup, `UserProfileService` store fields, `CurrentActor.assignedStoreId*`, `PullScope.employee`, sale/stock/transfer/inventory/day-closure store checks, « EMPLOYEE sees only own sales », messaging listeners that notify on employee actions, reporting masks) uses it, so MANAGER and VIEWER are never less restricted than EMPLOYEE. Places that grant owner privileges (`"OWNER".equals`, `hasRole('OWNER')`, `requireOwner`) keep OWNER only, except the MANAGER team rule below. `CurrentActor.role()` returns the real role (no fallback to EMPLOYEE for MANAGER/VIEWER).
- `@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")` everywhere (≈25) becomes the four roles (reads open to MANAGER/VIEWER, writes then filtered by the VIEWER rule and the existing per-action checks).
- Permissions: `KeevoModule.permissionsFor(Role)`; each `module.json` gains `"rolePermissions": {"MANAGER":[…],"EMPLOYEE":[…],"VIEWER":[…]}` — explicit for every module (empty arrays allowed); OWNER implicitly has all declared permissions. `ModulePermissions` fails the boot when a module lacks an explicit entry for one of MANAGER/EMPLOYEE/VIEWER, when a role lists a permission its module does not declare, or when a VIEWER entry contains a write permission (any permission not ending in `_VIEW`). Invoicing: EMPLOYEE = today's set (`INVOICE_VIEW, INVOICE_CREATE, INVOICE_SEND`), MANAGER = EMPLOYEE's, VIEWER = `INVOICE_VIEW`. `employeePermissions()` removed (callers moved).
- VIEWER never writes: one `OncePerRequestFilter` (or `HandlerInterceptor`) after authentication answers 403 `FORBIDDEN` (existing code and message) to any non-GET/HEAD/OPTIONS request of a `ROLE_VIEWER`, except a self-service allow-list in one constant: `/api/v1/auth/**` (logout, change-password, refresh, select-tenant…), `/api/v1/devices/**` (push token), `/api/v1/web-push/**`, `/api/v1/notifications/**` (mark read), `/api/v1/feedback` (if present), `/api/v1/sync/push`, `/api/v1/sync/devices`. Sync push: the controller admits VIEWER; `SyncPushService` answers every operation of a VIEWER with `REJECTED`, reason `FORBIDDEN`, without calling handlers.
- MANAGER team rule: on `EmployeeController` endpoints (list, get, create, update, deactivate, reactivate, set/regenerate password, activity), a MANAGER is allowed only on employees whose store is the MANAGER's store, may create members only in that store and only with role EMPLOYEE or VIEWER, may not change roles, reassign stores or touch OWNER/MANAGER members → otherwise 403 `FORBIDDEN`. OWNER behaviour unchanged. Store-filtered list in one query (no per-row checks).
- Role change: `ChangeEmployeeRoleService` accepts OWNER, MANAGER, EMPLOYEE, VIEWER (owner only); the last-owner guard covers OWNER→any role; tokens/sessions revoked as today; `ChangeRoleRequestDto` documents the four values.
- V1 2.0.1 compatibility: every V1-visible `role` value (legacy JWT claim `role`, `LoginSessionResponse` memberships `role`, `LoginResponse.role`, `UserProfileResponse.role`, `EmployeeResponseDto.role`) shows MANAGER and VIEWER as `EMPLOYEE`; the same responses gain an additive `memberRole` field with the real role (OWNER/MANAGER/EMPLOYEE/VIEWER/SUPER_ADMIN). Consequence checked: a V1 owner editing a MANAGER sees `EMPLOYEE`, sends no role change, so no silent demotion. V1 Flutter (branch `release/2.0.x`) is not modified; its handling of `"EMPLOYEE"` is the documented reading behaviour (AC « rôle inconnu traité comme EMPLOYEE »).
- Tests: a parameterised full-context test over every non-GET endpoint from `EndpointEntitlementCoverageTest.scan` (minus public paths, `/api/v1/admin/**`, the allow-list) calling each with a VIEWER session expects 403 `FORBIDDEN`; a VIEWER sync push gets every operation `REJECTED/FORBIDDEN`; `ModulePermissions` boot-validation tests; MANAGER team rule tests; V1 role mapping tests (JWT legacy claim, profile, employees, login session).

**Never:**
- No change to OWNER or EMPLOYEE behaviour, to plans/entitlements, to SUPER_ADMIN. No per-tenant custom roles, no multi-role per membership (19.6), no Flutter change, no dashboard work, no new permission strings beyond invoicing's existing ones.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| VIEWER reads | GET products/sales/stock of his store | 200, scoped like EMPLOYEE | — |
| VIEWER writes | POST /products, PATCH /stores/{id}… | — | 403 `FORBIDDEN` |
| VIEWER self-service | POST /auth/logout, PATCH /notifications/{id}/read | 2xx | — |
| VIEWER sync push | 3 ops | each `REJECTED`, reason `FORBIDDEN` | — |
| MANAGER lists team | GET /employees | only his store's members | — |
| MANAGER creates EMPLOYEE in his store | | 201 | — |
| MANAGER creates in another store / creates MANAGER / changes a role | | — | 403 `FORBIDDEN` |
| Owner sets role VIEWER | PATCH /employees/{id}/role | 200, sessions revoked | — |
| Last owner → MANAGER | | — | existing last-owner error |
| V1 sees a MANAGER | /auth/profile, employees list, JWT | `role:"EMPLOYEE"`, `memberRole:"MANAGER"` | — |
| Module manifest missing VIEWER entry | boot | — | `IllegalStateException` at startup |

</intent-contract>

## Code Map

- `identity/api/Role.java` -- enum {OWNER, EMPLOYEE, SUPER_ADMIN}.
- `shared/module/KeevoModule.java:47` (`employeePermissions`), `shared/module/application/ModulePermissions.java:34-35,48,52,85-97,112-145`, `shared/module/infrastructure/PermissionAuthorizationManager.java`, `RequiresPermission.java`, `invoicing/module/InvoicingModule.java:41`, `crm/module/CrmModule.java`, `reporting/module/ReportingModule.java`, `src/main/resources/keevo/modules/*/module.json` (18, only invoicing declares permissions), `TenantExtensionsController` (slot filter), `invoicing/.../InvoiceSyncHandler.java:142-144`.
- `shared/infrastructure/security/JwtAuthFilter.java:213,276-307` (role from access state, EMPLOYEE guards, `AuthDetails.storeId`), `CurrentActor.java:57-136`, `SecurityConfig.java` (filter chain, PUBLIC_PATHS), `AccessStateLoader.java:29`.
- `identity/auth/application/service/SessionTokenIssuer.java:65-90` (EMPLOYEE lookups, `LegacyClaims` role), `UserProfileService.java:81-96`, DTOs `UserProfileResponse`, `LoginResponse`, `LoginSessionResponse`, `EmployeeResponseDto`, `EmployeeRoleResolver`.
- `identity/employee/adapter/in/rest/EmployeeController.java:90-262` (11 `requireOwner`), `ChangeEmployeeRoleService.java:39,103`, `ChangeRoleRequestDto.java:12`, `ListEmployeesService`.
- Role-string comparisons to classify (≈75 lines, ≈60 files): catalog use cases (`CreateProductUseCase:138`, `UpdateProductUseCase:132`, …), commerce (`SaleController:46,52`, `SaleHistoryController:82`, `GetSalesHistoryService:34`), inventory, messaging listeners (`StockModification…:96`, `PriceChange…:104`, `ProductCreation…:96,173`, `PendingDraftsCountController:46`), reporting (`ReportController:142,171,274`), `AuditLabels:256`, `AuditLogExportDatasetProvider:45`, `AdminTenantService:537,567`. Use `grep -rn '"EMPLOYEE"\|"OWNER"' src/main/java`.
- Sync: `SyncController.java:125-126,184,220-239`, `SyncPushService.java:117-135,365`, `AbstractSyncOperationHandler.java:32-86`, `LastWriteWinsStrategy.java:79`, test pattern `SyncPushServiceModuleGuardTest`.
- `src/test/java/com/keevo/architecture/EndpointEntitlementCoverageTest.java:51,131,216` (`scan`, `Endpoint` record, verb), `docs/entitlements/endpoints.md`.
- V1 Flutter (read-only, `git show release/2.0.x:keevo/app/lib/...`): `features/team/presentation/page/edit_employee_page.dart:54,63,121,159` (demotion hazard), `core/auth/jwt_claims.dart:47`, `core/di/providers.dart:46-61`.

## Tasks & Acceptance

**Execution:**
- `Role.java` -- MANAGER, VIEWER, `isStoreMember()`, `v1Label()` (MANAGER/VIEWER → EMPLOYEE).
- `KeevoModule`, `ModulePermissions`, manifests, `InvoicingModule`, consumers -- per-role permissions and boot validation.
- `JwtAuthFilter`, `CurrentActor`, `SessionTokenIssuer`, `UserProfileService`, `PullScope` users -- store-member scoping.
- Role-string call sites -- classify and switch restrictions to `isStoreMember()`; `@PreAuthorize` lists widened.
- `shared/infrastructure/security/ViewerWriteGuardFilter.java` (+ registration in `SecurityConfig`) -- central VIEWER write block with the allow-list constant.
- `SyncPushService` -- VIEWER operations `REJECTED/FORBIDDEN`.
- `EmployeeController` + employee services/queries -- MANAGER team rule, store-filtered list.
- `ChangeEmployeeRoleService`, `ChangeRoleRequestDto` -- four roles, last-owner guard.
- DTOs and legacy JWT claims -- `role` in V1 vocabulary + additive `memberRole`.
- Core module script (only if the tenant `users.role` CHECK is still written) -- widen idempotently.
- Tests -- per the Always list; existing OWNER/EMPLOYEE tests green.

**Acceptance Criteria:**
- Given the four roles in one tenant, when each calls the same set of endpoints, then OWNER and EMPLOYEE results are unchanged from before the story, MANAGER gets EMPLOYEE's results plus his store's team management, VIEWER gets EMPLOYEE's reads and 403 on every write.
- Given the full suite run package by package, then it is green, including the parameterised VIEWER test and architecture tests.

## Spec Change Log

## Review Triage Log

### 2026-11-05 — Review pass
- verdicts: 27 findings — high 0, medium 7, low 19, false 1, maybe-false 0 (grouped rows list every reviewer finding they cover)
- findings:
  - `[medium]` `[patch]` (patterns 1, blind 3) SyncPushService reads CurrentActor in the application layer — role carried by PushBatchCommand; misleading « stay pushable » comment fixed.
  - `[medium]` `[patch]` (patterns 2, blind 5, blind 6) declared roles and the store-member set written 3 times — derived from Role; CurrentActor/MemberRoles delegate.
  - `[low]` `[patch]` (patterns 3) permissionsOf allocates a TreeSet per check — cached set returned for a single role.
  - `[low]` `[patch]` (patterns 4) Role.parse scans values() — static map.
  - `[low]` `[reject]` (patterns 5) requireManageable reloads the target row — O(1), 3 keyed lookups.
  - `[low]` `[reject]` (patterns 6) shared.domain → identity.api dependency — the module-level link already exists (CurrentActor, ModulePermissions); MemberRoles now only delegates.
  - `[medium]` `[patch]` (patterns note, edge 1) MANAGER list/activity returns OWNER and peer MANAGER rows of his store — filtered to EMPLOYEE/VIEWER (+ himself) via the bulk resolver.
  - `[medium]` `[patch]` (patterns note, blind 11, edge 2) requireManageable fails open on a missing membership (defaults to EMPLOYEE) — membership read directly, missing → FORBIDDEN.
  - `[low]` `[reject]` (intent 1) Flutter-side parser test — V1 is frozen; the server never sends MANAGER/VIEWER in V1 fields (role vs memberRole), tested server-side.
  - `[low]` `[reject]` (intent 2) MANAGER identical to EMPLOYEE in manifests — only invoicing has permissions; team management is a behavioural rule (EmployeeTeamScope).
  - `[medium]` `[patch]` (intent 3, blind 1) allow-list wildcards would open future writes to VIEWER and the test skips them — exact self-service list pinned against the endpoint scan.
  - `[false]` `[reject]` (intent 4) no migration — role columns in public are unconstrained varchar; existing memberships untouched by construction; the tenant users CHECK is never written.
  - `[low]` `[patch]` (vg 1) MANAGER store filtering only tested through mocks — ListEmployeesServiceTest + forStore case.
  - `[low]` `[patch]` (vg 2) no MANAGER case for sales history / cancel inventory — tests added.
  - `[low]` `[patch]` (vg 3) no MANAGER-actor case for owner notification listeners — tests added.
  - `[low]` `[patch]` (vg 4, blind 7) admin role lists hard-coded in SQL — one constant derived from Role.
  - `[low]` `[patch]` (vg other, edge 5) admin member list labels MANAGER/VIEWER « Employé » — « Gérant » / « Lecteur ».
  - `[low]` `[patch]` (blind 2) filter matches raw getRequestURI — path within application.
  - `[medium]` `[patch]` (blind 4) VIEWER on V1 sees seller UI and loses offline sales (REJECTED is final) — routed to 19.10 (minimum version / refuse V1 login for MANAGER/VIEWER); comment corrected.
  - `[medium]` `[patch]` (blind 8) MANAGER/VIEWER only see their own sales; MANAGER cannot cancel his sellers' sessions — spec rule « never less restricted than EMPLOYEE »; product decision routed to new key 19-5b.
  - `[low]` `[patch]` (blind 9, blind 10) MANAGER excluded from device sessions; can reactivate an owner-deactivated member — routed to 19-5b (product decisions).
  - `[low]` `[reject]` (blind 12) authorisation only in the REST adapter — same pattern as the existing owner checks; no other inbound adapter calls these use cases.
  - `[low]` `[patch]` (blind 13) stale « EMPLOYEE » messages, javadocs and fallback labels — updated.
  - `[low]` `[patch]` (blind 14) EmployeeTeamScope dead branch / unused parameter / misleading message — cleaned; plan cap counting MANAGER/VIEWER routed to 19-5b; phone change by a MANAGER rejected (owner can do the same, uniqueness enforced).
  - `[low]` `[reject]` (edge 3) TOCTOU between scope check and use case — owner promotion racing a manager action is rare; the next request is checked again.
  - `[low]` `[patch]` (edge 4) role change to a store role for a member without store — VALIDATION_FAILED « Affectez d'abord ce membre à une boutique ».
  - `[low]` `[patch]` (edge 6) multi-verb/ANY handlers only tested on the first verb — every write verb exercised.

## Design Notes

**Patterns.** Role as a value with behaviour (`isStoreMember`, `v1Label`) instead of scattered string compares; declarative per-role permissions in manifests validated at boot (fail-fast, Specification-like rule set); a single Intercepting Filter for the VIEWER write ban (one place instead of 116 endpoints, with an explicit allow-list), and a guard at the sync dispatch for the same rule; Adapter at the API edge for V1 role vocabulary (`role` vs `memberRole`).

**Why VIEWER/MANAGER are store members.** Bref admin/seller/viewer belong to a shop; mapping them to the existing store scoping keeps every read path as restricted as EMPLOYEE's without new rules, and only MANAGER gains the team rule.

**Complexity (FUS-NFR11).** Filter: O(1) per request (method + prefix check). Sync: one role check per batch. Team list for MANAGER: one store-filtered query (existing list query + `store_id = ?`), bulk enrichment from 19.4 (3 queries). Boot validation O(modules × roles). No new per-row queries.

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='ModulePermissions*Test,ViewerWriteGuard*Test,Viewer*FullContextTest,*Manager*Test,ChangeEmployeeRole*Test,SyncPushService*Test,EmployeeController*Test,UserProfile*Test,SessionTokenIssuer*Test,JwtAuthFilter*Test'` -- expected: green.
- Full suite package by package (scratchpad `run-pkgs-wait.sh`) -- expected: 0 failures.

**Manual checks:**
- Local backend: owner sets a member to VIEWER → member's POST /products 403, GET /products 200; set to MANAGER → GET /employees shows his store only; `/auth/profile` shows `role:"EMPLOYEE"`, `memberRole:"MANAGER"`.

## Auto Run Result

Status: done

**Summary.** Membership roles OWNER, MANAGER, EMPLOYEE, VIEWER (`Role` single source: `isStoreMember`, `v1Label`, static `parse`). Every « non-owner » restriction now applies to all store members; `@PreAuthorize` lists open reads to the four roles. Per-role permissions declared explicitly in all 18 manifests (`rolePermissions`), validated at boot (missing role, unknown permission, VIEWER write permission → startup failure); `permissionsFor(Role)` replaces `employeePermissions()`. VIEWER never writes: `ViewerWriteGuardFilter` (403 `FORBIDDEN`) with an exact, pinned self-service allow-list of 11 endpoints; sync push answers every VIEWER operation `REJECTED/FORBIDDEN` (role carried by the command). MANAGER: team management of his store (EMPLOYEE/VIEWER only, no role change or store move, fails closed on missing membership), store-filtered list in one query. Members can be created with a role. Role change accepts the four roles with the last-owner guard. V1 apps see MANAGER/VIEWER as `EMPLOYEE` in every `role` field (JWT legacy claim included) and the real role in the additive `memberRole`. Admin labels « Gérant »/« Lecteur »; export contract regenerated.

**Files.** New: `ViewerWriteGuardFilter`, `EmployeeTeamScope`, `shared/domain/MemberRoles`, `admin/MemberRolesSql`, tests (`RoleTest`, `ViewerWriteGuardFilterTest`, `ViewerAllowListPinTest`, `ViewerNeverWritesFullContextTest`, `EmployeeControllerManagerTest`, `SyncPushServiceViewerTest`, `ListEmployeesServiceTest`). Changed: `Role`, `KeevoModule`, `ModuleManifest`(+loader), `ModulePermissions`, 18 `module.json`, `JwtAuthFilter`, `CurrentActor`, `SecurityConfig`, `SessionTokenIssuer`, `UserProfileService`, auth/employee DTOs (`memberRole`), `EmployeeController`, employee services (list/activity by store, create with role, role change), sales/inventory/messaging/reporting/admin call sites, `SyncUseCase`/`SyncController`/`SyncPushService`, `keevo/contracts/export-datasets.json`.

**Review.** 27 entries (5 layers): 0 high, 7 medium, 19 low, 1 false. Patched in code: 5 medium (sync role via command, single role source, MANAGER list filtering, fail-closed membership, exact pinned allow-list) and 15 low. Routed: 2 medium → new key 19-5b (product decisions: MANAGER store-wide sales/inventory, VIEWER reads by POST, device sessions, owner-deactivated reactivation, plan cap) and 19.10 (VIEWER on V1 loses offline sales). Rejected: 6, reasons in the triage log.

**Follow-up review: recommended (true)** — 5 medium code patches; unverified risk: role behaviour of the ≈75 reclassified call sites is covered by targeted cases, not by a per-role run of every business flow.

**Verification.** Targeted classes green (≈650 then 451 + full-context VIEWER 74 cases after patches). Full suite package by package: 5 582 tests, 0 failures after regenerating `keevo/contracts/export-datasets.json` (new audit role labels). Live `keevo_dev`: owner creates a VIEWER (`role:EMPLOYEE`, `memberRole:VIEWER`), VIEWER logs in by username, GET /products 200, POST /categories 403 `FORBIDDEN`, profile `EMPLOYEE`/`VIEWER`, PATCH /notifications/read-all 204.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit de la story 19.5, voir `git log --grep "feat(19-5)"`). Conventions : spec-16-11 ; synchro : spec-16-10.

**Ce qui est fait côté API :** rôles gérant (MANAGER), vendeur (EMPLOYEE), lecteur (VIEWER) en plus du propriétaire ; le lecteur ne peut rien modifier ; le gérant gère l'équipe de sa boutique.

**À utiliser :**
1. Rôle réel : lire `memberRole` (`OWNER|MANAGER|EMPLOYEE|VIEWER|SUPER_ADMIN`) dans `/auth/profile`, les memberships de connexion et les employés ; `role` reste au vocabulaire V1 (MANAGER/VIEWER → `EMPLOYEE`), ne pas s'en servir.
2. `POST /api/v1/employees` accepte `role` (`EMPLOYEE` par défaut, `MANAGER`, `VIEWER`) ; `PATCH /api/v1/employees/{id}/role` accepte les quatre rôles (propriétaire seulement). Erreurs : 403 `FORBIDDEN` « Vous n'avez pas les droits nécessaires » ; 422 `VALIDATION_FAILED` (rôle inconnu, membre sans boutique : « Affectez d'abord ce membre à une boutique ») ; erreur du dernier propriétaire inchangée.
3. Lecteur : toute écriture → 403 `FORBIDDEN` ; synchro : chaque opération revient `REJECTED` avec la raison `FORBIDDEN`. Permises : changer son mot de passe, ses sessions, son jeton push, Web Push, marquer les notifications lues, envoyer un avis.
4. Gérant : `GET /employees` ne renvoie que les vendeurs et lecteurs de sa boutique (et lui-même) ; il peut en créer (EMPLOYEE/VIEWER, sa boutique), les modifier, désactiver, réactiver, réinitialiser leur mot de passe ; pas de changement de rôle ni de boutique.

Code backend : `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/ViewerWriteGuardFilter.java`, `identity/employee/adapter/in/rest/EmployeeTeamScope.java`, `identity/api/Role.java`.

**À implémenter côté mobile :**
1. Navigation et actions selon `memberRole` : lecteur = écrans en lecture seule (aucun bouton de création, vente, ajustement) ; gérant = écrans vendeur + « Mon équipe » limité à sa boutique ; libellés « Propriétaire », « Gérant », « Vendeur », « Lecteur ».
2. Ne jamais mettre en file d'attente hors ligne une écriture d'un lecteur (elle serait refusée à la synchro).
3. Formulaire d'ajout de membre : choix du rôle (propriétaire : les trois ; gérant : vendeur ou lecteur).
4. Afficher les messages d'erreur de l'API tels quels.

**Critères d'acceptation mobile / scénarios de test :**
- Given un lecteur connecté, Then aucun bouton d'écriture n'est visible et une tentative forcée renvoie « Vous n'avez pas les droits nécessaires ».
- Given un gérant, When il ouvre « Mon équipe », Then il ne voit que les membres de sa boutique et peut ajouter un vendeur.
- Mise en place : créer un membre avec `"role":"VIEWER"` via `POST /api/v1/employees` puis se connecter avec son identifiant.

**Clé de suivi mobile :** `m19-5-roles-manager-viewer-permissions`.

