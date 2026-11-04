---
title: '19.5 — Membership roles MANAGER and VIEWER, permissions per role, VIEWER never writes'
type: 'feature'
created: '2026-11-05'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
warnings: [oversized]
deferred: []
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
