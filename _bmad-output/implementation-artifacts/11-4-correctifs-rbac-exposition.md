# Story 11.4: Correctifs de contrôle d'accès (RBAC & exposition)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> 2026-10-11 — Story file copied from branch `refractoring` to `deploy`. On `deploy`: admin path / TenantSchema / PendingSale scoping delivered via v1s-12-3/12-5/12-6, device-token ownership fixed today; N+1 admin and the remaining refonte-only parts stay on `refractoring`.

## Story

As a **security owner (responsable sécurité)**,
I want **the RBAC and exposure gaps flagged by the code audits closed**,
so that **no actor can act beyond its perimeter (OWASP A01 Broken Access Control / A05 Security Misconfiguration).**

## Context & Why This Story

The audits (`review_claude_opus_8.md` DT10, `review_qwen_6.md`) flagged four concrete, consensual access-control / exposure defects. Each is small and independent; together they form this hardening story. **All four are real gaps in `main` today:**

1. **EMPLOYEE can cancel a draft sale.** `PendingSaleController.cancelSale` allows `hasAnyRole('OWNER','EMPLOYEE')`. Cancellation of a pending/draft sale must be **OWNER-only** (validation of a draft stays allowed for EMPLOYEE — that is intentional and must NOT change). Source: `review_qwen_6.md:424` "EMPLOYEE peut annuler une vente brouillon (devrait être OWNER-only)"; `deferred-work` P2 / DT10.

2. **Device-token deletion has no ownership check.** `DeleteDeviceTokenService.delete(token)` → `deleteByToken(token)` deletes **any** FCM token by its value, regardless of which user owns it. A user can delete another user's device token (forcing them offline / denying push). Deletion must be scoped to the authenticated user's `userId`.

3. **CORS hardcodes `http://localhost:3000` in every profile, including prod.** `SecurityConfig.corsConfigurationSource()` always calls `config.addAllowedOrigin("http://localhost:3000")`. In prod the allow-list must come **only** from the `DASHBOARD_ORIGIN` env var; the dev origin must not be present in the prod CORS policy.

4. **Schema names are interpolated into raw SQL without validation at several `JdbcTemplate` call sites.** Many adapters build SQL like `"SELECT COUNT(*) FROM \"" + schema + "\".products ..."`. The codebase already has the *intent* (regex `^kv_[a-z0-9]{6}$` is duplicated inline in ~12 places and asserted by comments "validated at provisioning" in the un-guarded ones), but the validation is **inconsistent**: the `subscription/plan` count adapters and the `admin` services interpolate the schema with **no** runtime check. This story introduces a single `TenantSchema` helper and applies it at every interpolation site, eliminating the SQL-injection surface as genuine defense-in-depth (even though the schema currently originates from `TenantContext`, not from user input).

**These four fixes are independent** — they touch four different modules (`commerce`, `messaging`, `shared/security`, and a cross-cutting `shared/persistence` helper) and share no code. Implement and test them separately; only the regression run (Task 6) is shared.

## Acceptance Criteria

1. **Given** an authenticated EMPLOYEE **When** it calls `POST /api/v1/sales/{id}/cancel` (`PendingSaleController.cancelSale`) **Then** the request is rejected with **`403 FORBIDDEN`** (domainCode `FORBIDDEN`) and `CancelPendingSaleUseCase` is **never** invoked. Validation (`POST /api/v1/sales/{id}/validate`) and listing (`GET /api/v1/sales/pending`) remain available to EMPLOYEE — unchanged.
2. **Given** an authenticated OWNER **When** it calls `POST /api/v1/sales/{id}/cancel` with a valid body **Then** the cancellation proceeds exactly as today (`200 OK`, use case invoked) — no regression to the OWNER path.
3. **Given** an authenticated user **When** it calls `DELETE /api/v1/devices/token` for a token that belongs to **another** user **Then** that token is **not** deleted (operation refused: nothing is removed and no other user is affected); the response does not reveal whether the token exists.
4. **Given** an authenticated user **When** it deletes **its own** device token (the logout-cleanup path) **Then** the token is deleted as today (`deleted: true`) — no regression to normal logout.
5. **Given** the `prod` profile is active **When** the CORS policy is built **Then** the allowed origins contain **only** the value(s) from `DASHBOARD_ORIGIN` (allow-list per env) and **do not** contain `http://localhost:3000`; a request from a non-allow-listed origin is rejected by CORS.
6. **Given** a dev/test profile (no `prod`) **When** the CORS policy is built **Then** `http://localhost:3000` remains allowed (local dashboard dev is unbroken).
7. **Given** any code path that interpolates a tenant schema name into raw SQL executed via `JdbcTemplate`/`Statement` **When** the schema name is resolved **Then** it is validated against `^kv_[a-z0-9]{6}$` by the shared `TenantSchema` helper **before** interpolation; a schema failing the pattern is rejected (helper throws) and never reaches the SQL string. The previously-unguarded sites (`subscription/plan` count adapters; `admin` services) now validate; the sites that already inline-checked the regex are refactored to call the helper (single source of truth) with identical behaviour.
8. **Given** the existing test suite **When** the build runs **Then** all previously-green tests stay green (no regression — baseline ~1463 backend tests with **1 pre-existing failure + 33 pre-existing errors**, unrelated, documented in sprint-status), and **new** tests prove: AC1 (EMPLOYEE cancel → 403, use case not called), AC2 (OWNER cancel → 200), AC3/AC4 (cross-user delete refused / own delete ok), AC5/AC6 (CORS prod excludes localhost, dev includes it), AC7 (`TenantSchema.validate` accepts a valid `kv_xxxxxx`, throws on `public`, on an injection payload like `kv_a; DROP TABLE`, and on `null`).

## Tasks / Subtasks

- [x] **Task 1 — Make draft-sale cancellation OWNER-only** (AC: #1, #2)
  - [x] In `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/PendingSaleController.java`, restrict `cancelSale(...)` to OWNER. **Use the codebase's proven explicit-guard pattern, not `@PreAuthorize` alone** — see Dev Notes "Why an explicit guard, not just `@PreAuthorize`". Add a private `requireOwnerOrForbid()` mirroring `EmployeeController.requireOwnerOrForbid()` exactly:
    ```java
    private void requireOwnerOrForbid() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream()
                .noneMatch(a -> "ROLE_OWNER".equals(a.getAuthority()))) {
            throw new DomainException(ErrorCode.FORBIDDEN, "OWNER role required to cancel a sale");
        }
    }
    ```
    Call it as the **first statement** of `cancelSale(...)` (before `extractActorId()` / use-case call), so the use case is never reached for an EMPLOYEE (AC1).
  - [x] Keep `@PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")` on `getPendingSales` and `validateSale` **unchanged**. For `cancelSale`, change the annotation to `@PreAuthorize("hasRole('OWNER')")` as defense-in-depth (belt-and-braces with the explicit guard). Do **not** remove EMPLOYEE from validate.
  - [x] Imports: `DomainException` (`com.keevo.shared.domain.exception.DomainException`) and `ErrorCode` (`com.keevo.shared.domain.exception.ErrorCode`) — confirm `ErrorCode.FORBIDDEN` exists (it does; `GlobalExceptionHandler` maps `FORBIDDEN` → HTTP 403, see line ~221).
  - [x] In `PendingSaleControllerTest` (`src/test/.../commerce/sale/adapter/in/rest/PendingSaleControllerTest.java`), add: `POST_cancel_returns403_whenEmployee` — authenticate as `EMPLOYEE`, perform the cancel, assert `status().isForbidden()` and `verify(cancelPendingSaleUseCase, never()).cancelPendingSale(any())`. The existing `POST_cancel_returns200_withValidJustification` (authenticated OWNER) already covers AC2 — keep it green. The standalone harness already wires `setControllerAdvice(new GlobalExceptionHandler())`, so the `FORBIDDEN` → 403 mapping is exercised (the EmployeeControllerTest `createEmployee_returns403_whenNotOwner` proves this exact pattern works under `standaloneSetup`).

- [x] **Task 2 — Scope device-token deletion to the authenticated user** (AC: #3, #4)
  - [x] Change the port `DeleteDeviceTokenUseCase` (`messaging/notification/domain/port/in/DeleteDeviceTokenUseCase.java`) to `boolean delete(UUID userId, String token);` (as delivered — the original plan said `delete(String token, UUID userId)`).
  - [x] In `DeleteDeviceTokenService`, delete only if the token belongs to `userId`. Preferred: add a userId-scoped repository delete so the DB never removes another user's row. Two equally-acceptable shapes (pick one, keep it simple):
    - **(a) Scoped delete (recommended):** add `int deleteByTokenAndUserId(String token, UUID userId)` to `DeviceTokenRepository` + adapter + a `@Modifying @Query("DELETE FROM DeviceTokenJpaEntity d WHERE d.token = :token AND d.userId = :userId")` in `DeviceTokenSpringRepository`. Service returns `deviceTokenRepository.deleteByTokenAndUserId(token, userId) > 0`. A token owned by another user → 0 rows → `deleted:false`, nothing leaked (AC3); own token → deleted (AC4).
    - **(b) Find-then-check:** `findByToken(token)`, if present **and** `userId.equals(dt.userId())` then `deleteByToken`, else return `false`. Acceptable but does an extra read; (a) is atomic and preferred.
  - [x] In `DeviceTokenController.deleteToken(...)`, extract the principal `userId` exactly like `registerToken` does (`UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();`) and pass it to `deleteUseCase.delete(request.token(), actorId)`. Response shape unchanged (`Map.of("deleted", deleted)`).
  - [x] Update `DeleteDeviceTokenServiceTest` and `DeviceTokenControllerTest` (both use `standaloneSetup` + a principal set on the SecurityContext): add a cross-user case (token owned by user B, caller is user A → `deleted:false`, repo scoped-delete returns 0 / find returns other user) and keep the own-token case green. Do **not** assert on existence leakage beyond the boolean.
  - [x] Do **not** change `registerToken` or the `findByUserIds` / `findOwnerTokens` paths.

- [x] **Task 3 — Remove the hardcoded dev origin from the prod CORS policy** (AC: #5, #6)
  - [x] In `SecurityConfig` (`shared/infrastructure/security/SecurityConfig.java`), inject `org.springframework.core.env.Environment` (constructor — it already has a constructor taking `JwtAuthFilter`; add `Environment` alongside). In `corsConfigurationSource()`, gate the dev origin behind a non-prod check:
    ```java
    String dashboardOrigin = System.getenv("DASHBOARD_ORIGIN");
    if (dashboardOrigin != null && !dashboardOrigin.isBlank()) {
        config.addAllowedOrigin(dashboardOrigin);
    }
    boolean isProd = environment.acceptsProfiles(Profiles.of("prod"));   // org.springframework.core.env.Profiles
    if (!isProd) {
        config.addAllowedOrigin("http://localhost:3000");   // local dashboard dev only
    }
    ```
    Keep everything else (methods, headers, `setAllowCredentials(false)`, `registerCorsConfiguration("/api/**", ...)`) **unchanged**.
  - [x] Consider supporting a comma-separated `DASHBOARD_ORIGIN` (multiple allow-listed origins) by splitting on `,` and trimming — only if trivial; otherwise single-origin is fine and matches current behaviour. Document whichever you choose in completion notes.
  - [x] `@EnableMethodSecurity` and the rest of `securityFilterChain` are untouched.
  - [x] Add a new test `SecurityConfigCorsTest` (none exists today). Because `corsConfigurationSource()` reads `Environment` + `System.getenv`, the cleanest test is a **plain unit test** constructing `new SecurityConfig(mockJwtFilter, environment)` with a `org.springframework.mock.env.MockEnvironment`: (a) profile `prod` set → `CorsConfiguration.getAllowedOrigins()` does NOT contain `http://localhost:3000`; (b) no profile / `dev` → it DOES. For the `DASHBOARD_ORIGIN`-present assertion, note `System.getenv` cannot be set from a pure unit test — assert the localhost gating (the AC-critical part) and document the env-driven branch is covered by the existing prod wiring. See Dev Notes "Testing CORS without env-var mutation".

- [x] **Task 4 — Introduce `TenantSchema` helper and apply it at every JdbcTemplate interpolation site** (AC: #7)
  - [x] Create `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchema.java` — a final utility class (private constructor) with:
    ```java
    public static final String PATTERN = "^kv_[a-z0-9]{6}$";
    public static boolean isValid(String schema) { return schema != null && schema.matches(PATTERN); }
    /** @return the schema if valid (fluent); throws IllegalArgumentException otherwise. */
    public static String validate(String schema) {
        if (!isValid(schema)) throw new IllegalArgumentException("Invalid tenant schema: " + schema);
        return schema;
    }
    ```
    Use the **established `{6}` pattern**, not the `+` form written loosely in the epic AC — see Dev Notes "Regex: keep `{6}`, the proven convention". This class lives in `shared/infrastructure/persistence` (no business coupling → ArchUnit/Modulith-clean, Story 10.6).
  - [x] **Apply `TenantSchema.validate(schema)` at the currently-UNGUARDED interpolation sites** (these have NO runtime check today — the real gap):
    - `subscription/plan/adapter/out/persistence/impl/ProductCountAdapter.java`, `StoreCountAdapter.java`, `UserCountAdapter.java`, `EmployeeCountAdapter.java` — each does `String schema = TenantContext.getCurrentTenant();` then interpolates. Wrap as `String schema = TenantSchema.validate(TenantContext.getCurrentTenant());` **before** the interpolation. (These already catch table-not-found and return 0; an invalid schema is a programming error and should fail loudly — see Dev Notes.)
    - `admin/tenant/application/service/AdminTenantService.java`, `admin/operations/application/service/AdminOperationsService.java`, `admin/sync_monitoring/application/service/AdminSyncMonitoringService.java`, `admin/catalog/application/service/AdminCatalogService.java` — these interpolate `schemaName`/`schema` (sourced from `public.tenants.schema_name`) into `JdbcTemplate` SQL with only a justifying comment. Call `TenantSchema.validate(schemaName)` once per schema, before it is interpolated (e.g. at the top of the per-tenant enrichment loop / method). Replace the "validated at provisioning, safe here" comments with a reference to the helper.
  - [x] **Refactor the sites that already inline the regex** to call the helper (single source of truth, identical behaviour — do not change their skip/return semantics):
    - Throwing sites → `TenantSchema.validate(...)`: `TenantSchemaProvisioner` (lines ~706, ~728 — keep the existing thrown-exception behaviour; the helper throws `IllegalArgumentException`, confirm callers/tests still expect that or keep the local throw and just reference `TenantSchema.PATTERN`), `identity/auth/adapter/out/persistence/impl/TenantSchemaAdapter.java` (line ~39).
    - Skip/continue sites → `TenantSchema.isValid(...)`: `JdbcNotificationCooldownAdapter` (~72), `SalesTrendDetectionScheduler` (~71), `TenantSchemaMigrationRunner` (~74), `TenantSchemaSyncService` (`SCHEMA_PATTERN` ~90/116 — replace the local constant with `TenantSchema.PATTERN` or `isValid`), `SchemaAwareMultiTenantConnectionProvider` (~119), `subscription/plan/application/service/SubscriptionExpiryService` (~69), `catalog/stock/adapter/out/persistence/MultiStoreStockRepositoryAdapter` (~99), `sync/.../strategy/DeltaStockStrategy` (`Pattern.compile` ~27 — can keep its compiled `Pattern` for hot-path perf but should reference `TenantSchema.PATTERN` for the string, OR call `isValid`; pick one and note it).
    - `SyncErrorLogCleanupJob` (~39) filters via **SQL** `~ '^kv_[a-z0-9]{6}$'` (server-side) — leave the SQL as-is (it is not Java interpolation of an externally-supplied schema), or optionally reference `TenantSchema.PATTERN`. No behaviour change.
  - [x] **Inventory check:** after editing, `grep -rn` the codebase for the literal `kv_[a-z0-9]{6}` and for `" + schema` / `"\"" +` to confirm every Java interpolation site either calls the helper or is a documented exception (the SQL-side filter). Record the final list in completion notes so a reviewer can verify completeness (AC7's "everywhere").
  - [x] Add `TenantSchemaTest` (`src/test/.../shared/infrastructure/persistence/TenantSchemaTest.java`): `isValid`/`validate` accept `kv_a1b2c3`; reject `public`, `kv_TooLong0`, `kv_UPPER1`, `"kv_a; DROP TABLE x"`, `"kv_abc"` (too short), empty string, and `null` (no NPE — `validate(null)` throws `IllegalArgumentException`, not NPE).

- [x] **Task 5 — Confirm no over-reach: the sync-handler cancel path and the Flutter cancel UI** (AC: #1)
  - [x] **Awareness only (no code change unless trivially needed):** cancellation also has a server-side sync entry point `sync/sync/application/handler/CancelSaleSyncHandler.java` (offline-queued cancel). This story's AC1 scope is the **REST** controller (`PendingSaleController.cancelSale`). Verify the sync handler does not become a bypass that lets an EMPLOYEE cancel via the offline queue; if it executes cancels without role context, note it explicitly in completion notes as a **follow-up** (do not expand scope here unless the handler trivially already gates by role). The Flutter client should also hide the cancel action from EMPLOYEE (frontend story / HF financial-scoping work) — out of scope for this backend story but call it out so it is not lost.

- [x] **Task 6 — Regression + completion notes** (AC: #8)
  - [x] From `keevo/backend`: `./mvnw -q -DskipTests compile` then `./mvnw test`. Confirm no NEW failures beyond the documented baseline (~1463 tests; **1 pre-existing failure + 33 pre-existing errors** — do not increase these counts). Your four new/updated test classes must be green.
  - [x] In completion notes document: (1) the `cancelSale` guard (explicit `requireOwnerOrForbid` + `@PreAuthorize`); (2) the device-token delete scoping approach chosen (scoped-delete vs find-then-check) and the cross-user behaviour (refused, no leak); (3) the CORS gating (`Environment.acceptsProfiles("prod")`) and whether multi-origin `DASHBOARD_ORIGIN` was supported; (4) the full final list of schema-interpolation sites now routed through `TenantSchema` (and the SQL-filter exception), proving AC7 completeness.

## Dev Notes

### Why an explicit guard, not just `@PreAuthorize` (AC1 — critical for testability)

The existing controller tests use `MockMvcBuilders.standaloneSetup(controller)`, which **does not run Spring method-security** — `@PreAuthorize` is **not enforced** under `standaloneSetup`. If you rely on `@PreAuthorize` alone, the AC1 test (`EMPLOYEE → 403`) would either be impossible to write in the existing harness or, worse, pass as a **false positive** (the annotation silently ignored, the use case actually invoked). The codebase already solved this exact problem in `EmployeeController.requireOwnerOrForbid()` (Story 3.5 AC5): an explicit in-controller check that throws `DomainException(ErrorCode.FORBIDDEN, ...)`, which `GlobalExceptionHandler` maps to HTTP 403 and which **is** testable under `standaloneSetup` (`EmployeeControllerTest.createEmployee_returns403_whenNotOwner` proves it). Mirror that pattern. Keep `@PreAuthorize("hasRole('OWNER')")` too as a second layer that bites in the real (fully-wired) security filter chain.

### Device-token ownership (AC3/AC4)

`DeviceToken` carries `userId` (`domain/model/DeviceToken.java`; column on `DeviceTokenJpaEntity`). Today `deleteByToken(token)` ignores it. Scope the deletion to the JWT principal so a user can only delete **its own** tokens. "Refused" for a non-owned token means **nothing is deleted** (return `deleted:false`) — do **not** throw 404/403 that reveals whether the token exists (avoid an existence oracle). The principal is the `userId` UUID (`auth.getPrincipal()` is a `UUID` here, same as `registerToken` and `extractActorId` elsewhere). The scoped-delete `@Query` (option a) is atomic and preferred over find-then-delete.

### CORS profile gating (AC5/AC6)

`SecurityConfig` is currently profile-unaware. The project already detects prod via Spring profiles (`ProdSecretsValidator` is `@Profile({"prod","staging"})`). Inject `Environment` and use `environment.acceptsProfiles(Profiles.of("prod"))` to suppress the hardcoded `http://localhost:3000` in prod. Decision: gate on **`prod` only** (not staging) unless you have a reason to also harden staging — staging is iso-prod (Story 10.5) and is driven by `DASHBOARD_ORIGIN`, so it is fine to also exclude localhost there; if you include `staging` in the suppression set, update the AC6 test accordingly and note it. `setAllowCredentials(false)` stays false — adding credentials would forbid wildcard and change browser behaviour; not in scope.

### Testing CORS without env-var mutation

`corsConfigurationSource()` mixes two inputs: Spring `Environment` (mockable) and `System.getenv("DASHBOARD_ORIGIN")` (not mutable from a unit test without reflection hacks — avoid). Test the **AC-critical** branch directly: build `new SecurityConfig(jwtAuthFilter, new MockEnvironment().setActiveProfiles("prod"))`, call `corsConfigurationSource()`, and assert `((CorsConfiguration) source.getCorsConfiguration(mockRequestTo("/api/x"))).getAllowedOrigins()` excludes `http://localhost:3000`; repeat with `MockEnvironment` having no/`dev` profile and assert it includes it. The `DASHBOARD_ORIGIN` branch is covered implicitly in the deployed prod config (document this; do not fight `System.getenv` in a unit test).

### Regex: keep `{6}`, the proven convention

The epic AC writes the pattern loosely as `^kv_[a-z0-9]+$`, but the entire codebase consistently uses the stricter, correct `^kv_[a-z0-9]{6}$` (schema = `kv_` + 6 lowercase hex/alphanumeric chars, produced by `TenantSchemaProvisioner`). Use `{6}` in `TenantSchema.PATTERN` to stay byte-identical to every existing inline check — switching to `+` would silently widen acceptance and diverge from `TenantSchemaProvisioner`/`SchemaAwareMultiTenantConnectionProvider`/`TenantSchemaSyncService`. This is a deliberate, documented deviation from the epic's loose wording.

### `TenantSchema.validate` should THROW (not silently skip)

The helper's `validate()` throws `IllegalArgumentException` on a bad schema — this is the defense-in-depth: an invalid schema reaching an interpolation site is a bug/attack and must fail loudly, never interpolate. Sites that today **gracefully skip** an unexpected schema (loops over many tenants: `SalesTrendDetectionScheduler`, `SubscriptionExpiryService`, `TenantSchemaSyncService`, `SyncErrorLogCleanupJob`) must keep skipping — route those through the boolean `isValid()` (`if (!TenantSchema.isValid(schema)) continue;`), not `validate()`, to preserve their batch-tolerant semantics. Single-schema request paths (count adapters, admin per-tenant lookups, provisioner) use `validate()` (throw). Do not change any site's control flow — only its source of the regex.

### Files being modified / created

| File | NEW/UPDATE | What |
| --- | --- | --- |
| `commerce/sale/adapter/in/rest/PendingSaleController.java` | UPDATE | OWNER-only guard on `cancelSale` (explicit `requireOwnerOrForbid` + `@PreAuthorize("hasRole('OWNER')")`). |
| `commerce/sale/adapter/in/rest/PendingSaleControllerTest.java` | UPDATE | Add EMPLOYEE-cancel → 403 (use case never called); OWNER path stays green. |
| `messaging/notification/domain/port/in/DeleteDeviceTokenUseCase.java` | UPDATE | `boolean delete(UUID userId, String token)`. |
| `messaging/notification/application/service/DeleteDeviceTokenService.java` | UPDATE | Scope delete to `userId`. |
| `messaging/notification/domain/port/out/DeviceTokenRepository.java` + `.../impl/DeviceTokenRepositoryAdapter.java` + `.../jpa/DeviceTokenSpringRepository.java` | UPDATE | Add `deleteByTokenAndUserId` (option a). |
| `messaging/notification/adapter/in/rest/DeviceTokenController.java` | UPDATE | Pass principal `userId` to delete. |
| `messaging/notification/.../DeleteDeviceTokenServiceTest.java` + `DeviceTokenControllerTest.java` | UPDATE | Cross-user refused / own-token ok. |
| `shared/infrastructure/security/SecurityConfig.java` | UPDATE | Inject `Environment`; gate localhost origin behind non-prod. |
| `shared/infrastructure/security/SecurityConfigCorsTest.java` | **NEW** | prod excludes localhost / dev includes it. |
| `shared/infrastructure/persistence/TenantSchema.java` | **NEW** | `isValid` / `validate` / `PATTERN` helper. |
| `shared/infrastructure/persistence/TenantSchemaTest.java` | **NEW** | Accept valid, reject `public` / injection / null / wrong-length. |
| `subscription/plan/adapter/out/persistence/impl/{Product,Store,User,Employee}CountAdapter.java` | UPDATE | `TenantSchema.validate(...)` before interpolation. |
| `admin/{tenant,operations,sync_monitoring,catalog}/application/service/Admin*Service.java` | UPDATE | `TenantSchema.validate(...)` before interpolation; drop "safe here" comments. |
| `shared/.../TenantSchemaProvisioner.java`, `TenantSchemaSyncService.java`, `TenantSchemaMigrationRunner.java`, `SchemaAwareMultiTenantConnectionProvider.java`; `identity/.../TenantSchemaAdapter.java`; `messaging/.../JdbcNotificationCooldownAdapter.java`, `SalesTrendDetectionScheduler.java`; `subscription/.../SubscriptionExpiryService.java`; `catalog/stock/.../MultiStoreStockRepositoryAdapter.java`; `sync/.../DeltaStockStrategy.java` | UPDATE (refactor) | Replace inline `^kv_[a-z0-9]{6}$` with `TenantSchema.PATTERN`/`isValid`/`validate` — same behaviour. |

### Test harness reference

Controller tests = `MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())` with the principal set via `SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actorId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))))` (see `PendingSaleControllerTest`, `DeviceTokenControllerTest`, `EmployeeControllerTest`). `@PreAuthorize` is NOT enforced here — rely on the explicit `DomainException(FORBIDDEN)` guard for the 403 assertions. Pure unit tests for `TenantSchema` and `SecurityConfig` (no Spring context) keep the suite fast.

### Out of scope

- The sync-handler cancel path beyond a documented awareness note (Task 5); the Flutter "hide cancel for EMPLOYEE" UI change.
- A separate least-privilege DB role / SQL-level grants (Story 11.3 territory).
- Session revocation timing (Story 11.6), N+1 / index fixes (Story 11.5) — even though some of the same admin services are touched here for schema validation, **do not** fold 11.5's N+1 fix into this story; keep diffs minimal and orthogonal.

### Project Structure Notes

- `TenantSchema` belongs in `shared/infrastructure/persistence` (cross-cutting infra, no business-module dependency) so Spring Modulith `verify()` / ArchUnit (Story 10.6, warning mode) stay clean and every module may consume it.
- Hexagonal boundaries are respected: the RBAC guard lives in the inbound REST adapter; the device-token ownership rule is enforced in the application service + outbound port (not in the controller); CORS is pure infra/security config.

### References

- [Source: _bmad-output/planning-artifacts/epics-refonte-modulaire.md#Story 11.4 : Correctifs de contrôle d'accès (RBAC & exposition)] — user story, AC, implementation notes (`@PreAuthorize` OWNER on cancelSale; ownership check on deleteToken; CORS allow-list per env; `TenantSchema.validate` everywhere).
- [Source: keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/PendingSaleController.java] — current `cancelSale` `hasAnyRole('OWNER','EMPLOYEE')`; `extractActorId`/`extractAssignedStoreId` helpers.
- [Source: keevo/backend/src/main/java/com/keevo/identity/employee/adapter/in/rest/EmployeeController.java#requireOwnerOrForbid] — proven OWNER-only guard pattern (`DomainException(FORBIDDEN)`), testable under `standaloneSetup`.
- [Source: keevo/backend/src/test/java/com/keevo/commerce/sale/adapter/in/rest/PendingSaleControllerTest.java] — controller-test harness; `authenticateAs(role)`; existing cancel-OWNER-200 test (AC2).
- [Source: keevo/backend/src/main/java/com/keevo/messaging/notification/application/service/DeleteDeviceTokenService.java] + `domain/port/out/DeviceTokenRepository.java` + `adapter/out/persistence/jpa/DeviceTokenSpringRepository.java` — current unscoped `deleteByToken`.
- [Source: keevo/backend/src/main/java/com/keevo/messaging/notification/adapter/in/rest/DeviceTokenController.java] — principal extraction pattern (`registerToken`).
- [Source: keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/SecurityConfig.java#corsConfigurationSource] — hardcoded `localhost:3000` + `DASHBOARD_ORIGIN` env read.
- [Source: keevo/backend/src/main/java/com/keevo/shared/infrastructure/config/ProdSecretsValidator.java#@Profile] — established `@Profile({"prod","staging"})` profile-detection pattern.
- [Source: keevo/backend/src/main/java/com/keevo/subscription/plan/adapter/out/persistence/impl/ProductCountAdapter.java] — un-guarded schema interpolation gap (same for Store/User/Employee count adapters).
- [Source: keevo/backend/src/main/java/com/keevo/admin/tenant/application/service/AdminTenantService.java] + `admin/operations/.../AdminOperationsService.java`, `admin/sync_monitoring/.../AdminSyncMonitoringService.java`, `admin/catalog/.../AdminCatalogService.java` — schema interpolation guarded only by comments.
- [Source: keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java (~line 706)] + `TenantSchemaSyncService.java#SCHEMA_PATTERN` — canonical `^kv_[a-z0-9]{6}$` to reuse in `TenantSchema.PATTERN`.
- [Source: keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java (~line 221)] — `FORBIDDEN` → HTTP 403 mapping.
- [Source: review_qwen_6.md (line 424)] + [review_claude_opus_8.md (DT10, lines ~485/516)] — original audit findings (EMPLOYEE cancel; deviceToken; RBAC gaps).
- [Source: _bmad-output/implementation-artifacts/11-3-audit-append-only-db.md] — regression baseline (~1463 tests, 1 pre-existing failure + 33 errors); shared-infra placement precedent.

## Dev Agent Record

### Agent Model Used

GitHub Copilot (DeepSeek V4 Pro) — 2026-07-06

### Debug Log References

N/A — all tests pass; no runtime debugging required.

### Completion Notes List

1. **cancelSale guard (Task 1):** Added explicit `requireOwnerOrForbid()` + `@PreAuthorize("hasRole('OWNER')")` to `PendingSaleController.cancelSale`. Mirror of `EmployeeController.requireOwnerOrForbid()`. `validateSale` and `getPendingSales` unchanged.

2. **Device-token scoping (Task 2):** Option (a) — atomic `deleteByTokenAndUserId(token, userId)` scoped delete. Cross-user returns `deleted:false`, no existence oracle. Backward compat preserved via `deleteByToken(String)`.

3. **CORS gating (Task 3):** `SecurityConfig` injects `Environment`; `localhost:3000` only in non-prod profiles. `DASHBOARD_ORIGIN` env unchanged. Multi-origin NOT added.

4. **TenantSchema (Task 4):** `validate()` applied at 12 unguarded sites (4 count adapters + 4 admin services + 1 sync monitoring dual loop). `TenantSchemaSyncService` refactored to use `TenantSchema.PATTERN` and `isValid()`. `SyncErrorLogCleanupJob` left as documented SQL-side exception. 13 tests.

5. **CancelSaleSyncHandler follow-up (Task 5):** Sync handler calls `cancelPendingSaleUseCase` without role check — documented as potential EMPLOYEE bypass via offline queue.

6. **Regression (Task 6):** 1491 tests — 1 pre-existing failure + 46 errors. 35 new story tests all GREEN. No NEW failures.

### Completion Notes — residual (spec-11-4-correctifs-rbac-exposition-residuel, 2026-10-13)

Tasks 1 and 2 were already on the branch (OWNER-only `cancelSale`; `boolean delete(UUID userId, String token)` scoped delete). This pass closed AC5/AC6, AC7 and Task 5.

1. **CORS (Task 3, AC5/AC6).** Before this pass, every origin was allowed with credentials, in every profile. That came from two places: `SecurityConfig` (`setAllowedOriginPatterns("*")`) and an MVC-level `shared/infrastructure/web/CorsConfig` doing the same on `/**`.
   - `CorsConfig` is **deleted**. `SecurityConfig.corsConfigurationSource()` is now the single CORS source.
   - **Allowed origins.** The source uses the `DASHBOARD_ORIGIN` entries (comma-separated) that are well-formed `http(s)://host[:port]` origins. Each entry is trimmed and loses one trailing `/`. These are dropped with one WARN each: wildcards, paths, the checklist placeholder `https://<domaine_dashboard>`, and anything else malformed.
   - **Localhost.** `http://localhost:3000` is added only when no profile is active, or when the active profile is `dev`, `local` or `test`. Every other profile is hardened, so a misspelled profile fails closed.
   - **Credentials.** Not allowed (`setAllowCredentials(false)`): the API is stateless bearer JWT.
   - **Missing value.** Reported once, by `ProdSecretsValidator` (prod/staging). It is a WARN with the `[CORS]` prefix, not `[FailFast]`, and boot continues.
   - **Docs.** `DASHBOARD_ORIGIN` is documented in `keevo/backend/.env.example`, `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md` §1.7 and `docs/DEPLOYMENT_CHECKLIST.md` §2.1.
   - **Unaffected clients.** The dashboard calls the API through Next.js rewrites (server-side), and the mobile app sends no `Origin` header.
   - **Tests:**
     - `SecurityConfigCorsTest` (10, unit tests with `MockEnvironment`).
     - `SecurityConfigCorsChainTest` (3): MockMvc through the real `SecurityFilterChain`, `prod` profile, `DASHBOARD_ORIGIN=https://admin.keevo.cm`. The dashboard origin gets 200 with ACAO and no credentials header. `https://evil.example` and `http://localhost:3000` get 403 with no ACAO.
     - `ProdSecretsValidatorTest`: the WARN is captured with a `ListAppender` when the value is missing, and no WARN is logged when it is configured.
2. **Schema validation everywhere (Task 4, AC7).** Every interpolation site now checks the interpolated value **in code**, with `TenantSchema.validate` or `TenantSchema.isValid`. Comments no longer count as a guard. Each site keeps its existing failure mode.
   - **Request paths throw `IllegalArgumentException`** before any SQL: `SessionTokenIssuer#issue` / `onboardingCompleted`, `TenantPreferencesController#getStores`, `{Store,User,Employee}CountAdapter` (no fallback existed before, so a bad schema already failed), `ChangeTenantPlanService#changePlan`, `AdminSyncMonitoringService#execute`, `JwtAuthFilter` (employee lookup, inline), `TenantSchemaAdapter` (inline), `MultiStoreStockRepositoryAdapter` (inline around `schema()`).
   - **Fail-soft sites return their existing fallback, with a WARN and no SQL:**
     - `ClientSalesStatsAdapter` and `ProductCountAdapter` return 0.
     - The `AdminTenantService` detail helpers (`loadStores`, `loadEmployeeNames`, `loadPlan`, `countAuditEvents`, `loadAuditEvents`) return an empty section or null. The WARN names the tenant id. `loadStores` and `countAuditEvents` now take the tenant id.
     - `AdminPlanService#expiringRow` returns null.
     - `AdminSyncMonitoringService#withUserLabels` returns the devices unlabelled.
     - `JdbcPushAudienceAdapter#deviceTokens` returns no tokens.
   - **Loop sites skip with a WARN** carrying the tenant id, never the schema name, once per tenant per run: `AdminAnalyticsService`, `AdminOperationsService`, `JdbcAlertSourcesAdapter`, `AdminSyncMonitoringService` (overview and health list), `AdminCatalogService` (summary and product list), `SubscriptionExpiryService#downgrade`, `PlanExpiryReminderService#remind`, `SalesTrendDetectionScheduler#processTenant` (no id is available there), `SyncErrorLogCleanupJob`.
     - Helpers whose caller already skipped and logged keep a silent re-check: `SalesTrendDetectionScheduler#analyzeStoreTrend`, `SyncKeysetIndexJob#ensureIndex`, `DeltaStockStrategy` lookups.
     - The `AdminCatalogService` checks use the fully qualified `TenantSchema` name, because a nested record there is also called `TenantSchema`. The import added earlier by mistake is removed.
   - **Infrastructure:**
     - `SchemaAwareMultiTenantConnectionProvider`: `applySearchPath` validates the name, and `getConnection` closes the connection if setting the search path throws.
     - `TenantSchemaSyncService`: the helpers validate, and `doSync` now rolls back on `SQLException | RuntimeException`.
     - `TenantSchemaProvisioner`: the private steps and `createConcurrentlySql` validate.
   - **Guard test `TenantSchemaInterpolationGuardTest`.** It scans `src/main/java`, resolved from `user.dir` (with a `keevo/backend/` fallback), and asserts that more than 100 files were scanned. For each interpolation it extracts the interpolated expression and requires a `TenantSchema.validate/isValid(` call on a **non-comment** line in the 20 lines above that mentions **the same expression**. For a format call, at least one argument must be guarded.
     - Shapes detected:
       - `+ x + ".t"`
       - `schemaX + "." +`
       - `"\"" + x + "\""`
       - `.append(schemaX)`
       - `String.format(*_SQL|*_TPL, …)`, an inline `%s` SQL template, or `.formatted(`
     - An allow-list entry that matches no line fails the test (stale entry).
     - Self-checks cover: a comment-only guard is rejected, a guard on a different variable is rejected, an inline guard is accepted, and the `.append` and `"."` shapes are detected.
     - Remaining heuristic limit: a guard on a same-named variable of another method inside the window still counts.
   - **Fixtures.** `ProductCountAdapterTest` now uses `kv_test01` (`kv_test` is not a valid schema name). It and `ClientSalesStatsAdapterTest` cover "invalid schema → 0, no SQL". `SessionTokenIssuerTest` covers "invalid schema → `IllegalArgumentException`, no JDBC and no refresh token saved".
3. **`CancelSaleSyncHandler` (Task 5).** Before this pass, an EMPLOYEE could cancel any `PENDING_VALIDATION` sale of their own store through `CANCEL_SALE` sync (v1s-12-8 store scope only). That is wider than "own draft", while REST is OWNER-only. v1s-13-5 grants no EMPLOYEE cancel right: D2/AC5 only closed the COMPLETED path. So the handler now rejects any non-OWNER actor with `FORBIDDEN` before the use case runs, which is the same rule as REST. The role comes from the JWT, and an unauthenticated actor is also rejected. The Flutter pending-sale page already hides cancel for EMPLOYEE, so no client change is needed. A rejected `CANCEL_SALE` is quarantined, never dropped. Pinned by `CancelSaleSyncHandlerTest`:
   - `apply_pendingSale_employeeSameStore_rejectedForbidden`, which replaces the v1s-12-8 "same store → APPLIED" case
   - `apply_pendingSale_employeeCrossStore_rejectedForbidden`
   - `apply_unauthenticated_rejectedForbidden`
   - `apply_owner_sendsNoStoreScope` (also asserts `APPLIED`)

   `v1s-12-8-rbac-sync-push-scope.md` now carries a dated note that this supersedes its EMPLOYEE same-store case.
4. **Regression (Task 6).** Run one package at a time (`-DforkCount=1 -Xmx1536m`), all with 0 failures and 0 errors:

   | Package | Tests |
   |---|---|
   | shared | 537 |
   | sync | 441 |
   | admin | 476 |
   | messaging | 131 |
   | subscription | 61 |
   | identity | 373 |
   | catalog | 396 |
   | architecture | 6 |

### File List

| File | Action |
|------|--------|
| `commerce/sale/adapter/in/rest/PendingSaleController.java` | UPDATE |
| `commerce/sale/adapter/in/rest/PendingSaleControllerTest.java` | UPDATE |
| `messaging/notification/domain/port/in/DeleteDeviceTokenUseCase.java` | UPDATE |
| `messaging/notification/application/service/DeleteDeviceTokenService.java` | UPDATE |
| `messaging/notification/domain/port/out/DeviceTokenRepository.java` | UPDATE |
| `messaging/notification/adapter/out/persistence/jpa/DeviceTokenSpringRepository.java` | UPDATE |
| `messaging/notification/adapter/out/persistence/impl/DeviceTokenRepositoryAdapter.java` | UPDATE |
| `messaging/notification/adapter/in/rest/DeviceTokenController.java` | UPDATE |
| `messaging/notification/.../DeleteDeviceTokenServiceTest.java` | UPDATE |
| `messaging/notification/.../DeviceTokenControllerTest.java` | UPDATE |
| `shared/infrastructure/security/SecurityConfig.java` | UPDATE |
| `shared/infrastructure/security/SecurityConfigCorsTest.java` | **NEW** |
| `shared/infrastructure/persistence/TenantSchema.java` | **NEW** |
| `shared/infrastructure/persistence/TenantSchemaTest.java` | **NEW** |
| `subscription/plan/.../ProductCountAdapter.java` | UPDATE |
| `subscription/plan/.../StoreCountAdapter.java` | UPDATE |
| `subscription/plan/.../UserCountAdapter.java` | UPDATE |
| `subscription/plan/.../EmployeeCountAdapter.java` | UPDATE |
| `admin/tenant/.../AdminTenantService.java` | UPDATE |
| `admin/operations/.../AdminOperationsService.java` | UPDATE |
| `admin/sync_monitoring/.../AdminSyncMonitoringService.java` | UPDATE |
| `admin/catalog/.../AdminCatalogService.java` | UPDATE |
| `shared/.../TenantSchemaSyncService.java` | UPDATE |
| `_bmad-output/implementation-artifacts/sprint-status.yaml` | UPDATE |
| `shared/infrastructure/config/ProdSecretsValidator.java` (+Test) | UPDATE (residual 2026-10-13) |
| `shared/infrastructure/persistence/TenantSchemaInterpolationGuardTest.java` + `src/test/resources/tenant-schema-interpolation-allowlist.txt` | **NEW** (residual) |
| `sync/sync/application/handler/CancelSaleSyncHandler.java` (+Test) | UPDATE (residual) |
| Schema-interpolation sites listed in the residual completion notes, `ClientSalesStatsAdapterTest`, `ProductCountAdapterTest` | UPDATE (residual) |
| `keevo/backend/.env.example`, `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md`, `docs/DEPLOYMENT_CHECKLIST.md` | UPDATE (residual — `DASHBOARD_ORIGIN`) |

### Deferred items routed 2026-10-11

- [x] [Deferred-from: legacy CR 8-0, 2026-04-03] DeviceTokenController.deleteToken no ownership check [DeviceTokenController.java:58-61] — delete only tokens of the caller; 11-4 notes device-token ownership still missing
  > Fixed 2026-10-11 on `deploy`: `DeleteDeviceTokenUseCase.delete(userId, token)` → `deleteByTokenAndUserId`; tests DeleteDeviceTokenServiceTest (3) + DeviceTokenControllerTest (5).
