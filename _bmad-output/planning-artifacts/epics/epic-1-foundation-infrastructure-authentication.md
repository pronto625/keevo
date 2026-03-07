# Epic 1: Foundation, Infrastructure & Authentication

Simon peut s'inscrire avec son numéro WhatsApp, obtenir son tenant isolé automatiquement, se connecter sur mobile et desktop, compléter son onboarding sectoriel en moins de 5 minutes, et voir en permanence l'état de sa connexion et de sa synchronisation. L'architecture hexagonale monorepo Flutter + Spring Boot est initialisée avec toutes les fondations techniques : multi-tenant, RBAC, subscription model, sécurité, audit et scaffold CI/CD.

## Story 1.1: Project Initialization & Architecture Scaffold

As a developer (Toor),
I want the full monorepo scaffold initialized with hexagonal architecture, CI/CD, and all technical foundations,
So that every subsequent story has a clean, consistent structure to build upon with zero rework.

**Acceptance Criteria:**

**Given** a fresh empty repository `keevo/`
**When** the project initialization story is completed
**Then** the monorepo structure exists with `backend/` (Spring Boot), `app/` (Flutter), `docker-compose.yml` (PostgreSQL + dev services), `.gitignore`, and `README.md`

**Given** the Flutter app is initialized
**When** running `flutter create --org com.keevo --project-name keevo --platforms android,ios,linux,windows --empty ./` inside `app/`
**Then** the project compiles and runs on Android, iOS, Linux, and Windows with zero errors
**And** the feature-first folder structure exists: `lib/core/` (di, network, storage, sync, auth, theme, router) and `lib/features/` (auth, pos, products, inventory, stores, reports, settings, onboarding)
**And** `pubspec.yaml` includes: `flutter_riverpod`, `riverpod_generator`, `go_router`, `drift`, `sqlite3_flutter_libs`, `flutter_secure_storage`, `freezed`, `build_runner`
**And** Material 3 `ThemeData` is configured with Indigo Sky palette tokens (`colorPrimary: #3B5BDB`, `colorSuccess: #51CF66`, `colorWarning: #FCC419`, `colorError: #FA5252`, dark surface `#0D1B2A`) in `lib/core/theme/`
**And** `ThemeMode.system` is set — dark mode follows system preferences automatically
**And** `go_router` is configured in `lib/core/router/` with placeholder routes for all features
**And** Inter font (Google Fonts) is configured with weights 300–700
**And** all domain layers (`domain/`, `data/`, `presentation/`) exist per feature with `.gitkeep` files

**Given** the Spring Boot backend is initialized
**When** Spring Initializr generates the project (Spring Boot 3.5.x, Java 21, Maven)
**Then** `backend/pom.xml` includes: Web, Data JPA, Security, Actuator, PostgreSQL, Flyway (dependency present, disabled at runtime via `spring.flyway.enabled=false`), Validation, Lombok
**And** the hexagonal domain structure exists for all 10 domains: `identity/`, `catalog/`, `commerce/`, `inventory/`, `store/`, `reporting/`, `messaging/`, `sync/`, `subscription/`, `admin/`
**And** each domain contains the sub-structure: `domain/model/`, `domain/port/in/`, `domain/port/out/`, `application/service/`, `adapter/in/rest/`, `adapter/out/persistence/`
**And** `shared/` contains: `infrastructure/security/` (stub), `infrastructure/persistence/` (TenantContext stub, MultiTenantConnectionProvider interface), `infrastructure/web/` (GlobalExceptionHandler stub), `domain/exception/DomainException.java`, `domain/model/Money.java` (XAF value object)
**And** `application.yml`, `application-dev.yml`, `application-prod.yml` exist with placeholder configuration

**Given** the sync infrastructure stubs are needed by all subsequent features
**When** the sync scaffold is created
**Then** `lib/core/sync/sync_service.dart` interface exists with methods `push()`, `pull()`, `queueOperation()`
**And** a Drift table `SyncQueue` exists with columns: `id` (UUID), `operation` (string), `payload` (JSON), `createdAt`, `synced` (bool)
**And** `sync/sync/domain/port/in/SyncUseCase.java` interface exists in backend with methods `push()` and `pull()`
**And** stub REST endpoints exist: `POST /api/v1/sync/push` and `GET /api/v1/sync/pull` returning `501 Not Implemented`

**Given** CI/CD pipelines are needed from day one
**When** GitHub Actions workflows are created
**Then** `.github/workflows/backend-ci.yml` runs `mvn test` on every push to `main` and `develop`
**And** `.github/workflows/flutter-ci.yml` runs `flutter test` and `flutter analyze` on every push
**And** both pipelines pass on the initial scaffold (zero tests, zero lint errors)

**Given** `docker-compose.yml` is needed for local development
**When** running `docker-compose up`
**Then** a PostgreSQL 16 instance starts on port 5432 with database `keevo_dev`, user `keevo`, password configurable via `.env`
**And** `backend/.env.example` documents all required environment variables

---

## Story 1.2: User Registration & Automated Tenant Provisioning

As a proprietor (Simon),
I want to create my account with my phone number and have my isolated workspace provisioned automatically,
So that I can start using Keevo immediately without any manual configuration.

**Acceptance Criteria:**

**Given** Simon opens Keevo for the first time
**When** he submits his phone number and password on the registration screen
**Then** the system creates his user account with role `OWNER`
**And** a unique tenant code `KV-XXXXXX` (6 alphanumeric characters, uppercase) is generated and stored
**And** a dedicated PostgreSQL schema `kv_xxxxxx` is created automatically via `TenantFactory`
**And** `TenantSchemaProvisioner` provisions the tenant schema programmatically (JDBC — no SQL migration files), creating 5 tables: `users`, `subscriptions`, `roles`, `user_roles`, `stores`, and seeding the `OWNER` and `EMPLOYEE` roles and a `Free` subscription
**And** a default store named after the business (entered in onboarding) is created
**And** the `Plan Free` subscription limits apply immediately: 3 stores, 500 products, 5 employees
**And** the JWT token for the new user contains `tenantId`, `userId`, `role` claims
**And** the response returns HTTP 201 with the tenant code and JWT token

**Given** two different proprietors register simultaneously
**When** both registrations complete
**Then** each has a completely isolated PostgreSQL schema — no shared tables, no cross-tenant data possible
**And** querying one tenant's schema returns zero results from the other tenant's data

**Given** Simon tries to register with a phone number already in use
**When** he submits the registration form
**Then** the system returns an error `{ "domainCode": "USER_ALREADY_EXISTS" }` with HTTP 409
**And** no schema or tenant is created

**Given** a registration fails mid-way (e.g., schema creation error)
**When** the error occurs
**Then** the entire provisioning is rolled back — no partial tenant exists
**And** the user account is not created

**Given** the Flutter app receives the successful registration response
**When** the JWT is received
**Then** the token is stored securely in `flutter_secure_storage` (never in SharedPreferences or plaintext)
**And** the app navigates to the onboarding wizard (Story 1.4)

---

## Story 1.3: JWT Authentication & Session Management

As a user (Simon or Loïc),
I want to log in securely with my phone number and password on any device,
So that I can access my workspace from mobile and desktop with my data protected.

**Acceptance Criteria:**

**Given** Simon has a registered account
**When** he submits correct credentials on the login screen
**Then** the system returns a JWT access token (RS256, expires 24h) and a refresh token (expires 30 days)
**And** the JWT payload contains: `userId`, `tenantId`, `role`, `iat`, `exp`
**And** all tokens are stored via `flutter_secure_storage`
**And** `JwtAuthFilter` (`OncePerRequestFilter`) on the backend resolves `tenantId` from the JWT and sets `TenantContext` via `ThreadLocal` before every protected request
**And** `JwtAuthFilter.shouldNotFilter()` bypasses JWT validation for public paths: `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/refresh`

**Given** Simon's JWT has expired
**When** he makes any API request
**Then** the backend returns HTTP 401 with `{ "domainCode": "TOKEN_EXPIRED" }`
**And** the Flutter app automatically attempts a token refresh using the refresh token
**And** if refresh succeeds, the original request is retried transparently
**And** if refresh fails, the user is redirected to the login screen

**Given** Simon tries to log in with an incorrect password
**When** he submits the form
**Then** the system returns HTTP 401 with `{ "domainCode": "INVALID_CREDENTIALS" }`
**And** no token is issued
**And** after 5 consecutive failed attempts, the account is locked for 15 minutes

**Given** passwords are stored in the database
**When** a user account is created or password changed
**Then** the password is hashed with bcrypt at cost factor ≥ 12
**And** the plaintext password is never logged or stored anywhere

**Given** all API traffic between Flutter and backend
**When** any request is made
**Then** all data is transmitted over HTTPS (TLS 1.2+)
**And** the HTTP client in Flutter rejects connections without valid TLS certificates

**Given** Simon logs in on a second device (desktop)
**When** both sessions are active
**Then** both devices can operate independently with their own valid tokens

**Given** `JwtAuthFilter` (`shared/infrastructure/security/`) is active on all protected endpoints
**When** a request arrives without a valid JWT
**Then** the backend returns HTTP 401 immediately, before reaching any business logic
**And** the tenant schema is never touched

---

## Story 1.4: Onboarding Wizard — Sector Templates & Shop Setup

As a new proprietor (Simon),
I want to complete my initial setup in under 5 minutes by choosing my business type and naming my shop,
So that Keevo is immediately personalized for my sector with the right product categories ready to use.

**Acceptance Criteria:**

**Given** Simon has just registered and his tenant is provisioned
**When** the onboarding wizard launches
**Then** Screen 1 shows a visual grid of sector tiles with emoji icons:
  - 👗 Vêtements & Shopping
  - 📱 Électronique & Smartphones
  - 📚 Librairie & Fournitures Scolaires
  - 🏠 Électroménager & Cuisine
  - 🍎 Alimentation
  - 💊 Pharmacie
  - 🔧 Quincaillerie
  - ➕ Autre (custom)
**And** each tile has a ripple animation on tap
**And** selecting a sector highlights it with `#D0EBFF` background

**Given** Simon selects "👗 Vêtements & Shopping"
**When** he taps the tile and proceeds
**Then** the sector template is applied: default categories are created in his tenant schema (e.g., Hauts, Pantalons, Robes, Chaussures, Accessoires)
**And** the UI language, icons, and product field suggestions reflect the clothing sector
**And** this selection is stored in the tenant preferences

**Given** Simon is on Screen 2 (shop name)
**When** he types the name of his first store and taps "Terminer"
**Then** the store is created in his tenant schema with the provided name
**And** the onboarding completes in under 5 minutes total (measured from registration confirmation)
**And** the app navigates directly to the POS screen — ready to sell immediately

**Given** Simon skips adding products during onboarding
**When** he arrives on the POS screen for the first time
**Then** the app is fully functional with zero pre-loaded products
**And** a non-blocking empty state with illustration and CTA "Ajouter votre premier produit" is shown on the products screen
**And** Simon can create a product on-the-fly during his first sale (progressive adoption)

**Given** integrated French video tutorials are available
**When** Simon completes onboarding
**Then** a dismissible tooltip offers a 2-minute tutorial for each key module (POS, Stock, Rapports)
**And** tutorials are accessible at any time from Paramètres > Aide
**And** tutorials can be skipped without blocking any functionality

**Given** notification preferences need a default value
**When** tenant provisioning completes
**Then** end-of-day report time defaults to 20:00 local time
**And** stock alert notifications default to enabled
**And** Simon can change these in Paramètres at any time

---

## Story 1.5: Local Data Storage, Encryption & Connectivity Indicator

As a user (Simon or Loïc),
I want all my data stored securely on my device and to always know my connection status,
So that I can work with confidence whether online or offline, knowing my data is safe.

**Acceptance Criteria:**

**Given** the app is installed on a device
**When** Drift initializes the local database for the first time
**Then** the SQLite database is encrypted with SQLCipher (encryption at rest)
**And** the encryption key is generated on first launch and stored in `flutter_secure_storage`
**And** the database file is inaccessible without the key — opening it with a standard SQLite browser returns an error

**Given** all critical data entities
**When** they are stored locally via Drift
**Then** the following tables exist in the local database: `products`, `stock_levels`, `sales`, `sale_items`, `stores`, `sync_queue`, `users`, `categories`
**And** all monetary values are stored as integers (XAF, no decimals) — the `Money` value object enforces this rule
**And** all IDs are UUID v4 strings

**Given** the app is running on any screen
**When** the network state changes
**Then** the `SyncIndicator` component is always visible in the AppBar trailing position
**And** it displays one of four states:
  - 🟢 "En ligne" — connected and synced
  - 🔵 "Synchronisation..." — actively syncing (animated)
  - 🟡 "Hors-ligne — Jour X/7" — offline with days counter
  - 🔴 "Hors-ligne critique — Jour 6/7" — offline, warning threshold reached (day 5+)
**And** the indicator updates within 3 seconds of a network state change
**And** tapping the indicator shows a bottom sheet with last sync timestamp and a "Synchroniser maintenant" button (disabled if offline)

**Given** the app is offline
**When** any data-writing operation is performed (sale, stock adjustment, transfer)
**Then** the operation is queued in the local `sync_queue` Drift table with: `id` (UUID), `operation` type, `payload` (JSON), `createdAt`, `synced: false`
**And** the UI confirms the operation succeeded locally with the standard success SnackBar
**And** the user receives no error or warning about being offline — the operation feels identical to online mode

**Given** the app size constraints
**When** the Flutter app is built in release mode
**Then** the APK/IPA size does not exceed 100 MB (excluding user data)
**And** the app runs at ≥ 30 FPS on a device with 2 GB RAM (tested on Samsung Galaxy A14 equivalent)

---

## Story 1.6: Subscription Model, Plan Limits & Account Lifecycle

As a proprietor (Simon),
I want to understand my plan limits clearly and manage my account lifecycle,
So that I know what I can do on the free plan, benefit from my 6-month Premium Trial, and have a clear path to upgrade when the trial expires.

**Acceptance Criteria:**

**Given** a new tenant completes registration
**When** the tenant is provisioned
**Then** the subscription is initialized as `PREMIUM_TRIAL` (6 months of unlimited Premium features, `expires_at = now + 6 months`)
**And** after 6 months without payment, the system automatically downgrades the tenant to the `FREE` plan (1 store, 500 products, 3 employees)
**And** the Flutter app shows a trial banner: "Plan Premium Trial — Expire le [date]"

**Given** Simon is on the Free plan (after trial expired)
**When** he attempts to create a 2nd store (limit: 1)
**Then** a bottom sheet appears with the message "Vous avez atteint la limite de 1 boutique sur votre plan gratuit"
**And** a prominent CTA button "Passer au plan Premium" is shown
**And** the store is NOT created
**And** the same enforcement applies for products (limit: 500) and employees (limit: 3)

**Given** plan limits are enforced
**When** the backend receives a request to create an entity that would exceed the limit
**Then** the backend returns HTTP 403 with `{ "domainCode": "PLAN_LIMIT_EXCEEDED", "details": { "limit": 1, "current": 1, "entity": "stores" } }`
**And** limits are checked server-side on every creation request — they cannot be bypassed from the client

**Given** Simon's Premium Trial or paid Premium subscription expires
**When** the expiration date passes and the daily scheduler runs
**Then** the system automatically downgrades the tenant to `plan_type = FREE`, `status = ACTIVE`
**And** write operations beyond Free limits return HTTP 403 with `PLAN_LIMIT_EXCEEDED` (NOT ACCOUNT_SUSPENDED)
**And** read operations (view stock, view history) continue to work
**And** Simon's data is fully preserved — nothing is deleted
**And** the Flutter app shows a banner: "Votre période d'essai est terminée. Passez au Premium pour retrouver tous vos accès."

**Given** Simon's account is on Free plan (trial expired or never paid)
**When** Toor (Super Admin) manually activates his paid plan via `POST /api/v1/admin/subscriptions/{tenantId}/activate`
**Then** the tenant `plan_type` changes to `PREMIUM` within one request
**And** all Premium features are immediately restored (no limits)
**And** Simon receives a WhatsApp notification confirming reactivation

**Given** Simon navigates to Paramètres > Souscription
**When** the screen loads
**Then** he sees his current plan (Free / Premium Trial / Premium), status (Actif / Expiré / Suspendu), expiry date (if Trial or Premium), and usage:
  - Free plan: "1/1 boutique, 87/500 produits, 2/3 employés"
  - Trial/Premium: "boutiques illimitées, produits illimités, employés illimités"

**Given** the rate limiting rule
**When** a tenant makes more than 100 API requests in one minute
**Then** subsequent requests return HTTP 429 with `{ "domainCode": "RATE_LIMIT_EXCEEDED" }`
**And** the limit resets after 60 seconds
**And** other tenants are completely unaffected

---

## Story 1.7: Multi-Tenant User Memberships & Two-Step Login

> ✅ **Correct Course — 2026-03-07** — Architectural fix identified during Stories 1.2/1.3 implementation.
> Original Story 1.7 (Audit Trail) is renumbered to 1.8.

As a user (Simon or Loïc),
I want to be able to belong to multiple Keevo tenants simultaneously (e.g., OWNER of my own shop AND EMPLOYEE in another merchant's shop),
So that a single phone number uniquely identifies my global identity while my roles and permissions are scoped per tenant at login.

**Context:** `public.users.tenant_id` (1:1 coupling) must be replaced by `public.user_tenant_memberships` (N:N). Login is split into two API steps: (1) verify credentials → return `loginToken` + memberships list; (2) select tenant → return full scoped JWT. Flutter auto-selects if user has only 1 membership (zero UX change for 99% of V1 users).

**Note:** This is NOT related to Epic 3 multi-boutiques. Epic 3 = multiple stores within one tenant. This story = one person belonging to multiple tenants.

**Acceptance Criteria:** See `_bmad-output/implementation-artifacts/1-7-multi-tenant-user-memberships-two-step-login.md` for full AC, tasks, and dev notes.

---

## Story 1.8: Immutable Audit Trail & Security Domain Foundation

> Renumbered from 1.7 — 2026-03-07

As a proprietor (Simon),
I want every modification to my data to be permanently recorded with full traceability,
So that I can trust my data is accurate, disputes are resolvable, and my employees are protected by total transparency.

**Acceptance Criteria:**

**Given** any write operation occurs in the system (stock change, price update, sale, transfer, user modification)
**When** the operation is committed to the database
**Then** a domain event `{Entity}{PastTense}Event` is emitted (e.g., `StockAdjustedEvent`, `SaleCompletedEvent`, `ProductPriceUpdatedEvent`)
**And** the `AuditEventListener` captures the event and writes an immutable entry to the `audit_log` table with: `id` (UUID), `tenantId`, `userId`, `entityType`, `entityId`, `action`, `valueBefore` (JSON), `valueAfter` (JSON), `occurredAt` (ISO 8601 UTC)
**And** this happens synchronously within the same transaction — if the main operation fails, no audit entry is written

**Given** an audit entry has been written
**When** any user (Owner, Employee, or Super Admin) attempts to update or delete it via any API endpoint
**Then** the backend returns HTTP 403 with `{ "domainCode": "AUDIT_IMMUTABLE" }`
**And** no modification is possible — the audit log is append-only at the database level (row-level security or trigger)

**Given** Simon views the stock movement history for a product (FR27)
**When** the history screen loads
**Then** he sees all audit entries for that product: entry type (Vente, Transfert, Ajustement, Entrée stock), quantity change, actor name, timestamp, and before/after values
**And** entries are displayed in reverse chronological order

**Given** tenant data isolation is a non-negotiable security requirement
**When** a request arrives with a valid JWT for tenant `kv_000001`
**Then** the `TenantContext` is set to `kv_000001` via `ThreadLocal` before any repository call
**And** all JPA queries automatically use the `kv_000001` schema (via `MultiTenantConnectionProvider`)
**And** it is architecturally impossible for a query from tenant `kv_000001` to read or write data from `kv_000002`
**And** an automated integration test verifies this isolation: two tenants with identical product IDs — querying one never returns data from the other

**Given** the `GlobalExceptionHandler` is active
**When** any unhandled exception or domain exception occurs
**Then** the response always follows the standard error format: `{ "error": "...", "code": "HTTP_STATUS", "domainCode": "DOMAIN_SPECIFIC_CODE", "details": { ... }, "timestamp": "ISO8601" }`
**And** stack traces are never exposed in API responses (only logged server-side)
**And** user-facing error messages are in French, human-readable, with no technical jargon

---

## Story 1.9: Certificate Pinning — Transport Security Hardening

> Renumbered from 1.8 — 2026-03-07
> ⏸️ **POST-MVP — à implémenter après la sortie V1. Ne pas bloquer le MVP.**

As a user (Simon or Loïc),
I want the app to refuse any connection that is not directly authenticated with the Keevo server certificate,
So that my credentials and business data cannot be intercepted by a MITM attack on public networks.

**Context:**

HTTPS alone is not enough on mobile. An attacker on a shared wifi (café, market) can install a trusted root certificate on the device and intercept all HTTPS traffic in plaintext using tools like Burp Suite or Charles Proxy. Certificate pinning makes the Flutter app reject any TLS connection whose certificate does not exactly match the pinned Keevo server certificate — even if it is signed by a legitimate CA.

**Acceptance Criteria:**

**Given** the Keevo backend TLS certificate (or its public key hash)
**When** the Flutter app initializes its Dio HTTP client in `core/network/api_client.dart`
**Then** a `SecurityContext` is created with `withTrustedRoots: false` (system CAs are ignored)
**And** only the Keevo server certificate (bundled in `assets/certs/keevo_server.crt`) is trusted
**And** the `HttpClient` is constructed with this `SecurityContext` and injected into Dio via `IOHttpClientAdapter`

**Given** a MITM attacker intercepts the connection with their own TLS certificate
**When** the app attempts any API request
**Then** the `HandshakeException` is caught and the request is aborted immediately
**And** the user sees a generic network error in French (no technical details exposed)
**And** no data (credentials, tokens, business data) is transmitted to the attacker

**Given** the backend TLS certificate is renewed (rotation)
**When** the new certificate is deployed on the server
**Then** a new app version with the updated certificate is published simultaneously
**And** the previous app version gracefully shows an "mise à jour requise" message instead of a cryptic error

**Implementation notes:**
- Bundle **two certificates** in the assets (current + next) to allow zero-downtime rotation
- The pinned certificate file is `assets/certs/keevo_server.crt` (PEM format, server leaf certificate)
- `withTrustedRoots: false` is mandatory — without it, system CAs still apply and pinning is bypassed
- Only applies to `IOHttpClientAdapter` (mobile/desktop) — web platform uses browser TLS, pinning not applicable
- Dev/staging builds use a separate self-signed certificate via `--dart-define=ENV=dev`

---
