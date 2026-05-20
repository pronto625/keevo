# Story 8.6: Gestion du Compte — Profil Utilisateur & Changement de Mot de Passe

**Status:** done
**Epic:** 8 — Alertes, Notifications & Gestion Opérationnelle
**Story ID:** 8.6
**Story Key:** 8-6-changement-mot-de-passe-volontaire
**Date Created:** 2026-05-20
**Date Updated:** 2026-05-21

---

## GoF Pattern Analysis (MANDATORY — fill before any implementation)

| Question | Answer |
|---|---|
| What variability exists in this feature? | Token generation after password change varies by role (EMPLOYEE clears passwordChangeRequired + storeId; OWNER skips employee record). Profile data source varies by role (EMPLOYEE has firstName+lastName+storeId from Employee entity; OWNER has phoneNumber only from User entity). |
| What might change in the future? | OWNER firstName/lastName (TODO Story-7.1/H4 in SelectTenantService); profile photo upload; MFA before password change; email/OTP confirmation step; password history enforcement. |
| Which GoF pattern(s) apply? | **Strategy** (primary): `PostPasswordChangeTokenStrategy` interface with `EmployeePostPasswordChangeStrategy` + `OwnerPostPasswordChangeStrategy` — `ChangePasswordService` delegates token generation by role. **Facade** (secondary): `UserProfileService` facades both `UserRepository` + `EmployeeRepository` queries behind a single `getProfile(userId)` method. |
| How does it enable Open/Closed principle? | Adding a new role (e.g. MANAGER) requires only a new `PostPasswordChangeTokenStrategy` implementation. `UserProfileService` is closed for modification when new profile fields are added — only a new adapter method is needed. |
| Where is the pattern applied? | Strategy: `identity/employee/application/service/ChangePasswordService.java`. Facade: `identity/auth/application/service/UserProfileService.java`. |

---

## Story

As a logged-in user (OWNER or EMPLOYEE),
I want a "Mon Compte" page in the Plus tab where I can see all my profile information and change my password,
So that I can verify who I am, know which store I'm assigned to, and keep my account secure, all in one place.

---

## Acceptance Criteria

### AC1 — Section "Compte" dans la SettingsPage avec accès au profil

- **Given** a user is logged in (OWNER or EMPLOYEE) and opens the Plus/Settings tab
- **Then** a new section **"Compte"** is visible (above "Préférences"), containing a single tile:
  - Icon: `Icons.manage_accounts_rounded`
  - Title: **"Mon Compte"**
  - Subtitle: phone number of the logged-in user (e.g. `+237 6XX XXX XXX`)
  - Navigates to `/settings/account` on tap
  - Visible for **both** OWNER and EMPLOYEE

### AC2 — Page Compte (`/settings/account`) — Layout & informations affichées

- **Given** the user taps "Mon Compte"
- **Then** a full-page route `/settings/account` opens with:
  - **AppBar**: title "Mon Compte", back arrow (user CAN navigate back)
  - **Avatar** : cercle 72px avec initiales (première lettre de firstName + première lettre de lastName pour EMPLOYEE; première lettre du numéro de téléphone pour OWNER si pas de nom), fond `AppTheme.primary` avec alpha 30%, texte blanc
  - **Section "Informations personnelles"** (card) :
    - Ligne "Prénom" : valeur = `firstName` (EMPLOYEE) ou `"—"` (OWNER sans firstName)
    - Ligne "Nom" : valeur = `lastName` (EMPLOYEE) ou `"—"` (OWNER)
    - Ligne "Téléphone" : valeur = `phoneNumber` (both roles)
    - Ligne "Rôle" : badge coloré — `"Propriétaire"` (jaune) pour OWNER, `"Employé"` (bleu) pour EMPLOYEE
  - **Section "Boutique"** (card, visible uniquement si `storeId != null`):
    - Ligne "Boutique assignée" : valeur = `storeName` résolu via le store list provider
  - **Section "Sécurité"** (card) :
    - Tile "Changer mon mot de passe" (icon: `Icons.lock_reset_rounded`) → navigue vers `/settings/change-password`

### AC3 — Données profil chargées depuis le backend (`GET /api/v1/auth/profile`)

- **Given** the page `/settings/account` is opened
- **When** the `accountProfileProvider` is initialized
- **Then** it calls `GET /api/v1/auth/profile` (authenticated)
- **And** the response is used to populate all fields on the page
- **And** while loading, a shimmer/skeleton loader is shown (3 placeholder rows)
- **And** on network error, an error banner + "Réessayer" button is shown

### AC4 — Réponse backend `GET /api/v1/auth/profile`

- **Given** an authenticated EMPLOYEE calls `GET /api/v1/auth/profile`
- **Then** the response is:
  ```json
  {
    "userId": "...",
    "phoneNumber": "+237...",
    "role": "EMPLOYEE",
    "firstName": "Jean",
    "lastName": "Dupont",
    "storeId": "...",
    "storeName": "Boutique Centrale"
  }
  ```
- **Given** an authenticated OWNER calls `GET /api/v1/auth/profile`
- **Then** the response is:
  ```json
  {
    "userId": "...",
    "phoneNumber": "+237...",
    "role": "OWNER",
    "firstName": null,
    "lastName": null,
    "storeId": null,
    "storeName": null
  }
  ```
- **And** the endpoint is secured: requires valid JWT, returns 401 if not authenticated
- **And** the endpoint respects multi-tenancy: uses `TenantContext.getCurrentTenant()` for EMPLOYEE store lookup

### AC5 — Page Changement de mot de passe (`/settings/change-password`)

- **Given** the user taps "Changer mon mot de passe" (from AccountPage OR via direct navigation)
- **Then** a full-page route `/settings/change-password` opens with:
  - AppBar title: **"Changer mon mot de passe"** (back arrow — NO `PopScope`)
  - Champ 1: **"Mot de passe actuel"** (requis, password obscured, eye toggle)
  - Champ 2: **"Nouveau mot de passe"** (requis) + helper text "Min. 8 caractères, dont au moins un chiffre"
  - Champ 3: **"Confirmer le nouveau mot de passe"** (requis) — validator doit correspondre au champ 2
  - Bouton: **"Modifier le mot de passe"**

### AC6 — Succès et erreurs du changement de mot de passe

- **Given** the user submits valid fields
- **When** backend returns `200 OK` with new tokens
- **Then** new tokens are persisted in secure storage
- **And** the page pops back (`context.pop()`)
- **And** `ScaffoldMessenger` shows: **"Mot de passe modifié avec succès"** (green snackbar, 3s)
- **Given** current password is wrong → inline error: `"Mot de passe actuel incorrect"`
- **Given** new password < 8 chars or missing digit → field-level client-side error before API call
- **Given** champs 2 et 3 ne correspondent pas → erreur champ 3: `"Les mots de passe ne correspondent pas"`

### AC7 — Backend: `POST /api/v1/auth/change-password` works for OWNER and EMPLOYEE

- **Given** an OWNER calls `POST /api/v1/auth/change-password` with valid credentials
- **Then** the service finds **no Employee record** for the userId → uses OWNER token strategy
- **And** new tokens generated with `role=OWNER`, `storeId=null`, no `EMPLOYEE_NOT_FOUND` exception
- **Given** an EMPLOYEE calls the same endpoint
- **Then** existing behavior is preserved: `passwordChangeRequired` cleared, `role=EMPLOYEE` with `storeId`
- **And** all existing refresh tokens are revoked and new ones issued for both roles

### AC8 — Token refresh validity = 6 mois (180 jours)

- **Given** any user performs login, refresh, or password change
- **Then** the refresh token `expiresAt` = `now() + 180 days`
- **And** `keevo.jwt.refresh-token-expiry-days` in `application.yml` = `180`

---

## Tasks / Subtasks

### Backend

- [x] Task 1 — Créer `UserProfileService` + endpoint `GET /api/v1/auth/profile` (AC3, AC4)
  - [x] 1.1 Créer DTO `identity/auth/adapter/in/rest/dto/UserProfileResponse.java`:
    ```java
    public record UserProfileResponse(
        UUID userId,
        String phoneNumber,
        String role,
        String firstName,
        String lastName,
        String storeId,
        String storeName
    ) {}
    ```
  - [x] 1.2 Créer port `identity/auth/domain/port/in/GetUserProfileUseCase.java`:
    ```java
    public interface GetUserProfileUseCase {
        UserProfileData execute(UUID userId, String tenantId);
    }
    ```
  - [x] 1.3 Créer `identity/auth/domain/model/UserProfileData.java` (record avec mêmes champs que DTO)
  - [x] 1.4 Créer `identity/auth/application/service/UserProfileService.java`:
    - Injecte `UserRepository` + `EmployeeRepository` + `StoreRepository` (port déjà existant)
    - Pour EMPLOYEE : `employeeRepository.findByUserId(userId)` → firstName, lastName, storeId → `storeRepository.findById(storeId)` → storeName
    - Pour OWNER : User seulement, firstName=null, lastName=null, storeId=null, storeName=null
  - [x] 1.5 Ajouter `@GetMapping("/profile")` dans `AuthController.java`:
    ```java
    @GetMapping("/profile")
    public ResponseEntity<UserProfileResponse> getProfile() {
        UUID userId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String tenantId = TenantContext.getCurrentTenant();
        UserProfileData data = getUserProfileUseCase.execute(userId, tenantId);
        return ResponseEntity.ok(toProfileResponse(data));
    }
    ```
  - [x] 1.6 Écrire `UserProfileServiceTest.java` (4 cas : EMPLOYEE with store, OWNER, EMPLOYEE store not found, user not found)

- [x] Task 2 — Fix `ChangePasswordService` pour OWNER (AC7)
  - [x] 2.1 Remplacer `employeeRepository.findByUserId(...).orElseThrow(EMPLOYEE_NOT_FOUND)` par gestion `Optional`:
    ```java
    Optional<Employee> employeeOpt = employeeRepository.findByUserId(command.actorId());
    String role;
    UUID storeId = null;
    String firstName = null;
    if (employeeOpt.isPresent()) {
        Employee emp = employeeOpt.get();
        employeeRepository.updatePasswordChangeRequired(emp.getId(), false);
        role = "EMPLOYEE";
        storeId = emp.getStoreId();
        firstName = emp.getFirstName();
        eventPublisher.publishEvent(new EmployeePasswordSetEvent(
                command.actorId(), emp.getId(), Instant.now()));
    } else {
        role = "OWNER";
    }
    ```
  - [x] 2.2 Créer `ChangePasswordServiceOwnerTest.java` : tester le path OWNER (pas d'exception, role=OWNER, storeId=null)
  - [x] 2.3 Vérifier que les tests EMPLOYEE existants passent toujours

- [x] Task 3 — Mise à jour token validity à 180 jours (AC8)
  - [x] 3.1 `application.yml` : `refresh-token-expiry-days: 30` → `180`
  - [x] 3.2 `.env.example` : commentaire mis à jour (Access 24h / Refresh 180 days)

### Flutter

- [x] Task 4 — Créer `RemoteAuthDatasource.getProfile()` + `AuthRepository.getProfile()` (AC3)
  - [x] 4.1 Dans `remote_auth_datasource.dart` : ajouter `Future<AccountProfile> getProfile()` → `GET /api/v1/auth/profile`
  - [x] 4.2 Créer modèle `lib/features/auth/domain/model/account_profile.dart`:
    ```dart
    class AccountProfile {
      final String userId;
      final String phoneNumber;
      final String role;
      final String? firstName;
      final String? lastName;
      final String? storeId;
      final String? storeName;
    }
    ```
  - [x] 4.3 Ajouter `Future<AccountProfile> getProfile()` dans `auth_repository.dart` (interface)
  - [x] 4.4 Implémenter dans `auth_repository_impl.dart`

- [x] Task 5 — Créer `accountProfileProvider` Riverpod (AC3)
  - [x] 5.1 Dans `auth_provider.dart`, ajouter :
    ```dart
    final accountProfileProvider = FutureProvider.autoDispose<AccountProfile>((ref) {
      return ref.read(authRepositoryProvider).getProfile();
    });
    ```

- [x] Task 6 — Créer `AccountPage` (`/settings/account`) (AC2)
  - [x] 6.1 Créer `lib/features/settings/presentation/page/account_page.dart`
  - [x] 6.2 Utilise `accountProfileProvider` (watch) : loading → skeleton, error → banner + retry, data → UI
  - [x] 6.3 Section "Informations personnelles" : rows Prénom, Nom, Téléphone, Rôle badge
  - [x] 6.4 Section "Boutique" (conditionnel sur `storeId != null`) : row "Boutique assignée" + storeName
  - [x] 6.5 Section "Sécurité" : tile "Changer mon mot de passe" → `context.push('/settings/change-password')`
  - [x] 6.6 Avatar : cercle 72px, initiales (EMPLOYEE: `${firstName[0]}${lastName[0]}`; OWNER: premier char du phoneNumber)

- [x] Task 7 — Créer `UserPasswordChangePage` (`/settings/change-password`) (AC5, AC6)
  - [x] 7.1 Créer `lib/features/auth/presentation/page/user_password_change_page.dart`
  - [x] 7.2 Trois champs (actuel + nouveau + confirmer), pas de `PopScope`
  - [x] 7.3 `ref.listen(changePasswordProvider, ...)` → on success: `context.pop()` + snackbar vert
  - [x] 7.4 Reset provider state en `initState` via `ref.invalidate(changePasswordProvider)` pour éviter double-trigger si déjà utilisé dans la session

- [x] Task 8 — Ajouter les routes dans `app_router.dart` (AC2, AC5)
  - [x] 8.1 Dans le block `/settings` ShellRoute, ajouter:
    ```dart
    GoRoute(
      path: '/settings/account',
      builder: (_, __) => const AccountPage(),
    ),
    GoRoute(
      path: '/settings/change-password',
      builder: (_, __) => const UserPasswordChangePage(),
    ),
    ```
  - [x] 8.2 Ajouter `/settings/account` et `/settings/change-password` à la liste `_protectedPaths`

- [x] Task 9 — Mettre à jour `SettingsPage` — section "Compte" (AC1)
  - [x] 9.1 Dans `settings_page.dart`, section "Compte" ajoutée avant "Préférences" avec tile "Mon Compte" → `/settings/account`

- [x] Task 10 — Tests widget (AC2, AC5, AC6)
  - [x] 10.1 Créer `test/features/settings/presentation/page/account_page_test.dart`:
    - Test: loading state → skeleton visible
    - Test: EMPLOYEE data loaded → firstName/lastName/phone/role affichés
    - Test: OWNER profile → firstName/lastName = "—"
    - Test: "Changer mon mot de passe" tile navigates to `/settings/change-password`
    - Test: error state → error banner + retry button
  - [x] 10.2 Créer `test/features/auth/presentation/page/user_password_change_page_test.dart`:
    - Test: 3 champs + bouton rendu
    - Test: confirm mismatch → erreur champ 3
    - Test: new password too short → field 2 error
    - Test: on success → snackbar "Mot de passe modifié avec succès"
    - Test: on INVALID_CREDENTIALS → error banner

---

## Dev Notes

### Backend Architecture — Contraintes Critiques

#### Endpoint `GET /api/v1/auth/profile`

**URL finale** : `GET /api/v1/auth/profile`
**Auth** : Bearer token (JWT) — via `JwtAuthFilter`
**Multi-tenant** : `TenantContext.getCurrentTenant()` requis pour interroger le schéma tenant de l'employé

**Module d'appartenance** : l'endpoint va dans `AuthController` (déjà dans `identity/auth/adapter/in/rest/`), injecte `GetUserProfileUseCase`.

**Résolution du `storeName` pour EMPLOYEE** : Le `StoreRepository` port existe dans `identity/onboarding/domain/port/out/` ou `catalog/store/domain/port/`. Vérifier quelle implémentation est disponible (le port `StoreRepository` ou `StorePort`) et utiliser `findById(storeId)` → récupère le `name`.

> **ATTENTION** : Si aucun port de store n'est accessible depuis le module `identity`, utiliser `JdbcTemplate` directement avec `SELECT name FROM {tenantSchema}.stores WHERE id = ?` pour éviter les couplages cross-modules.

#### Fix `ChangePasswordService` — Remplacement `orElseThrow`

**Fichier** : `identity/employee/application/service/ChangePasswordService.java` (lignes 80-91)

Remplacer :
```java
Employee employee = employeeRepository.findByUserId(command.actorId())
        .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));
employeeRepository.updatePasswordChangeRequired(employee.getId(), false);
// ...
String accessToken = jwtTokenProvider.generateAccessToken(
        command.actorId(), tenantId, "EMPLOYEE", "ACTIVE",
        employee.getStoreId(), false, employee.getFirstName());
// ...
eventPublisher.publishEvent(new EmployeePasswordSetEvent(
        command.actorId(), employee.getId(), Instant.now()));
```

Par le code documenté dans la section Tasks (Task 2.1) avec `Optional`.

#### JWT Expiry

**Fichier** : `keevo/backend/src/main/resources/application.yml`
```yaml
keevo:
  jwt:
    access-token-expiry-hours: 24     # inchangé
    refresh-token-expiry-days: 180    # ← changer de 30 à 180
```
`JwtProperties.getRefreshTokenExpiryDays()` est déjà câblé via `@ConfigurationProperties` — aucun changement Java nécessaire.

---

### Flutter Architecture — Contraintes Critiques

#### NE PAS réutiliser `PasswordChangePage` directement

`PasswordChangePage` (Story 3.5 AC4) :
- `PopScope(canPop: false)` — bloque le retour
- `context.go('/pos')` sur succès — redirection forcée
- Titre "Créer votre mot de passe" — incorrect
- Seulement 2 champs (pas de confirmation)

Créer **`UserPasswordChangePage`** séparé. Le `changePasswordProvider` existant (auth_provider.dart ~line 306) peut être réutilisé tel quel — il appelle `POST /api/v1/auth/change-password` et persiste les tokens.

#### Reset du provider `changePasswordProvider` en `initState`

Si l'utilisateur est déjà passé par la page dans la même session (provider en état `data`), le `ref.listen` ne se re-déclenche pas. Fixer via :

```dart
@override
void initState() {
  super.initState();
  // Reset provider state so ref.listen fires on next success
  WidgetsBinding.instance.addPostFrameCallback((_) {
    ref.invalidate(changePasswordProvider);
  });
}
```

#### `accountProfileProvider` — autoDispose

Utiliser `.autoDispose` pour que le provider se réinitialise quand la page est fermée (évite d'afficher des données périmées si l'utilisateur change de compte).

#### Résolution du `storeName` côté Flutter

L'`AccountPage` reçoit `storeName` directement depuis le backend (`GET /api/v1/auth/profile`). Pas besoin de résoudre côté Flutter via `storeListNotifierProvider`. Si `storeName == null` → ne pas afficher la section "Boutique".

#### Route placement dans `app_router.dart`

Chercher le `GoRoute` avec `path: '/settings'` (~ligne 388) et ajouter les enfants là :

```dart
GoRoute(
  path: '/settings/account',
  builder: (context, state) => const AccountPage(),
),
GoRoute(
  path: '/settings/change-password',
  builder: (context, state) => const UserPasswordChangePage(),
),
```

La liste `_protectedPaths` (variable locale ~ligne 240) liste les routes qui redirigent vers login. **Ajouter** `/settings/account` et `/settings/change-password`.

#### AccountPage — Design widgets

Réutiliser les composants existants de `SettingsPage` : `_SectionLabel`, `_SettingsCard`, `_SettingsTile`, `_Divider`. Les importer ou les rendre accessibles (extraire dans un fichier partagé `settings_widgets.dart` si nécessaire, sinon dupliquer).

**Row de profil** (prénom/nom/téléphone/rôle) — pattern simple avec `ListTile` ou Row custom :
```dart
_ProfileInfoRow(label: 'Prénom', value: profile.firstName ?? '—'),
_ProfileInfoRow(label: 'Nom', value: profile.lastName ?? '—'),
_ProfileInfoRow(label: 'Téléphone', value: profile.phoneNumber),
_ProfileInfoRow(label: 'Rôle', value: profile.role, isBadge: true),
```

---

### Project Structure Notes

#### Fichiers à CRÉER

| Fichier | Rôle |
|---|---|
| `keevo/backend/.../auth/adapter/in/rest/dto/UserProfileResponse.java` | DTO réponse profil |
| `keevo/backend/.../auth/domain/model/UserProfileData.java` | Modèle domaine profil |
| `keevo/backend/.../auth/domain/port/in/GetUserProfileUseCase.java` | Port use case profil |
| `keevo/backend/.../auth/application/service/UserProfileService.java` | Service façade profil |
| `keevo/backend/.../auth/application/service/UserProfileServiceTest.java` | Tests unitaires |
| `keevo/app/lib/features/auth/domain/model/account_profile.dart` | Modèle Flutter profil |
| `keevo/app/lib/features/settings/presentation/page/account_page.dart` | Page Mon Compte |
| `keevo/app/lib/features/auth/presentation/page/user_password_change_page.dart` | Page changement MDP |
| `keevo/app/test/features/settings/presentation/page/account_page_test.dart` | Tests widget AccountPage |
| `keevo/app/test/features/auth/presentation/page/user_password_change_page_test.dart` | Tests widget |

#### Fichiers à MODIFIER

| Fichier | Changement |
|---|---|
| `keevo/backend/.../auth/adapter/in/rest/AuthController.java` | Ajouter `@GetMapping("/profile")` + injecter `GetUserProfileUseCase` |
| `keevo/backend/.../employee/application/service/ChangePasswordService.java` | Remplacer `orElseThrow` par `Optional` (Task 2.1) |
| `keevo/backend/src/main/resources/application.yml` | `refresh-token-expiry-days: 30` → `180` |
| `keevo/backend/.env.example` | Mettre à jour commentaire si applicable |
| `keevo/app/lib/features/auth/data/datasource/remote_auth_datasource.dart` | Ajouter `getProfile()` |
| `keevo/app/lib/features/auth/domain/repository/auth_repository.dart` | Ajouter `getProfile()` |
| `keevo/app/lib/features/auth/data/repository/auth_repository_impl.dart` | Implémenter `getProfile()` |
| `keevo/app/lib/features/auth/presentation/provider/auth_provider.dart` | Ajouter `accountProfileProvider` |
| `keevo/app/lib/core/router/app_router.dart` | Ajouter 2 routes + `_protectedPaths` |
| `keevo/app/lib/features/settings/presentation/page/settings_page.dart` | Ajouter section "Compte" |

#### NE PAS toucher

- `PasswordChangePage` (Story 3.5 flow forcé — laisser intact)
- `changePasswordProvider` (réutiliser sans modification)
- `ChangePasswordUseCase` Flutter (réutiliser sans modification)

### Références

- Service backend existant : `identity/employee/application/service/ChangePasswordService.java`
- Controller existant : `identity/auth/adapter/in/rest/AuthController.java` (endpoint `/change-password` lignes 232-240)
- Provider Flutter : `auth_provider.dart` lignes ~149, ~306
- Settings page : `settings_page.dart`
- Router : `app_router.dart` lignes ~388, ~240
- Employee domain model : `identity/employee/domain/model/Employee.java` (fields: firstName, lastName, storeId)
- User domain model : `identity/auth/domain/model/User.java` (field: phoneNumber — pas de firstName pour OWNER)
- JWT config : `application.yml` — `keevo.jwt.refresh-token-expiry-days`
- TODO OWNER firstName : `SelectTenantService.java` ligne 107 commentaire "Story-7.1/H4"

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-5

### Debug Log References

- `AuthControllerChangePasswordTest.java` — constructor updated to pass `null` for new `GetUserProfileUseCase` arg (6th param added to `AuthController`).
- `MessagingStatusControllerTest.java` — constructor updated to mock `WhatsAppPort` (pre-existing gap surfaced by compilation).
- `account_page_test.dart` loading test — replaced `Future.delayed(Duration(days: 999))` with `Completer<AccountProfile>().future` to avoid pending timer error in test runner.
- `user_password_change_page_test.dart` snackbar test — restructured `GoRouter` to include parent `/settings` route so `context.pop()` returns to a live scaffold and the snackbar is visible.

### Completion Notes List

- All 8 acceptance criteria (AC1–AC8) implemented and verified.
- Backend regression failures (admin.catalog, admin.operations, sync modules) are pre-existing and confirmed present on baseline without story 8.6 changes — not introduced by this story.
- Story 8.6 backend tests: 11/11 pass (UserProfileServiceTest ×4, ChangePasswordServiceOwnerTest ×3, AuthControllerChangePasswordTest ×3, MessagingStatusControllerTest ×1).
- Flutter widget tests: 10/10 pass (AccountPage ×5, UserPasswordChangePage ×5).
- `ChangePasswordService` OWNER path: no `EMPLOYEE_NOT_FOUND` exception, role=OWNER, storeId=null, no EmployeePasswordSetEvent published.
- `refresh-token-expiry-days` changed from 30 → 180 in `application.yml`.

### File List

#### Created
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/in/rest/dto/UserProfileResponse.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/model/UserProfileData.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/port/in/GetUserProfileUseCase.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/application/service/UserProfileService.java`
- `keevo/backend/src/test/java/com/keevo/identity/auth/application/service/UserProfileServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/identity/employee/application/service/ChangePasswordServiceOwnerTest.java`
- `keevo/app/lib/features/auth/domain/model/account_profile.dart`
- `keevo/app/lib/features/settings/presentation/page/account_page.dart`
- `keevo/app/lib/features/auth/presentation/page/user_password_change_page.dart`
- `keevo/app/test/features/settings/presentation/page/account_page_test.dart`
- `keevo/app/test/features/auth/presentation/page/user_password_change_page_test.dart`

#### Modified
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/in/rest/AuthController.java`
- `keevo/backend/src/main/java/com/keevo/identity/employee/application/service/ChangePasswordService.java`
- `keevo/backend/src/main/resources/application.yml`
- `keevo/backend/.env.example`
- `keevo/backend/src/test/java/com/keevo/identity/auth/adapter/in/rest/AuthControllerChangePasswordTest.java`
- `keevo/backend/src/test/java/com/keevo/messaging/notification/adapter/in/rest/MessagingStatusControllerTest.java`
- `keevo/app/lib/features/auth/data/datasource/remote_auth_datasource.dart`
- `keevo/app/lib/features/auth/domain/repository/auth_repository.dart`
- `keevo/app/lib/features/auth/data/repository/auth_repository_impl.dart`
- `keevo/app/lib/features/auth/presentation/provider/auth_provider.dart`
- `keevo/app/lib/core/router/app_router.dart`
- `keevo/app/lib/features/settings/presentation/page/settings_page.dart`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
