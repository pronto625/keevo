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
- **Subscriptions** (FR16-20): Freemium limits, suspension read-only, manual activation
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
│   ├── transfer/                    # MODULE: Inter-store transfers
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
- `adapter/in/rest/` — REST controllers + Request/Response DTOs *(MVP)*
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

### 🔴 FULL TDD — NON-NEGOTIABLE

**Every single feature MUST follow strict Test-Driven Development:**

1. **RED** — Write a failing test FIRST (unit, integration, or widget test)
2. **GREEN** — Write the minimum code to make the test pass
3. **REFACTOR** — Clean up while keeping tests green

**TDD rules:**
- NO production code without a failing test written first
- A feature is DONE only when ALL its tests pass
- Test coverage must be comprehensive — happy path + edge cases + error cases
- Tests are the specification — they document what the code does

**Backend (Spring Boot) testing stack:**

| Layer | Test Type | Tools |
|---|---|---|
| Domain (use cases) | Unit tests | JUnit 5 + Mockito |
| Ports (interfaces) | Contract tests | JUnit 5 |
| Adapters (REST) | Integration tests | `@WebMvcTest` + MockMvc |
| Adapters (DB) | Repository tests | `@DataJpaTest` + Testcontainers (PostgreSQL) |
| Multi-tenant | Isolation tests | Custom: verify no cross-tenant data leaks |
| End-to-end | API tests | `@SpringBootTest` + Testcontainers |

**Frontend (Flutter) testing stack:**

| Layer | Test Type | Tools |
|---|---|---|
| Domain (use cases) | Unit tests | `test` package + `mocktail` |
| Data (repositories) | Unit tests | `test` + mock datasources |
| Providers (Riverpod) | Provider tests | `riverpod_test` / `ProviderContainer.test` |
| Widgets | Widget tests | `flutter_test` + `WidgetTester` |
| Integration | Flow tests | `integration_test` package |

**TDD workflow per feature:**
```
1. Write domain model test → implement model
2. Write use case test (mock port) → implement use case
3. Write repository test → implement repository
4. Write controller/provider test → implement adapter
5. Write widget test → implement UI
6. ALL tests green → feature is DONE
```

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

**Riverpod State (Flutter):**
- `@riverpod` annotation for all providers
- State immutable (Freezed ou Dart 3 records)
- `AsyncValue` for all async operations
- Naming: `{noun}Provider`, `{noun}Notifier`

### Process Patterns

**Error Handling:**
- Domain: `throw new ProductNotFoundException("PRODUCT_NOT_FOUND")`
- Controller: `@ExceptionHandler` → map domainCode → HTTP status
- Flutter data: `AsyncValue.error()`
- Flutter UI: SnackBar via `ref.listen` sur les erreurs

**Sync Patterns:**
- Queue locale: Drift table `sync_queue`
- Push: `POST /api/v1/sync/push` (batch)
- Pull: `GET /api/v1/sync/pull?since={timestamp}`
- Conflicts stock: delta-based (somme des deltas)
- Conflicts autres: last-write-wins par timestamp
- Retry: backoff exponentiel (2s, 4s, 8s, 16s, max 5 min)

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
│   │   │   ├── persistence/           # TenantContext, TenantConnectionProvider, FlywayTenantMigration
│   │   │   ├── config/                # AppConfig
│   │   │   └── web/                   # GlobalExceptionHandler, ApiResponseWrapper
│   │   ├── domain/
│   │   │   ├── exception/             # DomainException, ErrorCode enum
│   │   │   └── model/                 # BaseEntity, Money (XAF value object)
│   │   └── application/port/          # AuditPort
│   ├── identity/                      # DOMAIN: Identity & Access
│   │   ├── auth/                      # MODULE: hexagonal structure (domain/application/adapter)
│   │   └── user/                      # MODULE
│   ├── catalog/                       # DOMAIN: Product Catalog
│   │   ├── product/
│   │   ├── category/
│   │   └── variant/
│   ├── commerce/                      # DOMAIN: Sales & POS
│   │   ├── pos/
│   │   ├── sale/
│   │   └── payment/
│   ├── inventory/                     # DOMAIN: Stock Management
│   │   ├── stock/
│   │   ├── transfer/
│   │   └── counting/
│   ├── store/                         # DOMAIN: Multi-Store
│   │   ├── store/
│   │   └── warehouse/
│   ├── reporting/                     # DOMAIN: Reports
│   │   ├── dashboard/
│   │   └── report/
│   ├── messaging/                     # DOMAIN: Communication
│   │   ├── whatsapp/
│   │   └── notification/
│   ├── sync/sync/                     # DOMAIN: Synchronization
│   ├── subscription/plan/             # DOMAIN: Billing
│   └── admin/                         # DOMAIN: Super Admin
│       ├── tenant/
│       └── platform/
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   ├── application-prod.yml
│   └── db/migration/tenant/           # Flyway per-tenant
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
| FR45-49: Inventory | `inventory/stock/`, `counting/` | `inventory/` |
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

**Before implementing ANY feature, the agent MUST:**

1. **Identify the problem type** — What variability exists? What might change?
2. **Select the appropriate GoF pattern** — Match the problem to the pattern
3. **Document the pattern choice** — Comment in code why this pattern was chosen
4. **Implement via interfaces** — Closed for modification, open for extension

**Common Keevo patterns:**

| Problem | GoF Pattern | Example |
|---|---|---|
| Multiple sync strategies | **Strategy** | `SyncService` → `RestSyncService`, future `PowerSyncService` |
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
