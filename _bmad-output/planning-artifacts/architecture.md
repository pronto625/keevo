---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
lastStep: 8
status: 'complete'
completedAt: '2026-02-28T23:31:00+01:00'
inputDocuments:
  - planning-artifacts/prd.md
  - planning-artifacts/prd-validation-report.md
  - planning-artifacts/product-brief-AI-2026-02-13.md
  - planning-artifacts/ux-design-specification.md
  - planning-artifacts/research/market-Keevo-research-2026-02-26.md
  - planning-artifacts/research/technical-stack-technique-keevo-research-2026-02-26.md
workflowType: 'architecture'
project_name: 'Bmad-test'
user_name: 'Toor'
date: '2026-02-28T20:35:05+01:00'
---

# Architecture Decision Document

_This document builds collaboratively through step-by-step discovery. Sections are appended as we work through each architectural decision together._

## Project Context Analysis

### Requirements Overview

**Functional Requirements:**
93 FRs organized into 14 domains:
- **Auth & Onboarding** (FR1-7): WhatsApp-based signup, JWT auth, sector templates, video tutorials
- **Tenant Provisioning** (FR8-15): Auto schema creation, default roles, tenant code generation
- **Subscriptions** (FR15-20): Free plan limits (1 store, 500 products, 3 employees), Premium Trial (6 months at signup → auto-downgrade to Free on expiry), Premium paid plan, manual reactivation by admin
- **Products** (FR21-29): CRUD with variants, margin calculation, CSV import, stock thresholds
- **Multi-Store & Warehouse** (FR30-36): Centralized stock view, inter-store transfers, employee-store assignment
- **POS** (FR37-44): Quick sale, payment modes, cross-store check, day close, leaderboard
- **Inventory** (FR45-49): Guided form, theoretical vs physical, auto-gap calculation
- **Reports & Dashboard** (FR50-56): Real-time dashboard, end-of-day/weekly reports, profitability
- **WhatsApp Communication** (FR57-60): Auto daily/weekly reports, configurable frequency
- **Alerts & Notifications** (FR61-64): Stock critical, sales trends, motivational messages
- **User Management** (FR65-68): WhatsApp invite, role management, activity tracking, remote revoke
- **Offline-First Sync** (FR69-76): 7-day offline, <60s sync, delta-based conflicts, zero data loss
- **Super Admin** (FR77-83): Tenant management, revenue dashboard, platform analytics, system health
- **Security & Audit** (FR84-93): Immutable audit log, local encryption, XAF currency, error handling

**Non-Functional Requirements:**
34 NFRs driving architectural decisions:
- **Performance**: <2s local actions, <60s sync, <3s dashboard, >30 FPS on 2GB RAM, <500ms search, <100MB app
- **Security**: TLS 1.2+, SQLCipher encryption at rest, JWT 24h expiry + refresh, 100% tenant isolation, bcrypt ≥12, <5min session revocation, immutable audit
- **Scalability**: 100 tenants Phase 1, 1000+ Phase 2 (linear scaling), 50K aggregated products, 3x seasonal peaks
- **Reliability**: ≥99.5% uptime, 0% client downtime offline, ACID sync integrity, exponential backoff retry, daily backups 30-day retention
- **Accessibility**: Tech literacy 4/10, ≤3 taps critical actions, adaptive font, French V1 (i18n ready), <10min onboarding
- **Integration**: ≥100 WhatsApp msg/hour/tenant, <5s sync latency, graceful fallback, REST /api/v1/ versioning
- **Compatibility**: Android 8+ / iOS 14+, Windows 10+ / Linux Ubuntu 20.04+, 5" to 27" screens

**Scale & Complexity:**
- Primary domain: Full-stack cross-platform (Mobile + Desktop + Cloud)
- Complexity level: High
- Estimated architectural components: 8-10 major components (Auth, Sync, Products, Sales, Inventory, Reports, WhatsApp, Admin)

### Technical Constraints & Dependencies

> ⚠️ **CRITICAL OPEN DECISION — Must be resolved BEFORE Epic 5 story creation (2026-03-04)**
>
> **PostgreSQL schema strategy for sync is irreversible.**
> The current implementation (Stories 1.1 & 1.2) uses **full schema-per-tenant** (`kv_xxxxxx`) for ALL tables.
> Migrating from full separation to a hybrid model later (some tables in `public`) is a breaking change requiring data migration across all tenants — extremely costly and risky in production.
>
> **Two mutually exclusive paths — choose ONE before Epic 5:**
>
> | Path | PostgreSQL isolation | Sync engine | Migration cost if changed later |
> |---|---|---|---|
> | **A — Full schema-per-tenant (current)** | ✅ Complete per-tenant schemas | Custom REST delta (push/pull API) | N/A — this is the current state |
> | **B — Hybrid (public + tenant_id for synced tables)** | ⚠️ Partial — synced tables in `public` | PowerSync possible + Custom REST | ❌ Extremely high — full data migration |
>
> **Toor's constraint (2026-03-04):** Full PostgreSQL schema separation per tenant is non-negotiable. If this holds → Path A is locked, PowerSync is excluded permanently.
> **Decision must be confirmed before creating story 5-1.** Once Epic 5 implementation starts, this is frozen.

- **Sync engine constraint**: If using PowerSync, sync rules don't support dynamic schemas — requires hybrid approach (synced tables in shared `public` schema with `tenant_id` column, sensitive data in tenant schemas). Alternative sync solutions may have different constraints.
- **Backend**: Spring Boot (Java), schema-per-tenant via ThreadLocal, Flyway per-tenant migrations
- **Mobile**: Flutter, Drift (SQLite ORM), Material 3, responsive breakpoints (compact/medium/expanded)
- **Desktop**: Flutter Desktop (same codebase as mobile — decided in Step 3)
- **Messaging WhatsApp**: Multiple options to evaluate — WhatsApp Business API via BSP (Africa's Talking, 360Dialog...), WhatsApp automation solutions (Wassender, WPPConnect), or hybrid approach. Constraint: Meta tier warmup required if official Business API.
- **Sync Engine**: PowerSync (recommended by technical research) or alternative custom/open-source solution. Key constraint: compatibility with schema-per-tenant PostgreSQL pattern.
- **Cloud Infrastructure**: AWS, GCP, or combination of managed services (Supabase, Railway, etc.). Exact choices to be decided in architecture steps.
- **Shipping APIs**: DHL direct API, 17TRACK, Karrio open-source, or commercial aggregators. Decision deferred to Growth phase.
- **Device constraints**: Android 2GB RAM entry-level, app ≤100MB, offline-first as default mode

### Cross-Cutting Concerns Identified

1. **Offline-First Synchronization** — Affects every data-writing component (POS, inventory, transfers, user management). Delta-based sync pattern required for stock quantities.
2. **Multi-Tenant Isolation** — Every API endpoint, database query, and sync operation must enforce tenant boundaries. JWT-based tenant resolution across all layers.
3. **RBAC (Role-Based Access Control)** — Owner vs Employee permissions affect POS, reports, admin, and stock management. Super Admin operates at platform level.
4. **Audit Trail** — Immutable logging of all modifications (stock, prices, sales, transfers) with identity, timestamp, before/after values. Spans all transactional components.
5. **WhatsApp Integration** — Cross-cuts reports, alerts, onboarding (invite), and day-close flows. Provider-agnostic abstraction layer needed.
6. **Responsive Design** — All UI components must adapt across compact (mobile), medium (tablet), and expanded (desktop) breakpoints with feature parity.
7. **Error Handling & Graceful Degradation** — External service failures (WhatsApp, sync engine, cloud) must not block core offline operations.

## Starter Template Evaluation

### Primary Technology Domain

Full-stack cross-platform: Flutter (mobile + desktop) + Spring Boot (backend API) + PostgreSQL (cloud DB)

### Starter Options Considered

**Flutter App:**
- `flutter create` (standard) — clean slate, maximum flexibility for custom architecture
- Very Good CLI v0.28.0 — opinionated with BLoC, multi-flavor, CI, 100% test setup

**Spring Boot Backend:**
- Spring Initializr — industry standard, customizable dependency selection

**Desktop:**
- Flutter Desktop (same codebase) — single codebase, shared Drift/sync/UI
- Tauri 2.0 (CLI v2.10.0) — lightweight bundle but separate TypeScript codebase

### Selected Starters

**Flutter App: `flutter create --empty` (Flutter 3.41)**

- **Rationale**: Maximum flexibility for custom clean architecture with Drift + sync engine + multi-tenant patterns. No framework lock-in (BLoC vs Riverpod decided later in architecture).
- **Initialization**: `flutter create --org com.keevo --project-name keevo --platforms android,ios,linux,windows --empty ./`
- **Architectural decisions provided**: Dart null-safe, Material 3 theme system, multi-platform support (mobile + desktop)

**Spring Boot Backend: Spring Initializr (Spring Boot 3.5.x, Java 21)**

- **Rationale**: Industry standard for Java multi-tenant SaaS. Schema-per-tenant pattern (ThreadLocal, MultiTenantConnectionProvider, TenantJwtFilter, Flyway programmatic) implemented manually on top.
- **Dependencies**: Web, Data JPA, Security, Actuator, PostgreSQL, Flyway, Validation, Lombok
- **Architectural decisions provided**: Maven build, JAR packaging, Java 21 LTS

**Desktop: Flutter Desktop (same codebase)**

- **Rationale**: Single codebase for solo developer. Shared Drift DB, sync engine, and UI components. Responsive breakpoints (compact/medium/expanded) handle mobile ↔ desktop adaptation.
- **No separate starter needed** — built into Flutter project with `--platforms linux,windows`

**Note:** Project initialization using these commands should be the first implementation stories.

## Core Architectural Decisions

### Foundational Architecture Principles

**NON-NEGOTIABLE constraints driving ALL architectural decisions:**

1. **Hexagonal Architecture (Ports & Adapters)** — Business logic at the center, all external dependencies (DB, APIs, UI) connected via ports (interfaces) and adapters (implementations). Both frontend and backend.
2. **Clean Code** — Readable, maintainable, well-named, single-responsibility. No shortcuts.
3. **Open/Closed Principle (SOLID)** — Code is closed for modification, open for extension. Every external dependency accessed through interfaces. Design patterns applied per problem.
4. **Domain-Driven Decomposition** — Backend organized by business domains, each domain containing modules. Designed for future microservice extraction.
5. **100% Schema-per-Tenant Isolation** — No shared data tables. Every tenant completely isolated in `kv_xxxxxx` schema. Non-negotiable.
6. **Interface-Driven Design** — All service contracts defined as interfaces. Implementations injected via DI. Enables swapping (e.g., sync engine, WhatsApp provider) without modifying consuming code.
7. **GoF Design Patterns (Gang of Four)** — Before implementing ANY feature, analyze which design pattern is most appropriate (Strategy, Observer, Factory, Adapter, Decorator, etc.). Code MUST be closed for modification, open for extension. This analysis is mandatory and non-negotiable.

### Backend Architecture (Spring Boot)

**Domain → Module → Layer decomposition:**

```
src/main/java/com/keevo/
├── shared/                          # Cross-cutting concerns
│   ├── infrastructure/
│   │   ├── security/                # JWT filter, tenant resolver
│   │   ├── persistence/             # Multi-tenant config, Flyway
│   │   └── config/                  # Spring configs
│   ├── domain/
│   │   ├── exception/               # Domain exceptions (error codes)
│   │   └── model/                   # Shared value objects
│   └── application/
│       └── port/                    # Shared ports (AuditPort, etc.)
│
├── identity/                        # DOMAIN: Identity & Access
│   ├── auth/                        # MODULE: Authentication
│   │   ├── domain/
│   │   │   ├── model/               # User, Credential, Role
│   │   │   └── port/
│   │   │       ├── in/              # AuthUseCase (interface)
│   │   │       └── out/             # UserRepository, TokenPort
│   │   ├── application/
│   │   │   └── service/             # AuthService (implements AuthUseCase)
│   │   └── adapter/
│   │       ├── in/
│   │       │   └── rest/            # AuthController + DTOs
│   │       └── out/
│   │           └── persistence/     # JpaUserRepository
│   └── user/                        # MODULE: User Management
│       └── (same structure)
│
├── catalog/                         # DOMAIN: Product Catalog
│   ├── product/                     # MODULE: Products
│   ├── category/                    # MODULE: Categories
│   └── variant/                     # MODULE: Variants
│
├── commerce/                        # DOMAIN: Sales & POS
│   ├── pos/                         # MODULE: Point of Sale
│   ├── sale/                        # MODULE: Sales
│   └── payment/                     # MODULE: Payments
│
├── inventory/                       # DOMAIN: Stock Management
│   ├── stock/                       # MODULE: Stock tracking
│   ├── transfer/                    # MODULE: Inter-store transfers (planned — Story 3.3 impl in catalog/stock/)
│   └── counting/                    # MODULE: Physical inventory
│
├── store/                           # DOMAIN: Multi-Store
│   ├── store/                       # MODULE: Store CRUD
│   └── warehouse/                   # MODULE: Warehouse
│
├── reporting/                       # DOMAIN: Reports & Analytics
│   ├── dashboard/                   # MODULE: Real-time dashboard
│   └── report/                      # MODULE: End-of-day, weekly
│
├── messaging/                       # DOMAIN: Communication
│   ├── whatsapp/                    # MODULE: WhatsApp integration
│   └── notification/                # MODULE: Alerts & notifications
│
├── sync/                            # DOMAIN: Synchronization
│   └── sync/                        # MODULE: Push/Pull REST sync
│
├── subscription/                    # DOMAIN: Billing
│   └── plan/                        # MODULE: Plans & limits
│
└── admin/                           # DOMAIN: Super Admin
    ├── tenant/                      # MODULE: Tenant management
    └── platform/                    # MODULE: Platform analytics
```

**Each module follows hexagonal layers:**
- `domain/model/` — Entities, Value Objects, Domain Events
- `domain/port/in/` — Use case interfaces (driving ports)
- `domain/port/out/` — Repository & external service interfaces (driven ports)
- `application/service/` — Use case implementations (orchestration only)
- `adapter/in/rest/` — REST controllers *(MVP)*
- `adapter/in/rest/dto/` — Request/Response DTOs (separate from controller for clarity)
- `adapter/in/mcp/` — MCP Tool adapters *(placeholder — V2, Spring AI MCP Server)*
- `adapter/out/persistence/` — JPA repositories, entity mappers
- `adapter/out/external/` — External API adapters (WhatsApp, etc.)

**MCP Port Purity Rules (non-negotiable for migration readiness):**
- Use case interfaces (`port/in/`) MUST accept only pure Java Records — NEVER `HttpServletRequest`, `Principal`, `MultipartFile`, or any HTTP/Spring-web type
- `ActorId` (authenticated user) MUST be passed explicitly as a parameter in every Command/Query — NEVER resolved via `SecurityContextHolder` inside use cases
- All command inputs MUST be named `{Action}{Entity}Command` (records) and query inputs `{Action}{Entity}Query` — these become MCP tool parameters as-is
- `ResponseEntity<>` belongs ONLY in REST adapters — use cases return domain objects or void

**Key design patterns per concern:**

| Concern | Pattern | Application |
|---|---|---|
| Tenant resolution | Strategy + ThreadLocal | `TenantResolver` interface, JWT impl |
| Sync conflicts | Strategy | `ConflictResolutionStrategy` interface (DeltaStock, LastWriteWins) |
| Audit trail | Observer / Event | Domain events → `AuditEventListener` |
| Notifications | Observer + Template Method | `NotificationPort` → WhatsApp/Push adapters |
| RBAC | Decorator + Annotations | `@RequiresRole(OWNER)` on use cases |
| Data validation | Specification | Domain-level validation rules |
| Entity creation | Factory | Complex entity creation (Tenant provisioning) |
| External services | Adapter + Façade | `WhatsAppPort` → `WassenderAdapter` / `AfricasTalkingAdapter` |
| Error handling | Domain Exceptions | `ProductNotFoundException(PRODUCT_NOT_FOUND)` → HTTP mapping |

### Frontend Architecture (Flutter)

**Feature-first + Clean Architecture:**

```
lib/
├── core/                            # Shared infrastructure
│   ├── di/                          # Riverpod providers setup
│   ├── network/                     # HTTP client, interceptors
│   ├── storage/                     # Secure storage, Drift DB
│   ├── sync/                        # SyncService interface + impl
│   ├── auth/                        # Auth state, JWT handling
│   ├── theme/                       # Material 3 Keevo tokens
│   └── router/                      # go_router config
│
├── features/
│   ├── pos/                         # FEATURE: Point of Sale
│   │   ├── domain/
│   │   │   ├── model/               # Sale, CartItem
│   │   │   ├── repository/          # SaleRepository (interface)
│   │   │   └── usecase/             # RecordSaleUseCase
│   │   ├── data/
│   │   │   ├── datasource/          # LocalSaleDataSource, RemoteSaleDataSource
│   │   │   └── repository/          # SaleRepositoryImpl
│   │   └── presentation/
│   │       ├── provider/            # Riverpod providers (@riverpod)
│   │       ├── page/                # PosPage, CheckoutPage
│   │       └── widget/              # CartWidget, ProductGrid
│   │
│   ├── inventory/                   # FEATURE: Inventory
│   ├── products/                    # FEATURE: Product management
│   ├── reports/                     # FEATURE: Reports & dashboard
│   ├── stores/                      # FEATURE: Multi-store
│   ├── settings/                    # FEATURE: User settings
│   └── auth/                        # FEATURE: Login
│
└── main.dart
```

**Flutter architectural rules:**
- Domain layer has ZERO dependencies on Flutter, Drift, or any framework
- Repository interfaces in `domain/`, implementations in `data/`
- Riverpod providers in `presentation/provider/` — bridge between domain and UI
- All external services accessed via interfaces (sync, storage, WhatsApp)

### Decision Priority Analysis

**Critical (block implementation):** Sync engine, Auth, API design, State management, Project structure, Hexagonal architecture, Domain decomposition

**Important (shape architecture):** Error handling with domain codes, Employee credential management, Rate limiting, RBAC pattern, Audit trail pattern

**Deferred (post-MVP):** Shipping APIs, Advanced analytics, E-commerce marketplace, Microservice extraction, CI/CD detailed pipeline

### Technology Decisions Summary

| Decision | Choice | Version |
|---|---|---|
| Sync engine | Custom REST delta-based (MVP) | — |
| Auth | Login/password → JWT RS256 | jjwt |
| State management | Riverpod + riverpod_generator | 3.0 |
| Routing | go_router | latest |
| API style | REST `/api/v1/` JSON | — |
| Error format | `{ error, code, domainCode, details }` | — |
| Rate limiting | Spring Boot filter 100 req/min/tenant | — |
| Cloud | AWS (RDS, EC2/ECS, S3) | — |
| CI/CD | GitHub Actions | — |
| Monitoring | CloudWatch + Spring Actuator | — |
| Password hash | bcrypt ≥12 | — |
| Token storage | flutter_secure_storage | — |
| WhatsApp | Abstraction `WhatsAppPort` — provider TBD | — |

### Decision Impact: Implementation Sequence

1. Flutter project init + Riverpod + Drift + core architecture scaffold
2. Spring Boot init + Hexagonal scaffold + Multi-tenant + Auth
3. Sync REST service (push/pull) with `SyncService` interface
4. Core features: Products → POS → Inventory (domain by domain)
5. Reports & Dashboard
6. WhatsApp integration (via `WhatsAppPort` adapter)
7. Desktop adaptation (responsive breakpoints)

### Cross-Component Dependencies

- Sync service depends on Auth (JWT tenant routing)
- RBAC affects all API endpoints + all Flutter features
- Audit trail is transversal (every write = domain event → audit log)
- Domain exceptions map 1:1 between backend `DomainException` codes and Flutter error handling
- Future microservice extraction: each domain is self-contained with well-defined ports

## Implementation Patterns & Consistency Rules

### 🔴 FULL TDD — NON-NEGOTIABLE (STRICT)

> **This section is LAW. No code may be written before a failing test. No exceptions.**

**The cycle: RED → GREEN → REFACTOR. Always in this order. Zero tolerance for deviations.**

**✅ CORRECT sequence (mandatory):**
```
1. Write failing test  →  run test  →  confirm RED
2. Write minimum code  →  run test  →  confirm GREEN
3. Refactor code       →  run test  →  confirm still GREEN
4. Repeat for every acceptance criterion
```

**❌ FORBIDDEN — any dev agent MUST refuse to:**
- Write a class or method before writing a failing test for it
- Skip tests because "the logic seems obvious"
- Write tests AFTER implementation ("test-last") — this is NOT TDD
- Use `@Disabled` tests to make a build pass
- Mark a story as done with failing tests or zero test coverage

**TDD rules:**
- NO production code without a failing test written first
- A feature is DONE only when ALL its tests pass
- Test coverage must be comprehensive — happy path + edge cases + error cases
- Tests are the specification — they document what the code does
- Every story file MUST have a **Task 1: Write RED tests** section **before** any implementation task

---

> ⚠️ **TEST VALIDITY PRINCIPLE — NON-NEGOTIABLE**
>
> When writing tests, always make sure they exercise the real production code and not just mocked behavior. If a test can pass without executing the actual runtime logic, wiring, configuration, or data flow used in production, then it provides a **false sense of safety**. Mocks should only exist at system boundaries to control external dependencies, never to replace core logic. A good test must **fail when the real system is broken**; otherwise, it validates assumptions instead of behavior and hides bugs rather than revealing them.
>
> **Practical consequences:**
> - Unit tests that mock a service under test are **invalid** — they test the mock, not the service
> - JPA/persistence behavior (ID strategy, constraints, lifecycle hooks) **must** be tested with a real DB (`@DataJpaTest` + real PostgreSQL or H2), never with a mocked repository
> - Flyway migrations and SQL correctness **must** be verified via integration tests against a real database — mocking `FlywayTenantMigration` in unit tests cannot detect missing tables or incorrect SQL
> - Spring wiring, security rules, and HTTP request/response mapping **must** be tested with the actual application context slices (`@WebMvcTest`, `@SpringBootTest`), not pure unit mocks
> - If the only way to make a test pass is by calling `.when(mock).thenReturn(...)`, ask: **"Would this test catch the bug if I deleted the production class?"** — if not, it is not a real test

---

**Backend (Spring Boot) testing stack:**

| Layer | Test Type | Tools |
|---|---|---|
| Domain models | Unit tests | JUnit 5 (pure Java — no Spring context) |
| Use cases / services | Unit tests | JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`) |
| Ports (interfaces) | Contract tests | JUnit 5 |
| Adapters (REST) | Slice tests | `@WebMvcTest` + MockMvc + `@MockBean` |
| Adapters (JPA) | Slice tests | `@DataJpaTest` + H2 or Testcontainers |
| Security rules | Security tests | `@WebMvcTest` + `spring-security-test` |
| End-to-end | Integration tests | `@SpringBootTest` + Testcontainers (PostgreSQL) |

**Frontend (Flutter) testing stack:**

| Layer | Test Type | Tools |
|---|---|---|
| Domain use cases | Unit tests | `test` package + `mocktail` |
| Data repositories | Unit tests | `test` + mock datasources |
| Providers (Riverpod) | Unit tests | `ProviderContainer` + `mocktail` |
| Widgets / Pages | Widget tests | `flutter_test` + `WidgetTester` |
| Integration flows | Integration tests | `integration_test` package |

> **Critical for Flutter tests**: Always call `GoogleFonts.config.allowRuntimeFetching = false`
> in `setUpAll()` — failure to do this causes network calls during tests.

**TDD workflow per feature:**
```
1. Write domain model test → implement model
2. Write use case test (mock port) → implement use case
3. Write repository test → implement repository
4. Write controller/provider test → implement adapter
5. Write widget test → implement UI
6. ALL tests green → feature is DONE
```

---

### 🔴 ITERATION LAW — CYCLE UNTIL EVERY TEST IS GREEN (NON-NEGOTIABLE)

> **A story is NOT done until every test passes AND every cURL integration call returns the expected response. No exceptions, no workarounds.**

**The complete validation cycle (mandatory on every story, every task):**

```
🔴 RED      → Write failing test   → run → confirm FAILURE
🟢 GREEN    → Write minimum code   → run → confirm PASS
🔵 REFACTOR → Clean code           → run → confirm still PASS
🌐 cURL     → Run integration bash → confirm all ✅
🔁 ITERATE  → Any failure?         → go back to 🔴 RED, fix, repeat
```

**Stopping condition — story may only be marked `done` when ALL of these are true simultaneously:**

| Gate | Command | Required result |
|---|---|---|
| Backend unit + slice tests | `mvn test` | `BUILD SUCCESS — 0 failures, 0 errors` |
| Flutter unit + widget tests | `flutter test --reporter=expanded` | `All N tests passed` |
| cURL integration script | bash script in story file | All `✅` — zero `❌` |

**Forbidden shortcuts (zero tolerance):**
- `@Disabled` or `@Ignore` on ANY test to force a build pass
- `// TODO: fix later` adjacent to a failing assertion
- Skipping the cURL script because "the unit tests pass"
- Marking a story `done` with a partial test suite
- Using `try/catch` in tests to swallow assertion exceptions

---

### 🌐 cURL INTEGRATION TESTS — MANDATORY AFTER EVERY BACKEND STORY

> **Every story that produces API endpoints MUST include a cURL validation section in the story file. The dev agent MUST run it before closing the story.**

**Standard format for cURL sections in story files:**

```bash
#!/usr/bin/env bash
# ======================================================
# Story X.Y — cURL Integration Tests
# Run: bash curl-tests-story-X-Y.sh
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE_URL="http://localhost:8443"

# Step 1 — Prerequisite: register a test user and get JWT
REGISTER=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"phone":"+237600000001","password":"Test1234!","firstName":"Test","lastName":"User"}')
JWT=$(echo "$REGISTER" | jq -r '.data.accessToken')
[[ -n "$JWT" && "$JWT" != "null" ]] && echo "✅ Step 1 — JWT obtained" || { echo "❌ Step 1 FAILED"; exit 1; }

# Step N — Feature endpoint call
RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/feature/endpoint" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"field": "value"}')
echo "$RESPONSE" | jq .
HTTP_STATUS=$(echo "$RESPONSE" | jq -r '.status // "200"')
# Attendu: HTTP 200, body { "data": { ... } }
[[ "$HTTP_STATUS" != "4"* && "$HTTP_STATUS" != "5"* ]] && echo "✅ Step N — OK" || { echo "❌ Step N FAILED"; exit 1; }

echo "\n✅✅✅ All cURL integration checks passed — story backend validated ✅✅✅"
```

**Rules for cURL test scripts in story files:**
- Every step is numbered sequentially and labeled with what it validates
- Expected response is documented after each call (`# Attendu: HTTP 200, ...`)
- Tokens and IDs are stored in variables and chained between steps (no hardcoded values)
- Each step fails fast with `exit 1` so the error is clearly identified
- Use `jq` for JSON field extraction — never `grep` raw JSON
- Edge cases covered: 401 Unauthorized (no token), 400 Bad Request (invalid body), 409 Conflict (duplicate), 403 Forbidden (wrong role)
- Final line echoes `✅✅✅ All cURL integration checks passed` — visible confirmation gate
- Dev agent MUST run the full script end-to-end and paste output in the PR/commit message

---

###  GoF DESIGN PATTERN ANALYSIS — MANDATORY BEFORE EVERY FEATURE

> **No implementation may begin before completing this analysis. It is documented in the story file.**

**The Dev Agent MUST fill this table in every story before writing any code:**

```markdown
## GoF Pattern Analysis (MANDATORY — fill before any implementation)

| Question | Answer |
|---|---|
| What variability exists in this feature? | [answer] |
| What might change in the future? | [answer] |
| Which GoF pattern(s) apply? | [pattern name + reason] |
| How does it enable Open/Closed principle? | [answer] |
| Where is the pattern applied? | [class/method] |
```

**Mandatory reasoning step** — for each feature, ask:

1. **Is there an algorithm that might change?** → Strategy
2. **Do I need to notify multiple systems of an event?** → Observer
3. **Is object creation complex (3+ steps / conditional logic)?** → Factory or Builder
4. **Do I need to adapt an incompatible interface?** → Adapter
5. **Do I need to add behavior without modifying the class?** → Decorator
6. **Is there a skeleton algorithm with variable steps?** → Template Method
7. **Do I need to pass a request through a chain?** → Chain of Responsibility
8. **Do I need a single global instance?** → Singleton (via DI container)
9. **Do I work with tree structures or composite objects?** → Composite
10. **Do I need to decouple sender from receiver?** → Command pattern

**Anti-pattern (FORBIDDEN):**
- Proceeding with implementation when no pattern was analyzed
- Using a pattern "by habit" without justifying it fits the problem
- Skipping the GoF analysis because the ticket "looks simple"

**Common Keevo patterns (reference):**

| Problem | GoF Pattern | Example |
|---|---|---|
| Multiple sync strategies | **Strategy** | `SyncService` → `RestSyncService`, `PowerSyncService` |
| Multiple notification providers | **Strategy + Adapter** | `WhatsAppPort` → `WassenderAdapter` |
| Complex object creation | **Factory** | `TenantFactory.create()` |
| Audit on every mutation | **Observer** | `UserRegisteredEvent` → `AuditEventListener` |
| Access control decoration | **Decorator** | `@RequiresRole(OWNER)` wrapping use cases |
| Conflict resolution | **Strategy** | `ConflictResolver` → `DeltaStockResolver` |
| Report generation | **Template Method** | `AbstractReport` → `DailyReport`, `WeeklyReport` |
| Notification routing | **Chain of Responsibility** | WhatsApp → Push → SMS fallback chain |
| Building complex queries | **Builder** | `SyncPullQueryBuilder` |
| Unique code generation | **Strategy** | `TenantCodeGenerator` |
| Registration orchestration | **Façade** | `RegistrationService` |

---

### 🔴 Spring Security — Correct Usage (Non-Negotiable)

> **This project uses `spring-boot-starter-security` (Spring Security 6.x). Spring Cloud Security is NOT used and NOT needed.**

**Clarification:**
- **Spring Security 6.x** (`spring-boot-starter-security`) = the standard security framework for Spring Boot. Handles authentication, authorization, password encoding, CSRF, CORS, filter chains. **This is what Keevo uses.**
- **Spring Cloud Security** = an add-on for OAuth2/OIDC SSO flows in distributed microservice architectures. NOT applicable to a standalone REST API + Flutter app.

**Mandatory Spring Security rules for this project:**

```java
// ✅ ALWAYS required for REST APIs (prevents HTML /login page redirect)
.formLogin(form -> form.disable())
.httpBasic(basic -> basic.disable())

// ✅ ALWAYS required for REST APIs (no browser sessions)
.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

// ✅ ALWAYS return JSON status for unauthenticated requests (not HTML redirect)
.exceptionHandling(ex -> ex
    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
)

// ✅ ALWAYS disable CSRF for stateless REST (CSRF only matters for browser sessions)
.csrf(csrf -> csrf.disable())
```

**Swagger/OpenAPI paths MUST be in the public list:**
```java
private static final String[] PUBLIC_PATHS = {
    "/actuator/health",
    "/api/v1/auth/**",
    "/v3/api-docs/**",
    "/swagger-ui/**",
    "/swagger-ui.html"
};
```

---

### 🔴 Springdoc OpenAPI — Mandatory for Every Controller

> **Every REST controller MUST have OpenAPI annotations. Swagger UI is available at `/swagger-ui.html`.**

**Required annotations per controller:**
```java
@Tag(name = "Domain Name", description = "What this group of endpoints does")
@RestController
public class MyController {

    @Operation(summary = "Short action name", description = "Full explanation")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Success"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @SecurityRequirements  // Only on public endpoints — removes the Bearer lock icon
    @GetMapping("/resource")
    public ResponseEntity<Response> getResource() { ... }
}
```

**OpenAPI configuration** is in `shared/infrastructure/web/OpenApiConfig.java`.
JWT Bearer scheme is pre-configured — all endpoints show the lock icon by default.
Mark public endpoints explicitly with `@SecurityRequirements` to remove it.



### Naming Patterns

**Database (PostgreSQL):**
- Tables: `snake_case` **pluriel** → `products`, `stock_movements`
- Colonnes: `snake_case` → `unit_price`, `created_at`
- PKs: `id` (UUID v4)
- FKs: `{entity}_id` → `store_id`, `user_id`
- Index: `idx_{table}_{columns}` → `idx_products_name`
- Contraintes: `fk_{table}_{ref}`, `uq_{table}_{col}`
- Schéma tenant: `kv_{6digits}` → `kv_000042`

**API REST:**
- Endpoints: **pluriel**, `kebab-case` → `/api/v1/products`, `/api/v1/stock-movements`
- Path params: `{camelCase}` → `/api/v1/products/{productId}`
- Query params: `camelCase` → `?storeId=xxx&sortBy=name`
- Headers custom: `X-Keevo-{Name}` → `X-Keevo-Tenant-Id`
- JSON fields: **camelCase** → `{ "unitPrice": 5000, "createdAt": "..." }`

**Code Java:**
- Classes: `PascalCase` → `ProductService`, `AuthUseCase`
- Interfaces (ports): nom du contrat → `UserRepository`, `WhatsAppPort`
- Implémentations: `{Provider}{Name}` → `JpaUserRepository`, `WassenderWhatsAppAdapter`
- Méthodes: `camelCase` verbe → `findByStoreId()`, `createSale()`
- DTOs: `{Action}{Entity}Request/Response` → `CreateProductRequest`, `LoginResponse`
- Domain exceptions: `{Entity}{Problem}Exception` → `ProductNotFoundException`
- Constants: `SCREAMING_SNAKE_CASE` → `MAX_PRODUCTS_FREE_PLAN`
- Packages: domaine.module.layer → `com.keevo.catalog.product.domain.model`

**Code Dart (Flutter):**
- Fichiers: `snake_case` → `sale_repository.dart`, `pos_page.dart`
- Classes: `PascalCase` → `SaleRepository`, `PosPage`
- Variables/fonctions: `camelCase` → `unitPrice`, `recordSale()`
- Providers: `{name}Provider` (via `@riverpod`) → `productsProvider`
- Drift tables: `PascalCase` pluriel → `Products`, `StockMovements`
- Feature folders: `snake_case` → `features/pos/`, `features/inventory/`

### Format Patterns

**API Response — Succès:**
```json
{ "data": { ... }, "meta": { "page": 1, "total": 42 } }
```

**API Response — Erreur:**
```json
{ "error": "Product not found", "code": "NOT_FOUND", "domainCode": "PRODUCT_NOT_FOUND", "details": { "productId": "xxx" }, "timestamp": "2026-02-28T21:00:00Z" }
```

**Data formats:**
- Dates JSON: ISO 8601 UTC → `"2026-02-28T21:00:00Z"`
- Dates affichage: Locale `fr` → `"28 févr. 2026"`
- Montants: Entier XAF (pas de décimales) → `5000`
- IDs: UUID v4
- Booléens: `true`/`false` (jamais `1`/`0`)
- Null: JSON `null` — champ omis si optionnel absent

### Communication Patterns

**Domain Events (Backend):**
- Nommage: `{Entity}{PastTense}Event` → `ProductCreatedEvent`, `SaleCompletedEvent`
- Payload: `{ entityId, tenantId, userId, occurredAt, data }`
- Listener: `{Domain}EventListener` → `AuditEventListener`

**Domain Event Registry — Canonical Locations:**

Events are placed in the domain that **owns the concept**, even if other domains publish them. Before creating a new event, check this registry.

| Event | Package | Created in | Audit Action | Audit EntityType | Notes |
|---|---|---|---|---|---|
| `UserRegisteredEvent` | `identity.auth.domain.model` | Story 1.2 | `USER_REGISTERED` | `User` | PUBLIC endpoint → listener manages TenantContext |
| `UserAuthenticatedEvent` | `identity.auth.domain.model` | Story 1.3 | `USER_AUTHENTICATED` | `User` | PUBLIC endpoint → listener manages TenantContext |
| `OnboardingCompletedEvent` | `identity.onboarding.domain.model` | Story 1.4 | `ONBOARDING_COMPLETED` | `Tenant` | Authenticated |
| `ProductCreatedEvent` | `catalog.product.domain.event` | Story 2.1 | `PRODUCT_CREATED` | `Product` | |
| `ProductUpdatedEvent` | `catalog.product.domain.event` | Story 2.1 | `PRODUCT_UPDATED` | `Product` | |
| `ProductArchivedEvent` | `catalog.product.domain.event` | Story 2.1 | `PRODUCT_ARCHIVED` | `Product` | |
| `SalePriceOverriddenEvent` | `catalog.product.domain.event` | Story 2.2 | `PRICE_OVERRIDDEN` | `PriceOverride` | Published by `RecordSaleService` (commerce domain) — lives in catalog because it's a pricing concept |
| `StockAdjustedEvent` | `catalog.stock.domain.event` | Story 2.3 | `STOCK_ADJUSTED` | `StockMovement` | |
| `StockThresholdBreachedEvent` | `catalog.stock.domain.event` | Story 2.3 | `STOCK_THRESHOLD_BREACHED` | `StockLevel` | |
| `ClientCreatedEvent` | `catalog.contact.domain.event` | Story 2.5 | `CLIENT_CREATED` | `Client` | |
| `ClientArchivedEvent` | `catalog.contact.domain.event` | Story 2.5 | `CLIENT_ARCHIVED` | `Client` | |
| `SupplierCreatedEvent` | `catalog.contact.domain.event` | Story 2.5 | `SUPPLIER_CREATED` | `Supplier` | |
| `SupplierArchivedEvent` | `catalog.contact.domain.event` | Story 2.5 | `SUPPLIER_ARCHIVED` | `Supplier` | |
| `StoreCreatedEvent` | `store.store.domain.event` | Story 3.1 | `STORE_CREATED` | `Store` | |
| `StoreUpdatedEvent` | `store.store.domain.event` | Story 3.1 | `STORE_UPDATED` | `Store` | |
| `StoreDeactivatedEvent` | `store.store.domain.event` | Story 3.1 | `STORE_DEACTIVATED` | `Store` | |
| `SaleCompletedEvent` | `commerce.sale.domain.model` | Story 4.1 | `SALE_COMPLETED` | `Sale` | +`discountAmount` added in Story 4.2 |

> **Rule**: Never create a duplicate event in another package. If an event already exists, extend it. Use this registry to check before creating any `*Event.java`.

**Riverpod State (Flutter):**
- `@riverpod` annotation for all providers
- State immutable (Freezed ou Dart 3 records)
- `AsyncValue` for all async operations
- Naming: `{noun}Provider`, `{noun}Notifier`

### Process Patterns

**Error Handling:**
- Domain: `throw new ProductNotFoundException("PRODUCT_NOT_FOUND")`
- Controller: **centralised in `GlobalExceptionHandler` (`@RestControllerAdvice`) only** — all `DomainException` → HTTP status mapping lives there
- ⚠️ **Anti-pattern** (discovered via E2E in Story 1-4): never use a local `@ExceptionHandler` in a `@RestController` for `DomainException`. `throw ex` inside an `@ExceptionHandler` **cannot delegate to `@RestControllerAdvice`** — Spring MVC converts it to HTTP 500. Unit tests cannot detect this because they mock the use case and never traverse the real Spring exception handler resolution order. Only a full HTTP round-trip E2E test exposes it.
- Flutter data: `AsyncValue.error()`
- Flutter UI: SnackBar via `ref.listen` sur les erreurs

**Sync Patterns:**
- Queue locale: Drift table `sync_queue`
- Push: `POST /api/v1/sync/push` (batch)
- Pull: `GET /api/v1/sync/pull?since={timestamp}`
- Conflicts stock: delta-based (somme des deltas)
- Conflicts autres: last-write-wins par timestamp
- Retry: backoff exponentiel (2s, 4s, 8s, 16s, max 5 min)

**Write-Through Pattern (Flutter → Backend) — CRITICAL for all transactional features:**

Every transactional write (sales, stock movements, transfers) follows this exact pipeline:

```
┌─────────────────────────────────────────────────────────────────────┐
│ 1. LocalDataSource.insertAll() — single Drift transaction:        │
│    ├─ Insert main record (e.g. sales)                             │
│    ├─ Insert child records (e.g. sale_items)                      │
│    ├─ Update stock_levels                                         │
│    ├─ Insert stock_movements                                      │
│    └─ Enqueue sync_queue entry with _buildPayload() JSON          │
│                                                                   │
│ 2. RemoteDataSource.push() — unawaited background call:           │
│    ├─ POST to backend REST API                                    │
│    ├─ On success (201): mark synced + remove sync_queue entry     │
│    └─ On failure: log + leave in sync_queue for later retry       │
│                                                                   │
│ 3. Epic 5 SyncService — handles offline retry:                    │
│    └─ Reads pending sync_queue entries + replays _buildPayload()  │
└─────────────────────────────────────────────────────────────────────┘
```

**⚠️ INVARIANT**: `_buildPayload()` (in each `LocalDataSource`) and `RemoteDataSource.push()` MUST produce **identical JSON structures** matching the backend DTO contract. When adding fields to a backend DTO (e.g. `discountAmount` on `RecordSaleRequestDto`), you MUST update THREE places in Flutter:
1. `LocalDataSource.insertAll()` → Drift companion fields
2. `LocalDataSource._buildPayload()` → sync_queue JSON (for offline replay)
3. `RemoteDataSource.push()` → Dio POST body (for immediate push)

Failing to update `_buildPayload()` causes offline-created records to sync without the new fields — silent data loss.

**Local Data Retention Policy (Drift — device storage):**

Two categories of local data with distinct retention rules:

| Category | Tables | Retention | Rationale |
|---|---|---|---|
| **Static/Reference** | `products`, `product_variants`, `categories`, `stock_levels`, `store_settings`, `auth_cache` | Permanent (never purged) | Critical for POS offline — small volume (<5MB) |
| **Transactional** | `sales`, `sale_items`, `stock_movements` | **30 days** rolling window | Grows daily — purge prevents exceeding 100MB device limit |
| **Sync queue** | `sync_queue` (SYNCED items) | 7 days after confirmed sync | Only confirmed entries — PENDING items never purged |

- Data older than 30 days is available **online-only** via backend API (never lost — server is the permanent store)
- A daily background job in Flutter executes the purge on app startup:
  ```dart
  // Triggered once/day via WorkManager (Android) / BGTaskScheduler (iOS)
  final cutoff = DateTime.now().subtract(const Duration(days: 30));
  await db.transaction(() async {
    await db.sales.deleteWhere((s) => s.syncedAt.isSmallerThan(cutoff));
    await db.saleItems.deleteWhere((i) => i.syncedAt.isSmallerThan(cutoff));
    await db.stockMovements.deleteWhere((m) => m.createdAt.isSmallerThan(cutoff));
  });
  ```
- Reports covering periods > 30 days are always fetched from the server (online-only — expected UX)
- **Zero data loss guarantee**: purge only runs on rows where `synced_at IS NOT NULL` (confirmed push)

### Enforcement — All AI Agents MUST:

1. ✅ Write failing test FIRST (TDD Red), then code, then refactor
2. ✅ Follow hexagonal architecture: domain → port → adapter
3. ✅ Create interface (port) BEFORE any implementation
4. ✅ Follow naming conventions without exception
5. ✅ Use DTOs for API, never expose domain models
6. ✅ Include `domainCode` in every exception
7. ✅ Log via domain events, never `System.out.println` / `print()`
8. ✅ Pass `ActorId` explicitly in every Command/Query — never via `SecurityContextHolder` inside a use case
9. ✅ Keep `port/in/` interfaces free of ANY HTTP/framework type — pure Java Records only
10. ✅ Create `adapter/in/mcp/` placeholder folder (empty `.gitkeep`) for every new module
11. ✅ Purge transactional Drift tables (sales, sale_items, stock_movements) only when `synced_at IS NOT NULL` — PENDING rows are never deleted

### Anti-Patterns — FORBIDDEN:

- ❌ Production code without a failing test written first
- ❌ Business logic in a Controller/Adapter
- ❌ Framework imports in `domain/` layer
- ❌ Direct DB access without port/repository
- ❌ Shared mutable state
- ❌ `catch(Exception e)` without re-throw or logging
- ❌ Hardcoded tenant ID, store ID, or passwords
- ❌ Implementing a feature without GoF design pattern analysis first
- ❌ `SecurityContextHolder.getContext()` inside a use case or domain service — ActorId must be injected
- ❌ `HttpServletRequest` / `ResponseEntity` in any port `in/` interface or application service
- ❌ Creating a REST controller without creating the matching `adapter/in/mcp/` placeholder folder
- ❌ Purging local Drift data without checking `synced_at IS NOT NULL` — risk of permanent data loss
- ❌ Purging static/reference tables (products, stock_levels, categories) — these must always remain local

## Project Structure & Boundaries

### Repository Structure

```
keevo/
├── README.md
├── .gitignore
├── .github/workflows/
│   ├── backend-ci.yml
│   └── flutter-ci.yml
├── docker-compose.yml                # PostgreSQL + dev services
├── backend/                           # Spring Boot API
└── app/                               # Flutter (mobile + desktop)
```

### Backend (Spring Boot — Hexagonal / Domain-Driven)

```
backend/
├── pom.xml
├── .env.example
├── Dockerfile
├── src/main/java/com/keevo/
│   ├── KeevoApplication.java
│   ├── shared/
│   │   ├── infrastructure/
│   │   │   ├── security/              # JwtTokenProvider, JwtAuthFilter, SecurityConfig
│   │   │   ├── persistence/           # JpaBaseEntity, TenantContext, MultiTenantConnectionProvider,
│   │   │   │                          # TenantSchemaProvisioner (programmatic DDL — no SQL files)
│   │   │   └── web/                   # GlobalExceptionHandler, ApiResponseWrapper
│   │   ├── domain/
│   │   │   ├── exception/             # DomainException, ErrorCode enum
│   │   │   └── model/                 # BaseEntity, Money (XAF value object)
│   │   └── application/port/          # AuditPort
│   ├── identity/                      # DOMAIN: Identity & Access
│   │   └── auth/                      # MODULE — full hexagonal structure:
│   │       ├── domain/
│   │       │   ├── model/             # User, Tenant, RefreshToken, Role, PlanType, ...
│   │       │   └── port/
│   │       │       ├── in/            # RegisterUserUseCase, AuthenticateUserUseCase, ...
│   │       │       └── out/           # UserRepository, TenantRepository, RefreshTokenRepository,
│   │       │                          # TenantSchemaPort  (pure Java interfaces — no Spring/JPA)
│   │       ├── application/
│   │       │   └── service/           # RegistrationService, AuthenticationService,
│   │       │                          # RefreshTokenService, TenantFactory, TenantCodeGenerator
│   │       └── adapter/
│   │           ├── in/rest/           # AuthController + dto/ (LoginRequest, RegistrationRequest, ...)
│   │           └── out/persistence/   # ← THE PERSISTENCE PATTERN (see section below)
│   │               ├── entity/        # UserJpaEntity, TenantJpaEntity, RefreshTokenJpaEntity
│   │               ├── jpa/           # UserSpringRepository, TenantSpringRepository,
│   │               │                  # RefreshTokenSpringRepository  (extends JpaRepository)
│   │               └── impl/          # UserRepositoryAdapter, TenantRepositoryAdapter,
│   │                                  # RefreshTokenRepositoryAdapter, TenantSchemaAdapter
│   ├── catalog/                       # DOMAIN: Product Catalog
│   │   ├── product/                   # MODULE: Products
│   │   ├── category/                  # MODULE: Categories
│   │   └── variant/                   # MODULE: Variants & attributes
│   ├── commerce/                      # DOMAIN: Sales & POS
│   │   ├── pos/                       # MODULE: Point of Sale
│   │   ├── sale/                      # MODULE: Sales & receipts
│   │   └── payment/                   # MODULE: Payments & cash
│   ├── inventory/                     # DOMAIN: Stock Management
│   │   ├── stock/                     # MODULE: Stock tracking per store
│   │   │                              #   ⚠️  Inter-store transfers implemented HERE (catalog/stock/)
│   │   │                              #   not in inventory/transfer/ — Story 3.3 reuses StockOperationService
│   │   │                              #   Tables: stock_transfers (IN_TRANSIT→COMPLETED two-step),
│   │   │                              #           stock_movements (TRANSFER_OUT step1, TRANSFER_IN step2)
│   │   │                              #   API: POST /stock/transfers (step1) · POST /stock/transfers/{id}/complete (step2)
│   │   ├── transfer/                  # MODULE: Inter-store transfers (placeholder — logic in catalog/stock/)
│   │   └── counting/                  # MODULE: Physical inventory counting
│   ├── store/                         # DOMAIN: Multi-Store
│   │   ├── store/                     # MODULE: Store CRUD & settings
│   │   └── warehouse/                 # MODULE: Warehouse management
│   ├── reporting/                     # DOMAIN: Reports & Analytics
│   │   ├── dashboard/                 # MODULE: Real-time dashboard
│   │   └── report/                    # MODULE: End-of-day, weekly, custom
│   ├── messaging/                     # DOMAIN: Communication
│   │   ├── whatsapp/                  # MODULE: WhatsApp integration
│   │   └── notification/              # MODULE: Alerts & push notifications
│   ├── sync/                          # DOMAIN: Synchronization
│   │   └── sync/                      # MODULE: Push/Pull REST sync
│   ├── subscription/                  # DOMAIN: Billing
│   │   └── plan/                      # MODULE: Plans & limits enforcement
│   └── admin/                         # DOMAIN: Super Admin
│       ├── tenant/                    # MODULE: Tenant management
│       └── platform/                  # MODULE: Platform analytics & health
├── src/main/resources/
│   ├── application.yml                # ddl-auto=update, flyway.enabled=false
│   ├── application-dev.yml
│   ├── application-prod.yml
│   └── keys/                          # RSA keypair for JWT
└── src/test/java/com/keevo/          # Mirrors src — TDD tests per module
```

### Frontend (Flutter — Feature-First + Clean Architecture)

```
app/
├── pubspec.yaml
├── analysis_options.yaml
├── build.yaml                         # build_runner (Drift, Riverpod, Freezed)
├── lib/
│   ├── main.dart
│   ├── core/
│   │   ├── di/providers.dart
│   │   ├── network/                   # api_client, interceptors, api_exception
│   │   ├── storage/                   # Drift database, secure_storage
│   │   ├── sync/                      # SyncService interface, RestSyncService, sync_queue
│   │   ├── auth/                      # auth_state, auth_provider
│   │   ├── theme/                     # Material 3 Indigo Sky tokens
│   │   └── router/                    # go_router config
│   └── features/
│       ├── auth/                      # domain/ + data/ + presentation/
│       ├── pos/                       # domain/ + data/ + presentation/
│       ├── products/
│       ├── inventory/
│       ├── stores/
│       ├── reports/
│       ├── settings/
│       └── onboarding/
├── test/                              # Mirrors features — TDD unit + widget tests
└── integration_test/                  # E2E flow tests
```

### Requirements → Structure Mapping

| FR Domain | Backend Domain/Module | Flutter Feature |
|---|---|---|
| FR1-7: Auth & Onboarding | `identity/auth/`, `identity/user/` | `auth/`, `onboarding/` |
| FR8-15: Tenant Provisioning | `admin/tenant/` | — (backend) |
| FR16-20: Subscriptions | `subscription/plan/` | `settings/` |
| FR21-29: Products | `catalog/product/`, `category/`, `variant/` | `products/` |
| FR30-36: Multi-Store | `store/store/`, `store/warehouse/` | `stores/` |
| FR37-44: POS | `commerce/pos/`, `sale/`, `payment/` | `pos/` |
| FR45-49: Inventory | `catalog/stock/` (stock tracking + inter-store transfers — Story 3.3) | `inventory/` |
> ⚠️ Inter-store transfers (FR32, Story 3.3) are in `catalog/stock/` (not `inventory/transfer/`) — they reuse `StockOperationService`. Two-step flow: `ExecuteTransferService` (TRANSFER_OUT, → IN_TRANSIT) + `CompleteTransferService` (TRANSFER_IN, → COMPLETED).
| FR50-56: Reports | `reporting/dashboard/`, `report/` | `reports/` |
| FR57-64: WhatsApp & Alerts | `messaging/whatsapp/`, `notification/` | — (push) |
| FR65-68: User Management | `identity/user/` | `settings/` |
| FR69-76: Offline Sync | `sync/sync/` | `core/sync/` |
| FR77-83: Super Admin | `admin/` | — (admin web futur) |
| FR84-93: Security & Audit | `shared/` (transversal) | Transversal |

### Architectural Boundaries

**API Boundaries:**
- All communication Flutter ↔ Backend via REST `/api/v1/`
- JWT in `Authorization: Bearer` header on every request
- Tenant resolved from JWT, never from URL or body
- Super Admin endpoints: `/api/v1/admin/` (separate RBAC)

**Data Boundaries:**
- Flutter → Drift (SQLite) for local data
- Backend → JPA/Hibernate → PostgreSQL `kv_xxxxxx` schema
- Sync crosses this boundary via `POST /sync/push` and `GET /sync/pull`
- No direct DB access from Flutter — always through API + local cache

**Domain Boundaries (microservice-ready):**
- Each domain (`identity`, `catalog`, `commerce`, etc.) communicates via its ports
- No direct import between domains — only through shared ports or domain events
- Future extraction: each domain becomes an independent service with its own DB schema

### GoF Design Pattern Analysis — Pre-Implementation Checklist

> See the full mandatory analysis framework in [Implementation Patterns — GoF section](#-gof-design-pattern-analysis--mandatory-before-every-feature) above.

**Common Keevo patterns quick reference:**

| Problem | GoF Pattern | Example |
|---|---|---|
| Multiple sync strategies | **Strategy** | `SyncService` → `RestSyncService`, `PowerSyncService` |
| Multiple WhatsApp providers | **Strategy + Adapter** | `WhatsAppPort` → `WassenderAdapter` |
| Complex object creation | **Factory** | `TenantFactory.create()` (schema + roles + defaults) |
| Audit on every mutation | **Observer** | `DomainEvent` → `AuditEventListener` |
| Access control decoration | **Decorator** | `@RequiresRole(OWNER)` wrapping use cases |
| Conflict resolution | **Strategy** | `ConflictResolver` → `DeltaStockResolver`, `LastWriteWinsResolver` |
| Report generation | **Template Method** | `AbstractReport` → `DailyReport`, `WeeklyReport` |
| Notification routing | **Chain of Responsibility** | WhatsApp → Push → SMS fallback |
| Building complex queries | **Builder** | `SyncPullQueryBuilder` |
| Singleton services | **Singleton** (via Spring/Riverpod DI) | `TenantContext`, `ApiClient` |

## MCP Migration Path

### Overview

The Keevo backend is designed to be **migrable to a Spring AI MCP Server** without rewriting any domain or application logic. The hexagonal architecture guarantees that adding MCP support is purely an **adapter concern** — a new `adapter/in/mcp/` folder per module, with zero changes to the domain, ports, or application services.

### Target Architecture (V2)

When MCP migration is triggered:
1. Add Spring AI MCP Server dependency to `pom.xml`
2. Create `{Domain}McpTools.java` per module in `adapter/in/mcp/`
3. Annotate use case calls with `@Tool(description = "...")`
4. Every Command/Query Record auto-serializes to JSON Schema for MCP tool parameters
5. Zero changes to `domain/`, `application/`, `adapter/in/rest/`, or `adapter/out/`

```
# Example: adapter/in/mcp/ProductMcpTools.java
@Component
public class ProductMcpTools {
    private final CreateProductUseCase createProduct;  // same port as REST
    private final GetProductUseCase getProduct;

    @Tool(description = "Create a new product in the tenant catalogue")
    public ProductResponse createProduct(CreateProductCommand command) {
        return createProduct.execute(command);  // identical to REST controller
    }
}
```

### Why This Works Without Rework

| Architectural Decision | MCP Benefit |
|---|---|
| Ports `in/` = pure Java `interface` | → becomes MCP Tool contract directly |
| Commands/Queries = Java `record` | → JSON Schema generated automatically by Spring AI |
| `ActorId` in Command (not HTTP context) | → MCP context can inject ActorId from LLM session |
| `application/service/` has no HTTP dependency | → called by MCP adapter exactly like REST adapter |
| DTOs separate from domain models | → REST and MCP can have different serialization shapes |

### Technology Note

- **Spring AI MCP Server** (available Spring Boot 3.3+) — transforms `@Tool`-annotated beans into MCP server tools
- **Protocol**: MCP over stdio or SSE (configurable)
- **Auth**: MCP has its own authorization layer — `ActorId` resolved from MCP session context instead of JWT
- **No timeline constraint**: This is a V2 concern. The placeholder folders and port purity rules are the only MVP obligations.

---

## Architecture Validation Results

### Coherence Validation ✅

- ✅ Flutter 3.41 + Riverpod 3.0 + Drift + go_router — compatible stack
- ✅ Spring Boot 3.5.x + Java 21 + JPA + Flyway + PostgreSQL — mature enterprise stack
- ✅ JWT RS256 — verifiable offline on Flutter via public key
- ✅ REST sync custom — no conflict with hexagonal architecture
- ✅ Naming conventions consistent: DB (snake_case), API (camelCase), Java (PascalCase), Dart (camelCase)
- ✅ Feature-first Flutter ↔ Domain-first Backend — coherent domain nomenclature
- ✅ No contradictions detected

### Requirements Coverage ✅

**Functional Requirements:** 93/93 FRs covered across 14 domains mapped to backend modules and Flutter features.

**Non-Functional Requirements:** 34/34 NFRs addressed:
- Performance (<2s local, <60s sync, 30 FPS) → Drift offline-first + Flutter native
- Security (tenant isolation, bcrypt ≥12, JWT 24h, TLS) → Schema-per-tenant + Spring Security
- Scalability (100→1000+ tenants) → Domain-driven + microservice-ready boundaries
- Reliability (99.5% uptime, 0% client offline) → AWS RDS + Drift local
- Accessibility (≤3 taps, <10min onboarding, fr) → UX spec + Material 3 responsive

### Implementation Readiness ✅

- ✅ Complete technology stack with verified versions
- ✅ 7 foundational non-negotiable principles documented
- ✅ Full testing stack defined (JUnit 5, Mockito, Testcontainers, mocktail, riverpod_test)
- ✅ GoF pre-implementation checklist with 10 common patterns identified
- ✅ Complete project structure with file-level detail
- ✅ Anti-patterns documented and forbidden

### Gap Analysis

**Critical gaps:** None

**Non-blocking gaps (addressed during implementation):**
- Sync REST payload format details → defined per story
- Business monitoring metrics → defined during reporting feature
- Git conventions (commits, branching) → can be defined in project-context.md

### Architecture Completeness Checklist

- [x] Project context analyzed (93 FRs, 34 NFRs, 7 cross-cutting concerns)
- [x] Scale & complexity assessed (High, full-stack cross-platform)
- [x] Starter templates selected (Flutter create, Spring Initializr)
- [x] 7 foundational principles (Hexagonal, Clean Code, SOLID, DDD, Isolation, Interfaces, GoF)
- [x] All technology decisions with versions
- [x] Backend domain decomposition (10 domains, 20+ modules)
- [x] Frontend feature structure (8 features)
- [x] Full TDD with testing stacks
- [x] Naming/format/communication/process patterns
- [x] GoF pre-implementation checklist
- [x] Complete project structure
- [x] FR → Structure mapping (93/93)
- [x] NFR → Architecture mapping (34/34)
- [x] Architectural boundaries defined
- [x] Anti-patterns documented

### Architecture Readiness Assessment

**Overall Status:** 🟢 READY FOR IMPLEMENTATION

**Confidence Level:** HIGH

**Key Strengths:**
- Hexagonal architecture ensures clean separation and future microservice extraction
- Full TDD guarantees feature correctness
- GoF patterns enforce extensibility without modification
- Schema-per-tenant provides absolute data isolation
- Custom sync abstraction enables future technology swap

**Implementation Priority:**
1. Flutter project init + core architecture scaffold
2. Spring Boot init + hexagonal scaffold + multi-tenant + auth
3. Sync REST service
4. Core features domain by domain

---

## Backend Persistence Pattern — Implementation Reference

> **This is the mandatory pattern for every entity in the project.**
> Tables are created automatically from JPA entity definitions. No SQL migration files.

### Core Principles

| Principle | Rule |
|-----------|------|
| **No manual SQL** | `ddl-auto=update` creates/alters public schema tables from JPA entities. No `.sql` files, no Flyway. |
| **Clean separation** | Domain model, JPA entity, Spring Data interface, and adapter are 4 distinct classes in 4 distinct sub-packages. |
| **Domain isolation** | `domain/model/` and `domain/port/` are pure Java — zero Spring or JPA imports. |
| **Infrastructure isolation** | `@Entity`, `@Repository`, `@Component` live exclusively in `adapter/out/persistence/`. |
| **Anti-corruption** | The adapter translates domain ↔ JPA entity. The domain never sees a JPA entity. |

### The Four Sub-Packages

```
adapter/out/persistence/
├── entity/     ← @Entity classes — Hibernate reads these to auto-create/alter tables
├── jpa/        ← Spring Data JpaRepository interfaces — CRUD + custom queries
└── impl/       ← @Component adapters implementing domain ports — translate domain ↔ entity
```

### Layer 1 — Domain Port (`domain/port/out/`)

Declares **what** the domain needs, without knowing **how** it is stored.

```java
// Pure Java interface — no Spring, no JPA
public interface UserRepository {
    User save(User user);
    Optional<User> findByPhoneNumber(String phoneNumber);
    Optional<User> findById(UUID id);
    boolean existsByPhoneNumber(String phoneNumber);
}
```

### Layer 2 — JPA Entity (`adapter/out/persistence/entity/`)

Maps the domain concept to a DB table. **Hibernate auto-creates the table from this class.**

```java
@Entity
@Table(name = "users")
public class UserJpaEntity extends JpaBaseEntity {   // JpaBaseEntity handles id + createdAt + updatedAt

    @Column(name = "phone_number", unique = true, nullable = false, length = 20)
    private String phoneNumber;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    // ... other columns

    protected UserJpaEntity() {}                    // required by JPA
    public UserJpaEntity(UUID id, ...) { if (id != null) setId(id); ... }  // domain UUID
    // getters only — no setters
}
```

**Adding a column** = add a `@Column` field → Hibernate issues `ALTER TABLE` on next start.

### Layer 3 — Spring Data Interface (`adapter/out/persistence/jpa/`)

```java
@Repository
public interface UserSpringRepository extends JpaRepository<UserJpaEntity, UUID> {
    Optional<UserJpaEntity> findByPhoneNumber(String phoneNumber);
    boolean existsByPhoneNumber(String phoneNumber);
}
```

Consumed **only** by the adapter. Never injected into application or domain layers.

### Layer 4 — Adapter (`adapter/out/persistence/impl/`)

Implements the domain port. Contains only mapping logic — no business logic.

```java
@Component
public class UserRepositoryAdapter implements UserRepository {

    private final UserSpringRepository springRepository;

    public UserRepositoryAdapter(UserSpringRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    public User save(User user) {
        return toDomain(springRepository.save(toEntity(user)));
    }

    @Override
    public Optional<User> findByPhoneNumber(String phoneNumber) {
        return springRepository.findByPhoneNumber(phoneNumber).map(this::toDomain);
    }

    // ── Mapping only — no business logic ──────────────────────────────────────
    private UserJpaEntity toEntity(User u) { ... }
    private User toDomain(UserJpaEntity e) { ... }
}
```

### JpaBaseEntity — Why It Exists

All entities extend `shared/infrastructure/persistence/JpaBaseEntity`, a `@MappedSuperclass` that centralises:

- `@Id UUID id` — domain UUID assigned at construction time (never `@GeneratedValue`)
- `@CreationTimestamp Instant createdAt`
- `@UpdateTimestamp Instant updatedAt`

**⚠️ `Persistable<UUID>` — REMOVED (do not re-add)**

An earlier version implemented `Persistable<UUID>` with an `@Transient isNew` flag. This caused a production bug:
`isNew` was not reliably cleared to `false` after load, so `SimpleJpaRepository.save()` kept calling `persist()` on existing entities → `EntityExistsException` → HTTP 500 on login.

**Current approach**: `JpaBaseEntity` does NOT implement `Persistable`. `SimpleJpaRepository.save()` falls back to the default strategy: checks if `id == null` (new) → `persist()`, or `id != null` but entity already in DB → `merge()`. Both paths work correctly when the domain UUID is always set at construction time.

### Table Creation Strategy

| Schema | Strategy | Config |
|--------|------------|--------|
| **Public** (`users`, `tenants`, `refresh_tokens`) | `ddl-auto=update` — Hibernate auto-creates/alters at startup | `spring.jpa.hibernate.ddl-auto: update` |
| **Tenant** (`kv_xxxxxx` — per-tenant, at registration) | `TenantSchemaProvisioner` — programmatic DDL via JDBC called by `TenantFactory` during registration | Creates tables + seeds roles/store |
| **Tenant** (per-tenant, at every login) | `TenantSchemaSyncService` — diffs `information_schema` between `public` and tenant schema; creates missing tables/columns | Called by `JwtAuthFilter` on every authenticated request (no-op after first sync per JVM thanks to `ConcurrentHashMap` cache) |

`TenantSchemaProvisioner` creates: `users`, `subscriptions`, `roles`, `user_roles`, `stores`, `categories`, `tenant_preferences` + seeds OWNER/EMPLOYEE roles, Free subscription, placeholder store.

**`TenantSchemaSyncService` design:**
- **Source of truth**: `public` schema (kept current by `ddl-auto=update` at startup)
- **Missing table**: `CREATE TABLE IF NOT EXISTS "kv_xxx"."t" (LIKE public."t" INCLUDING ALL)` — copies column defs, NOT NULL, CHECK constraints, defaults (gen_random_uuid(), NOW()), indexes, storage settings. Foreign keys intentionally excluded.
- **Missing column**: `ALTER TABLE "kv_xxx"."t" ADD COLUMN IF NOT EXISTS <col_def>` reconstructed from `information_schema.columns`
- **Cache**: `ConcurrentHashMap<String, Boolean>` — first sync per schema costs 1 transaction; subsequent requests are ~nanosecond hashmap lookup
- **Excluded tables**: `GLOBAL_ONLY_TABLES = {"tenants"}` — lives only in `public`
- **Integration point**: `JwtAuthFilter.doFilterInternal()` — immediately after `TenantContext.setCurrentTenant()`, before JPA session opens
- **Why raw JDBC (not EntityManager)**: Sync runs in the security filter before the JPA session, uses fully-qualified `"schema"."table"` names — no `search_path` needed. Intentional.
- **Error handling**: Non-blocking — `SQLException` caught, logged, swallowed; request proceeds with existing schema

**Critical rule — Raw SQL and multi-tenant schema routing:**

`SchemaAwareMultiTenantConnectionProvider` is a **Hibernate SPI**. It intercepts connections that **Hibernate itself** requests — it has zero effect on connections acquired via any other path.

| SQL access method | Routed by `SchemaAwareMultiTenantConnectionProvider`? | Safe for tenant tables? |
|---|---|---|
| JPA `Repository` / `EntityManager` (Hibernate-managed) | ✅ Yes — Hibernate calls the SPI | ✅ Yes |
| `EntityManager.createNativeQuery()` inside `@Transactional` | ✅ Yes — shares the Hibernate-bound connection | ✅ Yes |
| `JdbcTemplate` | ❌ No — goes directly to HikariCP, bypasses SPI | ❌ No |
| `DataSource.getConnection()` | ❌ No — bypasses SPI entirely | ❌ No |

**Rule 1 — Inside `@Transactional` methods:** never use `JdbcTemplate` or raw `DataSource.getConnection()`. `JpaTransactionManager` binds the active connection under the `EntityManagerFactory` key in `TransactionSynchronizationManager`. Raw JDBC bypasses this and gets a fresh pool connection without `search_path` set.

**Rule 2 — Outside `@Transactional` (e.g. lightweight count adapters):** `JdbcTemplate` is permitted **only if** every SQL statement uses a fully-qualified schema name built from `TenantContext.getCurrentTenant()`:
```java
String schema = TenantContext.getCurrentTenant();  // e.g. "kv_abc123"
jdbcTemplate.queryForObject(
    "SELECT COUNT(*) FROM \"" + schema + "\".stores WHERE is_active = TRUE",
    Integer.class);
```
This is the same pattern used by `TenantSchemaSyncService`. Never use bare table names (`FROM stores`) with `JdbcTemplate` — PostgreSQL will look in `public` and the relation will not be found.

**Summary — the golden rule:** if the SQL touches a tenant-schema table, either use Hibernate (JPA repository / `EntityManager`) or qualify the table name with `TenantContext.getCurrentTenant()` as the schema prefix.

---

## Multi-Tenant Identity Model

> **Decision date:** 2026-03-07 — Correct course applied during Story 1.7 (sprint execution)

### The Problem: 1:1 User ↔ Tenant Coupling

The initial implementation (Stories 1.2 & 1.3) stored `tenant_id` directly on `public.users`. This creates a hard 1:1 coupling: one phone number → one tenant. This design fails for any scenario where a person belongs to more than one Keevo tenant simultaneously.

**Real-world examples that require N:N:**
- Simon (OWNER of `KV-ABC123`) is invited as an EMPLOYEE by a colleague in `KV-DEF456`
- A freelance accountant manages books for three different merchants — three tenants
- An employee leaves one shop and joins another. Their old record must remain; only membership changes

**Critical distinction — multi-boutiques vs multi-tenant:**

| Concept | Scope | Story | Design |
|---|---|---|---|
| **Multi-boutiques (Epic 3)** | Multiple stores within **one tenant** | 3-1 to 3-4 | `kv_xxx.stores` table — pure intra-tenant concern. ZERO impact on user identity model. |
| **Multi-tenant membership (Story 1.7)** | One person belonging to **multiple tenants** | 1.7 | `public.user_tenant_memberships` — global N:N relation |

These are **orthogonal problems**. Epic 3 is unrelated to Story 1.7.

---

### Database Schema — Global Identity Layer (`public`)

```
public.users
    id              UUID PK
    phone_number    VARCHAR(20) UNIQUE NOT NULL   ← global identifier
    password_hash   VARCHAR(255) NOT NULL
    is_active       BOOLEAN DEFAULT TRUE
    failed_attempts INT DEFAULT 0
    locked_until    TIMESTAMPTZ
    created_at      TIMESTAMPTZ
    -- NOTE: tenant_id column still physically exists (dangling from Story 1.2).
    -- It is NOT mapped by Hibernate — completely ignored. Will be removed in a
    -- future Flyway migration when proper migration strategy is adopted.

public.user_tenant_memberships
    id          UUID PK DEFAULT gen_random_uuid()
    user_id     UUID NOT NULL REFERENCES public.users(id)
    tenant_id   UUID NOT NULL REFERENCES public.tenants(id)
    role        VARCHAR(30) NOT NULL           ← 'OWNER', 'EMPLOYEE', 'SUPER_ADMIN'
    is_active   BOOLEAN DEFAULT TRUE
    created_at  TIMESTAMPTZ DEFAULT NOW()
    UNIQUE(user_id, tenant_id)
```

**Why the UNIQUE constraint matters:** A person can hold only one role per tenant. If Simon is OWNER of `KV-ABC123`, he cannot also be EMPLOYEE of `KV-ABC123`. He CAN be EMPLOYEE of `KV-DEF456` (different tenant).

**Valid membership combinations — all fully supported:**

| Scenario | `user_tenant_memberships` rows |
|---|---|
| Simon owns 1 clothing shop (3 stores inside) | `(simon, KV-ABC123, OWNER)` — Epic 3 handles the 3 stores *inside* that one tenant |
| Simon owns 2 completely separate businesses (clothing + electronics) | `(simon, KV-ABC123, OWNER)` + `(simon, KV-XYZ789, OWNER)` |
| Loïc is an employee in two different merchants' shops | `(loic, KV-ABC123, EMPLOYEE)` + `(loic, KV-DEF456, EMPLOYEE)` |
| Loïc owns his own shop AND works for Simon | `(loic, KV-GHI012, OWNER)` + `(loic, KV-ABC123, EMPLOYEE)` |

**Key rule:** the `UNIQUE(user_id, tenant_id)` constraint only prevents having two rows for the **same** (user, tenant) pair. A person can have as many rows as they have distinct tenant relationships — there is no upper bound enforced at the DB level.

---

### Tenant Schema Layer (`kv_xxxxxx`)

```
kv_xxx.user_roles
    id      UUID PK
    user_id UUID           ← same UUID as public.users.id (cross-schema FK by convention, not DB constraint)
    role_id UUID REFERENCES kv_xxx.roles(id)
```

`kv_xxx.users` — exists in every tenant schema (provisioned by `TenantSchemaProvisioner`) but is **never written to** for OWNER users. It is a placeholder for Epic 3 employee management where employees are invited into a tenant workspace and their profile is managed locally.

**Source of authority per concern:**

| Concern | Source |
|---|---|
| Authentication (password, lockout) | `public.users` |
| Which tenants a user can access | `public.user_tenant_memberships` |
| Role within a tenant at runtime | JWT claim `role` (derived from membership at login) |
| Local employee profile in a tenant | `kv_xxx.users` (Epic 3, not yet populated) |

---

### Authentication Flow — Two-Step Login (Approach B)

Chosen over three alternatives:

| Approach | Verdict | Why |
|---|---|---|
| A — `tenantCode` in login form | ❌ Rejected | Forces all users (even owners) to know their tenant code. UX degradation for the majority. |
| B — **Two-step login (chosen)** | ✅ Adopted | Step 1 is identical to old login for single-tenant users (auto-select). Step 2 is implicit. Zero UX change for 99% of V1 users. |
| C — Try all tenant schemas | ❌ Rejected | N+1 DB problem. Catastrophic at scale. |
| D — Separate employee endpoint | ❌ Rejected | Duplicates auth logic. Breaks uniform identity model. |

#### Step 1 — Credential Verification

```
POST /api/v1/auth/login
Body: { "phoneNumber": "+237...", "password": "..." }

Response 200:
{
  "loginToken": "eyJ...",          ← RS256 JWT, TTL: 5 min, scope: "login_pending"
  "memberships": [
    {
      "tenantCode":  "KV-ABC123",
      "tenantName":  "Boutique Simon",
      "role":        "OWNER",
      "schemaName":  "kv_abc123"
    }
  ]
}
```

- Credentials verified (password + lockout state)
- `loginToken` is a **short-lived RS256 JWT** with claim `"scope": "login_pending"`
- `loginToken` is **NOT usable** on protected API endpoints — `JwtAuthFilter` rejects scope `"login_pending"` with HTTP 401
- Memberships loaded via `JdbcTemplate` (raw SQL on `public.user_tenant_memberships JOIN public.tenants`) — Hibernate NOT used here because `TenantContext` is not yet set

#### Step 2 — Tenant Selection

```
POST /api/v1/auth/select-tenant
Body: { "loginToken": "eyJ...", "tenantCode": "KV-ABC123" }

Response 200:
{
  "accessToken":  "eyJ...",   ← RS256 JWT, TTL: 24h, scope: "access"
  "refreshToken": "...",
  "expiresIn":    86400,
  "userId":       "...",
  "tenantId":     "kv_abc123",
  "role":         "OWNER"
}
```

- `loginToken` validated: RS256 signature + expiry + `scope == "login_pending"`
- `tenantCode` resolved → `tenantId` → membership lookup → verify `is_active == true`
- Full `accessToken` generated (scope: `"access"`, or no scope claim — backward compatible)
- `RefreshToken` created and persisted in `public.refresh_tokens`
- `UserAuthenticatedEvent` published

#### Flutter UX Optimization (auto-select)

```
Flutter: AuthRepository.login()
  ┌─ Step 1: POST /auth/login ─────────────────────── always
  │
  ├─── memberships.length == 1 ──→ Step 2 auto-called → store tokens → navigate home
  │                                       (zero UX change — identical to old login)
  │
  └─── memberships.length > 1  ──→ TenantPickerScreen → user selects → Step 2 called
```

In V1, virtually all users (OWNER-only) have exactly 1 membership. The tenant picker is a progressive enhancement for future use (Epic 3 employee invitation scenario).

---

### JWT Scope Convention

| Token type | `scope` claim | TTL | Usage |
|---|---|---|---|
| Login token | `"login_pending"` | 5 minutes | Only valid for `POST /auth/select-tenant` |
| Access token | absent (or `"access"`) | 24 hours | All protected API endpoints |
| Refresh token | opaque (hashed) | 30 days | Only valid for `POST /auth/refresh` |

`JwtAuthFilter` rule: if `scope == "login_pending"` → reject with HTTP 401 `TOKEN_INVALID`. A login token must never grant API access.

---

### Admin (SUPER_ADMIN) Identity

```
public.users: { id: 00000000-..., phoneNumber: $ADMIN_PHONE, role: SUPER_ADMIN }
public.user_tenant_memberships: { userId: 00000000-..., tenantId: 00000000-..., role: SUPER_ADMIN }
public.tenants: { id: 00000000-..., code: KV-ADMIN, schemaName: "public" }
```

`AdminAccountInitializer` creates both the user record AND the membership on first boot. Idempotent — guarded by existence checks.

---

### Naming Conventions

| Layer | Pattern | Example |
|-------|---------|---------|
| Domain model | `{Aggregate}` | `User`, `Tenant` |
| Domain port (out) | `{Aggregate}Repository` | `UserRepository` |
| Domain port (in) | `{Action}UseCase` | `RegisterUserUseCase` |
| Use case command | `{Action}Command` | `RegisterUserCommand` |
| Application service | `{Feature}Service` | `RegistrationService` |
| JPA entity | `{Aggregate}JpaEntity` | `UserJpaEntity` |
| Spring Data interface | `{Aggregate}SpringRepository` | `UserSpringRepository` |
| Adapter | `{Aggregate}RepositoryAdapter` | `UserRepositoryAdapter` |

### Checklist — Adding a New Entity

- [ ] `domain/model/{Aggregate}.java` — pure Java, no annotations
- [ ] `domain/port/out/{Aggregate}Repository.java` — pure Java interface
- [ ] `adapter/out/persistence/entity/{Aggregate}JpaEntity.java` — extends `JpaBaseEntity`, `@Entity @Table`
- [ ] `adapter/out/persistence/jpa/{Aggregate}SpringRepository.java` — `extends JpaRepository<..., UUID>`
- [ ] `adapter/out/persistence/impl/{Aggregate}RepositoryAdapter.java` — `@Component implements {Aggregate}Repository`
- [ ] Inject the **domain port** (not the adapter) into the application service
- [ ] Start backend → Hibernate auto-creates the table

### Forbidden Anti-Patterns

| ❌ Forbidden | ✅ Correct |
|-------------|----------|
| Injecting `UserSpringRepository` into a service | Inject `UserRepository` (domain port) |
| Using `UserJpaEntity` in domain or application layer | Use `User` (domain model) |
| Putting `@Entity` on a domain model | Create a separate `UserJpaEntity` |
| Writing `.sql` files for public schema tables | Add `@Column` to the JPA entity |
| Using `@GeneratedValue` for UUID | Set UUID from domain via `setId()` in `JpaBaseEntity` |
| Business logic in `toEntity()` / `toDomain()` | Pure field translation only |
| Creating Flyway migrations for new columns | Add the `@Column` field; `ddl-auto=update` handles it |
| Implementing `Persistable<UUID>` in `JpaBaseEntity` | Do NOT add — causes `persist()` on existing entities → HTTP 500. Use default `merge()` strategy. |
