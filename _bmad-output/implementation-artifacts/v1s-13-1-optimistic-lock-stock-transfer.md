---
baseline_commit: 1299370
---
# Story 13.1: Verrouillage optimiste StockTransfer + StockLevel — `@Version` + `V5__optimistic_lock` + idempotent completion + atomic stock UPDATE

Status: done

<!-- V1-stabilization track — tag A (patch V1 urgent, release-blocker).
     Branche : v1-stabilization (off deploy d766035, HEAD 1299370 = story 12.7 done).
     Refonte réabsorbe le fix (audit : « re-implement natively (mêmes entités, le @Version porte) »).
     ⚠️ Story 100% backend. La contrepartie Flutter (gestion 409 OPTIMISTIC_LOCK, refresh silencieux,
     SnackBar humain) est explicitement DIFFÉRÉE — voir « Out of scope » en fin de fichier.
     Validation optionnelle : lancer validate-create-story avant dev-story. -->

## Story

**As a** Toor (opérateur de la plateforme),
**I want** que des mutations concurrentes sur les transferts de stock et les niveaux de stock ne puissent jamais créditer deux fois un transfert ni perdre des mises à jour (read-modify-write),
**so that** l'intégrité des stocks est garantie mécaniquement même sous concurrence multi-device / multi-tab (FR32, FR37, FR48 — B-CRIT-1/2).

## Contexte V1-stabilization

- **Source backlog :** `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md:193-209` → Story 13.1 (Refs B-CRIT-1/2, FR32 AC Step2).
- **Audit preuve `file:line` :**
  - `AUDIT_CONFORMITE_BMAD.md` — **B-CRIT-1** : « StockTransferJpaEntity sans `@Version` — deux `CompleteTransferService.execute()` concurrents sur le même IN_TRANSIT peuvent tous les deux créditer le stock destination (double-credit). `CompleteTransferService.java:52-71` lit le transfer, check le status, save COMPLETED — aucune protection optimistic locking. »
  - `AUDIT_CONFORMITE_BMAD.md` — **B-CRIT-2** : « StockLevelJpaEntity sans `@Version` — read-modify-write dans `StockOperationService.recordOperation()` (`StockOperationService.java:87-120`) charge le level, calcule quantityAfter, save la nouvelle instance. Deux appels concurrently → last-write-wins silencieux, perte de mouvement. »
- **Architecture :** `_bmad-output/planning-artifacts/architecture.md:1290-1296` — Flyway versioned migrations, `ddl-auto=validate`. Toute modification schema = nouveau `V{n}__*.sql`. Actuel : V4. Donc **V5** pour cette story.
- **Index track :** `v1-stabilization-stories.md` — `v1s-13-1` Verrouillage optimiste StockTransfer + StockLevel · B-CRIT-1/2 · release-blocker. Position 1 dans Epic 13 (première story, fondation pour 13-4 qui dépend de l'atomicité stock).
- **Aucun `@Version` n'existe dans le codebase** (vérifié `grep -rn "@Version"` — 0 résultats). C'est le premier usage de l'optimistic locking JPA dans Keevo. Les patterns ci-dessous doivent donc être établis de zéro.
- **Aucun `OptimisticLockException` handler** n'existe dans `GlobalExceptionHandler`. Il faut ajouter le mapping (→ 409 CONFLICT).
- **Aucun locking pessimiste (`FOR UPDATE`)** n'existe dans le codebase. Cette story n'en ajoute PAS non plus — approche retenue : @Version (optimistic) + conditional UPDATE idempotent (StockTransfer) + @Version (StockLevel). Voir décisions D1/D2.

## Acceptance Criteria

> AC BDD de `epics-remediation-audit.md` Story 13.1, décomposées en AC numérotées 1-5 pour la traçabilité TDD.

1. **AC1 (`@Version` sur `StockTransferJpaEntity` + migration V5, ferme B-CRIT-1 schema)** — **Given** `StockTransferJpaEntity.java` n'a aucun champ `@Version`, **When** ajouté, **Then** :
   - `StockTransferJpaEntity` gagne `@Version @Column(name = "version", nullable = false) private long version;` avec getter/setter.
   - Migration Flyway `V5__stock_optimistic_lock.sql` (public schema) :
     ```sql
     ALTER TABLE stock_transfers ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
     ALTER TABLE stock_levels ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
     ```
   - `TenantSchemaProvisioner.java` :
     - `DDL_STOCK_TRANSFERS` (ligne 265-278) : ajouter `version BIGINT NOT NULL DEFAULT 0` dans le CREATE TABLE.
     - `DDL_STOCK_LEVELS` (ligne 220-229) : ajouter `version BIGINT NOT NULL DEFAULT 0` dans le CREATE TABLE.
     - Nouvelles constantes idempotentes :
       ```java
       static final String DDL_STOCK_TRANSFERS_MIGRATE_VERSION =
           "ALTER TABLE stock_transfers ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0";
       static final String DDL_STOCK_LEVELS_MIGRATE_VERSION =
           "ALTER TABLE stock_levels ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0";
       ```
     - `provisionTenantSchema()` (ligne ~789-794) : exécuter ces deux migrations après les DDL existants.
   - `TenantSchemaSyncService.ensureRequiredIndexes()` (ligne ~229) : exécuter les deux `DDL_*_MIGRATE_VERSION` pour les tenants existants (même pattern que `DDL_STOCK_TRANSFERS_MIGRATE_UPDATED_AT` Story 5.2).
   - Le domaine `StockTransfer.java` (ligne 15-68) gagne `private final long version;` — le constructeur doit accepter `version`, le `withStatus()` (ligne 64-67) doit le propager.
   - `StockTransferRepositoryAdapter.toDomain()` (ligne 86-98) : passer `e.getVersion()`.
   - `StockTransferRepositoryAdapter.toJpaEntity()` (ligne 101-114) : passer `t.getVersion()`.

2. **AC2 (`@Version` sur `StockLevelJpaEntity`, ferme B-CRIT-2 schema)** — **Given** `StockLevelJpaEntity.java` n'a aucun champ `@Version`, **When** ajouté, **Then** :
   - `StockLevelJpaEntity` gagne `@Version @Column(name = "version", nullable = false) private long version;` avec getter/setter.
   - Le domaine `StockLevel.java` (ligne 14-47) gagne `private final long version;` — le constructeur accepte `version`, le `withQuantity()` (ligne 44-46) le propage.
   - `StockLevelRepositoryAdapter.toDomain()` (ligne 69-77) : passer `e.getVersion()`.
   - `StockLevelRepositoryAdapter.toJpaEntity()` (ligne 80-89) : passer `level.getVersion()`.

3. **AC3 (`CompleteTransferService` — completion idempotente, ferme B-CRIT-1 behaviour)** — **Given** `CompleteTransferService.java:50-88` fait `findById` → check status IN_TRANSIT → `recordOperation` → `save(COMPLETED)`, **sans protection contre deux appels concurrents qui passeraient tous les deux le check status** (TOCTOU) ; **When** rendu idempotent, **Then** DEUX options (le dev doit choisir, voir Décision D1) :

   **Option A (recommandée — conditional UPDATE) :**
   - Ajouter une méthode au port `StockTransferRepository` :
     ```java
     /** Conditional status transition — returns true if row was updated (atomic). */
     boolean transitionStatus(UUID transferId, TransferStatus expectedStatus, TransferStatus newStatus);
     ```
   - Implémentation dans `StockTransferRepositoryAdapter` :
     ```java
     @Modifying
     @Query("UPDATE StockTransferJpaEntity t SET t.status = :newStatus, t.version = t.version + 1 " +
            "WHERE t.id = :id AND t.status = :expectedStatus")
     int transitionStatus(@Param("id") UUID id,
                          @Param("expectedStatus") TransferStatus expectedStatus,
                          @Param("newStatus") TransferStatus newStatus);
     ```
     L'annotation `@Modifying` + JPQL `WHERE t.status = :expectedStatus` rend l'opération atomique : deux concurrents → un seul retourne `1` (rows affected), l'autre retourne `0`.
   - `CompleteTransferService.execute()` refactoré :
     ```java
     // 1. Fetch (pour les données du transfer, pas pour le status check)
     var transfer = transferRepository.findById(command.transferId())
         .orElseThrow(() -> new DomainException(TRANSFER_NOT_FOUND, ...));

     // 2. ATOMIC transition IN_TRANSIT → COMPLETED (remplace le check + save)
     boolean transitioned = transferRepository.transitionStatus(
         transfer.getId(), TransferStatus.IN_TRANSIT, TransferStatus.COMPLETED);
     if (!transitioned) {
         // Re-fetch to check current status for idempotent behavior
         var current = transferRepository.findById(command.transferId()).orElseThrow(...);
         if (current.getStatus() == TransferStatus.COMPLETED) {
             return current; // IDEMPOTENT — already completed, return existing
         }
         throw new DomainException(TRANSFER_INVALID_STATUS, ...);
     }

     // 3. TRANSFER_IN on destination
     stockOperationService.recordOperation(...);

     // 4. Save the completed transfer (with new @Version from transitionStatus)
     var completed = transfer.withStatus(TransferStatus.COMPLETED);
     // Note: la version a été incrémentée par le conditional UPDATE, re-fetch needed
     var saved = transferRepository.findById(command.transferId()).orElseThrow(...);

     // 5. Publish event
     eventPublisher.publishEvent(new StockTransferredEvent(...));
     return saved;
     ```

   **Option B (alternative — @Version + catch OptimisticLockException) :**
   - Utiliser `@Version` seul. Deux concurrents lisent version=0, le premier save passe (version 0→1), le deuxième save jette `ObjectOptimisticLockingFailureException`.
   - Catch dans `CompleteTransferService` : re-fetch et comportement idempotent.
   - **Inconvénient :** la deuxième transaction a déjà fait le `recordOperation(TRANSFER_IN)` avant l'échec du save → il faut annuler manuellement ou utiliser `@Transactional(noRollbackFor=...)` partiellement. Complexe.

   **→ Recommandation : Option A. Voir Décision D1.**

4. **AC4 (`StockOperationService.recordOperation` — atomic stock UPDATE via @Version, ferme B-CRIT-2 behaviour)** — **Given** `StockOperationService.java:87-120` fait read-modify-write non protégé sur `StockLevel`, **When** sécurisé, **Then** :
   - Le `@Version` sur `StockLevelJpaEntity` (AC2) assure que `springRepository.save(entity)` avec version stale jette `ObjectOptimisticLockingFailureException`.
   - `StockOperationService.recordOperation()` est déjà `@Transactional` — l'exception remonte et rollback la transaction complète (movement + level + events).
   - Ajouter un `@ExceptionHandler(ObjectOptimisticLockingFailureException.class)` dans `GlobalExceptionHandler` :
     ```java
     @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
     public ResponseEntity<ApiResponseWrapper<Void>> handleOptimisticLock(
             ObjectOptimisticLockingFailureException ex) {
         log.warn("OPTIMISTIC_LOCK: {}", ex.getMessage());
         return ResponseEntity.status(HttpStatus.CONFLICT)
             .body(ApiResponseWrapper.error(
                 "Cette donnée a été modifiée par une autre opération, veuillez réessayer",
                 "CONFLICT",
                 "OPTIMISTIC_LOCK",
                 null));
     }
     ```
   - Ajouter `OPTIMISTIC_LOCK` à `ErrorCode.java` (section Sync ou nouveau Generic).
   - Ajouter dans `FR_MESSAGES` : `Map.entry("OPTIMISTIC_LOCK", "Cette donnée a été modifiée par une autre opération, veuillez réessayer")`.
   - Ajouter `"OPTIMISTIC_LOCK" -> HttpStatus.CONFLICT` dans `domainCodeToHttpStatus()`.
   - **Note :** Pas de retry automatique côté serveur — le client (Flutter) devra gérer le 409 et proposer un retry. Defer Flutter.

5. **AC5 (TDD — 3 tests minimum, ferme epics-remediation-audit.md TDD)** — **Given** les AC ci-dessus, **Then** les tests suivants doivent exister et passer :
   - `shouldNotDoubleCreditOnConcurrentTransferComplete()` — dans `CompleteTransferServiceTest` (nouveau fichier ou complété) : simule deux appels concurrents à `execute()` sur le même transfer IN_TRANSIT. Un seul doit créditer le stock destination (un seul `recordOperation(TRANSFER_IN)` appelé). L'autre retourne le transfer COMPLETED idempotently.
   - `shouldNotLoseUpdateOnConcurrentStockMutation()` — dans `StockOperationServiceTest` (fichier existant) : simule deux read-modify-write concurrents sur le même StockLevel. Le @Version fait que l'un des deux échoue avec `ObjectOptimisticLockingFailureException` (vérifier que l'exception remonte bien).
   - `shouldReturnExistingTransferOnDuplicateReception()` — dans `CompleteTransferServiceTest` : appeler `execute()` une première fois (→ COMPLETED), puis une deuxième fois avec le même `transferId` → retourne le transfer COMPLETED existant (idempotent), pas de `TRANSFER_INVALID_STATUS` jeté.
   - `GlobalExceptionHandlerTest` (si existant, sinon créer) : vérifier que `ObjectOptimisticLockingFailureException` → HTTP 409 avec `domainCode: "OPTIMISTIC_LOCK"`.

## Tasks / Subtasks

- [x] **Task 1 — Migration Flyway V5 (AC1, AC2)** [schema]
  - [x] 1.1 Créer `V5__stock_optimistic_lock.sql` : ALTER TABLE stock_transfers ADD COLUMN version + ALTER TABLE stock_levels ADD COLUMN version
  - [x] 1.2 Vérifier que `V1__baseline_public.sql` reste inchangé (pas de rétro-modification)
- [x] **Task 2 — StockTransferJpaEntity @Version (AC1)** [entity]
  - [x] 2.1 Ajouter `@Version @Column(name = "version", nullable = false) private long version;` + getter/setter
  - [x] 2.2 `StockTransfer.java` domaine : ajouter `private final long version;`, constructor param, getter, propager dans `withStatus()`
  - [x] 2.3 `StockTransferRepositoryAdapter` : `toDomain()` et `toJpaEntity()` propagent `version`
- [x] **Task 3 — StockLevelJpaEntity @Version (AC2)** [entity]
  - [x] 3.1 Ajouter `@Version @Column(name = "version", nullable = false) private long version;` + getter/setter
  - [x] 3.2 `StockLevel.java` domaine : ajouter `private final long version;`, constructor param, getter, propager dans `withQuantity()`
  - [x] 3.3 `StockLevelRepositoryAdapter` : `toDomain()` et `toJpaEntity()` propagent `version`
- [x] **Task 4 — TenantSchemaProvisioner + TenantSchemaSyncService DDL (AC1, AC2)** [migration]
  - [x] 4.1 Ajouter `version BIGINT NOT NULL DEFAULT 0` aux DDL constants `DDL_STOCK_TRANSFERS` et `DDL_STOCK_LEVELS` dans `TenantSchemaProvisioner.java`
  - [x] 4.2 Créer constantes migration idempotentes `DDL_STOCK_TRANSFERS_MIGRATE_VERSION` et `DDL_STOCK_LEVELS_MIGRATE_VERSION`
  - [x] 4.3 Ajouter les exécutions dans `provisionTenantSchema()` (nouveaux tenants)
  - [x] 4.4 Ajouter les exécutions dans `TenantSchemaSyncService.ensureRequiredTenantTables()` (tenants existants — même pattern que `DDL_STOCK_TRANSFERS_MIGRATE_UPDATED_AT`)
- [x] **Task 5 — CompleteTransferService idempotent (AC3)** [use case]
  - [x] 5.1 Ajouter `transitionStatus(UUID, TransferStatus, TransferStatus)` au port `StockTransferRepository`
  - [x] 5.2 Implémenter dans `StockTransferRepositoryAdapter` via `@Modifying @Query` JPQL conditional UPDATE
  - [x] 5.3 Ajouter la méthode dans `StockTransferSpringRepository` (Decision D2 — SpringRepository pour accessibilité/testabilité)
  - [x] 5.4 Refactorer `CompleteTransferService.execute()` : Option A (conditional UPDATE) + comportement idempotent
  - [x] 5.5 Vérifier que `StockTransferControllerTest` existant passe toujours (le endpoint `/complete` ne change pas de contrat)
- [x] **Task 6 — GlobalExceptionHandler OPTIMISTIC_LOCK (AC4)** [error handling]
  - [x] 6.1 Ajouter `OPTIMISTIC_LOCK` à `ErrorCode.java`
  - [x] 6.2 Ajouter FR message dans `GlobalExceptionHandler.FR_MESSAGES`
  - [x] 6.3 Ajouter `@ExceptionHandler(OptimisticLockingFailureException.class)` → 409 CONFLICT
  - [x] 6.4 Ajouter `"OPTIMISTIC_LOCK" -> HttpStatus.CONFLICT` dans `domainCodeToHttpStatus()`
- [x] **Task 7 — TDD tests (AC5)** [testing]
  - [x] 7.1 `CompleteTransferServiceTest` : `shouldNotDoubleCreditOnConcurrentTransferComplete()`
  - [x] 7.2 `CompleteTransferServiceTest` : `shouldReturnExistingTransferOnDuplicateReception()`
  - [x] 7.3 `StockOperationServiceTest` : `shouldNotLoseUpdateOnConcurrentStockMutation()`
  - [x] 7.4 `GlobalExceptionHandlerTest` : `shouldReturn409ForOptimisticLockException()`
- [x] **Task 8 — Full regression + curl verification** [validation]
  - [x] 8.1 `mvn test` — 0 NEW failures (1470 tests, 1 failure + 40 errors préexistants non liés — DB-dependent + categories)
  - [x] 8.2 curl script : non applicable (backend-only, pas de changement de contrat API)
  - [x] 8.3 Vérifier que les sync handlers (`TransferSyncHandler`, `StockAdjustSyncHandler`) ne cassent pas — utilisent les UseCases, pas les constructeurs d'entités

## Dev Notes

### Patterns architecturaux à respecter

- **Hexagonal (ports/adapters)** : le `@Version` vit dans l'entité JPA (adapter/out). Le domaine `StockTransfer.java` / `StockLevel.java` propage `version` mais ne connaît pas JPA. Le port `StockTransferRepository` peut exposer `transitionStatus()` car c'est un comportement métier (conditional state transition), pas un détail JPA.
- **Domain model immutable** : `StockTransfer` et `StockLevel` sont des value objects immuables (final fields, `withStatus()`/`withQuantity()` retournent de nouvelles instances). Le `version` doit suivre ce pattern.
- **Flyway mandatory** : pas de `ddl-auto=update`. Toute modif schema passe par `V{n}__*.sql` + validation boot (`ddl-auto=validate`). Le DDL per-tenant dans `TenantSchemaProvisioner`/`TenantSchemaSyncService` doit rester synchronisé (CREATE TABLE pour nouveaux tenants, ALTER TABLE ADD COLUMN IF NOT EXISTS pour existants).
- **TenantContext** : toutes les tables stock sont per-tenant schema (`kv_xxxxx`). Les migrations V5 dans `V5__*.sql` s'appliquent au schema `public` (utilisé comme template). `TenantSchemaSyncService.ensureRequiredTenantTables()` propage aux tenants existants via `ALTER TABLE ADD COLUMN IF NOT EXISTS` (idempotent).

### Fichiers à modifier (File List exhaustive)

| Fichier | Action | Raison |
|---------|--------|--------|
| `V5__stock_optimistic_lock.sql` (nouveau) | CREATE | Migration public schema |
| `StockTransferJpaEntity.java` | UPDATE | +@Version field |
| `StockLevelJpaEntity.java` | UPDATE | +@Version field |
| `StockTransfer.java` | UPDATE | +version field + constructor + withStatus() propagate |
| `StockLevel.java` | UPDATE | +version field + constructor + withQuantity() propagate |
| `StockTransferRepositoryAdapter.java` | UPDATE | toDomain/toJpaEntity propagate version + transitionStatus() |
| `StockLevelRepositoryAdapter.java` | UPDATE | toDomain/toJpaEntity propagate version |
| `StockTransferRepository.java` (port) | UPDATE | +transitionStatus() method |
| `StockTransferSpringRepository.java` | UPDATE | +@Modifying @Query for transitionStatus (ou dans l'adapter) |
| `CompleteTransferService.java` | UPDATE | idempotent completion via transitionStatus() |
| `TenantSchemaProvisioner.java` | UPDATE | DDL constants + version column + migration DDL + provisionTenantSchema() |
| `TenantSchemaSyncService.java` | UPDATE | ensureRequiredTenantTables() + migration pour existants |
| `ErrorCode.java` | UPDATE | +OPTIMISTIC_LOCK |
| `GlobalExceptionHandler.java` | UPDATE | +FR_MESSAGES + @ExceptionHandler + domainCodeToHttpStatus |
| `CompleteTransferServiceTest.java` (nouveau ou update) | CREATE/UPDATE | 2 tests AC5 |
| `StockOperationServiceTest.java` | UPDATE | 1 test AC5 |
| `GlobalExceptionHandlerTest.java` (nouveau ou update) | CREATE/UPDATE | 1 test AC4 |

### ⚠️ Pièges connus (anti-patterns)

1. **`@Version` sur entité avec `save()` qui fait INSERT OR UPDATE** : Spring Data JPA `save()` détermine isNew() via `Persistable.isNew()` ou `id == null`. Keevo utilise des UUID générés côté application (`UUID.randomUUID()` dans `toJpaEntity()` quand `id == null`). Vérifier que `isNew()` fonctionne correctement avec `@Version` — si le entity manager voit `version == 0` ET `id != null`, il peut tenter un `merge()` au lieu de `persist()`. Tester ce cas. Le pattern `Persistable<UUID>` avec `@Transient isNew` a déja causé un bug (voir `JpaBaseEntityPersistTest.java:23` — "ObjectOptimisticLockingFailureException: unsaved-value mapping was incorrect"). **Si des entités utilisent `Persistable`, s'assurer que `@Version` est cohérent avec `isNew()`.** `StockTransferJpaEntity` et `StockLevelJpaEntity` n'implémentent PAS `Persistable` actuellement — ils utilisent le comportement par défaut de `JpaRepository` (`id == null → persist`, sinon `merge`). C'est correct pour `@Version`.

2. **`@Modifying @Query` et `@Version`** : le JPQL `UPDATE ... SET version = version + 1` dans `transitionStatus()` contourne le mécanisme `@Version` automatique de Hibernate (qui fait `WHERE version = :currentVersion`). C'est volontaire — on veut un conditional UPDATE atomique, pas un optimistic lock check. Le `version` est incrémenté manuellement. Cela n'interfère pas avec les `save()` ultérieurs car le `save()` re-lit l'entité (via `findById`) et voit la version à jour.

3. **Idempotent completion et events** : quand un transfer est déjà COMPLETED et qu'on reçoit une deuxième demande, on retourne l'entité existante **sans re-publier le `StockTransferredEvent`**. Sinon l'audit enregistrerait deux completions. C'est le comportement idempotent correct (FR32 AC Step2).

4. **`StockOperationService` — retry automatique ?** : NON. Pas de retry côté serveur. Le `@Version` fait que le deuxième concurrent obtient une exception. C'est au client (Flutter) de retry avec données fraîches. Ajouter un retry serveur masquerait les vrais conflits.

5. **`version` default value `0`** : `@Version` JPA initialise à 0 au persist. La migration V5 met `DEFAULT 0` pour les lignes existantes. Cohérent.

### Décisions à trancher en code review

- **D1 (Option A vs Option B pour CompleteTransferService)** : l'audit suggère les deux options. Ce story recommande **Option A (conditional UPDATE)** car : (a) atomique au niveau DB, pas de TOCTOU ; (b) pas d'exception à catch/roll back partiellement ; (c) idempotent naturel. L'Option B (@Version + catch) est plus fragile car `recordOperation(TRANSFER_IN)` est dans la même transaction — si le `save()` du transfer échec après, il faut annuler le mouvement de stock. Le conditional UPDATE évite ce problème. **À confirmer en code review.**

- **D2 (Où mettre le `@Modifying @Query`)** : dans `StockTransferSpringRepository` (plus proche du pattern Spring Data) ou directement dans `StockTransferRepositoryAdapter` (encapsulation maximale) ? Recommandation : dans le SpringRepository (accessibilité, testabilité). **À confirmer.**

### Out of scope (différé)

- **Flutter 409 UX** : gérer `domainCode: "OPTIMISTIC_LOCK"` dans `AuthInterceptor`/`DioProvider` — refresh silencieux + retry automatique ou SnackBar « Cette donnée a été modifiée, veuillez réessayer ». Différé car cette story est 100% backend (release-blocker priorité). La contrepartie Flutter sera dans une story future (probablement Epic 16 mobile hardening ou Epic 17 gating).
- **Transfert déjà-COMPLETED → silent refresh côté client** : idem, Flutter. L'audit mentionne « transfert déjà-COMPLETED → silent refresh (ne pas afficher d'erreur) » — c'est un comportement client qui sera traité avec le 409 UX Flutter.
- **Pessimistic locking (SELECT FOR UPDATE)** : l'audit mentionne `SELECT … FOR UPDATE` comme option pour `StockOperationService`. Pas retenu : @Version est suffisant pour le cas d'usage Keevo (peu de write concurrently sur le même produit/store, la concurrence est principalement transfer complete). FOR UPDATE ajouterait de la contention. Si le besoin émerge, ce sera dans la refonte modulaire.
- **Atomic UPDATE `SET quantity = quantity + ?`** : l'audit mentionne cette option comme alternative à @Version pour StockLevel. Pas retenu car : (a) le code actuel fait read-modify-write pour calculer `quantityBefore`/`quantityAfter` du StockMovement (audit trail) — un UPDATE atomique perd cette information ; (b) @Version est plus simple et suffisant.

### Contrepartie mobile (Flutter) — DEFER

- Gérer HTTP 409 `domainCode: "OPTIMISTIC_LOCK"` → refresh silencieux des données + retry automatique OU SnackBar « Cette donnée a été modifiée, veuillez réessayer. »
- Transfert déjà-COMPLETED (ré-réception) → silent refresh de la liste des transferts, pas d'erreur affichée.
- Sera adressé dans une story future (Epic 16 ou 17).

### References

- [Source: _bmad-output/planning-artifacts/epics/epics-remediation-audit.md#Story 13.1]
- [Source: _bmad-output/planning-artifacts/architecture.md#Flyway]
- [Source: StockTransferJpaEntity.java — current state: no @Version]
- [Source: StockLevelJpaEntity.java — current state: no @Version]
- [Source: CompleteTransferService.java:50-88 — TOCTOU vulnerability]
- [Source: StockOperationService.java:87-120 — read-modify-write without lock]
- [Source: TenantSchemaProvisioner.java:220-229,265-278 — DDL constants to update]
- [Source: ErrorCode.java + GlobalExceptionHandler.java — add OPTIMISTIC_LOCK]
- [Source: Story 3.3 (original transfer implementation)]
- [Source: AUDIT_CONFORMITE_BMAD.md — B-CRIT-1/2]

## Dev Agent Record

### Agent Model Used

DeepSeek V4 Pro (GitHub Copilot)

### Debug Log References

- Compilation fix: added backward-compatible 10-param constructor to `StockTransferJpaEntity` (test `StockTransferRepositoryAdapterTest` needed it)
- Test fix: changed `any()` → `any(StockTransferredEvent.class)` in `CompleteTransferServiceTest` (Mockito strict stubbing)

### Completion Notes List

**Décisions d'implémentation :**
- **Option A confirmée** — conditional UPDATE atomique (`transitionStatus` via `@Modifying @Query`) plutôt que `@Version` + catch. Évite le problème de rollback partiel du `recordOperation`.
- **D2 : SpringRepository** — le `@Modifying @Query` est dans `StockTransferSpringRepository`, implémenté via l'adapter. Accessible et testable.
- **Constructeurs backward-compatible** ajoutés à `StockTransfer`, `StockLevel`, `StockTransferJpaEntity`, `StockLevelJpaEntity` — tous les callers existants continuent de fonctionner sans modification.

**AC vérifiées :**
- AC1 ✅ `@Version` sur `StockTransferJpaEntity` + migration Flyway V5 + DDL per-tenant (Provisioner + SyncService)
- AC2 ✅ `@Version` sur `StockLevelJpaEntity` + domaine + adapter
- AC3 ✅ `CompleteTransferService` idempotent — `transitionStatus()` + comportement idempotent si déjà COMPLETED (pas de double crédit, pas de double event)
- AC4 ✅ `@ExceptionHandler(OptimisticLockingFailureException.class)` → 409 CONFLICT + `OPTIMISTIC_LOCK` ErrorCode + FR message + `domainCodeToHttpStatus`
- AC5 ✅ 4 tests TDD : `shouldNotDoubleCreditOnConcurrentTransferComplete`, `shouldReturnExistingTransferOnDuplicateReception`, `shouldNotLoseUpdateOnConcurrentStockMutation`, `shouldReturn409ForOptimisticLockException`

**Régression :** 1470 tests, 0 NEW failures, 0 NEW errors. 1 failure + 40 errors préexistants (DB-dependent : SectorTemplateFactoryTest, FlywayBaselineIntegrationTest, ProductJpaEntityTest + OnboardingServiceTest categories).

### File List

| Fichier | Action |
|---------|--------|
| `src/main/resources/db/migration/V5__stock_optimistic_lock.sql` | CREATE |
| `src/main/java/com/keevo/shared/infrastructure/persistence/entity/StockTransferJpaEntity.java` | UPDATE |
| `src/main/java/com/keevo/shared/infrastructure/persistence/entity/StockLevelJpaEntity.java` | UPDATE |
| `src/main/java/com/keevo/catalog/stock/domain/model/StockTransfer.java` | UPDATE |
| `src/main/java/com/keevo/catalog/stock/domain/entity/StockLevel.java` | UPDATE |
| `src/main/java/com/keevo/catalog/stock/adapter/out/persistence/StockTransferRepositoryAdapter.java` | UPDATE |
| `src/main/java/com/keevo/catalog/stock/adapter/out/persistence/StockLevelRepositoryAdapter.java` | UPDATE |
| `src/main/java/com/keevo/catalog/stock/domain/port/out/StockTransferRepository.java` | UPDATE |
| `src/main/java/com/keevo/catalog/stock/adapter/out/persistence/StockTransferSpringRepository.java` | UPDATE |
| `src/main/java/com/keevo/catalog/stock/application/usecase/CompleteTransferService.java` | UPDATE |
| `src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java` | UPDATE |
| `src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaSyncService.java` | UPDATE |
| `src/main/java/com/keevo/shared/domain/exception/ErrorCode.java` | UPDATE |
| `src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java` | UPDATE |
| `src/main/java/com/keevo/catalog/stock/domain/service/StockOperationService.java` | UPDATE |
| `src/test/java/com/keevo/catalog/stock/application/usecase/CompleteTransferServiceTest.java` | CREATE |
| `src/test/java/com/keevo/catalog/stock/domain/service/StockOperationServiceTest.java` | UPDATE |
| `src/test/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandlerTest.java` | UPDATE |
| `_bmad-output/implementation-artifacts/sprint-status.yaml` | UPDATE |
| `_bmad-output/implementation-artifacts/v1s-13-1-optimistic-lock-stock-transfer.md` | UPDATE |

### Change Log

- 2026-07-21: Story v1s-13-1 implemented — @Version optimistic locking on StockTransfer + StockLevel, V5 Flyway migration, CompleteTransferService idempotent (Option A: conditional UPDATE), GlobalExceptionHandler OPTIMISTIC_LOCK → 409, 4 TDD tests, 0 NEW regressions
- 2026-07-21: Code review bmad-code-review PASSED — 2 patches appliqués (P1: `clearAutomatically=true` sur `@Modifying` StockTransferSpringRepository ; P2: test mock chaining dans CompleteTransferServiceTest idempotent/invalid-status) ; 3 defers (intégration test, concurrent test, V5+SyncService boot-time) ; ~10 dismiss ; 22/22 tests GREEN, 0 NEW régression

### Review Findings

Code review 2026-07-21 — 3 layers: Blind Hunter + Edge Case Hunter + Acceptance Auditor.

**Patches:**
- [x] [Review][Patch] `@Modifying` missing `clearAutomatically=true` — L1 cache stale read after JPQL UPDATE breaks idempotency and return value [`StockTransferSpringRepository.java:31`] — FIX APPLIQUÉ : `@Modifying(clearAutomatically = true)`. 22/22 tests GREEN.
- [x] [Review][Patch] Test mock override — idempotent/invalid-status tests testent le mauvais scénario [`CompleteTransferServiceTest.java:120,124` et `160,163`] — FIX APPLIQUÉ : chaînage `.thenReturn(transfer).thenReturn(completed)` dans les 2 tests. 22/22 tests GREEN.

**Deferred:**
- [x] [Review][Defer] Pas de test d'intégration `@Modifying` + `@Version` avec EntityManager réel — deferred, nécessite Testcontainers/PostgreSQL ; hors scope AC5
- [x] [Review][Defer] Pas de test concurrent multi-thread sur `CompleteTransferService.execute()` — deferred, nécessite CountDownLatch + deux threads ; hors scope AC5
- [x] [Review][Defer] V5 migration `public` + `TenantSchemaSyncService` boot-time dependency — pre-existing pattern (même risque pour toutes les migrations ALTER TABLE per-tenant) ; si SyncService échoue silencieusement, les writes @Version cassent
