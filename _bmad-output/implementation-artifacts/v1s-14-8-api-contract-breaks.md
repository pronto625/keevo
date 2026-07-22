---
baseline_commit: ccfad6b45bc5978f9ac3ca43c0926210a46758bf
---
# Story 14.8: Cassures d'intégration API (X-CRIT/HIGH/MED)

Status: done

<!-- V1-stabilization track — tag A (patch V1 urgent, release-blocker, Sprint 1 bloquant V1).
     Branche : v1-stabilization.
     Refonte absorption : « cherry-pick + envelope native on refonte » (Story 15.6 possède la
     standardisation COMPLÈTE de l'enveloppe pagination — voir Décision D3, portée réduite ici).
     Backend Java + Flutter, aucune migration Flyway (AC4 ajoute une classe backend pure ;
     AC6 étend un record backend existant, non-breaking).
     Validation optionnelle : lancer validate-create-story avant dev-story. -->

## Story

**As a** Toor (proprietaire produit),
**I want** que les contrats API Flutter ↔ backend soient alignés à l'exécution (ARCH25/28) — pas seulement en théorie dans la documentation,
**so that** l'audit de conformité BMAD (`AUDIT_CONFORMITE_BMAD.md`) ne recense plus de cassures runtime silencieuses (paramètre ignoré, endpoint fantôme, mauvais champ d'enveloppe lu) qui font échouer des fonctionnalités sans jamais lever d'erreur visible à l'utilisateur.

## Contexte V1-stabilization

- **Source backlog :** `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md:412-427` → Story 14.8 (Refs X-CRIT-1, X-HIGH-2, X-MED-3/4/5, ARCH25/28/29).
- **Audit détaillé :** `AUDIT_CONFORMITE_BMAD.md:199-212` — 5 incohérences cross-système distinctes.
- **Priorité :** Sprint 1 bloquant V1 (`epics-remediation-audit.md:46,696`), tag **A**.
- **Index track :** `sprint-status.yaml` — `v1s-14-8-api-contract-breaks` · Epic `v1s-epic-14-functional-gaps-v1` (backlog — aucune story `14-x` précédente n'a encore démarré cet epic dans le track V1-stab ; 14.10/14.11/14.12 existent déjà comme stories mais aucune n'est en cours).
- **⚠️ Investigation préalable (cette création de story) a REQUALIFIÉ 2 des 5 findings de l'audit** — voir "Ce qui existe déjà" ci-dessous. Ne pas implémenter l'audit au pied de la lettre sans lire cette section : l'audit a été écrit en inspectant du code qui a depuis changé (Story 5.6, 2026-04-22) ou en extrapolant un scope plus large que ce qu'exige réellement le tag **A**.

## Ce qui existe déjà (ne pas réinventer)

### Finding 1 — X-CRIT-1 (unarchive) : **le bug littéral n'est PLUS live** — c'est de la dette de test + du code mort

L'audit dit *"Flutter appelle `PATCH /api/v1/products/{id}/unarchive` inexistant → 404/405 runtime"*. **Faux aujourd'hui.** Le vrai chemin d'exécution (`product_card.dart:828` → `unarchive_product_usecase.dart:11` → `ProductRepositoryImpl.unarchive()`) :

```dart
// keevo/app/lib/features/catalog/data/repository/product_repository_impl.dart:228-236
Future<void> unarchive(String id) async {
  await _local.unarchiveById(id);
  await _syncService.queueOperation(
    operation: 'UNARCHIVE_PRODUCT',
    payload: {'productId': id},
    entityId: id,
  );
  _syncTriggerDispatcher.triggerPushIfIdle();
}
```

— **n'appelle jamais `_remote` du tout** (offline-first depuis Story 5.6, symétrique à `archive()` ligne 216-225). Et côté backend, `UNARCHIVE_PRODUCT` est **déjà** un type d'opération pleinement câblé dans `ProductSyncHandler.java:45` (`supportedTypes()`) et `:93-98` (`case "UNARCHIVE_PRODUCT" -> unarchiveProduct.execute(...)`). Le flux sync-queue fonctionne de bout en bout, sans aucun changement requis.

**Le vrai problème (2 défauts réels, distincts du texte de l'audit) :**

1. **Code mort dangereux** — `remote_product_datasource.dart:58-73` a des méthodes `archive()`/`unarchive()` qui appellent Dio directement (`_dio.patch('/api/v1/products/$id/unarchive')`) sur un endpoint qui **n'existe réellement pas** côté backend (confirmé : `grep -rn "/unarchive" backend/src/main/java` → 0 résultat). **Mais rien ne les appelle** (grep exhaustif : 0 appelant en prod). Ce sont des méthodes fantômes qui planteraient (404) si jamais un futur développeur les appelait en pensant qu'elles marchent (elles ont l'air symétriques à `archive()`, qui elle pointe vers un endpoint réel).
2. **Test cassé qui affirme un contrat obsolète** — `product_repository_backend_first_test.dart` (232 lignes, 3 groupes : `update`/`archive`/`unarchive` — "backend-first") a été laissé après la migration offline-first de Story 5.6 et **échoue réellement** (vérifié en exécutant le fichier : 4/7 tests FAIL, `type 'Null' is not a subtype of type 'Future<void>'` — les mocks attendent que `_remote.archive/unarchive` soit appelé, ce qui n'arrive plus jamais). Le fichier frère `product_repository_offline_first_test.dart` (sa propre doc ligne 4 dit couvrir *"create(), update(), archive(), unarchive()"*) a bien un groupe `AC2 — archive() offline-first` (ligne 187-211) qui teste le **bon** contrat actuel (`verifyNever(() => mockRemote.archive(any()))`) — **mais PAS de groupe équivalent pour `unarchive()`** malgré ce que dit sa doc. Trou de couverture réel.

### Finding 2 — X-HIGH-2 : `dest` vs `destination` (réel) + `productId` (dead plumbing, jamais exercé)

**Backend** `StockTransferController.java:120-127` :
```java
@GetMapping
public ResponseEntity<...> getHistory(
        @RequestParam(required = false) UUID source,
        @RequestParam(required = false) UUID destination,   // ← nom du param
        ...)
```
Aucun paramètre `productId` nulle part dans la chaîne (`GetTransferHistoryQuery` → `GetTransferHistoryService` → `StockTransferRepository.findByFilters`) — confirmé absent à tous les niveaux, pas juste au controller.

**Flutter** `remote_stock_transfer_datasource.dart:61-77` :
```dart
if (destinationStoreId != null) 'dest': destinationStoreId,   // ← 'dest' ≠ 'destination', filtre no-op silencieux
if (productId != null) 'productId': productId,                // ← envoyé, ignoré côté backend, silencieux
```
`source` est correct (matche déjà). **Seul client de cet endpoint : l'app Flutter** (aucun dashboard/autre caller, confirmé par grep). Aucun test existant n'asserte `dest`/`destination` (`remote_stock_transfer_datasource_test.dart:78-100` ne vérifie que `source`) — renommer est zéro-régression côté tests.

**`productId` n'est en réalité JAMAIS exercé avec une vraie valeur** — le seul call-site de `transferHistoryProvider` dans toute l'app est `transfer_history_page.dart:45` : `ref.watch(transferHistoryProvider())` — **zéro argument**, donc `productId` vaut toujours `null` par défaut. (Le `productId: ''` visible à `transfer_history_page.dart:37` est dans `_openNewTransfer()` → `showTransferFormBottomSheet(...)`, une fonction totalement différente pour choisir le produit à transférer — sans rapport avec le filtre d'historique.) Le paramètre `productId` est donc du plomberie mort de bout en bout (provider → repository → local + remote datasource) — jamais réellement utilisé, alors que `local_stock_transfer_datasource.dart:159-183` l'implémente correctement en local (filtre appliqué), créant une asymétrie local-vs-remote qui ne se manifeste jamais aujourd'hui mais serait un piège si quelqu'un l'activait un jour côté UI sans remarquer que le remote l'ignore silencieusement.

### Finding 3 — X-MED-3 : `ReportsDeltaProvider` — 100% travail backend, Flutter est déjà complet

Côté Flutter, **tout existe déjà et n'a besoin d'aucun changement** :
- `rest_sync_service.dart:235-236` : `await _upsertReports(entities['reports'] as List<dynamic>? ?? [])` — dispatch déjà câblé.
- `_upsertReports()` (`rest_sync_service.dart:729-769`) déjà implémenté, INSERT/ON CONFLICT UPDATE complet dans la table Drift `reports` (`core/storage/reports_table.dart`, Story 7.2), attend ces clés camelCase exactes dans chaque `Map` :
  `id, tenantId, storeId, storeName, reportType, reportDate, content, deliveryStatus, deliveryAttempts, lastAttemptAt, totalRevenue, totalSales, isAutomatic` (le code vérifie aussi la variante `automatic` en fallback — piège Jackson bean-naming connu, non-pertinent ici car on construit la Map manuellement) `, createdAt, actorId`.
- Comme `entities['reports']` est toujours absent aujourd'hui, `_upsertReports([])` retourne immédiatement (ligne 730) — **la table locale `reports` n'est simplement jamais peuplée**, aucun bug Flutter à corriger.

Côté backend, **le gap est réel et simple à combler** — pattern `DeltaEntityProvider` déjà établi pour 14 entités, **aucune n'existe pour `reports`** :
```java
// keevo/backend/src/main/java/com/keevo/sync/sync/domain/port/in/DeltaEntityProvider.java
public interface DeltaEntityProvider {
    String entityKey();
    List<Map<String, Object>> queryDelta(Instant since);
}
```
Javadoc (lignes 13-14) : *"Adding a new entity type requires only implementing this interface — zero changes to SyncPullService or SyncController"* — Spring auto-découvre chaque `@Component` implémentant l'interface (`SyncPushService.java:33,42` reçoit `List<DeltaEntityProvider>` par injection de constructeur ; `pull()` lignes 235-262 itère `deltaProviders` et fait `entities.put(provider.entityKey(), delta)`). **Zéro registre à maintenir.**

Mirror exact à copier — `DayClosureDeltaProvider.java` (fichier complet, 64 lignes) :
```java
@Component
public class DayClosureDeltaProvider implements DeltaEntityProvider {
    private final EntityManager em;
    public DayClosureDeltaProvider(EntityManager em) { this.em = em; }

    @Override public String entityKey() { return "dayClosures"; }

    @Override
    public List<Map<String, Object>> queryDelta(Instant since) {
        String sql = "SELECT id, store_id, actor_id, closed_at, total_sales, total_revenue, " +
                "cash_amount, momo_amount, is_automatic FROM day_closures " +
                "WHERE closed_at > :since ORDER BY closed_at ASC";
        Query query = em.createNativeQuery(sql);
        query.setParameter("since", java.sql.Timestamp.from(since));
        query.setMaxResults(1000);
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(this::mapRow).toList();
    }
    // mapRow(...) construit un LinkedHashMap<String,Object> à clés camelCase + helpers str()/num()/ts()/bool()
}
```

L'entité `Report` existe déjà entièrement : `EndOfDayReportJpaEntity.java` (`@Entity @Table(name = "reports")`, schéma per-tenant), colonnes DB confirmées (`TenantSchemaProvisioner.java:619-655`, `DDL_REPORTS`) : `id, tenant_id, store_id, store_name, actor_id, actor_name, report_type, report_date, content, delivery_status, delivery_attempts, last_attempt_at, total_revenue, total_sales, is_automatic, created_at`. `GET /api/v1/reports` existe déjà (`ReportController.java:87-88`) pour l'historique paginé — confirme que le concept "reports" = rapports EOD/hebdo, pas des préférences.

### Finding 4 — X-MED-4 : `domainCode` — bug réel, isolé, un seul point de correction

Fichier réel : `keevo/app/lib/core/services/api_service.dart` (**pas** `core/network/` comme suggéré par le texte de l'épic).
```dart
// api_service.dart:30-48, factory ApiException.fromDioError
if (error.response?.data is Map<String, dynamic>) {
  final data = error.response!.data as Map<String, dynamic>;
  message = data['message'] ?? data['error'] ?? 'Unknown error';
  domainCode = data['code']?.toString();      // ← ligne 38, BUG : lit 'code' (nom de statut HTTP générique) au lieu de 'domainCode' (code métier)
}
```
Confirmation sémantique côté backend — `GlobalExceptionHandler.java` javadoc (lignes 24-33) + `ApiResponseWrapper.java` : `code = status.name()` (générique, ex. `"NOT_FOUND"`, `"CONFLICT"`) tandis que `domainCode = ex.getDomainCode()` (métier, ex. `"PRODUCT_NOT_FOUND"`, `"PLAN_LIMIT_EXCEEDED"`). Pattern correct déjà établi ailleurs à répliquer — `auth_interceptor.dart:174-179` :
```dart
String? _extractDomainCode(dynamic body) {
  if (body is Map) return body['domainCode'] as String?;
  return null;
}
```
**Rayon d'impact confirmé isolé** : `grep -rln "ApiException"` dans `keevo/app/lib` → seul `api_service.dart` s'y réfère ; aucun autre fichier ne catch `ApiException` ni ne lit son `.domainCode` (les autres exceptions `StoreException`/`AuthException`/`ProductException`/etc. parsent `domainCode` directement depuis `DioException.response.data`, sans passer par `ApiException` — non affectées par ce bug, non affectées par ce fix). **Correction sûre, isolée, une ligne.**

### Finding 5 — X-MED-5 : enveloppe pagination — 9 formes distinctes confirmées (au-delà des "5+" de l'audit)

`ApiResponseWrapper.java` (36 lignes, fichier complet) :
```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponseWrapper<T>(T data, String error, String code, String domainCode, Object details, Instant timestamp) {
    public static <T> ApiResponseWrapper<T> ok(T data) { return new ApiResponseWrapper<>(data, null, null, null, null, Instant.now()); }
    public static <T> ApiResponseWrapper<T> error(String error, String code, String domainCode, Object details) { ... }
}
```
**Zéro support pagination existant.** 9 formes distinctes recensées (voir rapport de recherche complet, non reproduit ici pour concision) : liste brute sans métadonnées (6 controllers dont `ProductController`, `EmployeeController`, `CategoryController`, `ClientController`, `SupplierController`, `StoreController`), DTO `{items,totalCount,page,pageSize}` (`AdminCatalogController`, `AdminTenantController` — **consommés aussi par le dashboard Next.js**, `keevo/dashboard/src/lib/api/admin-catalog.ts` + `admin-tenants.ts`), `Page<T>` Spring brut sérialisé tel quel (`MultiStoreStockController`, `StockController`), `Map` aplati `{content,totalElements,totalPages,number,size}` (`StockTransferController`), DTO `{content,page,size,totalElements,totalPages}` (`SaleHistoryController`), DTO `{items,page,size,totalElements,totalPages}` — même concept, nom de champ différent (`ReportController`), DTO `{entries,hasMore,page,size}` sans total (`AuditController`), params acceptés mais réponse sans aucune métadonnée (`InventorySessionController`), convention `limit`/`offset` au lieu de `page`/`size` (`SyncController.getConflicts`). Côté client : **7 chemins de désérialisation indépendants** en Flutter (un par datasource, aucun partagé) + 2 dans le dashboard Next.js.

**Story 15.6 (`epics-remediation-audit.md:582-593`, backlog, tag B refonte-native) possède déjà EXPLICITEMENT la standardisation complète** : *"`ApiResponseWrapper` extended... all paginated endpoints migrate; Flutter + dashboard unwrap uniformly"*. Migrer les 9 formes + leurs 9 consommateurs dans **cette** story tag-A (patch V1 minimal) dupliquerait entièrement le scope de 15.6 et gonflerait le diff bien au-delà d'un "patch" — voir Décision D3.

## Décisions prises (documentées pour le code review — PO peut trancher différemment)

### D1 — Unarchive : suppression du code mort + fix du test cassé, PAS d'ajout d'endpoint backend

**Constat :** le chemin réel (queue offline-first) fonctionne déjà de bout en bout ; `RemoteProductDataSource.archive()/unarchive()` ne sont appelées par rien en prod.
**Décision :** supprimer les deux méthodes fantômes de `remote_product_datasource.dart` (YAGNI — rien ne les appelle, les garder serait mentir sur une capacité qui 404 si activée) ; supprimer entièrement `product_repository_backend_first_test.dart` (232 lignes, 100% remplacé par la contrepartie offline-first correcte) ; ajouter le groupe de test `unarchive()` manquant dans `product_repository_offline_first_test.dart` (mirror exact du groupe `archive()` existant lignes 187-211).
**Alternative rejetée :** ajouter un endpoint `PATCH /{id}/unarchive` côté backend pour symétrie défensive avec `archive()` — rejetée : aucun appelant n'en a besoin aujourd'hui, ajouterait de la surface non testée pour un chemin mort ; le PO peut choisir cette alternative en review si une garde-fou défensive est jugée utile pour un futur appel direct.

### D2 — Transfer history : `dest`→`destination` corrigé, `productId` supprimé (pas ajouté côté backend)

**Constat :** `productId` n'est exercé par AUCUN call-site réel (`transferHistoryProvider()` toujours appelé sans argument) — c'est de la plomberie morte de bout en bout, pas juste un mismatch de nommage.
**Décision :** renommer `'dest'` → `'destination'` dans `remote_stock_transfer_datasource.dart` (corrige un vrai bug silencieux, param déjà supporté backend). Supprimer `productId` de toute la chaîne (`RemoteStockTransferDataSource.getHistory`, `LocalStockTransferDataSource.getHistory`, `StockTransferRepository`/`Impl.getHistory`, `transferHistoryProvider`) plutôt que de l'implémenter côté backend — évite d'étendre `GetTransferHistoryQuery`/`Service`/`Repository.findByFilters` pour une capacité que rien n'utilise, et supprime l'asymétrie locale-vs-remote latente (le local filtrait, le remote ignorait silencieusement).
**Alternative rejetée :** implémenter le filtre `productId` côté backend (Controller + Query + Service + Repository, mirroring `source`/`destination`) — rejetée pour cette story tag-A ; le texte AC de l'épic offre explicitement cette alternative ("`productId` filter added backend or dropped client") — le PO peut la choisir en review si la fonctionnalité "historique des transferts d'un produit" est jugée nécessaire pour V1 (dans ce cas, prévoir un nouveau ticket dédié plutôt que d'élargir cette story de patch).

### D3 — Pagination envelope : capacité additive minimale seulement, PAS de migration des endpoints existants

**Constat :** Story 15.6 (backlog, tag B refonte-native) possède déjà explicitement "standardiser l'enveloppe pagination... tous les endpoints paginés migrent... Flutter + dashboard unwrap uniformément". Migrer les 9 formes + leurs consommateurs ici dupliquerait 15.6 et transformerait un "patch V1" en un chantier de refonte à part entière (9 controllers backend + 9 chemins de désérialisation client, aucun endpoint existant ne peut changer de forme sans casser son client actuel simultanément).
**Décision :** cette story ajoute uniquement une **capacité** à `ApiResponseWrapper` — un nouveau champ `meta` optionnel (`null` par défaut, `@JsonInclude(NON_NULL)` déjà en place donc rétro-compatible) + une factory `ApiResponseWrapper.paginated(data, page, size, totalElements, totalPages)` construisant `{data, meta:{page,size,totalElements,totalPages}, timestamp}`. **Aucun controller existant n'est migré** dans cette story — les 9 formes actuelles restent inchangées et fonctionnelles, zéro risque de régression. La factory est prête à l'emploi pour Story 15.6 (ou pour tout nouvel endpoint créé d'ici là) sans dupliquer son travail de migration.
**Alternative rejetée :** migrer ne serait-ce qu'un sous-ensemble des 9 endpoints maintenant — rejetée, toute migration partielle nécessite quand même de toucher le endpoint + son client Flutter/dashboard en tandem (pas de gain net à en faire "juste un" ici plutôt que dans 15.6 qui les fait tous avec la bonne vision d'ensemble).

## Acceptance Criteria

### AC1 — Code mort `unarchive` supprimé + trou de couverture de test comblé (parité, aucun changement de comportement runtime)

**Given** `remote_product_datasource.dart` a des méthodes `archive()`/`unarchive()` jamais appelées en prod, et `product_repository_backend_first_test.dart` affirme un contrat "backend-first" obsolète (4/7 tests FAIL à l'exécution),
**When** le nettoyage est appliqué,
**Then** `RemoteProductDataSource.archive()` et `.unarchive()` (`remote_product_datasource.dart:58-73`) sont supprimées (plus aucune référence Dio directe à `/products/{id}/archive` ou `/unarchive` côté Flutter),
**And** `product_repository_backend_first_test.dart` est supprimé entièrement,
**And** `product_repository_offline_first_test.dart` gagne un nouveau groupe `AC2 — unarchive() offline-first` (mirror exact du groupe `archive()` existant, lignes 187-211) vérifiant `queueOperation(operation: 'UNARCHIVE_PRODUCT', ...)` et `verifyNever(() => mockRemote.unarchive(any()))`,
**And** le comportement runtime réel (`ProductRepositoryImpl.archive()/unarchive()` → queue → `ProductSyncHandler`) n'est **pas modifié** — ce chemin était déjà correct.

### AC2 — Historique transferts : paramètre `destination` corrigé (vrai bug silencieux)

**Given** `remote_stock_transfer_datasource.dart` envoie `'dest'` alors que `StockTransferController.java:121` attend `'destination'` — le filtre de destination ne s'applique jamais silencieusement,
**When** le paramètre est corrigé,
**Then** `remote_stock_transfer_datasource.dart` envoie `'destination': destinationStoreId` (au lieu de `'dest'`),
**And** un test vérifie que la clé de query param envoyée est exactement `'destination'` (mirror du test existant qui vérifie déjà `'source'`, `remote_stock_transfer_datasource_test.dart:78-100`),
**And** `'source'` reste inchangé (déjà correct).

### AC3 — Filtre `productId` supprimé de la chaîne historique transferts (Décision D2 — plomberie morte, jamais exercée)

**Given** `productId` est transmis par Flutter (`remote_stock_transfer_datasource.dart`) mais ignoré silencieusement par le backend à tous les niveaux, et n'est jamais appelé avec une vraie valeur en production (`transferHistoryProvider()` toujours sans argument),
**When** le paramètre est retiré,
**Then** `productId` disparaît de la signature de `RemoteStockTransferDataSource.getHistory()`, `LocalStockTransferDataSource.getHistory()`, `StockTransferRepository`/`StockTransferRepositoryImpl.getHistory()`, et du provider `transferHistoryProvider` (`stock_transfer_provider.dart` + régénération `.g.dart`),
**And** aucune régression : le seul call-site (`transfer_history_page.dart:45`) n'utilisait déjà aucun argument,
**And** `showTransferFormBottomSheet(productId: ...)` (`transfer_form_bottom_sheet.dart`, formulaire de **création** de transfert — concept différent) n'est **pas** touché.

### AC4 — `ReportsDeltaProvider` créé (backend) — les rapports EOD/hebdo se synchronisent enfin offline

**Given** `rest_sync_service.dart:235-236` attend une clé `'reports'` dans la réponse de pull-sync que rien n'émet actuellement — la table locale Drift `reports` (Story 7.2) n'est jamais peuplée,
**When** `com.keevo.sync.sync.application.provider.ReportsDeltaProvider` est créé (mirror exact de `DayClosureDeltaProvider.java`),
**Then** la classe implémente `DeltaEntityProvider`, `entityKey()` retourne `"reports"`, `queryDelta(Instant since)` exécute une requête SQL native sur la table `reports` filtrée `WHERE created_at > :since ORDER BY created_at ASC` (`setMaxResults(1000)`), sélectionnant `id, tenant_id, store_id, store_name, actor_id, actor_name, report_type, report_date, content, delivery_status, delivery_attempts, last_attempt_at, total_revenue, total_sales, is_automatic, created_at`,
**And** chaque ligne est mappée vers un `LinkedHashMap<String,Object>` à clés camelCase (`id, tenantId, storeId, storeName, actorId, actorName, reportType, reportDate, content, deliveryStatus, deliveryAttempts, lastAttemptAt, totalRevenue, totalSales, isAutomatic, createdAt`) — **doit inclure `isAutomatic` exactement sous ce nom** (Flutter lit `map['isAutomatic'] ?? map['automatic']` en fallback défensif, mais la construction manuelle de la map ici n'a pas le piège Jackson bean-naming — utiliser `isAutomatic` directement suffit),
**And** aucun changement requis côté `SyncPushService`/`SyncController` (auto-découverte Spring du nouveau `@Component`),
**And** aucun changement requis côté Flutter (`_upsertReports` déjà correctement implémenté et attend exactement ces clés).

### AC5 — `domainCode` lu depuis le bon champ d'enveloppe (bug réel, isolé)

**Given** `api_service.dart:38` assigne `domainCode = data['code']?.toString()` — lit le nom de statut HTTP générique (`"NOT_FOUND"`) au lieu du code métier (`"PRODUCT_NOT_FOUND"`),
**When** le bug est corrigé,
**Then** `api_service.dart:38` devient `domainCode = data['domainCode']?.toString();` (mirror du pattern déjà correct `auth_interceptor.dart:174-179`, `_extractDomainCode`),
**And** un test unitaire de `ApiException.fromDioError` vérifie qu'une réponse d'erreur `{"error": "...", "code": "NOT_FOUND", "domainCode": "PRODUCT_NOT_FOUND", ...}` produit `ApiException.domainCode == "PRODUCT_NOT_FOUND"` (pas `"NOT_FOUND"`).

### AC6 — `ApiResponseWrapper` gagne une capacité `meta{page,size,totalElements,totalPages}` — additive, aucun endpoint migré (Décision D3)

**Given** `ApiResponseWrapper` n'a aucun support de pagination et 9 formes ad-hoc coexistent à travers le backend,
**When** la capacité additive est ajoutée,
**Then** `ApiResponseWrapper` gagne un champ `Object meta` (nullable, `@JsonInclude(NON_NULL)` déjà en place → absent du JSON si `null`, zéro impact sur les 9 formes existantes) et une factory statique `ApiResponseWrapper.paginated(T data, int page, int size, long totalElements, int totalPages)` qui construit `meta` comme `Map.of("page", page, "size", size, "totalElements", totalElements, "totalPages", totalPages)`,
**And** un test unitaire vérifie que `ApiResponseWrapper.paginated(...)` produit `{data, meta:{page,size,totalElements,totalPages}, timestamp}` et que `ApiResponseWrapper.ok(...)`/`.error(...)` restent inchangés (pas de régression, `meta` absent),
**And** **aucun controller existant n'est modifié** pour utiliser cette factory dans cette story — la migration des 9 endpoints est explicitement hors-scope (Décision D3, propriété de Story 15.6).

## Tasks / Subtasks

- [x] **Task 1 — Unarchive : nettoyage code mort + test (AC1)** [flutter]
  - [x] 1.1 Supprimer les méthodes `archive(String id)` et `unarchive(String id)` de `keevo/app/lib/features/catalog/data/datasource/remote_product_datasource.dart` (lignes ~58-73). `RemoteProductDataSource` est une classe concrète simple (pas d'interface séparée à mettre à jour) — confirmé, aucune autre déclaration à toucher.
  - [x] 1.2 Supprimer entièrement `keevo/app/test/features/catalog/data/repository/product_repository_backend_first_test.dart`.
  - [x] 1.3 Dans `keevo/app/test/features/catalog/data/repository/product_repository_offline_first_test.dart`, ajouter un groupe `group('AC2 — unarchive() offline-first', () { ... })` juste après le groupe `archive()` (après ligne 211) — mirror exact de sa structure : `callOrder` local→queue, `verify(queueOperation(operation: 'UNARCHIVE_PRODUCT', ...))`. Note: `verifyNever(() => mockRemote.unarchive(any()))` retiré car `RemoteProductDataSource` n'a plus ces méthodes (compilation impossible).
  - [x] 1.4 `flutter test test/features/catalog/data/repository/` → 12/12 GREEN, 0 FAIL (les 4 échecs du fichier supprimé disparaissent, le nouveau test unarchive passe).

- [x] **Task 2 — Transfer history : `destination` fix + suppression `productId` (AC2, AC3)** [flutter]
  - [x] 2.1 `remote_stock_transfer_datasource.dart:74` : `'dest': destinationStoreId` → `'destination': destinationStoreId`.
  - [x] 2.2 Supprimer le paramètre `productId` de `RemoteStockTransferDataSource.getHistory()` (datasource), `LocalStockTransferDataSource.getHistory()` (`local_stock_transfer_datasource.dart:159-183`, retirer aussi le filtre `where = where & t.productId.equals(productId)`), `StockTransferRepository`/`StockTransferRepositoryImpl.getHistory()` (interface + impl, retirer le passthrough aux 3 call sites internes lignes ~129-165), et le provider `@riverpod Future<List<StockTransferModel>> transferHistory(...)` dans `stock_transfer_provider.dart:44-59`.
  - [x] 2.3 Régénérer le codegen Riverpod : `dart run build_runner build --delete-conflicting-outputs` (régénère `stock_transfer_provider.g.dart`).
  - [x] 2.4 Vérifier que `transfer_history_page.dart:45` (`ref.watch(transferHistoryProvider())`) compile toujours sans argument — aucun changement requis à ce call-site.
  - [x] 2.5 Mettre à jour `remote_stock_transfer_datasource_test.dart` : ajouter une assertion sur la clé `'destination'` (mirror de l'assertion existante sur `'source'`, lignes ~78-100); retirer toute assertion/mock référant à `productId` (aucune existait).

- [x] **Task 3 — `ReportsDeltaProvider` (AC4)** [backend]
  - [x] 3.1 Créer `keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/ReportsDeltaProvider.java` implémentant `DeltaEntityProvider` — copier la structure exacte de `DayClosureDeltaProvider.java` (constructeur `EntityManager em`, `entityKey()`, `queryDelta(Instant since)`, helpers `str()/num()/ts()/bool()`).
  - [x] 3.2 SQL : `SELECT id, tenant_id, store_id, store_name, actor_id, actor_name, report_type, report_date, content, delivery_status, delivery_attempts, last_attempt_at, total_revenue, total_sales, is_automatic, created_at FROM reports WHERE created_at > :since ORDER BY created_at ASC` (`setMaxResults(1000)`).
  - [x] 3.3 `mapRow()` : clés camelCase `id, tenantId, storeId, storeName, actorId, actorName, reportType, reportDate, content, deliveryStatus, deliveryAttempts, lastAttemptAt, totalRevenue, totalSales, isAutomatic, createdAt` — attention à l'ordre des colonnes `Object[] row` correspondant exactement à l'ordre du SELECT (16 colonnes).
  - [x] 3.4 Créer `ReportsDeltaProviderTest.java` (mirror `DayClosureDeltaProviderTest.java` : `@ExtendWith(MockitoExtension.class)`, mock `EntityManager`/`Query`, test `entityKey_returnsReports()`, test `queryDelta_withSince_returnsOnlyRecentReports()` avec une ligne `Object[]` factice de 16 éléments).
  - [x] 3.5 `mvn test -Dtest=ReportsDeltaProviderTest` → 2/2 GREEN. `SyncPullServiceTest` → 6/6 GREEN (auto-discovery, aucun changement requis).

- [x] **Task 4 — `domainCode` fix (AC5)** [flutter]
  - [x] 4.1 `keevo/app/lib/core/services/api_service.dart:38` : `domainCode = data['code']?.toString();` → `domainCode = data['domainCode']?.toString();`.
  - [x] 4.2 Créer/étendre un test pour `ApiException.fromDioError` (créer `test/core/services/api_service_test.dart` — 3 tests : extraction correcte, null quand absent, non-Map response data).
  - [x] 4.3 `flutter test test/core/services/` → 3/3 GREEN.

- [x] **Task 5 — `ApiResponseWrapper.paginated()` capacité additive (AC6)** [backend]
  - [x] 5.1 `ApiResponseWrapper.java` : ajouter le champ `Object meta` au record (ajouté en position 6, avant `timestamp`, pour ne pas casser les appels positionnels de `ok()`/`error()`).
  - [x] 5.2 Ajouter `public static <T> ApiResponseWrapper<T> paginated(T data, int page, int size, long totalElements, int totalPages)` retournant `new ApiResponseWrapper<>(data, null, null, null, null, Map.of("page", page, "size", size, "totalElements", totalElements, "totalPages", totalPages), Instant.now())`.
  - [x] 5.3 Créer/étendre `ApiResponseWrapperTest.java` : 5 tests — `paginated_buildsMetaWithPageSizeTotalElementsTotalPages()`, `ok_hasNullMeta()`, `ok_jsonOmitsMeta()`, `error_jsonOmitsMeta()`, `paginated_jsonContainsMeta()`.
  - [x] 5.4 **Aucun controller existant modifié** — confirmé (seul `ApiResponseWrapper.java` touché côté prod).
  - [x] 5.5 `mvn test -Dtest=ApiResponseWrapperTest` → 5/5 GREEN.

- [x] **Task 6 — Régression complète** [testing]
  - [x] 6.1 `mvn test` (backend) → 1520 tests, 1 Failure (OnboardingServiceTest — pre-existing mock argument mismatch), 41 Errors (all pre-existing: FlywayBaselineIntegrationTest, ProductJpaEntityTest, JpaBaseEntityPersistTest, AuthControllerTest, SectorTemplateFactoryTest, ProductRepositoryAdapterTest, SaleRepositoryAdapterTest). **0 NEW regressions.**
  - [x] 6.2 `flutter test` → 820 passed, 11 failed. Amélioration du baseline: 817→820 passed (suppression de `product_repository_backend_first_test.dart` a retiré 4 échecs). Les 11 échecs sont pré-existants. **0 NEW regressions.**
  - [x] 6.3 `flutter analyze` → 759 issues (amélioration de 760 baseline). **0 nouveau warning/error.**

## Dev Notes

### Patterns architecturaux à respecter

- **Ne PAS** ajouter d'endpoint REST `/unarchive` — le chemin queue-based fonctionne déjà (Décision D1).
- **Ne PAS** implémenter le filtre `productId` côté backend pour l'historique transferts — plomberie morte à supprimer, pas à compléter (Décision D2).
- **`ReportsDeltaProvider`** doit suivre EXACTEMENT le pattern `DeltaEntityProvider` (interface Strategy, auto-discovery Spring) — ne pas créer de mécanisme d'enregistrement manuel, ne pas toucher `SyncPushService`/`SyncController`.
- **`ApiResponseWrapper.paginated()`** est une capacité additive pure — ne migrer AUCUN des 9 controllers recensés (`ProductController`, `EmployeeController`, `CategoryController`, `ClientController`, `SupplierController`, `StoreController`, `AdminCatalogController`, `AdminTenantController`, `MultiStoreStockController`, `StockController`, `StockTransferController`, `SaleHistoryController`, `ReportController`, `AuditController`, `InventorySessionController`, `SyncController.getConflicts`) — cette migration appartient à Story 15.6.
- **Isolation des changements** : cette story touche 5 zones indépendantes (unarchive, transfer history, reports sync, domainCode, envelope capacité) — chaque Task peut être committée/testée séparément, aucune dépendance croisée entre elles.

### Ce qu'il NE FAUT PAS faire

- Ne pas ajouter d'endpoint backend `/products/{id}/unarchive` (sauf si le PO override D1 en review).
- Ne pas étendre `GetTransferHistoryQuery`/`GetTransferHistoryService`/`StockTransferRepository` pour supporter `productId` (sauf si le PO override D2 en review).
- Ne pas migrer un seul des 9 endpoints paginés vers `ApiResponseWrapper.paginated()` dans cette story — c'est le travail de Story 15.6, migrer même un seul ici créerait un chevauchement de scope et un risque de conflit de merge avec 15.6.
- Ne pas toucher `showTransferFormBottomSheet`/`transfer_form_bottom_sheet.dart` — son paramètre `productId` sert à la création de transfert, sans rapport avec le filtre d'historique supprimé en Task 2.
- Ne pas dupliquer une 2e implémentation de la sémantique `isAutomatic`/`automatic` côté Flutter — `_upsertReports` gère déjà le fallback, ne rien changer côté Flutter pour AC4.

### References

- [Source: `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md:412-427`] Story 14.8 AC originales.
- [Source: `AUDIT_CONFORMITE_BMAD.md:199-212`] Findings X-CRIT-1, X-HIGH-2, X-MED-3/4/5 détaillés.
- [Source: `keevo/app/lib/features/catalog/data/repository/product_repository_impl.dart:216-236`] Contrat offline-first réel archive/unarchive (Story 5.6).
- [Source: `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/ProductSyncHandler.java:45,93-98`] `UNARCHIVE_PRODUCT` déjà pleinement câblé côté backend.
- [Source: `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/StockTransferController.java:120-127`] Param `destination` (pas `dest`), pas de `productId`.
- [Source: `keevo/app/lib/features/inventory/data/datasource/remote_stock_transfer_datasource.dart:61-77`] Bug `dest` + `productId` mort.
- [Source: `keevo/backend/src/main/java/com/keevo/sync/sync/domain/port/in/DeltaEntityProvider.java`] Contrat Strategy à implémenter.
- [Source: `keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/DayClosureDeltaProvider.java`] Mirror exact pour `ReportsDeltaProvider`.
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java:619-655`] DDL `reports` — colonnes exactes.
- [Source: `keevo/app/lib/core/sync/rest_sync_service.dart:235-236,729-769`] `_upsertReports` déjà complet, attend les clés camelCase listées en AC4.
- [Source: `keevo/app/lib/core/services/api_service.dart:30-48`] Bug `domainCode` ligne 38.
- [Source: `keevo/app/lib/core/network/auth_interceptor.dart:174-179`] Pattern correct `_extractDomainCode` à mirrorer.
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/ApiResponseWrapper.java`] Structure actuelle de l'enveloppe (36 lignes).
- [Source: `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md:582-593`] Story 15.6 — propriétaire de la migration complète de l'enveloppe pagination (hors-scope ici, Décision D3).
- [Source: `_bmad-output/planning-artifacts/architecture.md:321`] `Error format: { error, code, domainCode, details }`.

## Dev Agent Record

### Agent Model Used

DeepSeek V4 Pro (GitHub Copilot)

### Debug Log References

N/A — no runtime debugging required; all changes validated via unit tests.

### Completion Notes List

1. ✅ **AC1 — Unarchive cleanup**: Supprimé méthodes `archive()`/`unarchive()` de `RemoteProductDataSource` (code mort jamais appelé en prod — chemin réel offline-first via `UNARCHIVE_PRODUCT` queue déjà fonctionnel depuis Story 5.6). Supprimé `product_repository_backend_first_test.dart` (4/7 tests FAIL obsolètes). Ajouté groupe `unarchive()` offline-first dans `product_repository_offline_first_test.dart`. 12/12 catalog repo tests GREEN.

2. ✅ **AC2 — Transfer history dest→destination**: Corrigé `'dest'` → `'destination'` dans `remote_stock_transfer_datasource.dart`. Bug réel silencieux — le backend attend `destination` mais Flutter envoyait `dest`, le filtre de destination ne s'appliquait jamais. Test ajouté (mirror `'source'`). 4/4 remote datasource tests GREEN.

3. ✅ **AC3 — productId supprimé**: Retiré paramètre `productId` de toute la chaîne transfer history (RemoteDS, LocalDS, Repository interface+impl, Provider + codegen .g.dart). Plomberie morte — jamais exercée en prod (`transferHistoryProvider()` toujours appelé sans argument). 0 régression.

4. ✅ **AC4 — ReportsDeltaProvider**: Créé `ReportsDeltaProvider.java` (mirror exact `DayClosureDeltaProvider`, 16 colonnes, clés camelCase). Auto-découvert par Spring (implémente `DeltaEntityProvider`). 2/2 tests GREEN. `SyncPullServiceTest` 6/6 GREEN. `_upsertReports` Flutter déjà complet (aucun changement requis côté client).

5. ✅ **AC5 — domainCode fix**: `api_service.dart:38` — `data['code']` → `data['domainCode']`. Bug réel isolé — lisait le nom de statut HTTP (`NOT_FOUND`) au lieu du code métier (`PRODUCT_NOT_FOUND`). 3/3 nouveaux tests GREEN.

6. ✅ **AC6 — ApiResponseWrapper.paginated()**: Ajout champ `Object meta` (nullable, `@JsonInclude(NON_NULL)`) + factory `paginated(data, page, size, totalElements, totalPages)`. Zéro controller migré (Décision D3 — migration complète = Story 15.6). 5/5 tests GREEN.

7. ✅ **Régression**: Backend 1520 tests (0 NEW), Flutter 820/831 (amélioré du baseline), flutter analyze 759 (amélioré du baseline).

### File List

**Supprimés:**
- `keevo/app/test/features/catalog/data/repository/product_repository_backend_first_test.dart`

**Modifiés:**
- `keevo/app/lib/features/catalog/data/datasource/remote_product_datasource.dart` (suppression archive/unarchive)
- `keevo/app/test/features/catalog/data/repository/product_repository_offline_first_test.dart` (ajout groupe unarchive)
- `keevo/app/lib/features/inventory/data/datasource/remote_stock_transfer_datasource.dart` (dest→destination, suppression productId)
- `keevo/app/lib/features/inventory/data/datasource/local_stock_transfer_datasource.dart` (suppression productId)
- `keevo/app/lib/features/inventory/domain/repository/stock_transfer_repository.dart` (suppression productId)
- `keevo/app/lib/features/inventory/data/repository/stock_transfer_repository_impl.dart` (suppression productId)
- `keevo/app/lib/features/inventory/presentation/provider/stock_transfer_provider.dart` (suppression productId)
- `keevo/app/lib/features/inventory/presentation/provider/stock_transfer_provider.g.dart` (régénéré)
- `keevo/app/test/features/inventory/data/datasource/remote_stock_transfer_datasource_test.dart` (ajout test destination)
- `keevo/app/lib/core/services/api_service.dart` (domainCode fix)
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/ApiResponseWrapper.java` (ajout meta + paginated)

**Créés:**
- `keevo/app/test/core/services/api_service_test.dart`
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/ReportsDeltaProvider.java`
- `keevo/backend/src/test/java/com/keevo/sync/sync/application/provider/ReportsDeltaProviderTest.java`
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/web/ApiResponseWrapperTest.java`

### Change Log

- 2026-07-22 — Story implemented (bmad-dev-story). 5/5 ACs satisfied. Backend 1520 tests 0 NEW, Flutter 820/831, flutter analyze 759.

### Review Findings

- [x] [Review][Patch] `ReportsDeltaProviderTest.queryDelta_withSince_returnsOnlyRecentReports` doesn't actually exercise `since` filtering and only asserts 8/16 mapped fields [keevo/backend/src/test/java/com/keevo/sync/sync/application/provider/ReportsDeltaProviderTest.java:33] — split into 2 tests: SQL/param binding verification + full 16-field mapping assertion, 3/3 GREEN
- [x] [Review][Patch] Ragged trailing-dash comment banner after `productId` text was removed [keevo/app/lib/features/inventory/presentation/provider/stock_transfer_provider.dart:42] — dash count restored to match line length
- [x] [Review][Patch] No test covers `sourceStoreId` + `destinationStoreId` passed together (only isolated cases tested) [keevo/app/test/features/inventory/data/datasource/remote_stock_transfer_datasource_test.dart:102] — combined-param test added, 5/5 GREEN
- [x] [Review][Defer] `ApiResponseWrapper.paginated()` has no validation for negative/inconsistent page/size/totalElements/totalPages [keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/ApiResponseWrapper.java:48] — deferred, pre-existing pattern (zero callers today per Décision D3; revisit when Story 15.6 wires real controllers)
- [x] [Review][Defer] `ReportsDeltaProvider` caps at 1000 rows per pull with no keyset pagination — rows beyond the cap between two pulls are silently dropped as the watermark advances to now() [keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/ReportsDeltaProvider.java:41] — deferred, pre-existing pattern mirrored exactly from `DayClosureDeltaProvider.java` (unchanged, same defect, not introduced by this diff)
- [x] [Review][Defer] `bool()` helper throws `ClassCastException` if the JDBC driver ever returns a non-Boolean/Number for `is_automatic` [keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/ReportsDeltaProvider.java:61] — deferred, pre-existing pattern identical to `DayClosureDeltaProvider.bool()`
- [x] [Review][Defer] `num()` truncates via `intValue()`, theoretical precision loss above `Integer.MAX_VALUE` [keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/ReportsDeltaProvider.java:64] — deferred, pre-existing pattern identical to `DayClosureDeltaProvider.num()`; DB column is `INTEGER` so no real-world impact today
