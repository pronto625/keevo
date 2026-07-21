---
baseline_commit: 9bde5bd
---
# Story 12.7: Hardening Flutter — secure storage, logs Bearer, HTTPS — `SecureStorageProvider` + redaction logs + HTTPS par défaut + session-revoke UX

Status: done

<!-- V1-stabilization track — tag A (patch V1 urgent, release-blocker).
     Branche : v1-stabilization (off `deploy` d766035). Refonte réabsorbe le fix (audit :
     « re-appliquer dans le Flutter refonte, mêmes SecureStorageProvider/AuthInterceptor »).
     Validation optionnelle : lancer `validate-create-story` avant `dev-story`.

     100% Flutter — aucun changement backend requis par cette story (contrairement à 12.2/12.6
     qui portaient une contrepartie mobile ; ici c'est l'inverse : cette story EST la
     contrepartie Flutter des findings sécurité serveur déjà clos par 12.2). -->

## Story

**As a** Toor (opérateur de la plateforme),
**I want** les secrets client (JWT, refresh token) protégés au repos sur toutes les plateformes, les logs de debug ne fuitant jamais le Bearer token, le trafic API chiffré par défaut, et une UX claire quand une session est révoquée/désactivée côté serveur,
**so that** un appareil volé/compromis ou un log de debug partagé ne divulgue pas les identifiants de session, et qu'un employé désactivé ou dont la session est révoquée soit immédiatement et clairement déconnecté (S8, S9, S12, ARCH21, Story 1.3 AC6, FR68 client, ties 12.2).

## Contexte V1-stabilization

- **Source backlog :** `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md:171-185` → Story 12.7 (Refs S8, S9, S12, ARCH21, Story 1.3 AC5 [sic, voir note ci-dessous], FR68 client, ties 12.2).
- **Tracker :** `sprint-status.yaml` clé `v1s-12-7-flutter-hardening-storage-logs-https` (ligne 331).
- **Index track :** `v1-stabilization-stories.md:21` — « Hardening Flutter (SecureStorageProvider + redact Bearer + HTTPS) · S8/S9/S12 ».
- **Tag :** A — patch V1 maintenant. Refonte : ré-appliquer les mêmes `SecureStorageProvider`/`AuthInterceptor` dans la structure Flutter de la refonte (pas de cherry-pick direct si l'arborescence diverge).
- **Findings audit fermés par cette story** (`AUDIT_CONFORMITE_BMAD.md:277-281`) :
  - **S8** (Moyenne) : `FlutterSecureStorage` avec options par défaut (pas d'`EncryptedSharedPreferences` Android) — 6 sites ad-hoc.
  - **S9** (Moyenne) : Bearer JWT logué en clair via `LogInterceptor(requestHeader:true)` dans tous les builds, y compris release.
  - **S12** (Moyenne) : pas de garde d'authentification globale — un deep link direct vers une route protégée contourne la vérification de session (`app_router.dart:336`).
- **⚠️ Note sur la référence « Story 1.3 AC5 » de l'épique :** vérifié — `1-3-jwt-authentication-session-management.md` a une **AC6 « HTTPS / TLS 1.2+ enforcement »** (pas AC5, qui est « Password security bcrypt » et sans rapport). AC6 dit littéralement : « the Flutter `Dio` HTTP client is configured to reject connections without valid TLS certificates (no `badCertificateCallback` bypass in production) ». C'est cette AC6 que cette story ferme réellement côté client (**AC3 ci-dessous**) — l'épique a probablement une coquille de numérotation. Les commentaires de code existants (`auth_provider.dart:54,59`) qui citent « AC5 » pour le secure storage font référence à une version antérieure/différente de la numérotation AC de Story 1.3 — ne pas s'y fier pour retrouver le texte AC réel, se référer à `1-3-....md:60-65` (AC6) directement.
- **FR68** (session/compte révoqué) : le backend (Story 12.2, `done`) émet déjà `401 {"domainCode":"SESSION_REVOKED"}` et `401 {"domainCode":"ACCOUNT_INACTIVE"}` — vérifié dans `ErrorCode.java:62-63` et `GlobalExceptionHandler.java:72-73,190-192`. **Cette story ne touche PAS le backend** — elle ajoute uniquement la branche client qui consomme ces deux codes déjà émis.
- Cette story touche **exclusivement des fichiers Flutter** (`keevo/app/lib/**`) — aucun fichier backend Java. 4 zones de travail indépendantes (AC1-AC4 ci-dessous), procéder dans l'ordre, un commit/vérification à la fois si possible.

## Acceptance Criteria

> Les AC BDD proviennent de `epics-remediation-audit.md` Story 12.7, décomposées en AC numérotées 1-4 pour la traçabilité TDD (le texte original est une seule liste `Given/When/Then` à puces). **AC5 est une extension de scope explicitement justifiée ci-dessous — lire la note avant de l'ignorer.**

1. **AC1 (`SecureStorageProvider` centralisé + hardening Android/iOS, ferme S8)** — **Given** 7 sites Flutter instancient `const FlutterSecureStorage()` directement, sans options plateforme :
   - `main.dart:39` (avant `runApp`, hors arbre Riverpod)
   - `core/router/app_router.dart:200` (`_SplashRedirectPageState._redirect`, dans un `ConsumerState` — a accès à `ref`)
   - `core/router/app_router.dart:360` (redirect de la route `/auth/login`, fonction `(context, state)` sans `ref` direct mais avec `context`)
   - `core/di/providers.dart:66` (`currentUserIdProvider`)
   - `core/di/providers.dart:91` (`syncServiceProvider`, argument `secureStorage:` de `RestSyncService`)
   - `features/settings/presentation/page/settings_page.dart:399` (logout, dans un widget avec `ref`)
   - `features/auth/presentation/provider/auth_provider.dart:56` (`flutterSecureStorageProvider` — le SEUL déjà centralisé en provider Riverpod, mais sans options de hardening ; réutilisé par `providers.dart:111` `syncDiagnosticRunnerProvider`)

   **When** un point d'entrée unique est introduit, **Then** :
   - Un nouveau fichier `lib/core/storage/secure_storage_provider.dart` expose une fonction factory `FlutterSecureStorage buildSecureStorage()` construisant l'instance avec `AndroidOptions(encryptedSharedPreferences: true)` + `IOSOptions(accessibility: KeychainAccessibility.first_unlock_this_device)`.
   - `flutterSecureStorageProvider` (`auth_provider.dart:55-57`) délègue à cette factory : `Provider<FlutterSecureStorage>((ref) => buildSecureStorage())` — **le nom du provider ne change pas**, seuls son emplacement d'implémentation et ses options changent, pour ne casser aucun des call-sites existants qui l'utilisent déjà via `ref.watch(flutterSecureStorageProvider)`.
   - Les 6 autres sites ad-hoc sont remplacés :
     - `main.dart:39` → appel direct `buildSecureStorage()` (pas de `ref` disponible avant `runApp`).
     - `app_router.dart:200` → `ref.read(flutterSecureStorageProvider)` (le `State` a déjà `ref`, `ConsumerState<_SplashRedirectPage>`).
     - `app_router.dart:360` → `ProviderScope.containerOf(context, listen: false).read(flutterSecureStorageProvider)` (pattern déjà utilisé ailleurs dans ce même fichier, `app_router.dart:446`).
     - `providers.dart:66` → `ref.read(flutterSecureStorageProvider)`.
     - `providers.dart:91` → `ref.watch(flutterSecureStorageProvider)`.
     - `settings_page.dart:399` → `ref.read(flutterSecureStorageProvider)`.
   - **And** les fichiers de test (`test/core/sync/rest_sync_service_pull_test.dart`, `test/core/sync/sync_pull_merge_test.dart`, `test/core/sync/diagnostics/sync_diagnostic_test.dart`) qui instancient `const FlutterSecureStorage()` directement en mock **ne sont PAS touchés** — usage de test légitime, hors scope.

2. **AC2 (`LogInterceptor` gated par `kDebugMode` + redaction `Authorization`, ferme S9)** — **Given** `auth_provider.dart:102-109` ajoute inconditionnellement (tout build, y compris release) un `LogInterceptor(requestHeader: true, ...)` qui logue l'en-tête `Authorization: Bearer <jwt>` en clair via `debugPrint`, **When** l'interceptor est durci, **Then** :
   - Le bloc `dio.interceptors.add(LogInterceptor(...))` est enveloppé dans `if (kDebugMode) { ... }` — n'existe plus du tout en release/profile.
   - Même en debug, la valeur du header `Authorization` n'apparaît jamais en clair dans les logs : le `logPrint` callback redacte la ligne avant `debugPrint` (voir Dev Notes pour le pattern exact — `LogInterceptor` ne permet pas de filtrer un header individuellement en amont, la redaction se fait donc au niveau de la ligne de log formatée).

3. **AC3 (HTTPS par défaut + rejet certificats invalides sauf debug/localhost, ferme Story 1.3 AC6)** — **Given** `_apiBaseUrl` (`auth_provider.dart:48-52`) et `_kApiBaseUrl` (`app_router.dart:70-73`, dupliqué et explicitement commenté « Mirrors the API_BASE_URL from auth_provider.dart ») ont tous deux `defaultValue: 'http://localhost:4500'`, et aucun `HttpClientAdapter` n'est configuré (validation TLS par défaut de Dio, mais pas de politique explicite de rejet en production), **When** durci, **Then** :
   - Les deux `defaultValue` deviennent `'https://localhost:4500'` (les deux occurrences, gardées synchronisées comme aujourd'hui — ne pas dé-dupliquer dans cette story, hors scope, voir Dev Notes).
   - Sur `dioProvider` (`auth_provider.dart:80-113`) ET `_refreshDioProvider` (`auth_provider.dart:66-76`), configurer `dio.httpClientAdapter = IOHttpClientAdapter(createHttpClient: () { final client = HttpClient(); client.badCertificateCallback = (cert, host, port) => kDebugMode && host == 'localhost'; return client; });` (API **dio 5.9.2** confirmée — utiliser `createHttpClient`, **pas** `onHttpClientCreate` qui est `@Deprecated` et sera retiré en 6.0.0).

4. **AC4 (`AuthInterceptor.onError` — branche `SESSION_REVOKED`/`ACCOUNT_INACTIVE`, ferme FR68 client, ties 12.2)** — **Given** `AuthInterceptor.onError` (`core/network/auth_interceptor.dart:74-139`) ne traite que `401 TOKEN_EXPIRED` (refresh+retry) et `403 PLAN_LIMIT_EXCEEDED`, alors que le backend (Story 12.2, déjà `done`) émet aussi `401 SESSION_REVOKED` (membership révoqué, `tokens_valid_after`) et `401 ACCOUNT_INACTIVE` (compte désactivé) — un utilisateur dont la session est révoquée reçoit aujourd'hui un 401 qui **tombe dans le bloc `if (statusCode == 401 && !_isRefreshing)`, ne matche ni `TOKEN_EXPIRED` ni `_isRefreshing`, et ressort par `handler.next(err)` sans jamais nettoyer les tokens ni rediriger** — l'app reste dans un état incohérent (tokens locaux valides en apparence, toutes les requêtes suivantes échouent en boucle avec le même 401 non traité, **When** une branche dédiée est ajoutée, **Then** :
   - Un nouveau bloc dans `onError`, testé sur `domainCode == 'SESSION_REVOKED' || domainCode == 'ACCOUNT_INACTIVE'` (même statusCode 401), appelle `await _expireSession()` (méthode privée existante, ligne 144-147 : `storage.clearAll()` + `onSessionExpired()`) **puis** invoque un nouveau callback optionnel `onAccountSuspended` (mirroring le pattern déjà établi par `onPlanLimitExceeded`, ligne 31).
   - `dioProvider` (`auth_provider.dart:92-100`) câble `onAccountSuspended` pour afficher un SnackBar « Votre accès a été suspendu, veuillez vous reconnecter. » via une nouvelle clé globale `rootScaffoldMessengerKey` (voir Dev Notes — pattern à créer, aucun équivalent n'existe aujourd'hui).
   - **And** ce test doit passer AVANT le check `TOKEN_EXPIRED` existant dans `onError` (les deux sont mutuellement exclusifs par valeur de `domainCode`, l'ordre n'affecte pas la logique mais garder la lisibilité).
   - **And** le test existant `'passes non-TOKEN_EXPIRED 401 errors through unchanged'` (`test/core/network/auth_interceptor_test.dart:190-209`, `domainCode: 'INVALID_CREDENTIALS'`) **doit continuer à passer sans modification** — `INVALID_CREDENTIALS` ne matche ni la nouvelle branche ni `TOKEN_EXPIRED`, tombe toujours dans `handler.next(err)` final.

5. **AC5 (garde d'auth globale sur deep link — ferme S12 — scope ajouté, non littéral dans l'épique, décision par défaut documentée) 🚨** — **Given** l'épique référence **S12** dans ses Refs (« Pas de garde auth global Flutter ; les deep links bypassent le check de session », `app_router.dart:336`) mais le texte `Given/When/Then` de la Story 12.7 ne mentionne **explicitement** que storage/logs/HTTPS/session-revoke UX, pas ce finding précis — **vérifié en lisant `app_router.dart:336-347`** : le `redirect` top-level du `GoRouter` ne vérifie **que** `_ownerOnlyPrefixes` + rôle SharedPreferences ; si `role != 'EMPLOYEE'` (y compris `role == null`, càd **aucune session**), la fonction retourne `null` et **laisse passer** la navigation vers **n'importe quelle route**, y compris `/dashboard` ou toute route protégée, **sans jamais vérifier qu'un JWT valide existe**. Un deep link (notification push, lien externe, restauration d'état) vers une route protégée contourne donc entièrement `_SplashRedirectPage` (qui, lui, valide bien le token) — **When** une garde globale est ajoutée par défaut dans cette story (recommandation : fermer le finding puisqu'il est cité en Refs, plutôt que le laisser en defer silencieux comme un gap non documenté), **Then** :
   - Le `redirect` top-level (`app_router.dart:336-347`) vérifie, pour tout chemin qui n'est **pas** dans un allow-list public (`/splash`, `/auth/login`, `/auth/register`, `/onboarding` et ses sous-routes), qu'un JWT structurellement valide et non expiré existe (réutiliser `_isValidJwt(token)`, déjà définie ligne 77-94) **avant** d'évaluer `_ownerOnlyPrefixes`.
   - Si aucun token valide n'existe **et** la route demandée n'est pas publique → redirect vers `/splash` (qui refait la validation complète + refresh silencieux + routing correct, réutilise la logique existante plutôt que de la dupliquer).
   - **Ne PAS** dupliquer la logique de refresh silencieux dans le `redirect` top-level — se contenter de renvoyer vers `/splash`, qui la possède déjà (`_tryProactiveRefresh`, ligne 116-141).
   - **⚠️ Si le code-review/PO juge cette AC hors scope de cette story** (parce que non-littérale dans l'épique) et préfère la traiter comme un defer séparé, documenter ce choix explicitement dans Completion Notes — mais **ne pas l'omettre silencieusement**, car S12 est cité en Refs et serait alors un finding audit rouvert sans trace.

**Doc :** ARCH21 (S8/S9) + Story 8.3 (référencée par l'épique — **recherché, aucun fichier `8-3-*.md` n'existe dans `implementation-artifacts/`, ni de section « 8.3 » identifiable dans `epic-8-alertes-notifications-gestion-oprationnelle.md` sous ce numéro exact — probable référence obsolète/renumérotée de l'épique, ne pas bloquer dessus, noter en Completion Notes que la référence n'a pas pu être résolue**). **TDD :** `shouldUseEncryptedSharedPreferencesOnAndroid()`, `shouldNotLogAuthorizationHeaderInRelease()`, `shouldRedirectToLoginOnAccountInactive()` (noms cités littéralement par l'épique — voir Dev Notes pour le mapping exact vers les tests réels, certains noms sont adaptés car `kDebugMode` ne peut pas être basculé à l'exécution dans un test).

## Tasks / Subtasks

- [x] **Task 1 — `SecureStorageProvider` centralisé (AC1)**
  - [x] 1.1 Créer `lib/core/storage/secure_storage_provider.dart` : fonction `FlutterSecureStorage buildSecureStorage()` retournant `const FlutterSecureStorage(aOptions: AndroidOptions(encryptedSharedPreferences: true), iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock_this_device))`. Imports : `package:flutter_secure_storage/flutter_secure_storage.dart`.
  - [x] 1.2 `auth_provider.dart:55-57` : remplacer le corps de `flutterSecureStorageProvider` par `Provider<FlutterSecureStorage>((ref) => buildSecureStorage())`, importer le nouveau fichier. **Ne pas renommer le provider.**
  - [x] 1.3 `main.dart:39` : remplacer `const secureStorage = FlutterSecureStorage();` par `final secureStorage = buildSecureStorage();` (import du nouveau fichier).
  - [x] 1.4 `app_router.dart:200` : remplacer `const storage = FlutterSecureStorage();` par `final storage = ref.read(flutterSecureStorageProvider);` (le `State` est déjà `ConsumerState`, `ref` est disponible).
  - [x] 1.5 `app_router.dart:360` : remplacer `const storage = FlutterSecureStorage();` par `final storage = ProviderScope.containerOf(context, listen: false).read(flutterSecureStorageProvider);`.
  - [x] 1.6 `providers.dart:66` (`currentUserIdProvider`) : remplacer `const storage = FlutterSecureStorage(); return storage.read(...)` par `return ref.read(flutterSecureStorageProvider).read(key: 'user_id');`.
  - [x] 1.7 `providers.dart:91` (`syncServiceProvider`) : remplacer l'argument `secureStorage: const FlutterSecureStorage(),` par `secureStorage: ref.watch(flutterSecureStorageProvider),`.
  - [x] 1.8 `settings_page.dart:399` : remplacer `const storage = FlutterSecureStorage(); await storage.deleteAll();` par `await ref.read(flutterSecureStorageProvider).deleteAll();` (le widget a déjà `ref`, c'est un `ConsumerWidget`/`ConsumerState` — vérifier le type exact de la classe englobante avant d'éditer).
  - [x] 1.9 **Ne PAS toucher** les 4 fichiers de test qui instancient `const FlutterSecureStorage()` en mock (`rest_sync_service_pull_test.dart`, `sync_pull_merge_test.dart`, `sync_diagnostic_test.dart` ×2, `db_encryption_key_service_test.dart` — ce dernier utilise `MockFlutterSecureStorage()`, sans rapport).

- [x] **Task 2 — `LogInterceptor` gated + redaction (AC2)**
  - [x] 2.1 `auth_provider.dart:102-109` : envelopper l'ajout de `LogInterceptor` dans `if (kDebugMode) { dio.interceptors.add(LogInterceptor(...)); }`. Importer `package:flutter/foundation.dart` (déjà importé ligne 4 pour `debugPrint` — vérifier que `kDebugMode` est bien exporté du même import, c'est le cas).
  - [x] 2.2 Extraire la logique de redaction en fonction pure top-level testable (ne PAS l'inliner dans `logPrint`) : `String redactAuthorizationHeader(String line)` dans le même fichier ou un nouveau `lib/core/network/log_redaction.dart` — regex `RegExp(r'Bearer\s+\S+')` remplacée par `'Bearer [REDACTED]'` si la ligne contient `'Authorization'`, sinon retourne la ligne inchangée.
  - [x] 2.3 `logPrint: (o) => debugPrint('[DIO] ${redactAuthorizationHeader(o.toString())}')`.

- [x] **Task 3 — HTTPS par défaut + rejet certificats (AC3)**
  - [x] 3.1 `auth_provider.dart:51` : `defaultValue: 'https://localhost:4500'` (garder le commentaire existant, juste changer le schéma).
  - [x] 3.2 `app_router.dart:72` : idem, garder synchronisé (le commentaire ligne 68-69 dit déjà « Mirrors the API_BASE_URL from auth_provider.dart » — ne pas dé-dupliquer davantage, hors scope).
  - [x] 3.3 `auth_provider.dart` : sur `dioProvider` (après construction du `Dio`, avant l'ajout des interceptors) ET sur `_refreshDioProvider`, ajouter :
    ```dart
    dio.httpClientAdapter = IOHttpClientAdapter(
      createHttpClient: () {
        final client = HttpClient();
        client.badCertificateCallback = (cert, host, port) => kDebugMode && host == 'localhost';
        return client;
      },
    );
    ```
    Importer `package:dio/io.dart` (pour `IOHttpClientAdapter`) et `dart:io` (pour `HttpClient`).

- [x] **Task 4 — `AuthInterceptor` branche `SESSION_REVOKED`/`ACCOUNT_INACTIVE` (AC4)**
  - [x] 4.1 `core/router/app_router.dart` (ou nouveau petit fichier `lib/core/navigation/root_scaffold_messenger.dart`, au choix — cohérent avec `appRouter` déjà global dans `app_router.dart`, préférer y ajouter) : `final rootScaffoldMessengerKey = GlobalKey<ScaffoldMessengerState>();`.
  - [x] 4.2 `main.dart:67` (`MaterialApp.router(...)`) : ajouter `scaffoldMessengerKey: rootScaffoldMessengerKey,`.
  - [x] 4.3 `core/network/auth_interceptor.dart` : ajouter un champ `final void Function()? onAccountSuspended;` au constructeur (paramètre nommé optionnel, mirroring `onPlanLimitExceeded` ligne 31/41).
  - [x] 4.4 Dans `onError` (avant le bloc `if (statusCode == 401 && !_isRefreshing)` existant ou en première sous-condition dedans) : `final domainCode = _extractDomainCode(err.response?.data); if (statusCode == 401 && (domainCode == 'SESSION_REVOKED' || domainCode == 'ACCOUNT_INACTIVE')) { await _expireSession(); onAccountSuspended?.call(); handler.next(err); return; }`.
  - [x] 4.5 `auth_provider.dart:92-100` (construction de `AuthInterceptor`) : ajouter `onAccountSuspended: () { rootScaffoldMessengerKey.currentState?.showSnackBar(const SnackBar(content: Text('Votre accès a été suspendu, veuillez vous reconnecter.'))); },`. Importer `material.dart` si absent (déjà importé indirectement ? vérifier — `auth_provider.dart` importe actuellement `flutter/foundation.dart`, pas `material.dart` — à ajouter).

- [x] **Task 5 — Garde d'auth globale deep link (AC5, scope ajouté — voir note AC5)**
  - [x] 5.1 `app_router.dart:336-347` (`redirect` top-level du `GoRouter`) : avant le check `_ownerOnlyPrefixes`, ajouter un check de session pour les chemins non publics : définir `const _publicPathPrefixes = ['/splash', '/auth', '/onboarding'];` puis `final isPublicPath = _publicPathPrefixes.any((p) => path.startsWith(p)); if (!isPublicPath) { final storage = ProviderScope.containerOf(context, listen: false).read(flutterSecureStorageProvider); final token = await storage.read(key: 'jwt_token'); if (token == null || !_isValidJwt(token)) return '/splash'; }`.
  - [x] 5.2 Conserver inchangé le check `_ownerOnlyPrefixes` existant après ce nouveau bloc (il s'exécute seulement si un token valide existe).
  - [x] 5.3 **Ne pas** dupliquer `_tryProactiveRefresh` ici — un token expiré-mais-structurellement-valide doit passer par `/splash` pour tenter le refresh silencieux, pas être traité inline.

- [x] **Task 6 — TDD (transverse, toutes AC)**
  - [x] 6.1 `test/core/storage/secure_storage_provider_test.dart` (nouveau) : `shouldUseEncryptedSharedPreferencesOnAndroid()` — `final storage = buildSecureStorage(); expect(storage.aOptions.toMap()['encryptedSharedPreferences'], equals('true'));` (`AndroidOptions.toMap()` est public, confirmé en lisant le package `flutter_secure_storage-9.2.4` installé — résolu depuis la contrainte `^9.2.2` de `pubspec.yaml`, voir `pubspec.lock`).
  - [x] 6.2 `test/core/network/log_redaction_test.dart` (nouveau, ou groupe dans un fichier existant) : `shouldNotLogAuthorizationHeaderInRelease()` — **adapté** : comme `kDebugMode` est une constante de compilation non bascultable à l'exécution dans `flutter test` (qui tourne toujours en mode debug), ce test unitaire vérifie la fonction pure `redactAuthorizationHeader()` isolément : `expect(redactAuthorizationHeader('Authorization: Bearer eyJhbGci...'), equals('Authorization: Bearer [REDACTED]'));` + un cas où la ligne ne contient pas `Authorization` (retour inchangé). **Documenter en Completion Notes** que le gating `if (kDebugMode)` lui-même (AC2, l'interceptor absent en release) n'est pas vérifiable par un test unitaire standard — vérification par revue de code uniquement, comme c'est le cas pour tout code conditionné par `kDebugMode`/`kReleaseMode` dans Flutter.
  - [x] 6.3 `test/core/network/auth_interceptor_test.dart` : ajouter `shouldRedirectToLoginOnAccountInactive()` (nom cité par l'épique, adapté ici en 2 tests distincts pour SESSION_REVOKED et ACCOUNT_INACTIVE) — reprendre le pattern des tests existants (ligne 145-188, refresh-failure case) : `err` avec `domainCode: 'SESSION_REVOKED'` → `verify(() => mockStorage.clearAll()).called(1)`, `expect(sessionExpiredCount, equals(1))`, et `expect(accountSuspendedCount, equals(1))` (nouveau compteur mirroring `sessionExpiredCount` pour `onAccountSuspended`). Répéter pour `domainCode: 'ACCOUNT_INACTIVE'`. **Ne pas modifier** le test existant `'passes non-TOKEN_EXPIRED 401 errors through unchanged'` (ligne 190-209, `INVALID_CREDENTIALS`) — doit rester GREEN sans changement.
  - [x] 6.4 `flutter analyze` (côté `keevo/app`) — 0 erreurs, 0 warnings nouveaux.
  - [x] 6.5 `flutter test` (suite complète `keevo/app`) — 0 nouvelle régression. Vérifier en particulier que les tests existants touchant `dioProvider`/`AuthInterceptor`/`app_router.dart` (splash redirect, login redirect) restent GREEN après Task 5 (nouveau check async dans `redirect` — s'assurer qu'aucun test de navigation existant ne dépendait implicitement de l'ancien comportement « laisse passer sans session »).

- [x] **Task 7 — Dev Agent Record**
  - [x] 7.1 Renseigner Agent Model, Debug Log, Completion Notes (inclure explicitement : (a) la clarification Story 1.3 AC6 vs AC5, (b) le statut de la référence Story 8.3 non résolue, (c) la décision prise sur l'AC5 — scope ajouté S12, accepté ou reporté en defer), File List.

## Dev Notes

### Pourquoi le nom `flutterSecureStorageProvider` ne change pas

`providers.dart:111` (`syncDiagnosticRunnerProvider`) l'utilise déjà via `ref.watch(flutterSecureStorageProvider)` — renommer casserait ce call-site sans bénéfice. Le seul changement est **où** l'instance est construite (délégation vers `buildSecureStorage()`) et **ses options** (hardening) — pas son identité Riverpod.

### `main.dart` est un cas spécial — pas de `ref` disponible

`main.dart:39` construit `secureStorage` **avant** `runApp(ProviderScope(...))` — il n'y a littéralement pas de `WidgetRef`/`ProviderContainer` à ce stade. C'est pourquoi `buildSecureStorage()` doit être une **fonction factory autonome**, pas seulement un provider Riverpod — le provider `flutterSecureStorageProvider` délègue à cette même factory pour garantir des options identiques partout, mais `main.dart` appelle la factory directement.

### `ProviderScope.containerOf(context)` — pattern déjà existant dans ce fichier

`app_router.dart:446` utilise déjà `ProviderScope.containerOf(context)` pour lire un provider depuis un callback `GoRoute` sans `ref` direct (uniquement `context`) — **réutiliser ce pattern exact** pour Task 1.5 et Task 5.1, ne pas inventer un mécanisme différent (ex. singleton global, service locator).

### `IOHttpClientAdapter` — API dio 5.9.2 vérifiée dans le package installé

Vérifié directement dans `~/.pub-cache/hosted/pub.dev/dio-5.9.2/lib/src/adapters/io_adapter.dart` : le champ `onHttpClientCreate` porte l'annotation `@Deprecated('Use createHttpClient instead. This will be removed in 6.0.0')` (ligne 34-43) — **utiliser `createHttpClient`** (champ `CreateHttpClient? createHttpClient`, ligne 47), pas l'ancien nom. `pubspec.yaml` déclare `dio: ^5.7.0`, la version résolue dans `pubspec.lock` est `5.9.2` — cohérent avec cette API.

### Redaction du Bearer token — pourquoi pas un simple `requestHeader: false`

Mettre `requestHeader: false` supprimerait TOUT logging d'en-têtes en debug (perte de valeur diagnostique — Content-Type, etc. sont utiles). L'épique demande spécifiquement de **garder** le logging mais **redacter uniquement** `Authorization` — d'où la fonction `redactAuthorizationHeader()` appliquée sur la ligne déjà formatée par `LogInterceptor` avant de l'écrire via `debugPrint`. `LogInterceptor.logPrint` reçoit chaque ligne déjà formatée en `Object` (généralement une `String`) — c'est le seul point d'interception disponible sans réimplémenter `LogInterceptor` en entier (hors scope, ce serait une réinvention massive d'un interceptor tiers stable).

### Callback `onAccountSuspended` — pourquoi un nouveau champ plutôt que réutiliser `onSessionExpired`

`onSessionExpired` (`auth_interceptor.dart:26`) est déjà appelé par `_expireSession()` pour la navigation (`appRouter.go('/auth/login')`, câblé dans `auth_provider.dart:96-99`) — **le réutiliser tel quel** pour la partie clear+navigate (déjà fait via `_expireSession()`, ligne 4.4 des tasks l'appelle). Le **nouveau** callback `onAccountSuspended` sert **uniquement** à déclencher le SnackBar, en plus de `onSessionExpired` — mirroring exactement le pattern déjà établi par `onPlanLimitExceeded` (champ optionnel séparé, ligne 31, câblé indépendamment). **Ne pas** essayer de faire porter la logique SnackBar par `onSessionExpired` lui-même — ce callback est aussi appelé par le chemin `TOKEN_EXPIRED`+refresh-échoué (ligne 115-119), où aucun SnackBar « suspendu » ne doit apparaître (l'utilisateur a juste un token expiré, pas un compte désactivé — message différent, pas encore géré par cette story, hors scope).

### `rootScaffoldMessengerKey` — nouveau pattern global, aucun équivalent existant

Recherché : aucun `GlobalKey<ScaffoldMessengerState>` n'existe dans le codebase aujourd'hui. `onPlanLimitExceeded` (le seul précédent de callback global déclenché depuis l'interceptor) n'est en réalité **jamais câblé** dans `dioProvider` (vérifié : absent des arguments de construction `AuthInterceptor(...)` ligne 92-100) — chaque page qui montre `PlanLimitBottomSheet` le fait localement en interceptant elle-même l'exception (`stores_list_page.dart:103`, `create_employee_page.dart:92`), **pas** via ce hook global. **Ce n'est donc PAS un pattern à copier** pour AC4 — un SnackBar de session-révoquée doit s'afficher globalement (l'utilisateur peut être sur n'importe quelle page au moment de la révocation), d'où la nécessité du `rootScaffoldMessengerKey`, introduit pour la première fois par cette story. Co-localiser avec `appRouter` (même fichier `app_router.dart`) car c'est déjà le seul autre singleton global de navigation du projet — cohérence architecturale, pas une nouvelle catégorie de fichier.

### AC5 (garde deep link) — pourquoi documenté comme scope ajouté plutôt qu'ignoré

L'épique liste **S12** dans ses Refs (`epics-remediation-audit.md:175`) mais le texte `Given/When/Then` ne le couvre pas explicitement — situation similaire (en plus léger) à la Décision D1 de Story 12.6, où un Ref cité méritait une décision explicite plutôt qu'un silence. **Différence importante avec D1** : ici il n'y a pas de tension produit (pas de hotfix contradictoire à préserver), juste un gap non couvert par le texte littéral. Le défaut recommandé est de fermer le gap (AC5) puisqu'il est cité en Refs et que le code actuel confirme le bug (`redirect` laisse passer une navigation sans session vers une route protégée si `role != 'EMPLOYEE'`, y compris `role == null`). Si le code-review juge que c'est un scope creep à reporter, la Task 5 est isolée (un seul bloc dans `redirect`) et peut être retirée sans impacter AC1-AC4.

### Fichiers Flutter à toucher — récapitulatif

| Fichier | Changement | AC |
|---|---|---|
| `lib/core/storage/secure_storage_provider.dart` (nouveau) | `buildSecureStorage()` factory | AC1 |
| `lib/features/auth/presentation/provider/auth_provider.dart` | `flutterSecureStorageProvider` délègue à la factory ; `LogInterceptor` gated+redacted ; `_apiBaseUrl` https ; `IOHttpClientAdapter` sur `dioProvider`/`_refreshDioProvider` ; `AuthInterceptor(onAccountSuspended: ...)` | AC1, AC2, AC3, AC4 |
| `lib/core/network/auth_interceptor.dart` | Nouveau champ `onAccountSuspended` + branche `onError` SESSION_REVOKED/ACCOUNT_INACTIVE | AC4 |
| `lib/core/network/log_redaction.dart` (nouveau, ou inline) | `redactAuthorizationHeader()` | AC2 |
| `lib/main.dart` | `buildSecureStorage()` direct ; `scaffoldMessengerKey: rootScaffoldMessengerKey` sur `MaterialApp.router` | AC1, AC4 |
| `lib/core/router/app_router.dart` | `_kApiBaseUrl` https ; 2 sites `FlutterSecureStorage` → provider ; `rootScaffoldMessengerKey` (nouveau) ; garde d'auth globale dans `redirect` (AC5) | AC1, AC3, AC4, AC5 |
| `lib/core/di/providers.dart` | 2 sites `FlutterSecureStorage` → `ref.read/watch(flutterSecureStorageProvider)` | AC1 |
| `lib/features/settings/presentation/page/settings_page.dart` | 1 site `FlutterSecureStorage` → `ref.read(flutterSecureStorageProvider)` | AC1 |

**Fichiers explicitement HORS scope** : tout le backend Java (aucun changement — le backend Story 12.2 est déjà `done` et suffisant) ; `secure_token_storage.dart` (`SecureTokenStorage` — déjà correctement encapsulé, ne fait qu'utiliser l'instance injectée, aucun changement requis) ; les 4 fichiers de test qui mockent/instancient `FlutterSecureStorage` directement pour leurs propres besoins de test.

### Fichiers de test à toucher

| Test | Fichier | Nouveau/modifié |
|---|---|---|
| `secure_storage_provider_test.dart` | `test/core/storage/secure_storage_provider_test.dart` | Nouveau — AC1 |
| `log_redaction_test.dart` (ou groupe existant) | `test/core/network/log_redaction_test.dart` | Nouveau — AC2 |
| `auth_interceptor_test.dart` | `test/core/network/auth_interceptor_test.dart` | Étendu — AC4, réutiliser les mocks déjà déclarés (`MockTokenStorage`, `MockDio`, `MockErrorInterceptorHandler`) et le helper `_pump()` (ligne 18) |

### Project Structure Notes

- Alignement avec la structure existante : `lib/core/storage/` existe déjà (tables Drift) — `secure_storage_provider.dart` y a sa place naturelle, cohérent avec `db_encryption_key_service.dart` déjà présent dans ce même dossier pour un besoin similaire (clé de chiffrement SQLCipher).
- `lib/core/network/` existe déjà (`auth_interceptor.dart`, `retry_interceptor.dart`) — `log_redaction.dart` (si extrait en fichier séparé plutôt qu'inline dans `auth_provider.dart`) y a sa place naturelle.
- Aucune nouvelle couche d'architecture introduite — cette story ajoute des call-sites à des providers Riverpod déjà existants (pattern établi), pas une nouvelle abstraction.

### Testing standards summary

- Tests unitaires Flutter : `flutter_test` + `mocktail` (pattern déjà utilisé dans `auth_interceptor_test.dart` — `Mock`, `registerFallbackValue`, `when()/verify()`).
- Pas de test d'intégration/E2E requis pour cette story (contrairement à d'autres stories V1-stab qui touchent le backend) — la story est Flutter-only et les changements sont unitairement testables (factory pure, fonction de redaction pure, interceptor déjà testé par mocks).
- `flutter analyze` obligatoire avant de considérer la story terminée (0 nouveaux warnings/erreurs).

### References

- [Source: _bmad-output/planning-artifacts/epics/epics-remediation-audit.md#Story 12.7 (lignes 171-185)]
- [Source: AUDIT_CONFORMITE_BMAD.md#10. Findings Sécurité (S8 ligne 277, S9 ligne 278, S12 ligne 281)]
- [Source: _bmad-output/implementation-artifacts/1-3-jwt-authentication-session-management.md#AC6 — HTTPS / TLS 1.2+ enforcement (lignes 60-65)]
- [Source: keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java:62-63 — ACCOUNT_INACTIVE, SESSION_REVOKED déjà définis par Story 12.2]
- [Source: keevo/app/lib/features/auth/presentation/provider/auth_provider.dart — dioProvider, _apiBaseUrl, LogInterceptor]
- [Source: keevo/app/lib/core/network/auth_interceptor.dart — onError, onPlanLimitExceeded pattern]
- [Source: keevo/app/lib/core/router/app_router.dart — _ownerOnlyPrefixes, redirect, _isValidJwt, ProviderScope.containerOf pattern ligne 446]
- [Source: keevo/app/lib/core/di/providers.dart — flutterSecureStorageProvider usage ligne 111]
- [Source: ~/.pub-cache/hosted/pub.dev/dio-5.9.2/lib/src/adapters/io_adapter.dart — IOHttpClientAdapter.createHttpClient API]
- [Source: ~/.pub-cache/hosted/pub.dev/flutter_secure_storage-*/lib/options/android_options.dart — AndroidOptions.toMap()]

## Architecture Compliance

- **S8, S9, S12, ARCH21** : ferme les 3 findings sécurité client identifiés par l'audit sur le stockage sécurisé, le logging de secrets, et la garde d'authentification globale.
- **Story 1.3 AC6** : ferme rétroactivement l'exigence HTTPS/TLS jamais implémentée côté client depuis Story 1.3.
- **FR68 client, ties 12.2** : complète le cycle révocation de session — le backend (12.2) et maintenant le client (12.7) réagissent tous deux correctement à une session révoquée.
- **Refonte absorption** : la refonte Flutter devra ré-appliquer `SecureStorageProvider`/`AuthInterceptor` dans sa propre arborescence — pas de cherry-pick direct si la structure diverge, mais le pattern (factory + provider Riverpod + callback optionnel sur interceptor) reste transposable tel quel.

## Dev Agent Record

### Agent Model Used

GitHub Copilot — DeepSeek V4 Pro (2026-07-21)

### Debug Log References

- `flutter analyze`: 0 errors, 752 pre-existing info/warnings (no new issues)
- `flutter test` (full suite): 790 passed, 12 pre-existing failures — 0 new regressions
- `flutter test` (story-specific): 15/15 new tests GREEN (auth_interceptor: 8, log_redaction: 5, secure_storage_provider: 2)

### Completion Notes List

1. **(a) Clarification Story 1.3 AC6 vs AC5:** Confirmé : l'épique référence « Story 1.3 AC5 » mais le texte réel de Story 1.3 a AC6 = HTTPS/TLS 1.2+ enforcement (AC5 = bcrypt, sans rapport). L'AC3 de cette story ferme correctement cette exigence. Les commentaires existants dans le code (`auth_provider.dart:54,59` ancien) qui citaient « AC5 » ont été mis à jour vers « AC1 » pour refléter la numérotation de cette story.

2. **(b) Référence Story 8.3 non résolue:** La recherche de `8-3-*.md` dans `implementation-artifacts/` n'a donné aucun résultat. Aucun fichier `epic-8-*` identifiable sous ce numéro exact. Probable référence obsolète/renumérotée de l'épique original — ne bloque pas l'implémentation, la story 8-3 est `backlog` (désactivation employé / révocation de sessions), sans rapport direct avec le scope Flutter de celle-ci.

3. **(c) Décision AC5 (garde deep-link S12):** Implémentée en tant qu'AC5. Le bug était confirmé par lecture de code : si `role == null` (aucune session), la fonction `redirect` retournait `null`, laissant passer n'importe quelle navigation vers une route protégée. La fix ajoute un check de JWT valide avant le check `_ownerOnlyPrefixes`, redirigeant vers `/splash` si aucun token valide n'existe. Si le code-review juge que c'est un scope creep, la Task 5 est isolée (un seul bloc dans `redirect`) et peut être retirée sans impacter AC1-AC4.

4. **Notes implémentation:**
   - `buildSecureStorage()`: factory pure, 7 sites migrés (main.dart direct, 6 via Riverpod)
   - `LogInterceptor`: gated `kDebugMode` + `redactAuthorizationHeader()` — le gating compile-time n'est pas testable unitairement (documenté)
   - HTTPS: `defaultValue` → `https`, `IOHttpClientAdapter` avec `badCertificateCallback` debug-only localhost sur les 2 Dio instances
   - `AuthInterceptor.onAccountSuspended`: nouveau callback optionnel, pattern mirroring `onPlanLimitExceeded`
   - `rootScaffoldMessengerKey`: nouveau singleton global co-localisé avec `appRouter`
   - `redirect` top-level: ajout garde JWT pour routes non-publiques (AC5)

### File List

| Fichier | Action |
|---|---|
| `keevo/app/lib/core/storage/secure_storage_provider.dart` | Nouveau |
| `keevo/app/lib/core/network/log_redaction.dart` | Nouveau |
| `keevo/app/lib/features/auth/presentation/provider/auth_provider.dart` | Modifié |
| `keevo/app/lib/core/network/auth_interceptor.dart` | Modifié |
| `keevo/app/lib/core/router/app_router.dart` | Modifié |
| `keevo/app/lib/main.dart` | Modifié |
| `keevo/app/lib/core/di/providers.dart` | Modifié |
| `keevo/app/lib/features/settings/presentation/page/settings_page.dart` | Modifié |
| `keevo/app/test/core/storage/secure_storage_provider_test.dart` | Nouveau |
| `keevo/app/test/core/network/log_redaction_test.dart` | Nouveau |
| `keevo/app/test/core/network/auth_interceptor_test.dart` | Modifié |

### Review Findings

> Code review `bmad-code-review` (2026-07-21) — 3 couches : Blind Hunter, Edge Case Hunter, Acceptance Auditor.
> Acceptance Auditor : **tous les AC1-AC5 PASS**, aucune violation de spec.
> 1 decision, 4 patches, 4 defers, ~25 dismissed (faux positifs, spec-mandated, ou pré-existants).

- [x] [Review][Decision] **Migration `encryptedSharedPreferences` — perte de données silencieuse upgrade** — ✅ **RÉSOLU option A (migration one-shot)** : `migrateToEncryptedStorage()` ajouté dans `secure_storage_provider.dart`, appelé dans `main.dart:42` avant `DbEncryptionKeyService`. Lit l'ancien backing store (8 clés : jwt_token, refresh_token, user_id, tenant_id, store_id, db_encryption_key, keevo_device_id, last_sync_timestamp_ms), écrit dans le nouveau store hardené. Gardé par flag `secure_storage_migrated_v1` dans SharedPreferences — idempotent, ne tourne qu'une fois par device.

- [x] [Review][Patch] **`/auth/change-password` bypass de la garde d'auth globale (S12)** [`app_router.dart:350`] — ✅ **PATCHÉ** : `_publicPathPrefixes` remplacé par `_publicPaths` (set exact : `/splash`, `/auth/login`, `/auth/register`) + `_publicPathPrefixes` réduit à `/onboarding` uniquement. `/auth/change-password` et `/tenant-picker` requièrent désormais un JWT valide.
- [x] [Review][Patch] **`_tryProactiveRefresh` Dio nu sans `IOHttpClientAdapter`** [`app_router.dart:126-130`] — ✅ **PATCHÉ** : `IOHttpClientAdapter(createHttpClient: ...)` ajouté avec même `badCertificateCallback` que `dioProvider`/`_refreshDioProvider` (debug-only localhost).
- [x] [Review][Patch] **Pas de try/catch autour de `_expireSession()` dans le chemin SESSION_REVOKED** [`auth_interceptor.dart:96-100`] — ✅ **PATCHÉ** : enveloppé dans `try/catch` avec `handler.next(err)` garanti en fallback (même si le storage lève).
- [x] [Review][Patch] **Tri d'imports violé** [`app_router.dart:10`] — ✅ **PATCHÉ** : imports réordonnés alphabétiquement.

- [x] [Review][Defer] **Race condition SESSION_REVOKED vs TOKEN_EXPIRED refresh concurrent** [`auth_interceptor.dart:91-143`] — si Request A est en refresh (`_isRefreshing=true`) et Request B reçoit SESSION_REVOKED, le garde `!_isRefreshing` empêche B d'entrer dans le handler SESSION_REVOKED → B tombe en `handler.next(err)` sans nettoyage session. Auto-cicatrisant (prochaine requête recevra aussi 401 SESSION_REVOKED avec `_isRefreshing=false`). Nécessite un mécanisme de garde cross-path (`_sessionDead` flag) — hors scope.
- [x] [Review][Defer] **SnackBar flood sur SESSION_REVOKED concurrent** [`auth_provider.dart:128-136`] — N requêtes simultanées recevant 401 SESSION_REVOKED déclenchent N SnackBars identiques (file d'attente ~15s). Nécessite un mécanisme de debounce/déduplication — hors scope.
- [x] [Review][Defer] **Lecture secure storage à chaque navigation (AC5 perf)** [`app_router.dart:353-357`] — chaque transition de route (y compris changements d'onglet) déclenche `storage.read()` + déchargement AES via `EncryptedSharedPreferences` (10-50ms). Spéc-mandaté par AC5 ; l'optimisation (cache Riverpod synchronisé login/logout/refresh) est un changement architectural hors scope.
- [x] [Review][Defer] **Duplication `badCertificateCallback` verbatim** [`auth_provider.dart:78-85,103-110`] — bloc 9 lignes identique sur `dioProvider` et `_refreshDioProvider`. DRY : extraire `createDebugHttpClient()` — cosmétique, non bloquant.
