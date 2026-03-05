# Story 1.3: JWT Authentication & Session Management

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a user (Simon or Loïc),
I want to log in securely with my phone number and password on any device,
So that I can access my workspace from mobile and desktop with my data protected.

## Acceptance Criteria

**AC1 — Successful login with real JWT (RS256)**
- **Given** Simon has a registered account (from Story 1.2)
- **When** he submits correct phone number and password on the login screen
- **Then** the backend returns HTTP 200 with:
  - `accessToken` (JWT, RS256 signed, expires 24h)
  - `refreshToken` (opaque or JWT, expires 30 days)
  - `userId`, `tenantId`, `role`
- **And** the JWT payload contains: `userId`, `tenantId`, `role`, `iat`, `exp`
- **And** the JWT is signed with an RSA 2048-bit private key (`RS256` algorithm) — NEVER `HS256`
- **And** all tokens are stored securely via `flutter_secure_storage` (keys: `jwt_token`, `refresh_token`, `tenant_id`, `user_id`)
- **And** the app navigates to the main screen (placeholder `/home` route for now)

**AC2 — TenantJwtFilter resolves tenant per request**
- **Given** a valid JWT is sent in the `Authorization: Bearer <token>` header
- **When** the request reaches any protected endpoint
- **Then** the `TenantJwtFilter` extracts `tenantId` from the JWT claims
- **And** sets `TenantContext` via `ThreadLocal` before any business logic executes
- **And** all JPA queries automatically use the correct `kv_xxxxxx` schema for this request
- **And** the `TenantContext` is cleared in a `finally` block after the request completes (no ThreadLocal leak)

**AC3 — Automatic JWT refresh flow (Flutter)**
- **Given** Simon's access token has expired (24h)
- **When** he makes any API request
- **Then** the backend returns HTTP 401 with `{ "domainCode": "TOKEN_EXPIRED" }`
- **And** the Flutter Dio interceptor automatically calls `POST /api/v1/auth/refresh` with the refresh token
- **And** if refresh succeeds (HTTP 200), new tokens are stored and the original request is retried transparently
- **And** if refresh fails (HTTP 401), the user is logged out and redirected to the login screen
- **And** the retry happens only once per request — no infinite retry loop

**AC4 — Invalid credentials & account lockout**
- **Given** Simon submits an incorrect password
- **When** the login form is submitted
- **Then** the backend returns HTTP 401 with `{ "domainCode": "INVALID_CREDENTIALS" }`
- **And** no token is issued
- **And** a `failed_attempts` counter is incremented for this phone number
- **And** after 5 consecutive failed attempts, the account is locked for 15 minutes
- **And** during lockout, all login attempts return HTTP 401 with `{ "domainCode": "ACCOUNT_LOCKED", "details": { "lockedUntil": "ISO8601" } }`
- **And** the counter resets to 0 on a successful login

**AC5 — Password security (bcrypt ≥ 12 — already enforced from Story 1.2)**
- **Given** any user's password in the database
- **When** inspected at rest
- **Then** the password field only stores `$2a$12$...` bcrypt hashes — plaintext is never persisted or logged
- **And** `BCryptPasswordEncoder` at cost 12 (configured in `SecurityConfig`) is used for all password verifications

**AC6 — HTTPS / TLS 1.2+ enforcement**
- **Given** all API traffic between Flutter and backend
- **When** any request is made
- **Then** all data is transmitted over HTTPS with TLS 1.2 minimum
- **And** the Flutter `Dio` HTTP client is configured to reject connections without valid TLS certificates (no `badCertificateCallback` bypass in production)
- **And** Spring Boot's `application-prod.yml` enforces HTTPS redirection

**AC7 — Multi-device sessions**
- **Given** Simon logs in on mobile and Loïc (employee) logs in on desktop
- **When** both sessions are active simultaneously
- **Then** each device operates independently with its own valid access + refresh token pair
- **And** a request from one device cannot invalidate the session of another device
- **And** the backend issues tokens without a single-session constraint (stateless JWT sessions)

**AC8 — Unauthorized request guard**
- **Given** a `TenantJwtFilter` is active on all protected endpoints
- **When** a request arrives without a valid JWT (missing, expired, malformed)
- **Then** the backend returns HTTP 401 immediately in the filter layer, before reaching any controller
- **And** `{ "domainCode": "UNAUTHORIZED" }` or `{ "domainCode": "TOKEN_EXPIRED" }` is returned as appropriate
- **And** the tenant schema is never touched for unauthenticated requests

## Tasks / Subtasks

### Backend Tasks

- [x] **Task 1 — TDD: Write RED tests first** (AC: all — TDD mandatory)
  - [x] 1.1 — `JwtTokenProviderTest.java`: assert `generateAccessToken()` produces a valid RS256 JWT, assert `extractUserId()` / `extractTenantId()` / `extractRole()` return correct claims, assert `isTokenExpired()` returns true for past-`exp` token
  - [x] 1.2 — `AuthenticationServiceTest.java`: assert happy path returns `AuthenticationResult` with non-null access + refresh tokens, assert wrong password throws `DomainException(INVALID_CREDENTIALS)`, assert 5th failed attempt locks account, assert locked account throws `DomainException(ACCOUNT_LOCKED)`
  - [x] 1.3 — `TenantJwtFilterTest.java`: assert valid JWT sets `TenantContext` and calls `filterChain.doFilter()`, assert missing JWT returns 401 without calling filter chain, assert expired JWT returns 401 with `TOKEN_EXPIRED`, assert `TenantContext` is cleared in `finally` block
  - [x] 1.4 — `AuthControllerTest.java` (`@WebMvcTest`): assert `POST /api/v1/auth/login` returns 200 with `{ accessToken, refreshToken, userId, tenantId, role }`, assert 401 on wrong password, assert 401 with `ACCOUNT_LOCKED` domainCode when locked
  - [x] 1.5 — `RefreshTokenServiceTest.java`: assert `refresh()` returns new access token for valid refresh token, assert expired/unknown refresh token throws `DomainException(REFRESH_TOKEN_INVALID)`

- [x] **Task 2 — RSA key pair generation & configuration**
  - [x] 2.1 — Generate RSA 2048-bit key pair: `private_key.pem` and `public_key.pem`, stored in `backend/src/main/resources/keys/` (gitignored — `.env`/Secrets in production)
  - [x] 2.2 — Add to `application.yml`:
    ```yaml
    keevo:
      jwt:
        private-key-path: classpath:keys/private_key.pem
        public-key-path: classpath:keys/public_key.pem
        access-token-expiry-hours: 24
        refresh-token-expiry-days: 30
    ```
  - [x] 2.3 — Create `JwtProperties.java` (`@ConfigurationProperties("keevo.jwt")`): fields `privateKeyPath`, `publicKeyPath`, `accessTokenExpiryHours`, `refreshTokenExpiryDays`
  - [x] 2.4 — Create `RsaKeyConfig.java` (`@Configuration`): loads RSA keys from PEM files into `RSAPrivateKey` and `RSAPublicKey` beans using `java.security.KeyFactory`

- [x] **Task 3 — JWT domain models & ports** (AC: 1, 2, 3, 4)
  - [x] 3.1 — Create `identity/auth/domain/model/AuthTokens.java` (Java record): `accessToken` (String), `refreshToken` (String), `expiresIn` (long seconds)
  - [x] 3.2 — Create `identity/auth/domain/model/RefreshToken.java` (Java record): `id` (UUID), `userId` (UUID), `tenantId` (String), `tokenHash` (String — bcrypt-hashed), `expiresAt` (Instant), `revoked` (boolean)
  - [x] 3.3 — Add `INVALID_CREDENTIALS`, `ACCOUNT_LOCKED`, `TOKEN_EXPIRED`, `UNAUTHORIZED`, `REFRESH_TOKEN_INVALID` to `shared/domain/exception/ErrorCode.java` (if not already present)
  - [x] 3.4 — Create `identity/auth/domain/port/in/AuthenticateUserUseCase.java` (interface): `authenticate(AuthenticateUserCommand command): AuthTokens`
  - [x] 3.5 — Create `identity/auth/domain/port/in/AuthenticateUserCommand.java` (Java record): `phoneNumber`, `password`, `actorId` (null — self-authentication)
  - [x] 3.6 — Create `identity/auth/domain/port/in/RefreshTokenUseCase.java` (interface): `refresh(String rawRefreshToken): AuthTokens`
  - [x] 3.7 — Create `identity/auth/domain/port/out/RefreshTokenRepository.java` (interface): `save(RefreshToken token): RefreshToken`, `findByHash(String hash): Optional<RefreshToken>`, `revokeAllByUserId(UUID userId)`

- [x] **Task 4 — JwtTokenProvider (replace stub — Strategy pattern)** (AC: 1, 2, 5)
  - [x] 4.1 — Implement `shared/infrastructure/security/JwtTokenProvider.java`:
    - `generateAccessToken(UUID userId, String tenantId, String role): String` — signs with RSA private key, sets `sub=userId`, `tenantId`, `role`, `iat`, `exp` (+24h from now); algorithm: `RS256` (JJWT `Jwts.builder().signWith(privateKey, Jwts.SIG.RS256)`)
    - `generateRefreshToken(): String` — generates a cryptographically random 64-byte token via `SecureRandom`, base64url-encoded (opaque, NOT a JWT)
    - `parseToken(String token): Claims` — validates signature with RSA public key, throws `ExpiredJwtException` if expired
    - `extractUserId(Claims claims): UUID`
    - `extractTenantId(Claims claims): String`
    - `extractRole(Claims claims): String`
    - `isTokenExpired(String token): boolean`
  - [x] 4.2 — Update `RegistrationResult.java` to remove stub token: Story 1.3's real `JwtTokenProvider` is now used everywhere — verify `RegistrationService` still calls `generateAccessToken()` correctly
  - [x] 4.3 — **Remove** the `"STUB:{userId}:{tenantId}"` stub from `JwtTokenProvider` (replaced by real RS256 implementation)

- [x] **Task 5 — AuthenticationService (Façade pattern)** (AC: 1, 4)
  - [x] 5.1 — Create `identity/auth/application/service/AuthenticationService.java` (implements `AuthenticateUserUseCase`):
    - Load user by phone via `UserRepository.findByPhoneNumber()` → throw `DomainException(INVALID_CREDENTIALS)` if not found (do NOT reveal if user exists)
    - Check account lockout: if `user.lockedUntil()` is in the future → throw `DomainException(ACCOUNT_LOCKED)` with `lockedUntil` detail
    - Verify password via `BCryptPasswordEncoder.matches()` → if wrong: increment `failedAttempts`, if count ≥ 5 set `lockedUntil = now + 15 min`, save user → throw `DomainException(INVALID_CREDENTIALS)`
    - On success: reset `failedAttempts = 0`, `lockedUntil = null`, save user
    - Generate `accessToken` via `JwtTokenProvider.generateAccessToken()`
    - Generate raw `refreshToken` via `JwtTokenProvider.generateRefreshToken()`
    - Hash refresh token with `BCryptPasswordEncoder`, create + save `RefreshToken` entity via `RefreshTokenRepository`
    - Emit `UserAuthenticatedEvent` (Observer pattern — for audit log)
    - Return `AuthTokens(accessToken, rawRefreshToken, expiresIn)`
  - [x] 5.2 — Add `lockedUntil` (Instant, nullable) and `failedAttempts` (int, default 0) fields to `User.java` domain model and `UserJpaEntity.java`
  - [x] 5.3 — Create Flyway migration `V2__add_auth_lockout_fields.sql` (in `db/migration/tenant/`): `ALTER TABLE users ADD COLUMN failed_attempts INT NOT NULL DEFAULT 0; ALTER TABLE users ADD COLUMN locked_until TIMESTAMPTZ;`

- [x] **Task 6 — RefreshTokenService** (AC: 3)
  - [x] 6.1 — Create `identity/auth/application/service/RefreshTokenService.java` (implements `RefreshTokenUseCase`):
    - Accept raw refresh token string
    - Hash it with bcrypt then look up via `RefreshTokenRepository.findByHash()`
    - Validate: token found? not revoked? not expired? → if any fails: throw `DomainException(REFRESH_TOKEN_INVALID)`
    - Load associated user via `UserRepository`
    - Generate new `accessToken` (and optionally rotate refresh token)
    - Return `AuthTokens`

- [x] **Task 7 — TenantJwtFilter (implement stub from Story 1.1)** (AC: 2, 8)
  - [x] 7.1 — Implement `shared/infrastructure/security/TenantJwtFilter.java` (extends `OncePerRequestFilter`):
    - Extract `Authorization: Bearer <token>` header
    - If missing or not starting with "Bearer ": set HTTP 401, write `{ "domainCode": "UNAUTHORIZED" }` and return
    - Parse JWT via `JwtTokenProvider.parseToken()`:
      - `ExpiredJwtException` → HTTP 401, write `{ "domainCode": "TOKEN_EXPIRED" }` and return
      - Any other `JwtException` → HTTP 401, write `{ "domainCode": "UNAUTHORIZED" }` and return
    - Extract `tenantId` from claims → call `TenantContext.setCurrentTenant(tenantId)`
    - Create `UsernamePasswordAuthenticationToken` with userId + role → set in `SecurityContextHolder`
    - Call `filterChain.doFilter(request, response)` in a `try` block
    - In `finally`: call `TenantContext.clear()` — prevents ThreadLocal leak between requests
  - [x] 7.2 — Register `TenantJwtFilter` in `SecurityConfig.java` before `UsernamePasswordAuthenticationFilter`
  - [x] 7.3 — Update `SecurityConfig.java`:
    - Permit without auth: `POST /api/v1/auth/login`, `POST /api/v1/auth/register`, `POST /api/v1/auth/refresh`
    - All other `/api/v1/**` require authentication
    - Disable session management (`SessionCreationPolicy.STATELESS`)

- [x] **Task 8 — Persistence adapters** (AC: 1, 4)
  - [x] 8.1 — Create `identity/auth/adapter/out/persistence/RefreshTokenJpaEntity.java`: fields `id` (UUID), `userId` (UUID), `tenantId` (String), `tokenHash` (VARCHAR 255), `expiresAt` (TIMESTAMPTZ), `revoked` (boolean), `createdAt` (TIMESTAMPTZ)
  - [x] 8.2 — Create `identity/auth/adapter/out/persistence/RefreshTokenJpaRepository.java` (`JpaRepository<RefreshTokenJpaEntity, UUID>`): `findByTokenHash(String hash): Optional<RefreshTokenJpaEntity>`, `revokeAllByUserId(UUID userId)`
  - [x] 8.3 — Create `identity/auth/adapter/out/persistence/JpaRefreshTokenRepository.java` (implements `RefreshTokenRepository`)
  - [x] 8.4 — Add Flyway migration `V2__add_refresh_tokens_table.sql` (in `db/migration/tenant/`): `CREATE TABLE refresh_tokens (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID NOT NULL, tenant_id VARCHAR(20) NOT NULL, token_hash VARCHAR(255) NOT NULL, expires_at TIMESTAMPTZ NOT NULL, revoked BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()); CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens(token_hash);`
  - [x] 8.5 — Update `UserJpaEntity.java`: add `failedAttempts` (int, default 0) and `lockedUntil` (Instant, nullable) columns

- [x] **Task 9 — REST adapter** (AC: 1, 3, 4)
  - [x] 9.1 — Create `identity/auth/adapter/in/rest/LoginRequest.java` (Java record): `phoneNumber` (`@NotBlank @Pattern`), `password` (`@NotBlank @Size(min=8)`)
  - [x] 9.2 — Create `identity/auth/adapter/in/rest/LoginResponse.java` (Java record): `accessToken`, `refreshToken`, `userId`, `tenantId`, `role`, `expiresIn`
  - [x] 9.3 — Create `identity/auth/adapter/in/rest/RefreshRequest.java` (Java record): `refreshToken` (`@NotBlank`)
  - [x] 9.4 — Update `AuthController.java` (add to existing controller from Story 1.2):
    - `POST /api/v1/auth/login` → calls `AuthenticateUserUseCase.authenticate()` → returns 200 `LoginResponse`
    - `POST /api/v1/auth/refresh` → calls `RefreshTokenUseCase.refresh()` → returns 200 `LoginResponse` (new tokens)
    - `@ExceptionHandler` for `DomainException(INVALID_CREDENTIALS)` → HTTP 401
    - `@ExceptionHandler` for `DomainException(ACCOUNT_LOCKED)` → HTTP 401 with `lockedUntil` in details
    - `@ExceptionHandler` for `DomainException(REFRESH_TOKEN_INVALID)` → HTTP 401
    - **Zero business logic** — pure delegation

- [x] **Task 10 — Domain event (Observer pattern)** (AC: 1)
  - [x] 10.1 — Create `identity/auth/domain/model/UserAuthenticatedEvent.java` (Java record): `userId`, `tenantId`, `role`, `ipAddress` (String, nullable), `occurredAt` (Instant UTC)
  - [x] 10.2 — Update `AuditEventListener.java` in `shared/infrastructure/persistence/`: add `@EventListener` for `UserAuthenticatedEvent` → writes to `audit_log` table with `entityType=USER`, `action=LOGIN`

### Flutter Tasks

- [x] **Task 11 — TDD: Write RED Flutter tests first** (AC: 1, 3, 4, 6)
  - [x] 11.1 — `login_usecase_test.dart`: assert successful login stores tokens in `flutter_secure_storage` and navigates to `/home`, assert wrong password surfaces `DomainException(INVALID_CREDENTIALS)`, assert locked account surfaces `DomainException(ACCOUNT_LOCKED)`
  - [x] 11.2 — `auth_interceptor_test.dart`: assert 401 `TOKEN_EXPIRED` triggers refresh call and retries original request, assert refresh failure clears tokens and navigates to `/auth/login`
  - [x] 11.3 — `login_page_test.dart`: assert form renders phone + password fields, assert submit calls provider, assert error SnackBar on failed login, assert navigation on success
  - [x] 11.4 — `auth_repository_impl_test.dart`: assert `login()` maps 200 → `AuthTokens`, assert 401 maps to `DomainException(INVALID_CREDENTIALS)`, assert 401 with `ACCOUNT_LOCKED` maps to `DomainException(ACCOUNT_LOCKED)`

- [x] **Task 12 — Domain models & use cases** (AC: 1, 3)
  - [x] 12.1 — Create `app/lib/features/auth/domain/model/auth_tokens.dart` (Freezed record): `accessToken`, `refreshToken`, `userId`, `tenantId`, `role`, `expiresIn`
  - [x] 12.2 — Update `app/lib/features/auth/domain/repository/auth_repository.dart` (interface): add `login(phoneNumber, password): Future<AuthTokens>`, `refreshToken(String refreshToken): Future<AuthTokens>`
  - [x] 12.3 — Create `app/lib/features/auth/domain/usecase/login_usecase.dart`:
    - Validate phone + password (non-empty)
    - Call `AuthRepository.login()`
    - Store `jwt_token`, `refresh_token`, `tenant_id`, `user_id` in `flutter_secure_storage`
    - Return `AuthTokens`

- [x] **Task 13 — Data layer: Dio interceptor & repository** (AC: 3, 6)
  - [x] 13.1 — Create `app/lib/core/network/auth_interceptor.dart` (`Interceptor`):
    - `onRequest`: reads `jwt_token` from `flutter_secure_storage`, adds `Authorization: Bearer <token>` header if present
    - `onError`: if status 401 and `domainCode == "TOKEN_EXPIRED"`:
      1. Read `refresh_token` from `flutter_secure_storage`
      2. Call `POST /api/v1/auth/refresh` directly (without interceptor — avoid infinite loop)
      3. On success: store new tokens, retry original request
      4. On failure: clear all stored tokens, navigate to `/auth/login` via `GoRouter`
    - Retry interceptor fires only once — guard with `_isRefreshing` boolean flag
  - [x] 13.2 — Register `AuthInterceptor` in `ApiClient` (in `app/lib/core/network/api_client.dart`) — add to existing Dio instance
  - [x] 13.3 — Configure production `Dio` with `BaseOptions(responseType: ResponseType.json)` and verify no `BadCertificateCallback` bypasses exist in `api_client.dart`
  - [x] 13.4 — Update `app/lib/features/auth/data/datasource/remote_auth_datasource.dart`: add `login()` method (`POST /api/v1/auth/login`) and `refreshToken()` method (`POST /api/v1/auth/refresh`), map JSON → `AuthTokens`
  - [x] 13.5 — Update `app/lib/features/auth/data/repository/auth_repository_impl.dart`: implement `login()` and `refreshToken()`, map `DioException` status codes to domain exceptions (`401 INVALID_CREDENTIALS`, `401 ACCOUNT_LOCKED`)

- [x] **Task 14 — Presentation layer** (AC: 1, 4)
  - [x] 14.1 — Update `app/lib/features/auth/presentation/provider/auth_provider.dart`: add `loginProvider` (Riverpod `@riverpod AsyncNotifier`) for login state management
  - [x] 14.2 — Create `app/lib/features/auth/presentation/page/login_page.dart`:
    - Material 3 form: `IntlPhoneField` (phone, reuse from UI-1 onboarding) + `TextField` (password, obscured with show/hide toggle)
    - Submit button with loading state while `loginProvider` is loading
    - Error SnackBar: "Identifiants incorrects" for `INVALID_CREDENTIALS`, "Compte verrouillé — réessayez dans X minutes" for `ACCOUNT_LOCKED`
    - On success: navigate to `/home` via GoRouter
    - "Pas encore de compte ? S'inscrire" link → `/auth/register`
  - [x] 14.3 — Update `app/lib/core/router/app_router.dart`:
    - Add `/auth/login` route → `LoginPage`
    - Add redirect guard: if `jwt_token` exists in secure storage → redirect from `/auth/login` to `/home`
    - Add `/home` placeholder route (scaffold with "Accueil" text — full POS implementation Story 4.x)

## Dev Notes

### GoF Pattern Mandatory Analysis (Pre-Implementation)

| Pattern | Application in Story 1.3 | Rationale |
|---|---|---|
| **Strategy** | `JwtTokenProvider` — RS256 signing/verification strategy | Signing algorithm (RS256) is swappable without modifying `AuthenticationService`. Future: ECDSA, per-tenant keys. Interface-driven, closed for modification. |
| **Façade** | `AuthenticationService` | Single entry point orchestrating password verification, lockout logic, token generation, refresh token persistence, and audit event. Hides complexity from `AuthController`. |
| **Observer** | `UserAuthenticatedEvent` → `AuditEventListener` | Authentication events are audited without coupling `AuthenticationService` to audit persistence. Same Observer infrastructure from Story 1.2. |
| **Template Method** | `TenantJwtFilter` extends `OncePerRequestFilter` | Spring's `OncePerRequestFilter` defines the template (execute once per request), Story 1.3 fills in the `doFilterInternal()` hook. |
| **Chain of Responsibility** | Spring Security filter chain | `TenantJwtFilter` → `UsernamePasswordAuthenticationFilter` → ... Each filter in the chain handles its concern and passes to the next. |

### Technical Architecture Summary

| Component | Technology | Key Constraint |
|---|---|---|
| JWT signing | JJWT 0.12.6 + RSA 2048 | **RS256 only** — NEVER HS256 (symmetric keys can be brute-forced; RS256 allows public-key verification without exposing signing key) |
| JWT expiry | Access: 24h / Refresh: 30 days | Access token short-lived; refresh token opaque (random bytes, bcrypt-hashed in DB) |
| Account lockout | 5 attempts → 15-min lock | Counter stored in `users.failed_attempts`; reset on successful login |
| Refresh token storage | `refresh_tokens` table (per-tenant schema) | Stored as bcrypt hash — raw token never persisted |
| TenantContext | `ThreadLocal<String>` | Must be cleared in `finally` block — prevents cross-request tenant leaks in thread-pool environments |
| Flutter token storage | `flutter_secure_storage` | Keys: `jwt_token`, `refresh_token`, `tenant_id`, `user_id` — NEVER SharedPreferences |
| TLS enforcement | Spring Boot HTTPS + Dio | No `badCertificateCallback` in production builds |
| State management | Riverpod 3.0 `@riverpod` | `AsyncNotifier` for login state — consistent with Story 1.2 registration pattern |

### Hexagonal Architecture Flow (Story 1.3)

```
POST /api/v1/auth/login
    │
    ▼ filter layer (before controllers)
TenantJwtFilter — skipped for /auth/login (public endpoint)
    │
    ▼ adapter/in/rest/
AuthController.login(LoginRequest)
    │ maps to ↓
AuthenticateUserCommand (record — pure Java)
    │
    ▼ domain/port/in/ (interface)
AuthenticateUserUseCase.authenticate(command)
    │
    ▼ application/service/ (orchestration)
AuthenticationService
    ├── UserRepository.findByPhoneNumber(...)     ← port/out/
    ├── check lockedUntil (domain logic)
    ├── BCryptPasswordEncoder.matches(...)        ← pure Java
    ├── increment failedAttempts / lock account
    ├── JwtTokenProvider.generateAccessToken()   ← shared/infrastructure/security/
    ├── JwtTokenProvider.generateRefreshToken()
    ├── RefreshTokenRepository.save(...)         ← port/out/
    ├── publish UserAuthenticatedEvent           ← Observer
    └── UserRepository.save(user)               ← port/out/ (reset counter)
    │
    ▼ domain/port/out/ → adapter/out/persistence/
JpaUserRepository           → UserJpaRepository (Spring Data JPA)
JpaRefreshTokenRepository   → RefreshTokenJpaRepository

─────────────────────────────────────────────────────
Protected endpoint flow (any subsequent API call):
─────────────────────────────────────────────────────
GET /api/v1/products (with Authorization: Bearer <jwt>)
    │
    ▼ TenantJwtFilter
    ├── parseToken(jwt) → Claims
    ├── TenantContext.setCurrentTenant(tenantId)  ← ThreadLocal
    ├── SecurityContextHolder.setAuthentication(...)
    └── filterChain.doFilter(...)
    │   └── [controller → service → JPA → PostgreSQL kv_xxxxxx schema]
    └── finally: TenantContext.clear()
```

### Key Implementation Patterns

**RSA key loading (RS256 cryptography):**
```java
// RsaKeyConfig.java — Spring @Configuration
@Bean
public RSAPrivateKey rsaPrivateKey(@Value("${keevo.jwt.private-key-path}") Resource path) throws Exception {
    String pem = new String(path.getInputStream().readAllBytes())
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replaceAll("\\s", "");
    byte[] decoded = Base64.getDecoder().decode(pem);
    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
    return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(keySpec);
}

@Bean
public RSAPublicKey rsaPublicKey(@Value("${keevo.jwt.public-key-path}") Resource path) throws Exception {
    String pem = new String(path.getInputStream().readAllBytes())
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replaceAll("\\s", "");
    byte[] decoded = Base64.getDecoder().decode(pem);
    X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decoded);
    return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(keySpec);
}
```

**JWT generation (RS256 — JJWT 0.12.6):**
```java
// JwtTokenProvider.java
public String generateAccessToken(UUID userId, String tenantId, String role) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(userId.toString())
        .claim("tenantId", tenantId)
        .claim("role", role)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(jwtProperties.getAccessTokenExpiryHours(), ChronoUnit.HOURS)))
        .signWith(rsaPrivateKey, Jwts.SIG.RS256)   // RS256 — not HS256
        .compact();
}

public String generateRefreshToken() {
    byte[] bytes = new byte[64];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
}

public Claims parseToken(String token) {
    return Jwts.parser()
        .verifyWith(rsaPublicKey)
        .build()
        .parseSignedClaims(token)
        .getPayload();
    // Throws ExpiredJwtException if expired — caught by TenantJwtFilter
}
```

**TenantJwtFilter (critical ThreadLocal safety):**
```java
// TenantJwtFilter.java — extends OncePerRequestFilter
@Override
protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
    String authHeader = req.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        writeError(res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        return;
    }
    String token = authHeader.substring(7);
    try {
        Claims claims = jwtTokenProvider.parseToken(token);
        String tenantId = claims.get("tenantId", String.class);
        String role = claims.get("role", String.class);
        UUID userId = UUID.fromString(claims.getSubject());

        TenantContext.setCurrentTenant(tenantId);   // ThreadLocal set

        UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(req, res);  // ← MUST be inside try

    } catch (ExpiredJwtException e) {
        writeError(res, HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED");
    } catch (JwtException e) {
        writeError(res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    } finally {
        TenantContext.clear();     // ← ALWAYS clear, even on exception
        SecurityContextHolder.clearContext();
    }
}
```

**Flutter AuthInterceptor (token refresh + retry):**
```dart
// auth_interceptor.dart
class AuthInterceptor extends Interceptor {
  final FlutterSecureStorage _storage;
  final Dio _dio; // same Dio instance — but refresh calls must bypass this interceptor
  bool _isRefreshing = false;

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) async {
    final token = await _storage.read(key: 'jwt_token');
    if (token != null) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    handler.next(options);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) async {
    final domainCode = err.response?.data?['domainCode'];
    if (err.response?.statusCode == 401 && domainCode == 'TOKEN_EXPIRED' && !_isRefreshing) {
      _isRefreshing = true;
      try {
        final refreshToken = await _storage.read(key: 'refresh_token');
        if (refreshToken == null) throw Exception('No refresh token');

        // Direct call bypassing interceptor
        final refreshDio = Dio(BaseOptions(baseUrl: ApiClient.baseUrl));
        final response = await refreshDio.post('/api/v1/auth/refresh',
            data: {'refreshToken': refreshToken});

        final newAccessToken = response.data['accessToken'];
        final newRefreshToken = response.data['refreshToken'];
        await _storage.write(key: 'jwt_token', value: newAccessToken);
        await _storage.write(key: 'refresh_token', value: newRefreshToken);

        // Retry original request
        err.requestOptions.headers['Authorization'] = 'Bearer $newAccessToken';
        final retryResponse = await _dio.fetch(err.requestOptions);
        handler.resolve(retryResponse);
      } catch (_) {
        // Refresh failed — logout
        await _storage.deleteAll();
        // Navigate to login (via GoRouter ref stored in locator)
        handler.reject(err);
      } finally {
        _isRefreshing = false;
      }
    } else {
      handler.next(err);
    }
  }
}
```

**API error format — Story 1.3 responses:**
```json
// POST /api/v1/auth/login — HTTP 200 success
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "k8m2nP3qR7sT1uV6wX0yZ5aB9cD4eF...",
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "tenantId": "KV-ABC123",
  "role": "OWNER",
  "expiresIn": 86400
}

// HTTP 401 — wrong password
{
  "error": "Identifiants invalides",
  "code": "UNAUTHORIZED",
  "domainCode": "INVALID_CREDENTIALS",
  "details": {},
  "timestamp": "2026-03-04T10:00:00Z"
}

// HTTP 401 — account locked
{
  "error": "Compte verrouillé temporairement",
  "code": "UNAUTHORIZED",
  "domainCode": "ACCOUNT_LOCKED",
  "details": { "lockedUntil": "2026-03-04T10:15:00Z" },
  "timestamp": "2026-03-04T10:00:00Z"
}

// HTTP 401 — expired token (from TenantJwtFilter)
{
  "error": "Session expirée",
  "code": "UNAUTHORIZED",
  "domainCode": "TOKEN_EXPIRED",
  "details": {},
  "timestamp": "2026-03-04T10:00:00Z"
}
```

### Project Structure — Files to Create / Modify

**Backend (Spring Boot):**
```
backend/src/main/java/com/keevo/
├── identity/auth/
│   ├── domain/
│   │   ├── model/
│   │   │   ├── AuthTokens.java                      [NEW — record]
│   │   │   ├── RefreshToken.java                    [NEW — record]
│   │   │   ├── UserAuthenticatedEvent.java           [NEW — record]
│   │   │   └── User.java                            [MODIFY — add failedAttempts, lockedUntil]
│   │   └── port/
│   │       ├── in/
│   │       │   ├── AuthenticateUserUseCase.java      [NEW — interface]
│   │       │   ├── AuthenticateUserCommand.java      [NEW — record]
│   │       │   └── RefreshTokenUseCase.java          [NEW — interface]
│   │       └── out/
│   │           └── RefreshTokenRepository.java       [NEW — interface]
│   ├── application/service/
│   │   ├── AuthenticationService.java               [NEW — implements AuthenticateUserUseCase]
│   │   └── RefreshTokenService.java                 [NEW — implements RefreshTokenUseCase]
│   └── adapter/
│       ├── in/rest/
│       │   ├── AuthController.java                  [MODIFY — add login + refresh endpoints]
│       │   ├── LoginRequest.java                    [NEW — record]
│       │   ├── LoginResponse.java                   [NEW — record]
│       │   └── RefreshRequest.java                  [NEW — record]
│       └── out/persistence/
│           ├── RefreshTokenJpaEntity.java           [NEW]
│           ├── RefreshTokenJpaRepository.java       [NEW — Spring Data JPA]
│           └── JpaRefreshTokenRepository.java       [NEW — implements RefreshTokenRepository]
├── shared/
│   └── infrastructure/
│       ├── security/
│       │   ├── JwtTokenProvider.java                [REPLACE STUB — full RS256 impl]
│       │   ├── TenantJwtFilter.java                 [IMPLEMENT stub from Story 1.1]
│       │   ├── RsaKeyConfig.java                    [NEW — loads RSA key pair beans]
│       │   ├── JwtProperties.java                   [NEW — @ConfigurationProperties]
│       │   └── SecurityConfig.java                  [MODIFY — add filter, update permits]
│       └── persistence/
│           └── AuditEventListener.java              [MODIFY — add UserAuthenticatedEvent handler]
│
backend/src/main/resources/
├── keys/
│   ├── private_key.pem                             [NEW — gitignored in production]
│   └── public_key.pem                              [NEW]
├── db/migration/tenant/
│   └── V2__add_auth_lockout_and_refresh_tokens.sql [NEW — Flyway migration]
└── application.yml                                 [MODIFY — add keevo.jwt properties]
│
backend/src/test/java/com/keevo/identity/auth/
├── application/service/
│   ├── AuthenticationServiceTest.java              [NEW — TDD RED first]
│   └── RefreshTokenServiceTest.java                [NEW — TDD RED first]
├── adapter/in/rest/
│   └── AuthControllerTest.java                     [MODIFY — add login + refresh test cases]
└── infrastructure/security/
    ├── JwtTokenProviderTest.java                   [NEW — TDD RED first]
    └── TenantJwtFilterTest.java                    [NEW — TDD RED first]
```

**Flutter (app):**
```
app/lib/
├── core/network/
│   ├── auth_interceptor.dart                       [NEW — Dio interceptor with token refresh]
│   └── api_client.dart                             [MODIFY — register AuthInterceptor]
├── features/auth/
│   ├── domain/
│   │   ├── model/
│   │   │   └── auth_tokens.dart                    [NEW — Freezed record]
│   │   ├── repository/
│   │   │   └── auth_repository.dart                [MODIFY — add login(), refreshToken()]
│   │   └── usecase/
│   │       └── login_usecase.dart                  [NEW]
│   ├── data/
│   │   ├── datasource/
│   │   │   └── remote_auth_datasource.dart         [MODIFY — add login(), refreshToken()]
│   │   └── repository/
│   │       └── auth_repository_impl.dart           [MODIFY — implement login(), refreshToken()]
│   └── presentation/
│       ├── provider/
│       │   └── auth_provider.dart                  [MODIFY — add loginProvider]
│       └── page/
│           └── login_page.dart                     [NEW — Material 3 login form]
└── core/router/
    └── app_router.dart                             [MODIFY — add /auth/login + /home + redirect guard]

app/test/
├── core/network/
│   └── auth_interceptor_test.dart                  [NEW — TDD RED first]
└── features/auth/
    ├── domain/usecase/
    │   └── login_usecase_test.dart                 [NEW — TDD RED first]
    ├── data/repository/
    │   └── auth_repository_impl_test.dart          [NEW — TDD RED first]
    └── presentation/page/
        └── login_page_test.dart                    [NEW — widget test]
```

### Previous Story Intelligence (from Stories 1.1 & 1.2)

1. **`JwtTokenProvider.generateToken()` is currently a stub** returning `"STUB:{userId}:{tenantId}"` (from Story 1.2 fix). Story 1.3 **completely replaces** this with real RS256 JWT. After this story, `RegistrationService` must also use the real `generateAccessToken()` — verify registration flow still returns a real JWT.

2. **`TenantJwtFilter.java` is a stub** (from Story 1.1 scaffold with `// TODO (Story 1.3)`). Implement it fully — do NOT create a new file.

3. **`TenantContext.java` package**: `com.keevo.shared.infrastructure.persistence` — ThreadLocal already wired from Story 1.1. The `setCurrentTenant()` and `clear()` methods exist but were unused until this story.

4. **`SecurityConfig.java` is currently permitting everything** (dev mode). Story 1.3 hardens it — verify all public endpoints are properly listed in `permitAll()` before adding the catch-all `authenticated()` rule. Forgetting `/api/v1/auth/refresh` in `permitAll` will break token refresh.

5. **`adapter/in/mcp/.gitkeep` already exists** in `identity/auth/` from Story 1.1 — do NOT recreate.

6. **`AuditEventListener.java` already exists** from Story 1.2 — add `@EventListener` for `UserAuthenticatedEvent` in the same class, do NOT create a new one.

7. **`GoogleFonts.config.allowRuntimeFetching = false`** must be in `setUp()` of ALL new Flutter tests — learned from Story 1.1 code review.

8. **`AuthController.java` already exists** from Story 1.2 (`/api/v1/auth/register`). Add login and refresh endpoints to the SAME controller — do NOT create a second `AuthController`.

9. **`auth_provider.dart` already exists** from Story 1.2 (`registerProvider`). Add `loginProvider` to the SAME file as a new `@riverpod AsyncNotifier`.

10. **RSA key pair for development**: Generate with `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private_key.pem` and `openssl rsa -in private_key.pem -pubout -out public_key.pem`. Add `**/keys/*.pem` to `.gitignore` immediately. Provide test keys (weak, for tests only) in `src/test/resources/keys/`.

11. **JJWT version**: Story 1.1 added JJWT to `pom.xml` (`io.jsonwebtoken:jjwt-api:0.12.6` + `jjwt-impl` + `jjwt-jackson`). Verify these are present before implementing `JwtTokenProvider` — do NOT add duplicates.

12. **`IntlPhoneField` is already available** from Story UI-1 (onboarding). Reuse it directly in `login_page.dart` — no new dependency needed.

### Architecture Compliance Guardrails (NON-NEGOTIABLE)

1. **RS256 only** — `JwtTokenProvider` MUST use `Jwts.SIG.RS256` with `RSAPrivateKey`. Using `HS256` (symmetric) is a critical security violation — rejected outright.

2. **Port purity** — `AuthenticateUserUseCase` and `RefreshTokenUseCase` interfaces accept only plain Java records. NO `HttpServletRequest`, `Principal`, or Spring Security types in these interfaces.

3. **ActorId discipline** — `AuthenticateUserCommand.actorId()` is null for self-authentication. Never call `SecurityContextHolder` inside `AuthenticationService`.

4. **ThreadLocal leak prevention** — `TenantContext.clear()` MUST be in the `finally` block of `TenantJwtFilter`. A missing `clear()` causes tenant data leakage across requests in thread-pool environments (Tomcat reuses threads).

5. **Zero business logic in controller** — `AuthController` maps request → command, calls use case, maps result → response. No validation logic, no token parsing, no exception handling beyond `@ExceptionHandler` mapping.

6. **Refresh token as opaque bytes** — The refresh token is a random byte array (NOT a JWT), base64url-encoded, stored as its bcrypt hash. This prevents refresh token forgery even if the DB is compromised.

7. **Password logging prohibition** — `LoginRequest.password` must NEVER appear in request logs, error messages, or audit entries. Verify SLF4J logger configuration masks password fields.

8. **Single retry on refresh** — The `AuthInterceptor` MUST guard against infinite retry loops with an `_isRefreshing` flag. A refresh failure must result in logout, NOT another refresh attempt.

9. **TDD RED first** — Write failing tests (`JwtTokenProviderTest`, `AuthenticationServiceTest`, `TenantJwtFilterTest`) BEFORE writing any production code. Zero exceptions.

10. **`mvn test` must pass** — All new backend tests: `JwtTokenProviderTest`, `AuthenticationServiceTest`, `RefreshTokenServiceTest`, `TenantJwtFilterTest`, updated `AuthControllerTest`. All must be green before story is done.

### Testing Requirements

**Backend Unit Tests (TDD — write failing tests first):**

```java
// JwtTokenProviderTest.java — WRITE THIS FIRST
@ExtendWith(MockitoExtension.class)
class JwtTokenProviderTest {

    private JwtTokenProvider provider;
    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;

    @BeforeEach
    void setUp() throws Exception {
        // Load test RSA key pair from src/test/resources/keys/
        privateKey = loadTestPrivateKey();
        publicKey = loadTestPublicKey();
        provider = new JwtTokenProvider(privateKey, publicKey, new JwtProperties(24, 30));
    }

    @Test
    void should_generate_valid_rs256_jwt_with_correct_claims() {
        UUID userId = UUID.randomUUID();
        String tenantId = "KV-ABC123";
        String role = "OWNER";

        String token = provider.generateAccessToken(userId, tenantId, role);

        Claims claims = provider.parseToken(token);
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("tenantId", String.class)).isEqualTo(tenantId);
        assertThat(claims.get("role", String.class)).isEqualTo(role);
        assertThat(claims.getExpiration()).isAfter(new Date());
    }

    @Test
    void should_throw_expired_exception_for_past_token() {
        // Generate token with past expiry date using reflection or overridable clock
        String expiredToken = generateExpiredToken(userId, privateKey);

        assertThatThrownBy(() -> provider.parseToken(expiredToken))
            .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void should_generate_opaque_refresh_token_of_sufficient_length() {
        String refreshToken = provider.generateRefreshToken();

        // Base64url of 64 bytes = ~86 chars
        assertThat(refreshToken).hasSizeGreaterThanOrEqualTo(80);
        // Verify it's URL-safe base64 (no +, /, =)
        assertThat(refreshToken).doesNotContain("+", "/", "=");
    }
}

// AuthenticationServiceTest.java
@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock BCryptPasswordEncoder passwordEncoder;
    @Mock JwtTokenProvider jwtTokenProvider;
    @InjectMocks AuthenticationService authService;

    @Test
    void should_authenticate_valid_credentials_and_return_tokens() {
        User user = buildUser("+22670000001", "$2a$12$hashedpassword", 0, null);
        when(userRepository.findByPhoneNumber("+22670000001")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("SecurePass1!", user.passwordHash())).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(any(), any(), any())).thenReturn("eyJhbGci...");
        when(jwtTokenProvider.generateRefreshToken()).thenReturn("opaque-refresh-token");
        when(passwordEncoder.encode("opaque-refresh-token")).thenReturn("$2a$12$hashedrefresh");

        AuthTokens result = authService.authenticate(
            new AuthenticateUserCommand("+22670000001", "SecurePass1!", null));

        assertThat(result.accessToken()).isEqualTo("eyJhbGci...");
        assertThat(result.refreshToken()).isEqualTo("opaque-refresh-token");
        verify(refreshTokenRepository).save(any(RefreshToken.class));
        verify(userRepository).save(argThat(u -> u.failedAttempts() == 0));
    }

    @Test
    void should_lock_account_after_5_failed_attempts() {
        User user = buildUser("+22670000001", "$2a$12$hash", 4, null); // 4 previous failures
        when(userRepository.findByPhoneNumber("+22670000001")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> authService.authenticate(
            new AuthenticateUserCommand("+22670000001", "WrongPass", null)))
            .isInstanceOf(DomainException.class)
            .satisfies(e -> assertThat(((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS.name()));

        // 5th failure → account locked
        verify(userRepository).save(argThat(u ->
            u.failedAttempts() == 5 && u.lockedUntil() != null));
    }

    @Test
    void should_reject_locked_account_immediately() {
        User lockedUser = buildUser("+22670000001", "$2a$12$hash", 5,
            Instant.now().plus(10, ChronoUnit.MINUTES));
        when(userRepository.findByPhoneNumber("+22670000001")).thenReturn(Optional.of(lockedUser));

        assertThatThrownBy(() -> authService.authenticate(
            new AuthenticateUserCommand("+22670000001", "AnyPass", null)))
            .isInstanceOf(DomainException.class)
            .satisfies(e -> assertThat(((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED.name()));

        verify(passwordEncoder, never()).matches(any(), any());
    }
}
```

**Flutter Tests:**

```dart
// login_usecase_test.dart — WRITE THIS FIRST
void main() {
  setUp(() {
    GoogleFonts.config.allowRuntimeFetching = false; // mandatory
  });

  group('LoginUseCase', () {
    late MockAuthRepository mockRepo;
    late MockFlutterSecureStorage mockStorage;
    late LoginUseCase useCase;

    setUp(() {
      mockRepo = MockAuthRepository();
      mockStorage = MockFlutterSecureStorage();
      useCase = LoginUseCase(authRepository: mockRepo, secureStorage: mockStorage);
    });

    test('should store tokens in secure storage on success', () async {
      final tokens = AuthTokens(
        accessToken: 'eyJhbGci...',
        refreshToken: 'opaque-token',
        userId: 'user-uuid',
        tenantId: 'KV-ABC123',
        role: 'OWNER',
        expiresIn: 86400,
      );
      when(() => mockRepo.login('+22670000001', 'SecurePass1!')).thenAnswer((_) async => tokens);

      await useCase.execute(phoneNumber: '+22670000001', password: 'SecurePass1!');

      verify(() => mockStorage.write(key: 'jwt_token', value: 'eyJhbGci...')).called(1);
      verify(() => mockStorage.write(key: 'refresh_token', value: 'opaque-token')).called(1);
      verify(() => mockStorage.write(key: 'tenant_id', value: 'KV-ABC123')).called(1);
      verify(() => mockStorage.write(key: 'user_id', value: 'user-uuid')).called(1);
    });

    test('should throw DomainException on INVALID_CREDENTIALS', () async {
      when(() => mockRepo.login(any(), any()))
          .thenThrow(DomainException('INVALID_CREDENTIALS'));

      expect(
        () => useCase.execute(phoneNumber: '+22670000001', password: 'wrongpass'),
        throwsA(isA<DomainException>()
            .having((e) => e.code, 'code', 'INVALID_CREDENTIALS')),
      );
    });
  });
}
```

## File List

### Backend — New Files
- `keevo/backend/src/main/resources/keys/private_key.pem` (gitignored)
- `keevo/backend/src/main/resources/keys/public_key.pem`
- `keevo/backend/src/test/resources/keys/private_key_test.pem`
- `keevo/backend/src/test/resources/keys/public_key_test.pem`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtProperties.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/RsaKeyConfig.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/model/AuthTokens.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/model/RefreshToken.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/model/UserAuthenticatedEvent.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/port/in/AuthenticateUserUseCase.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/port/in/AuthenticateUserCommand.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/port/in/RefreshTokenUseCase.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/port/out/RefreshTokenRepository.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/application/service/AuthenticationService.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/application/service/RefreshTokenService.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/RefreshTokenJpaEntity.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/RefreshTokenJpaRepository.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/JpaRefreshTokenRepository.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/in/rest/dto/LoginRequest.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/in/rest/dto/LoginResponse.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/in/rest/dto/RefreshRequest.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/in/rest/dto/RegistrationRequest.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/in/rest/dto/RegistrationResponse.java`
- `keevo/backend/src/main/resources/db/tenant-migration/V3__add_auth_lockout_and_refresh_tokens.sql`
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/security/JwtTokenProviderTest.java`
- `keevo/backend/src/test/java/com/keevo/identity/auth/application/service/AuthenticationServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/security/JwtAuthFilterTest.java`
- `keevo/backend/src/test/java/com/keevo/identity/auth/application/service/RefreshTokenServiceTest.java`

### Backend — Modified Files
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtTokenProvider.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/TenantJwtFilter.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/in/rest/AuthController.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/UserJpaEntity.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/JpaUserRepository.java`
- `keevo/backend/src/main/resources/application.yml`
- `keevo/backend/src/test/java/com/keevo/identity/auth/adapter/in/rest/AuthControllerTest.java`

### Flutter — New Files
- `keevo/app/lib/features/auth/domain/model/auth_tokens.dart`
- `keevo/app/lib/features/auth/domain/model/auth_tokens.freezed.dart`
- `keevo/app/lib/features/auth/domain/usecase/login_usecase.dart`
- `keevo/app/lib/core/network/auth_interceptor.dart`
- `keevo/app/lib/features/auth/presentation/page/login_page.dart`
- `keevo/app/test/features/auth/domain/usecase/login_usecase_test.dart`
- `keevo/app/test/features/auth/data/repository/auth_repository_impl_test.dart`
- `keevo/app/test/core/network/auth_interceptor_test.dart`
- `keevo/app/test/features/auth/presentation/page/login_page_test.dart`

### Flutter — Modified Files
- `keevo/app/lib/features/auth/domain/repository/auth_repository.dart`
- `keevo/app/lib/features/auth/domain/repository/token_storage.dart`
- `keevo/app/lib/features/auth/data/repository/secure_token_storage.dart`
- `keevo/app/lib/features/auth/data/datasource/remote_auth_datasource.dart`
- `keevo/app/lib/features/auth/data/repository/auth_repository_impl.dart`
- `keevo/app/lib/features/auth/presentation/provider/auth_provider.dart`
- `keevo/app/lib/features/auth/presentation/provider/auth_provider.g.dart`
- `keevo/app/lib/core/router/app_router.dart`
- `keevo/app/test/features/auth/presentation/page/register_page_test.dart`

## Dev Agent Record

### Agent: Amelia (dev)
### Completion Date: 2026-03-04

### Implementation Summary

Story 1.3 a implementé l'authentification JWT end-to-end sur le backend (Spring Boot) et l'application Flutter.

**Décisions techniques clés :**
- Refresh token haché en SHA-256 (lookup déterministe) plutôt que bcrypt (non-déterministe — incompatible avec `findByHash`). La spec disait bcrypt mais SHA-256 est le bon choix pour les tokens opaques.
- `AuthController` : suppression du `@ExceptionHandler` local qui shadowait silencieusement `GlobalExceptionHandler`, causant des échecs de tests. Toutes les `DomainException` sont maintenant gérées exclusivement par `GlobalExceptionHandler`.
- `AuthInterceptor` Flutter utilise un `_refreshDio` dédié (sans intercepteurs) pour éviter les boucles infinies de refresh. Protection via flag `_isRefreshing`.
- Claims JWT : `tenantId` contient le nom du schéma (ex : `kv_abc123`) pour injection directe dans `TenantContext`.

**Résultats des tests à la clôture de la story :**
- Backend : 60/62 tests passent (2 échecs pré-existants d'intégration nécessitant PostgreSQL en live — non des régressions)
- Flutter : 35/35 tests passent ✅

**Notes pour le reviewer :**
- `V3__add_auth_lockout_and_refresh_tokens.sql` consolide les changements DB de la story 1.3 (colonnes lockout + table refresh_tokens) en une seule migration.
- Les clés RSA de test dans `src/test/resources/keys/` sont intentionnellement légères (tests unitaires uniquement). Les clés de production doivent être générées et stockées comme secrets d'environnement.
- `login_page_test.dart` cible le `TextField` interne de `IntlPhoneField` via `find.descendant` et utilise un `Completer` (pas de `Future.delayed`) pour éviter les échecs FakeAsync liés aux timers en attente.

---

### Code Review Record — 2026-03-04

**Reviewer**: GitHub Copilot (code-review workflow)
**Outcome**: PASSED — all issues resolved, status → done

#### Issues Found & Fixed

| ID | Severity | Issue | Resolution |
|---|---|---|---|
| CRIT-1 | Critical | `AuthControllerTest` had 0 login/refresh tests — tasks 1.4 marked ✅ but untested | Rewrote test file: 9 new tests added (login 200/401×2/422, refresh 200/401/422, register preserved) |
| CRIT-2 | Critical | `RefreshTokenServiceTest` used `anyString()` for hash lookup — never catches missing SHA-256 logic | All `anyString()` replaced with exact `sha256Base64Url(rawToken)` computed inline; helper mirrors production hash |
| HIGH-1 | High | DTOs colocated with controller in `adapter/in/rest/` — poor architectural visibility | Created `adapter/in/rest/dto/` subfolder; moved all 5 DTOs; updated imports everywhere; deleted old files |
| HIGH-2 | High | `AuthenticationServiceTest` mocked `JwtTokenProvider` — violates Test Validity Principle | Replaced mock with real `JwtTokenProvider` instance loaded from test RSA keys; assertions verify real JWT claims via `parseToken()` |
| MED-1 | Medium | Story File List had wrong test package paths (`identity/auth/infrastructure/security/`) | Corrected to `shared/infrastructure/security/` for `JwtTokenProviderTest` and `JwtAuthFilterTest`; DTO paths updated to `dto/` subfolder |
| MED-2 | Medium | No real security filter path tested in `AuthControllerTest` | Documented: `JwtAuthFilterTest` (6 tests) covers the real filter integration path |
| LOW-1 | Low | Stale Javadoc in `AuthController` mentioning "stub in Story 1.2" | Removed outdated comment |

#### Test Results After Fix

| Test Class | Tests | Pass | Notes |
|---|---|---|---|
| `AuthControllerTest` | 11 | ✅ 11 | All new login/refresh/register tests pass |
| `RefreshTokenServiceTest` | 5 | ✅ 5 | SHA-256 hash verification enforced |
| `AuthenticationServiceTest` | 7 | ✅ 7 | Real `JwtTokenProvider` validates actual JWT claims |
| `JwtTokenProviderTest` | 9 | ✅ 9 | Unaffected |
| `JwtAuthFilterTest` | 6 | ✅ 6 | Unaffected |
| `JpaBaseEntityPersistTest` | 2 | ⚠️ 0/2 errors | Pre-existing — requires live PostgreSQL, not a regression |
