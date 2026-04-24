# Story 5.6: Offline-First Writes — UX Instantanée pour Opérations Transactionnelles

**Status:** done

## Story

As a merchant (Simon or an employee),
I want every transactional action (sale, product, category, client, supplier, stock movement, stock transfer)
to respond instantly regardless of network conditions,
so that daily operations are never slowed down by internet latency or connectivity issues.

---

## ⚠️ CRITICAL ARCHITECTURAL CONTEXT — Infrastructure déjà opérationnelle

> **Story 5.1 (DONE): push pipeline — offline queue → batch POST /api/v1/sync/push**
> **Story 5.2 (DONE): pull pipeline — GET /api/v1/sync/pull → delta merge → Drift upsert**
> **Story 5.3 (DONE): conflict resolution — DELTA_SUM + LWW + AppendOnly strategies**
> **Story 5.4 (DONE): temporal gate — 7-day offline limit + write blocking**
> **Story 5.5 (DONE): monitoring, diagnostic, multi-device coherence**
> **Story 5.6 (THIS STORY): offline-first write pattern on ALL transactional repositories**

### Current State — Ce qui existe déjà

```
┌──────────────────────────────────────────────────────────────────────┐
│ SYNC INFRASTRUCTURE (fully operational after 5.1–5.5)               │
│                                                                      │
│ SyncTriggerNotifier (keepAlive):                                     │
│   triggerPush()        — push pending ops NOW (background)           │
│   triggerSync()        — push → pull cycle                           │
│   _periodicPushTimer   — 30s check pending ops                       │
│   _periodicSyncTimer   — 5min full pull cycle (multi-device)         │
│   Retry backoff        — 2^n × 1000ms + ±20% jitter, max 5 min      │
│   Connectivity trigger — triggerPush() on network restore            │
│                                                                      │
│ SyncService.queueOperation() — enqueue op into sync_queue Drift      │
│ SyncService.push()           — flush queue to backend                │
│ SyncService.hasPendingOperations() — guard for reads                 │
│                                                                      │
│ RestSyncService backend handlers:                                    │
│   CREATE_SALE, VALIDATE_SALE, CANCEL_SALE                            │
│   CREATE_PRODUCT, UPDATE_PRODUCT, ARCHIVE_PRODUCT, UNARCHIVE_PRODUCT │
│   UPDATE_CLIENT, ARCHIVE_CLIENT                                      │
│   UPDATE_SUPPLIER, ARCHIVE_SUPPLIER                                  │
│   RECORD_STOCK_ENTRY, STOCK_ADJUST                                   │
│   STOCK_TRANSFER                                                     │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│ CURRENT WRITE STRATEGIES (TO BE MIGRATED IN THIS STORY)             │
│                                                                      │
│ SaleRepositoryImpl:     BACKEND-FIRST when online                    │
│   recordSale() → if online: remote.pushSale() THEN local.insert()   │
│   → problème: bloque UI pendant latence réseau                       │
│                                                                      │
│ ProductRepositoryImpl:  BACKEND-FIRST write-through                  │
│   create() → remote.create() THEN local.upsert()                    │
│   update() → remote.update() THEN local.update()                    │
│   archive()/unarchive() → remote THEN local                          │
│                                                                      │
│ CategoryRepositoryImpl: ONLINE-ONLY writes                           │
│   createCustomCategory() → api.post() THEN local fallback            │
│   toggleCategoryStatus() → api.patch() only                          │
│   renameCategory()       → api.patch() only                          │
│                                                                      │
│ ClientRepositoryImpl:   BACKEND-FIRST                                │
│   create() → remote.create() THEN local.upsert() (id=millis fallback)│
│   update() → remote THEN local (with sync queue)                     │
│                                                                      │
│ SupplierRepositoryImpl: BACKEND-FIRST                                │
│   create() → remote.create() THEN local.upsert() (id=millis fallback)│
│   update() → remote THEN local (with sync queue)                     │
│                                                                      │
│ StockRepositoryImpl:    ONLINE-FIRST (reads go to network first)     │
│   recordEntry() → if online: remote THEN local cache; else: queue    │
│   adjustStock() → if online: remote THEN local cache; else: queue    │
│                                                                      │
│ StockTransferRepositoryImpl: BACKEND-FIRST when online               │
│   executeTransfer() → if online: remote.create() THEN local          │
└──────────────────────────────────────────────────────────────────────┘
```

### Ce que Story 5.6 apporte

```
┌──────────────────────────────────────────────────────────────────────┐
│ STORY 5.6 — OFFLINE-FIRST WRITES ON ALL TRANSACTIONAL REPOS         │
│                                                                      │
│ PATTERN CIBLE UNIVERSEL (pour tous les repos ci-dessous):            │
│                                                                      │
│   async write(data) {                                                │
│     1. local.write(data)          ← INSTANTANÉ (Drift SQLite)        │
│     2. syncService.queueOperation(...) ← Enqueue op                 │
│     3. if (online) syncTrigger.triggerPush()  ← Silent bg sync      │
│     return  ← retourné immédiatement (UX instantanée)               │
│   }                                                                  │
│                                                                      │
│ REPOS MODIFIÉS:                                                      │
│   SaleRepositoryImpl       → recordSale()                            │
│   ProductRepositoryImpl    → create(), update(), archive(),          │
│                               unarchive()                            │
│   CategoryRepositoryImpl   → createCustomCategory(),                 │
│                               toggleCategoryStatus(),                │
│                               renameCategory()                       │
│   ClientRepositoryImpl     → create(), update()                      │
│   SupplierRepositoryImpl   → create(), update()                      │
│   StockRepositoryImpl      → recordEntry(), adjustStock()            │
│   StockTransferRepositoryImpl → executeTransfer()                    │
│                                                                      │
│ NOTE: validateSale(), cancelSale(), completeTransfer() sont déjà     │
│ offline-first ou ont une logique complexe (stock decrement, remote   │
│ authority) — voir section par repo pour les cas exclus.             │
│                                                                      │
│ INJECTION DU SyncTriggerNotifier:                                    │
│   Les repos n'ont pas accès à Riverpod Ref directement.              │
│   Solution: passer un callback `VoidCallback? onQueued` au repo,     │
│   OR injecter une abstraction `SyncDispatcher` via DI providers.     │
│   Voir AC6 pour le pattern exact retenu.                             │
└──────────────────────────────────────────────────────────────────────┘
```

---

## GoF Pattern Analysis

| Question | Answer |
|---|---|
| What variability exists? | 7 repos avec des write paths différentes. La logique « écrire local → queue → trigger » est identique, seuls le payload et l'opération changent. |
| What might change? | Nouvelle entité transactionnelle (ex: day closure offline), priorité de sync (urgente vs normale), stratégie pessimiste/optimiste par entité. |
| Which GoF pattern(s) apply? | **Strategy** — `OfflineFirstWriteStrategy` interface unifiée. **Template Method** (implicite) — chaque repo suit le même squelette local→queue→trigger, différant seulement dans `_buildPayload()`. |
| OCP compliance? | Ajouter un nouveau repo offline-first = implémenter le pattern sans modifier l'infrastructure. |
| Where applied? | Le pattern est inline dans chaque repo (Template Method naturel Dart). Pas de classe abstraite nécessaire pour MVP. |

---

## Acceptance Criteria

### AC1 — SaleRepositoryImpl: recordSale() offline-first

- **Given** un employé enregistre une vente (connecté ou non)
- **When** `recordSale(sale)` est appelé
- **Then** `_local.insertAll(sale, synced: false)` est exécuté **en premier**, sans attendre le réseau
- **And** `_syncService.queueOperation(operation: 'CREATE_SALE', ...)` est appelé immédiatement
- **And** si `_connectivity.isOnline()`, `_syncTriggerDispatcher.triggerPushIfIdle()` est appelé en background (sans await dans le chemin critique)
- **And** la vente est visible dans `getSalesForToday()` **instantanément** après l'appel
- **And** la méthode `pushSale()` distante n'est plus appelée directement depuis `recordSale()`
- **Excluded:** `validateSale()` et `cancelSale()` ne changent **pas** (ils ont une logique de stock decrement et d'autorité serveur spécifique — voir note en fin de story)

### AC2 — ProductRepositoryImpl: create(), update(), archive(), unarchive() offline-first

- **Given** un marchand crée / modifie / archive un produit (connecté ou non)
- **When** la méthode correspondante est appelée
- **Then** pour `create()`:
  - Un UUID local est généré via `const Uuid().v4()` (déjà importé)
  - `_local.upsert(product)` est exécuté en premier avec cet UUID
  - `_syncService.queueOperation(operation: 'CREATE_PRODUCT', ...)` est appelé
  - Trigger push si online
  - **Important:** la vérification de nom dupliqué (`_local.search()`) est conservée **avant** l'écriture locale
- **Then** pour `update()`:
  - `_local.update(product)` en premier
  - `queueOperation('UPDATE_PRODUCT', ...)`
  - Trigger push si online
- **Then** pour `archive()` / `unarchive()`:
  - `_local.archive()` / `_local.unarchive()` en premier
  - `queueOperation('ARCHIVE_PRODUCT', ...)` / `queueOperation('UNARCHIVE_PRODUCT', ...)`
  - Trigger push si online
- **Excluded:** `promoteToActive()`, `importCsv()`, `createDraft()`, `syncFromRemote()` ne changent **pas**

### AC3 — CategoryRepositoryImpl: createCustomCategory(), toggleCategoryStatus(), renameCategory() offline-first

- **Given** un marchand crée une catégorie custom / active-désactive / renomme (connecté ou non)
- **When** la méthode correspondante est appelée
- **Then** pour `createCustomCategory()`:
  - UUID local généré avec `const Uuid().v4()`
  - Insertion dans Drift (`categories` table) d'abord
  - `queueOperation('CREATE_CATEGORY', {'id': uuid, 'name': name, 'parentId': parentId})`
  - Trigger push si online
- **Then** pour `toggleCategoryStatus()`:
  - Mise à jour locale `isActive` en Drift d'abord
  - `queueOperation('TOGGLE_CATEGORY', {'id': id, 'isActive': newValue})`
  - Trigger push si online
- **Then** pour `renameCategory()`:
  - Mise à jour locale `name` en Drift d'abord
  - `queueOperation('RENAME_CATEGORY', {'id': id, 'name': newName})`
  - Trigger push si online
- **Note:** `CREATE_CATEGORY`, `TOGGLE_CATEGORY`, `RENAME_CATEGORY` sont de **nouveaux** types d'opérations sync — le backend handler doit les supporter (voir AC9)
- **Excluded:** `deleteCategory()`, `syncFromApi()` ne changent **pas**

### AC4 — ClientRepositoryImpl: create(), update() offline-first

- **Given** un merchand crée ou modifie un client (connecté ou non)
- **When** `create()` ou `update()` est appelé
- **Then** pour `create()`:
  - UUID généré avec `const Uuid().v4()` (remplace l'ID `millisecondsSinceEpoch`)
  - `_local.upsert(client)` en premier
  - `queueOperation('CREATE_CLIENT', {'id': uuid, 'name': name, 'phone': phone, ...})`
  - Trigger push si online
- **Then** pour `update()`:
  - `_local.upsert(updatedClient)` en premier (copyWith depuis existing)
  - `queueOperation('UPDATE_CLIENT', ...)` — déjà présent dans le chemin offline, unifié ici
  - Trigger push si online
- **Note:** `CREATE_CLIENT` est un **nouveau** type d'opération sync (le handler backend doit être ajouté — voir AC9)

### AC5 — SupplierRepositoryImpl: create(), update() offline-first

- **Given** un merchand crée ou modifie un fournisseur (connecté ou non)
- **When** `create()` ou `update()` est appelé
- **Then** pour `create()`:
  - UUID généré avec `const Uuid().v4()` (remplace `millisecondsSinceEpoch`)
  - `_local.upsert(supplier)` en premier (avec `productIds` merge logic préservée)
  - `queueOperation('CREATE_SUPPLIER', {'id': uuid, 'name': name, 'phone': phone, 'productIds': [...]})`
  - Trigger push si online
- **Then** pour `update()`:
  - `_local.upsert(updatedSupplier)` en premier
  - `queueOperation('UPDATE_SUPPLIER', ...)` — unifié ici
  - Trigger push si online
- **Note:** `CREATE_SUPPLIER` est un **nouveau** type d'opération sync (handler backend requis — voir AC9)

### AC6 — SyncTriggerDispatcher: injection du trigger dans les repos (ARCHITECTURE PATTERN)

- **Given** les repos Flutter (`SaleRepositoryImpl`, `ProductRepositoryImpl`, etc.) n'ont pas accès à Riverpod `Ref`
- **When** un write offline-first est effectué et que la connexion est disponible
- **Then** les repos utilisent une abstraction `SyncTriggerDispatcher` injectée via constructeur:
  ```dart
  // lib/core/sync/sync_trigger_dispatcher.dart (NOUVEAU FICHIER)
  abstract interface class SyncTriggerDispatcher {
    /// Triggers a background push if the notifier is idle.
    /// Non-blocking: fire-and-forget. No-op if offline or already syncing.
    void triggerPushIfIdle();
  }

  // lib/core/sync/riverpod_sync_trigger_dispatcher.dart (NOUVEAU FICHIER)
  class RiverpodSyncTriggerDispatcher implements SyncTriggerDispatcher {
    final Ref _ref;
    final ConnectivityService _connectivity;

    const RiverpodSyncTriggerDispatcher(this._ref, this._connectivity);

    @override
    void triggerPushIfIdle() {
      // Fire-and-forget: do NOT await, this is purely background
      Future.microtask(() async {
        if (await _connectivity.isOnline()) {
          _ref.read(syncTriggerNotifierProvider.notifier).triggerPush();
        }
      });
    }
  }
  ```
- **And** un `syncTriggerDispatcherProvider` est créé dans `providers.dart`:
  ```dart
  final syncTriggerDispatcherProvider = Provider<SyncTriggerDispatcher>((ref) {
    return RiverpodSyncTriggerDispatcher(
      ref,
      ref.read(connectivityServiceProvider),
    );
  });
  ```
- **And** chaque repo impacté reçoit un paramètre `required SyncTriggerDispatcher syncTriggerDispatcher` dans son constructeur
- **And** le provider du repo dans `providers.dart` injecte `ref.read(syncTriggerDispatcherProvider)`

### AC7 — StockRepositoryImpl: recordEntry(), adjustStock() offline-first

- **Given** un marchand enregistre une entrée de stock ou ajuste un stock (connecté ou non)
- **When** `recordEntry()` ou `adjustStock()` est appelé
- **Then** pour `recordEntry()`:
  - `_local.upsertLevel(...)` (mise à jour compteur local) en premier
  - `_local.insertMovement(movement)` en premier (historique local)
  - `queueOperation('RECORD_STOCK_ENTRY', ...)` immédiatement
  - Trigger push si online
  - **Plus** de `_remote.recordEntry()` dans le chemin critque
- **Then** pour `adjustStock()`:
  - `_local.upsertLevel(...)` en premier
  - `_local.insertMovement(movement)` en premier
  - `queueOperation('STOCK_ADJUST', ...)`
  - Trigger push si online
- **Note:** `getLevels()`, `getLevelByStore()`, `getCrossStoreAvailability()` ne changent **pas** (reads uniquement)

### AC8 — StockTransferRepositoryImpl: executeTransfer() offline-first

- **Given** un marchand initie un transfert inter-boutiques (connecté ou non)
- **When** `executeTransfer()` est appelé
- **Then**:
  - Validation locale du stock source (déjà présente dans `_offlineTransfer()`) est exécutée dans **tous les cas**
  - `_local.applyLocalStockChange()` est exécuté en premier
  - Transfer créé en local avec statut `PENDING_SYNC`
  - `queueOperation('STOCK_TRANSFER', ...)` immédiatement
  - Trigger push si online
  - L'ancienne branche "remote-first when online" (`_remote.create()` conditionnel) est supprimée — le path devient unique
- **Note:** `completeTransfer()` reste **inchangé** (il requiert confirmation distante — statut `PENDING` → `COMPLETED` via server authority)

### AC9 — Backend: nouveaux handlers sync pour CREATE_CATEGORY, TOGGLE_CATEGORY, RENAME_CATEGORY, CREATE_CLIENT, CREATE_SUPPLIER

- **Given** de nouveaux types d'opérations sync sont introduits par les repos offline-first
- **When** le backend reçoit un batch sync push contenant ces opérations
- **Then** des handlers sont ajoutés dans `SyncOperationHandlerRegistry`:

  **CREATE_CATEGORY handler:**
  ```java
  // Upsert via CategoryRepository.save() (tenant schema)
  // Idempotent: INSERT category where id = operationPayload.id (conflict = no-op)
  // Fields: id (UUID from client), name, parentId (nullable), isActive=true, isCustom=true
  ```

  **TOGGLE_CATEGORY handler:**
  ```java
  // PATCH via CategoryRepository.toggleActive(id)
  // Fields: id, isActive (boolean)
  // If category not found: return APPLIED (idempotent — may already be deleted)
  ```

  **RENAME_CATEGORY handler:**
  ```java
  // UPDATE via CategoryRepository.updateName(id, name)
  // If category not found: return APPLIED (idempotent)
  ```

  **CREATE_CLIENT handler:**
  ```java
  // Upsert via ClientRepository.save() (tenant schema)
  // Idempotent: INSERT where id = operationPayload.id
  // Fields: id (UUID), name, phone, email (nullable), notes (nullable)
  ```

  **CREATE_SUPPLIER handler:**
  ```java
  // Upsert via SupplierRepository.save() (tenant schema)
  // Idempotent: INSERT where id = operationPayload.id
  // Fields: id (UUID), name, phone, email (nullable)
  // Product associations: handle productIds array (insert supplier_products junction rows)
  ```

- **And** chaque handler est enregistré dans `SyncOperationHandlerRegistry` avec son opération string
- **And** les handlers sont idempotents (re-envoi du même `operationId` = no-op via `SyncOperationsLogRepository.existsById()`)

### AC10 — Tests Flutter : offline-first behavior

#### Unit Tests (flutter_test)
- `sale_repository_offline_first_test.dart`:
  - `recordSale_alwaysWritesLocalFirst_beforeAnyNetwork()`
  - `recordSale_queuesCreateSale_operation()`
  - `recordSale_whenOnline_triggersPushInBackground()`
  - `recordSale_isImmediatelyVisibleInGetSalesForToday()`
- `product_repository_offline_first_test.dart`:
  - `create_writesLocalFirst_withGeneratedUUID()`
  - `create_queuesCreateProduct()`
  - `create_duplicateName_throwsProductException_beforeWrite()`
  - `update_writesLocalFirst_queuesUpdateProduct()`
  - `archive_writesLocalFirst_queuesArchiveProduct()`
- `category_repository_offline_first_test.dart`:
  - `createCustomCategory_writesLocalFirst_queuesCreateCategory()`
  - `toggleCategoryStatus_writesLocalFirst_queuesToggleCategory()`
  - `renameCategory_writesLocalFirst_queuesRenameCategory()`
- `client_repository_offline_first_test.dart`:
  - `create_writesLocalFirst_withUUID_queuesCreateClient()`
  - `update_writesLocalFirst_queuesUpdateClient()`
- `supplier_repository_offline_first_test.dart`:
  - `create_writesLocalFirst_withUUID_queuesCreateSupplier()`
  - `update_writesLocalFirst_queuesUpdateSupplier()`
- `stock_repository_offline_first_test.dart`:
  - `recordEntry_writesLocalFirst_queuesRecordStockEntry()`
  - `adjustStock_writesLocalFirst_queuesStockAdjust()`
- `stock_transfer_repository_offline_first_test.dart`:
  - `executeTransfer_validatesLocalStock_orThrows()`
  - `executeTransfer_writesLocalFirst_queuesStockTransfer()`

#### Contraintes de tests:
- Les mocks `MockSyncService`, `MockConnectivityService`, `MockSyncTriggerDispatcher` doivent être utilisés
- Vérifier l'ordre d'appel : **`_local.write()` doit être appelé AVANT `queueOperation()`**
- Vérifier que `triggerPushIfIdle()` est appelé quand `MockConnectivityService.isOnline()` retourne `true`
- Vérifier que `triggerPushIfIdle()` N'est PAS appelé quand offline

### AC11 — Tests Backend : nouveaux handlers (AC9)

#### Unit Tests (JUnit 5)
- `CreateCategoryHandlerTest.java`:
  - `handle_newCategory_persists()`
  - `handle_duplicateId_isIdempotent()`
- `ToggleCategoryHandlerTest.java`:
  - `handle_existingCategory_togglesIsActive()`
  - `handle_categoryNotFound_returnsApplied()`
- `RenameCategoryHandlerTest.java`:
  - `handle_existingCategory_updatesName()`
  - `handle_notFound_returnsApplied()`
- `CreateClientHandlerTest.java`:
  - `handle_newClient_persists()`
  - `handle_duplicateId_isIdempotent()`
- `CreateSupplierHandlerTest.java`:
  - `handle_newSupplier_persists()`
  - `handle_withProductIds_associatesProducts()`
  - `handle_duplicateId_isIdempotent()`

---

## Tasks / Subtasks

> **LOI TDD : Tests RED écrits AVANT tout code de production. Le test doit échouer en premier.**
> **Order: AC6 (infrastructure) → AC1→AC8 (Flutter repos) → AC9 (backend) → AC10/AC11 (test completion)**

---

### Task 1 — TDD RED: Flutter — SyncTriggerDispatcher (AC6)

- [ ] **1.1** Créer `lib/core/sync/sync_trigger_dispatcher.dart`
  ```dart
  // Interface abstraite SyncTriggerDispatcher avec triggerPushIfIdle()
  ```
- [ ] **1.2** Créer `lib/core/sync/riverpod_sync_trigger_dispatcher.dart`
  ```dart
  // RiverpodSyncTriggerDispatcher implements SyncTriggerDispatcher
  // Injecte Ref + ConnectivityService
  // triggerPushIfIdle() = Future.microtask + isOnline check + notifier.triggerPush()
  ```
- [ ] **1.3** Créer `MockSyncTriggerDispatcher` dans les tests:
  ```dart
  // lib/test/mocks/mock_sync_trigger_dispatcher.dart
  class MockSyncTriggerDispatcher implements SyncTriggerDispatcher {
    int triggerCount = 0;
    @override
    void triggerPushIfIdle() => triggerCount++;
  }
  ```

---

### Task 2 — Implémenter SyncTriggerDispatcher + provider (AC6)

- [ ] **2.1** Créer `lib/core/sync/sync_trigger_dispatcher.dart` (interface)
- [ ] **2.2** Créer `lib/core/sync/riverpod_sync_trigger_dispatcher.dart` (implémentation)
- [ ] **2.3** Ajouter dans `lib/core/di/providers.dart`:
  ```dart
  final syncTriggerDispatcherProvider = Provider<SyncTriggerDispatcher>((ref) {
    return RiverpodSyncTriggerDispatcher(
      ref,
      ref.read(connectivityServiceProvider),
    );
  });
  ```

---

### Task 3 — TDD RED: Flutter unit tests offline-first repos (AC10)

- [ ] **3.1** Créer `test/features/pos/data/repository/sale_repository_offline_first_test.dart`
  - Tests: `recordSale_alwaysWritesLocalFirst_beforeAnyNetwork`, `recordSale_queuesCreateSale_operation`, `recordSale_whenOnline_triggersPushInBackground`, `recordSale_isImmediatelyVisibleInGetSalesForToday`
- [ ] **3.2** Créer `test/features/catalog/data/repository/product_repository_offline_first_test.dart`
  - Tests: `create_writesLocalFirst_withGeneratedUUID`, `create_queuesCreateProduct`, `create_duplicateName_throwsProductException_beforeWrite`, `update_writesLocalFirst_queuesUpdateProduct`, `archive_writesLocalFirst_queuesArchiveProduct`
- [ ] **3.3** Créer `test/features/catalog/data/repository/category_repository_offline_first_test.dart`
  - Tests: `createCustomCategory_writesLocalFirst_queuesCreateCategory`, `toggleCategoryStatus_writesLocalFirst_queuesToggleCategory`, `renameCategory_writesLocalFirst_queuesRenameCategory`
- [ ] **3.4** Créer `test/features/contact/data/repository/client_repository_offline_first_test.dart`
  - Tests: `create_writesLocalFirst_withUUID_queuesCreateClient`, `update_writesLocalFirst_queuesUpdateClient`
- [ ] **3.5** Créer `test/features/contact/data/repository/supplier_repository_offline_first_test.dart`
  - Tests: `create_writesLocalFirst_withUUID_queuesCreateSupplier`, `update_writesLocalFirst_queuesUpdateSupplier`
- [ ] **3.6** Créer `test/features/catalog/data/repository/stock_repository_offline_first_test.dart`
  - Tests: `recordEntry_writesLocalFirst_queuesRecordStockEntry`, `adjustStock_writesLocalFirst_queuesStockAdjust`
- [ ] **3.7** Créer `test/features/inventory/data/repository/stock_transfer_repository_offline_first_test.dart`
  - Tests: `executeTransfer_validatesLocalStock_orThrows`, `executeTransfer_writesLocalFirst_queuesStockTransfer`

---

### Task 4 — Migrer SaleRepositoryImpl vers offline-first (AC1)

- [ ] **4.1** Modifier `lib/features/pos/data/repository/sale_repository_impl.dart`
  - Ajouter `required SyncTriggerDispatcher syncTriggerDispatcher` au constructeur
  - Remplacer `recordSale()`:
    ```dart
    @override
    Future<void> recordSale(Sale sale) async {
      // OFFLINE-FIRST: local write is always first — instant UX
      await _local.insertAll(sale, synced: false);
      await _syncService.queueOperation(
        operation: 'CREATE_SALE',
        payload: _buildPayload(sale),
        entityId: sale.id,
      );
      // Silent background sync (fire-and-forget, no UI blocking)
      _syncTriggerDispatcher.triggerPushIfIdle();
    }
    ```
  - Supprimer: bloc `if (await _connectivity.isOnline())` et l'appel `_remote.pushSale()`
  - **Conserver sans modification:** `validateSale()`, `cancelSale()`, `getSalesHistory()`, `getPendingSales()`, `getSalesForToday()`, `getFrequentProductIds()`, `countPendingSales()`
- [ ] **4.2** Mettre à jour le provider `saleRepositoryProvider` dans `providers.dart` pour injecter `syncTriggerDispatcher`

---

### Task 5 — Migrer ProductRepositoryImpl vers offline-first (AC2)

- [ ] **5.1** Modifier `lib/features/catalog/data/repository/product_repository_impl.dart`
  - Ajouter `required SyncTriggerDispatcher syncTriggerDispatcher` au constructeur
  - Remplacer `create()`:
    ```dart
    @override
    Future<ProductModel> create({...}) async {
      // Local name uniqueness check (preserved)
      final existing = await _local.search(name);
      final duplicate = existing.any((p) => p.name.toLowerCase() == name.toLowerCase());
      if (duplicate) throw const ProductException(...);

      // OFFLINE-FIRST: generate UUID locally, write to Drift first
      final localId = const Uuid().v4();
      final product = ProductModel(id: localId, name: name, ...);
      await _local.upsert(product);
      await _syncService.queueOperation(
        operation: 'CREATE_PRODUCT',
        payload: {'id': localId, 'name': name, ...},
        entityId: localId,
      );
      _syncTriggerDispatcher.triggerPushIfIdle();
      return product;
    }
    ```
  - Remplacer `update()` (même pattern: local first, queue, trigger)
  - Remplacer `archive()` (local first, queue `ARCHIVE_PRODUCT`, trigger)
  - Remplacer `unarchive()` (local first, queue `UNARCHIVE_PRODUCT`, trigger)
  - **Conserver sans modification:** `promoteToActive()`, `importCsv()`, `createDraft()`, `countDrafts()`, `syncFromRemote()`, `getAll()`, `getArchived()`, `getById()`, `search()`
- [ ] **5.2** Mettre à jour le provider `productRepositoryProvider` dans `providers.dart`

---

### Task 6 — Migrer CategoryRepositoryImpl vers offline-first (AC3)

- [ ] **6.1** Modifier `lib/features/catalog/data/repository/category_repository_impl.dart`
  - Remplacer l'injection `ApiService` par: `LocalCategoryDataSource` (ou opérations Drift directes), `SyncService`, `SyncTriggerDispatcher`
  - **Note:** Si `LocalCategoryDataSource` n'existe pas, implémenter les opérations Drift directement dans le repo (déjà fait pour reads via `_database.select(_database.categories)`)
  - Remplacer `createCustomCategory()`:
    ```dart
    Future<CategoryModel> createCustomCategory(String name, {String? parentId}) async {
      final id = const Uuid().v4();
      final now = DateTime.now();
      await _database.into(_database.categories).insertOnConflictUpdate(
        CategoriesCompanion.insert(
          id: id, name: name,
          parentId: Value(parentId),
          isActive: const Value(true),
          isCustom: const Value(true),
          createdAt: now, updatedAt: now,
        ),
      );
      await _syncService.queueOperation(
        operation: 'CREATE_CATEGORY',
        payload: {'id': id, 'name': name, 'parentId': parentId},
        entityId: id,
      );
      _syncTriggerDispatcher.triggerPushIfIdle();
      return CategoryModel(id: id, name: name, parentId: parentId, isActive: true, isCustom: true);
    }
    ```
  - Remplacer `toggleCategoryStatus()`:
    ```dart
    Future<void> toggleCategoryStatus(String id) async {
      // Read current isActive from local DB
      final row = await (_database.select(_database.categories)..where((c) => c.id.equals(id))).getSingleOrNull();
      if (row == null) return;
      final newIsActive = !row.isActive;
      await (_database.update(_database.categories)..where((c) => c.id.equals(id)))
          .write(CategoriesCompanion(isActive: Value(newIsActive), updatedAt: Value(DateTime.now())));
      await _syncService.queueOperation(
        operation: 'TOGGLE_CATEGORY',
        payload: {'id': id, 'isActive': newIsActive},
        entityId: id,
      );
      _syncTriggerDispatcher.triggerPushIfIdle();
    }
    ```
  - Remplacer `renameCategory()`:
    ```dart
    Future<void> renameCategory(String id, String newName) async {
      await (_database.update(_database.categories)..where((c) => c.id.equals(id)))
          .write(CategoriesCompanion(name: Value(newName), updatedAt: Value(DateTime.now())));
      await _syncService.queueOperation(
        operation: 'RENAME_CATEGORY',
        payload: {'id': id, 'name': newName},
        entityId: id,
      );
      _syncTriggerDispatcher.triggerPushIfIdle();
    }
    ```
  - **Conserver sans modification:** `getLocalCategories()`, `getRootCategories()`, `getSubcategories()`, `syncFromApi()`, `deleteCategory()`
- [ ] **6.2** Mettre à jour le provider `categoryRepositoryProvider` dans `providers.dart`

---

### Task 7 — Migrer ClientRepositoryImpl vers offline-first (AC4)

- [ ] **7.1** Modifier `lib/features/contact/data/repository/client_repository_impl.dart`
  - Ajouter `required SyncTriggerDispatcher syncTriggerDispatcher` au constructeur
  - Remplacer `create()`:
    ```dart
    @override
    Future<ClientModel> create({required String name, required String phone, String? email, String? notes}) async {
      final id = const Uuid().v4();
      final now = DateTime.now();
      final client = ClientModel(id: id, name: name, phone: phone, email: email, notes: notes, createdAt: now, updatedAt: now);
      await _local.upsert(client);
      await _syncService.queueOperation(
        operation: 'CREATE_CLIENT',
        payload: {'id': id, 'name': name, 'phone': phone, if (email != null) 'email': email, if (notes != null) 'notes': notes},
        entityId: id,
      );
      _syncTriggerDispatcher.triggerPushIfIdle();
      return client;
    }
    ```
  - Modifier `update()` pour unifier les deux branches (online/offline) en: local first, queue `UPDATE_CLIENT`, trigger
  - **Conserver sans modification:** `archive()`, `syncFromRemote()`, `getAll()`, `search()`, `getById()`
- [ ] **7.2** Mettre à jour `clientRepositoryProvider` dans `providers.dart`

---

### Task 8 — Migrer SupplierRepositoryImpl vers offline-first (AC5)

- [ ] **8.1** Modifier `lib/features/contact/data/repository/supplier_repository_impl.dart`
  - Ajouter `required SyncTriggerDispatcher syncTriggerDispatcher` au constructeur
  - Remplacer `create()`:
    ```dart
    @override
    Future<SupplierModel> create({required String name, required String phone, String? email, List<String>? productIds}) async {
      final id = const Uuid().v4();
      final now = DateTime.now();
      final supplier = SupplierModel(id: id, name: name, phone: phone, email: email, productIds: productIds ?? [], createdAt: now, updatedAt: now);
      await _local.upsert(supplier);
      await _syncService.queueOperation(
        operation: 'CREATE_SUPPLIER',
        payload: {'id': id, 'name': name, 'phone': phone, if (email != null) 'email': email, 'productIds': productIds ?? []},
        entityId: id,
      );
      _syncTriggerDispatcher.triggerPushIfIdle();
      return supplier;
    }
    ```
  - Modifier `update()` pour unifier les deux branches: local first, queue `UPDATE_SUPPLIER`, trigger
  - **Conserver sans modification:** `archive()`, `syncFromRemote()`, `getAll()`, `getById()`, `search()`
- [ ] **8.2** Mettre à jour `supplierRepositoryProvider` dans `providers.dart`

---

### Task 9 — Migrer StockRepositoryImpl vers offline-first (AC7)

- [ ] **9.1** Modifier `lib/features/catalog/data/repository/stock_repository_impl.dart`
  - Ajouter `required SyncTriggerDispatcher syncTriggerDispatcher` au constructeur
  - Remplacer `recordEntry()`:
    ```dart
    @override
    Future<void> recordEntry({required String productId, required String storeId, required int quantity, String? note}) async {
      final id = const Uuid().v4();
      final movement = StockMovementModel(
        id: id, productId: productId, storeId: storeId,
        movementType: 'STOCK_ENTRY', quantity: quantity, note: note,
        occurredAt: DateTime.now(),
      );
      // Local writes first — instant feedback
      await _local.upsertLevel(StockLevelModel(productId: productId, storeId: storeId, quantity: quantity, updatedAt: DateTime.now()));
      await _local.insertMovement(movement);
      await _syncService.queueOperation(
        operation: 'RECORD_STOCK_ENTRY',
        payload: {'productId': productId, 'storeId': storeId, 'quantity': quantity, if (note != null) 'note': note},
        entityId: id,
      );
      _syncTriggerDispatcher.triggerPushIfIdle();
    }
    ```
  - Même pattern pour `adjustStock()` avec opération `STOCK_ADJUST`
  - **Conserver sans modification:** `getLevels()`, `getLevelByStore()`, `getCrossStoreAvailability()`, `getMovementHistory()`, `setThreshold()`
- [ ] **9.2** Mettre à jour `stockRepositoryProvider` dans `providers.dart`

---

### Task 10 — Migrer StockTransferRepositoryImpl vers offline-first (AC8)

- [ ] **10.1** Modifier `lib/features/inventory/data/repository/stock_transfer_repository_impl.dart`
  - Ajouter `required SyncTriggerDispatcher syncTriggerDispatcher` au constructeur
  - Remplacer `executeTransfer()` pour utiliser `_offlineTransfer()` comme chemin unique:
    ```dart
    @override
    Future<StockTransferModel> executeTransfer({required String sourceStoreId, required String destStoreId, required String productId, required int quantity, String? note}) async {
      // OFFLINE-FIRST: local validation + write always first
      final transfer = await _offlineTransfer(
        sourceStoreId: sourceStoreId,
        destStoreId: destStoreId,
        productId: productId,
        quantity: quantity,
        note: note,
      );
      // Silent background sync
      _syncTriggerDispatcher.triggerPushIfIdle();
      return transfer;
    }
    ```
  - Supprimer la branche `if (await _connectivity.isOnline())` avec `_remote.create()`
  - La méthode `_offlineTransfer()` reste inchangée (validation locale + applyLocalStockChange + queueOperation)
  - **Conserver sans modification:** `completeTransfer()`, `getHistory()`
- [ ] **10.2** Mettre à jour `stockTransferRepositoryProvider` dans `providers.dart`

---

### Task 11 — Backend: nouveaux handlers sync (AC9)

> **Packages:** `com/keevo/sync/sync/adapter/in/sync/handler/`

- [ ] **11.1** Créer `CreateCategoryHandler.java`
  ```java
  @Component
  public class CreateCategoryHandler implements SyncOperationHandler {
    @Override public String operationType() { return "CREATE_CATEGORY"; }
    @Override
    public SyncOperationResult handle(SyncOperationPayload payload, String tenantId) {
      // Upsert category in tenant schema (INSERT ON CONFLICT DO NOTHING)
      // Fields: id, name, parentId, isActive=true, isCustom=true, createdAt=now, updatedAt=now
      // Returns: APPLIED
    }
  }
  ```
- [ ] **11.2** Créer `ToggleCategoryHandler.java`
  ```java
  // UPDATE categories SET is_active = ?, updated_at = NOW() WHERE id = ?
  // Category not found → APPLIED (idempotent)
  ```
- [ ] **11.3** Créer `RenameCategoryHandler.java`
  ```java
  // UPDATE categories SET name = ?, updated_at = NOW() WHERE id = ?
  // Category not found → APPLIED (idempotent)
  ```
- [ ] **11.4** Créer `CreateClientHandler.java`
  ```java
  // INSERT INTO clients WHERE id = payload.id ON CONFLICT DO NOTHING
  // Fields: id, name, phone, email, notes, createdAt=NOW(), updatedAt=NOW()
  // Returns: APPLIED
  ```
- [ ] **11.5** Créer `CreateSupplierHandler.java`
  ```java
  // INSERT INTO suppliers WHERE id = payload.id ON CONFLICT DO NOTHING
  // Handle productIds: INSERT INTO supplier_products (supplier_id, product_id) ON CONFLICT DO NOTHING
  // Returns: APPLIED
  ```
- [ ] **11.6** Enregistrer les 5 handlers dans `SyncOperationHandlerRegistry`

---

### Task 12 — TDD GREEN: Faire passer les tests (AC10, AC11)

- [ ] **12.1** Flutter: Faire passer les 7 suites de tests `*_offline_first_test.dart` (toutes les méthodes RED deviennent GREEN)
- [ ] **12.2** Backend: Faire passer `CreateCategoryHandlerTest`, `ToggleCategoryHandlerTest`, `RenameCategoryHandlerTest`, `CreateClientHandlerTest`, `CreateSupplierHandlerTest`

---

### Task 13 — Vérification: tests existants non-régressifs (CRITICAL)

- [ ] **13.1** Flutter: `flutter test` — zéro nouveaux échecs sur les tests existants
  - Vérifier tout particulièrement: `sale_repository_test.dart`, `product_repository_test.dart`, `client_repository_test.dart`, `stock_repository_test.dart`
  - Les mocks doivent être mis à jour pour injecter `MockSyncTriggerDispatcher` partout où un repo reçoit le nouveau paramètre
- [ ] **13.2** Backend: `mvn test` — zéro nouveaux échecs
  - Les tests existants des handlers (CREATE_PRODUCT, CREATE_SALE, etc.) restent inchangés

---

## Dev Agent Record

### File List

#### NOUVEAU (Flutter):
- `keevo/app/lib/core/sync/sync_trigger_dispatcher.dart`
- `keevo/app/lib/core/sync/riverpod_sync_trigger_dispatcher.dart`
- `test/features/pos/data/repository/sale_repository_offline_first_test.dart`
- `test/features/catalog/data/repository/product_repository_offline_first_test.dart`
- `test/features/catalog/data/repository/category_repository_offline_first_test.dart`
- `test/features/contact/data/repository/client_repository_offline_first_test.dart`
- `test/features/contact/data/repository/supplier_repository_offline_first_test.dart`
- `test/features/catalog/data/repository/stock_repository_offline_first_test.dart`
- `test/features/inventory/data/repository/stock_transfer_repository_offline_first_test.dart`

#### NOUVEAU (Backend):
- `src/main/java/com/keevo/sync/sync/adapter/in/sync/handler/CreateCategoryHandler.java`
- `src/main/java/com/keevo/sync/sync/adapter/in/sync/handler/ToggleCategoryHandler.java`
- `src/main/java/com/keevo/sync/sync/adapter/in/sync/handler/RenameCategoryHandler.java`
- `src/main/java/com/keevo/sync/sync/adapter/in/sync/handler/CreateClientHandler.java`
- `src/main/java/com/keevo/sync/sync/adapter/in/sync/handler/CreateSupplierHandler.java`
- `src/test/java/com/keevo/sync/sync/adapter/in/sync/handler/CreateCategoryHandlerTest.java`
- `src/test/java/com/keevo/sync/sync/adapter/in/sync/handler/ToggleCategoryHandlerTest.java`
- `src/test/java/com/keevo/sync/sync/adapter/in/sync/handler/RenameCategoryHandlerTest.java`
- `src/test/java/com/keevo/sync/sync/adapter/in/sync/handler/CreateClientHandlerTest.java`
- `src/test/java/com/keevo/sync/sync/adapter/in/sync/handler/CreateSupplierHandlerTest.java`

#### MODIFIÉ (Flutter):
- `keevo/app/lib/core/di/providers.dart` — ajout `syncTriggerDispatcherProvider`
- `keevo/app/lib/features/pos/data/repository/sale_repository_impl.dart`
- `keevo/app/lib/features/catalog/data/repository/product_repository_impl.dart`
- `keevo/app/lib/features/catalog/data/repository/category_repository_impl.dart`
- `keevo/app/lib/features/contact/data/repository/client_repository_impl.dart`
- `keevo/app/lib/features/contact/data/repository/supplier_repository_impl.dart`
- `keevo/app/lib/features/catalog/data/repository/stock_repository_impl.dart`
- `keevo/app/lib/features/inventory/data/repository/stock_transfer_repository_impl.dart`

#### MODIFIÉ (Backend):
- `src/main/java/com/keevo/sync/sync/adapter/in/sync/SyncOperationHandlerRegistry.java` — enregistrement des 5 nouveaux handlers

---

## Notes de Complétion

> À remplir par le Dev Agent après implémentation.

---

## ⚠️ Précisions architecturales importantes

### Pourquoi validateSale() et cancelSale() ne changent PAS

`validateSale()` requiert que le backend effectue:
1. Remapping des `productIdRemappings` (draft product → promoted product)
2. Décrement de stock **et** enregistrement des `initialStockEntries`
3. Transition de statut `PENDING_VALIDATION → COMPLETED`

Ces opérations sont déjà offline-capable (`validateAndDecrementStock()` local + `VALIDATE_SALE` queue). Le chemin offline est déjà implémenté correctement. Cette story n'y touche pas.

### Pourquoi completeTransfer() reste backend-required

`completeTransfer()` confirme la réception du stock en destination. La logique d'autorité est côté serveur (le transfert doit être `PENDING` sur le backend). Rendre cela offline-first créerait des conflits de double-application. Reste remote-only.

### UUID local vs UUID backend

Quand un produit/client/fournisseur est créé offline-first, son UUID est généré localement et envoyé au backend via la sync queue en tant que `id` dans le payload. Les handlers backend côté CategoryHandler/ClientHandler/SupplierHandler utilisent `INSERT ... ON CONFLICT DO NOTHING` sur cet UUID. Ainsi, si la sync est déclenchée plusieurs fois (retry), l'opération est idempotente sans créer de doublon.

Pour les **ventes**, l'UUID est déjà généré côté Flutter depuis Story 4.1 — comportement inchangé.

### ConnectivityService n'est plus dans les repos

Après cette story, `ConnectivityService` peut être **supprimé du constructeur** de `SaleRepositoryImpl` (et des autres repos modifiés) puisque la logique de détection réseau est déléguée au `SyncTriggerDispatcher`. Note: vérifier que `hasPendingOperations()` ne requiert pas `ConnectivityService` dans ces repos — si oui, conserver.

---

### Review Findings

- [ ] [Review][Decision] **F2 — `syncFromApi()` no-op brise le chargement initial des catégories** — `CategoryRepositoryImpl.syncFromApi()` retourne désormais `getLocalCategories()` (pas d'appel réseau). `category_provider.dart` l'appelle comme fallback quand `localCategories.isEmpty` (ex: après onboarding sur appareil vierge). Sans remplacement, l'utilisateur voit un sélecteur de catégorie vide jusqu'au prochain pull sync périodique. Options: (A) déclencher `SyncService.pull()` dans le provider quand les categories locales sont vides ; (B) accepter le comportement actuel — le pull sync au login (Story 5.2) peuple déjà les catégories avant qu'elles soient affichées. [category_provider.dart]

- [ ] [Review][Decision] **F4 — `TOGGLE_CATEGORY` payload sans `isActive` : toggle non-idempotent sur l'état final** — Le payload `{'categoryId': categoryId}` ne contient pas la valeur cible `isActive`. Si deux devices togglent la même catégorie offline, l'ordre d'arrivée des deux `TOGGLE_CATEGORY` ops sur le backend produit l'état opposé à ce qui était attendu. Options: (A) ajouter `'isActive': newActive` au payload et que le handler utilise cette valeur (set to value, pas toggle) ; (B) conserver le toggle pur côté backend (comportement actuel) et accepter ce risque de conflit multi-device minime pour les catégories. [category_repository_impl.dart + CategorySyncHandler.java]

- [ ] [Review][Patch] **F1 — `CategorySyncHandler.CREATE_CATEGORY` ignore l'UUID client → doublon persistant post-sync** — Le handler appelle `createCategory.execute(new CreateCategoryDto(name, parentId))` qui génère un nouveau UUID backend, ignorant le `id` du payload. La Notes de Complétion de la story elle-même attendait `INSERT … ON CONFLICT DO NOTHING` sur cet UUID. Résultat : UUID=X (local Drift) et UUID=Y (backend) coexistent après pull sync, UUID=X devient zombie. Fix : Implémenter à l'image de `CreateClientSyncHandler` — bypass `CreateCategoryUseCase`, utiliser `categoryRepository.save()` avec l'UUID client, vérification d'idempotence `findById` avant insertion. [CategorySyncHandler.java]

- [ ] [Review][Patch] **F3 — Test `recordSale_isImmediatelyVisibleInGetSalesForToday()` absent** — AC10 spécifie ce test dans `sale_repository_offline_first_test.dart` mais il est manquant (4 tests présents, le 4ème est `recordSale_localWriteOrdering_beforeQueue` non prévu par la spec). Fix : ajouter le test vérifiant que `getSalesForToday()` retourne immédiatement la vente after `recordSale()`. [sale_repository_offline_first_test.dart]

- [x] [Review][Defer] **F6 — `RiverpodSyncTriggerDispatcher` capture `Ref` à la construction** — Si la portée du Provider est recréée (override dans les tests ou changement de scope auth), le `_ref` capturé peut être obsolète. Risque théorique dans le contexte Riverpod `keepAlive`. — deferred, pattern pre-existing dans le projet, impact négligeable en prod.
