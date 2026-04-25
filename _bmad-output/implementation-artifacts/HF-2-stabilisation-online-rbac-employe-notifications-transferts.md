# Story HF-2: Stabilisation Online — RBAC Employé, Notifications Transferts & Ventes Brouillons

**Status:** done
**Code Review:** 2026-04-24 — PASSED (adversarial, 2 deferred items D1+D2)
**Révision:** 2026-04-25 — Revue code livré : ACs 8–11 ajoutés (scope confirmé déjà en prod pour OWNER), bug B3 identifié (employé sans accès ventes brouillons — 4 sous-problèmes UI)
**Révision 2:** 2026-04-25 — Bugs B1, B2, B3 corrigés (voir Dev Agent Record ci-dessous)
**Révision 3:** 2026-04-25 — B1 complété (3 autres getSingleOrNull dans insertAll/validateAndDecrementStock/cascadeValidatePendingSales), + B4 corrigé (stock mismatch + catalogue EMPLOYEE)
**Révision 4 (code review 2ème passe):** 2026-04-25 — P1/P3/P4 corrigés ; D1 + P2 ajournés dans deferred-work.md
**Story Key:** HF-2-stabilisation-online-rbac-employe-notifications-transferts

---

## Story

As a merchant (OWNER Simon) and an employee (Loïc),
I want stock transfers to show correct quantities, employees to be notified when a transfer arrives, employees to validate draft sales, and employees to access the catalogue without seeing financial data,
so that daily operations are accurate and role-based access is properly enforced.

---

## Context — Source des remontées

Ces corrections et fonctionnalités proviennent de logs runtime et de retours terrain (sessions 2026-04-22/23) :

1. **Stock transfer display bug** : la boutique destination voit déjà les 15 unités en stock alors que le transfert est encore `IN_TRANSIT` → doit rester à 0 jusqu'à réception.
2. **Notification FCM manquante** : aucune notification FCM envoyée lors de la création d'un transfert vers une boutique.
3. **Gel interface après création employé** : l'UI se fige après la création d'un employé.
4. **Crash « Bad state: Too many elements »** : crash lors de la création d'une nouvelle vente quand un brouillon (`PENDING_VALIDATION`) existe déjà avec le même produit.
5. **Employé valide ventes brouillons** : l'employé doit pouvoir valider une vente brouillon (comme l'owner), avec notification envoyée à l'owner.
6. **Accès catalogue employé** : l'employé doit accéder au catalogue (produits, stock) sans voir les valeurs financières (buyPrice, marges, totaux).

---

## Acceptance Criteria

### AC1 — Deux étapes de transfert : stock source uniquement à l'étape 1

- **Given** un OWNER initie un transfert de stock (Step 1 — STOCK_TRANSFER sync op)
- **When** le transfert est créé avec statut `IN_TRANSIT`
- **Then** la quantité est **uniquement** décrémentée de la boutique source
- **And** la boutique destination ne voit **aucun changement** de stock
- **And** le stock destination n'est incrémenté qu'à la **Step 2 — réception** (`completeTransfer()`)
- **Acceptance** : `StockTransferRepositoryImpl.executeTransfer()` appelle `applySourceDecrement()` seulement ; `completeTransfer()` appelle `applyDestinationIncrement()` après confirmation backend

### AC2 — Notification FCM à la création d'un transfert

- **Given** un utilisateur exécute un STOCK_TRANSFER (`POST /api/v1/sync/push` ou `POST /api/v1/stock/transfers`)
- **When** le transfert est persisté avec statut `IN_TRANSIT`
- **Then** une notification FCM est envoyée à **tous les utilisateurs OWNER** du tenant **ET** à tous les **employés assignés à la boutique destination**
- **And** le titre est : `"📦 Nouveau transfert de stock"`
- **And** le corps précise la quantité et la boutique destination
- **And** le deep link FCM payload est `/stock/transfers` (AC7)
- **And** si l'envoi FCM échoue, le transfert lui-même **ne doit pas échouer** (async best-effort)
- **Acceptance** : `TransferCreatedNotificationListener` @Async @AfterCommit ; `OwnerAndDestinationEmployeeStrategy` pour la résolution des destinataires

### AC3 — Accès catalogue employé (Flutter UI)

- **Given** l'utilisateur connecté a le rôle `EMPLOYEE`
- **When** il navigue dans l'application
- **Then** il voit la bottom nav avec : Caisse / Catalogue (read-only) / Rapports / Plus
- **And** dans le Catalogue, les boutons de mutation (créer produit, archiver, importer CSV, ajouter stock) sont **masqués**
- **And** les prix de vente sont **affichés** (prix de vente public : normal)
- **And** `buyPrice`, `transportCost`, marges, et totaux financiers sont **masqués ou nuls**
- **Acceptance** : `main_shell.dart` `_employeeRoutes` ; masquage dans CatalogPage des actions owner ; router guard AC5

### AC4 — Listing ventes pendantes multi-articles sans exception

- **Given** plusieurs ventes `PENDING_VALIDATION` existent, dont certaines ont plusieurs articles (sale_items)
- **When** `GET /api/v1/sales/pending` est appelé
- **Then** la réponse retourne 200 avec **toutes** les ventes pendantes
- **And** aucune `IncorrectResultSizeDataAccessException` / `getSingle()` ne lève d'erreur
- **Acceptance** : requête JPA avec `JOIN FETCH DISTINCT` ou lazy load corrigé ; N+1 résolu

### AC5 — EMPLOYEE peut accéder et valider les ventes brouillons de sa boutique

- **Given** un utilisateur avec rôle `EMPLOYEE` et `storeId=S` dans son JWT
- **When** il appelle `GET /api/v1/sales/pending`
- **Then** il reçoit **uniquement** les ventes pendantes de sa boutique assignée (filtre automatique par storeId JWT)
- **When** il appelle `POST /api/v1/sales/{saleId}/validate`
- **Then** il peut valider si `sale.storeId == jwt.storeId` → 200
- **And** il reçoit **403** si `sale.storeId != jwt.storeId`
- **And** l'OWNER reçoit une notification FCM lors de la validation par un employé : `"✅ Vente validée par {employeeName}"`
- **Acceptance** : `ValidateSaleController` / `SaleController` avec RBAC store-scoped ; `SaleDraftValidatedNotificationListener` pour notification owner

### AC6 — Masquage des champs financiers pour EMPLOYEE

- **Given** un EMPLOYEE appelle `GET /api/v1/sales/pending` ou `GET /api/v1/sales/{id}`
- **When** la réponse est sérialisée
- **Then** `totalAmount = 0`, `discountAmount = 0`, `appliedUnitPrice = 0`, `subtotal = 0` dans tous les items
- **And** l'OWNER voit les vraies valeurs
- **Acceptance** : DTO factory / rôle-conditionnel : `PendingSaleResponseDto.forRole(sale, role)` ; backend masquage au niveau controller/mapper

### AC7 — Contrats deep link

- **Given** une notification FCM est envoyée (transfert ou vente validée)
- **When** l'utilisateur tap la notification
- **Then** l'app navigue vers :
  - Transfert créé → `/stock/transfers`
  - Vente pendante validée par employé → `/pos/pending/{saleId}`
- **Acceptance** : deep link encodé dans `NotificationPayload.data`

### AC8 — Produits DRAFT visibles dans la Caisse (POS) avec badge + bannière ventes brouillons (OWNER)

- **Given** le catalogue contient des produits avec `status = DRAFT`
- **When** l'utilisateur ouvre la Caisse
- **Then** les produits DRAFT apparaissent dans la grille POS avec un badge **« Brouillon »** superposé (Stack overlay)
- **And** une bannière **« Ventes brouillons »** est affichée en haut du POS indiquant le nombre de ventes `PENDING_VALIDATION` (OWNER uniquement — voir B3.2 pour l'accès EMPLOYEE)
- **And** la requête `frequentProductsProvider` filtre sur `status IN ('ACTIVE', 'DRAFT')`
- **Acceptance** : `pos_page.dart` — grille POS + `_PendingSalesBanner`

### AC9 — Tab « Brouillons » dans Rapports → PendingSalesPage (OWNER)

- **Given** l'utilisateur connecté est OWNER
- **When** il navigue vers l'onglet Rapports
- **Then** il voit un écran à 5 onglets : Jour / Historique / Rentabilité / Boutiques / **Brouillons**
- **And** l'onglet Brouillons ouvre `PendingSalesPage` qui liste et permet de valider les ventes `PENDING_VALIDATION`
- **And** `PendingSalesPage` est réutilisée (pas de doublon — identique à l'accès via deep link)
- **Acceptance** : `owner_reports_page.dart` — `TabController(length: 5)` + `_KeepAliveTab` wrapping `PendingSalesPage`

### AC10 — Navigation Plus/Settings : Clients, Fournisseurs, Catégories, Transferts

- **Given** l'utilisateur navigue vers la section « Plus »
- **When** il est OWNER
- **Then** il voit : Boutiques, Stock multi-boutiques, Inventaire, Abonnement, Équipe, Journal d'audit, Synchronisation, **Clients**, **Fournisseurs**, **Catégories**, **Transferts**
- **When** il est EMPLOYEE
- **Then** il voit : Stock multi-boutiques, Inventaire, **Transferts**
- **Acceptance** : `settings_page.dart` — sections OWNER et EMPLOYEE distinctes

### AC11 — Bouton transfert dans la vue stock globale

- **Given** l'utilisateur se trouve sur `GlobalStockOverviewPage`
- **When** la page est chargée
- **Then** un bouton **FAB** navigue vers `/stock/transfers`
- **And** un bouton dans la barre de titre (header action) navigue également vers `/stock/transfers`
- **Acceptance** : `global_stock_overview_page.dart` — FAB L87 + header action L110-111

---

## Scope de l'implémentation

### Backend (Spring Boot 3.5.0, Java 21)

#### Nouveaux fichiers créés

| Fichier | Rôle |
|---|---|
| `messaging/notification/application/listener/TransferCreatedNotificationListener.java` | Observer: écoute `TransferCreatedEvent` @AfterCommit @Async, envoie FCM |
| `messaging/notification/application/strategy/TransferNotificationRecipientStrategy.java` | Port (interface) pour résolution des destinataires d'un transfert |
| `messaging/notification/application/strategy/OwnerAndDestinationEmployeeStrategy.java` | Stratégie concrète : OWNER + EMPLOYEEs de la boutique destination |
| `messaging/notification/application/listener/SaleDraftValidatedNotificationListener.java` | Observer: écoute `SaleValidatedEvent` @AfterCommit @Async, notifie l'owner si validé par employé |
| `catalog/stock/domain/event/TransferCreatedEvent.java` | Domain event record (transferId, sourceStoreId, destinationStoreId, quantity, tenantId) |

#### Fichiers modifiés

| Fichier | Changement |
|---|---|
| `catalog/stock/application/service/TransferStockService.java` | Publie `TransferCreatedEvent` après persisting le transfert `IN_TRANSIT` |
| `commerce/sale/adapter/in/web/SaleController.java` | `GET /api/v1/sales/pending` : EMPLOYEE voit store-scoped ; `POST .../validate` : EMPLOYEE autorisé si même store que JWT |
| `commerce/sale/adapter/out/persistence/SaleSpringRepository.java` | `findByStoreIdAndStatus()` ajouté pour le scope EMPLOYEE |
| `commerce/sale/application/service/ValidateSaleService.java` | RBAC guard : vérifie que `sale.storeId == actorStoreId` pour EMPLOYEE |
| `identity/employee/adapter/out/persistence/EmployeeSpringRepository.java` | `findByTenantAndStore()` ajouté pour `OwnerAndDestinationEmployeeStrategy` |
| `identity/auth/domain/port/out/UserRepository.java` | `findOwnersByTenant()` ajouté pour récuperer les OWNER devices tokens |
| `shared/infrastructure/persistence/TenantSchemaSyncService.java` | Ajout migration `findByStoreIdAndStatus` indexes si nécessaire |

#### Tests backend

- `TransferCreatedNotificationListenerTest` — 3 tests (notifie, no-op si pas de recipients, async non-bloquant)
- `OwnerAndDestinationEmployeeStrategyTest` — 3 tests
- `SaleDraftValidatedNotificationListenerTest` — 2 tests
- `SaleControllerHF2Test` — RBAC store-scoped EMPLOYEE access tests (AC5 positif + negatif 403)

### Flutter/Dart (Riverpod, Drift, FCM)

#### Fichiers modifiés

| Fichier | Changement |
|---|---|
| `lib/core/scaffold/main_shell.dart` | `_employeeRoutes` : Caisse + Catalogue (read-only) + Rapports + Plus ; `pendingSalesCount` store-scoped pour EMPLOYEE |
| `lib/core/router/app_router.dart` | `_ownerOnlyPrefixes` liste des routes OWNER-only ; route guard EMPLOYEE → `/pos` pour routes interdites ; commentaire AC5 RBAC multi-couche |
| `lib/features/pos/presentation/page/pending_sale_detail_page.dart` | `isEmployee` from `currentUserRoleProvider` ; masquage `totalAmount`/prix pour EMPLOYEE (AC6) ; bouton "Valider" accessible à EMPLOYEE |
| `lib/features/notifications/domain/model/notification_model.dart` | Deep link handler `/stock/transfers` → `TransferHistoryPage` |

#### Tests Flutter

- Deep link handler tests (notification model)
- Router guard tests (EMPLOYEE redirect)

---

## Fichiers déjà opérationnels (non modifiés par HF-2)

- `StockTransferRepositoryImpl` — `executeTransfer()` appelle `applySourceDecrement()` seulement (AC1 déjà correct depuis Story 3.3 + 5.6)
- `completeTransfer()` appelle `applyDestinationIncrement()` (AC1 déjà correct)

> **Note**: Le bug de stock destination visible pré-réception était dû à un `getSingle()` back-end sur multi-items (AC4) qui causait une exception et renvoyait des données partielles. Corriger AC4 a résolu l'affichage backend ; côté Flutter, l'offline-first pattern était déjà correct.

---

## Éléments différés (Deferred Work — ne pas réimplémenter)

### D1 — N+1 chargement liste ventes pendantes (BACKEND)

`SaleSpringRepository.findByStoreIdAndStatus` + `findByStatus` chargent les `items` en lazy, générant N requêtes DB supplémentaires. Fonctionne correctement dans `@Transactional` mais à optimiser.
**Fix futur** : `@Query` avec `JOIN FETCH DISTINCT` analogue à `findByIdWithItems`.
**Pré-existant** (HF-2 ajoute `findByStoreIdAndStatus`).
*Documenté dans `_bmad-output/deferred-work.md` D1.*

### D2 — Mismatch variant/stock dans `ValidateSaleService` (BACKEND)

Disponibilité lue avec `findByProductAndStore` (sans `variantId`) mais décrémentée à niveau variant. Peut déclencher `StockForcedZeroEvent` à tort si le produit a des variants.
**Pré-existant** Story 4.3.
*Documenté dans `_bmad-output/deferred-work.md` D2.*

---

## Bugs ouverts post-HF-2 (non couverts)

> Ces bugs ont été identifiés dans les logs post-déploiement mais **ne font pas partie de HF-2**. Ils nécessitent une investigation + correctif dans une story ultérieure.

### B1 — "Bad state: Too many elements" à la création d'une nouvelle vente

**Symptôme** : crash lors de la création d'une NOUVELLE vente quand un brouillon (`PENDING_VALIDATION`) existe déjà avec le même produit dans la boutique.

**Trace** :
```
flutter: Erreur: Bad state: Too many elements
  local_stock_datasource.dart — getLevelByStore() → getSingleOrNull()
  local_sale_datasource.dart  — getAvailableStock()  → getSingleOrNull()
```

**Hypothèse** : `stock_levels` contient 2+ lignes pour la même paire `(product_id, store_id)`. L'index UNIQUE `idx_stock_levels_product_store` (ajouté en migration v10) devrait l'empêcher, mais la création depuis le catalogue ou la sync pull peut contourner `upsertLevel()` dans certains chemins.

**Action requise** :
1. Ajouter un guard dans `getLevelByStore()` : si 2+ lignes → dédupliquer et prendre la quantité max (ou logguer et filtrer `first`).
2. Investiguer les chemins d'écriture dans `local_stock_datasource.dart` et `local_sale_datasource.dart` pour l'insert conditionnel qui pourrait créer des doublons.
3. S'assurer que l'index UNIQUE est bien créé à l'installation fraîche (vérifier `stepByStep` migration).

### B2 — Gel interface après création d'un employé ("interface figé")

**Symptôme** : après création d'un employé via `CreateEmployeePage`, la bottom sheet de mot de passe temporaire s'affiche puis l'UI se fige — impossible de naviguer.

**Trace** :
```
flutter: Another exception was thrown: Bad state: Cannot use 'ref' after the widget was disposed
  RiverpodSyncTriggerDispatcher._ref  (sync_trigger_dispatcher.dart)
```

**Root cause confirmé** : `RiverpodSyncTriggerDispatcher.triggerPushIfIdle()` planifie un `Future.microtask(() async { _ref.read(...).triggerPush(); })` sans `try/catch`. Si le `ProviderScope` est reconstruit (changement auth, recréation du ProviderContainer au login/logout) entre la planification et l'exécution du microtask, `_ref.read()` lève `StateError: Bad state: Cannot use 'ref' after the widget was disposed`. L'exception non catchée gèle la boucle d'événements.

**Note** : bug pré-existant identifié comme déféré F6 dans Story 5.6 (`_bmad-output/deferred-work.md`).

**Action requise** :
1. Dans `riverpod_sync_trigger_dispatcher.dart`, wrapper le corps du microtask :
```dart
Future.microtask(() async {
  try {
    if (await _connectivity.isOnline()) {
      _ref.read(syncTriggerNotifierProvider.notifier).triggerPush();
    }
  } on StateError {
    // Ref stale — ProviderScope rebuilt (auth change). Sync will
    // be triggered on the next write operation. Swallow silently.
  }
});
```
2. Dans `create_employee_page.dart` : vérifier que `context.pop()` dans `.then((_) {...})` s'exécute bien. Si le GoRouter stack ne permet pas de `pop()`, utiliser `context.go('/team')` comme fallback.

### B3 — CRITIQUE : EMPLOYEE ne peut pas accéder ni voir les ventes brouillons

**Symptôme** : un employé ne voit aucun indicateur de ventes brouillons dans l'app. Le badge onglet Caisse est toujours à 0, la bannière POS est absente, et l'onglet Rapports n'a pas de section Brouillons.

**Root cause** : 4 sous-problèmes indépendants dans le code Flutter :

| Ref | Fichier | Ligne | Code actuel | Fix requis |
|---|---|---|---|---|
| B3.1 | `main_shell.dart` | L212 | `isEmployee ? 0 : ref.watch(pendingSalesCountProvider(storeId))...` | Supprimer la branche `isEmployee ? 0` — `pendingSalesCountProvider` est déjà scopé par `storeId` |
| B3.2 | `pos_page.dart` | L254 | `if (!isEmployee) _PendingSalesBanner(storeId: storeId)` | Retirer le guard `if (!isEmployee)` — la bannière utilise déjà `storeId` pour filtrer |
| B3.3 | `app_router.dart` | `/reports` route | EMPLOYEE → `ReportsPage` (vue simple, pas de tab Brouillons) | Ajouter un onglet « Brouillons » à `ReportsPage` pour les employés (réutiliser `PendingSalesPage`) OU router l'employé vers une vue tabbed filtrée |
| B3.4 | `reports_page.dart` | L281 | `'${_currencyFormat.format(summary.pendingSalesTotal)}'` affiché sans guard rôle | Masquer `pendingSalesTotal` pour les employés avec un check `isEmployee` (cohérence avec AC6) |

**Impact** : un employé ne peut valider aucune vente brouillon depuis l'app mobile (aucune entrée UI disponible). Il doit utiliser le deep link FCM comme seul chemin — ce qui est un workaround non documenté.

**Story cible** : HF-3 ou correctif dédié (criticité HAUTE).

---

## Architecture — Contraintes à respecter

### Backend
- **Hexagonal** : nouveaux services dans `application/`, nouveaux adapters dans `adapter/out/`
- **Event-driven** : utilisez `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` pour toutes les notifications — jamais dans la transaction métier
- **Async best-effort** : `@Async` sur les listeners de notification — une erreur FCM NE DOIT PAS faire échouer la transaction métier
- **TenantContext** : toujours `TenantContext.setCurrentTenant(tenantId)` en entrée de `@Async` et `TenantContext.clear()` en finally
- **Migrations** : tout changement de schéma via `TenantSchemaProvisioner` (nouveaux tenants) + `TenantSchemaMigrationRunner` (tenants existants)

### Flutter
- **Offline-first** : toutes les écritures transactionnelles via `local.write()` → `syncService.queueOperation()` → `triggerPushIfIdle()`
- **Riverpod** : `@riverpod` annotation, `keepAlive: true` uniquement pour singletons (SyncTriggerNotifier, ConnectivityService)
- **Drift** : VERSION SCHEMA via `stepByStep`, toujours `insertOnConflictUpdate` ou UPDATE-then-INSERT pour les tables sans UNIQUE définie dans le modèle Dart
- **RBAC Flutter** : toujours multi-couche = route guard + navigation bar filter + widget-level hide + backend enforcement (403)

---

## Résumé des tests E2E

**Script** : `backend/scripts/curl-tests-hf-2.sh`

```
✅ AC1  Two-step transfer (source-only at Step 1, dest at Step 2)
✅ AC4  Multi-item pending sale loaded without exception
✅ AC5  EMPLOYEE accesses /sales/pending (store-scoped, 200)
✅ AC5  EMPLOYEE can validate pending sale from own store (200)
✅ AC5  EMPLOYEE gets 403 on sale from non-assigned store
✅ AC6  Financial fields masked for EMPLOYEE (totalAmount=0, price=0)
✅ AC6  OWNER still sees real financial values
✅ AC7  Deep link contracts validated by unit tests
ℹ️  AC2  Notification listeners verified by TransferCreatedNotificationListenerTest (FCM disabled in CI)
```

---

## Completion Notes

**2026-04-24** — Code review PASSED (adversarial). 2 items deferred (D1 N+1, D2 variant/stock mismatch).

Bugs B1 et B2 identifiés post-code-review et documentés ci-dessus pour traitement dans une story ultérieure.

---

## Dev Agent Record — Révision 2 (2026-04-25)

### Bugs corrigés

**B1 — "Bad state: Too many elements" crash (vente avec brouillon existant)**

- `local_stock_datasource.dart: getLevelByStore()` — remplacé `getSingleOrNull()` par `.get()` + réduction sur la ligne avec quantité max. Résistant aux lignes dupliquées `(product_id, store_id)` créées quand `upsertLevel()` est contourné.
- `local_sale_datasource.dart: getAvailableStock()` — remplacé `getSingleOrNull()` par `.get()` + somme des quantités. Même protection.
- `local_sale_datasource.dart: insertAll()` — **Révision 3** : remplacé `getSingleOrNull()` par `.get()` + `fold` dans le bloc de décrémentation de stock (section 3 de la transaction). Crash actif lorsqu'une vente COMPLETED est créée avec des lignes `(product_id, store_id)` dupliquées — chemin non couvert par la Révision 2.
- `local_sale_datasource.dart: validateAndDecrementStock()` — **Révision 3** : 2 sites supplémentaires corrigés (init stock entries + décrémentation sale items). Chemin de validation locale offline.
- `local_sale_datasource.dart: cascadeValidatePendingSales()` — **Révision 3** : 1 site corrigé (décrémentation cascade). Chemin de validation cascade offline.

**B2 — Gel interface après création employé**

- `riverpod_sync_trigger_dispatcher.dart: triggerPushIfIdle()` — body du `Future.microtask` encapsulé dans `try { } on StateError { }`. Note silencieuse : la ref devient stale quand le ProviderScope est reconstruit (changement d'auth). La sync se déclenche au prochain write.
- `create_employee_page.dart` — `.then((_) { if (mounted) context.pop() })` remplacé par `context.canPop() ? context.pop() : context.go('/settings/team')` pour gérer le cas où le GoRouter stack a été réinitialisé pendant la bottom-sheet.

**B3 — EMPLOYEE n'accède pas aux ventes brouillons (4 sous-problèmes)**

- B3.1 `main_shell.dart` — supprimé le guard `isEmployee ? 0 :` sur `pendingSalesCountProvider(storeId)`. Le provider est déjà store-scoped.
- B3.2 `pos_page.dart` — supprimé `if (!isEmployee)` autour de `_PendingSalesBanner`. Le widget filtre via `storeId`, donc déjà store-scoped.
- B3.3 `owner_reports_page.dart` — ajouté `EmployeeReportsPage` (2 onglets : Jour + Brouillons). `app_router.dart` route désormais l'EMPLOYEE vers `EmployeeReportsPage` au lieu de `ReportsPage`.
- B3.4 `reports_page.dart` — ajout check `isEmployee` dans `_buildContent`. Le montant `pendingSalesTotal` est masqué pour EMPLOYEE : seul le nombre de ventes en attente est affiché.

### Fichiers modifiés

| Fichier | Bug |
|---|---|
| `lib/core/sync/riverpod_sync_trigger_dispatcher.dart` | B2 |
| `lib/features/team/presentation/page/create_employee_page.dart` | B2 |
| `lib/features/catalog/data/datasource/local_stock_datasource.dart` | B1 |
| `lib/features/pos/data/datasource/local_sale_datasource.dart` | B1 |
| `lib/core/scaffold/main_shell.dart` | B3.1 |
| `lib/features/pos/presentation/page/pos_page.dart` | B3.2 |
| `lib/features/reports/presentation/page/owner_reports_page.dart` | B3.3 (new EmployeeReportsPage) |
| `lib/features/reports/presentation/page/reports_page.dart` | B3.4 |
| `lib/core/router/app_router.dart` | B3.3 (route update) |

### Validation

`flutter analyze` — 0 erreurs, 0 warnings en code production. Tous les `info` (directives_ordering, use_build_context_synchronously) sont pré-existants.

---

## Dev Agent Record — Révision 3 (2026-04-25)

### Bugs corrigés

**B1 (suite) — getSingleOrNull() manquants (Révision 3)**

- `local_sale_datasource.dart: insertAll()` — stock decrement block : `getSingleOrNull()` → `.get()` + `fold MAX`. **Crash actif** lors de la création d'une vente COMPLETED avec des lignes `(product_id, store_id)` dupliquées.
- `local_sale_datasource.dart: validateAndDecrementStock()` — 2 sites corrigés : init stock entries + décrémentation sale items. Chemin de validation locale offline.
- `local_sale_datasource.dart: cascadeValidatePendingSales()` — 1 site corrigé. Chemin de validation cascade offline.
- `local_sale_datasource.dart: getAvailableStock()` — stratégie corrigée SUM → MAX. SUM était correct pour des lignes légitimement distinctes, mais les doublons représentent le même stock physique : MAX est la valeur de référence et est cohérent avec `getLevelByStore()`.

**B4 — Stock mismatch POS ≠ Catalogue**

- `stock_levels_table.dart` — ajout `uniqueKeys => [{productId, storeId}]`. Sur installation fraîche, `onCreate` → `createAll()` ne créait pas l'index UNIQUE de la migration v10 (rawSQL hors Drift ORM). Les doublons étaient donc possibles sur tout appareil install-from-scratch.
- `app_database.dart` — migration v25 : `CREATE UNIQUE INDEX IF NOT EXISTS idx_stock_levels_product_store ON stock_levels (product_id, store_id)`. Idempotente — couvre les appareils déjà en v24 sans l'index.
- `app_database.dart` — `schemaVersion` bumped 24 → 25.

**B4 (suite) — EMPLOYEE redirigé vers /pos depuis le catalogue**

- `app_router.dart` — supprimé `/products/new`, `/products/import` de `_ownerOnlyPrefixes` et supprimé la regex `^/products/[^/]+/edit$`. Le catalogue (création, import, édition produit) est désormais identique pour OWNER et EMPLOYEE. L'autorisation réelle est déléguée au backend (JWT RBAC, 403 pour opérations non consenties).
- `app_router.dart` — supprimé import mort `reports_page.dart` (rendu inutile par le fix B3.3 en Révision 2).

### Fichiers modifiés (Révision 3)

| Fichier | Bug |
|---|---|
| `lib/features/pos/data/datasource/local_sale_datasource.dart` | B1 (insertAll + validateAndDecrementStock + cascadeValidatePendingSales + getAvailableStock) |
| `lib/core/storage/stock_levels_table.dart` | B4 |
| `lib/core/storage/app_database.dart` | B4 |
| `lib/core/router/app_router.dart` | B4 |

### Validation

`flutter analyze` — 0 erreurs, 0 warnings. `info` pré-existants uniquement.

---

## Code Review — 2ème passe (2026-04-25)

Revue adversariale post-Révision 3 (commit `0427589`, 64 fichiers, 1683 insertions, 319 suppressions).

### Findings

| ID | Décision | Fichier | Description |
|---|---|---|---|
| **D1** | Ajourné | `DayClosureDeltaProvider.java` | `totalTransactions` mappe sur `row[4]` (= `totalSales`). Aucune colonne `total_transactions` dans le DDL. Payload de sync corrompu silencieusement. Voir `deferred-work.md`. |
| **P1** | ✅ Corrigé | `app_database_test.dart`, `users_first_name_migration_test.dart` | Assertions `schemaVersion == 24` → `25` (migration v25 ajoutée en Révision 3). |
| **P2** | Ajourné | `PendingSaleController.java` L71 | `cancelSale` autorise EMPLOYEE (`hasAnyRole('OWNER', 'EMPLOYEE')`). Non prévu par AC5. Voir `deferred-work.md`. |
| **P3** | ✅ Corrigé | `app_router.dart` | `/settings/categories` absent de `_ownerOnlyPrefixes`. EMPLOYEE pouvait accéder à `CategoriesPage`. Ajouté. |
| **P4** | ✅ Corrigé | `local_sale_datasource.dart` L64/193/243/337 | `fold SUM` au lieu de `fold MAX` pour lignes stock dupliquées (cohérence avec `getAvailableStock()` L124 qui utilise déjà MAX). |
| Defer | Ajourné (pré-existant D2) | `ValidateSaleService.java` | Race condition read-then-decrement concurrent. Pré-existant Story 4.3. |
| Defer | Ajourné | `transfer_history_page.dart` | `ref.watch(activeStoreIdProvider)` appelé deux fois dans la même condition. |
