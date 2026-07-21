---
baseline_commit: 1299370
---
# Story 13.2: Corriger le verrouillage permanent porte sync 7j — `GREATEST(last_push_at, last_pull_at)` + `deviceId` lié à `actorId`

Status: review

<!-- V1-stabilization track — tag A (patch V1 urgent, release-blocker).
     Branche : v1-stabilization (off deploy d766035, HEAD 1299370 = story 13.1 done).
     Refonte réabsorbe le fix (audit : « re-implement dans sync modulaire »).
     ⚠️ Story 100% backend. La contrepartie Flutter (deviceId stable + gestion 423) est
     DÉJÀ en place (story 5.4) — cette story ne touche PAS le client.
     Validation optionnelle : lancer validate-create-story avant dev-story. -->

## Story

**As a** Toor (opérateur de la plateforme),
**I want** qu'un device qui a effectué un pull réussi après une fenêtre de 7 jours soit débloqué et puisse à nouveau pusher (FR74),
**so that** un device actif qui synchronise régulièrement ne soit pas verrouillé définitivement alors qu'il dispose de données fraîches (B-CRIT-3).

## Contexte V1-stabilization

- **Source backlog :** `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md:211-226` → Story 13.2 (Refs B-CRIT-3, FR74, 5-4 AC10).
- **Audit preuve `file:line` :**
  - `AUDIT_CONFORMITE_BMAD.md` — **B-CRIT-3** : « `SyncGateCheckService.isStalePush(deviceId)` vérifie `last_push_at` exclusivement. Un device qui pull régulièrement mais ne push jamais (ex : terminal vitrine qui reçoit les prix mais n'a pas de ventes) est verrouillé à vie après 7 jours, même si ses données sont fraîches. »
  - `AUDIT_CONFORMITE_BMAD.md` — **deviceId rotation bypass** : « `SyncController.push` lit `deviceId` du body (`request.deviceId()`) et appelle `syncGateCheckService.isStalePush(deviceId)` sans vérifier que ce `deviceId` appartient à l'`actorId` authentifié. Un attaquant peut forger un `deviceId` correspondant à un device inactif d'un autre tenant/user pour bypass la gate. »
- **Architecture :** `architecture.md:40` — **Offline-First Sync (FR69-76)**: 7-day offline, <60s sync, delta-based conflicts, zero data loss.
- **Index track :** `sprint-status.yaml` — `v1s-13-2` Corriger le verrouillage permanent porte sync 7j · B-CRIT-3 · release-blocker. Position 2 dans Epic 13 (dépend de 13.1 @Version — les updates `user_sync_state` doivent être cohérents avec les nouveaux locking patterns).
- **Story 5.4 déjà implémentée** — `user_sync_state` DDL + Flutter gate (open/warning/critical/blocked) + backend `SyncGateCheckService` + `SyncController` push gate 423. Cette story **corrige** la logique de la 5.4, ne la remplace pas.

## Problème mécanique identifié

### Problème 1 : Pull ne lève pas la gate (verrouillage permanent)

**État actuel (`SyncGateCheckService.java:40-45`) :**
```java
public boolean isStalePush(String deviceId) {
    return userSyncStateRepository.findByDeviceId(deviceId)
            .map(s -> s.lastPushAt() != null &&
                      s.lastPushAt().isBefore(Instant.now().minus(GATE_THRESHOLD_DAYS, ChronoUnit.DAYS)))
            .orElse(false);
}
```

**Scénario de bug :**
1. Device A push il y a 8 jours → `last_push_at = 8 days ago`
2. Device A pull aujourd'hui → `last_pull_at = NOW()` (via `upsertOnPull`)
3. Device A tente de pusher → `isStalePush` vérifie `last_push_at = 8 days ago > 7` → **BLOQUÉ** (HTTP 423)
4. L'utilisateur a des données fraîches (grâce au pull) mais ne peut pas pusher ses opérations offline
5. **Deadlock** : le device est sain mais la gate le rejette → l'utilisateur doit réinstaller l'app ou attendre 7 jours sans activité

### Problème 2 : deviceId non lié à actorId (rotation bypass)

**État actuel (`SyncController.push`) :**
```java
String deviceId = request.deviceId();  // body — forgeable
// ...
if (syncGateCheckService.isStalePush(deviceId)) { ... }
```

Aucune vérification que `deviceId` appartient bien à l'`actorId` du JWT. Un attaquant qui connaît le `deviceId` d'un autre device (ex : via l'endpoint `/api/v1/sync/devices` qui expose `deviceId` + `userId`) peut :
- Utiliser ce `deviceId` pour pusher **ses propres opérations** comme si elles venaient de l'autre device
- Bypasser la gate si l'autre device est à jour (last_push_at récent)

## Acceptance Criteria

> AC BDD de `epics-remediation-audit.md` Story 13.2, décomposées en AC numérotées 1-3 pour la traçabilité TDD.

1. **AC1 (Pull lève la gate — `GREATEST(last_push_at, last_pull_at)`, ferme B-CRIT-3 staleness)** — **Given** `SyncGateCheckService.isStalePush` vérifie `last_push_at` exclusivement, **When** corrigé, **Then** :
   - La staleness est calculée contre `GREATEST(last_push_at, last_pull_at)` (option A du audit — la plus simple)
   - Implémentation recommandée : modifier `findByDeviceId` pour exposer `lastPullAt` (déjà dans le modèle `UserSyncState`) et remplacer la condition :
     ```java
     public boolean isStalePush(String deviceId) {
         return userSyncStateRepository.findByDeviceId(deviceId)
                 .map(s -> {
                     Instant lastActivity = s.lastPushAt();
                     if (s.lastPullAt() != null &&
                         (lastActivity == null || s.lastPullAt().isAfter(lastActivity))) {
                         lastActivity = s.lastPullAt();
                     }
                     return lastActivity != null &&
                            lastActivity.isBefore(Instant.now().minus(GATE_THRESHOLD_DAYS, ChronoUnit.DAYS));
                 })
                 .orElse(false);
     }
     ```
   - **Note :** Option B du audit (stamp `last_push_at = NOW()` lors d'un pull réussi) **REJETÉE** — elle sémantiquement ment sur le dernier push réel, et casserait le monitoring (AdminSyncMonitoringService qui affiche `last_push_at` dans le dashboard super-admin). Option A préserve la sémantique : `last_push_at` reste le vrai dernier push, mais la gate considère l'activité pull aussi.

2. **AC2 (`deviceId` lié à `actorId` — ferme le rotation bypass, B-CRIT-3 auth)** — **Given** `SyncController.push` lit `deviceId` du body sans vérifier l'ownership, **When** sécurisé, **Then** :
   - Après `findByDeviceId(deviceId)`, si l'entrée existe et `s.userId() != currentActorId`, la requête est rejetée
   - Réponse : HTTP 403 FORBIDDEN avec `domainCode: "DEVICE_ID_MISMATCH"` + message FR « Ce deviceId n'est pas associé à votre compte »
   - Ajouter `DEVICE_ID_MISMATCH` à `ErrorCode.java`
   - Ajouter dans `FR_MESSAGES` : `Map.entry("DEVICE_ID_MISMATCH", "Ce device n'est pas associé à votre compte")`
   - Ajouter `"DEVICE_ID_MISMATCH" -> HttpStatus.FORBIDDEN` dans `domainCodeToHttpStatus()`
   - Ajouter un `@ExceptionHandler` global (ou traiter dans le controller) pour `DeviceIdMismatchException` (nouveau DomainException)
   - **Exception :** si `findByDeviceId` retourne `Optional.empty()` (device inconnu), le push est autorisé (premier push de ce device — backward-compatible avec le comportement existant `isStalePush` → false)
   - **Exception :** si l'entry n'a pas encore de `userId` (ancien device pré-5.4 avec `last_push_at` mais `user_id` null), autoriser — migration progressive
   - Vérification ajoutée également sur le endpoint `GET /api/v1/sync/pull` (header `X-Device-Id`) :
     - Si `X-Device-Id` est fourni ET le device existe ET `userId != actorId` → HTTP 403 FORBIDDEN

3. **AC3 (TDD — 2 tests minimum, ferme epics-remediation-audit.md TDD)** — **Given** les AC ci-dessus, **Then** les tests suivants doivent exister et passer :
   - `shouldUnblockPushAfterSuccessfulPull()` — dans `SyncGateCheckServiceTest` :
     1. Créer un `UserSyncState` avec `lastPushAt = 8 days ago`, `lastPullAt = NOW()`
     2. `isStalePush(deviceId)` → **false** (pull récent lève la gate)
   - `shouldStillBlockWhenBothPushAndPullAreStale()` — dans `SyncGateCheckServiceTest` :
     1. Créer un `UserSyncState` avec `lastPushAt = 8 days ago`, `lastPullAt = 8 days ago`
     2. `isStalePush(deviceId)` → **true** (les deux sont stale)
   - `shouldRejectDeviceIdNotOwnedByActor()` — dans `SyncControllerPushGateTest` (nouveau test) :
     1. Enregistrer un device avec `deviceId = "device-A"` lié à `actorId = UUID_A`
     2. Pusher avec `deviceId = "device-A"` mais JWT authenticant `actorId = UUID_B`
     3. Response → HTTP 403 + `domainCode: "DEVICE_ID_MISMATCH"`
   - `shouldAllowDeviceIdWhenNotYetRegistered()` — dans `SyncControllerPushGateTest` :
     1. Device inconnu → push autorisé (backward-compatible)
   - `shouldAllowDeviceIdWhenOwnedByActor()` — dans `SyncControllerPushGateTest` :
     1. Device enregistré avec `userId = currentActorId` → push autorisé

## Tasks / Subtasks

- [x] **Task 1 — SyncGateCheckService : GREATEST(last_push_at, last_pull_at) (AC1)** [use case]
  - [x] 1.1 Modifier `isStalePush()` pour calculer `lastActivity = max(lastPushAt, lastPullAt)`
  - [x] 1.2 Ajouter les tests unitaires `shouldUnblockPushAfterSuccessfulPull`, `shouldStillBlockWhenBothPushAndPullAreStale`
- [x] **Task 2 — DeviceId ownership check (AC2)** [security]
  - [x] 2.1 Ajouter `DEVICE_ID_MISMATCH` à `ErrorCode.java`
  - [x] 2.2 Utiliser DomainException existant avec ErrorCode.DEVICE_ID_MISMATCH (pas de sous-classe dédiée)
  - [x] 2.3 Ajouter `FR_MESSAGES` entry + `domainCodeToHttpStatus` mapping
  - [x] 2.4 Ajouter `DEVICE_ID_MISMATCH` dans `domainCodeToHttpStatus` → FORBIDDEN (via DomainException handler existant)
  - [x] 2.5 Dans `SyncController.push()` : après `findByDeviceId`, vérifier ownership
  - [x] 2.6 Dans `SyncController.pull()` : vérifier ownership si `X-Device-Id` fourni
  - [x] 2.7 Ajouter les tests `shouldRejectDeviceIdNotOwnedByActor`, `shouldAllowDeviceIdWhenNotYetRegistered`, `shouldAllowDeviceIdWhenOwnedByActor`, `shouldAllowDeviceIdWhenUserIdNull`
- [x] **Task 3 — Full regression + validation** [testing]
  - [x] 3.1 `mvn test` — 0 NEW failures (1 pre-existing flaky: OnboardingServiceTest timestamp; 40 pre-existing DB-dependent errors)
  - [x] 3.2 Vérifier que `SyncControllerPushGateTest` existant (push_staleDevice_returns423) passe toujours
  - [x] 3.3 Vérifier que `SyncGateCheckServiceTest` existant (5 tests) passe toujours
  - [x] 3.4 Vérifier que `AdminSyncMonitoringService` (qui lit `last_push_at` pour le dashboard super-admin) n'est pas impacté — l'Option A préserve `last_push_at` réel

## Dev Notes

### Patterns architecturaux à respecter

- **Hexagonal (ports/adapters)** : la gate check est dans `application/service` (`SyncGateCheckService`). Le port `UserSyncStateRepository` expose déjà `findByDeviceId` qui retourne un `UserSyncState` avec `lastPullAt` — aucune modification de port nécessaire.
- **Domain exceptions** : utiliser `DomainException` existant avec le nouveau code `DEVICE_ID_MISMATCH` (même pattern que `TRANSFER_NOT_FOUND`, `FORBIDDEN`, etc. dans `GlobalExceptionHandler`).
- **Pas de modification du modèle `UserSyncState`** : le record a déjà `lastPushAt` et `lastPullAt`. Pas de migration DDL nécessaire — les colonnes existent depuis Story 5.4.
- **Option A (GREATEST) privilégiée** : l'audit proposait deux options :
  - **Option A** : `GREATEST(last_push_at, last_pull_at)` — modifie la logique de lecture uniquement
  - **Option B** : stamp `last_push_at = NOW()` lors d'un pull — modifie la sémantique de `last_push_at` et casserait le monitoring super-admin
  - **→ Option A retenue** (voir Décision D1)

### Fichiers à modifier (File List exhaustive)

| Fichier | Action | Raison |
|---------|--------|--------|
| `SyncGateCheckService.java` | UPDATE | isStalePush() → GREATEST(lastPushAt, lastPullAt) |
| `SyncController.java` | UPDATE | + deviceId ownership check dans push() et pull() |
| `ErrorCode.java` | UPDATE | +DEVICE_ID_MISMATCH |
| `GlobalExceptionHandler.java` | UPDATE | +FR_MESSAGES + @ExceptionHandler + domainCodeToHttpStatus |
| `SyncGateCheckServiceTest.java` | UPDATE | +2 tests AC1 |
| `SyncControllerPushGateTest.java` | UPDATE | +3 tests AC2 |
| `SyncController.java` (pull endpoint) | UPDATE | + deviceId ownership check |

### ⚠️ Pièges connus (anti-patterns)

1. **Option B tentante mais incorrecte** : modifier `upsertOnPull` pour stamp `last_push_at = NOW()` semblerait simple, mais :
   - `AdminSyncMonitoringService` affiche `last_push_at` dans le dashboard super-admin comme « dernier push réel » — cette sémantique serait cassée
   - Les admins ne pourraient plus distinguer un vrai push d'un pull
   - L'audit lui-même propose l'option A (GREATEST) comme recommandation

2. **Device inconnu (premier push)** : le comportement existant `isStalePush` retourne `false` si device non trouvé — ce comportement DOIT être préservé. Si on rejetait les deviceIds inconnus, on casserait l'onboarding des nouveaux devices.

3. **Device existant avec `userId` null** : les devices créés avant Story 5.4 peuvent avoir `user_id` null (la colonne a été ajoutée dans 5.4). Pour ces devices, **tolérer** le push (ne pas rejeter) — migration progressive. L'audit ne mentionne pas ce cas explicitement, mais il existe dans la DB de production.

4. **`X-Device-Id` header sur pull** : actuellement optionnel (`required = false`). Si absent, pas de check. Si présent mais mismatch → 403. Si présent et match → OK.

5. **Pas de changement Flutter nécessaire** :
   - Le client Flutter utilise déjà un `deviceId` stable (stocké dans `_secureStorage` clé `keevo_device_id`)
   - Après pull, le client met à jour `kLastSyncAtKey` dans SharedPreferences → la gate locale s'ouvre
   - Le client gère déjà HTTP 423 via `SyncRequiredException`
   - Le client gère déjà HTTP 403 (via `AuthInterceptor` existant)
   - **→ Cette story est 100% backend**

6. **Pas de changement DDL** : les colonnes `last_push_at` et `last_pull_at` existent déjà dans `user_sync_state` (Story 5.4). Pas de migration Flyway nécessaire.

7. **`upsertOnPull` existant** : la méthode `upsertOnPull` dans `UserSyncStateRepositoryAdapter` met déjà à jour `last_pull_at = NOW()` sur pull. Aucun changement nécessaire — c'est le comportement backend qui utilise `last_pull_at` qui doit changer.

### Décisions à trancher en code review

- **D1 (Option A vs Option B)** : cette story recommande **Option A (GREATEST)** car :
  - (a) préserve la sémantique de `last_push_at` pour le monitoring super-admin
  - (b) modifie uniquement la logique de lecture (un seul fichier)
  - (c) pas de migration DDL nécessaire
  - (d) l'audit lui-même suggère cette option en premier
  - **À confirmer en code review.**

- **D2 (DeviceId mismatch → 403 vs 400)** : cette story recommande **403 FORBIDDEN** car c'est une violation d'auth (device appartient à un autre user). Un 400 serait sémantiquement incorrect (le format de la requête est valide). **À confirmer.**

- **D3 (Vérification pull sur deviceId mismatch)** : cette story recommande de vérifier l'ownership aussi sur `GET /pull` (header `X-Device-Id`) pour cohérence. Un attaquant pourrait sinon pull des données d'un autre tenant via un deviceId forgé. **À confirmer.**

### Out of scope (différé)

- **Flutter UI pour DEVICE_ID_MISMATCH** : si le client reçoit 403 DEVICE_ID_MISMATCH, il devrait probablement régénérer un nouveau deviceId (cas de réinstallation sur un device partagé). Différé — l'interceptor actuel affiche déjà un message d'erreur générique pour 403.
- **Migration rétroactive des devices sans userId** : les devices pré-5.4 avec `user_id = null` ne sont pas rejetés (tolérance). Une future story pourra nettoyer ces entrées orphelines.

## Dev Agent Record

### Implementation Plan

**Task 1 (AC1) — GREATEST(last_push_at, last_pull_at)** : Modifier `SyncGateCheckService.isStalePush()` pour calculer `lastActivity = max(lastPushAt, lastPullAt)` au lieu de vérifier `lastPushAt` exclusivement. Option A retenue (préserve la sémantique de `last_push_at` pour le monitoring super-admin). Aucune modification de port ou de modèle nécessaire — `UserSyncState` expose déjà `lastPullAt`.

**Task 2 (AC2) — DeviceId ownership check** : 
- Ajout de `DEVICE_ID_MISMATCH` dans `ErrorCode.java`
- Ajout du message FR et du mapping HTTP 403 dans `GlobalExceptionHandler.java`
- Dans `SyncController.push()` : après `findByDeviceId(deviceId)`, vérification `s.userId() != null && !s.userId().equals(actorId)` → `DomainException(ErrorCode.DEVICE_ID_MISMATCH, ...)`
- Dans `SyncController.pull()` : même vérification si `X-Device-Id` header fourni
- Pas de sous-classe `DeviceIdMismatchException` — utilisation directe de `DomainException` avec `ErrorCode.DEVICE_ID_MISMATCH` (pattern cohérent avec le reste du codebase)
- Tolérance device inconnu (Optional.empty → autorisé, backward-compatible)
- Tolérance userId null (device pré-5.4 → autorisé, migration progressive)

### Completion Notes

**Implémenté :**
- `SyncGateCheckService.isStalePush()` → `GREATEST(lastPushAt, lastPullAt)` — un pull récent lève la gate même si le dernier push est vieux
- `SyncController.push()` + `pull()` — deviceId ownership check contre `actorId` du JWT
- `ErrorCode.DEVICE_ID_MISMATCH` → HTTP 403 FORBIDDEN avec message FR

**Tests :**
- `SyncGateCheckServiceTest` : 7 tests (5 existants + 2 nouveaux AC1) — 7/7 GREEN
- `SyncControllerPushGateTest` : 11 tests (6 existants + 5 nouveaux AC2) — 11/11 GREEN
- Full regression : 1476 tests, 0 NEW failures, 0 NEW errors
  - 1 pre-existing flaky: `OnboardingServiceTest.categories_should_have_valid_fields` (Mockito timestamp mismatch)
  - 40 pre-existing DB-dependent errors (AuthControllerTest, JpaBaseEntityPersistTest, SectorTemplateFactoryTest, FlywayBaselineIntegrationTest, ProductJpaEntityTest — require PostgreSQL)

**Non touché :**
- Aucun changement DDL (colonnes `last_push_at`/`last_pull_at` existent depuis 5.4)
- Aucun changement Flutter (le client gère déjà 423 + 403)
- `AdminSyncMonitoringService` non impacté (Option A préserve `last_push_at` réel)

**Décisions prises :**
- D1 → Option A (GREATEST) retenue. À confirmer en code review.
- D2 → 403 FORBIDDEN retenu. À confirmer.
- D3 → Ownership check aussi sur pull (X-Device-Id). À confirmer.

## File List

| Fichier | Action |
|---------|--------|
| `keevo/backend/src/main/java/com/keevo/sync/sync/application/service/SyncGateCheckService.java` | MODIFIED — isStalePush() → GREATEST(lastPushAt, lastPullAt) |
| `keevo/backend/src/main/java/com/keevo/sync/sync/adapter/in/rest/SyncController.java` | MODIFIED — + deviceId ownership check (push + pull) |
| `keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java` | MODIFIED — + DEVICE_ID_MISMATCH |
| `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java` | MODIFIED — + FR_MESSAGES + domainCodeToHttpStatus for DEVICE_ID_MISMATCH |
| `keevo/backend/src/test/java/com/keevo/sync/sync/application/service/SyncGateCheckServiceTest.java` | MODIFIED — +2 tests AC1 (shouldUnblockPushAfterSuccessfulPull, shouldStillBlockWhenBothPushAndPullAreStale) + helpers |
| `keevo/backend/src/test/java/com/keevo/sync/sync/adapter/in/rest/SyncControllerPushGateTest.java` | MODIFIED — +5 tests AC2 (shouldRejectDeviceIdNotOwnedByActor, shouldAllowDeviceIdWhenNotYetRegistered, shouldAllowDeviceIdWhenOwnedByActor, shouldAllowDeviceIdWhenUserIdNull, + legacy userId null tolerance) |
| `_bmad-output/implementation-artifacts/sprint-status.yaml` | MODIFIED — status ready-for-dev → in-progress → review |

## Change Log

- 2026-07-21: Story implemented. AC1 (GREATEST gate) + AC2 (deviceId ownership) + full regression 0 NEW failures. Status → review.
- **Rotation automatique de deviceId** : si un device est définitivement compromise, l'utilisateur devrait pouvoir le désactiver depuis les paramètres. Différé (pas dans l'audit).
- **Rate-limiting sur DEVICE_ID_MISMATCH** : un attaquant qui tente de deviner des deviceIds pourrait être rate-limité. Différé — pas dans l'audit.

### Contrepartie mobile (Flutter) — AUCUNE

- Le client Flutter est déjà conforme : deviceId stable, gestion 423, gestion 403.
- Aucune modification Flutter requise pour cette story.

### References

- [Source: _bmad-output/planning-artifacts/epics/epics-remediation-audit.md#Story 13.2]
- [Source: SyncGateCheckService.java:40-45 — current state: only checks last_push_at]
- [Source: SyncController.java:72-95 — push endpoint, no deviceId ownership check]
- [Source: SyncController.java:123-136 — pull endpoint, no deviceId ownership check]
- [Source: UserSyncState.java — record already has lastPullAt field]
- [Source: UserSyncStateRepositoryAdapter.java:37-42 — upsertOnPull already stamps last_pull_at]
- [Source: Story 5.4 (original gate implementation)]
- [Source: AUDIT_CONFORMITE_BMAD.md — B-CRIT-3]

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List

### Change Log
