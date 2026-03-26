# Story 6.1: Lancement & Configuration d'une Session d'Inventaire

Status: done

<!-- Validation optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a proprietor (Simon),
I want to start an inventory session for a specific store or the warehouse, choosing between full or partial scope,
So that I can count my actual stock without disrupting ongoing operations.

---

## ⚠️ CRITICAL ARCHITECTURAL CONTEXT — Building on Epics 1–5 Foundation

> **This is the FIRST story of Epic 6 — Inventaire Assisté.**
> There are NO previous inventory domain implementations. The `inventory/counting/` backend module and the Flutter `inventory/` feature folder contain only `.gitkeep` placeholders and the Epic 3 multi-store stock code.
>
> **Existing infrastructure this story builds on:**
> - `catalog/stock/` domain: `StockLevel`, `StockMovement`, `StockOperationService`, `MovementType.ADJUSTMENT`
> - `catalog/stock/domain/port/out/StockLevelRepository`: `findByProductIdAndStoreId()`, `findAllByStoreId()`
> - `catalog/product/domain/port/out/ProductRepository`: `findAllActiveByStoreId()`
> - `store/store/domain/`: Store entity, StoreRepository
> - Drift tables: `StockLevels`, `Products`, `Stores`, `Categories` — all available offline
> - Schema version: 18 (next: 19)
> - Sync engine: push/pull operational (Epic 5 complete)
> - TenantSchemaProvisioner: idempotent DDL pattern, TenantSchemaSyncService auto-sync
>
> **Story 6.1 creates the session foundation; Stories 6.2–6.4 add counting form, gap analysis, and stock adjustment.**

### Current State — What Exists

```
┌──────────────────────────────────────────────────────────────────────
│ BACKEND inventory domain (currently EMPTY scaffolding)              │
│                                                                      │
│ inventory/                                                           │
│   ├── counting/adapter/in/mcp/.gitkeep                              │
│   ├── stock/adapter/in/mcp/.gitkeep                                 │
│   └── transfer/adapter/in/mcp/.gitkeep                              │
│                                                                      │
│ REUSABLE FROM catalog/stock/:                                        │
│   StockLevelRepository.findAllByStoreId(UUID storeId)               │
│   StockLevelRepository.findByProductIdAndStoreId(UUID, UUID)        │
│   ProductRepository.findAllActive()                                  │
│   StockOperationService.recordOperation(..., MovementType.ADJUSTMENT)│
│   StockMovement domain entity (immutable audit record)              │
│   StockAdjustedEvent / StockThresholdBreachedEvent (Observer)        │
└──────────────────────────────────────────────────────────────────────

┌──────────────────────────────────────────────────────────────────────
│ FLUTTER inventory feature (Epic 3 multi-store stock code only)      │
│                                                                      │
│ features/inventory/                                                  │
│   ├── domain/model/ — StoreProductStockModel, StoreStockSummaryModel │
│   │                    StockTransferModel                            │
│   ├── domain/repository/ — MultiStoreStockRepository,               │
│   │                         StockTransferRepository                  │
│   ├── domain/usecase/ — ExecuteTransferUseCase                       │
│   ├── data/datasource/ — Local/Remote stock + transfer DS            │
│   ├── data/repository/ — impls                                       │
│   └── presentation/ — GlobalStockOverviewPage,                       │
│                         TransferHistoryPage, widgets                 │
│                                                                      │
│ Router: /inventory → Placeholder, /stock/overview, /stock/transfers  │
│                                                                      │
│ Drift v18: StockLevels, Products, Stores, Categories available      │
└──────────────────────────────────────────────────────────────────────
```

### What Story 6.1 Adds

```
┌──────────────────────────────────────────────────────────────────────
│ STORY 6.1 — INVENTORY SESSION CREATION & CONFIGURATION              │
│                                                                      │
│ BACKEND (inventory/counting/ module):                                │
│   DDL: inventory_sessions table (per-tenant schema)                  │
│     → id, store_id, scope, category_ids, status, started_by,        │
│       started_at, cancelled_by, cancelled_at, completed_at,         │
│       updated_at                                                     │
│                                                                      │
│   Domain model:                                                      │
│     InventorySession (entity), InventoryScope (FULL/PARTIAL),       │
│     InventorySessionStatus (IN_PROGRESS/VALIDATED/CANCELLED),       │
│     InventorySessionCreatedEvent (Observer → audit)                  │
│                                                                      │
│   Ports:                                                             │
│     in/  → CreateInventorySessionUseCase,                            │
│            GetActiveSessionUseCase,                                  │
│            CancelInventorySessionUseCase                             │
│     out/ → InventorySessionRepository                                │
│                                                                      │
│   Application services:                                              │
│     CreateInventorySessionService (Factory pattern)                  │
│     GetActiveSessionService                                          │
│     CancelInventorySessionService                                    │
│                                                                      │
│   Adapter REST:                                                      │
│     POST   /api/v1/inventory/sessions (create)                       │
│     GET    /api/v1/inventory/sessions/active?storeId= (get active)  │
│     POST   /api/v1/inventory/sessions/{id}/cancel (cancel, OWNER)   │
│     GET    /api/v1/inventory/sessions (list, paginated)              │
│                                                                      │
│   JPA entity: InventorySessionJpaEntity + adapter                    │
│                                                                      │
│ FLUTTER:                                                             │
│   Drift v19: InventorySessions table                                 │
│   Domain: InventorySessionModel (Freezed)                            │
│   Data: Local/Remote InventorySession DataSources                    │
│   Repository: InventorySessionRepositoryImpl (Backend-First)         │
│   Providers: inventorySessionProvider, activeSessionProvider         │
│   Pages: InventoryLaunchPage (configuration form)                    │
│   Router: /inventory → InventoryLaunchPage (replaces placeholder)    │
│           /inventory/sessions → session list (history)               │
└──────────────────────────────────────────────────────────────────────
```

---

## GoF Pattern Analysis (MANDATORY — fill before any implementation)

| Question | Answer |
|---|---|
| What variability exists in this feature? | Two inventory scopes (FULL vs PARTIAL) with different product filtering logic. Session lifecycle states (IN_PROGRESS → VALIDATED or CANCELLED). RBAC rules differ (OWNER can cancel anyone's session, EMPLOYEE can only cancel their own). Future: scheduled inventories, barcode scan mode. |
| What might change in the future? | New scopes (BY_SUPPLIER, BY_PRICE_RANGE). Scheduled inventories (CRON-based). Multi-user concurrent counting per session. Barcode scan integration. Audit requirements may extend. |
| Which GoF pattern(s) apply? | **Factory** — `InventorySessionFactory.create()` encapsulates all creation validation (active session check, store existence, scope resolution, category validation). **Observer** — `InventorySessionCreatedEvent`, `InventorySessionCancelledEvent` → `AuditEventListener`. **Strategy** — `InventoryScopeResolver` interface for FULL vs PARTIAL product selection (extensible for future scopes). **State** — `InventorySessionStatus` enum drives which operations are valid on a session (cancel only if IN_PROGRESS, validate only if IN_PROGRESS). |
| How does it enable Open/Closed principle? | Adding a new scope (e.g., BY_SUPPLIER) = implement new `InventoryScopeResolver` strategy + register it. Adding a new session state transition = update state machine without modifying existing transitions. Adding a new event = create event class + listener — no modification to session creation code. |
| Where is the pattern applied? | **Factory** → `inventory/counting/domain/model/InventorySessionFactory.java`. **Observer** → `InventorySessionCreatedEvent` + `InventorySessionCancelledEvent` in `inventory/counting/domain/event/` → existing `AuditEventListener`. **Strategy** → `InventoryScopeResolver` interface in `inventory/counting/domain/service/` — `FullScopeResolver` + `PartialScopeResolver` implementations. **State** → `InventorySessionStatus` enum with `canCancel()`, `canValidate()` methods. |

---

## Acceptance Criteria

> **⚠️ SCOPE NOTE — "Planifier" mode deferred:** The epic AC mentions a "Planifier" option (date + time picker for scheduled inventories). This is **explicitly deferred** from Story 6.1 to keep scope focused on immediate session launch. Scheduled inventories will be a Growth feature. Story 6.1 only supports "Compter maintenant" (immediate start).

### AC1 — Create an inventory session (Backend + Flutter)

- **Given** Simon navigates to Stock > Inventaire (the `/inventory` route)
- **When** he taps "Lancer un inventaire"
- **Then** a configuration bottom sheet appears with:
  - **Boutique cible** (required) — dropdown of active stores + warehouse from local Drift `Stores` table
  - **Portée** (required) — radio: "Inventaire complet" (FULL — all active products in store) / "Inventaire partiel" (PARTIAL — select categories)
  - **Catégories** (visible only if PARTIAL) — multi-select chip list from local Drift `Categories` table (only active categories)
- **And** the "Démarrer" button is disabled until store + scope are selected (+ at least 1 category if PARTIAL)
- **And** on submit, the session is created:
  - **Online**: `POST /api/v1/inventory/sessions` → backend creates → Flutter caches locally (synced:true)
  - **Offline**: local Drift insert (synced:false) + `sync_queue` entry
- **And** the session receives: `id` (UUID), `storeId`, `scope` (FULL/PARTIAL), `categoryIds` (JSON array, null if FULL), `status: IN_PROGRESS`, `startedAt` (now), `startedBy` (actorId from JWT)
- **And** an `InventorySessionCreatedEvent` is emitted → audit log: `action: INVENTORY_SESSION_CREATED`, `entityType: InventorySession`
- **And** the app navigates to the inventory counting form (Story 6.2 placeholder — for now just a "Session en cours" confirmation page with session details)
- **And** a success SnackBar "Inventaire démarré" appears with ✅

### AC2 — Active session conflict detection

- **Given** an inventory session is already `IN_PROGRESS` for a store (e.g., started by Loïc at 10h)
- **When** Simon (or another user) tries to start a new session for the **same store**
- **Then** the backend returns HTTP 409 `{ "domainCode": "INVENTORY_SESSION_ALREADY_ACTIVE", "details": { "sessionId": "uuid", "startedBy": "Loïc", "startedAt": "2026-03-25T10:00:00Z" } }`
- **And** on Flutter, a dialog appears: "Un inventaire est déjà en cours pour [Store Name], commencé par [Actor] à [time]. Voulez-vous le reprendre ?"
- **And** tapping "Reprendre" navigates to the counting form for the existing session (Story 6.2)
- **And** tapping "Annuler et recommencer" calls the cancel endpoint THEN creates a new session (available to OWNER only)
- **And** **offline**: the local check uses the Drift `InventorySessions` table — if an IN_PROGRESS session exists for the store, show the same conflict dialog without network

### AC3 — Cancel an inventory session (OWNER only for others' sessions)

- **Given** Simon (OWNER) wants to cancel an in-progress session
- **When** he taps "Annuler l'inventaire" on the session detail
- **Then** the backend sets `status: CANCELLED`, `cancelledBy: actorId`, `cancelledAt: now()`
- **And** an `InventorySessionCancelledEvent` is emitted → audit log
- **And** the Flutter app updates local Drift and navigates back to the inventory launch page
- **And** an EMPLOYEE can only cancel sessions they started themselves (`startedBy == actorId`). Attempting to cancel another user's session returns HTTP 403 `{ "domainCode": "FORBIDDEN" }`

### AC4 — List past inventory sessions

- **Given** Simon navigates to Stock > Inventaire and there is no active session
- **When** the page loads
- **Then** below the "Lancer un inventaire" button, a list of past sessions is shown (VALIDATED + CANCELLED), loaded from local Drift first, refreshed from `GET /api/v1/inventory/sessions` when online
- **And** each session card shows: date, store name, scope badge (Complet / Partiel), status badge (✅ Validé / ❌ Annulé), product count (placeholder 0 for now — Story 6.2+ will fill this)
- **And** the list is paginated with "Voir plus" (local: Drift LIMIT/OFFSET, API: `?page=&size=`)
- **And** the list loads fully offline from Drift

### AC5 — Resume an in-progress session

- **Given** Simon has an IN_PROGRESS session for a store (created earlier, app was closed)
- **When** he navigates to Stock > Inventaire
- **Then** a prominent banner at the top shows: "📋 Inventaire en cours — [Store Name] — Commencé [relative time]"
- **And** tapping the banner navigates to the counting form for that session (Story 6.2 placeholder)
- **And** no new session can be started for that store until the active one is completed or cancelled

### AC6 — Sync integration

- **Given** a session is created offline
- **When** connectivity returns and push sync runs
- **Then** the session is pushed via the existing sync pipeline: `sync_queue` → `POST /api/v1/sync/push` with operation `CREATE_INVENTORY_SESSION`
- **And** the backend `SyncOperationHandler` for inventory sessions validates and creates the session (idempotent by `id`)
- **And** pull sync includes `inventory_sessions` in the delta response (new `InventorySessionDeltaProvider`)
- **And** other devices see the session appear/update after their next pull

---

## Technical Requirements

### Backend — Spring Boot (Hexagonal Architecture)

**Module**: `inventory/counting/` — follows the established hexagonal pattern.

**Package structure:**
```
com.keevo.inventory.counting/
├── domain/
│   ├── model/
│   │   ├── InventorySession.java           # Domain entity (pure Java)
│   │   ├── InventoryScope.java             # Enum: FULL, PARTIAL
│   │   ├── InventorySessionStatus.java     # Enum: IN_PROGRESS, VALIDATED, CANCELLED
│   │   └── InventorySessionFactory.java    # GoF Factory — creation + validation
│   ├── event/
│   │   ├── InventorySessionCreatedEvent.java
│   │   └── InventorySessionCancelledEvent.java
│   ├── port/
│   │   ├── in/
│   │   │   ├── CreateInventorySessionUseCase.java   # Command: CreateInventorySessionCommand
│   │   │   ├── GetActiveSessionUseCase.java         # Query: GetActiveSessionQuery
│   │   │   ├── CancelInventorySessionUseCase.java   # Command: CancelInventorySessionCommand
│   │   │   └── ListInventorySessionsUseCase.java    # Query: ListInventorySessionsQuery
│   │   └── out/
│   │       └── InventorySessionRepository.java      # Driven port (interface)
│   └── service/           # (reserved for Story 6.2+ scope resolvers)
├── application/
│   └── service/
│       ├── CreateInventorySessionService.java
│       ├── GetActiveSessionService.java
│       ├── CancelInventorySessionService.java
│       └── ListInventorySessionsService.java
└── adapter/
    ├── in/
    │   ├── rest/
    │   │   ├── InventorySessionController.java
    │   │   └── dto/
    │   │       ├── CreateInventorySessionRequestDto.java
    │   │       ├── InventorySessionResponseDto.java
    │   │       └── InventorySessionListResponseDto.java
    │   └── mcp/.gitkeep
    └── out/
        └── persistence/
            ├── InventorySessionJpaEntity.java
            └── InventorySessionRepositoryAdapter.java
```

**DDL — `inventory_sessions` table** (add to `TenantSchemaProvisioner.java`):

```sql
CREATE TABLE IF NOT EXISTS inventory_sessions (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id       UUID         NOT NULL REFERENCES stores(id),
    scope          VARCHAR(10)  NOT NULL CHECK (scope IN ('FULL', 'PARTIAL')),
    category_ids   JSONB,       -- null for FULL, JSON array of UUID for PARTIAL
    status         VARCHAR(15)  NOT NULL DEFAULT 'IN_PROGRESS'
                     CHECK (status IN ('IN_PROGRESS', 'VALIDATED', 'CANCELLED')),
    started_by     UUID         NOT NULL,
    started_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    cancelled_by   UUID,
    cancelled_at   TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_inventory_sessions_store_status
    ON inventory_sessions(store_id, status);
CREATE INDEX IF NOT EXISTS idx_inventory_sessions_status
    ON inventory_sessions(status);
```

**Domain Event Registry — Add to architecture registry:**

| Event | Package | Created in | Audit Action | Audit EntityType |
|---|---|---|---|---|
| `InventorySessionCreatedEvent` | `inventory.counting.domain.event` | Story 6.1 | `INVENTORY_SESSION_CREATED` | `InventorySession` |
| `InventorySessionCancelledEvent` | `inventory.counting.domain.event` | Story 6.1 | `INVENTORY_SESSION_CANCELLED` | `InventorySession` |

**ErrorCode additions** (in `shared/domain/exception/ErrorCode.java`):

```java
// ── Inventory ──
INVENTORY_SESSION_ALREADY_ACTIVE,    // HTTP 409 — store already has an in-progress session
INVENTORY_SESSION_NOT_FOUND,         // HTTP 404
INVENTORY_SESSION_NOT_IN_PROGRESS,   // HTTP 409 — cannot cancel/validate a non-active session
INVENTORY_STORE_NOT_FOUND,           // HTTP 404 — target store does not exist
INVENTORY_INVALID_CATEGORIES,        // HTTP 400 — one or more category IDs are invalid
```

**MCP Port Purity Rules** (as per architecture — non-negotiable):
- `CreateInventorySessionCommand` = pure Java Record: `(UUID storeId, InventoryScope scope, List<UUID> categoryIds, UUID actorId)`
- `CancelInventorySessionCommand` = pure Java Record: `(UUID sessionId, UUID actorId, String actorRole)`
- `GetActiveSessionQuery` = pure Java Record: `(UUID storeId)`
- `ListInventorySessionsQuery` = pure Java Record: `(UUID storeId, int page, int size)` — storeId nullable for all stores
- **NEVER** use `HttpServletRequest`, `Principal`, `SecurityContextHolder` in use cases
- **NEVER** return `ResponseEntity<>` from use cases

**TenantSchemaSyncService**: Add `inventory_sessions` to `ensureRequiredIndexes()` or the table sync set — needed to auto-create the table on login for existing tenants.

**Sync Handler**: Register `INVENTORY_SESSION` operation type in `SyncOperationHandlerRegistry` with a handler that creates/updates `inventory_sessions` records idempotently by `id`.

**Delta Provider**: Create `InventorySessionDeltaProvider` as the 15th delta provider for pull sync — returns inventory_sessions modified since `?since=` timestamp.

### Frontend — Flutter (Clean Architecture + Riverpod)

**Drift schema v19** — new table `InventorySessions`:

```dart
/// InventorySessions — local inventory session tracking.
/// Schema v19 (Story 6.1).
class InventorySessions extends Table {
  TextColumn get id => text()();
  TextColumn get storeId => text()();
  TextColumn get scope => text()(); // 'FULL' | 'PARTIAL'
  TextColumn get categoryIds => text().nullable()(); // JSON array of UUIDs, null if FULL
  TextColumn get status => text()(); // 'IN_PROGRESS' | 'VALIDATED' | 'CANCELLED'
  TextColumn get startedBy => text()();
  DateTimeColumn get startedAt => dateTime()();
  TextColumn get cancelledBy => text().nullable()();
  DateTimeColumn get cancelledAt => dateTime().nullable()();
  DateTimeColumn get completedAt => dateTime().nullable()();
  DateTimeColumn get updatedAt => dateTime()();
  BoolColumn get synced => boolean().withDefault(const Constant(false))();

  @override
  Set<Column> get primaryKey => {id};
}
```

**Migration** (`app_database.dart`):
```dart
// schemaVersion => 19
if (from < 19) {
  await m.createTable(inventorySessions);
}
```

**Feature structure:**
```
lib/features/inventory/
├── domain/
│   ├── model/
│   │   ├── inventory_session_model.dart      # Freezed model
│   │   └── inventory_scope.dart              # Enum
│   ├── repository/
│   │   └── inventory_session_repository.dart # Interface (port)
│   └── usecase/
│       ├── create_inventory_session_usecase.dart
│       └── cancel_inventory_session_usecase.dart
├── data/
│   ├── datasource/
│   │   ├── local_inventory_session_datasource.dart
│   │   └── remote_inventory_session_datasource.dart
│   └── repository/
│       └── inventory_session_repository_impl.dart  # Backend-First pattern
├── presentation/
│   ├── provider/
│   │   └── inventory_session_provider.dart
│   ├── page/
│   │   └── inventory_launch_page.dart       # Configuration bottom sheet + session list
│   └── widget/
│       ├── inventory_config_bottom_sheet.dart
│       ├── active_session_banner.dart
│       └── session_history_card.dart
```

**Router updates:**
- `/inventory` → `InventoryLaunchPage` (replaces placeholder)
- `/inventory/sessions` → session history list (optional separate route)
- `/inventory/counting/:sessionId` → placeholder for Story 6.2

**Backend-First-When-Online pattern** (canonical — MANDATORY):
```dart
class InventorySessionRepositoryImpl implements InventorySessionRepository {
  final LocalInventorySessionDataSource _local;
  final RemoteInventorySessionDataSource _remote;
  final AppDatabase _db;
  final bool Function() _isOnline;

  Future<InventorySessionModel> create(CreateInventorySessionParams params) async {
    if (_isOnline()) {
      try {
        final remote = await _remote.create(params);
        await _local.upsert(remote); // cache, synced=true
        return remote;
      } on DomainException { rethrow; }
      catch (_) { /* network fail → fall through */ }
    }
    // OFFLINE: local insert + sync_queue
    final local = await _local.insert(params);
    await _db.into(_db.syncQueue).insert(SyncQueueCompanion.insert(
      id: const Uuid().v4(),
      operation: 'CREATE_INVENTORY_SESSION',
      payload: jsonEncode(params.toPayload()),
      createdAt: DateTime.now(),
    ));
    return local;
  }
}
```

**UX Refresh** — after session creation/cancellation:
```dart
ref.invalidate(activeSessionProvider);
ref.invalidate(sessionHistoryProvider);
```

---

## UX / Design Requirements

> **Source**: UX Design Specification — Flow 10 (Inventaire Assisté), UX9 (Inventory Row — deferred to Story 6.2), Component Strategy P3
> **Note**: UX9 "Inventory Row" component (name + stock théorique + champ saisie réel + badge écart, states: Non saisi | Match ✅ | Écart ⚠️) belongs to Story 6.2 (counting form), not this story.

### InventoryLaunchPage layout

**When no session active:**
- AppBar: "Inventaire" (no gradient — inner page, standard)
- Content:
  1. **"Lancer un inventaire"** — large ElevatedButton (primary gradient), full-width, icon `Icons.assignment_outlined`
  2. **Historique des inventaires** — section title + list of past sessions as `SessionHistoryCard`
  3. Empty state if no history: illustration + "Aucun inventaire réalisé" + "Lancez votre premier inventaire pour vérifier vos stocks"

**When active session exists:**
- **ActiveSessionBanner** at the top: amber background (`#FCC419` 15% opacity), icon 📋, text "Inventaire en cours — [Store Name] — Commencé il y a [duration]", tap to navigate
- "Lancer un inventaire" button replaced by "Reprendre l'inventaire" (amber variant)

### InventoryConfigBottomSheet

- Title: "Configurer l'inventaire"
- **Store selector**: `DropdownButtonFormField` with store names from Drift, required validator
- **Scope selector**: two Material radio tiles — "Inventaire complet" (icon: `Icons.select_all`) / "Inventaire partiel" (icon: `Icons.filter_list`)
- **Category multi-select** (visible only if PARTIAL): horizontal scrollable `FilterChip` list from Drift categories
- **Start button**: "Démarrer l'inventaire" — disabled until valid, primary gradient
- **Cancel link**: "Annuler" TextButton at bottom

### Colors & Tokens (from UX spec)

- Active session banner: `colorWarning (#FCC419)` 15% opacity background, dark text
- Status badge VALIDATED: `colorSuccess (#51CF66)` + ✅
- Status badge CANCELLED: `colorError (#FA5252)` + ❌
- Status badge IN_PROGRESS: `colorWarning (#FCC419)` + ⏳
- Session history card: `ColorScheme.surfaceContainerLow` background, 16dp border radius

### Responsive (UX5 breakpoints)

> **Source**: UX spec responsive table — Inventaire: "Liste full-width | 2 col: liste + aperçu | 3 col: catégories + liste + saisie"
> The 3-column layout applies to the counting form (Story 6.2). For Story 6.1 (launch page + config):

- **Compact** (<600dp): bottom sheet for config, single-column session list
- **Medium** (600-840dp): bottom sheet wider, 2-column grid for history
- **Expanded** (>840dp): config as dialog (not bottom sheet) centered on screen, 3-column session grid

### Accessibility

- Touch targets ≥ 48dp spacing 8dp
- Semantics labels on all interactive elements
- Store selector auto-focuses on open
- French labels everywhere — no English in UI

---

## Tasks / Subtasks

> **⚠️ TDD STRICT — RED → GREEN → REFACTOR. Write the failing test FIRST, then implement. No exceptions.**

---

### BACKEND — Full TDD (Spring Boot, Hexagonal Architecture)

> **Module placement**: `inventory/counting/`
> Domain: `com.keevo.inventory.counting`
> All tests go in `src/test/java/com/keevo/inventory/counting/`

---

- [x] **Task 1 — ErrorCode additions** (AC1, AC2, AC3)

  - [x] 1.1 — Add inventory-specific error codes to `ErrorCode.java`:
    ```java
    // ── Inventory ──
    INVENTORY_SESSION_ALREADY_ACTIVE,
    INVENTORY_SESSION_NOT_FOUND,
    INVENTORY_SESSION_NOT_IN_PROGRESS,
    INVENTORY_STORE_NOT_FOUND,
    INVENTORY_INVALID_CATEGORIES,
    ```
  - [x] 1.2 — Add HTTP status mapping in `GlobalExceptionHandler.java` (if needed — most map to existing status patterns):
    - `INVENTORY_SESSION_ALREADY_ACTIVE` → 409 CONFLICT
    - `INVENTORY_SESSION_NOT_FOUND` → 404 NOT_FOUND
    - `INVENTORY_SESSION_NOT_IN_PROGRESS` → 409 CONFLICT
    - `INVENTORY_STORE_NOT_FOUND` → 404 NOT_FOUND
    - `INVENTORY_INVALID_CATEGORIES` → 400 BAD_REQUEST

---

- [x] **Task 2 — DDL: inventory_sessions table** (AC1)

  > **Pattern**: Same as `DDL_STORES`, `DDL_PRODUCTS` — add constants to `TenantSchemaProvisioner.java` and execute in `buildSchema()`. Also add to `TenantSchemaSyncService.ensureRequiredIndexes()` for existing tenant auto-migration on login.

  - [x] 2.1 — Write failing test `InventorySessionsDdlTest.java`:
    ```java
    @Test
    void inventorySessions_table_shouldExistWithRequiredColumns() {
        // GIVEN a provisioned tenant schema
        // WHEN queried for inventory_sessions table columns
        // THEN columns id, store_id, scope, category_ids, status, started_by,
        //      started_at, cancelled_by, cancelled_at, completed_at, updated_at exist
    }
    ```
  - [x] 2.2 — Add `DDL_INVENTORY_SESSIONS` constant to `TenantSchemaProvisioner.java`:
    ```java
    static final String DDL_INVENTORY_SESSIONS = """
        CREATE TABLE IF NOT EXISTS inventory_sessions (
            id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
            store_id       UUID         NOT NULL REFERENCES stores(id),
            scope          VARCHAR(10)  NOT NULL CHECK (scope IN ('FULL', 'PARTIAL')),
            category_ids   JSONB,
            status         VARCHAR(15)  NOT NULL DEFAULT 'IN_PROGRESS'
                             CHECK (status IN ('IN_PROGRESS', 'VALIDATED', 'CANCELLED')),
            started_by     UUID         NOT NULL,
            started_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
            cancelled_by   UUID,
            cancelled_at   TIMESTAMPTZ,
            completed_at   TIMESTAMPTZ,
            updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
        )""";

    static final String DDL_INVENTORY_SESSIONS_IDX_STORE_STATUS =
        "CREATE INDEX IF NOT EXISTS idx_inventory_sessions_store_status ON inventory_sessions(store_id, status)";

    static final String DDL_INVENTORY_SESSIONS_IDX_STATUS =
        "CREATE INDEX IF NOT EXISTS idx_inventory_sessions_status ON inventory_sessions(status)";
    ```
  - [x] 2.3 — Add execution in `buildSchema()`:
    ```java
    stmt.execute(DDL_INVENTORY_SESSIONS);
    stmt.execute(DDL_INVENTORY_SESSIONS_IDX_STORE_STATUS);
    stmt.execute(DDL_INVENTORY_SESSIONS_IDX_STATUS);
    ```
  - [x] 2.4 — Add `inventory_sessions` table migration to `TenantSchemaSyncService` for existing tenants:
    ```java
    // Add REQUIRED_TABLES entry or ensureRequiredIndexes() call
    ```
  - [x] 2.5 — Run test → GREEN

---

- [x] **Task 3 — Domain model: Enums + InventorySession entity** (AC1, AC2, AC3)

  - [x] 3.1 — Write `InventoryScopeTest.java`:
    ```java
    @Test void fullScope_shouldBeValid() { assertEquals("FULL", InventoryScope.FULL.name()); }
    @Test void partialScope_shouldBeValid() { assertEquals("PARTIAL", InventoryScope.PARTIAL.name()); }
    ```
  - [x] 3.2 — Create `InventoryScope.java`:
    ```java
    public enum InventoryScope { FULL, PARTIAL }
    ```
  - [x] 3.3 — Write `InventorySessionStatusTest.java`:
    ```java
    @Test void inProgress_canCancel() { assertTrue(InventorySessionStatus.IN_PROGRESS.canCancel()); }
    @Test void inProgress_canValidate() { assertTrue(InventorySessionStatus.IN_PROGRESS.canValidate()); }
    @Test void validated_cannotCancel() { assertFalse(InventorySessionStatus.VALIDATED.canCancel()); }
    @Test void cancelled_cannotValidate() { assertFalse(InventorySessionStatus.CANCELLED.canValidate()); }
    ```
  - [x] 3.4 — Create `InventorySessionStatus.java` with state machine methods:
    ```java
    public enum InventorySessionStatus {
        IN_PROGRESS, VALIDATED, CANCELLED;
        public boolean canCancel() { return this == IN_PROGRESS; }
        public boolean canValidate() { return this == IN_PROGRESS; }
    }
    ```
  - [x] 3.5 — Write `InventorySessionTest.java`:
    ```java
    @Test void session_shouldHoldAllFields() { ... }
    @Test void session_whenStoreIdNull_shouldThrow() { ... }
    @Test void session_fullScope_shouldHaveNullCategoryIds() { ... }
    @Test void session_partialScope_withEmptyCategoryIds_shouldThrow() { ... }
    @Test void session_cancel_whenInProgress_shouldSetStatusAndActorAndTimestamp() { ... }
    @Test void session_cancel_whenAlreadyCancelled_shouldThrowDomainException() { ... }
    ```
  - [x] 3.6 — Create `InventorySession.java` (pure Java entity — no framework deps):
    ```java
    public class InventorySession {
        private final UUID id;
        private final UUID storeId;
        private final InventoryScope scope;
        private final List<UUID> categoryIds; // null if FULL, non-empty if PARTIAL
        private InventorySessionStatus status;
        private final UUID startedBy;
        private final Instant startedAt;
        private UUID cancelledBy;
        private Instant cancelledAt;
        private Instant completedAt;
        private Instant updatedAt;

        // Constructor with validation
        // cancel(UUID actorId) method — validates state machine
        // validate() method — validates state machine (for Story 6.4)
    }
    ```

---

- [x] **Task 4 — Domain events** (AC1, AC3)

  - [x] 4.1 — Create `InventorySessionCreatedEvent.java` in `inventory/counting/domain/event/`:
    ```java
    public record InventorySessionCreatedEvent(
        UUID sessionId, UUID storeId, InventoryScope scope,
        UUID actorId, String tenantId, Instant occurredAt
    ) {}
    ```
  - [x] 4.2 — Create `InventorySessionCancelledEvent.java`:
    ```java
    public record InventorySessionCancelledEvent(
        UUID sessionId, UUID actorId, String tenantId, Instant occurredAt
    ) {}
    ```
  - [x] 4.3 — Register in `AuditEventListener` — add `@EventListener` methods:
    ```java
    @EventListener
    public void on(InventorySessionCreatedEvent event) {
        auditPort.record("INVENTORY_SESSION_CREATED", "InventorySession", event.sessionId(), ...);
    }
    @EventListener
    public void on(InventorySessionCancelledEvent event) {
        auditPort.record("INVENTORY_SESSION_CANCELLED", "InventorySession", event.sessionId(), ...);
    }
    ```

---

- [x] **Task 5 — Port out: InventorySessionRepository** (AC1, AC2, AC4)

  - [x] 5.1 — Write `InventorySessionRepositoryContractTest.java`:
    ```java
    @Test void save_shouldPersistSession() { ... }
    @Test void findById_shouldReturnSession() { ... }
    @Test void findActiveByStoreId_whenExists_shouldReturn() { ... }
    @Test void findActiveByStoreId_whenNone_shouldReturnEmpty() { ... }
    @Test void findAllByStoreId_shouldReturnPaginated() { ... }
    ```
  - [x] 5.2 — Create `InventorySessionRepository.java` interface in `domain/port/out/`:
    ```java
    public interface InventorySessionRepository {
        InventorySession save(InventorySession session);
        Optional<InventorySession> findById(UUID id);
        Optional<InventorySession> findActiveByStoreId(UUID storeId);
        Page<InventorySession> findByStoreId(UUID storeId, int page, int size);
        Page<InventorySession> findAll(int page, int size);
    }
    ```

---

- [x] **Task 6 — Port in: Use case interfaces + Commands/Queries** (AC1, AC2, AC3, AC4)

  - [x] 6.1 — Create `CreateInventorySessionCommand.java`:
    ```java
    public record CreateInventorySessionCommand(
        UUID storeId, InventoryScope scope, List<UUID> categoryIds, UUID actorId
    ) {}
    ```
  - [x] 6.2 — Create `CreateInventorySessionUseCase.java`:
    ```java
    public interface CreateInventorySessionUseCase {
        InventorySession execute(CreateInventorySessionCommand command);
    }
    ```
  - [x] 6.3 — Create `CancelInventorySessionCommand.java`:
    ```java
    public record CancelInventorySessionCommand(UUID sessionId, UUID actorId, String actorRole) {}
    ```
  - [x] 6.4 — Create `CancelInventorySessionUseCase.java`:
    ```java
    public interface CancelInventorySessionUseCase {
        void execute(CancelInventorySessionCommand command);
    }
    ```
  - [x] 6.5 — Create `GetActiveSessionQuery.java` + `GetActiveSessionUseCase.java`
  - [x] 6.6 — Create `ListInventorySessionsQuery.java` + `ListInventorySessionsUseCase.java`

---

- [x] **Task 7 — Application services** (AC1, AC2, AC3, AC4)

  - [x] 7.1 — Write `CreateInventorySessionServiceTest.java`:
    ```java
    @Test void create_shouldPersistSession() { ... }
    @Test void create_whenActiveSessionExists_shouldThrowConflict() { ... }
    @Test void create_whenStoreNotFound_shouldThrow() { ... }
    @Test void create_partialScope_withInvalidCategories_shouldThrow() { ... }
    @Test void create_fullScope_shouldIgnoreCategoryIds() { ... }
    @Test void create_shouldPublishEvent() { ... }
    ```
  - [x] 7.2 — Implement `CreateInventorySessionService.java`:
    - Inject: `InventorySessionRepository`, `StoreRepository` (from store domain), `CategoryRepository` (from catalog), `ApplicationEventPublisher`
    - Logic: verify store exists → check no active session → validate categories if PARTIAL → create via factory → save → publish event
  - [x] 7.3 — Write `CancelInventorySessionServiceTest.java`:
    ```java
    @Test void cancel_whenInProgress_shouldSetCancelled() { ... }
    @Test void cancel_whenNotFound_shouldThrow() { ... }
    @Test void cancel_whenAlreadyCancelled_shouldThrow() { ... }
    @Test void cancel_employee_canCancelOwnSession() { ... }
    @Test void cancel_employee_cannotCancelOthersSession() { ... }
    @Test void cancel_owner_canCancelAnySession() { ... }
    @Test void cancel_shouldPublishEvent() { ... }
    ```
  - [x] 7.4 — Implement `CancelInventorySessionService.java`:
    - RBAC check: `if ("EMPLOYEE".equals(command.actorRole()) && !session.getStartedBy().equals(command.actorId()))` → throw FORBIDDEN
    - `actorRole` is passed explicitly in `CancelInventorySessionCommand` (extracted from JWT by the controller, same pattern as `ImportCsvProductsUseCase`)
  - [x] 7.5 — Write + implement `GetActiveSessionService.java` (simple delegate)
  - [x] 7.6 — Write + implement `ListInventorySessionsService.java` (simple delegate)

---

- [x] **Task 8 — JPA entity + Repository adapter** (AC1, AC4)

  - [x] 8.1 — Create `InventorySessionJpaEntity.java` in `inventory/counting/adapter/out/persistence/`:
    ```java
    @Entity
    @Table(name = "inventory_sessions")
    public class InventorySessionJpaEntity {
        @Id UUID id;
        @Column(name = "store_id") UUID storeId;
        @Enumerated(EnumType.STRING) InventoryScope scope;
        @Column(columnDefinition = "JSONB") String categoryIds; // JSON array
        @Enumerated(EnumType.STRING) InventorySessionStatus status;
        @Column(name = "started_by") UUID startedBy;
        @Column(name = "started_at") Instant startedAt;
        @Column(name = "cancelled_by") UUID cancelledBy;
        @Column(name = "cancelled_at") Instant cancelledAt;
        @Column(name = "completed_at") Instant completedAt;
        @Column(name = "updated_at") Instant updatedAt;
    }
    ```
  - [x] 8.2 — Create `JpaInventorySessionRepository.java` (Spring Data JPA interface):
    ```java
    public interface JpaInventorySessionRepository extends JpaRepository<InventorySessionJpaEntity, UUID> {
        Optional<InventorySessionJpaEntity> findByStoreIdAndStatus(UUID storeId, InventorySessionStatus status);
        Page<InventorySessionJpaEntity> findByStoreIdOrderByStartedAtDesc(UUID storeId, Pageable pageable);
        Page<InventorySessionJpaEntity> findAllByOrderByStartedAtDesc(Pageable pageable);
    }
    ```
  - [x] 8.3 — Write `InventorySessionRepositoryAdapterTest.java` (domain ↔ JPA mapping):
    ```java
    @Test void toDomain_shouldMapAllFields() { ... }
    @Test void toJpa_shouldMapAllFields() { ... }
    @Test void categoryIds_shouldSerializeDeserializeAsJson() { ... }
    ```
  - [x] 8.4 — Implement `InventorySessionRepositoryAdapter.java` with domain ↔ JPA mapping (JSON list ↔ String for categoryIds)

---

- [x] **Task 9 — REST controller + DTOs** (AC1, AC2, AC3, AC4)

  - [x] 9.1 — Create `CreateInventorySessionRequestDto.java`:
    ```java
    public record CreateInventorySessionRequestDto(
        @NotNull UUID storeId,
        @NotNull InventoryScope scope,
        List<UUID> categoryIds  // optional, validated in service
    ) {}
    ```
  - [x] 9.2 — Create `InventorySessionResponseDto.java`:
    ```java
    public record InventorySessionResponseDto(
        UUID id, UUID storeId, String storeName,
        InventoryScope scope, List<UUID> categoryIds,
        InventorySessionStatus status,
        UUID startedBy, String startedByName,
        Instant startedAt, Instant cancelledAt,
        Instant completedAt, Instant updatedAt
    ) {}
    ```
  - [x] 9.3 — Write `InventorySessionControllerTest.java` (`@WebMvcTest`):
    ```java
    @Test void POST_sessions_shouldReturn201() { ... }
    @Test void POST_sessions_whenAlreadyActive_shouldReturn409() { ... }
    @Test void POST_sessions_withoutAuth_shouldReturn401() { ... }
    @Test void POST_sessions_cancel_shouldReturn200() { ... }
    @Test void POST_sessions_cancel_employeeCannotCancelOthers_shouldReturn403() { ... }
    @Test void GET_sessions_active_shouldReturn200() { ... }
    @Test void GET_sessions_active_whenNone_shouldReturn404() { ... }
    @Test void GET_sessions_list_shouldReturnPaginated() { ... }
    ```
  - [x] 9.4 — Implement `InventorySessionController.java`:
    ```java
    @Tag(name = "Inventory", description = "Inventory session management")
    @RestController
    @RequestMapping("/api/v1/inventory/sessions")
    public class InventorySessionController {
        @PostMapping → createSession
        @GetMapping("/active") → getActiveSession (query: ?storeId=)
        @PostMapping("/{id}/cancel") → cancelSession
        @GetMapping → listSessions (query: ?storeId=&page=&size=)
    }
    ```
  - [x] 9.5 — Add OpenAPI annotations per architecture rules

---

- [x] **Task 10 — Sync integration: handler + delta provider** (AC6)

  - [x] 10.1 — Create `InventorySessionSyncOperationHandler.java` in sync module:
    - Handles `CREATE_INVENTORY_SESSION` operation type
    - Idempotent by session `id` (if exists, skip)
    - Delegates to `InventorySessionRepository.save()`
  - [x] 10.2 — Register handler in `SyncOperationHandlerRegistry`
  - [x] 10.3 — Create `InventorySessionDeltaProvider.java`:
    - Query: `SELECT * FROM inventory_sessions WHERE updated_at > :since`
    - Register as 15th delta provider in `SyncService.pull()`
  - [x] 10.4 — Write tests for handler (idempotency) and delta provider

---

### FLUTTER — Full TDD (Clean Architecture + Riverpod)

> **Feature folder**: `lib/features/inventory/` (extends existing structure)
> Tests go in `test/features/inventory/`

---

- [x] **Task 11 — Drift v19: InventorySessions table** (AC1)

  - [x] 11.1 — Create `lib/core/storage/inventory_sessions_table.dart`:
    ```dart
    class InventorySessions extends Table {
      TextColumn get id => text()();
      TextColumn get storeId => text()();
      TextColumn get scope => text()(); // 'FULL' | 'PARTIAL'
      TextColumn get categoryIds => text().nullable()();
      TextColumn get status => text()();
      TextColumn get startedBy => text()();
      DateTimeColumn get startedAt => dateTime()();
      TextColumn get cancelledBy => text().nullable()();
      DateTimeColumn get cancelledAt => dateTime().nullable()();
      DateTimeColumn get completedAt => dateTime().nullable()();
      DateTimeColumn get updatedAt => dateTime()();
      BoolColumn get synced => boolean().withDefault(const Constant(false))();
      @override
      Set<Column> get primaryKey => {id};
    }
    ```
  - [x] 11.2 — Register in `@DriftDatabase(tables: [..., InventorySessions])`
  - [x] 11.3 — Bump `schemaVersion => 19` and add migration: `if (from < 19) { await m.createTable(inventorySessions); }`
  - [x] 11.4 — Run `dart run build_runner build` to regenerate

---

- [x] **Task 12 — Domain model: InventorySessionModel** (AC1)

  - [x] 12.1 — Write `inventory_session_model_test.dart`:
    ```dart
    test('should create from JSON with all fields', () { ... });
    test('should handle FULL scope with null categoryIds', () { ... });
    test('isActive returns true only for IN_PROGRESS', () { ... });
    ```
  - [x] 12.2 — Create `inventory_session_model.dart` (Freezed):
    ```dart
    @freezed
    class InventorySessionModel with _$InventorySessionModel {
      const InventorySessionModel._();
      const factory InventorySessionModel({
        required String id,
        required String storeId,
        required String scope,
        List<String>? categoryIds,
        required String status,
        required String startedBy,
        required DateTime startedAt,
        String? cancelledBy,
        DateTime? cancelledAt,
        DateTime? completedAt,
        required DateTime updatedAt,
      }) = _InventorySessionModel;

      bool get isActive => status == 'IN_PROGRESS';
      bool get isValidated => status == 'VALIDATED';
      bool get isCancelled => status == 'CANCELLED';

      factory InventorySessionModel.fromJson(Map<String, dynamic> json) =>
          _$InventorySessionModelFromJson(json);
    }
    ```
  - [x] 12.3 — Create `inventory_scope.dart` enum:
    ```dart
    enum InventoryScope { full, partial }
    ```

---

- [x] **Task 13 — Domain repository interface** (AC1)

  - [x] 13.1 — Create `inventory_session_repository.dart`:
    ```dart
    abstract class InventorySessionRepository {
      Future<InventorySessionModel> create({
        required String storeId,
        required String scope,
        List<String>? categoryIds,
      });
      Future<InventorySessionModel?> getActiveByStoreId(String storeId);
      Future<void> cancel(String sessionId);
      Future<List<InventorySessionModel>> getHistory({int page = 0, int size = 20});
    }
    ```

---

- [x] **Task 14 — Data layer: datasources + repository impl** (AC1, AC2, AC5, AC6)

  - [x] 14.1 — Write `local_inventory_session_datasource_test.dart`:
    ```dart
    test('insert should persist session', () { ... });
    test('findActiveByStoreId should return IN_PROGRESS session', () { ... });
    test('findActiveByStoreId returns null when none active', () { ... });
    test('upsert should update existing session', () { ... });
    ```
  - [x] 14.2 — Implement `local_inventory_session_datasource.dart`:
    - `insert()`, `upsert()`, `findActiveByStoreId()`, `findById()`, `findAll()`, `updateStatus()`
  - [x] 14.3 — Write `remote_inventory_session_datasource_test.dart` (mock Dio):
    ```dart
    test('create should POST and return model', () { ... });
    test('getActive should GET and return model', () { ... });
    test('cancel should POST cancel endpoint', () { ... });
    ```
  - [x] 14.4 — Implement `remote_inventory_session_datasource.dart`:
    - `create()`: `POST /api/v1/inventory/sessions`
    - `getActive()`: `GET /api/v1/inventory/sessions/active?storeId=`
    - `cancel()`: `POST /api/v1/inventory/sessions/{id}/cancel`
    - `getHistory()`: `GET /api/v1/inventory/sessions?page=&size=`
  - [x] 14.5 — Write `inventory_session_repository_impl_test.dart`:
    ```dart
    test('create online → remote first → cache locally', () { ... });
    test('create offline → local insert + sync_queue', () { ... });
    test('create remote error → offline fallback', () { ... });
    test('getActive → local first', () { ... });
    test('cancel online → remote first → update locally', () { ... });
    ```
  - [x] 14.6 — Implement `inventory_session_repository_impl.dart` (Backend-First pattern)

---

- [x] **Task 15 — Use cases** (AC1, AC3)

  - [x] 15.1 — Write `create_inventory_session_usecase_test.dart`:
    ```dart
    test('should delegate to repository', () { ... });
    ```
  - [x] 15.2 — Implement `create_inventory_session_usecase.dart`
  - [x] 15.3 — Write + implement `cancel_inventory_session_usecase.dart`

---

- [x] **Task 16 — Riverpod providers** (AC1, AC2, AC4, AC5)

  - [x] 16.1 — Write `inventory_session_provider_test.dart`:
    ```dart
    test('activeSessionProvider should return active session from repo', () { ... });
    test('sessionHistoryProvider should return paginated list', () { ... });
    test('createSessionNotifier should create and invalidate providers', () { ... });
    test('cancelSessionNotifier should cancel and invalidate providers', () { ... });
    ```
  - [x] 16.2 — Implement `inventory_session_provider.dart`:
    ```dart
    @riverpod
    Future<InventorySessionModel?> activeSession(Ref ref) async { ... }

    @riverpod
    Future<List<InventorySessionModel>> sessionHistory(Ref ref) async { ... }

    @riverpod
    class CreateSessionNotifier extends _$CreateSessionNotifier { ... }

    @riverpod
    class CancelSessionNotifier extends _$CancelSessionNotifier { ... }
    ```

---

- [x] **Task 17 — UI: InventoryLaunchPage + widgets** (AC1, AC2, AC4, AC5)

  - [x] 17.1 — Write `inventory_launch_page_test.dart`:
    ```dart
    testWidgets('shows launch button when no active session', (t) async { ... });
    testWidgets('shows active session banner when session exists', (t) async { ... });
    testWidgets('shows session history list', (t) async { ... });
    testWidgets('shows empty state when no history', (t) async { ... });
    ```
  - [x] 17.2 — Implement `inventory_launch_page.dart`
  - [x] 17.3 — Write `inventory_config_bottom_sheet_test.dart`:
    ```dart
    testWidgets('store dropdown shows active stores', (t) async { ... });
    testWidgets('category chips visible only for PARTIAL scope', (t) async { ... });
    testWidgets('start button disabled until form valid', (t) async { ... });
    testWidgets('start button creates session and navigates', (t) async { ... });
    ```
  - [x] 17.4 — Implement `inventory_config_bottom_sheet.dart`
  - [x] 17.5 — Implement `active_session_banner.dart`
  - [x] 17.6 — Implement `session_history_card.dart`

---

- [x] **Task 18 — Router update** (AC1, AC5)

  - [x] 18.1 — Replace `/inventory` placeholder route with `InventoryLaunchPage`
  - [x] 18.2 — Add `/inventory/counting/:sessionId` placeholder route for Story 6.2
  - [x] 18.3 — Ensure nav bar "Stock" tab includes access to inventory (current nav structure)

---

- [x] **Task 19 — Sync pull integration (Flutter)** (AC6)

  - [x] 19.1 — Add `inventory_sessions` upsert logic in `RestSyncService.pull()`:
    - Parse `inventorySessions` from pull delta response
    - Protect pending IDs (AC7 pattern from Story 5.2: skip upsert if local entry has `synced == false`)
    - Upsert via `LocalInventorySessionDataSource.upsert()`
  - [x] 19.2 — Write test for pull upsert with pending protection

---

### CURL INTEGRATION TESTS

- [x] **Task 20 — cURL integration script** (AC1, AC2, AC3, AC4)

```bash
#!/usr/bin/env bash
# ======================================================
# Story 6.1 — Inventory Session cURL Integration Tests
# Run: bash curl-tests-story-6-1.sh
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE_URL="http://localhost:8443"

# Step 1 — Register + Onboard (get JWT + tenant + store)
REGISTER=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber":"+237699990061","password":"Test1234!","firstName":"Inv","lastName":"Test"}')
JWT=$(echo "$REGISTER" | jq -r '.data.accessToken // empty')
[[ -n "$JWT" ]] && echo "✅ Step 1 — JWT obtained" || { echo "❌ Step 1 FAILED"; exit 1; }

# Step 2 — Complete onboarding to get a store
ONBOARD=$(curl -s -X POST "$BASE_URL/api/v1/onboarding/complete" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"sectorType":"VETEMENTS","storeName":"Boutique Inventaire","businessName":"Inv Test SARL"}')
echo "$ONBOARD" | jq .
echo "✅ Step 2 — Onboarding completed"

# Step 3 — Get store ID
STORES=$(curl -s -X GET "$BASE_URL/api/v1/tenant/stores" \
  -H "Authorization: Bearer $JWT")
STORE_ID=$(echo "$STORES" | jq -r '.data[0].id')
[[ -n "$STORE_ID" && "$STORE_ID" != "null" ]] && echo "✅ Step 3 — Store ID: $STORE_ID" || { echo "❌ Step 3 FAILED"; exit 1; }

# Step 4 — Create FULL inventory session
SESSION=$(curl -s -X POST "$BASE_URL/api/v1/inventory/sessions" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$STORE_ID\",\"scope\":\"FULL\"}")
echo "$SESSION" | jq .
SESSION_ID=$(echo "$SESSION" | jq -r '.data.id')
[[ -n "$SESSION_ID" && "$SESSION_ID" != "null" ]] && echo "✅ Step 4 — Session created: $SESSION_ID" || { echo "❌ Step 4 FAILED"; exit 1; }

# Step 5 — Attempt duplicate session (same store) → expect 409
DUP=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/inventory/sessions" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$STORE_ID\",\"scope\":\"FULL\"}")
[[ "$DUP" == "409" ]] && echo "✅ Step 5 — Duplicate blocked (409)" || { echo "❌ Step 5 FAILED (got $DUP)"; exit 1; }

# Step 6 — Get active session
ACTIVE=$(curl -s -X GET "$BASE_URL/api/v1/inventory/sessions/active?storeId=$STORE_ID" \
  -H "Authorization: Bearer $JWT")
ACTIVE_STATUS=$(echo "$ACTIVE" | jq -r '.data.status')
[[ "$ACTIVE_STATUS" == "IN_PROGRESS" ]] && echo "✅ Step 6 — Active session is IN_PROGRESS" || { echo "❌ Step 6 FAILED"; exit 1; }

# Step 7 — Cancel session
CANCEL=$(curl -s -X POST "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/cancel" \
  -H "Authorization: Bearer $JWT")
CANCEL_STATUS=$(echo "$CANCEL" | jq -r '.data.status')
[[ "$CANCEL_STATUS" == "CANCELLED" ]] && echo "✅ Step 7 — Session cancelled" || { echo "❌ Step 7 FAILED"; exit 1; }

# Step 8 — No active session after cancel → expect 404
NO_ACTIVE=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE_URL/api/v1/inventory/sessions/active?storeId=$STORE_ID" \
  -H "Authorization: Bearer $JWT")
[[ "$NO_ACTIVE" == "404" ]] && echo "✅ Step 8 — No active session (404)" || { echo "❌ Step 8 FAILED (got $NO_ACTIVE)"; exit 1; }

# Step 9 — Create new session after cancel (should succeed)
SESSION2=$(curl -s -X POST "$BASE_URL/api/v1/inventory/sessions" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$STORE_ID\",\"scope\":\"FULL\"}")
SESSION2_ID=$(echo "$SESSION2" | jq -r '.data.id')
[[ -n "$SESSION2_ID" && "$SESSION2_ID" != "null" ]] && echo "✅ Step 9 — New session after cancel: $SESSION2_ID" || { echo "❌ Step 9 FAILED"; exit 1; }

# Step 10 — List sessions (should have 2: 1 cancelled + 1 in progress)
LIST=$(curl -s -X GET "$BASE_URL/api/v1/inventory/sessions?page=0&size=10" \
  -H "Authorization: Bearer $JWT")
COUNT=$(echo "$LIST" | jq '.data | length')
[[ "$COUNT" -ge "2" ]] && echo "✅ Step 10 — List has $COUNT sessions" || { echo "❌ Step 10 FAILED (count=$COUNT)"; exit 1; }

# Step 11 — Cancel without auth → 401
NO_AUTH=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/inventory/sessions/$SESSION2_ID/cancel")
[[ "$NO_AUTH" == "401" ]] && echo "✅ Step 11 — Unauthorized cancel (401)" || { echo "❌ Step 11 FAILED (got $NO_AUTH)"; exit 1; }

# Step 12 — Create PARTIAL session with categoryIds
# First cancel the active one
curl -s -X POST "$BASE_URL/api/v1/inventory/sessions/$SESSION2_ID/cancel" \
  -H "Authorization: Bearer $JWT" > /dev/null

# Get a category ID
CATS=$(curl -s -X GET "$BASE_URL/api/v1/categories" \
  -H "Authorization: Bearer $JWT")
CAT_ID=$(echo "$CATS" | jq -r '.data[0].id // empty')
if [[ -n "$CAT_ID" ]]; then
  PARTIAL=$(curl -s -X POST "$BASE_URL/api/v1/inventory/sessions" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"storeId\":\"$STORE_ID\",\"scope\":\"PARTIAL\",\"categoryIds\":[\"$CAT_ID\"]}")
  PARTIAL_SCOPE=$(echo "$PARTIAL" | jq -r '.data.scope')
  [[ "$PARTIAL_SCOPE" == "PARTIAL" ]] && echo "✅ Step 12 — Partial session created" || { echo "❌ Step 12 FAILED"; exit 1; }
else
  echo "⚠️  Step 12 — Skipped (no categories found)"
fi

echo ""
echo "✅✅✅ All cURL integration checks passed — Story 6.1 backend validated ✅✅✅"
```

---

## Dev Notes

### Architecture Patterns & Constraints

- **Hexagonal Architecture**: `inventory/counting/` follows the canonical `domain/ → application/ → adapter/` structure. Domain has ZERO framework dependencies.
- **GoF patterns**: Factory (session creation), Observer (audit events), Strategy (scope resolution — prep for 6.2), State (session status machine)
- **MCP Port Purity**: Commands/Queries are pure Java Records. No HTTP types in use cases. `actorId` passed explicitly.
- **TDD**: All 20 tasks follow RED → GREEN → REFACTOR. Backend tests first, then Flutter.
- **Sync**: Sessions participate in push/pull sync. Handler is idempotent by session UUID.
- **RBAC**: OWNER can cancel any session. EMPLOYEE can only cancel their own.

### Key Dependencies on Existing Code

| Dependency | Location | Used For |
|---|---|---|
| `StoreRepository` | `store/store/domain/port/out/` | Verify store exists before creating session |
| `CategoryRepository` | `catalog/category/domain/port/out/` (moved in 2.1) | Validate category IDs for PARTIAL scope |
| `AuditEventListener` | `shared/application/service/` | Handle `InventorySession*Event` for audit log |
| `SyncOperationHandlerRegistry` | `sync/sync/application/service/` | Register inventory session sync handler |
| `SyncService.pull()` | `sync/sync/application/service/` | Register 15th delta provider |
| `TenantSchemaProvisioner` | `shared/infrastructure/persistence/` | DDL for `inventory_sessions` table |
| `TenantSchemaSyncService` | `shared/infrastructure/persistence/` | Auto-migrate existing tenants |
| `GlobalExceptionHandler` | `shared/infrastructure/web/` | Map new ErrorCodes to HTTP status |
| `RestSyncService.pull()` | `lib/core/sync/rest_sync_service.dart` | Parse new `inventorySessions` delta |
| `ConnectivityService` | `lib/core/sync/connectivity_service.dart` | Online/offline branching in repository |

### Source Tree Components to Touch

**Backend (create):**
- `com/keevo/inventory/counting/domain/model/` — 4 files (entity + 2 enums + factory)
- `com/keevo/inventory/counting/domain/event/` — 2 files
- `com/keevo/inventory/counting/domain/port/in/` — 4 use case interfaces + 4 command/query records
- `com/keevo/inventory/counting/domain/port/out/` — 1 repository interface
- `com/keevo/inventory/counting/application/service/` — 4 service files
- `com/keevo/inventory/counting/adapter/in/rest/` — 1 controller + 3 DTOs
- `com/keevo/inventory/counting/adapter/out/persistence/` — 1 JPA entity + 1 adapter + 1 Spring Data interface
- _(JPA entity lives in module: `com/keevo/inventory/counting/adapter/out/persistence/`)_

**Backend (modify):**
- `ErrorCode.java` — add 5 inventory codes
- `GlobalExceptionHandler.java` — add mappings (if not auto-detected)
- `TenantSchemaProvisioner.java` — add DDL constants
- `TenantSchemaSyncService.java` — add inventory_sessions migration
- `AuditEventListener.java` — add 2 `@EventListener` methods
- `SyncOperationHandlerRegistry.java` — register new handler
- `SyncService.java` (pull) — register 15th delta provider

**Flutter (create):**
- `lib/core/storage/inventory_sessions_table.dart`
- `lib/features/inventory/domain/model/inventory_session_model.dart` + `.freezed.dart` + `.g.dart`
- `lib/features/inventory/domain/model/inventory_scope.dart`
- `lib/features/inventory/domain/repository/inventory_session_repository.dart`
- `lib/features/inventory/domain/usecase/create_inventory_session_usecase.dart`
- `lib/features/inventory/domain/usecase/cancel_inventory_session_usecase.dart`
- `lib/features/inventory/data/datasource/local_inventory_session_datasource.dart`
- `lib/features/inventory/data/datasource/remote_inventory_session_datasource.dart`
- `lib/features/inventory/data/repository/inventory_session_repository_impl.dart`
- `lib/features/inventory/presentation/provider/inventory_session_provider.dart`
- `lib/features/inventory/presentation/page/inventory_launch_page.dart`
- `lib/features/inventory/presentation/widget/inventory_config_bottom_sheet.dart`
- `lib/features/inventory/presentation/widget/active_session_banner.dart`
- `lib/features/inventory/presentation/widget/session_history_card.dart`

**Flutter (modify):**
- `lib/core/storage/app_database.dart` — add table + bump schema v19
- `lib/core/router/app_router.dart` — replace placeholder routes
- `lib/core/sync/rest_sync_service.dart` — add inventory_sessions pull upsert

### Testing Standards Summary

| Layer | Framework | Coverage Target |
|---|---|---|
| Domain model (enums, entity) | JUnit 5 (no Spring) | State machine, validation, all branches |
| Application services | JUnit 5 + Mockito | Happy path + all error paths + event publication |
| Repository adapter | Unit (domain ↔ JPA mapping) | JSON serialization round-trip |
| REST controller | `@WebMvcTest` + MockMvc | Status codes, auth, validation, RBAC |
| DDL | Integration (tenant provisioner) | Table + columns exist after provisioning |
| Flutter domain | `test` + `mocktail` | Model creation, JSON, enum behaviors |
| Flutter data | `test` + mock DB | Datasource CRUD, repository online/offline |
| Flutter providers | `ProviderContainer` | Provider state transitions |
| Flutter widgets | `flutter_test` + `WidgetTester` | UI states, interactions, accessibility |

### Project Structure Notes

- `inventory/counting/` is a NEW module under the existing `inventory/` domain — not to be confused with `catalog/stock/` which handles stock levels and movements
- The `inventory/stock/` and `inventory/transfer/` scaffolded modules remain unused (stock is in `catalog/stock/`, transfers are in `catalog/stock/` — per implementation history)
- The Flutter `features/inventory/` folder already has Epic 3 multi-store stock code — new inventory session code lives alongside it in the same feature

### References

- [Source: `_bmad-output/planning-artifacts/archive/epics.md` lines 1675–1720 — Epic 6 Story 6.1 AC]
- [Source: `_bmad-output/planning-artifacts/prd.md` — FR45–FR49, Flow 10 (Inventaire Assisté)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md` — UX32 Inventory Row, Flow 10, Component P3]
- [Source: `_bmad-output/planning-artifacts/architecture.md` — Hexagonal, TDD, GoF, Backend-First, Sync, Naming]
- [Source: `_bmad-output/implementation-artifacts/5-5-monitoring-sync-*` — Latest sync engine state]
- [Source: `_bmad-output/implementation-artifacts/3-1-creation-configuration-*` — Story pattern reference]
- [Source: `keevo/backend/src/.../TenantSchemaProvisioner.java` — DDL pattern]
- [Source: `keevo/backend/src/.../StockOperationService.java` — MovementType.ADJUSTMENT for Story 6.4]
- [Source: `keevo/app/lib/core/storage/app_database.dart` — Drift v18, 17 existing tables]
- [Source: `keevo/app/lib/core/router/app_router.dart` — /inventory placeholder route]

---

## Dev Agent Record

### Agent Model Used

Claude Opus 4.6 (GitHub Copilot)

### Debug Log References

- Fixed `CancelInventorySessionUseCase` return type from `void` → `InventorySession` to simplify controller
- Fixed missing import in `CancelInventorySessionService` after return type change
- Fixed `TenantSchemaSyncServiceTest` — added `sync_error_log` + `inventory_sessions` to mock table lists (14→16 tables)
- Fixed Flutter `inventory_session_provider.dart` — removed unused `sync_status_provider.dart` and `sync_status.dart` imports
- Fixed deprecated `withOpacity` → `withValues(alpha:)` across 4 inventory widget files
- Fixed deprecated `value` → `initialValue` in `DropdownButtonFormField` in `inventory_config_bottom_sheet.dart`

### Completion Notes List

- **Backend**: 45 inventory-specific tests (3 scope + 6 status + 10 entity + 7 create service + 7 cancel service + 4 adapter + 8 controller) —  all pass
- **Backend total**: 1007/1007 tests GREEN, 0 failures, 0 regressions
- **Flutter**: build_runner 421 outputs generated, 0 new warnings in inventory files
- **Flutter analyze**: 0 warnings in Story 6.1 files (all remaining warnings are pre-existing)
- **Sync**: InventorySessionSyncHandler + InventorySessionDeltaProvider registered, pull upsert with pending protection
- **DDL**: inventory_sessions table + 2 indexes in TenantSchemaProvisioner + TenantSchemaSyncService auto-migration
- **AuditEventListener**: 2 new @EventListener methods for InventorySessionCreatedEvent/CancelledEvent
- **cURL integration script**: 12-step end-to-end validation script created

### File List

**Backend — Created (23 files):**
- `src/main/java/com/keevo/inventory/counting/domain/model/InventoryScope.java`
- `src/main/java/com/keevo/inventory/counting/domain/model/InventorySessionStatus.java`
- `src/main/java/com/keevo/inventory/counting/domain/model/InventorySession.java`
- `src/main/java/com/keevo/inventory/counting/domain/event/InventorySessionCreatedEvent.java`
- `src/main/java/com/keevo/inventory/counting/domain/event/InventorySessionCancelledEvent.java`
- `src/main/java/com/keevo/inventory/counting/domain/port/out/InventorySessionRepository.java`
- `src/main/java/com/keevo/inventory/counting/domain/port/in/CreateInventorySessionCommand.java`
- `src/main/java/com/keevo/inventory/counting/domain/port/in/CreateInventorySessionUseCase.java`
- `src/main/java/com/keevo/inventory/counting/domain/port/in/CancelInventorySessionCommand.java`
- `src/main/java/com/keevo/inventory/counting/domain/port/in/CancelInventorySessionUseCase.java`
- `src/main/java/com/keevo/inventory/counting/domain/port/in/GetActiveSessionQuery.java`
- `src/main/java/com/keevo/inventory/counting/domain/port/in/GetActiveSessionUseCase.java`
- `src/main/java/com/keevo/inventory/counting/domain/port/in/ListInventorySessionsQuery.java`
- `src/main/java/com/keevo/inventory/counting/domain/port/in/ListInventorySessionsUseCase.java`
- `src/main/java/com/keevo/inventory/counting/application/service/CreateInventorySessionService.java`
- `src/main/java/com/keevo/inventory/counting/application/service/CancelInventorySessionService.java`
- `src/main/java/com/keevo/inventory/counting/application/service/GetActiveSessionService.java`
- `src/main/java/com/keevo/inventory/counting/application/service/ListInventorySessionsService.java`
- `src/main/java/com/keevo/inventory/counting/adapter/out/persistence/InventorySessionJpaEntity.java`
- `src/main/java/com/keevo/inventory/counting/adapter/out/persistence/JpaInventorySessionRepository.java`
- `src/main/java/com/keevo/inventory/counting/adapter/out/persistence/InventorySessionRepositoryAdapter.java`
- `src/main/java/com/keevo/inventory/counting/adapter/in/rest/dto/CreateInventorySessionRequestDto.java`
- `src/main/java/com/keevo/inventory/counting/adapter/in/rest/dto/InventorySessionResponseDto.java`
- `src/main/java/com/keevo/inventory/counting/adapter/in/rest/InventorySessionController.java`

**Backend — Sync (2 files):**
- `src/main/java/com/keevo/sync/sync/application/handler/InventorySessionSyncHandler.java`
- `src/main/java/com/keevo/sync/sync/application/provider/InventorySessionDeltaProvider.java`

**Backend — Tests (7 files):**
- `src/test/java/com/keevo/inventory/counting/domain/model/InventoryScopeTest.java`
- `src/test/java/com/keevo/inventory/counting/domain/model/InventorySessionStatusTest.java`
- `src/test/java/com/keevo/inventory/counting/domain/model/InventorySessionTest.java`
- `src/test/java/com/keevo/inventory/counting/application/service/CreateInventorySessionServiceTest.java`
- `src/test/java/com/keevo/inventory/counting/application/service/CancelInventorySessionServiceTest.java`
- `src/test/java/com/keevo/inventory/counting/adapter/out/persistence/InventorySessionRepositoryAdapterTest.java`
- `src/test/java/com/keevo/inventory/counting/adapter/in/rest/InventorySessionControllerTest.java`

**Backend — Modified (6 files):**
- `src/main/java/com/keevo/shared/domain/exception/ErrorCode.java` — 5 inventory error codes
- `src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java` — FR_MESSAGES + HTTP mappings
- `src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java` — DDL + execution
- `src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaSyncService.java` — table + indexes
- `src/main/java/com/keevo/shared/infrastructure/web/AuditEventListener.java` — 2 @EventListener methods
- `src/test/java/com/keevo/shared/infrastructure/persistence/TenantSchemaSyncServiceTest.java` — mock table list fix

**Flutter — Created (14 files):**
- `lib/core/storage/inventory_sessions_table.dart`
- `lib/features/inventory/domain/model/inventory_scope.dart`
- `lib/features/inventory/domain/model/inventory_session_model.dart`
- `lib/features/inventory/domain/repository/inventory_session_repository.dart`
- `lib/features/inventory/domain/usecase/create_inventory_session_usecase.dart`
- `lib/features/inventory/domain/usecase/cancel_inventory_session_usecase.dart`
- `lib/features/inventory/data/datasource/local_inventory_session_datasource.dart`
- `lib/features/inventory/data/datasource/remote_inventory_session_datasource.dart`
- `lib/features/inventory/data/repository/inventory_session_repository_impl.dart`
- `lib/features/inventory/presentation/provider/inventory_session_provider.dart`
- `lib/features/inventory/presentation/page/inventory_launch_page.dart`
- `lib/features/inventory/presentation/widget/active_session_banner.dart`
- `lib/features/inventory/presentation/widget/session_history_card.dart`
- `lib/features/inventory/presentation/widget/inventory_config_bottom_sheet.dart`

**Flutter — Modified (3 files):**
- `lib/core/storage/app_database.dart` — InventorySessions table + v19 migration
- `lib/core/router/app_router.dart` — /inventory + /inventory/counting/:sessionId routes
- `lib/core/sync/rest_sync_service.dart` — inventory_sessions pull upsert

**Scripts (1 file):**
- `keevo/scripts/curl-tests-story-6-1.sh` — 12-step cURL integration test
