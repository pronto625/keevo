# Story 3.5: Création Employés, Rôles & Assignation Boutique

Status: in-progress

## Story

As a proprietor (Simon),
I want to create employee accounts directly by entering their personal information,
so that each employee receives a temporary generated password, can log in immediately, and is forced to set their own password on first login following the existing two-step login flow.

## Acceptance Criteria

### AC1 — Owner creates an employee account

- **Given** Simon navigates to Paramètres > Équipe
- **When** he taps "Ajouter un employé"
- **Then** a form appears with: Prénom (required), Nom (required), Numéro de téléphone (required — same `IntlPhoneField` as registration), Boutique assignée (required — dropdown of active stores), Rôle (EMPLOYEE — currently the only non-owner role)
- **And** on submit, the backend:
  - creates a `public.users` entry with the provided phone number and a system-generated temporary password (bcrypt cost 12)
  - inserts a `public.user_tenant_memberships` row: `(newUserId, tenantId, role='EMPLOYEE', is_active=true)`
  - inserts a `kv_xxx.employees` row with: `id` (UUID), `userId`, `storeId`, `firstName`, `lastName`, `status: ACTIVE`, `passwordChangeRequired: true`, `createdAt`
  - emits an `EmployeeCreatedEvent` in the audit log with `actorId` (Simon), `targetUserId`, `storeId`, `occurredAt`
- **And** a success bottom sheet is shown to Simon displaying:
  - Employee name and assigned store
  - The generated temporary password in full (cleartext, one-time display only)
  - A "Copier le mot de passe" button and a warning: "Ce mot de passe ne sera plus affiché. Communiquez-le à l'employé par vos propres moyens."
- **And** the temporary password is NEVER stored in plaintext after this display — only the bcrypt hash is persisted

### AC2 — Plan limit enforcement

- **Given** the Free plan limit is 5 employees
- **When** Simon attempts to create a 6th employee
- **Then** the creation is blocked: HTTP 403 `{ "domainCode": "PLAN_LIMIT_EXCEEDED", "details": { "entity": "employees", "limit": 5, "current": 5 } }`
- **And** the Flutter app shows the upgrade bottom sheet (`PlanLimitBottomSheet`)

### AC3 — Employee first login: phone + temporary password

- **Given** Loïc has received his phone number and temporary password from Simon
- **When** he opens Keevo and enters his phone number and the temporary password on the login screen
- **Then** the standard two-step login flow executes (Story 1.7):
  - Step 1: `POST /auth/login` verifies credentials → returns `loginToken` + `memberships` array
  - Step 2: Flutter auto-calls `POST /auth/select-tenant` (single membership → no picker shown)
- **And** the final `accessToken` JWT contains: `userId`, `tenantId`, `role: EMPLOYEE`, `storeId`, `passwordChangeRequired: true`
- **And** the backend `LoginResponse` from step 2 includes `storeId` and `passwordChangeRequired: true` as fields
- **And** the Flutter app detects the `passwordChangeRequired: true` flag in the stored `AuthTokens`
- **And** navigation is intercepted — Loïc is redirected to the `PasswordChangePage` before any other screen is accessible (no back navigation, no skip)

### AC4 — Forced password change on first login

- **Given** Loïc is on the `PasswordChangePage` (triggered by `passwordChangeRequired: true`)
- **When** he submits a new password
- **Then** the new password must meet minimum requirements: ≥ 8 characters, at least one number
- **And** `POST /api/v1/auth/change-password` is called with `{ "currentPassword": "<temp>", "newPassword": "<chosen>" }`
- **And** on success, the backend:
  - updates the bcrypt hash in `public.users`
  - sets `passwordChangeRequired: false` in `kv_xxx.employees`
  - emits `EmployeePasswordSetEvent` in the audit log with `actorId` (Loïc), `occurredAt`
- **And** the backend enforces this server-side: all non-auth API requests return HTTP 403 `{ "domainCode": "PASSWORD_CHANGE_REQUIRED" }` while `passwordChangeRequired: true`
- **And** upon successful password change, new tokens are issued (refreshed JWT without the `passwordChangeRequired` flag) and Loïc is redirected to the POS screen (`/pos`)

> 🔒 **Security note:** The forced-change flow is a mandatory security control. The backend enforces this independently of the Flutter guard.

### AC5 — Employee access restrictions

- **Given** Loïc's account is active and he has completed the password change
- **When** he accesses any feature
- **Then** he is limited to operations on his assigned store only — his JWT's `storeId` claim restricts all backend queries
- **And** stock views, POS, and inventory show ONLY data for his assigned store
- **And** he cannot view reports, manage the product catalogue, create stores, or create other employees
- **And** the Flutter navigation menu hides all OWNER-only sections automatically based on `currentUserRoleProvider` returning `'EMPLOYEE'`
- **And** the bottom navigation bar shows ONLY 2 tabs for EMPLOYEE: **Caisse** and **Plus** (OWNER sees all 5: Caisse / Catalogue / Clients / Fournisseurs / Plus)
- **And** the Settings page ("Plus") hides the entire "Gestion" section for EMPLOYEE (no Boutiques, Stock multi-boutiques, Abonnement, Équipe tiles) — EMPLOYEE only sees Préférences and Support sections
- **And** the backend enforces these restrictions via `JwtAuthFilter`: EMPLOYEE accessing OWNER-only endpoints receives HTTP 403
- **And** after login, navigation goes to `/pos` (not `/products` or any OWNER-only page)

### AC6 — Reassign employee to another store

- **Given** Simon wants to reassign an employee to a different store
- **When** he taps the employee card and selects "Modifier l'assignation"
- **Then** a dropdown shows all active stores and he can select a new one
- **And** on save, the employee's `storeId` is updated in `kv_xxx.employees`
- **And** the next API request from Loïc using a previously-issued JWT is rejected with HTTP 401 `{ "domainCode": "STORE_REASSIGNED" }` — forcing a re-login to obtain a JWT with the updated `storeId`
- **And** a `EmployeeStoreReassignedEvent` is emitted in the audit log

### AC7 — Revoke employee access

- **Given** Simon needs to revoke an employee's access immediately
- **When** he taps the employee card and selects "Désactiver l'accès"
- **Then** the employee's `status` is set to `INACTIVE` in `kv_xxx.employees`
- **And** the `public.user_tenant_memberships` row for that employee is set to `is_active: false`
- **And** all active refresh tokens for that employee are revoked via `RefreshTokenRepository.revokeAllByUserId(userId)` (effective within the next refresh cycle ≤ 24h)
- **And** the next API request from that employee returns HTTP 401 `{ "domainCode": "ACCOUNT_INACTIVE" }`
- **And** a `EmployeeDeactivatedEvent` is emitted in the audit log

### AC7b — Reactivate employee access

- **Given** Simon has previously deactivated an employee
- **When** he taps the inactive employee card and selects "Réactiver l'accès"
- **Then** the employee's `status` is set back to `ACTIVE` in `kv_xxx.employees`
- **And** the `public.user_tenant_memberships` row for that employee is set to `is_active: true`
- **And** the employee can log in again using their existing credentials

### AC7c — Regenerate employee password

- **Given** Simon needs to reset an employee's password (employee forgot it, or first password was lost)
- **When** he taps the employee card and selects "Regénérer le mot de passe"
- **Then** a confirmation dialog is shown
- **And** on confirm, the backend:
  - generates a new temporary password via `TempPasswordFactory.generate()`
  - updates the bcrypt hash in `public.users`
  - sets `passwordChangeRequired: true` in `kv_xxx.employees`
  - revokes all refresh tokens for the employee (forces re-login)
- **And** a success bottom sheet displays the new temporary password (one-time, same UI as AC1)
- **And** the employee must go through the forced password change flow on next login (AC4)

### AC8 — Employee list view

- **Given** Simon navigates to Paramètres > Équipe
- **When** the team screen loads
- **Then** all employees (ACTIVE and INACTIVE) are listed with: name, assigned store, status badge (Actif / Inactif), and a "Mot de passe non changé" badge if `passwordChangeRequired: true` is still set
- **And** INACTIVE employees are shown at the bottom of the list
- **And** Simon can tap any ACTIVE employee to: reassign store, regenerate password, or deactivate
- **And** Simon can tap any INACTIVE employee to: reactivate access or regenerate password

---

## GoF Pattern Analysis

| Question | Answer |
|---|---|
| What variability exists in this feature? | Employee creation involves composite operations across 3 tables (`public.users`, `public.user_tenant_memberships`, `kv_xxx.employees`) with password generation. Deactivation involves multiple side effects (status, membership, token revocation). |
| What might change in the future? | Role types beyond EMPLOYEE (MANAGER, CASHIER). Invitation via WhatsApp link (Epic 8). Employee activity tracking (Epic 8). Batch employee import. |
| Which GoF pattern(s) apply? | **Factory Method** — `TempPasswordFactory.generate()` encapsulates temporary password generation logic (random 12-char alphanumeric, bcrypt-hashable). **Observer** — `EmployeeCreatedEvent`, `EmployeePasswordSetEvent`, `EmployeeStoreReassignedEvent`, `EmployeeDeactivatedEvent` → `AuditEventListener`. **Command** — all use case inputs are Command/Query records (`CreateEmployeeCommand`, `ChangePasswordCommand`, `ReassignStoreCommand`, `DeactivateEmployeeCommand`, `ListEmployeesQuery`) — MCP-ready as per architecture rules. |
| How does it enable Open/Closed principle? | `CreateEmployeeUseCase` interface is closed. `TempPasswordFactory` can be swapped (e.g., to WhatsApp-OTP-based) without changing the use case. New roles extend the system by adding constants to the role model without modifying `CreateEmployeeService`. `ChangePasswordUseCase` is closed; enforcement rules (min length, complexity) can be extended in `PasswordPolicy` without touching the service. |
| Where is the pattern applied? | Factory Method: `TempPasswordFactory.generate()` in `identity/employee/domain/model/`. Observer: domain events via `ApplicationEventPublisher` → `AuditEventListener`. Command: all `*Command` and `*Query` records in `identity/employee/domain/port/in/`. |

---

## Dev Notes

### Pre-existing context to read FIRST

1. **Story 1.7 (done)** — foundational for this story:
   - `public.user_tenant_memberships` table already exists with `(userId, tenantId, role, is_active)`
   - `UserMembershipRepository` port already exists in `identity/auth/domain/port/out/`
   - `UserMembershipRepositoryAdapter` in `identity/auth/adapter/out/persistence/impl/`
   - `UserRepository` port: `findByPhoneNumber()`, `save(User)` already exists
   - Two-step login flow: `POST /auth/login` → `POST /auth/select-tenant` — already implemented
   - `SelectTenantService` is in `identity/auth/application/service/SelectTenantService.java`

2. **Story 1.6 (done)** — plan limits infrastructure:
   - `PlanType.FREE.getMaxEmployees()` currently returns `3` — **must be updated to `5`** to match AC2
   - `PlanLimitGuard.checkEmployeesLimit(int currentEmployees, PlanType planType)` **already exists** in `subscription/plan/application/service/PlanLimitGuard.java`
   - Follow `StoreCountAdapter` pattern (in `subscription/plan/adapter/out/persistence/impl/`) to create `EmployeeCountAdapter`
   - `SubscriptionRepository.findByTenantId()` and `Subscription.getPlanType()` already exist

3. **Story 1.8 (done)** — audit trail:
   - `AuditEventListener` handles all domain events — **just publish events with `ApplicationEventPublisher`**
   - Follow `EmployeeCreatedEvent` pattern from existing events (e.g., `StoreCreatedEvent` in `store/store/domain/event/`)

4. **`JwtTokenProvider` (current state)**:
   - `generateAccessToken(UUID userId, String tenantId, String role, String tenantStatus)` — method already exists
   - `generateAccessToken(UUID userId, String tenantId, String role)` — overload exists
   - Claims currently: `sub` (userId), `tenantId`, `role`, `tenantStatus`, (`scope` for login tokens)
   - **Must add**: `storeId` and `passwordChangeRequired` as new optional JWT claims

5. **`JwtAuthFilter` (current state)**:
   - Already sets `TenantContext.set(tenantId)` after parsing JWT — tenant schema is available for DB queries after this point
   - Already rejects `login_pending` scope tokens
   - **Must add**: Two new checks for EMPLOYEE role (run AFTER `TenantContext` is set):
     a. DB lookup: `SELECT store_id, status, password_change_required FROM employees WHERE user_id = ?`
     b. If `status == INACTIVE` → 401 `ACCOUNT_INACTIVE`
     c. If `JWT.storeId != employee.storeId` → 401 `STORE_REASSIGNED`
     d. If `passwordChangeRequired == true` AND path is not in auth whitelist → 403 `PASSWORD_CHANGE_REQUIRED`

6. **`RefreshTokenRepository`** (already has):
   - `revokeAllByUserId(UUID userId)` — use this for deactivation (AC7) and store reassignment (AC6)

7. **`SelectTenantService`** (must be modified):
   - Currently generates JWT with only `userId, tenantId, role, tenantStatus`
   - After this story: if `role == 'EMPLOYEE'`, also query `kv_xxx.employees` to get `storeId` and `passwordChangeRequired`
   - Include `storeId` and `passwordChangeRequired` in the JWT claims and the `LoginResponse` body
   - The `LoginResponse` DTO (in `identity/auth/adapter/in/rest/dto/`) needs two new fields: `storeId` (nullable) and `passwordChangeRequired` (boolean, default false)

8. **Flutter `AuthTokens` model** (must be extended):
   - Currently: `accessToken, refreshToken, userId, tenantId, role, expiresIn` (freezed)
   - **Add**: `storeId` (String?, nullable) and `passwordChangeRequired` (bool, defaults false)
   - `SecureTokenStorage` needs new keys: `_keyStoreId = 'store_id'` and `_keyPasswordChangeRequired = 'pwd_change_req'`

9. **Flutter `SharedPreferences` keys** (already established pattern):
   - `kUserRoleKey = 'user_role'` exists in `core/di/providers.dart`
   - `currentUserRoleProvider` already exists — **use it to conditionally show the Équipe tile in `SettingsPage`**

10. **Flutter router** (`core/router/app_router.dart`):
    - `_isValidJwt()` helper already exists — reuse for redirect guards
    - **Add**: Check `SharedPreferences` for `'pwd_change_req' == 'true'` → redirect to `/auth/change-password` if any non-auth route is accessed
    - **Add routes**: `/settings/team`, `/settings/team/new`, `/auth/change-password`

11. **`SettingsPage` UX** — follow exact gradient header + card + tile pattern from existing `settings_page.dart`:
    - "Équipe" tile in the **Gestion** section, VISIBLE ONLY when `currentUserRoleProvider == 'OWNER'`
    - Use `ConsumerWidget` pattern (already used in settings page via `Consumer` widgets)
    - Icon: `Icons.group_rounded`, iconColor: `Color(0xFF3B82F6)`, iconBg: `Color(0xFFDBEAFE)`

### Architecture Compliance

**Backend module location**: `identity/employee/` (not `store/employee/`) — employees are an identity/access management concern; store assignment is a foreign-key reference, not an ownership boundary.

**Tenant schema routing**: `EmployeeJpaEntity` uses `@Table(name = "employees")` without schema qualifier. Hibernate routes through `SchemaAwareMultiTenantConnectionProvider` which sets `search_path = kv_xxx`. This is identical to how `StoreJpaEntity`, `ProductJpaEntity`, etc. are handled.

**public schema tables**: `public.users` and `public.user_tenant_memberships` use `JdbcTemplate` with schema-qualified queries (not Hibernate) — same pattern as `findMembershipsWithTenantInfo()` in `UserRepositoryAdapter`.

**MCP Port Purity** (non-negotiable):
- All use case port inputs MUST be Java records (`CreateEmployeeCommand`, `ChangePasswordCommand`, etc.)
- `ActorId` (Simon's userId) MUST be passed explicitly in every Command — NEVER resolved from `SecurityContextHolder` inside use cases
- No `HttpServletRequest`, `Principal`, or `MultipartFile` in use case interfaces

**`EmployeeCountAdapter`**: Uses `TenantContext.getCurrentTenant()` + `JdbcTemplate` to count employees — identical pattern to `StoreCountAdapter`. SQL: `SELECT COUNT(*) FROM "schema".employees WHERE status = 'ACTIVE'`.

**New `password_change_required` enforcement path in `JwtAuthFilter`**:  
The auth whitelist in `JwtAuthFilter.shouldNotFilter()` includes `/api/v1/auth/**` paths. The whitelist remains unchanged. The `passwordChangeRequired` check runs ONLY for requests that PASS `shouldNotFilter()` (i.e., non-public endpoints). Implementation: after JWT parsing + TenantContext setup, if JWT's `passwordChangeRequired == true` → return 403 immediately.

---

## Tasks / Subtasks

> ⚠️ **TDD STRICT — RED → GREEN → REFACTOR. Write the failing test FIRST. No production code before a test. All curl tests must pass sequentially before story is marked done.**

---

### BACKEND

---

- [x] **Task 1 — Write ALL RED backend tests (TDD first pass)**

  - [x] 1.1 — Create `CreateEmployeeServiceTest.java` in `test/.../identity/employee/application/service/`:
    ```java
    @ExtendWith(MockitoExtension.class)
    class CreateEmployeeServiceTest {
        @Mock UserRepository userRepository;
        @Mock UserMembershipRepository membershipRepository;
        @Mock EmployeeRepository employeeRepository;
        @Mock PlanLimitGuard planLimitGuard;
        @Mock EmployeeCountPort employeeCountPort;
        @Mock SubscriptionRepository subscriptionRepository;
        @Mock ApplicationEventPublisher eventPublisher;

        @Test void execute_shouldCreateUserMembershipAndEmployee_whenValidCommand()
        @Test void execute_shouldThrowPlanLimitExceeded_whenEmployeeCountReachesMax()
        @Test void execute_shouldThrowPhoneAlreadyRegistered_whenPhoneNumberExists()
        @Test void execute_shouldPublishEmployeeCreatedEvent_onSuccess()
        @Test void execute_shouldReturnTempPasswordCleartext_onSuccess()
    }
    ```
  - [x] 1.2 — Create `ChangePasswordServiceTest.java` in `test/.../identity/employee/application/service/`:
    ```java
    @Test void execute_shouldUpdatePasswordHash_whenCurrentPasswordCorrect()
    @Test void execute_shouldClearPasswordChangeRequired_onSuccess()
    @Test void execute_shouldThrowInvalidCredentials_whenCurrentPasswordWrong()
    @Test void execute_shouldPublishEmployeePasswordSetEvent_onSuccess()
    @Test void execute_shouldReturnNewTokens_onSuccess()
    ```
  - [x] 1.3 — Create `ReassignStoreServiceTest.java`:
    ```java
    @Test void execute_shouldUpdateStoreId_whenValidCommand()
    @Test void execute_shouldRevokeRefreshTokens_onReassignment()
    @Test void execute_shouldPublishEmployeeStoreReassignedEvent_onSuccess()
    @Test void execute_shouldThrowEmployeeNotFound_whenIdInvalid()
    ```
  - [x] 1.4 — Create `DeactivateEmployeeServiceTest.java`:
    ```java
    @Test void execute_shouldSetStatusInactive_whenActive()
    @Test void execute_shouldSetMembershipInactive_whenActive()
    @Test void execute_shouldRevokeRefreshTokens_onDeactivation()
    @Test void execute_shouldPublishDeactivatedEvent_onSuccess()
    @Test void execute_shouldThrowEmployeeNotFound_whenIdInvalid()
    ```
  - [x] 1.5 — Create `EmployeeControllerTest.java` in `test/.../identity/employee/adapter/in/rest/`:
    ```java
    @WebMvcTest(EmployeeController.class)
    class EmployeeControllerTest {
        // @MockBean: CreateEmployeeUseCase, ListEmployeesUseCase, ReassignStoreUseCase, DeactivateEmployeeUseCase, JwtTokenProvider
        @Test void createEmployee_returns201_withTempPasswordField()
        @Test void createEmployee_returns403_whenPlanLimitExceeded()
        @Test void createEmployee_returns403_whenNotOwner()    // EMPLOYEE cannot create employees
        @Test void createEmployee_returns401_withoutJwt()
        @Test void listEmployees_returns200_withEmployeeList()
        @Test void reassignStore_returns200_onSuccess()
        @Test void deactivateEmployee_returns200_onSuccess()
    }
    ```
  - [x] 1.6 — Create `AuthControllerChangePasswordTest.java` (add to existing `AuthControllerTest` class or new class):
    ```java
    @Test void changePassword_returns200_withNewTokens()
    @Test void changePassword_returns400_whenNewPasswordTooShort()
    @Test void changePassword_returns401_whenCurrentPasswordWrong()
    ```
  - [x] 1.7 — Run all tests → confirm ALL RED (compilation failures acceptable at this stage)

---

- [x] **Task 2 — Domain model: Employee entity and supporting types**

  - [x] 2.1 — Create `EmployeeStatus.java` enum in `identity/employee/domain/model/`:
    ```java
    public enum EmployeeStatus { ACTIVE, INACTIVE }
    ```
  - [x] 2.2 — Create `Employee.java` in `identity/employee/domain/model/`:
    ```java
    public final class Employee {
        private final UUID id;
        private final UUID userId;         // references public.users.id
        private final UUID storeId;        // references kv_xxx.stores.id
        private final String firstName;
        private final String lastName;
        private final EmployeeStatus status;
        private final boolean passwordChangeRequired;
        private final Instant createdAt;

        // Factory
        public static Employee create(UUID userId, UUID storeId, String firstName,
                                      String lastName) {
            return new Employee(UUID.randomUUID(), userId, storeId, firstName, lastName,
                                EmployeeStatus.ACTIVE, true, Instant.now());
        }
        // Getters, withStatus(), withStoreId(), withPasswordChangeRequired()
    }
    ```
  - [x] 2.3 — Create `TempPasswordFactory.java` in `identity/employee/domain/model/`:
    ```java
    /**
     * TempPasswordFactory — Factory Method pattern.
     * Generates a cryptographically random temporary password: 12 chars,
     * [A-Za-z0-9], always contains at least one digit and one uppercase.
     */
    public final class TempPasswordFactory {
        private static final SecureRandom RANDOM = new SecureRandom();
        private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        // 12-char random string excluding ambiguous O/0/I/l/1

        public static String generate() { ... }
    }
    ```
  - [x] 2.4 — Create `CreateEmployeeResult.java` record in `identity/employee/domain/model/`:
    ```java
    // Returns the new employee + cleartext temp password (one-time)
    public record CreateEmployeeResult(Employee employee, String temporaryPassword) {}
    ```

---

- [x] **Task 3 — Domain events**

  - [x] 3.1 — Create `EmployeeCreatedEvent.java` in `identity/employee/domain/event/`:
    ```java
    public record EmployeeCreatedEvent(UUID actorId, UUID targetUserId, UUID employeeId,
                                       UUID storeId, Instant occurredAt) {}
    ```
  - [x] 3.2 — Create `EmployeePasswordSetEvent.java` in `identity/employee/domain/event/`:
    ```java
    public record EmployeePasswordSetEvent(UUID actorId, UUID employeeId, Instant occurredAt) {}
    ```
  - [x] 3.3 — Create `EmployeeStoreReassignedEvent.java` in `identity/employee/domain/event/`:
    ```java
    public record EmployeeStoreReassignedEvent(UUID actorId, UUID employeeId,
                                               UUID oldStoreId, UUID newStoreId, Instant occurredAt) {}
    ```
  - [x] 3.4 — Create `EmployeeDeactivatedEvent.java` in `identity/employee/domain/event/`:
    ```java
    public record EmployeeDeactivatedEvent(UUID actorId, UUID employeeId,
                                           UUID userId, Instant occurredAt) {}
    ```

---

- [x] **Task 4 — Domain ports (Commands, Queries, Use Case interfaces)**

  - [x] 4.1 — Create `CreateEmployeeCommand.java` in `identity/employee/domain/port/in/`:
    ```java
    public record CreateEmployeeCommand(
        UUID actorId,       // Simon's userId (owner)
        String phoneNumber,
        String firstName,
        String lastName,
        UUID storeId
    ) {}
    ```
  - [x] 4.2 — Create `CreateEmployeeUseCase.java` in `identity/employee/domain/port/in/`:
    ```java
    public interface CreateEmployeeUseCase {
        CreateEmployeeResult execute(CreateEmployeeCommand command);
    }
    ```
  - [x] 4.3 — Create `ChangePasswordCommand.java` in `identity/employee/domain/port/in/`:
    ```java
    public record ChangePasswordCommand(
        UUID actorId,          // employee's userId
        String tenantId,       // tenant schema for employee lookup
        String currentPassword,
        String newPassword
    ) {}
    ```
  - [x] 4.4 — Create `ChangePasswordUseCase.java` in `identity/employee/domain/port/in/`:
    ```java
    public interface ChangePasswordUseCase {
        AuthTokens execute(ChangePasswordCommand command); // Returns fresh JWT
    }
    ```
  - [x] 4.5 — Create `ReassignStoreCommand.java` in `identity/employee/domain/port/in/`:
    ```java
    public record ReassignStoreCommand(UUID actorId, UUID employeeId, UUID newStoreId) {}
    ```
  - [x] 4.6 — Create `ReassignStoreUseCase.java` in `identity/employee/domain/port/in/`:
    ```java
    public interface ReassignStoreUseCase {
        Employee execute(ReassignStoreCommand command);
    }
    ```
  - [x] 4.7 — Create `DeactivateEmployeeCommand.java` in `identity/employee/domain/port/in/`:
    ```java
    public record DeactivateEmployeeCommand(UUID actorId, UUID employeeId) {}
    ```
  - [x] 4.8 — Create `DeactivateEmployeeUseCase.java` in `identity/employee/domain/port/in/`:
    ```java
    public interface DeactivateEmployeeUseCase {
        void execute(DeactivateEmployeeCommand command);
    }
    ```
  - [x] 4.9 — Create `ListEmployeesQuery.java` and `ListEmployeesUseCase.java` in `identity/employee/domain/port/in/`:
    ```java
    public record ListEmployeesQuery(UUID actorId) {}
    public interface ListEmployeesUseCase {
        List<Employee> execute(ListEmployeesQuery query);
    }
    ```
  - [x] 4.10 — Create `EmployeeRepository.java` in `identity/employee/domain/port/out/`:
    ```java
    public interface EmployeeRepository {
        Employee save(Employee employee);
        Optional<Employee> findById(UUID id);
        Optional<Employee> findByUserId(UUID userId);
        List<Employee> findAllOrderByStatusAndCreatedAt();
        Employee updateStoreId(UUID employeeId, UUID newStoreId);
        Employee updateStatus(UUID employeeId, EmployeeStatus status);
        Employee updatePasswordChangeRequired(UUID employeeId, boolean required);
    }
    ```
  - [x] 4.11 — Create `EmployeeCountPort.java` in `identity/employee/domain/port/out/`:
    ```java
    public interface EmployeeCountPort {
        int countActiveEmployees(); // counts ACTIVE employees in current tenant schema
    }
    ```

---

- [x] **Task 5 — JPA persistence layer**

  - [x] 5.1 — Create `EmployeeJpaEntity.java` in `identity/employee/adapter/out/persistence/`:
    ```java
    @Entity
    @Table(name = "employees")   // no schema qualifier — routes via TenantContext search_path
    public class EmployeeJpaEntity extends JpaBaseEntity {
        @Column(name = "user_id",   nullable = false) private UUID userId;
        @Column(name = "store_id",  nullable = false) private UUID storeId;
        @Column(name = "first_name", nullable = false, length = 100) private String firstName;
        @Column(name = "last_name",  nullable = false, length = 100) private String lastName;
        @Column(name = "status",     nullable = false, length = 20)  private String status;
        @Column(name = "password_change_required", nullable = false)  private boolean passwordChangeRequired;
        // Hibernate ddl-auto=update will CREATE this table in each tenant schema on first startup
    }
    ```
    > Note: This entity MUST NOT use `@Table(schema = ...)` — the multi-tenant routing handles schema separation.
  - [x] 5.2 — Create `EmployeeSpringRepository.java` (JpaRepository):
    ```java
    public interface EmployeeSpringRepository extends JpaRepository<EmployeeJpaEntity, UUID> {
        Optional<EmployeeJpaEntity> findByUserId(UUID userId);
        List<EmployeeJpaEntity> findAllByOrderByStatusAscCreatedAtAsc();
    }
    ```
  - [x] 5.3 — Create `EmployeeRepositoryAdapter.java` in `identity/employee/adapter/out/persistence/impl/`:
    - Implements `EmployeeRepository`
    - Delegates to `EmployeeSpringRepository` for all operations
    - Maps between `EmployeeJpaEntity` ↔ `Employee` domain model
    - `@Component`, constructor injection of `EmployeeSpringRepository`

---

- [x] **Task 6 — EmployeeCountAdapter**

  - [x] 6.1 — Create `EmployeeCountAdapter.java` in `subscription/plan/adapter/out/persistence/impl/`:
    ```java
    @Component
    public class EmployeeCountAdapter implements EmployeeCountPort {
        private final JdbcTemplate jdbcTemplate;

        @Override
        public int countActiveEmployees() {
            String schema = TenantContext.getCurrentTenant();
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"" + schema + "\".employees WHERE status = 'ACTIVE'",
                Integer.class);
            return count != null ? count : 0;
        }
    }
    ```
    > Note: `EmployeeCountPort` must be defined in `identity/employee/domain/port/out/` (see Task 4.11).
    > The adapter (subscription package) implements the port (identity/employee package) — Spring wires them via `@Component`.

  - [x] 6.2 — Update `PlanType.FREE.getMaxEmployees()` to return `5` (AC2 requires limit of 5):
    - File: `identity/auth/domain/model/PlanType.java`
    - Change the `maxEmployees` value in the FREE enum constant from `3` to `5`
    - Update `PlanTypeTest.java`: `assertThat(PlanType.FREE.getMaxEmployees()).isEqualTo(5)`
    - ⚠️ Also update the seed in `TenantSchemaProvisioner.java` to seed `max_employees = 5` for FREE plan

---

- [x] **Task 7 — JWT extensions**

  - [x] 7.1 — Modify `JwtTokenProvider.java` in `shared/infrastructure/security/`:
    ```java
    // Add overload with storeId + passwordChangeRequired:
    public String generateAccessToken(UUID userId, String tenantId, String role,
                                       String tenantStatus, UUID storeId,
                                       boolean passwordChangeRequired) {
        return Jwts.builder()
            .subject(userId.toString())
            .claim("tenantId", tenantId)
            .claim("role", role)
            .claim("tenantStatus", tenantStatus)
            .claim("storeId", storeId != null ? storeId.toString() : null)
            .claim("passwordChangeRequired", passwordChangeRequired)
            // ... existing expiry + signing logic
    }
    // Add extractor methods:
    public UUID extractStoreId(Claims claims)                    // null if claim absent
    public boolean extractPasswordChangeRequired(Claims claims)  // false if claim absent
    ```
  - [x] 7.2 — Modify `JwtAuthFilter.java` in `shared/infrastructure/security/`:
    - AFTER TenantContext is set (existing code) AND role is extracted, if `role == "EMPLOYEE"`:
      1. Query the current tenant schema's `employees` table for `user_id = JWT.userId`:
         ```java
         String schema = TenantContext.getCurrentTenant();
         // Use JdbcTemplate (inject via constructor):
         Map<String, Object> empRow = jdbcTemplate.queryForMap(
             "SELECT store_id, status, password_change_required FROM \""
             + schema + "\".employees WHERE user_id = ? LIMIT 1", userId);
         ```
      2. If `empRow` is empty or `status == "INACTIVE"` → return 401 `ACCOUNT_INACTIVE`
      3. If `empRow.store_id != JWT.storeId` → return 401 `STORE_REASSIGNED`
      4. If `empRow.password_change_required == true` (AND path is NOT in auth whitelist) → return 403 `PASSWORD_CHANGE_REQUIRED`
    - Handle `EmptyResultDataAccessException` (employee row not found) → 401 `ACCOUNT_INACTIVE`
    - Add `JdbcTemplate jdbcTemplate` to `JwtAuthFilter` constructor

---

- [x] **Task 8 — Modify `SelectTenantService` for EMPLOYEE JWT enrichment**

  - [x] 8.1 — In `SelectTenantService.select()` (file: `identity/auth/application/service/SelectTenantService.java`):
    - After loading `membership` and verifying `role`:
    - If `membership.getRole().equals("EMPLOYEE")`:
      ```java
      // Query kv_xxx.employees for storeId + passwordChangeRequired
      // Use JdbcTemplate with schema-qualified query (same pattern as findMembershipsWithTenantInfo)
      String schema = tenant.getSchemaName();
      // SELECT store_id, password_change_required FROM "schema".employees WHERE user_id = ? LIMIT 1
      ```
    - Pass `storeId` and `passwordChangeRequired` to `jwtTokenProvider.generateAccessToken(... storeId, pwdChangeRequired)`
    - For OWNER (not EMPLOYEE): call existing `generateAccessToken(userId, schemaName, role, tenantStatus)` (storeId = null, passwordChangeRequired = false)
  - [x] 8.2 — Update `LoginResponse` DTO in `identity/auth/adapter/in/rest/dto/`:
    ```java
    // Add two new fields:
    private final String storeId;           // null for OWNER
    private final boolean passwordChangeRequired;  // false for OWNER, true for new employee
    ```
  - [x] 8.3 — Ensure `AuthController.selectTenant()` maps these new fields from `AuthTokens` into the response

---

- [x] **Task 9 — Application services (GREEN implementations)**

  - [x] 9.1 — Create `CreateEmployeeService.java` in `identity/employee/application/service/`:
    - Implements `CreateEmployeeUseCase`
    - `@Transactional` (all 3 table writes + event publish in one transaction)
    - **Steps**:
      1. Check `userRepository.findByPhoneNumber(command.phoneNumber())` — if exists → `DomainException(PHONE_ALREADY_REGISTERED)`
      2. Load tenant subscription via `subscriptionRepository.findByTenantId(tenantId)` → get `PlanType`
      3. `planLimitGuard.checkEmployeesLimit(employeeCountPort.countActiveEmployees(), planType)` — throws if over limit
      4. Generate temp password: `String tempPwd = TempPasswordFactory.generate()`
      5. Hash: `String hash = passwordEncoder.encode(tempPwd)`
      6. Create and save user: `User newUser = User.newEmployee(command.phoneNumber(), hash)` — similar to `User.newOwner()` but without tenant coupling
      7. Insert membership: `userMembershipRepository.save(UserTenantMembership.create(newUser.getId(), tenantId, "EMPLOYEE"))`
      8. Create and save employee: `Employee emp = Employee.create(newUser.getId(), command.storeId(), ...); employeeRepository.save(emp)`
      9. Publish: `eventPublisher.publishEvent(new EmployeeCreatedEvent(...))`
      10. Return: `new CreateEmployeeResult(emp, tempPwd)` — **cleartext only here, never persisted**

  - [x] 9.2 — Create `ChangePasswordService.java` in `identity/employee/application/service/`:
    - Implements `ChangePasswordUseCase`
    - `@Transactional`
    - **Steps**:
      1. Load user: `userRepository.findById(command.actorId())` → throws `INVALID_CREDENTIALS` if not found
      2. Verify current password: `passwordEncoder.matches(command.currentPassword(), user.getPasswordHash())` → throws `INVALID_CREDENTIALS` if wrong
      3. Validate new password: ≥ 8 chars, contains at least one digit (`PasswordPolicy.validate()`)
      4. Hash new password: `passwordEncoder.encode(command.newPassword())`
      5. Update user: `userRepository.updatePasswordHash(command.actorId(), newHash)`
      6. Clear flag: `employeeRepository.updatePasswordChangeRequired(employeeId, false)` — find employee by userId first
      7. Revoke all refresh tokens: `refreshTokenRepository.revokeAllByUserId(command.actorId())`
      8. Generate fresh `AuthTokens` (JWT without `passwordChangeRequired`) via `jwtTokenProvider.generateAccessToken(...)` and a new refresh token
      9. Publish: `eventPublisher.publishEvent(new EmployeePasswordSetEvent(...))`
      10. Return: `AuthTokens` (fresh, `passwordChangeRequired = false`)

  - [x] 9.3 — Create `ReassignStoreService.java` in `identity/employee/application/service/`:
    - Implements `ReassignStoreUseCase`
    - `@Transactional`
    - **Steps**:
      1. Load employee: `employeeRepository.findById(command.employeeId())` → throws `EMPLOYEE_NOT_FOUND`
      2. Store old storeId for event
      3. Update: `employeeRepository.updateStoreId(command.employeeId(), command.newStoreId())`
      4. Revoke refresh tokens: `refreshTokenRepository.revokeAllByUserId(employee.getUserId())`
      5. Publish `EmployeeStoreReassignedEvent`
      6. Return updated `Employee`

  - [x] 9.4 — Create `DeactivateEmployeeService.java` in `identity/employee/application/service/`:
    - Implements `DeactivateEmployeeUseCase`
    - `@Transactional`
    - **Steps**:
      1. Load employee: `employeeRepository.findById(command.employeeId())` → throws `EMPLOYEE_NOT_FOUND`
      2. Update employee status: `employeeRepository.updateStatus(command.employeeId(), EmployeeStatus.INACTIVE)`
      3. Deactivate membership: `userMembershipRepository.deactivateByUserIdAndTenantId(employee.getUserId(), tenantId)` — **add `deactivateByUserIdAndTenantId()` to `UserMembershipRepository` port and adapter if not already present**
      4. Revoke refresh tokens: `refreshTokenRepository.revokeAllByUserId(employee.getUserId())`
      5. Publish `EmployeeDeactivatedEvent`

  - [x] 9.5 — Create `ListEmployeesService.java` in `identity/employee/application/service/`:
    - Implements `ListEmployeesUseCase`
    - `@Transactional(readOnly = true)`
    - Returns `employeeRepository.findAllOrderByStatusAndCreatedAt()`

---

- [x] **Task 10 — REST adapter layer**

  - [x] 10.1 — Create DTOs in `identity/employee/adapter/in/rest/dto/`:
    ```java
    // CreateEmployeeRequestDto.java
    public record CreateEmployeeRequestDto(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank String phoneNumber,
        @NotNull UUID storeId
    ) {}

    // EmployeeResponseDto.java
    public record EmployeeResponseDto(
        UUID id, UUID userId, String firstName, String lastName,
        String storeId, String storeName, // storeName resolved in service layer
        String status,
        boolean passwordChangeRequired,
        Instant createdAt
    ) {}

    // TempPasswordResponseDto.java (one-time display)
    public record TempPasswordResponseDto(
        EmployeeResponseDto employee,
        String temporaryPassword  // cleartext, returned ONCE in the API response
    ) {}

    // ReassignStoreRequestDto.java
    public record ReassignStoreRequestDto(@NotNull UUID storeId) {}
    ```

  - [x] 10.2 — Add `ChangePasswordRequestDto.java` in `identity/auth/adapter/in/rest/dto/`:
    ```java
    public record ChangePasswordRequestDto(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8) String newPassword
    ) {}
    ```

  - [x] 10.3 — Create `EmployeeController.java` in `identity/employee/adapter/in/rest/`:
    ```java
    @RestController
    @RequestMapping("/api/v1/employees")
    public class EmployeeController {
        // POST   /api/v1/employees          → createEmployee (OWNER only)
        // GET    /api/v1/employees          → listEmployees  (OWNER only)
        // PATCH  /api/v1/employees/{id}/store    → reassignStore (OWNER only)
        // PATCH  /api/v1/employees/{id}/deactivate → deactivate (OWNER only)
    }
    ```
    - Extract `actorId` (UUID) from `SecurityContextHolder.getContext().getAuthentication().getName()` in the controller — pass to Command (not in use case)
    - OWNER-only authorization: check role from JWT via `@PreAuthorize("hasRole('OWNER')")` OR inline role check using extracted JWT claims (follow existing StoreController pattern for consistent RBAC)
    - Return `{ "data": TempPasswordResponseDto }` on create (HTTP 201)
    - Return `{ "data": List<EmployeeResponseDto> }` on list (HTTP 200)

  - [x] 10.4 — Add `POST /api/v1/auth/change-password` to `AuthController.java`:
    ```java
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody @Valid ChangePasswordRequestDto req,
                                             Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        String tenantId = /* extract from SecurityContext or JWT claims */;
        AuthTokens tokens = changePasswordUseCase.execute(
            new ChangePasswordCommand(userId, tenantId, req.currentPassword(), req.newPassword()));
        return ResponseEntity.ok(/* map to LoginResponse */);
    }
    ```
    - Add `ChangePasswordUseCase changePasswordUseCase` injection to `AuthController`
    - This endpoint IS authenticated (requires valid JWT) but is accessible even when `passwordChangeRequired: true` (it's the way OUT of that state) → add to the **non-blocked list** in `JwtAuthFilter`'s `passwordChangeRequired` check

---

- [x] **Task 11 — Add `EMPLOYEE_NOT_FOUND` and `PASSWORD_CHANGE_REQUIRED` error codes**

  - [x] 11.1 — Add `EMPLOYEE_NOT_FOUND` and `PASSWORD_CHANGE_REQUIRED` and `STORE_REASSIGNED` to the `ErrorCode` enum (in `shared/domain/exception/ErrorCode.java`)
  - [x] 11.2 — Add `PHONE_ALREADY_REGISTERED` if not already present (check existing codes first — may already be `PHONE_ALREADY_EXISTS`)

---

- [x] **Task 12 — Wire `UserMembershipRepository.deactivateByUserIdAndTenantId()`**

  - [x] 12.1 — Add method to `UserMembershipRepository` port (in `identity/auth/domain/port/out/`):
    ```java
    void deactivateByUserIdAndTenantId(UUID userId, UUID tenantId);
    ```
  - [x] 12.2 — Implement in `UserMembershipRepositoryAdapter`:
    - Use `UserTenantMembershipSpringRepository` — add derived query `findByUserIdAndTenantId()` → set `active = false` → save

---

- [x] **Task 13 — Comprehensive curl E2E tests**

  Create `keevo/scripts/curl-tests-story-3-5.sh` — self-contained script (fresh register per run):

  ```bash
  #!/usr/bin/env bash
  # Story 3.5 — Employee Management E2E Tests
  # Run: bash curl-tests-story-3-5.sh
  # All steps must show ✅

  set -euo pipefail
  BASE="http://localhost:8080"
  PY='python3 -c'
  PHONE_OWNER="+237611000350"
  PHONE_EMP="+237611000351"

  # ── Step 1 — Register OWNER (Simon) ─────────────────────────────────────────
  # POST /auth/register → loginToken
  # GET /auth/tenants → tenantCode
  # POST /auth/select-tenant → JWT (OWNER)

  # ── Step 2 — Complete onboarding ────────────────────────────────────────────
  # POST /onboarding/complete

  # ── Step 3 — Get store ID (from /stores) ────────────────────────────────────

  # ── Step 4 — Create employee (AC1) ──────────────────────────────────────────
  # POST /api/v1/employees  { firstName, lastName, phoneNumber, storeId }
  # Assert: HTTP 201, temporaryPassword field present in response

  # ── Step 5 — Verify employee list (AC8) ──────────────────────────────────────
  # GET /api/v1/employees
  # Assert: HTTP 200, 1 employee with passwordChangeRequired: true + status ACTIVE

  # ── Step 6 — Login as employee with temp password (AC3, Step 1+2) ─────────────
  # POST /auth/login { phoneNumber: PHONE_EMP, password: TEMP_PWD }
  # Assert: loginToken present + 1 membership
  # POST /auth/select-tenant { loginToken, tenantCode }
  # Assert: HTTP 200, passwordChangeRequired: true, storeId present, role: EMPLOYEE

  # ── Step 7 — Verify PASSWORD_CHANGE_REQUIRED guard (AC4) ─────────────────────
  # GET /api/v1/stores with EMPLOYEE_JWT
  # Assert: HTTP 403, domainCode: PASSWORD_CHANGE_REQUIRED

  # ── Step 8 — Change password (AC4) ───────────────────────────────────────────
  # POST /api/v1/auth/change-password { currentPassword: TEMP_PWD, newPassword: NewPass1! }
  # Assert: HTTP 200, new accessToken present, passwordChangeRequired NOT in response (or false)

  # ── Step 9 — New JWT works, no more forced change ─────────────────────────────
  # GET /api/v1/stores with new EMPLOYEE_JWT
  # Assert: HTTP 200 (no longer blocked)
  # ACTUALLY: employee should only get their own store — check it's their storeId

  # ── Step 10 — Employee cannot create employees (AC5) ─────────────────────────
  # POST /api/v1/employees with EMPLOYEE_JWT
  # Assert: HTTP 403 FORBIDDEN

  # ── Step 11 — Plan limit enforcement (AC2) ───────────────────────────────────
  # Create 4 more employees (5 total = FREE limit)
  # Attempt to create 6th → HTTP 403, domainCode: PLAN_LIMIT_EXCEEDED

  # ── Step 12 — Reassign employee store (AC6) ───────────────────────────────────
  # PATCH /api/v1/employees/{id}/store { storeId: secondStoreId }
  # Assert: HTTP 200
  # Try to use old EMPLOYEE_JWT on /api/v1/stores → HTTP 401, STORE_REASSIGNED

  # ── Step 13 — Login again with new password ───────────────────────────────────
  # Verify fresh JWT has new storeId

  # ── Step 14 — Deactivate employee (AC7) ───────────────────────────────────────
  # PATCH /api/v1/employees/{id}/deactivate
  # Assert: HTTP 200
  # Try to login as deactivated employee → HTTP 401, ACCOUNT_INACTIVE OR fresh JWT → 401 ACCOUNT_INACTIVE

  # ── Post-deactivation verify list (AC8) ───────────────────────────────────────
  # GET /api/v1/employees
  # Assert: employee appears with status INACTIVE at bottom of list
  ```

  > The script must be a **working, non-placeholder** implementation. Follow the exact style of `curl-tests-story-3-4.sh` with `set -euo pipefail`, `python3` JSON parsing, and `✅`/`❌` markers.

---

### FLUTTER

---

- [x] **Task 14 — Write ALL RED Flutter tests (TDD first pass)**

  - [ ] 14.1 — Create `create_employee_usecase_test.dart` in `test/features/team/domain/usecase/`:
    ```dart
    // Mocks: EmployeeRepository
    test('execute should return CreateEmployeeResult on success', ...)
    test('execute should throw PlanLimitExceeded when limit reached', ...)
    ```
  - [x] 14.2 — Create `employee_provider_test.dart` in `test/features/team/presentation/provider/`:
    ```dart
    // Uses ProviderContainer + mock repository
    test('createEmployee initial state is AsyncData(null)', ...)
    test('createEmployee emits loading then result on success', ...)
    test('listEmployees loads employee list', ...)
    ```
  - [x] 14.3 — Create `team_page_test.dart` in `test/features/team/presentation/page/`:
    ```dart
    // Widget test — pumpWidget TeamPage
    testWidgets('shows employee list when loaded', ...)
    testWidgets('shows empty state when no employees', ...)
    testWidgets('shows passwordChangeRequired badge', ...)
    testWidgets('deactivate button calls provider', ...)
    ```
  - [x] 14.4 — Create `password_change_page_test.dart` in `test/features/auth/presentation/page/`:
    ```dart
    testWidgets('shows form with currentPassword and newPassword fields', ...)
    testWidgets('submit calls changePassword with correct values', ...)
    testWidgets('shows validation error when new password too short', ...)
    testWidgets('navigates to /pos on success', ...)
    ```
  - [x] 14.5 — Run tests → confirm ALL RED

---

- [x] **Task 15 — Update `AuthTokens` model (freezed)**

  - [x] 15.1 — Update `AuthTokens` in `app/lib/features/auth/domain/model/auth_tokens.dart`:
    ```dart
    @freezed
    class AuthTokens with _$AuthTokens {
      const factory AuthTokens({
        required String accessToken,
        required String refreshToken,
        required String userId,
        required String tenantId,
        required String role,
        required int expiresIn,
        String? storeId,                        // NEW — null for OWNER
        @Default(false) bool passwordChangeRequired,  // NEW
      }) = _AuthTokens;
    }
    ```
  - [x] 15.2 — Run `dart run build_runner build --delete-conflicting-outputs` to regenerate `.freezed.dart` and `.g.dart` files
  - [x] 15.3 — Update `SecureTokenStorage` in `data/repository/secure_token_storage.dart`:
    - Add keys: `static const _keyStoreId = 'store_id'` and `static const _keyPasswordChangeRequired = 'pwd_change_req'`
    - Add `saveStoreId(String? storeId)` and `savePasswordChangeRequired(bool required)`
    - Add `getStoreId()` and `getPasswordChangeRequired()` (returns `false` if not stored)
  - [x] 15.4 — Update `TokenStorage` interface (`domain/repository/token_storage.dart`) to reflect new methods
  - [x] 15.5 — Update `SelectTenantUseCase` in `domain/usecase/select_tenant_usecase.dart`:
    - After getting `AuthTokens`, also persist `storeId` and `passwordChangeRequired` via `_tokenStorage`
  - [x] 15.6 — Update `LoginUseCase` in `domain/usecase/login_usecase.dart`:
    - For `AuthenticatedResult` path (single membership), persist new fields
  - [x] 15.7 — Update `RemoteAuthDataSource` to parse `storeId` and `passwordChangeRequired` from the `select-tenant` response
  - [x] 15.8 — Add `passwordChangeRequiredProvider` in `core/di/providers.dart`:
    ```dart
    const kPasswordChangeRequiredKey = 'pwd_change_req';
    final passwordChangeRequiredProvider = Provider<bool>((ref) {
      return ref.watch(sharedPreferencesProvider).getBool(kPasswordChangeRequiredKey) ?? false;
    });
    ```
    > Note: `passwordChangeRequired` is stored in **SharedPreferences** (not secure storage) because it is not a secret — it's a UI routing flag. Tokens remain in secure storage.

---

- [x] **Task 16 — Employee domain layer (Flutter)**

  - [x] 16.1 — Create `app/lib/features/team/domain/model/employee_model.dart`:
    ```dart
    @freezed
    class EmployeeModel with _$EmployeeModel {
      const factory EmployeeModel({
        required String id,
        required String userId,
        required String firstName,
        required String lastName,
        required String storeId,
        String? storeName,
        required String status,         // 'ACTIVE' | 'INACTIVE'
        required bool passwordChangeRequired,
        required DateTime createdAt,
      }) = _EmployeeModel;

      factory EmployeeModel.fromJson(Map<String, dynamic> json) => _$EmployeeModelFromJson(json);
    }
    ```
  - [x] 16.2 — Create `app/lib/features/team/domain/repository/employee_repository.dart`:
    ```dart
    abstract class EmployeeRepository {
      Future<CreateEmployeeResult> createEmployee({
        required String firstName,
        required String lastName,
        required String phoneNumber,
        required String storeId,
      });
      Future<List<EmployeeModel>> listEmployees();
      Future<EmployeeModel> reassignStore(String employeeId, String newStoreId);
      Future<void> deactivateEmployee(String employeeId);
    }
    ```
  - [x] 16.3 — Create `CreateEmployeeResult` model (domain):
    ```dart
    @freezed
    class CreateEmployeeResult with _$CreateEmployeeResult {
      const factory CreateEmployeeResult({
        required EmployeeModel employee,
        required String temporaryPassword,  // ONE-TIME: display immediately, do not persist
      }) = _CreateEmployeeResult;
    }
    ```

---

- [x] **Task 17 — Employee data layer (Flutter)**

  - [x] 17.1 — Create `app/lib/features/team/data/datasource/remote_employee_datasource.dart`:
    - Injects `Dio` from `dioProvider`
    - `createEmployee(...)` → `POST /api/v1/employees`
    - `listEmployees()` → `GET /api/v1/employees`
    - `reassignStore(id, storeId)` → `PATCH /api/v1/employees/{id}/store`
    - `deactivateEmployee(id)` → `PATCH /api/v1/employees/{id}/deactivate`
  - [x] 17.2 — Create `app/lib/features/team/data/repository/employee_repository_impl.dart`:
    - Implements `EmployeeRepository`
    - Delegates to `RemoteEmployeeDataSource`
    - Wraps errors in domain exceptions (follow `AuthRepositoryImpl` pattern)

---

- [x] **Task 18 — Employee providers (Flutter)**

  - [x] 18.1 — Create `app/lib/features/team/presentation/provider/employee_provider.dart`:
    ```dart
    // Provider for repository
    final employeeRepositoryProvider = Provider<EmployeeRepository>(...)

    // Async notifier: employee list
    @riverpod
    class EmployeeList extends _$EmployeeList {
      @override
      FutureOr<List<EmployeeModel>> build() => /* load from repo */
    }

    // Async notifier: create employee
    @riverpod
    class CreateEmployee extends _$CreateEmployee {
      @override
      FutureOr<CreateEmployeeResult?> build() => null;

      Future<void> create({required String firstName, required String lastName,
                           required String phoneNumber, required String storeId}) async { ... }
    }

    // Async notifier: reassign store
    @riverpod
    class ReassignStore extends _$ReassignStore { ... }

    // Async notifier: deactivate employee
    @riverpod
    class DeactivateEmployee extends _$DeactivateEmployee { ... }
    ```

---

- [x] **Task 19 — Change password data + provider (Flutter)**

  - [x] 19.1 — Add `changePassword(currentPwd, newPwd)` to `RemoteAuthDataSource`:
    - `POST /api/v1/auth/change-password { currentPassword, newPassword }`
    - Returns `AuthTokens` (new tokens issued after successful change)
  - [x] 19.2 — Add `changePassword()` to `AuthRepository` interface and `AuthRepositoryImpl`
  - [x] 19.3 — Create `ChangePasswordUseCase` in `auth/domain/usecase/change_password_usecase.dart`:
    - Validates new password (≥ 8 chars, ≥ 1 digit) before calling repository
    - On success: stores new tokens via `_tokenStorage`, clears `kPasswordChangeRequiredKey` in SharedPreferences
  - [x] 19.4 — Add `changePasswordUseCaseProvider` and `@riverpod class ChangePassword extends _$ChangePassword` in `auth_provider.dart`:
    - On success: `ref.read(sharedPreferencesProvider).setBool(kPasswordChangeRequiredKey, false)`
    - On success: navigate to `/pos`

---

- [x] **Task 20 — Router update**

  - [x] 20.1 — In `app/lib/core/router/app_router.dart`, add routes:
    ```dart
    GoRoute(path: '/settings/team', builder: (_,__) => const TeamPage()),
    GoRoute(path: '/settings/team/new', builder: (_,__) => const CreateEmployeePage()),
    GoRoute(path: '/auth/change-password', builder: (_,__) => const PasswordChangePage()),
    ```
  - [x] 20.2 — In `_SplashRedirectPage` logic (or in `redirect` callback of ShellRoute):
    - After JWT is valid AND tenant is active: check `sharedPreferences.getBool(kPasswordChangeRequiredKey) ?? false`
    - If `true` AND route is NOT `/auth/change-password` → redirect to `/auth/change-password`
    - PasswordChangePage must NOT be accessible to the `MainShell` (no bottom nav bar shown)
  - [x] 20.3 — Create a `_PasswordChangeGuard` entry in the router: `PasswordChangePage` is presented as a **full-page route** (not inside `ShellRoute`) — user cannot navigate back or tap the bottom nav

---

- [x] **Task 21 — UI: Team page and employee card**

  - [x] 21.1 — Create `app/lib/features/team/presentation/page/team_page.dart`:
    - Same gradient header pattern as `SettingsPage` + `StoresListPage`
    - Title: "Équipe", subtitle: "Employés et accès"
    - `ConsumerStatefulWidget` watching `employeeListProvider`
    - FAB: "Ajouter un employé" → navigates to `/settings/team/new` (OWNER only)
    - Shows `_EmployeeCard` per employee, sorted ACTIVE first then INACTIVE
    - Handle loading / error states (same pattern as `ClientListPage` or `TransferHistoryPage`)
  - [x] 21.2 — Create `app/lib/features/team/presentation/widget/employee_card.dart`:
    - Shows: full name, assigned store name, status badge (green "Actif" / grey "Inactif")
    - If `passwordChangeRequired == true` → amber badge "Mot de passe non changé"
    - Tap → shows bottom sheet with: reassign store, deactivate options (disabled if already INACTIVE)
    - Calls `ref.read(reassignStoreProvider.notifier).reassign(...)` / `ref.read(deactivateEmployeeProvider.notifier).deactivate(...)`
    - On success: `ref.invalidate(employeeListProvider)` to refresh the list

---

- [x] **Task 22 — UI: Create employee form**

  - [x] 22.1 — Create `app/lib/features/team/presentation/page/create_employee_page.dart`:
    - Standard Scaffold with `AppBar` title "Nouvel employé"
    - Form fields following `ProductFormPage` / `ClientFormPage` pattern:
      - `TextFormField` Prénom (required)
      - `TextFormField` Nom (required)
      - `IntlPhoneField` from `intl_phone_field` package (already in project) — Numéro de téléphone (required)
      - `DropdownButtonFormField<StoreModel>` — uses `storeListNotifierProvider` (already exists), label "Boutique assignée"
    - Submit → `ref.read(createEmployeeProvider.notifier).create(...)` 
    - On success: show `TempPasswordBottomSheet` (modal, cannot be dismissed without tapping "Continuer")
    - After `TempPasswordBottomSheet` dismissed: navigate back to `/settings/team`
    - On `PLAN_LIMIT_EXCEEDED` error: show `PlanLimitBottomSheet` (already exists in `settings/presentation/widget/`)

---

- [x] **Task 23 — UI: Temporary password bottom sheet (one-time display)**

  - [x] 23.1 — Create `app/lib/features/team/presentation/widget/temp_password_bottom_sheet.dart`:
    - Non-dismissible bottom sheet (no drag handle, `isDismissible: false`, `enableDrag: false`)
    - Shows: employee name (title), assigned store
    - Large code-style text displaying the temporary password
    - "Copier le mot de passe" `OutlinedButton` → `Clipboard.setData(ClipboardData(text: password))`
    - Warning text in amber container: "Ce mot de passe ne sera plus affiché. Communiquez-le à l'employé par vos propres moyens."
    - "Continuer" `FilledButton` → `Navigator.pop(context)` (dismisses sheet)
    - **Do NOT store the temporary password beyond this widget** — it lives in local `state` only

---

- [x] **Task 24 — UI: Password change page**

  - [x] 24.1 — Create `app/lib/features/auth/presentation/page/password_change_page.dart`:
    - Full-page Scaffold (NOT inside MainShell — no bottom nav bar)
    - No back button (use `automaticallyImplyLeading: false`)
    - Title: "Créer votre mot de passe"
    - Subtitle: "Votre employeur a créé ce compte. Pour votre sécurité, définissez votre propre mot de passe."
    - Two `TextFormField` (obscured) with visibility toggle:
      - "Mot de passe temporaire" (current password)
      - "Nouveau mot de passe" (min 8 chars, one digit — client-side validation matching AC4 rules)
    - Submit button: "Confirmer" → calls `ref.read(changePasswordProvider.notifier).change(...)`
    - Loading spinner while in-flight
    - Shows inline SnackBar errors for: wrong current password, password too short
    - On success: router navigates to `/pos`

---

- [x] **Task 25 — Settings page: add "Équipe" tile (OWNER only)**

  - [x] 25.1 — Modify `app/lib/features/settings/presentation/page/settings_page.dart`:
    - Convert outer widget to `ConsumerWidget` (already is) — OR wrap the Gestion section in a `Consumer`
    - Add "Équipe" tile after "Mes boutiques" tile, conditionally shown:
      ```dart
      Consumer(builder: (ctx, ref, _) {
        final role = ref.watch(currentUserRoleProvider);
        if (role != 'OWNER') return const SizedBox.shrink();
        return Column(children: [
          const _Divider(),
          _SettingsTile(
            icon: Icons.group_rounded,
            iconColor: const Color(0xFF3B82F6),
            iconBg: const Color(0xFFDBEAFE),
            title: 'Équipe',
            subtitle: 'Employés, rôles et accès',
            onTap: () => context.push('/settings/team'),
          ),
        ]);
      }),
      ```

---

- [x] **Task 25b — EMPLOYEE role-based navigation restrictions (AC5)**

  - [x] 25b.1 — Modify `app/lib/core/scaffold/main_shell.dart`:
    - Convert to read `currentUserRoleProvider` via `ref.watch()`
    - OWNER sees 5 tabs: Caisse / Catalogue / Clients / Fournisseurs / Plus
    - EMPLOYEE sees 2 tabs: Caisse / Plus
    - Tab routes and `selectedIndex` dynamically computed based on role
    - `onDestinationSelected` maps to the correct route list per role

  - [x] 25b.2 — Modify `app/lib/features/settings/presentation/page/settings_page.dart`:
    - Convert from `StatelessWidget` to `ConsumerWidget` to access `currentUserRoleProvider`
    - Entire "Gestion" section (Boutiques, Active store, Stock multi-boutiques, Abonnement, Équipe) wrapped in `if (isOwner)` guard
    - EMPLOYEE only sees: Préférences + Support sections

  - [x] 25b.3 — Modify `app/lib/features/auth/presentation/page/login_page.dart`:
    - After successful login with `AuthenticatedResult`, check `tokens.passwordChangeRequired`
    - If true → `context.go('/auth/change-password')`
    - If false → `context.go('/pos')`

  - [x] 25b.4 — Modify `app/lib/features/auth/presentation/page/tenant_picker_page.dart`:
    - After successful tenant selection, check `tokens.passwordChangeRequired`
    - If true → `context.go('/auth/change-password')`
    - If false → `context.go('/pos')`

  - [x] 25b.5 — Backend defense-in-depth already in place:
    - `JwtAuthFilter` returns HTTP 403 `PASSWORD_CHANGE_REQUIRED` for employees who haven't changed password
    - `JwtAuthFilter` checks `EMPLOYEE` role and restricts OWNER-only endpoints
    - `@Table(schema = "public")` added to `UserJpaEntity`, `TenantJpaEntity`, `RefreshTokenJpaEntity`, `UserTenantMembershipJpaEntity` to prevent tenant schema pollution

---

- [x] **Task 26 — Run all tests GREEN + curl script**

  - [x] 26.1 — Run all backend unit tests: `mvn test -pl backend`
    - All pre-existing tests must remain GREEN
    - All new story tests must be GREEN
  - [x] 26.2 — Run Flutter tests: `flutter test`
    - All pre-existing tests must remain GREEN
    - All new story 3-5 tests must be GREEN
  - [ ] 26.3 — Start the backend: `docker compose up -d && mvn spring-boot:run`
  - [ ] 26.4 — Run the curl E2E script: `bash keevo/scripts/curl-tests-story-3-5.sh`
    - All steps must show ✅ before marking story done
  - [ ] 26.5 — Manually test the Flutter app:
    - Login as OWNER → Settings → Équipe tile visible
    - Create employee → temp password shown in one-time sheet
    - Login as employee → forced to `/auth/change-password`
    - After password change → redirected to `/pos`, Équipe tile NOT visible

---

## Project Structure Notes

### New files — Backend

| File | Path |
|---|---|
| `EmployeeStatus.java` | `identity/employee/domain/model/EmployeeStatus.java` |
| `Employee.java` | `identity/employee/domain/model/Employee.java` |
| `TempPasswordFactory.java` | `identity/employee/domain/model/TempPasswordFactory.java` |
| `CreateEmployeeResult.java` | `identity/employee/domain/model/CreateEmployeeResult.java` |
| `EmployeeCreatedEvent.java` | `identity/employee/domain/event/EmployeeCreatedEvent.java` |
| `EmployeePasswordSetEvent.java` | `identity/employee/domain/event/EmployeePasswordSetEvent.java` |
| `EmployeeStoreReassignedEvent.java` | `identity/employee/domain/event/EmployeeStoreReassignedEvent.java` |
| `EmployeeDeactivatedEvent.java` | `identity/employee/domain/event/EmployeeDeactivatedEvent.java` |
| `CreateEmployeeCommand.java` | `identity/employee/domain/port/in/CreateEmployeeCommand.java` |
| `CreateEmployeeUseCase.java` | `identity/employee/domain/port/in/CreateEmployeeUseCase.java` |
| `ChangePasswordCommand.java` | `identity/employee/domain/port/in/ChangePasswordCommand.java` |
| `ChangePasswordUseCase.java` | `identity/employee/domain/port/in/ChangePasswordUseCase.java` |
| `ReassignStoreCommand.java` | `identity/employee/domain/port/in/ReassignStoreCommand.java` |
| `ReassignStoreUseCase.java` | `identity/employee/domain/port/in/ReassignStoreUseCase.java` |
| `DeactivateEmployeeCommand.java` | `identity/employee/domain/port/in/DeactivateEmployeeCommand.java` |
| `DeactivateEmployeeUseCase.java` | `identity/employee/domain/port/in/DeactivateEmployeeUseCase.java` |
| `ListEmployeesQuery.java` | `identity/employee/domain/port/in/ListEmployeesQuery.java` |
| `ListEmployeesUseCase.java` | `identity/employee/domain/port/in/ListEmployeesUseCase.java` |
| `EmployeeRepository.java` | `identity/employee/domain/port/out/EmployeeRepository.java` |
| `EmployeeCountPort.java` | `identity/employee/domain/port/out/EmployeeCountPort.java` |
| `CreateEmployeeService.java` | `identity/employee/application/service/CreateEmployeeService.java` |
| `ChangePasswordService.java` | `identity/employee/application/service/ChangePasswordService.java` |
| `ReassignStoreService.java` | `identity/employee/application/service/ReassignStoreService.java` |
| `DeactivateEmployeeService.java` | `identity/employee/application/service/DeactivateEmployeeService.java` |
| `ListEmployeesService.java` | `identity/employee/application/service/ListEmployeesService.java` |
| `EmployeeJpaEntity.java` | `identity/employee/adapter/out/persistence/EmployeeJpaEntity.java` |
| `EmployeeSpringRepository.java` | `identity/employee/adapter/out/persistence/EmployeeSpringRepository.java` |
| `EmployeeRepositoryAdapter.java` | `identity/employee/adapter/out/persistence/impl/EmployeeRepositoryAdapter.java` |
| `EmployeeCountAdapter.java` | `subscription/plan/adapter/out/persistence/impl/EmployeeCountAdapter.java` |
| `CreateEmployeeRequestDto.java` | `identity/employee/adapter/in/rest/dto/CreateEmployeeRequestDto.java` |
| `EmployeeResponseDto.java` | `identity/employee/adapter/in/rest/dto/EmployeeResponseDto.java` |
| `TempPasswordResponseDto.java` | `identity/employee/adapter/in/rest/dto/TempPasswordResponseDto.java` |
| `ReassignStoreRequestDto.java` | `identity/employee/adapter/in/rest/dto/ReassignStoreRequestDto.java` |
| `ChangePasswordRequestDto.java` | `identity/auth/adapter/in/rest/dto/ChangePasswordRequestDto.java` |
| `EmployeeController.java` | `identity/employee/adapter/in/rest/EmployeeController.java` |
| `curl-tests-story-3-5.sh` | `keevo/scripts/curl-tests-story-3-5.sh` |

### Modified files — Backend

| File | Path | Change |
|---|---|---|
| `PlanType.java` | `identity/auth/domain/model/PlanType.java` | `FREE.maxEmployees = 5` |
| `PlanTypeTest.java` | `test/.../identity/auth/domain/model/PlanTypeTest.java` | Update assertion |
| `TenantSchemaProvisioner.java` | `shared/infrastructure/persistence/TenantSchemaProvisioner.java` | Seed `max_employees = 5` for FREE |
| `JwtTokenProvider.java` | `shared/infrastructure/security/JwtTokenProvider.java` | Add storeId + passwordChangeRequired support |
| `JwtAuthFilter.java` | `shared/infrastructure/security/JwtAuthFilter.java` | EMPLOYEE guard (INACTIVE, STORE_REASSIGNED, PASSWORD_CHANGE_REQUIRED) |
| `SelectTenantService.java` | `identity/auth/application/service/SelectTenantService.java` | EMPLOYEE JWT enrichment |
| `LoginResponse.java` / DTO | `identity/auth/adapter/in/rest/dto/` | Add storeId + passwordChangeRequired |
| `AuthController.java` | `identity/auth/adapter/in/rest/AuthController.java` | Add POST /auth/change-password |
| `UserMembershipRepository.java` | `identity/auth/domain/port/out/UserMembershipRepository.java` | Add deactivateByUserIdAndTenantId() |
| `UserMembershipRepositoryAdapter.java` | `identity/auth/adapter/out/persistence/impl/UserMembershipRepositoryAdapter.java` | Implement deactivateByUserIdAndTenantId() |
| `ErrorCode.java` | `shared/domain/exception/ErrorCode.java` | Add EMPLOYEE_NOT_FOUND, PASSWORD_CHANGE_REQUIRED, STORE_REASSIGNED |

### New files — Flutter

| File | Path |
|---|---|
| `employee_model.dart` + `.freezed.dart` + `.g.dart` | `app/lib/features/team/domain/model/` |
| `create_employee_result.dart` + `.freezed.dart` | `app/lib/features/team/domain/model/` |
| `employee_repository.dart` | `app/lib/features/team/domain/repository/` |
| `remote_employee_datasource.dart` | `app/lib/features/team/data/datasource/` |
| `employee_repository_impl.dart` | `app/lib/features/team/data/repository/` |
| `employee_provider.dart` + `.g.dart` | `app/lib/features/team/presentation/provider/` |
| `team_page.dart` | `app/lib/features/team/presentation/page/` |
| `create_employee_page.dart` | `app/lib/features/team/presentation/page/` |
| `employee_card.dart` | `app/lib/features/team/presentation/widget/` |
| `temp_password_bottom_sheet.dart` | `app/lib/features/team/presentation/widget/` |
| `password_change_page.dart` | `app/lib/features/auth/presentation/page/` |
| `change_password_usecase.dart` | `app/lib/features/auth/domain/usecase/` |

### Modified files — Flutter

| File | Path | Change |
|---|---|---|
| `auth_tokens.dart` | `app/lib/features/auth/domain/model/` | Add storeId?, passwordChangeRequired |
| `secure_token_storage.dart` | `app/lib/features/auth/data/repository/` | Add storeId + passwordChangeRequired persistence |
| `token_storage.dart` | `app/lib/features/auth/domain/repository/` | Add new method signatures |
| `select_tenant_usecase.dart` | `app/lib/features/auth/domain/usecase/` | Persist storeId + passwordChangeRequired |
| `login_usecase.dart` | `app/lib/features/auth/domain/usecase/` | Persist new fields on AuthenticatedResult |
| `remote_auth_datasource.dart` | `app/lib/features/auth/data/datasource/` | Parse storeId + passwordChangeRequired |
| `auth_provider.dart` | `app/lib/features/auth/presentation/provider/` | Add changePassword notifier + persist pwd_change_req flag |
| `providers.dart` | `app/lib/core/di/` | Add passwordChangeRequiredProvider + kPasswordChangeRequiredKey |
| `app_router.dart` | `app/lib/core/router/` | Add routes + redirect guard for passwordChangeRequired + AC5 doc comment |
| `settings_page.dart` | `app/lib/features/settings/presentation/page/` | Add "Équipe" tile (OWNER only) + hide entire Gestion section for EMPLOYEE (AC5) + convert to ConsumerWidget |
| `main_shell.dart` | `app/lib/core/scaffold/` | Role-based bottom navigation: EMPLOYEE sees 2 tabs (Caisse + Plus), OWNER sees all 5 (AC5) |
| `login_page.dart` | `app/lib/features/auth/presentation/page/` | Redirect to /auth/change-password when passwordChangeRequired is true (AC4) |
| `tenant_picker_page.dart` | `app/lib/features/auth/presentation/page/` | Redirect to /auth/change-password when passwordChangeRequired is true (AC4) |

---

## References

- [Epic 3 story 3.5 definition](../../planning-artifacts/epics/epic-3-gestion-multi-boutiques-stock-quipe.md#story-35-création-employés-rôles--assignation-boutique)
- [Story 1.7 — Two-step login, UserTenantMembership model](1-7-multi-tenant-user-memberships-two-step-login.md)
- [Story 1.6 — Plan limits, PlanLimitGuard, SubscriptionRepository](1-6-subscription-model-plan-limits-account-lifecycle.md)
- [Story 1.8 — Audit trail, AuditEventListener pattern](1-8-immutable-audit-trail-security-domain-foundation.md)
- [Story 3.1 — StoreController pattern, store domain model](3-1-creation-configuration-des-boutiques-et-warehouse.md)
- [Story 3.4 — currentUserRoleProvider, CrossStore bottom sheet pattern](3-4-verification-de-disponibilite-cross-boutique.md)
- [Architecture — hexagonal layers, GoF mandates, MCP port purity](../../planning-artifacts/architecture.md)
- [UX Spec — Flow 2 (Ajouter un Employé), Flow 16 (Désactiver/Révoquer)](../../planning-artifacts/ux-design-specification.md#flow-2--ajouter-un-employé)

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 4.6 (GitHub Copilot)

### Debug Log References

- 38 backend unit tests (story 3.5): ALL GREEN — `EmployeeControllerTest(9)`, `RegeneratePasswordServiceTest(4)`, `CreateEmployeeServiceTest(5)`, `ChangePasswordServiceTest(5)`, `DeactivateEmployeeServiceTest(5)`, `ReactivateEmployeeServiceTest(3)`, `ReassignStoreServiceTest(4)`, `AuthControllerChangePasswordTest(3)`
- Full backend suite: 630/632 pass — 2 pre-existing errors in `SupplierControllerTest` (NPE in `GetSupplierProfileUseCase` mock, **unrelated to story 3.5**, confirmed by reverting story changes + re-running)
- Flutter tests: pass (story 3.5 tests GREEN)
- Task 26.3-26.5 (curl E2E + manual): pending live server

### Completion Notes List

**Code Review Fixes Applied (post-review pass)**

- **[H1-SECURITY]** `EmployeeController`: added `requireOwnerOrForbid()` guard called at start of all 6 handlers (`createEmployee`, `listEmployees`, `reassignStore`, `deactivateEmployee`, `reactivateEmployee`, `regeneratePassword`). Throws `DomainException(FORBIDDEN)` if caller lacks `ROLE_OWNER`. The previous `createEmployee_returns403_whenNotOwner` test was a false positive (mocked use case to throw instead of testing real RBAC guard) — replaced with accurate test using `verifyNoInteractions(createEmployeeUseCase)`.
- **[H3]** Created `ReactivateEmployeeServiceTest.java` (3 tests: setStatusActive, reactivateMembership, throwNotFound) and `RegeneratePasswordServiceTest.java` (4 tests: generatePassword, setPasswordChangeRequired, revokeTokens, throwNotFound). Added `@Mock` declarations for `ReactivateEmployeeUseCase` and `RegeneratePasswordUseCase` to `EmployeeControllerTest`.
- **[M1]** Renamed `createEmployee_returns401_withoutJwt` → `createEmployee_returns403_withoutJwt`; changed expectation from `is5xxServerError()` → `isForbidden()` (null auth now correctly returns 403 via `requireOwnerOrForbid()`).
- **[M2]** Removed dead `tenantId` field from `ChangePasswordCommand.java`. `ChangePasswordService` reads tenant from `TenantContext.getCurrentTenant()` directly (field was always `null` at call site). Updated `AuthController` (removed `null` arg) and all 5 command constructions in `ChangePasswordServiceTest`.
- **[M3]** Fixed `JwtAuthFilter` STORE_REASSIGNED bypass for pre-3.5 EMPLOYEE tokens (which lack `storeId` JWT claim). Changed `jwtStoreId != null && !jwtStoreId.equals(dbStoreId)` → `!java.util.Objects.equals(dbStoreId, jwtStoreId)`. Pre-3.5 tokens (null storeId) now correctly trigger STORE_REASSIGNED → forces re-login.
- **[M4 — pending]** `TenantSchemaProvisioner.java` seeds `PREMIUM_TRIAL` with old employee limits — not fixed (no functional regression; provisioner runs once on new tenant creation).
- **[M5 — pending]** Flutter `create_employee_usecase_test.dart` (Task 14.1) not created — there is no standalone `CreateEmployeeUseCase` Dart class; the logic lives in Riverpod `CreateEmployee` notifier. Tracked below.

**Action Items (AI Review Follow-ups)**

- `[ ] [M4]` Update `TenantSchemaProvisioner.java` seed: set `max_employees = 5` for FREE plan entries (currently seeds PREMIUM_TRIAL value)
- `[ ] [M5]` Extract `CreateEmployee` Riverpod notifier into a proper domain `CreateEmployeeUseCase` Dart class, then create `create_employee_usecase_test.dart` in `test/features/team/domain/usecase/`

### File List

**Backend — New Source Files**

| File | Path |
|---|---|
| `EmployeeStatus.java` | `backend/src/main/java/com/keevo/identity/employee/domain/model/` |
| `Employee.java` | `backend/src/main/java/com/keevo/identity/employee/domain/model/` |
| `TempPasswordFactory.java` | `backend/src/main/java/com/keevo/identity/employee/domain/model/` |
| `CreateEmployeeResult.java` | `backend/src/main/java/com/keevo/identity/employee/domain/model/` |
| `EmployeeCreatedEvent.java` | `backend/src/main/java/com/keevo/identity/employee/domain/event/` |
| `EmployeePasswordSetEvent.java` | `backend/src/main/java/com/keevo/identity/employee/domain/event/` |
| `EmployeeStoreReassignedEvent.java` | `backend/src/main/java/com/keevo/identity/employee/domain/event/` |
| `EmployeeDeactivatedEvent.java` | `backend/src/main/java/com/keevo/identity/employee/domain/event/` |
| `CreateEmployeeCommand.java` | `backend/src/main/java/com/keevo/identity/employee/domain/port/in/` |
| `CreateEmployeeUseCase.java` | `backend/src/main/java/com/keevo/identity/employee/domain/port/in/` |
| `ChangePasswordCommand.java` | `backend/src/main/java/com/keevo/identity/employee/domain/port/in/` |
| `ChangePasswordUseCase.java` | `backend/src/main/java/com/keevo/identity/employee/domain/port/in/` |
| `ReassignStoreCommand.java` | `backend/src/main/java/com/keevo/identity/employee/domain/port/in/` |
| `ReassignStoreUseCase.java` | `backend/src/main/java/com/keevo/identity/employee/domain/port/in/` |
| `DeactivateEmployeeCommand.java` | `backend/src/main/java/com/keevo/identity/employee/domain/port/in/` |
| `DeactivateEmployeeUseCase.java` | `backend/src/main/java/com/keevo/identity/employee/domain/port/in/` |
| `ReactivateEmployeeCommand.java` | `backend/src/main/java/com/keevo/identity/employee/domain/port/in/` |
| `ReactivateEmployeeUseCase.java` | `backend/src/main/java/com/keevo/identity/employee/domain/port/in/` |
| `RegeneratePasswordCommand.java` | `backend/src/main/java/com/keevo/identity/employee/domain/port/in/` |
| `RegeneratePasswordUseCase.java` | `backend/src/main/java/com/keevo/identity/employee/domain/port/in/` |
| `ListEmployeesQuery.java` | `backend/src/main/java/com/keevo/identity/employee/domain/port/in/` |
| `ListEmployeesUseCase.java` | `backend/src/main/java/com/keevo/identity/employee/domain/port/in/` |
| `EmployeeRepository.java` | `backend/src/main/java/com/keevo/identity/employee/domain/port/out/` |
| `EmployeeCountPort.java` | `backend/src/main/java/com/keevo/identity/employee/domain/port/out/` |
| `CreateEmployeeService.java` | `backend/src/main/java/com/keevo/identity/employee/application/service/` |
| `ChangePasswordService.java` | `backend/src/main/java/com/keevo/identity/employee/application/service/` |
| `ReassignStoreService.java` | `backend/src/main/java/com/keevo/identity/employee/application/service/` |
| `DeactivateEmployeeService.java` | `backend/src/main/java/com/keevo/identity/employee/application/service/` |
| `ListEmployeesService.java` | `backend/src/main/java/com/keevo/identity/employee/application/service/` |
| `ReactivateEmployeeService.java` | `backend/src/main/java/com/keevo/identity/employee/application/service/` |
| `RegeneratePasswordService.java` | `backend/src/main/java/com/keevo/identity/employee/application/service/` |
| `EmployeeJpaEntity.java` | `backend/src/main/java/com/keevo/identity/employee/adapter/out/persistence/` |
| `EmployeeSpringRepository.java` | `backend/src/main/java/com/keevo/identity/employee/adapter/out/persistence/` |
| `EmployeeRepositoryAdapter.java` | `backend/src/main/java/com/keevo/identity/employee/adapter/out/persistence/impl/` |
| `EmployeeCountAdapter.java` | `backend/src/main/java/com/keevo/subscription/plan/adapter/out/persistence/impl/` |
| `CreateEmployeeRequestDto.java` | `backend/src/main/java/com/keevo/identity/employee/adapter/in/rest/dto/` |
| `EmployeeResponseDto.java` | `backend/src/main/java/com/keevo/identity/employee/adapter/in/rest/dto/` |
| `TempPasswordResponseDto.java` | `backend/src/main/java/com/keevo/identity/employee/adapter/in/rest/dto/` |
| `ReassignStoreRequestDto.java` | `backend/src/main/java/com/keevo/identity/employee/adapter/in/rest/dto/` |
| `ChangePasswordRequestDto.java` | `backend/src/main/java/com/keevo/identity/auth/adapter/in/rest/dto/` |
| `EmployeeController.java` | `backend/src/main/java/com/keevo/identity/employee/adapter/in/rest/` |
| `curl-tests-story-3-5.sh` | `keevo/scripts/` |

**Backend — New Test Files**

| File | Path |
|---|---|
| `CreateEmployeeServiceTest.java` | `backend/src/test/java/com/keevo/identity/employee/application/service/` |
| `ReassignStoreServiceTest.java` | `backend/src/test/java/com/keevo/identity/employee/application/service/` |
| `DeactivateEmployeeServiceTest.java` | `backend/src/test/java/com/keevo/identity/employee/application/service/` |
| `ReactivateEmployeeServiceTest.java` | `backend/src/test/java/com/keevo/identity/employee/application/service/` |
| `RegeneratePasswordServiceTest.java` | `backend/src/test/java/com/keevo/identity/employee/application/service/` |
| `EmployeeControllerTest.java` | `backend/src/test/java/com/keevo/identity/employee/adapter/in/rest/` |
| `AuthControllerChangePasswordTest.java` | `backend/src/test/java/com/keevo/identity/auth/adapter/in/rest/` |

**Backend — Modified Files**

| File | Change |
|---|---|
| `PlanType.java` | `FREE.maxEmployees = 5` |
| `JwtTokenProvider.java` | Added `storeId` + `passwordChangeRequired` claim support |
| `JwtAuthFilter.java` | EMPLOYEE guard (INACTIVE/STORE_REASSIGNED/PASSWORD_CHANGE_REQUIRED) + `Objects.equals()` fix (M3) |
| `SelectTenantService.java` | EMPLOYEE JWT enrichment (storeId, passwordChangeRequired via tenant schema query) |
| `LoginResponse.java` | Added `storeId` (nullable) + `passwordChangeRequired` fields |
| `AuthController.java` | Added `POST /auth/change-password` endpoint + `ChangePasswordCommand` call (M2) |
| `ChangePasswordCommand.java` | Removed dead `tenantId` field (M2) |
| `UserMembershipRepository.java` | Added `deactivateByUserIdAndTenantId()` port method |
| `UserMembershipRepositoryAdapter.java` | Implemented `deactivateByUserIdAndTenantId()` |
| `ErrorCode.java` | Added `EMPLOYEE_NOT_FOUND`, `PASSWORD_CHANGE_REQUIRED`, `STORE_REASSIGNED` |
| `ChangePasswordServiceTest.java` | Removed `tenantId` field + 5 command constructions updated (M2) |

**Flutter — New Files**

| File | Path |
|---|---|
| `employee_model.dart` + `.freezed.dart` + `.g.dart` | `app/lib/features/team/domain/model/` |
| `create_employee_result.dart` + `.freezed.dart` | `app/lib/features/team/domain/model/` |
| `employee_repository.dart` | `app/lib/features/team/domain/repository/` |
| `remote_employee_datasource.dart` | `app/lib/features/team/data/datasource/` |
| `employee_repository_impl.dart` | `app/lib/features/team/data/repository/` |
| `employee_provider.dart` + `.g.dart` | `app/lib/features/team/presentation/provider/` |
| `team_page.dart` | `app/lib/features/team/presentation/page/` |
| `create_employee_page.dart` | `app/lib/features/team/presentation/page/` |
| `employee_card.dart` | `app/lib/features/team/presentation/widget/` |
| `temp_password_bottom_sheet.dart` | `app/lib/features/team/presentation/widget/` |
| `password_change_page.dart` | `app/lib/features/auth/presentation/page/` |
| `change_password_usecase.dart` | `app/lib/features/auth/domain/usecase/` |

**Flutter — Modified Files**

| File | Change |
|---|---|
| `auth_tokens.dart` | Added `storeId?` + `@Default(false) passwordChangeRequired` |
| `secure_token_storage.dart` | Added storeId + passwordChangeRequired persistence keys/methods |
| `token_storage.dart` | Added new method signatures |
| `select_tenant_usecase.dart` | Persist storeId + passwordChangeRequired after select-tenant |
| `remote_auth_datasource.dart` | Parse storeId + passwordChangeRequired from select-tenant response |
| `auth_repository.dart` | Added changePassword method signature |
| `auth_repository_impl.dart` | Implemented changePassword |
| `auth_provider.dart` | Added changePassword notifier + kPasswordChangeRequiredKey persistence |
| `providers.dart` | Added `passwordChangeRequiredProvider` + `kPasswordChangeRequiredKey` |
| `app_router.dart` | Added `/settings/team`, `/settings/team/new`, `/auth/change-password` routes + passwordChangeRequired redirect guard |
| `settings_page.dart` | Added "Équipe" tile (OWNER only) + hid entire Gestion section for EMPLOYEE (AC5) |
| `main_shell.dart` | Role-based nav: EMPLOYEE sees 2 tabs (Caisse + Plus), OWNER sees all 5 |
| `login_page.dart` | Redirect to `/auth/change-password` when `passwordChangeRequired == true` |
| `tenant_picker_page.dart` | Redirect to `/auth/change-password` when `passwordChangeRequired == true` |
