# Story 1.7: Multi-Tenant User Memberships & Two-Step Login

Status: done

## Story

As a user (Simon or Loïc),
I want to be able to belong to multiple Keevo tenants simultaneously with any role combination
(e.g., OWNER of my clothing shop AND OWNER of my electronics business, or OWNER of my shop AND EMPLOYEE in a colleague's shop),
so that a single phone number uniquely identifies my global identity while my roles and permissions are correctly scoped per tenant at login.

## Context & Rationale

**Architectural issue discovered during implementation of Story 1.3:**
The current model has `public.users.tenant_id` (NOT NULL), coupling a user's global identity to exactly one tenant. This makes it impossible for a person to hold any of these valid multi-tenant combinations:

| Combination | Memberships |
|---|---|
| OWNER of shop A + EMPLOYEE at shop B | `(user, KV-A, OWNER)` + `(user, KV-B, EMPLOYEE)` |
| OWNER of 2 separate businesses (clothing + electronics) | `(user, KV-A, OWNER)` + `(user, KV-B, OWNER)` |
| EMPLOYEE at two different merchants | `(user, KV-A, EMPLOYEE)` + `(user, KV-B, EMPLOYEE)` |

This breaks **FR65** (invite employees by phone/WhatsApp link) and **FR35** (assign employee to store) because an invited employee who is already an OWNER cannot register a second account with the same phone number. It also blocks any future scenario where Simon expands into a second independent business vertical.

**Key constraint — UNIQUE(user_id, tenant_id):** a person can hold only one role PER tenant. Two different roles on the same tenant is not allowed. Cross-tenant memberships are unlimited.

**Distinction important (confirmed by Toor):**
- Epic 3 **multi-boutiques** = a SINGLE tenant has multiple stores/warehouses → this story does NOT affect that
- THIS story = ONE PERSON can belong to MULTIPLE TENANTS → cross-tenant identity

**Solution: Approach B — Two-step login with auto-select**
- `public.users` becomes a pure global identity table (phone + passwordHash + lockout state — NO `tenant_id`)
- New `public.user_tenant_memberships` table holds the N:N relation (userId ↔ tenantId + role)
- Login is split into two steps:
  1. `POST /auth/login` → verifies credentials → returns `loginToken` (5min) + memberships list
  2. `POST /auth/select-tenant` → validates loginToken + tenantCode → returns full scoped JWT
- **UX optimization (Flutter):** if user has only 1 membership (99% of OWNER-only users), Flutter automatically calls step 2 without showing any picker → **zero UX change** for the overwhelming majority of users in V1

---

## Acceptance Criteria

**AC1 — `public.user_tenant_memberships` table exists and is populated at registration**
- **Given** a new user completes registration (Story 1.2 flow)
- **When** `RegistrationService.register()` executes
- **Then** the user is saved in `public.users` WITHOUT a `tenant_id` column
- **And** a new row is inserted in `public.user_tenant_memberships`: `(userId, tenantId, role='OWNER', is_active=true)`
- **And** `tenantSchemaPort.assignOwnerRole(schemaName, userId)` still runs (inserts into `kv_xxx.user_roles` — unchanged)
- **And** the existing end-to-end registration behavior is fully preserved (HTTP 201, JWT returned, tenant provisioned)

**AC2 — `POST /api/v1/auth/login` returns loginToken + memberships list**
- **Given** Simon has a registered account and belongs to 1 tenant
- **When** he `POST /api/v1/auth/login` with `{ "phoneNumber": "+237600000001", "password": "Secret@123!" }`
- **Then** HTTP 200 is returned with:
  ```json
  {
    "loginToken": "eyJ...",
    "memberships": [
      {
        "tenantCode": "KV-ABC123",
        "tenantName": "Boutique Simon",
        "role": "OWNER",
        "schemaName": "kv_abc123"
      }
    ]
  }
  ```
- **And** the `loginToken` is a short-lived RS256 JWT (TTL: 5 minutes) with claim `"scope": "login_pending"` and `userId` as subject
- **And** the `loginToken` is NOT usable for accessing protected API endpoints (scope mismatch must be rejected by `JwtAuthFilter`)

**AC3 — `POST /api/v1/auth/select-tenant` returns full scoped JWT**
- **Given** Simon has a valid `loginToken` and `tenantCode: "KV-ABC123"`
- **When** he `POST /api/v1/auth/select-tenant` with `{ "loginToken": "eyJ...", "tenantCode": "KV-ABC123" }`
- **Then** HTTP 200 is returned with the same structure as the current `LoginResponse`:
  ```json
  {
    "accessToken": "eyJ...",
    "refreshToken": "...",
    "expiresIn": 86400,
    "userId": "...",
    "tenantId": "kv_abc123",
    "role": "OWNER"
  }
  ```
- **And** the `accessToken` is a full RS256 JWT (TTL: 24h) with claim `"scope": "access"` (or no scope claim — to be backward compatible with JwtAuthFilter)

**AC4 — loginToken security: cannot be used as access token**
- **Given** an attacker has obtained a `loginToken`
- **When** they attempt to call any protected endpoint (e.g., `GET /api/v1/subscriptions/status`) with the `loginToken` as Bearer
- **Then** the backend returns HTTP 401 with `{ "domainCode": "TOKEN_INVALID" }`
- **And** `JwtAuthFilter` detects that `scope == "login_pending"` and rejects the request

**AC5 — Existing lockout, INVALID_CREDENTIALS, and ACCOUNT_LOCKED behavior unchanged**
- **Given** login step 1 (`/auth/login`)
- **When** credentials are wrong or account is locked
- **Then** the SAME HTTP 401 responses (`INVALID_CREDENTIALS`, `ACCOUNT_LOCKED`) are returned as before
- **And** failed attempt counting still works correctly

**AC6 — SUPER_ADMIN (AdminAccountInitializer) creates membership on startup**
- **Given** the application starts for the first time
- **When** `AdminAccountInitializer` runs
- **Then** the SUPER_ADMIN user is saved in `public.users` WITHOUT `tenant_id`
- **And** a `user_tenant_memberships` row is created for the admin: `(adminUserId, adminTenantId='00000000-0000-0000-0000-000000000000', role='SUPER_ADMIN', is_active=true)`
- **And** the initializer remains idempotent (running again does nothing)

**AC7 — `POST /auth/select-tenant` is publicly accessible (no JWT required)**
- **Given** `/api/v1/auth/select-tenant`
- **When** `JwtAuthFilter.shouldNotFilter()` is evaluated
- **Then** this path is in the public whitelist, alongside `/auth/login`, `/auth/register`, `/auth/refresh`

**AC8 — Flutter: auto-select for single-membership users (zero UX change)**
- **Given** the Flutter app receives a login response with `memberships.length == 1`
- **When** the `AuthRepository.login()` method processes the response
- **Then** it automatically calls `/auth/select-tenant` with the single membership's `tenantCode` without displaying a picker screen
- **And** the final `accessToken` + `refreshToken` are stored in `flutter_secure_storage`
- **And** navigation to the home screen proceeds exactly as before

**AC9 — Flutter: tenant picker for multi-membership users (future path)**
- **Given** the Flutter app receives a login response with `memberships.length > 1`
- **When** the `AuthRepository.login()` method processes the response
- **Then** a `TenantMembership` list is emitted via Riverpod state
- **And** the router navigates to `TenantPickerScreen` (simple list of tenants with name + role)
- **And** selecting a tenant triggers `/auth/select-tenant` call → stores tokens → navigates home

**AC10 — Dangling `tenant_id` column in `public.users` (acceptable debt)**
- `ddl-auto=update` does NOT drop columns in Hibernate — the `tenant_id` column will remain in the DB as a dangling nullable column after this story
- **Given** this is V1 and no proper Flyway migrations exist yet
- **When** the new `UserJpaEntity` no longer maps `tenant_id`
- **Then** this is acceptable: the column exists in the DB, is completely ignored by Hibernate, all existing test data still works
- **And** a TODO comment is added to `UserJpaEntity` documenting the dangling column and the Story 1.7 change

**AC11 — All existing 145 backend tests remain GREEN**
- **Given** all stories 1.1–1.6 tests (145 backend tests)
- **When** the implementation is complete
- **Then** ALL 145 tests continue to pass
- **And** at least 15 new unit tests cover the new login flow (step 1 + step 2 + lockout + scope rejection)

**AC12 — OWNER of multiple tenants: login returns all memberships**
- **Given** Simon has registered two separate businesses: `KV-ABC123` (clothing) and `KV-XYZ789` (electronics)
- **And** his `user_tenant_memberships` contains two rows: `(simon, KV-ABC123, OWNER)` + `(simon, KV-XYZ789, OWNER)`
- **When** he `POST /api/v1/auth/login` with his credentials
- **Then** HTTP 200 is returned with `memberships` array containing **both** entries:
  ```json
  {
    "loginToken": "eyJ...",
    "memberships": [
      { "tenantCode": "KV-ABC123", "tenantName": "Boutique Vêtements", "role": "OWNER", "schemaName": "kv_abc123" },
      { "tenantCode": "KV-XYZ789", "tenantName": "Boutique Électronique", "role": "OWNER", "schemaName": "kv_xyz789" }
    ]
  }
  ```
- **And** `POST /api/v1/auth/select-tenant` with `tenantCode: "KV-XYZ789"` returns a JWT scoped to `kv_xyz789` with `role: OWNER`
- **And** selecting `KV-ABC123` instead returns a JWT scoped to `kv_abc123` — each JWT is completely isolated

**AC13 — UNIQUE constraint enforcement: cannot have two rows for same (user, tenant)**
- **Given** Simon already has membership `(simon, KV-ABC123, OWNER)`
- **When** any code path attempts to insert a second membership row for `(simon, KV-ABC123, ANY_ROLE)`
- **Then** the database rejects it with a unique constraint violation
- **And** `UserMembershipRepository.save()` propagates a `DomainException(MEMBERSHIP_ALREADY_EXISTS)` (or equivalent) rather than a raw SQL error

---

## Tasks / Subtasks

### Backend

- [x] **Task 1 — Domain model changes** (AC1, AC6)
  - [x] 1.1 — Modify `User.java`: remove `tenantId` field, remove `newOwner(phone, hash, tenantId)` factory and replace with `newOwner(phone, hash)`, update all constructors and `withLockoutState()` copies
  - [x] 1.2 — Create `UserTenantMembership.java` in `identity/auth/domain/model/`: record with `UUID id, UUID userId, UUID tenantId, String role, boolean active, Instant createdAt`
  - [x] 1.3 — Create `UserMembershipInfo.java` in `identity/auth/domain/model/`: projection record for login response: `String tenantCode, String tenantName, String role, String schemaName`

- [x] **Task 2 — JPA entity changes** (AC1, AC10)
  - [x] 2.1 — Modify `UserJpaEntity.java`: remove `tenant_id` field and `getTenantId()` getter. Add TODO comment: `// NOTE Story 1.7: tenant_id column still exists in DB (dangling) — Hibernate ddl-auto=update does not drop columns. Column will be removed in a future Flyway migration.`
  - [x] 2.2 — Update `UserJpaEntity` constructor and all usages in `UserRepositoryAdapter` (remove tenantId parameter from JpaEntity creation)
  - [x] 2.3 — Create `UserTenantMembershipJpaEntity.java` in `identity/auth/adapter/out/persistence/entity/`:
    ```java
    @Entity
    @Table(name = "user_tenant_memberships",
           uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "tenant_id"}))
    public class UserTenantMembershipJpaEntity extends JpaBaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "tenant_id", nullable = false) private UUID tenantId;
        @Column(name = "role", nullable = false, length = 30) private String role;
        @Column(name = "is_active", nullable = false) private boolean active;
    }
    ```
    Hibernate `ddl-auto=update` will auto-create this table on next startup.
  - [x] 2.4 — Create `UserTenantMembershipSpringRepository.java` (JpaRepository):
    ```java
    List<UserTenantMembershipJpaEntity> findByUserIdAndActiveTrue(UUID userId);
    Optional<UserTenantMembershipJpaEntity> findByUserIdAndTenantId(UUID userId, UUID tenantId);
    ```

- [x] **Task 3 — Port changes** (AC1)
  - [x] 3.1 — Modify `UserRepository` port (`identity/auth/domain/port/out/`): remove references to `tenantId` from any method that no longer needs it. `findByPhoneNumber()` and `save()` remain. Add new method: `List<UserMembershipInfo> findMembershipsWithTenantInfo(UUID userId)`
  - [x] 3.2 — Create `UserMembershipRepository` port (`identity/auth/domain/port/out/`):
    ```java
    public interface UserMembershipRepository {
        UserTenantMembership save(UserTenantMembership membership);
        List<UserTenantMembership> findByUserId(UUID userId);
        Optional<UserTenantMembership> findByUserIdAndTenantId(UUID userId, UUID tenantId);
    }
    ```

- [x] **Task 4 — Repository adapter changes** (AC1, AC3)
  - [x] 4.1 — Modify `UserRepositoryAdapter.java`:
    - Remove `tenantId` from `toDomain()` mapping (user domain has no tenantId now)
    - Remove `tenantId` from JpaEntity construction in `save()`
    - Implement `findMembershipsWithTenantInfo(userId)`: join `user_tenant_memberships` + `tenants` using JdbcTemplate with schema-qualified query on `public.user_tenant_memberships` and `public.tenants` → return `List<UserMembershipInfo>`
  - [x] 4.2 — Create `UserMembershipRepositoryAdapter.java` in `identity/auth/adapter/out/persistence/impl/`: delegates to `UserTenantMembershipSpringRepository`

- [x] **Task 5 — New domain ports/commands for select-tenant** (AC3)
  - [x] 5.1 — Create `SelectTenantCommand.java` in `identity/auth/domain/port/in/`:
    ```java
    public record SelectTenantCommand(String loginToken, String tenantCode) {}
    ```
  - [x] 5.2 — Create `SelectTenantUseCase.java` in `identity/auth/domain/port/in/`:
    ```java
    public interface SelectTenantUseCase {
        AuthTokens select(SelectTenantCommand command);
    }
    ```
  - [x] 5.3 — Create `LoginSessionResult.java` in `identity/auth/domain/port/in/`:
    ```java
    public record LoginSessionResult(
        String loginToken,
        List<UserMembershipInfo> memberships
    ) {}
    ```
  - [x] 5.4 — Modify `AuthenticateUserUseCase.java`: change return type from `AuthTokens` to `LoginSessionResult`

- [x] **Task 6 — Service changes** (AC1, AC2, AC3, AC4, AC5, AC6)
  - [x] 6.1 — Modify `RegistrationService.java`:
    - Remove `tenant_id` from `User.newOwner(phone, hash)` call
    - After `userRepository.save(savedUser)`: also call `userMembershipRepository.save(UserTenantMembership.create(savedUser.getId(), tenant.getId(), "OWNER"))`
    - Keep `tenantSchemaPort.assignOwnerRole(schema, userId)` unchanged
  - [x] 6.2 — Modify `AuthenticationService.java` — implementing `AuthenticateUserUseCase` → now returns `LoginSessionResult`:
    - Steps 1–4 (load user, check lockout, verify password, reset lockout) remain identical
    - **Remove** step 5 (tenant lookup by user.getTenantId()) — user no longer has tenantId
    - Step 5 (new): load memberships: `userRepository.findMembershipsWithTenantInfo(user.getId())`
    - Step 6 (new): generate `loginToken` — short-lived RS256 JWT (5min TTL), claims: `{ sub: userId, scope: "login_pending" }`
    - Return `new LoginSessionResult(loginToken, memberships)` — NO `RefreshToken` created here (moved to step 2)
    - The `UserAuthenticatedEvent` is now published in `SelectTenantService` (not here — we don't know the tenant yet)
  - [x] 6.3 — Create `SelectTenantService.java` in `identity/auth/application/service/`:
    - Implements `SelectTenantUseCase`
    - Steps:
      1. Validate `loginToken`: parse RS256 JWT → verify `scope == "login_pending"` → extract `userId`
      2. Load membership: `userMembershipRepository.findByUserIdAndTenantId(userId, tenantIdFromCode)` — requires resolving `tenantCode` → `tenantId` via `tenantRepository.findByCode(tenantCode)`
      3. Verify membership `isActive == true`
      4. Load user from `userRepository.findById(userId)` (for role + lockout state)
      5. Load tenant from `tenantRepository.findById(membership.tenantId())`
      6. Generate full `accessToken` RS256 (24h), `scope: "access"` (or no scope claim)
      7. Generate `refreshToken`, persist in `public.refresh_tokens`
      8. Publish `UserAuthenticatedEvent`
      9. Return `AuthTokens`
  - [x] 6.4 — Modify `AdminAccountInitializer.java`:
    - Remove `tenant_id` from user creation call
    - After creating admin user: also call `userMembershipRepository.save(...)` for the admin membership
    - Idempotency guard: check if membership already exists before creating

- [x] **Task 7 — JWT: loginToken generation and scope validation** (AC2, AC4)
  - [x] 7.1 — Modify `JwtTokenProvider.java`: add `generateLoginToken(UUID userId)` method → generates RS256 JWT with `exp = now + 5min`, claims `{ sub: userId.toString(), scope: "login_pending" }`
  - [x] 7.2 — Add `extractScope(String token)` method to `JwtTokenProvider` (returns the `scope` claim or null)
  - [x] 7.3 — Modify `JwtAuthFilter.java`: after parsing the JWT, extract `scope`. If `scope == "login_pending"` → treat as invalid access token → return 401 `TOKEN_INVALID` (a login token must never grant API access)

- [x] **Task 8 — Controller and DTO changes** (AC2, AC3, AC7)
  - [x] 8.1 — Create `LoginSessionResponse.java` in `identity/auth/adapter/in/rest/dto/`:
    ```java
    public record LoginSessionResponse(
        String loginToken,
        List<MembershipDto> memberships
    ) {
        public record MembershipDto(String tenantCode, String tenantName, String role, String schemaName) {}
    }
    ```
  - [x] 8.2 — Create `SelectTenantRequest.java` in `identity/auth/adapter/in/rest/dto/`:
    ```java
    public record SelectTenantRequest(
        @NotBlank String loginToken,
        @NotBlank String tenantCode
    ) {}
    ```
  - [x] 8.3 — Modify `AuthController.java`:
    - `POST /auth/login` → calls `authenticateUserUseCase.authenticate(...)` → maps `LoginSessionResult` to `LoginSessionResponse` → HTTP 200
    - Add `POST /auth/select-tenant` → calls `selectTenantUseCase.select(new SelectTenantCommand(req.loginToken(), req.tenantCode()))` → maps `AuthTokens` to existing `LoginResponse` → HTTP 200
  - [x] 8.4 — Modify `JwtAuthFilter.shouldNotFilter()`: add `/api/v1/auth/select-tenant` to the public paths whitelist

- [x] **Task 9 — TenantRepository: add findByCode** (AC3)
  - [x] 9.1 — Add `Optional<Tenant> findByCode(String tenantCode)` to `TenantRepository` port (if not already present)
  - [x] 9.2 — Implement in `TenantRepositoryAdapter` using `TenantSpringRepository.findByCode(code)`
  - [x] 9.3 — Add `Optional<TenantJpaEntity> findByCode(String code)` to `TenantSpringRepository`

### Flutter

- [x] **Task 10 — Update AuthRepository** (AC8, AC9)
  - [x] 10.1 — Update `LoginResponse` Dart model (or create `LoginSessionResponse` model): add `loginToken: String`, `memberships: List<MembershipDto>`
  - [x] 10.2 — Create `MembershipDto` Dart model with `tenantCode, tenantName, role, schemaName`
  - [x] 10.3 — Modify `AuthRepository.login()`:
    - Step 1: POST `/auth/login` → parse `LoginSessionResponse`
    - Step 2: if `memberships.length == 1` → immediately POST `/auth/select-tenant` → store tokens → return (transparent)
    - If `memberships.length > 1` → store `loginToken` + `memberships` in a temp state → return `NeedsTenantSelection` result variant
  - [x] 10.4 — Modify `AuthRepository.selectTenant(loginToken, tenantCode)`: POST `/auth/select-tenant` → store tokens → return
  - [x] 10.5 — Create `TenantPickerScreen` widget (simple `ListView` of tenant tiles showing `tenantName` + `role` badge). Each tile calls `authNotifier.selectTenant(membership.tenantCode)`
  - [x] 10.6 — Update router: add `/tenant-picker` route guarded by `NeedsTenantSelection` auth state

### Tests

- [x] **Task 11 — Backend unit tests** (AC1–AC7, AC11)
  - [x] 11.1 — `AuthenticationServiceTest`: rewrite all existing tests — now verify `LoginSessionResult` returned (loginToken + memberships), NOT `AuthTokens` directly. Keep lockout / invalid credentials tests.
  - [x] 11.2 — `SelectTenantServiceTest`: new test class
    - Valid loginToken + valid tenantCode → returns `AuthTokens`
    - Expired loginToken → `TOKEN_EXPIRED`
    - Invalid scope (access token used as loginToken) → `TOKEN_INVALID`
    - Unknown tenantCode → `TENANT_NOT_FOUND`
    - Inactive membership → `UNAUTHORIZED`
  - [x] 11.3 — `RegistrationServiceTest`: verify membership is created after user registration (add mock for `UserMembershipRepository`)
  - [x] 11.4 — `AdminAccountInitializerTest`: update mock expectations — user saved without `tenantId`, membership saved separately
  - [x] 11.5 — `AuthControllerTest`: update `POST /auth/login` assertion to expect `loginToken + memberships[]`, add `POST /auth/select-tenant` tests
  - [x] 11.6 — `AuthenticationServiceTest`: add case **OWNER of 2 tenants** — verify `loginToken` response contains 2 memberships both with `role=OWNER` (AC12)
  - [x] 11.7 — `SelectTenantServiceTest`: add case — user with 2 OWNER memberships selects tenant B → JWT scoped to tenant B; then selects tenant A → JWT scoped to tenant A (AC12)
  - [x] 11.8 — `UserMembershipRepositoryAdapterTest`: verify unique constraint throws `DomainException` on duplicate (user, tenant) insert (AC13)

---

## Dev Notes

### Critical Architecture Rules

- **NEVER** put tenant context selection logic in `AuthenticationService` — it stops at credential verification + membership listing. All tenant-scoped logic lives in `SelectTenantService`.
- **loginToken vs accessToken security**: these are two different "scopes" of JWT even though both are RS256. The `scope` claim (`"login_pending"` vs `"access"`) is the safety gate. `JwtAuthFilter` MUST reject `login_pending` tokens for protected endpoints.
- `ddl-auto=update` note: Hibernate will **NOT** drop the `tenant_id` column from `public.users`. The column stays in the DB, unmapped, harmless. Do NOT try to manually drop it with JDBC — it's not needed for V1.
- All new JPA entities in `public` schema use `@Table(name = "...")` without schema qualifier — Hibernate routes them to `public` via the default connection pool (bypasses `SchemaAwareMultiTenantConnectionProvider`). This is correct behavior — same as existing `UserJpaEntity`, `TenantJpaEntity`, `RefreshTokenJpaEntity`.

### File Locations

| File | Path |
|---|---|
| `User.java` (modify) | `identity/auth/domain/model/User.java` |
| `UserTenantMembership.java` (new) | `identity/auth/domain/model/UserTenantMembership.java` |
| `UserMembershipInfo.java` (new) | `identity/auth/domain/model/UserMembershipInfo.java` |
| `UserJpaEntity.java` (modify) | `identity/auth/adapter/out/persistence/entity/UserJpaEntity.java` |
| `UserTenantMembershipJpaEntity.java` (new) | `identity/auth/adapter/out/persistence/entity/UserTenantMembershipJpaEntity.java` |
| `UserTenantMembershipSpringRepository.java` (new) | `identity/auth/adapter/out/persistence/jpa/UserTenantMembershipSpringRepository.java` |
| `UserMembershipRepository.java` (new port) | `identity/auth/domain/port/out/UserMembershipRepository.java` |
| `UserMembershipRepositoryAdapter.java` (new) | `identity/auth/adapter/out/persistence/impl/UserMembershipRepositoryAdapter.java` |
| `SelectTenantCommand.java` (new) | `identity/auth/domain/port/in/SelectTenantCommand.java` |
| `SelectTenantUseCase.java` (new) | `identity/auth/domain/port/in/SelectTenantUseCase.java` |
| `LoginSessionResult.java` (new) | `identity/auth/domain/port/in/LoginSessionResult.java` |
| `SelectTenantService.java` (new) | `identity/auth/application/service/SelectTenantService.java` |
| `AuthenticationService.java` (modify) | `identity/auth/application/service/AuthenticationService.java` |
| `RegistrationService.java` (modify) | `identity/auth/application/service/RegistrationService.java` |
| `JwtTokenProvider.java` (modify) | `shared/infrastructure/security/JwtTokenProvider.java` |
| `JwtAuthFilter.java` (modify) | `shared/infrastructure/security/JwtAuthFilter.java` |
| `AdminAccountInitializer.java` (modify) | `shared/infrastructure/config/AdminAccountInitializer.java` |
| `LoginSessionResponse.java` (new) | `identity/auth/adapter/in/rest/dto/LoginSessionResponse.java` |
| `SelectTenantRequest.java` (new) | `identity/auth/adapter/in/rest/dto/SelectTenantRequest.java` |
| `AuthController.java` (modify) | `identity/auth/adapter/in/rest/AuthController.java` |

### Existing Patterns to Follow

**JWT generation pattern** (from `AuthenticationService`):
```java
String accessToken = jwtTokenProvider.generateAccessToken(userId, schemaName, role, tenantStatus);
```
Use the same pattern for `generateLoginToken(userId)` — just different claims and TTL.

**Hexagonal rule**: `SelectTenantService` must inject ports (`UserMembershipRepository`, `UserRepository`, `TenantRepository`, `RefreshTokenRepository`) via constructor, not field injection. Pattern established in all existing services.

**`@Transactional` on service execute methods** — all steps in `SelectTenantService.select()` (find membership + save refresh token) must be in the same transaction (as in `ActivatePlanService`).

**loginToken scope check in `JwtAuthFilter`**:
```java
// After JWT parsing:
String scope = jwtTokenProvider.extractScope(token); // returns null or claim value
if ("login_pending".equals(scope)) {
    // Reject — login tokens cannot access protected resources
    sendAuthError(response, "TOKEN_INVALID");
    return;
}
```

### `findMembershipsWithTenantInfo` JdbcTemplate query

This must join `public.user_tenant_memberships` + `public.tenants` using `JdbcTemplate` (NOT Hibernate — `TenantContext` is not set at login time, would route to wrong schema). Use schema-qualified query:

```sql
SELECT m.tenant_id, m.role, m.is_active,
       t.code AS tenant_code, t.name AS tenant_name, t.schema_name
FROM public.user_tenant_memberships m
JOIN public.tenants t ON t.id = m.tenant_id
WHERE m.user_id = ? AND m.is_active = true
```

Inject `JdbcTemplate` directly into `UserRepositoryAdapter` (already pattern-established by `StoreCountAdapter`, `ProductCountAdapter`, `UserCountAdapter`). Do NOT use Hibernate for this query.

### Flutter Patterns

- Auth state management: uses Riverpod (`AsyncNotifier` pattern established in Story 1.3)
- Secure storage: `flutter_secure_storage` for `accessToken`, `refreshToken`, `userId`, `tenantId`, `role`
- Additionally store `loginToken` temporarily in memory (NOT in secure storage — it's ephemeral, 5min)
- `TenantPickerScreen` is a simple placeholder for V1 — no fancy UI needed. A `ListView` of `ListTile` with `tenantName` + a `Chip` for `role` is sufficient.

### Testing Standards

- Backend: JUnit 5 + Mockito (established pattern). All new service tests mock ports. No Spring context.
- Target: minimum 15 new backend tests
- All 145 existing tests must remain GREEN — if a test breaks, fix it before adding new ones

### Project Structure Notes

All paths relative to `keevo/backend/src/main/java/com/keevo/` (backend) and `keevo/app/lib/` (Flutter).

No Flyway SQL files needed — `ddl-auto=update` handles new table creation automatically.

### References

- [Source: architecture.md#Backend Architecture] — Hexagonal structure, domain/port/adapter layering
- [Source: architecture.md#JdbcTemplate vs Hibernate routing] — Rule: all `public.*` cross-schema joins must use `JdbcTemplate` directly, NOT Hibernate
- [Source: identity/auth/application/service/AuthenticationService.java] — Login flow to refactor
- [Source: identity/auth/application/service/RegistrationService.java] — Registration flow to modify
- [Source: identity/auth/adapter/out/persistence/entity/UserJpaEntity.java] — JPA entity to modify
- [Source: shared/infrastructure/persistence/TenantSchemaProvisioner.java] — tenant schema provisioning (unchanged)
- [Source: shared/infrastructure/config/AdminAccountInitializer.java] — Admin bootstrap to update
- [Source: epics/epic-1-foundation-infrastructure-authentication.md#Story 1.7] — Original audit trail story moved to 1.8

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 4.6 (GitHub Copilot)

### Debug Log References

N/A

### Completion Notes List

- Story 1.7 created as architectural correction to enable multi-tenant user memberships
- Original Story 1.7 (Immutable Audit Trail) renumbered to Story 1.8
- Original Story 1.8 (Certificate Pinning) renumbered to Story 1.9
- Code review session (backend): H2/M2/M4/H1+L2/M3 fixed | 175/175 tests GREEN | 10/10 curl steps | e2e-story-1-7.py 44/44 assertions GREEN
- Code review session (Flutter): AC8+AC9+Task 10 missing — Flutter two-step login was never implemented; fixed via full Flutter implementation: MembershipDto (freezed), LoginSessionResponse, LoginResult (sealed), SelectTenantUseCase, TenantPickerPage, RemoteAuthDataSource two-step, AuthRepository.selectTenant(), LoginUseCase→LoginResult, Login notifier type updated, SelectTenant notifier added, /tenant-picker route added | build_runner regenerated auth_provider.g.dart + membership_dto.freezed.dart | 28/28 Flutter auth tests GREEN

### File List

**Modified:**
- `identity/auth/adapter/in/rest/AuthController.java`
- `identity/auth/adapter/out/persistence/entity/TenantJpaEntity.java`
- `identity/auth/adapter/out/persistence/entity/UserJpaEntity.java`
- `identity/auth/adapter/out/persistence/impl/TenantRepositoryAdapter.java`
- `identity/auth/adapter/out/persistence/impl/UserRepositoryAdapter.java`
- `identity/auth/adapter/out/persistence/jpa/TenantSpringRepository.java`
- `identity/auth/application/service/AuthenticationService.java`
- `identity/auth/application/service/RegistrationService.java`
- `identity/auth/domain/model/Tenant.java`
- `identity/auth/domain/model/User.java`
- `identity/auth/domain/port/in/AuthenticateUserUseCase.java`
- `identity/auth/domain/port/out/TenantRepository.java`
- `identity/auth/domain/port/out/UserRepository.java`
- `shared/domain/exception/ErrorCode.java`
- `shared/infrastructure/config/AdminAccountInitializer.java`
- `shared/infrastructure/security/JwtAuthFilter.java`
- `shared/infrastructure/security/JwtTokenProvider.java`
- `shared/infrastructure/security/SecurityConfig.java`
- `shared/infrastructure/web/GlobalExceptionHandler.java`

**New:**
- `identity/auth/adapter/in/rest/dto/LoginSessionResponse.java`
- `identity/auth/adapter/in/rest/dto/SelectTenantRequest.java`
- `identity/auth/adapter/out/persistence/entity/UserTenantMembershipJpaEntity.java`
- `identity/auth/adapter/out/persistence/impl/UserMembershipRepositoryAdapter.java`
- `identity/auth/adapter/out/persistence/jpa/UserTenantMembershipSpringRepository.java`
- `identity/auth/application/service/SelectTenantService.java`
- `identity/auth/domain/model/UserMembershipInfo.java`
- `identity/auth/domain/model/UserTenantMembership.java`
- `identity/auth/domain/port/in/LoginSessionResult.java`
- `identity/auth/domain/port/in/SelectTenantCommand.java`
- `identity/auth/domain/port/in/SelectTenantUseCase.java`
- `identity/auth/domain/port/out/UserMembershipRepository.java`

**Flutter new:**
- `app/lib/features/auth/domain/model/membership_dto.dart`
- `app/lib/features/auth/domain/model/membership_dto.freezed.dart`
- `app/lib/features/auth/domain/model/login_session_response.dart`
- `app/lib/features/auth/domain/model/login_result.dart`
- `app/lib/features/auth/domain/usecase/select_tenant_usecase.dart`
- `app/lib/features/auth/presentation/page/tenant_picker_page.dart`

**Flutter modified:**
- `app/lib/features/auth/data/datasource/remote_auth_datasource.dart`
- `app/lib/features/auth/domain/repository/auth_repository.dart`
- `app/lib/features/auth/data/repository/auth_repository_impl.dart`
- `app/lib/features/auth/domain/usecase/login_usecase.dart`
- `app/lib/features/auth/presentation/provider/auth_provider.dart`
- `app/lib/features/auth/presentation/provider/auth_provider.g.dart`
- `app/lib/features/auth/presentation/page/login_page.dart`
- `app/lib/core/router/app_router.dart`

**Flutter tests updated:**
- `app/test/features/auth/data/repository/auth_repository_impl_test.dart`
- `app/test/features/auth/domain/usecase/login_usecase_test.dart`
- `app/test/features/auth/presentation/page/login_page_test.dart`

**E2E scripts:**
- `scripts/e2e/curl-tests-story-1-7.sh` (new — 10 self-contained curl tests)
- `scripts/e2e/curl-tests-story-1-6.sh` (fixed — phoneNumber field + two-step login in Step 5)
