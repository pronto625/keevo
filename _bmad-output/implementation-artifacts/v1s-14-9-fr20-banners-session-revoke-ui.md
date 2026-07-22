---
baseline_commit: ccfad6b45bc5978f9ac3ca43c0926210a46758bf
---
# Story 14.9: FR20 — Bannières trial/suspension + branche session-révoquée

Status: done

<!-- V1-stabilization track — tag A (patch V1, ties Story 12.2/12.7, Sprint 2 "scope V1 promis").
     Branche : v1-stabilization.
     Refonte absorption : « re-appliquer Flutter » (aucune logique backend nouvelle dans cette story).
     100% Flutter, aucune migration Flyway, aucun endpoint backend créé.
     Validation optionnelle : lancer validate-create-story avant dev-story. -->

## Story

**As a** proprietaire (Simon),
**I want** voir une bannière persistante quand mon compte est suspendu ou que mon essai Premium vient d'expirer, et être proprement déconnecté si ma session est révoquée,
**so that** je comprenne immédiatement l'état de mon compte sans devoir deviner pourquoi une action échoue, et que l'app ne reste jamais dans un état incohérent après révocation.

## Contexte V1-stabilization

- **Source backlog :** `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md:429-441` → Story 14.9 (Refs FR20, F-HIGH-5/8, B-HIGH-5, ties 12.2/12.7).
- **Audit détaillé :** `AUDIT_CONFORMITE_BMAD.md:184-185` (F-HIGH-8) et `:181-182` (F-HIGH-7, déjà fermé par 12.7 — voir ci-dessous).
- **Priorité :** Sprint 2 "scope V1 promis" (`epics-remediation-audit.md:47`), tag **A**.
- **Index track :** `sprint-status.yaml` — `v1s-14-9-fr20-banners-session-revoke-ui` · Epic `v1s-epic-14-functional-gaps-v1` (in-progress).
- **⚠️ Investigation préalable (cette création de story) a REQUALIFIÉ la moitié de l'AC de l'épique — voir "Ce qui existe déjà" ci-dessous.** Le texte AC couple deux choses dans un seul "When/Then" ; l'une est déjà faite (Story 12.7), l'autre reste à faire. Ne pas ré-implémenter la partie déjà livrée.
- **Story précédente (14.8, `done`) :** aucune dépendance de code directe — fichiers touchés disjoints (product/transfer/reports/api_service/ApiResponseWrapper vs settings/account_status ici).

## Ce qui existe déjà (ne pas réinventer)

### Partie 1 de l'AC épique — teardown de session sur révocation — **DÉJÀ ENTIÈREMENT LIVRÉE par Story 12.7 (`done`, commit `1299370`)**

Le texte de l'épique demande : *"`AuthInterceptor.onError` `ACCOUNT_INACTIVE`/`SESSION_REVOKED` branch → `_expireSession()` + 'Votre accès a été suspendu…' (ties 12.2 backend + 12.7 client)"*. **Vérifié dans le code actuel — 100% déjà implémenté :**

```dart
// keevo/app/lib/core/network/auth_interceptor.dart:96-106
if (domainCode == 'SESSION_REVOKED' || domainCode == 'ACCOUNT_INACTIVE') {
  try {
    await _expireSession();          // clearAll() + onSessionExpired() → go('/auth/login')
    onAccountSuspended?.call();      // → SnackBar
  } catch (_) { /* storage failure, still propagate */ }
  handler.next(err);
  return;
}
```
```dart
// keevo/app/lib/features/auth/presentation/provider/auth_provider.dart:124-132
onAccountSuspended: () {
  rootScaffoldMessengerKey.currentState?.showSnackBar(
    const SnackBar(content: Text('Votre accès a été suspendu, veuillez vous reconnecter.')),
  );
},
```
Tests déjà GREEN : `test/core/network/auth_interceptor_test.dart:212-291` (groupe `SESSION_REVOKED / ACCOUNT_INACTIVE`, 3 tests couvrant les deux domainCodes + le cas non-matché). **TDD nommé dans l'épique `shouldTearDownSessionOnAccountInactive()` est donc déjà satisfait — aucun code à écrire pour cette partie.** Ne PAS retoucher `auth_interceptor.dart`/`auth_provider.dart` pour ce point — seule une vérification (exécuter la suite existante) est requise en Task de régression.

### Partie 2 de l'AC épique — bannières `accountStatusProvider`/`SuspensionBanner` — **RÉELLEMENT LE STUB, à implémenter**

`account_status_provider.dart:26` (F-HIGH-8, confirmé) :
```dart
final accountStatusProvider = Provider<AccountStatus>((ref) => AccountStatus.active,);
// Commentaire d'origine : "Phase 2: replace with an AsyncNotifier that reads the JWT
// tenantStatus claim from TokenStorage and/or the subscription expires_at field."
```
`SuspensionBanner` (`suspension_banner.dart`) est un widget **complet et déjà testé isolément** (`test/features/settings/presentation/widget/suspension_banner_test.dart`, 3 tests : suspended/trialExpired/active — tous GREEN aujourd'hui car ils **overrident** `accountStatusProvider` directement, ils ne testent jamais la vraie logique de dérivation). Mais **le widget n'est monté nulle part dans l'arbre de rendu** (confirmé : `grep -rn "SuspensionBanner(" lib` → seule la déclaration `const SuspensionBanner({super.key})` dans son propre fichier, zéro appelant).

**Ce qui reste réellement à faire :**
1. Remplacer le stub par une vraie dérivation (Task 1).
2. Monter `SuspensionBanner()` dans l'écran principal (Task 2).

### Données déjà disponibles pour la dérivation (aucune nouvelle plomberie backend requise)

**Champ `tenantStatus` (JWT claim)** — mécanisme complet, déjà en prod :
- Émis à la connexion par `JwtTokenProvider.java:63,112` (`claim("tenantStatus", tenantStatus)`).
- Contrôlé côté backend par `JwtAuthFilter.java:169-177` : si `"SUSPENDED".equals(tenantStatus)` **et** méthode d'écriture → `403 {"domainCode":"ACCOUNT_SUSPENDED"}` (lecture toujours permise — commentaire ligne 169-171 : *"natural trial/premium expiry does NOT set SUSPENDED — it downgrades to FREE"*). Ce `tenantStatus=SUSPENDED` est donc un concept **distinct** de `SESSION_REVOKED`/`ACCOUNT_INACTIVE` (401, session entièrement invalidée) — c'est un **verrouillage manuel admin des écritures seulement**, la lecture continue de marcher, donc l'utilisateur reste connecté et navigue dans l'app pendant qu'il est suspendu → **exactement le scénario où une bannière persistante (pas un SnackBar one-shot) apporte de la valeur**, contrairement à SESSION_REVOKED qui déconnecte immédiatement.
- Décodage JWT côté Flutter : **aucun package `jwt_decoder`** dans `pubspec.yaml` — le pattern établi (3 occurrences existantes) est un décodage manuel `base64Url.decode(base64Url.normalize(parts[1]))` + `jsonDecode`, dupliqué localement par fichier (`app_router.dart:_isTenantActive`, `auth_provider.dart:_extractFirstNameFromJwt`, `sync_diagnostic_runner.dart`). **Suivre ce pattern** (4ème occurrence acceptée par convention du projet — ne PAS créer d'utilitaire partagé, ce serait un refactor hors-scope d'un patch tag-A qui toucherait 3 fichiers existants sans rapport avec cette story).
- `TokenStorage.getToken()` (`token_storage.dart:22`) est **async** → `accountStatusProvider` doit rester un `Provider<AccountStatus>` **synchrone** (contrainte dure : les 3 tests existants de `suspension_banner_test.dart` font `accountStatusProvider.overrideWith((ref) => AccountStatus.suspended)` — un type `Provider<AccountStatus>`, pas `FutureProvider`/`AsyncNotifier`. Changer le type casse la compilation de ces 3 tests). **Solution :** un `FutureProvider<String?>` intermédiaire pour le claim, regardé via `.valueOrNull` depuis le `Provider` synchrone final (voir Task 1) — `active` par défaut pendant le chargement/en cas d'erreur, comportement de repli sûr identique au stub actuel.

**Champ `planType`/`status` (subscription)** — déjà exposé et consommé ailleurs :
- `subscriptionInfoProvider` (`subscription_info_provider.dart:31`) — `FutureProvider<SubscriptionInfo>` existant, `GET /api/v1/subscription/me`, déjà utilisé par `subscription_page.dart`. `SubscriptionInfo.planType` ∈ `{FREE, PREMIUM_TRIAL, PREMIUM}`.
- **Story 1.6 (AC, `epic-1-foundation-infrastructure-authentication.md:255-256,271-277`) garantit qu'un tenant n'atteint JAMAIS `FREE` autrement que par expiration de son Premium Trial** (`"When the tenant is provisioned Then the subscription is initialized as PREMIUM_TRIAL"` — aucun chemin d'inscription direct vers `FREE`). Donc `planType == 'FREE'` ⟺ "essai expiré" sans ambiguïté — pas besoin de lire `SubscriptionInfo.status` (qui a son propre `SUSPENDED`/`EXPIRED` — **concept séparé, propre à la ligne d'abonnement, non lié à `TenantStatus.SUSPENDED`**, voir Constat ci-dessous — ne pas confondre les deux).
- Réutiliser directement `subscriptionInfoProvider` (ne pas dupliquer l'appel réseau) — `ref.watch(subscriptionInfoProvider).valueOrNull`.

### Constat (hors-scope, à documenter pour le code review — ne PAS implémenter)

`SubscriptionStatus.SUSPENDED` (`subscription/plan/domain/model/SubscriptionStatus.java:16-17`, commentaire *"Manual admin lockout — all write operations blocked"*) et `TenantStatus.SUSPENDED` (`identity/auth/domain/model/TenantStatus.java:12`) sont **deux enums distincts** qui semblent décrire le même concept produit à deux endroits différents du backend — mais **aucun des deux n'a de endpoint pour être positionné à `SUSPENDED`** (recherche exhaustive : zéro `AdminTenantController`/`AdminSubscriptionController` n'expose de "suspend" — seul `POST /admin/subscriptions/{id}/activate` existe, Story 1.6). Positionner ces statuts est donc aujourd'hui manuel (hors API) ou un gap backend non couvert par cette story (frontend-only, tag A). Cette story lit uniquement le claim JWT `tenantStatus` (mécanisme d'enforcement déjà vivant côté `JwtAuthFilter`) — elle ne crée ni ne modifie aucun mécanisme de mise en suspension. Ne pas confondre `SubscriptionResponse.status` (jamais lu par cette story) avec le claim JWT `tenantStatus` (seule source utilisée ici).

De même, le 403 `ACCOUNT_SUSPENDED` (`JwtAuthFilter.java:174`, écriture bloquée pendant qu'un tenant est `SUSPENDED`) n'est **traité nulle part côté Flutter** aujourd'hui (ni `AuthInterceptor`, ni `GlobalExceptionHandler.FR_MESSAGES` qui n'a pas d'entrée pour ce code — `writeErrorWithStatus` construit la réponse brute sans passer par `GlobalExceptionHandler`). Un utilisateur suspendu qui tente une écriture après que son JWT en cache soit périmé (bannière pas encore rafraîchie) verrait un échec sans message clair. **Hors scope de cette story** : corriger ce point toucherait la plomberie de réponse partagée de `JwtAuthFilter` (qui écrit aussi `SESSION_REVOKED`/`ACCOUNT_INACTIVE`/`PASSWORD_CHANGE_REQUIRED`/`STORE_REASSIGNED`) — un chantier plus large que ce patch, similaire en nature à la Décision D3 de la Story 14.8 (éviter de dupliquer un futur chantier systémique). Documenté ici pour que le PO/reviewer tranche s'il faut ouvrir un ticket dédié.

## Décisions prises (documentées pour le code review — PO peut trancher différemment)

### D1 — Pas de 3ème état "essai en cours, N jours restants" dans `AccountStatus`

**Constat :** Story 1.6 (AC, `epic-1-foundation-infrastructure-authentication.md:257`) demande aussi une bannière *pendant* le trial actif ("Plan Premium Trial — Expire le [date]"), distincte de la bannière "essai expiré" (`trialExpired`). Mais l'énumération `AccountStatus` actuelle n'a que 3 valeurs (`active`/`suspended`/`trialExpired`), les 3 tests existants de `suspension_banner_test.dart` ne couvrent que ces 3 cas, et les noms de TDD cités par l'épique 14.9 (`shouldDisplayTrialExpiryBanner`, `shouldDisplaySuspensionBanner`, `shouldTearDownSessionOnAccountInactive`) ne mentionnent aucun test pour un état "trial actif". Cette information ("Expire le [date]") est déjà affichée ailleurs — `subscription_page.dart:246-249` (Paramètres > Souscription).
**Décision :** ne PAS ajouter de 4ème valeur d'enum ni de bannière "trial actif" en haut d'écran dans cette story — hors-scope de F-HIGH-8 tel qu'audité, périmètre déjà couvert par l'écran Souscription existant. Le PO peut demander cet ajout séparément s'il juge la visibilité insuffisante.
**Alternative rejetée :** ajouter un 4ème état `trialActive(daysLeft)` avec bannière informative — rejetée pour cette story, gonflerait le scope sans test ciblé dans l'épique.

### D2 — `AccountStatus.suspended` dérivé du claim JWT `tenantStatus`, pas de `SubscriptionResponse.status`

**Constat :** deux champs distincts nommés `SUSPENDED` existent dans le backend (voir Constat ci-dessus) ; le commentaire d'origine du stub (`account_status_provider.dart:11`, *"Source: JWT claim tenantStatus = SUSPENDED"*) présuppose déjà le bon choix.
**Décision :** dérivation exclusivement depuis le claim JWT (`tenantStatus`) décodé localement — c'est le seul mécanisme réellement câblé à un comportement d'enforcement backend (`JwtAuthFilter.java:172`). `SubscriptionResponse.status` n'est utilisé nulle part dans cette story (son seul champ consommé est `planType`, pour `trialExpired`).

### D3 — Pas de nouvelle branche `AuthInterceptor` pour `ACCOUNT_SUSPENDED` (403)

**Constat :** voir Constat ci-dessus — ce gap est réel mais distinct du texte AC de l'épique (qui ne cite que `SESSION_REVOKED`/`ACCOUNT_INACTIVE`, déjà fermés par 12.7) et toucherait une plomberie de réponse backend partagée par 4 autres domainCodes.
**Décision :** non traité dans cette story. La bannière proactive (claim JWT, Task 1/2) reste la garde-fou principal attendu par FR20 — elle previent l'utilisateur *avant* qu'il tente une écriture qui échouerait silencieusement. Documenté pour suivi éventuel (pas ajouté à `deferred-work.md` par cette story de création — à faire par le dev/reviewer s'il confirme le gap en review).

## Acceptance Criteria

### AC1 — `accountStatusProvider` dérive un vrai état (suspended / trialExpired / active) au lieu du stub

**Given** `accountStatusProvider` retourne toujours `AccountStatus.active` (stub, `account_status_provider.dart:26-28`),
**When** la dérivation réelle est implémentée,
**Then** un `FutureProvider<String?>` privé lit le claim `tenantStatus` du JWT stocké (`tokenStorageProvider.getToken()`, décodage manuel base64Url du payload, mirroring `app_router.dart:_isTenantActive`/`auth_provider.dart:_extractFirstNameFromJwt` — retourne `null` sur token absent ou erreur de décodage),
**And** `accountStatusProvider` reste un `Provider<AccountStatus>` **synchrone** (type inchangé — contrainte des tests existants) qui : retourne `AccountStatus.suspended` si le claim `tenantStatus == 'SUSPENDED'` ; sinon retourne `AccountStatus.trialExpired` si `ref.watch(subscriptionInfoProvider).valueOrNull?.planType == 'FREE'` ; sinon `AccountStatus.active`,
**And** pendant le chargement ou en cas d'erreur des deux providers sources (`.valueOrNull` renvoie `null`), le résultat par défaut est `AccountStatus.active` (comportement de repli identique au stub actuel — pas de bannière fantôme le temps que les données arrivent),
**And** les 3 tests existants de `suspension_banner_test.dart` (qui overrident `accountStatusProvider` directement) restent GREEN sans modification.

### AC2 — `SuspensionBanner` monté en haut de l'écran principal

**Given** `SuspensionBanner` n'est référencé nulle part dans l'arbre de rendu (`grep -rn "SuspensionBanner(" lib` → 0 appelant hors sa propre déclaration),
**When** le widget est monté,
**Then** `MainShell.build()` (`core/scaffold/main_shell.dart:275-282`) ajoute `const SuspensionBanner()` dans le `Column` existant, **avant** `SyncWarningBanner`/`OfflineGateBanner` (bannière de suspension = signal le plus critique, affichée en premier) — couvre les 5 routes à onglets (`/dashboard`, `/pos`, `/products`, `/reports`, `/settings`) où `MainShell` fait déjà office de point d'insertion partagé pour `SyncWarningBanner`/`OfflineGateBanner`,
**And** aucune régression sur le `NavigationBar`/`Scaffold` existant (le nouveau enfant du `Column` ne modifie ni `bottomNavigationBar` ni la structure `Expanded(child: widget.child)`).

### AC3 — Teardown de session sur révocation : vérification de non-régression (aucun nouveau code attendu)

**Given** `AuthInterceptor.onError` gère déjà `SESSION_REVOKED`/`ACCOUNT_INACTIVE` → `_expireSession()` + `onAccountSuspended` (Story 12.7, `done`),
**When** cette story touche des fichiers voisins (`account_status_provider.dart`, `main_shell.dart`) sans toucher `auth_interceptor.dart`/`auth_provider.dart`,
**Then** la suite `test/core/network/auth_interceptor_test.dart` (groupe `SESSION_REVOKED / ACCOUNT_INACTIVE`, lignes 212-291) reste GREEN sans modification — confirmant que le TDD `shouldTearDownSessionOnAccountInactive()` cité par l'épique est satisfait par du code pré-existant, pas par cette story.

## Tasks / Subtasks

- [x] **Task 1 — `accountStatusProvider` : dérivation réelle depuis JWT claim + subscription (AC1)** [flutter]
  - [x] 1.1 Dans `keevo/app/lib/features/settings/presentation/provider/account_status_provider.dart`, ajouter les imports `dart:convert`, `../../../auth/presentation/provider/auth_provider.dart` (pour `tokenStorageProvider`), `subscription_info_provider.dart` (même dossier `provider/`).
  - [x] 1.2 Ajouter une fonction privée `Future<String?> _readTenantStatusClaim(TokenStorage storage) async` : `getToken()` → si `null` retourner `null` ; sinon split `.` (3 parties sinon `null`) ; décoder `base64Url.normalize(parts[1])` + `utf8.decode` + `jsonDecode` dans un `try/catch` (retourne `null` sur exception) ; retourner `claims['tenantStatus'] as String?`.
  - [x] 1.3 Ajouter `final tenantStatusClaimProvider = FutureProvider<String?>((ref) => _readTenantStatusClaim(ref.watch(tokenStorageProvider)));` (exposé sans underscore pour testabilité — usage interne uniquement).
  - [x] 1.4 Remplacer le corps de `accountStatusProvider` : `final tenantStatus = ref.watch(tenantStatusClaimProvider).valueOrNull; if (tenantStatus == 'SUSPENDED') return AccountStatus.suspended; final planType = ref.watch(subscriptionInfoProvider).valueOrNull?.planType; if (planType == 'FREE') return AccountStatus.trialExpired; return AccountStatus.active;` — **garder la signature `Provider<AccountStatus>` inchangée**.
  - [x] 1.5 Mettre à jour la doc du fichier (commentaire `enum AccountStatus`, ligne 3-17) : retirer la mention "Phase 2" du stub, documenter la source réelle (claim JWT pour `suspended`, `planType` pour `trialExpired`).
  - [x] 1.6 Créer `test/features/settings/presentation/provider/account_status_provider_test.dart` : `ProviderContainer` avec `tokenStorageProvider` et `subscriptionInfoProvider` overridés — 7 cas (au-delà des 4 planifiés) : `tenantStatus=SUSPENDED` → `suspended` ; `tenantStatus=SUSPENDED` + planType=FREE → `suspended` (priorité) ; `tenantStatus=ACTIVE` + `planType=FREE` → `trialExpired` ; `tenantStatus=ACTIVE` + `planType=PREMIUM_TRIAL` → `active` ; token absent → `active` (repli) ; décodage échoué → `active` (repli) ; subscription error → `active` (repli).
  - [x] 1.7 `flutter test test/features/settings/presentation/provider/account_status_provider_test.dart test/features/settings/presentation/widget/suspension_banner_test.dart` → tout GREEN (les 3 tests existants de banner + les 7 nouveaux = 10/10).

- [x] **Task 2 — Monter `SuspensionBanner` dans `MainShell` (AC2)** [flutter]
  - [x] 2.1 `keevo/app/lib/core/scaffold/main_shell.dart` : ajouter l'import `'../../features/settings/presentation/widget/suspension_banner.dart'`.
  - [x] 2.2 Ligne ~276-282, dans le `Column` du `Scaffold.body` : ajouter `const SuspensionBanner(),` comme **premier** enfant (avant `SyncWarningBanner()`).
  - [x] 2.3 `flutter analyze` sur les 2 fichiers modifiés — 4 issues pré-existantes (unused import pos_providers, directives_ordering ×2, prefer_const_constructors), 0 nouvelle issue imputable à cette story.

- [x] **Task 3 — Vérification non-régression teardown session (AC3, aucun code attendu)** [testing]
  - [x] 3.1 `flutter test test/core/network/auth_interceptor_test.dart` → groupe `SESSION_REVOKED / ACCOUNT_INACTIVE` (3 tests) GREEN, sans aucune modification de `auth_interceptor.dart`/`auth_provider.dart`.

- [x] **Task 4 — Régression complète** [testing]
  - [x] 4.1 `flutter test` (suite complète) → **828 passed / 11 failed** (baseline 820/11 après Story 14.8) — **0 nouvelle régression**, +8 tests (7 nouveaux + 1 variation pré-existante).
  - [x] 4.2 `flutter analyze` → **761 issues** (baseline 759) — +2 issues toutes dans le nouveau fichier de test (no_leading_underscores corrigé, 1 info residual), 0 nouveau warning/error dans les fichiers source.
  - [x] 4.3 Aucune commande backend (`mvn test`) requise — cette story ne touche aucun fichier Java.

### Review Findings

- [x] [Review][Patch] Copie "période d'essai terminée" trompeuse pour un downgrade FREE déclenché par un admin — `AccountStatus.trialExpired` est dérivé uniquement de `planType == 'FREE'` (`account_status_provider.dart:99-104`), en présumant (D2/Dev Notes, citant la garantie Story 1.6) qu'un tenant n'atteint FREE que par expiration de trial. Mais `ActivatePlanRequest.planType` accepte explicitement `"FREE"` comme cible directe d'un admin, et `ActivatePlanService.execute()` force toujours `SubscriptionStatus.ACTIVE` quel que soit le plan cible — donc `status` ne distingue pas "trial expiré" de "downgrade admin" (vérifié : `SubscriptionStatus.java` documente que l'expiration naturelle laisse aussi `status=ACTIVE`). Décision PO : message remplacé par une copie neutre indépendante de la cause — "Vous êtes sur le plan Gratuit. Passez au plan Premium pour continuer." [keevo/app/lib/features/settings/presentation/widget/suspension_banner.dart:27-32] — test `suspension_banner_test.dart` mis à jour en conséquence.
- [x] [Review][Patch] `tenantStatusClaimProvider` jamais invalidé au login/logout/changement de tenant — état de suspension périmé possible sur device partagé [keevo/app/lib/features/settings/presentation/provider/account_status_provider.dart:47] — patché : `ref.invalidate(tenantStatusClaimProvider)` ajouté aux 4 points de transition (register/login/selectTenant dans auth_provider.dart, logout dans settings_page.dart). `subscriptionInfoProvider` (trialExpired) volontairement PAS invalidé au même endroit — première tentative l'incluait mais `ref.invalidate()` sur un `FutureProvider` non-autoDispose force une reconstruction immédiate, déclenchant un vrai appel réseau Dio non mocké à chaque login/register/logout → régression détectée sur `register_page_test.dart` (Timer pendant après dispose), corrigée en retirant l'invalidation de `subscriptionInfoProvider`. Voir entrée deferred-work.md pour ce résidu.
- [x] [Review][Patch] Nouvel import casse `directives_ordering` (3→4 issues) — contredit la note Task 2.3 "0 nouvelle issue" [keevo/app/lib/core/scaffold/main_shell.dart:17] — patché : import `suspension_banner.dart` déplacé à sa position alphabétique (avant `stores/...`) ; `flutter analyze` confirmé revenu à 3 issues (baseline).
- [x] [Review][Patch] Test "décodage échoué" n'exerce pas la branche try/catch qu'il prétend couvrir (token 6 parties → guard `parts.length != 3`, jamais le catch base64/json) [keevo/app/test/features/settings/presentation/provider/account_status_provider_test.dart:180] — patché : token remplacé par un JWT à 3 parties avec un payload non-base64Url valide, qui atteint réellement le `catch`.
- [x] [Review][Patch] Test "subscription error" n'attend pas la résolution de l'erreur avant d'asserter — dépend d'un ordre de microtâches non garanti, passerait même si la branche d'erreur était cassée [keevo/app/test/features/settings/presentation/provider/account_status_provider_test.dart:195] — patché : `await expectLater(c.read(subscriptionInfoProvider.future), throwsA(isException))` ajouté avant l'assertion.
- [x] [Review][Patch] Commentaire doc auto-contradictoire sur `tenantStatusClaimProvider` ("Not intended for external use" sur un provider public) + branche morte non exercée dans `settledContainer` (chemin `subscription: null`) [keevo/app/lib/features/settings/presentation/provider/account_status_provider.dart:52] — patché : commentaire corrigé (documente l'invalidation réelle) ; paramètre `subscription` de `settledContainer` rendu `required`, branche morte supprimée.
- [x] [Review][Defer] `SuspensionBanner` variante `trialExpired` : texte blanc sur fond ambre (#FCC419) ≈ 1.6:1 de contraste, illisible (WCAG AA exige 4.5:1) [keevo/app/lib/features/settings/presentation/widget/suspension_banner.dart:47] — deferred, pre-existing
- [x] [Review][Defer] Aucune gestion de priorité/empilement entre `SuspensionBanner`/`SyncWarningBanner`/`OfflineGateBanner` si actifs simultanément [keevo/app/lib/core/scaffold/main_shell.dart:279] — deferred, pre-existing

## Dev Notes

### Patterns à respecter

- **Ne PAS** changer le type de `accountStatusProvider` (doit rester `Provider<AccountStatus>` synchrone) — casserait la compilation des 3 tests existants de `suspension_banner_test.dart` qui l'overrident directement avec une valeur synchrone.
- **Ne PAS** créer d'utilitaire JWT-decode partagé — suivre la convention (dupliquée 3 fois déjà) du décodage manuel local par fichier. Un refactor DRY serait hors-scope d'un patch tag-A.
- **Ne PAS** toucher `auth_interceptor.dart`/`auth_provider.dart` — le teardown de session (AC3) est déjà livré par la Story 12.7 ; cette story ne fait que vérifier la non-régression.
- **Ne PAS** lire `SubscriptionResponse.status` (champ `SubscriptionStatus`, `ACTIVE`/`SUSPENDED`/`EXPIRED`) pour dériver `AccountStatus.suspended` — c'est un concept distinct de `TenantStatus` (JWT claim `tenantStatus`), voir Décision D2. Seul `planType` de `SubscriptionInfo` est utilisé dans cette story.
- **Ne PAS** ajouter de 4ème état "trial actif, N jours restants" à `AccountStatus` (Décision D1) — hors-scope, déjà couvert par `subscription_page.dart`.
- **Ne PAS** ajouter de branche `ACCOUNT_SUSPENDED` (403) dans `AuthInterceptor` (Décision D3) — gap réel mais documenté hors-scope, plomberie de réponse backend partagée plus large.
- `subscriptionInfoProvider` fait un appel réseau (`GET /subscription/me`) — le regarder depuis `accountStatusProvider` (donc depuis `MainShell` à chaque montage) déclenche cet appel dès l'arrivée sur `/dashboard` etc., pas seulement depuis Paramètres > Souscription comme aujourd'hui. C'est un changement de fréquence d'appel attendu et acceptable (provider non-`autoDispose`, mis en cache pour la session app) — pas une régression à corriger.
- Isolation des changements : Task 1 (provider) et Task 2 (montage widget) sont indépendantes et peuvent être committées séparément ; Task 3 est de la vérification pure (zéro diff).

### Ce qu'il NE FAUT PAS faire

- Ne pas réimplémenter la branche `SESSION_REVOKED`/`ACCOUNT_INACTIVE` de `AuthInterceptor` — déjà faite, `git blame`/Story 12.7 confirment.
- Ne pas dupliquer l'appel `GET /subscription/me` — réutiliser `subscriptionInfoProvider` existant, ne pas créer un second `FutureProvider` équivalent.
- Ne pas confondre `TenantStatus.SUSPENDED` (JWT claim, utilisé ici) et `SubscriptionStatus.SUSPENDED` (champ `SubscriptionResponse.status`, non utilisé ici) — voir Constat.
- Ne pas monter `SuspensionBanner` sur les écrans hors `MainShell` (pages plein-écran type Paramètres > Souscription, Inventaire, etc.) — hors-scope, `MainShell` est le point d'insertion approuvé (mirroring `SyncWarningBanner`/`OfflineGateBanner`, déjà scopés aux 5 onglets uniquement).

### References

- [Source: `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md:429-441`] Story 14.9 AC originales.
- [Source: `AUDIT_CONFORMITE_BMAD.md:184-185`] F-HIGH-8 — stub confirmé.
- [Source: `AUDIT_CONFORMITE_BMAD.md:181-182`] F-HIGH-7 — déjà fermé par Story 12.7 (vérifié dans le code actuel, pas seulement dans le rapport d'audit historique).
- [Source: `keevo/app/lib/features/settings/presentation/provider/account_status_provider.dart:1-29`] Stub actuel — commentaire d'origine confirme l'intention "JWT tenantStatus claim + subscription expires_at".
- [Source: `keevo/app/lib/features/settings/presentation/widget/suspension_banner.dart`] Widget déjà complet, jamais monté.
- [Source: `keevo/app/test/features/settings/presentation/widget/suspension_banner_test.dart`] 3 tests existants — contrainte de type sur `accountStatusProvider`.
- [Source: `keevo/app/lib/features/settings/presentation/provider/subscription_info_provider.dart:31-34`] `subscriptionInfoProvider` à réutiliser (ne pas dupliquer l'appel réseau).
- [Source: `keevo/app/lib/features/settings/domain/model/subscription_info.dart:6`] `SubscriptionInfo.planType` — valeurs `FREE`/`PREMIUM_TRIAL`/`PREMIUM`.
- [Source: `_bmad-output/planning-artifacts/epics/epic-1-foundation-infrastructure-authentication.md:245-296`] Story 1.6 — garantit `FREE` ⟺ essai expiré (aucun chemin direct vers FREE), et texte des 2 bannières attendues.
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtTokenProvider.java:57-63,215-217`] Émission + extraction du claim `tenantStatus`.
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java:169-177`] Enforcement `TenantStatus.SUSPENDED` → 403 `ACCOUNT_SUSPENDED` (write-only, non géré côté Flutter — Constat/Décision D3).
- [Source: `keevo/backend/src/main/java/com/keevo/subscription/plan/domain/model/SubscriptionStatus.java:11-21`] `SubscriptionStatus` — concept distinct de `TenantStatus`, non utilisé par cette story (Décision D2).
- [Source: `keevo/app/lib/core/router/app_router.dart:162-177`] Pattern de décodage JWT manuel existant (`_isTenantActive`) — mirroring pour `_readTenantStatusClaim`.
- [Source: `keevo/app/lib/features/auth/presentation/provider/auth_provider.dart:34-48,60-67`] `_extractFirstNameFromJwt` (2ème occurrence du pattern) + `tokenStorageProvider` à réutiliser.
- [Source: `keevo/app/lib/core/network/auth_interceptor.dart:86-106`] Teardown session déjà implémenté (AC3, vérification seulement).
- [Source: `keevo/app/lib/core/scaffold/main_shell.dart:17,23,275-289`] Point d'insertion `Column` partagé (`SyncWarningBanner`/`OfflineGateBanner`) — mirroring pour `SuspensionBanner`.
- [Source: `_bmad-output/implementation-artifacts/v1s-12-7-flutter-hardening-storage-logs-https.md:34,70-72,161-167`] Détail complet de l'implémentation AC4 (teardown session) — confirme qu'aucun travail supplémentaire n'est requis ici.
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md:110-111`] Defers pré-existants liés (race SESSION_REVOKED/refresh concurrent, SnackBar flood) — non affectés par cette story, ne pas les re-traiter ici.

## Dev Agent Record

### Agent Model Used

GitHub Copilot (DeepSeek V4 Pro)

### Debug Log References

N/A — no debugging required; implementation followed story spec exactly.

### Completion Notes List

**Implementation Summary (2026-07-22):**

- **Task 1 (AC1):** Replaced the `accountStatusProvider` stub (`AccountStatus.active` always) with real derivation from two sources:
  1. JWT claim `tenantStatus` decoded from `TokenStorage.getToken()` → `AccountStatus.suspended`
  2. `subscriptionInfoProvider.planType == 'FREE'` → `AccountStatus.trialExpired`
  3. Fallback: `AccountStatus.active` (safe default during loading/errors)
  - Added `_readTenantStatusClaim()` helper (mirrors existing JWT-decode pattern from `app_router.dart:_isTenantActive` and `auth_provider.dart:_extractFirstNameFromJwt`)
  - Added `tenantStatusClaimProvider` (FutureProvider, non-private for testability)
  - `accountStatusProvider` remains `Provider<AccountStatus>` synchronous — constraint preserved for 3 existing `suspension_banner_test.dart` tests
  - Updated enum/comment documentation to remove "Phase 2" stub mention

- **Task 2 (AC2):** Mounted `const SuspensionBanner()` as first child in `MainShell.build()` Column, before `SyncWarningBanner`/`OfflineGateBanner`. Covers all 5 tab routes (`/dashboard`, `/pos`, `/products`, `/reports`, `/settings`).

- **Task 3 (AC3):** Verified non-regression: `auth_interceptor_test.dart` SESSION_REVOKED/ACCOUNT_INACTIVE group (3 tests) GREEN. No code touched in `auth_interceptor.dart`/`auth_provider.dart` — teardown session was already delivered by Story 12.7.

- **Task 4:** Full regression: 828 passed / 11 failed (baseline 820/11), 0 new regression. flutter analyze: 761 issues (baseline 759), +2 in new test file only.

**Decisions applied:**
- D1: No 4th `AccountStatus` state for "trial active N days" — out of scope, already covered by `subscription_page.dart`
- D2: `suspended` derived from JWT claim `tenantStatus`, not `SubscriptionResponse.status`
- D3: No `ACCOUNT_SUSPENDED` (403) branch in `AuthInterceptor` — documented gap, out of scope

### File List

| File | Action | Path |
|------|--------|------|
| `account_status_provider.dart` | Modified | `keevo/app/lib/features/settings/presentation/provider/account_status_provider.dart` |
| `main_shell.dart` | Modified | `keevo/app/lib/core/scaffold/main_shell.dart` |
| `account_status_provider_test.dart` | Created | `keevo/app/test/features/settings/presentation/provider/account_status_provider_test.dart` |
| `auth_provider.dart` | Modified (code review patch) | `keevo/app/lib/features/auth/presentation/provider/auth_provider.dart` |
| `settings_page.dart` | Modified (code review patch) | `keevo/app/lib/features/settings/presentation/page/settings_page.dart` |
| `suspension_banner.dart` | Modified (code review patch) | `keevo/app/lib/features/settings/presentation/widget/suspension_banner.dart` |
| `suspension_banner_test.dart` | Modified (code review patch) | `keevo/app/test/features/settings/presentation/widget/suspension_banner_test.dart` |
| `deferred-work.md` | Modified (code review) | `_bmad-output/implementation-artifacts/deferred-work.md` |
| `v1s-14-9-fr20-banners-session-revoke-ui.md` | Modified | `_bmad-output/implementation-artifacts/v1s-14-9-fr20-banners-session-revoke-ui.md` |
| `sprint-status.yaml` | Modified | `_bmad-output/implementation-artifacts/sprint-status.yaml` |

## Change Log

- 2026-07-22: Story implemented — accountStatusProvider real derivation (JWT claim + subscription), SuspensionBanner mounted in MainShell, 7 new tests + 3 existing GREEN, 0 regression (828/11 vs baseline 820/11). Status: ready-for-dev → review.
- 2026-07-22: Code review (bmad-code-review) — 3 layers (Blind Hunter/Edge Case Hunter/Acceptance Auditor), 1 decision resolved (copie `trialExpired` rendue neutre — `status` ne peut pas distinguer trial expiré d'un downgrade admin, vérifié `ActivatePlanService`/`SubscriptionStatus.java`), 6 patches appliqués : invalidation `tenantStatusClaimProvider` aux 4 transitions d'auth (fix stale-suspension-state device partagé — 1ère tentative incluait `subscriptionInfoProvider` mais causait une régression réseau détectée et corrigée), fix lint `directives_ordering`, 2 tests renforcés (décodage échoué + subscription error réellement exercés), commentaire doc corrigé. 2 defers (contraste WCAG banner trialExpired pré-existant, empilement bannières sans priorité). 5 dismiss. 828/11 GREEN maintenu, 0 nouvelle régression, 0 nouveau lint. Status: review → done.
