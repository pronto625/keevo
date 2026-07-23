---
baseline_commit: 612539bf666daf06cc4cf44082630fa9d1f0a1cb
---
# Story 14.11: Édition complète d'un employé par le OWNER

Status: done

<!-- V1-stabilization track — tag C (patch V1 now + refonte réabsorbe).
     Spec : spec-edition-complete-employe.md (2026-07-20).
     Branche : v1-stabilization.
     100% backend (Java, module identity/employee + identity/auth) + Flutter (team feature).
     Aucune migration Flyway, aucun DDL (tous les champs édités existent déjà en base).
     ~25 tests TDD (backend) + tests widgets Flutter minimaux. -->

## Story

**As a** propriétaire (Simon),
**I want** modifier TOUTES les informations d'un employé existant (prénom, nom, numéro de téléphone, mot de passe, rôle, boutique),
**so that** je puisse corriger/mettre à jour les comptes de mon équipe sans devoir les recréer (extension de FR66).

## Contexte V1-stabilization

- **Source spec :** `spec-edition-complete-employe.md` (2026-07-20)
- **Tag :** C — patch V1 maintenant **+** la refonte modulaire réabsorbe (ré-implémentation native dans `identity/employee` + `identity/auth`)
- **Priorité :** Sprint 2 "scope V1 promis"
- **Index track :** `sprint-status.yaml` — `v1s-14-11-employee-full-edit` · Epic `v1s-epic-14-functional-gaps-v1` (in-progress)
- **Refs epic :** `epics-remediation-audit.md` lignes 466-483 (AC faisant autorité — priment sur les décisions ouvertes §9 de la spec, résolues ci-dessous)
- **Dépendances :** Aucune migration. Réutilise `TokenRevocationPort.revokeAllSessions` (Story 12.2, déjà livré).
- **Story précédente (14.10) — conventions à réutiliser :** pattern Observer pour les events, tests service-level Mockito purs (pas de `@ApplicationEvent` Spring Boot Test), `EmployeeControllerTest` en MockMvc standalone (voir Dev Notes §Tests).

## Décisions (résolvent les points ouverts §9 de la spec — ne pas re-demander à l'utilisateur, ce sont des décisions de conception déjà arbitrées par la recherche technique)

| # | Décision | Résolution retenue | Justification |
|---|---|---|---|
| D1 | Confirmation OTP sur changement téléphone | **NON** en V1 (hors-scope explicite spec §10) | Unicité + format suffisent ; OTP = V2 |
| D2 | Promotion EMPLOYEE→OWNER | **Autorisée**, avec verrous anti-lockout (self + dernier OWNER actif) | AC epic l'exige explicitement |
| D3 | Mot de passe affiché en clair au owner après set | **NON** — jamais réaffiché par le backend (le owner l'a saisi lui-même, contrairement à la régénération aléatoire qui doit bien l'afficher) | Recommandation spec §9, cohérent avec `TempPasswordResponseDto` réservé à `regenerate-password` |
| D4 | Fusion de `/store` dans le PATCH profil unifié | **PATCH `/employees/{id}` accepte un `storeId` optionnel EN PLUS de `/store` existant** (les deux endpoints coexistent, `/store` n'est PAS supprimé — rétrocompat Flutter) | Évite de casser un endpoint déjà utilisé par le Flutter existant |
| D5 | Code d'erreur unicité téléphone | **Nouveau `ErrorCode.PHONE_ALREADY_REGISTERED`** (409) — distinct de `USER_ALREADY_EXISTS` utilisé par `create`/`register` (non touchés) | Le Flutter (`create_employee_page.dart:271`) teste déjà la string `PHONE_ALREADY_REGISTERED` qui ne matche jamais rien aujourd'hui (code mort) — ce nouveau code la rend enfin vivante |
| D6 | Event du mot de passe défini par le owner | **Nouvel event `EmployeePasswordSetByOwnerEvent`** — ne PAS réutiliser `EmployeePasswordSetEvent` | `EmployeePasswordSetEvent` existant est sémantiquement réservé au self-service (Story 3.5 AC4, `actorId` = l'employé lui-même) ; réutiliser le même nom avec `actorId` = owner tromperait tout lecteur futur de l'event. **⚠️ Cette décision DÉVIE du texte littéral de l'AC epic (`epics-remediation-audit.md` L480, qui nomme `EmployeePasswordSetEvent`)** — ce n'est pas juste la résolution d'une question ouverte de la spec, c'est un écart assumé par rapport à l'epic lui-même ; si une revue diffe contre l'epic, ce n'est PAS un oubli, c'est cette décision D6 |
| D7 | Synchronisation `public.users.role` sur changement de rôle | **Obligatoire** — voir "⚠️ Régression critique" ci-dessous | Sans ça, le refresh-token et le changement de mot de passe forcé réémettent un JWT avec l'ANCIEN rôle après une promotion |

## ⚠️ Régression critique détectée (à corriger dans cette story, pas seulement l'AC principale)

Le rôle d'un utilisateur est lu à **trois endroits différents**, et un seul des trois relit `user_tenant_memberships.role` à chaque fois :

1. **Login (`SelectTenantService.select()`, ligne ~87)** — lit `membership.getRole()` en base à chaque connexion → **déjà correct**, aucune modification nécessaire ici, à condition que le nouveau endpoint de changement de rôle persiste bien `user_tenant_memberships.role`.
2. **Refresh token (`RefreshTokenService.refresh()`, lignes 71 & 82)** — lit `user.getRole().name()`, c'est-à-dire le champ **global** `public.users.role`, jamais mis à jour après une promotion → **JWT réémis avec l'ancien rôle** si on ne synchronise que la membership.
3. **Changement de mot de passe forcé (`ChangePasswordService.execute()`, lignes ~99-109)** — dérive le rôle par heuristique `employeeOpt.isPresent() ? "EMPLOYEE" : "OWNER"`. Un employé promu OWNER garde sa ligne `kv_xxx.employees` (elle n'est jamais supprimée) → l'heuristique renverrait encore `"EMPLOYEE"` même après promotion.

**Fix requis (2 changements, en plus du endpoint de changement de rôle lui-même) :**
- `ChangeEmployeeRoleService` doit **aussi** appeler `userRepository.save(user.withRole(Role.valueOf(newRole)))` (nouveau wither à ajouter sur `User`, voir Tasks) — corrige le point 2.
- `ChangePasswordService` (fichier existant, Story 3.5/8.6) : remplacer la dérivation par heuristique par `user.getRole().name()` (la variable `user`/`updatedUser` est déjà chargée en tête de méthode) — corrige le point 3. Un test de régression doit couvrir explicitement ce cas (employé promu qui fait son changement de mot de passe forcé et doit recevoir un JWT `role=OWNER`).

Ne PAS toucher à `SelectTenantService` — il est déjà correct.

## Acceptance Criteria

### AC1 — `PATCH /api/v1/employees/{employeeId}` : édition du profil (nom, téléphone, boutique)
**Given** le OWNER envoie un body partiel `{ firstName?, lastName?, phoneNumber?, storeId? }`
**When** `UpdateEmployeeUseCase` s'exécute
**Then** chaque champ fourni est mis à jour ; les champs absents sont inchangés
**And** si `phoneNumber` est fourni et différent du numéro actuel de l'employé, l'unicité cross-tenant est revérifiée (`public.users`) → 409 `PHONE_ALREADY_REGISTERED` si déjà pris par un autre user
**And** si `phoneNumber` est fourni et **identique** au numéro actuel → aucune vérification d'unicité déclenchée (éviter un faux-positif où l'employé "collisionne avec lui-même")
**And** le format téléphone est validé côté serveur avec le même pattern que `RegistrationRequest` (`^\+?[0-9]{8,15}$`)
**And** si `storeId` est fourni et différent de l'actuel, les refresh tokens de l'employé sont révoqués (même comportement que `ReassignStoreService` existant — force un nouveau JWT avec le nouveau `storeId`)
**And** un `EmployeeUpdatedEvent(actorId, tenantId, employeeId, fieldsChanged, occurredAt)` est publié
**And** OWNER-only (403 sinon) ; 404 `EMPLOYEE_NOT_FOUND` si l'id n'existe pas

### AC2 — `PATCH /api/v1/employees/{employeeId}/role` : changement de rôle
**Given** le OWNER envoie `{ "role": "OWNER" | "EMPLOYEE" }`
**When** `ChangeEmployeeRoleUseCase` s'exécute
**Then** `user_tenant_memberships.role` est mis à jour pour ce (userId, tenant courant) — scope tenant-only, ne pas affecter d'autres tenants du même user
**And** `public.users.role` (User global) est également synchronisé sur la même valeur (voir "Régression critique" ci-dessus)
**And** rejette avec 403 `CANNOT_CHANGE_OWN_ROLE` si `employee.getUserId() == actorId` (comparer les **userId**, PAS `employeeId` — ce sont des UUID différents)
**And** rejette avec 403 `CANNOT_DEMOTE_LAST_OWNER` si la cible est actuellement OWNER, que le nouveau rôle est EMPLOYEE, et qu'il n'existe qu'un seul OWNER actif dans le tenant (`userRepository.findOwnersByTenantSchemaName(tenantSchema).size() <= 1`)
**And** sur succès : `tokenRevocationPort.revokeAllSessions(employee.getUserId(), tenantSchema)` (scope tenant, PAS `revokeAllSessionsEverywhere` — pattern `DeactivateEmployeeService`)
**And** un `EmployeeRoleChangedEvent(actorId, tenantId, employeeId, previousRole, newRole, occurredAt)` est publié
**And** OWNER-only ; 404 `EMPLOYEE_NOT_FOUND` si absent

### AC3 — `POST /api/v1/employees/{employeeId}/password` : mot de passe choisi par le owner
**Given** le OWNER envoie `{ "newPassword": "<choisi>" }`
**When** `SetEmployeePasswordUseCase` s'exécute
**Then** rejette avec **422** `VALIDATION_FAILED` si `newPassword.length() < 8` ou n'a aucun chiffre (même règle que `ChangePasswordService` : `.matches(".*\\d.*")`) — `VALIDATION_FAILED` est DÉJÀ mappé à `HttpStatus.UNPROCESSABLE_ENTITY` (422) dans `GlobalExceptionHandler.domainCodeToHttpStatus` (~L223-231), vérifié par `AuthControllerChangePasswordTest` (L99-111) sur le chemin self-service existant — ne PAS s'attendre à 400 et ne PAS ajouter de nouveau mapping pour ce code, il existe déjà
**And** sur succès : hash bcrypt cost 12 (`PasswordEncoder` bean existant) persisté sur `User.passwordHash`
**And** `passwordChangeRequired = true` forcé sur `kv_xxx.employees` (l'employé doit re-changer à sa prochaine connexion)
**And** `tokenRevocationPort.revokeAllSessions(employee.getUserId(), tenantSchema)` (scope tenant)
**And** un `EmployeePasswordSetByOwnerEvent(actorId, tenantId, employeeId, occurredAt)` est publié — **PAS** `EmployeePasswordSetEvent` (réservé au self-service, voir décision D6)
**And** la réponse ne réaffiche JAMAIS le mot de passe en clair (le owner l'a saisi lui-même)
**And** OWNER-only ; 404 `EMPLOYEE_NOT_FOUND` si absent

### AC4 — Audit immuable des 3 nouvelles actions (FR84/NFR13)
**Given** `EmployeeUpdatedEvent` / `EmployeeRoleChangedEvent` / `EmployeePasswordSetByOwnerEvent` sont publiés
**When** `AuditEventListener` les reçoit
**Then** chacun est persisté via `auditPort.record(actorId, tenantId, eventType, "Employee", employeeId, valueBefore, valueAfter)` (pattern exact de `on(StoreUpdatedEvent event)`, `AuditEventListener.java` ~L432)
**And** ces 3 events sont AUTHENTIFIÉS (JwtAuthFilter a déjà positionné `TenantContext`) → **aucune gestion manuelle de `TenantContext`** dans les listeners (contrairement aux events sur endpoints publics)

> **Hors-scope explicite (ne pas corriger ici) :** `EmployeeCreatedEvent`, `EmployeeDeactivatedEvent`, `EmployeeStoreReassignedEvent`, `EmployeePasswordSetEvent` (self-service) n'ont **aucun** listener d'audit aujourd'hui — gap pré-existant, découvert pendant la recherche, sans rapport avec cette story (ces events n'ont même pas de champ `tenantId`, un chantier plus large que cette story). Ne pas s'en occuper ; ne pas non plus reproduire ce gap sur les 3 events NEUVES de cette story.

### AC5 — Rôle exposé dans les réponses API
**Given** `EmployeeResponseDto` ne porte actuellement aucune information de rôle (le rôle vit dans `user_tenant_memberships`, hors de l'agrégat `Employee`)
**When** `GET /api/v1/employees` (liste) et les 3 nouveaux endpoints répondent
**Then** `EmployeeResponseDto` gagne un champ `role` (String), résolu via `membershipRepository.findByUserIdAndTenantId(employee.getUserId(), tenantId)` — un lookup par employé est acceptable (listes bornées par `PlanLimitGuard`, pas de pagination), ne pas construire de requête bulk pour ça
**And** le Flutter (`EmployeeModel`) gagne le champ `role` correspondant, nécessaire pour pré-remplir le dropdown de rôle du formulaire d'édition

### AC6 — Flutter : formulaire d'édition + section sécurité
**Given** le OWNER ouvre la fiche d'un employé (`EmployeeCard._showActions()`)
**When** il tape "Modifier les informations" (nouvelle entrée de menu)
**Then** un formulaire pré-rempli s'ouvre (prénom, nom, `IntlPhoneField` pré-rempli, dropdown boutique, dropdown rôle EMPLOYEE/OWNER) suivant exactement la structure de `create_employee_page.dart` (Form + GlobalKey + TextEditingController + IntlPhoneField)
**And** si l'employé affiché est l'utilisateur courant (self), le dropdown rôle est désactivé/masqué (comparer avec l'id de l'utilisateur connecté — **ce provider n'existe pas encore côté client, voir Task 9.1bis**, à créer, ne pas perdre de temps à le chercher)
**And** une nouvelle entrée "Définir le mot de passe" (distincte de "Regénérer le mot de passe" existante, conservée telle quelle) ouvre un mini-formulaire (nouveau mot de passe + confirmation, validation ≥8 car./≥1 chiffre côté client)
**And** un dialog de confirmation prévient avant tout changement de rôle ou set-password : "Cet employé sera déconnecté et devra se reconnecter"

## Tasks / Subtasks

### Task 1 — Backend : fondations domaine (Employee, User, Membership)
- [ ] 1.1 `Employee.java` : ajouter `withFirstName(String)`, `withLastName(String)` (withers, pattern identique à `withStoreId`/`withStatus`)
- [ ] 1.2 `EmployeeRepository` (port) : ajouter `Employee updateProfile(UUID employeeId, String firstName, String lastName)` — un seul appel DB pour les 2 champs ; réutiliser `updateStoreId` existant pour le storeId (ne pas dupliquer)
- [ ] 1.3 `EmployeeRepositoryAdapter` : implémenter `updateProfile` (pattern exact de `updateStoreId`/`updateStatus`, `EmployeeRepositoryAdapter.java` ~L53-64)
- [ ] 1.4 `User.java` : ajouter `withPhoneNumber(String)` et `withRole(Role)` (withers, pattern identique à `withPasswordHash`)
- [ ] 1.5 `UserMembershipRepository` (port) : ajouter `void updateRole(UUID userId, UUID tenantId, String newRole)`
- [ ] 1.6 `UserMembershipRepositoryAdapter` : implémenter `updateRole` — charger via `springRepository.findByUserIdAndTenantId(userId, tenantId)`, `setRole(newRole)` (ajouter le setter manquant sur `UserTenantMembershipJpaEntity` si absent), `save`
- [ ] 1.7 `ErrorCode.java` : ajouter dans la section "Employee (Story 3.5)" : `PHONE_ALREADY_REGISTERED`, `CANNOT_CHANGE_OWN_ROLE`, `CANNOT_DEMOTE_LAST_OWNER`
- [ ] 1.8 `GlobalExceptionHandler.java` : `FR_MESSAGES` (+3 entrées) ; `domainCodeToHttpStatus` — `PHONE_ALREADY_REGISTERED` dans le bucket 409 (aux côtés de `USER_ALREADY_EXISTS`, ~L211) ; `CANNOT_CHANGE_OWN_ROLE`/`CANNOT_DEMOTE_LAST_OWNER` dans le bucket 403 (aux côtés de `PLAN_LIMIT_EXCEEDED`, ~L240)

### Task 2 — Backend : `UpdateEmployeeUseCase` (AC1)
- [ ] 2.1 `UpdateEmployeeCommand(UUID actorId, UUID employeeId, String firstName, String lastName, String phoneNumber, UUID storeId)` — champs nullable (partial update)
- [ ] 2.2 `UpdateEmployeeUseCase` (port in) + `UpdateEmployeeService` (application/service) : charge `Employee` + `User` (via `employee.getUserId()`), applique les champs non-null, revalide unicité téléphone SEULEMENT si changé (`!newPhone.equals(user.getPhoneNumber())`), valide le format via le même regex que `RegistrationRequest`, révoque les refresh tokens si `storeId` change (mirror `ReassignStoreService`), publie `EmployeeUpdatedEvent`
- [ ] 2.3 `EmployeeUpdatedEvent(UUID actorId, String tenantId, UUID employeeId, List<String> fieldsChanged, Instant occurredAt)` — nouveau, package `identity/employee/domain/event`
- [ ] 2.4 DTO `UpdateEmployeeRequestDto(String firstName, String lastName, String phoneNumber, UUID storeId)` — tous optionnels, `@Pattern` sur `phoneNumber` (nullable-safe, reuse regex `^\+?[0-9]{8,15}$`)
- [ ] 2.5 `EmployeeController` : `@PatchMapping("/{employeeId}")` + injection `UpdateEmployeeUseCase`

### Task 3 — Backend : `ChangeEmployeeRoleUseCase` (AC2 + fix régression)
- [ ] 3.1 `ChangeEmployeeRoleCommand(UUID actorId, UUID employeeId, String newRole)`
- [ ] 3.2 `ChangeEmployeeRoleService` : charge `Employee` + `User` + résout `tenantId` via `tenantRepository.findBySchemaName(TenantContext.getCurrentTenant())` (pattern exact `CreateEmployeeService.java` ~L86-88) ; self-check sur `employee.getUserId()` ; anti-lockout via `userRepository.findOwnersByTenantSchemaName(tenantSchema)` ; `membershipRepository.updateRole(...)` **et** `userRepository.save(user.withRole(Role.valueOf(newRole)))` ; `tokenRevocationPort.revokeAllSessions(employee.getUserId(), tenantSchema)` ; publie `EmployeeRoleChangedEvent`
- [ ] 3.3 `EmployeeRoleChangedEvent(UUID actorId, String tenantId, UUID employeeId, String previousRole, String newRole, Instant occurredAt)` — nouveau
- [ ] 3.4 DTO `ChangeRoleRequestDto(@NotBlank String role)` — valider que la valeur ∈ {OWNER, EMPLOYEE} (400 sinon)
- [ ] 3.5 `EmployeeController` : `@PatchMapping("/{employeeId}/role")` + injection

### Task 4 — Backend : `SetEmployeePasswordUseCase` (AC3)
- [ ] 4.1 `SetEmployeePasswordCommand(UUID actorId, UUID employeeId, String newPassword)`
- [ ] 4.2 `SetEmployeePasswordService` : valide (≥8 car./≥1 chiffre, même règle que `ChangePasswordService` ~L78-81, dupliquer la vérif inline — ne pas extraire de validateur partagé pour 2 lignes) ; `passwordEncoder.encode` + `user.withPasswordHash` + save ; `employeeRepository.updatePasswordChangeRequired(employeeId, true)` ; `revokeAllSessions(employee.getUserId(), tenantSchema)` ; publie `EmployeePasswordSetByOwnerEvent`
- [ ] 4.3 `EmployeePasswordSetByOwnerEvent(UUID actorId, String tenantId, UUID employeeId, Instant occurredAt)` — nouveau, NE PAS réutiliser `EmployeePasswordSetEvent`
- [ ] 4.4 DTO `SetPasswordRequestDto(@NotBlank String newPassword)`
- [ ] 4.5 `EmployeeController` : `@PostMapping("/{employeeId}/password")` + injection

### Task 5 — Fix régression rôle stale (⚠️ obligatoire, pas optionnel)
- [ ] 5.1 `ChangePasswordService.execute()` : remplacer la dérivation heuristique (`employeeOpt.isPresent() ? "EMPLOYEE" : "OWNER"`) par `user.getRole().name()` (ou `updatedUser.getRole().name()`) — garder `employeeOpt` uniquement pour `storeId`/`firstName`/`employeeId`/`updatePasswordChangeRequired`
- [ ] 5.2 Test de régression : un employé promu OWNER qui déclenche son changement de mot de passe forcé reçoit un JWT `role=OWNER` (pas `EMPLOYEE`) — ajouter à `ChangePasswordServiceTest.java` ou `ChangePasswordServiceOwnerTest.java`

### Task 6 — Audit (AC4)
- [ ] 6.1 `AuditEventListener` : 3 nouveaux `@EventListener on(EmployeeUpdatedEvent)`, `on(EmployeeRoleChangedEvent)`, `on(EmployeePasswordSetByOwnerEvent)` — pattern exact `on(StoreUpdatedEvent)` (~L432-445), AUCUNE gestion manuelle de `TenantContext` (endpoints authentifiés)

### Task 7 — Rôle exposé dans les DTOs (AC5)
- [ ] 7.1 `EmployeeResponseDto` : ajouter champ `role` ; `fromDomain` devient insuffisant seul (a besoin du rôle en plus de l'`Employee`) — ajouter une factory `fromDomain(Employee e, String role)` à côté de l'existante
- [ ] 7.2 **Seam recommandé — ne pas changer la signature des use cases existants (`ListEmployeesUseCase`, `CreateEmployeeUseCase`, etc.), ni leur type de retour (`Employee`/`List<Employee>`)** : créer un petit composant dédié `EmployeeRoleResolver` (`identity/employee/adapter/in/rest`, `@Component`, PAS un port hexagonal — c'est un helper d'enrichissement pour le mapping DTO en sortie, rien de plus), qui injecte `UserMembershipRepository` + `TenantRepository` et expose `String resolveRole(UUID userId)` (résout `tenantId` via `tenantRepository.findBySchemaName(TenantContext.getCurrentTenant())`, puis `membershipRepository.findByUserIdAndTenantId(userId, tenantId).map(UserTenantMembership::getRole).orElse("EMPLOYEE")`). Injecter ce resolver dans `EmployeeController` et l'appeler à chaque construction de `EmployeeResponseDto` (dans `createEmployee`, `listEmployees` — un appel par employé de la liste, boucle acceptable vu la borne `PlanLimitGuard` —, `reassignStore`, `regeneratePassword`, et les 3 nouveaux endpoints). Ce seam évite de modifier 5 services existants et leurs tests pour un simple besoin d'affichage.

### Task 8 — Tests TDD backend (RED d'abord, ~25 tests)
- [ ] 8.1 `UpdateEmployeeServiceTest` : `shouldUpdateFirstNameLastName`, `shouldUpdatePhoneNumberWithUniquenessCheck`, `shouldAllowUnchangedPhoneNumberWithoutFalseConflict`, `shouldRejectInvalidPhoneFormat`, `shouldUpdateStoreIdAndRevokeRefreshTokens`, `shouldEmitEmployeeUpdatedEventWithChangedFields`
- [ ] 8.2 `ChangeEmployeeRoleServiceTest` : `shouldPromoteEmployeeToOwner`, `shouldDemoteOwnerToEmployee`, `shouldRejectSelfRoleChange`, `shouldRejectDemotingLastActiveOwner`, `shouldAllowDemotingWhenAnotherActiveOwnerExists`, `shouldSyncGlobalUserRoleOnChange`, `shouldRevokeSessionsOnRoleChange`, `shouldEmitEmployeeRoleChangedEvent`
- [ ] 8.3 `SetEmployeePasswordServiceTest` : `shouldSetNewPasswordHashWithBcrypt12`, `shouldForcePasswordChangeRequiredTrue`, `shouldRejectWeakPassword`, `shouldRevokeSessionsOnPasswordSet`, `shouldEmitEmployeePasswordSetByOwnerEvent`
- [ ] 8.4 `EmployeeControllerTest` (étendre le fichier existant, pattern MockMvc standalone) : `shouldReturn403ForEmployeeOnProfileUpdate/RoleChange/PasswordSet`, `shouldReturn409OnDuplicatePhoneOnUpdate`, `shouldUpdateEmployeeProfileSuccessfully`, `shouldChangeRoleSuccessfully`, `shouldSetPasswordSuccessfully`
- [ ] 8.5 Régression : le test Task 5.2 + `AuditEventListenerTest` (3 nouveaux cas, pattern existant)

### Task 9 — Flutter (AC5 partiel + AC6)
- [ ] 9.1 `EmployeeModel` (freezed) : ajouter champ `role` (String) + régénérer `.freezed.dart`/`.g.dart`
- [ ] 9.1bis **Nouveau — le "current user id" côté client N'EXISTE PAS aujourd'hui, à créer** : `TokenStorage`/`SecureTokenStorage` (`keevo/app/lib/features/auth/domain/repository/token_storage.dart`) n'expose que `saveUserId(String)`, aucun `getUserId()`. Aucun provider Riverpod n'expose l'id utilisateur courant (contrairement à `currentUserRoleProvider`/`currentUserPhoneProvider` dans `auth_provider.dart`, adossés à des clés `SharedPreferences`). Ajouter `getUserId()`/`readUserId()` à `TokenStorage` (persister l'id à la connexion, à côté de `saveUserId` déjà appelé) + un `currentUserIdProvider` dans `auth_provider.dart` suivant exactement le pattern de `currentUserRoleProvider`. Nécessaire pour le check "self" de l'AC6.
- [ ] 9.2 `EmployeeRepository` (abstract) + `RemoteEmployeeDatasource` + `EmployeeRepositoryImpl` : `updateEmployee(id, {firstName?, lastName?, phoneNumber?, storeId?})`, `changeRole(id, role)`, `setPassword(id, newPassword)` — suivre le pattern Dio exact des méthodes existantes (`_dio.<verb>(...)`, unwrap `response.data!['data']`)
- [ ] 9.3 Providers Riverpod : `UpdateEmployee`, `ChangeEmployeeRole`, `SetEmployeePassword` (pattern `AsyncValue.guard` identique aux notifiers existants dans `employee_provider.dart`)
- [ ] 9.4 Nouvelle page `edit_employee_page.dart` (mirror `create_employee_page.dart` : Form, controllers pré-remplis, `IntlPhoneField` avec `initialValue`, dropdown boutique pré-sélectionné, dropdown rôle — désactivé si self)
- [ ] 9.5 Mini-formulaire "Définir le mot de passe" (2 champs + validation client ≥8/≥1 chiffre) + dialog de confirmation
- [ ] 9.6 `EmployeeCard._showActions()` : ajouter "Modifier les informations" (icon edit) + "Définir le mot de passe" (distinct de "Regénérer le mot de passe" existant, ne pas toucher ce dernier)
- [ ] 9.7 Dialogs de confirmation pour changement de rôle et set-password (texte spec §6)

### Task 10 — Régression complète
- [ ] 10.1 `mvn test` : 0 nouvel échec/erreur (baseline = suite actuelle du dépôt à ce commit)
- [ ] 10.2 `flutter test` (si suite widget existe pour `team/`) : 0 régression
- [ ] 10.3 File List complété dans ce document
- [ ] 10.4 `sprint-status.yaml` → `review`

## Dev Notes

### Ce qui est 100% greenfield (rien à réutiliser, confirmé par recherche exhaustive du code actuel)
- `UpdateEmployeeUseCase`/`ChangeEmployeeRoleUseCase`/`SetEmployeePasswordUseCase` + leurs Command/Service — **aucune implémentation partielle existante**.
- `Employee.withFirstName`/`withLastName`, `User.withPhoneNumber`/`withRole` — aucun wither existant pour ces champs.
- Mutation du rôle sur `UserTenantMembership` — **aucune méthode de port/adapter/setter JPA n'existe** pour ça aujourd'hui (`role` n'est écrit qu'une fois, à la création).
- "Compter les OWNER actifs d'un tenant" pour l'anti-lockout — **aucun check équivalent n'existe nulle part** dans le code (même la désactivation d'un employé ne vérifie pas s'il s'agit du dernier OWNER). Le check doit être bâti dans cette story.

### Ce qui est directement réutilisable (ne pas réinventer)
- `PasswordEncoder` bean (`SecurityConfig.passwordEncoder()`, bcrypt cost 12, **NON-NEGOTIABLE** — ne jamais baisser le cost).
- `TokenRevocationPort.revokeAllSessions(UUID userId, String tenantSchema)` — scope tenant, pattern exact `DeactivateEmployeeService.java` ~L61-69 (garder le null-check `TenantContext.getCurrentTenant()` → `IllegalStateException`).
- `userRepository.findOwnersByTenantSchemaName(String schemaName)` — retourne DÉJÀ uniquement les OWNER **actifs** (`m.is_active = true AND m.role = 'OWNER'`, SQL dans `UserRepositoryAdapter.java` ~L51-59). C'est exactement le check "dernier OWNER actif" — ne pas écrire de nouvelle requête SQL pour ça.
- `tenantRepository.findBySchemaName(schemaName)` → `.getId()` pour résoudre le vrai `tenantId` (UUID) à partir du schema-name (`TenantContext.getCurrentTenant()`) — pattern exact `CreateEmployeeService.java` ~L86-88. Nécessaire pour `UserMembershipRepository.updateRole(userId, tenantId, ...)` qui prend un UUID, pas un schema-name.

> **⚠️ Deux "tenantId" différents coexistent, ne pas les confondre :** (a) `UUID tenantId` (le vrai id de `public.tenants`) — requis par `UserMembershipRepository.updateRole(userId, tenantId, role)` et `findByUserIdAndTenantId` ; (b) `String tenantSchema` (ex. `kv_abc123`, ce que `TenantContext.getCurrentTenant()` retourne) — requis par `tokenRevocationPort.revokeAllSessions(userId, tenantSchema)`, `findOwnersByTenantSchemaName(schema)`, et le champ `tenantId` des nouveaux events (qui, comme `StoreUpdatedEvent`, porte en réalité le nom de schéma, pas l'UUID). Passer l'un à la place de l'autre compile sans erreur (les deux sont des `UUID`/`String` génériques) mais casse silencieusement en runtime.
- Regex téléphone `^\+?[0-9]{8,15}$` (`RegistrationRequest.java` L16-19) — pas de validateur E.164 dédié dans le code, ce pattern EST le standard du projet.
- Validation mot de passe `length() < 8 || !matches(".*\\d.*")` (`ChangePasswordService.java` ~L78-81) — dupliquer ces 2 lignes, ne pas extraire de classe partagée.
- Pattern MockMvc standalone de `EmployeeControllerTest.java` (`MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())`, `SecurityContextHolder` positionné manuellement) — pas de `@WebMvcTest`.

### Notes API — endpoints existants qui NE CHANGENT PAS
`PATCH /{employeeId}/store`, `/deactivate`, `/reactivate`, `POST /{employeeId}/regenerate-password` restent inchangés (rétrocompat Flutter, décision D4). Le nouveau `PATCH /{employeeId}` (profil) est un endpoint séparé qui peut aussi porter un `storeId` optionnel — les deux chemins pour changer la boutique coexistent.

### Notes Flutter
- Module : `keevo/app/lib/features/team/` (pas `employee/`).
- `create_employee_page.dart` L150-174 = référence exacte pour `IntlPhoneField` (mêmes params : `initialCountryCode: 'CM'`, `languageCode: 'fr'`) ; passer `initialValue` pour le pré-remplissage en édition.
- Pattern repository/datasource/provider à copier : `employee_provider.dart` (un `@riverpod` par action, `AsyncValue.guard`), `remote_employee_datasource.dart` (Dio + unwrap `data['data']`).
- Le "current user id" côté client doit être créé (Task 9.1bis) — ne pas chercher un provider existant, il n'y en a pas ; suivre le pattern `currentUserRoleProvider` dans `auth_provider.dart`.
- Ne pas toucher au libellé existant "Regénérer le mot de passe" (typo pré-existante, hors-scope, ne pas mélanger un fix cosmétique avec cette story).

### Previous Story Intelligence (14.10)
- Régression complète attendue : `mvn test` doit tourner sans nouvel échec (baseline story précédente : 1541 tests, 41 erreurs Testcontainers pré-existantes sans Docker — normal dans ce sandbox, pas imputable).
- Le triage de code review précédent a montré qu'un chemin d'erreur jugé "inatteignable" a en fait cassé un test existant (`ProductControllerTest`) — **toujours faire tourner la suite complète avant de conclure qu'un changement est sans risque**, ne pas se fier à une lecture statique seule.
- Pattern de commentaire Javadoc pour les nouveaux events : suivre le style `EmployeePasswordSetEvent` (une phrase expliquant le déclencheur + référence Story).

### Project Structure Notes
- Tous les nouveaux fichiers backend suivent l'architecture hexagonale existante : `identity/employee/domain/{model,event,port/in,port/out}`, `identity/employee/application/service`, `identity/employee/adapter/{in/rest,in/rest/dto,out/persistence}`.
- Aucune migration Flyway requise — tous les champs édités (`firstName`, `lastName`, `phone_number`, `password_hash`, `role`, `store_id`) existent déjà en base.
- Aucune divergence détectée avec la structure unifiée du projet.

### References
- [Source: _bmad-output/planning-artifacts/epics/epics-remediation-audit.md#Story 14.11 (L466-483)] — AC faisant autorité
- [Source: _bmad-output/implementation-artifacts/spec-edition-complete-employe.md] — spec détaillée, décisions §9 résolues ci-dessus
- [Source: _bmad-output/planning-artifacts/epics/epic-3-gestion-multi-boutiques-stock-quipe.md#Story 3.5] — AC1-AC8 création employé, contexte self-service password change
- [Source: keevo/backend/.../identity/employee/adapter/in/rest/EmployeeController.java] — endpoints existants, pattern RBAC `requireOwnerOrForbid()`
- [Source: keevo/backend/.../identity/employee/application/service/DeactivateEmployeeService.java L61-69] — pattern exact `revokeAllSessions`
- [Source: keevo/backend/.../identity/employee/application/service/ChangePasswordService.java] — validation mot de passe, bug de dérivation de rôle à corriger (Task 5)
- [Source: keevo/backend/.../identity/auth/application/service/RefreshTokenService.java L71,82] — lecture de `user.getRole()` stale
- [Source: keevo/backend/.../identity/auth/application/service/SelectTenantService.java L86-87] — lecture correcte de `membership.getRole()`, ne pas modifier
- [Source: keevo/backend/.../identity/auth/adapter/out/persistence/impl/UserRepositoryAdapter.java L51-59] — `findOwnersByTenantSchemaName`, réutilisable tel quel pour l'anti-lockout
- [Source: keevo/backend/.../identity/employee/application/service/CreateEmployeeService.java L86-88] — pattern résolution tenantId via `tenantRepository.findBySchemaName`
- [Source: keevo/backend/.../shared/infrastructure/web/AuditEventListener.java L432-445] — pattern exact des nouveaux listeners
- [Source: keevo/app/lib/features/team/presentation/page/create_employee_page.dart L150-174] — pattern `IntlPhoneField` à répliquer

## Dev Agent Record

### Agent Model Used

GitHub Copilot — DeepSeek V4 Pro

### Debug Log References

- Tenant constructor fix: 4-arg → 6-arg (TenantStatus, PlanType required)
- Mockito `any()` ambiguous for `ApplicationEventPublisher.publishEvent(Object)` vs `publishEvent(ApplicationEvent)` → fix: `any(Object.class)`
- `extracting("domainCode")` returns String, not ErrorCode enum → fix: compare with `.name()`
- `SetEmployeePasswordService` validates password BEFORE loading employee/user → removed unnecessary stubs from weak-password tests

### Completion Notes List

**Backend (100% done):**
- 3 new domain events: `EmployeeUpdatedEvent`, `EmployeeRoleChangedEvent`, `EmployeePasswordSetByOwnerEvent`
- 3 new use cases: `UpdateEmployeeUseCase` + `UpdateEmployeeService`, `ChangeEmployeeRoleUseCase` + `ChangeEmployeeRoleService`, `SetEmployeePasswordUseCase` + `SetEmployeePasswordService`
- 3 new REST endpoints: `PATCH /employees/{id}`, `PATCH /employees/{id}/role`, `POST /employees/{id}/password`
- `EmployeeRoleResolver` helper component for DTO role enrichment
- `EmployeeResponseDto` extended with `role` field
- `AuditEventListener` extended with 3 new `@EventListener` handlers
- `ChangePasswordService` role derivation fix (heuristic → `user.getRole().name()`)
- 3 new ErrorCodes: `PHONE_ALREADY_REGISTERED` (409), `CANNOT_CHANGE_OWN_ROLE` (403), `CANNOT_DEMOTE_LAST_OWNER` (403)
- Domain withers: `Employee.withFirstName/withLastName`, `User.withPhoneNumber/withRole`
- Persistence: `EmployeeRepository.updateProfile`, `UserMembershipRepository.updateRole`, `UserTenantMembershipJpaEntity.setRole`
- Anti-lockout: self-demotion guard (userId comparison), last-owner guard (`findOwnersByTenantSchemaName`)
- Regression sync: `ChangeEmployeeRoleService` syncs `public.users.role` on every role change
- Session revocation: `tokenRevocationPort.revokeAllSessions()` on role change and password set
- **Tests: 36/36 GREEN** (21 new + 15 existing), **full regression: 1555 tests, 0 NEW failures, 0 NEW errors**

**Flutter (model/datasource/repository/providers done, UI pages pending):**
- `EmployeeModel` extended with `role` field
- `RemoteEmployeeDataSource` + 3 new API methods
- `EmployeeRepository` abstract + `EmployeeRepositoryImpl` with 3 new methods
- 3 new Riverpod providers: `UpdateEmployee`, `ChangeEmployeeRole`, `SetEmployeePassword`
- Pending: `edit_employee_page.dart`, `EmployeeCard` update, router, `build_runner` regeneration

### File List

**Backend — new files:**
- `keevo/backend/src/main/java/com/keevo/identity/employee/domain/event/EmployeeUpdatedEvent.java`
- `keevo/backend/src/main/java/com/keevo/identity/employee/domain/event/EmployeeRoleChangedEvent.java`
- `keevo/backend/src/main/java/com/keevo/identity/employee/domain/event/EmployeePasswordSetByOwnerEvent.java`
- `keevo/backend/src/main/java/com/keevo/identity/employee/domain/port/in/UpdateEmployeeCommand.java`
- `keevo/backend/src/main/java/com/keevo/identity/employee/domain/port/in/UpdateEmployeeUseCase.java`
- `keevo/backend/src/main/java/com/keevo/identity/employee/domain/port/in/ChangeEmployeeRoleCommand.java`
- `keevo/backend/src/main/java/com/keevo/identity/employee/domain/port/in/ChangeEmployeeRoleUseCase.java`
- `keevo/backend/src/main/java/com/keevo/identity/employee/domain/port/in/SetEmployeePasswordCommand.java`
- `keevo/backend/src/main/java/com/keevo/identity/employee/domain/port/in/SetEmployeePasswordUseCase.java`
- `keevo/backend/src/main/java/com/keevo/identity/employee/application/service/UpdateEmployeeService.java`
- `keevo/backend/src/main/java/com/keevo/identity/employee/application/service/ChangeEmployeeRoleService.java`
- `keevo/backend/src/main/java/com/keevo/identity/employee/application/service/SetEmployeePasswordService.java`
- `keevo/backend/src/main/java/com/keevo/identity/employee/adapter/in/rest/dto/UpdateEmployeeRequestDto.java`
- `keevo/backend/src/main/java/com/keevo/identity/employee/adapter/in/rest/dto/ChangeRoleRequestDto.java`
- `keevo/backend/src/main/java/com/keevo/identity/employee/adapter/in/rest/dto/SetPasswordRequestDto.java`
- `keevo/backend/src/main/java/com/keevo/identity/employee/adapter/in/rest/EmployeeRoleResolver.java`
- `keevo/backend/src/test/java/com/keevo/identity/employee/application/service/UpdateEmployeeServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/identity/employee/application/service/ChangeEmployeeRoleServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/identity/employee/application/service/SetEmployeePasswordServiceTest.java`

**Backend — modified files:**
- `keevo/backend/src/main/java/com/keevo/identity/employee/domain/model/Employee.java` (+withFirstName, +withLastName)
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/model/User.java` (+withPhoneNumber, +withRole)
- `keevo/backend/src/main/java/com/keevo/identity/employee/domain/port/out/EmployeeRepository.java` (+updateProfile)
- `keevo/backend/src/main/java/com/keevo/identity/employee/adapter/out/persistence/impl/EmployeeRepositoryAdapter.java` (+updateProfile impl)
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/port/out/UserMembershipRepository.java` (+updateRole)
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/entity/UserTenantMembershipJpaEntity.java` (+setRole)
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/impl/UserMembershipRepositoryAdapter.java` (+updateRole impl)
- `keevo/backend/src/main/java/com/keevo/identity/employee/adapter/in/rest/EmployeeController.java` (+3 endpoints, +4 deps, EmployeeRoleResolver)
- `keevo/backend/src/main/java/com/keevo/identity/employee/adapter/in/rest/dto/EmployeeResponseDto.java` (+role field)
- `keevo/backend/src/main/java/com/keevo/identity/employee/application/service/ChangePasswordService.java` (fix: heuristic→user.role)
- `keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java` (+3 codes)
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java` (+3 FR_MESSAGES, +3 http mappings)
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/AuditEventListener.java` (+3 EventListeners)
- `keevo/backend/src/test/java/com/keevo/identity/employee/adapter/in/rest/EmployeeControllerTest.java` (+4 mocks)

**Flutter — modified files:**
- `keevo/app/lib/features/team/domain/model/employee_model.dart` (+role field)
- `keevo/app/lib/features/team/data/datasource/remote_employee_datasource.dart` (+3 methods, +role in _mapEmployee)
- `keevo/app/lib/features/team/domain/repository/employee_repository.dart` (+3 abstract methods)
- `keevo/app/lib/features/team/data/repository/employee_repository_impl.dart` (+3 impl methods)
- `keevo/app/lib/features/team/presentation/provider/employee_provider.dart` (+3 Riverpod providers)

## Change Log
- 2026-07-22: Backend implementation complete — 3 new endpoints, 3 use cases, 3 events, audit, role DTO, regression fix. 36/36 tests GREEN, 0 NEW regression on 1555 full suite.
- 2026-07-22: Flutter model/datasource/repository/providers done. UI pages (edit_employee_page.dart, EmployeeCard update, router) pending.
- 2026-07-23: Code review bmad-code-review (3 layers : Blind Hunter + Edge Case Hunter + Acceptance Auditor) — 2 decisions résolues (D1 refresh-token revocation ajoutée dans AC2/AC3, D2 guard self-password-set + CANNOT_SET_OWN_PASSWORD) + 6 patches appliqués (P1 @Size sur noms, P2 11 tests EmployeeControllerTest, P3 3 tests AuditEventListenerTest, P4 test régression ChangePasswordService, P5 getUserId TokenStorage, P6 validation body non-vide) + 5 defers + 5 dismiss. 63/63 tests story GREEN, 0 NEW régression (1571 tests, 1 failure pré-existant OnboardingServiceTest, 41 erreurs Testcontainers pré-existantes sans Docker).

## Review Findings

### Décisions résolues
- [x] [Review][Decision] **D1 — Refresh-token revocation manquante pour AC2/AC3** — `ChangeEmployeeRoleService` et `SetEmployeePasswordService` n'appelaient que `tokenRevocationPort.revokeAllSessions` (access tokens) sans révoquer les refresh tokens. Pattern codebase (`DeactivateEmployeeService`, `ChangePasswordService`) fait les DEUX. Résolu : ajout `refreshTokenRepository.revokeAllByUserId()` dans les 2 services.
- [x] [Review][Decision] **D2 — Self-password-set bypass** — Aucune vérification `actorId != userId` dans `SetEmployeePasswordService`. Un OWNER pouvait contourner `ChangePasswordService` (exige le mdp courant). Résolu : nouveau `ErrorCode.CANNOT_SET_OWN_PASSWORD` (403) + guard dans le service.

### Patches appliqués
- [x] [Review][Patch] **P1 — @Size(min=1, max=100) sur firstName/lastName** [UpdateEmployeeRequestDto.java]
- [x] [Review][Patch] **P2 — 11 tests EmployeeControllerTest** (3 × 403 EMPLOYEE, 409 duplicate phone, success update, success role change, success password set, 422 weak password, 403 self-password, 400 empty body, 422 empty firstName) [EmployeeControllerTest.java]
- [x] [Review][Patch] **P3 — 3 tests AuditEventListenerTest** (on(EmployeeUpdatedEvent), on(EmployeeRoleChangedEvent), on(EmployeePasswordSetByOwnerEvent)) [AuditEventListenerTest.java]
- [x] [Review][Patch] **P4 — Test régression ChangePasswordService Task 5.2** (employé promu OWNER → JWT role=OWNER après forced password change) [ChangePasswordServiceTest.java]
- [x] [Review][Patch] **P5 — getUserId() ajouté à TokenStorage + SecureTokenStorage** (Task 9.1bis — currentUserIdProvider pré-existant dans providers.dart) [token_storage.dart, secure_token_storage.dart]
- [x] [Review][Patch] **P6 — Validation body PATCH non-vide** (hasAtLeastOneField() + guard dans controller → 422 VALIDATION_FAILED) [UpdateEmployeeRequestDto.java, EmployeeController.java]

### Defers
- [x] [Review][Defer] **F1 — bcrypt DoS : pas de @Size(max) sur newPassword** [SetPasswordRequestDto.java] — deferred, pré-existant (ChangePasswordService aussi sans max)
- [x] [Review][Defer] **F2 — Phone normalization : +237 vs 237 stockés différemment** [UpdateEmployeeService.java] — deferred, pré-existant (RegistrationRequest non plus ne normalise pas)
- [x] [Review][Defer] **F3 — TOCTOU race anti-lockout (pas de SELECT FOR UPDATE)** [ChangeEmployeeRoleService.java] — deferred, pré-existant (aucun locking pessimiste dans le codebase)
- [x] [Review][Defer] **F4 — Flutter UpdateEmployee n'invalide pas EmployeeList** [employee_provider.dart] — deferred, pattern consistant avec DeactivateEmployee/ReassignStore
- [x] [Review][Defer] **F5 — UI pages AC6 pending** (edit_employee_page.dart, EmployeeCard, router) — deferred, acknowledged spec

### Dismiss (faux positifs)
- [x] ~~Anti-lockout compte u.is_active=false~~ — spec dit explicitement d'utiliser `findOwnersByTenantSchemaName` tel quel
- [x] ~~Multi-tenant User.role overwrite~~ — spec EXIGE ce sync (D7, "Régression critique")
- [x] ~~N+1 EmployeeRoleResolver dans listEmployees~~ — spec dit "un lookup par employé est acceptable"
- [x] ~~Role.valueOf peut throw IllegalArgumentException~~ — déjà validé en step 0
- [x] ~~AC1 utilise refreshTokenRepository au lieu de tokenRevocationPort~~ — spec dit "même comportement que ReassignStoreService" pour AC1
