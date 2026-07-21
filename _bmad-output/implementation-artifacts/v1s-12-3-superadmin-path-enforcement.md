---
baseline_commit: a4c27759dc9c56faa8b6634391f27d0c84291459
---
# Story 12.3: Enforcement SUPER_ADMIN au niveau chemin — `SecurityConfig` `/api/v1/admin/**` `hasRole("SUPER_ADMIN")`

Status: done

<!-- V1-stabilization track — tag A (patch V1 urgent, release-blocker).
     Branche : v1-stabilization (off `deploy` d766035). Refonte réabsorbe le fix (cherry-pick trivial, même SecurityConfig).
     Validation optionnelle : lancer `validate-create-story` avant `dev-story`. -->

## Story

**As a** Toor (opérateur de la plateforme),
**I want** `/api/v1/admin/**` to require `SUPER_ADMIN` at the filter-chain level (not only per-method),
**so that** tout endpoint admin soit protégé par défaut au niveau de la chaîne de filtres Spring Security, même si un futur contrôleur oublie le garde `requireSuperAdmin()` par-méthode (défense-en-profondeur, S3, NFR-sécurité, OWASP A01/A05).

## Contexte V1-stabilization

- **Source backlog :** `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md` → Story 12.3 (Refs S3, Epic 9 Story 9.1, defense-in-depth).
- **Audit preuve `file:line` :** `AUDIT_CONFORMITE_BMAD.md:272` — « S3 | Haute | `SecurityConfig` pas de `hasRole("SUPER_ADMIN")` au niveau chemin sur `/api/v1/admin/**` | `SecurityConfig.java:91-96` | défense-en-profondeur ». Et `AUDIT_CONFORMITE_BMAD.md:14,332` — « les endpoints admin s'appuient sur un `requireSuperAdmin()` par-méthode **sans enforcement au niveau chemin dans `SecurityConfig`** » ; remédiation : « `SecurityConfig` : ajouter `.requestMatchers("/api/v1/admin/**").hasRole("SUPER_ADMIN")` — tue S3 ».
- **Index track :** `_bmad-output/implementation-artifacts/v1-stabilization-stories.md:17` — « `SecurityConfig` admin path `hasRole("SUPER_ADMIN")` · S3 » (sécurité backend, rapide, tag A).
- **Tracker :** `sprint-status.yaml` clé `v1s-12-3-superadmin-path-enforcement`.
- **Tag :** A — patch V1 maintenant. Refonte : cherry-pick trivial (même `SecurityConfig`).

## Acceptance Criteria

> Les AC BDD proviennent textuellement de `epics-remediation-audit.md` (Story 12.3). AC3/AC4/AC5 ajoutées pour fermer les coutures TDD/non-régression (sinon la story est un no-op test).

1. **AC1 (règle chemin) — Given** `SecurityConfig.java:87-92` configure la chaîne d'autorisation avec `PUBLIC_PATHS permitAll()` puis `.anyRequest().authenticated()`, **When** la chaîne est reconfigurée, **Then** `.requestMatchers("/api/v1/admin/**").hasRole("SUPER_ADMIN")` apparaît **avant** `.anyRequest().authenticated()` (l'ordre des matchers compte — Spring évalue en séquence).
2. **AC2 (garde par-méthode préservé) — And** les contrôleurs admin conservent leur garde method-level `requireSuperAdmin()` (défense-en-profondeur : filter-chain + method-level). **NE PAS supprimer** `requireSuperAdmin()` — l'AC l'exige explicitement.
3. **AC3 (TDD OWNER → 403 au niveau chemin) — And** un `@WebMvcTest` asserte qu'un token `ROLE_OWNER` sur `/api/v1/admin/tenants` → HTTP 403 **au niveau de la filter-chain** (pas via le `DomainException` du contrôleur). Le test prouve que c'est la règle `hasRole` qui bloque, indépendamment du garde par-méthode — voir Dev Notes « TDD subtil ».
4. **AC4 (TDD SUPER_ADMIN → 200) — And** un `@WebMvcTest` asserte qu'un token `ROLE_SUPER_ADMIN` sur `/api/v1/admin/tenants` → HTTP 200 (la règle `hasRole("SUPER_ADMIN")` laisse passer le SUPER_ADMIN).
5. **AC5 (non-régression) — And** les 4 contrôleurs admin existants + leurs tests `@WebMvcTest` (`AdminTenantControllerTest`, `AdminSubscriptionControllerTest`, `AdminCatalogControllerTest`, `AdminSyncMonitoringControllerTest`) restent GREEN ; aucun endpoint admin légitime n'est cassé.

> ⚠️ **Discrepancy « 5 contrôleurs » vs 4 réels :** l'audit/story source dit « the 5 admin controllers » mais seuls **4** contrôleurs existent aujourd'hui sous `/api/v1/admin/**` : `AdminCatalogController` (`/api/v1/admin/catalog`), `AdminSubscriptionController` (`/api/v1/admin/subscriptions`), `AdminTenantController` (`/api/v1/admin/tenants`), `AdminSyncMonitoringController` (`/api/v1/admin/sync`). Le 5e (`/api/v1/admin/revenue/...` SUPER_ADMIN, story 14.3 / dashboard revenus) n'existe pas encore — c'est exactement la valeur de la défense-en-profondeur : la règle chemin protégera automatiquement ce futur contrôleur. Ne pas chercher un 5e contrôleur inexistant.

## Tasks / Subtasks

- [x] **Task 1 — Ajouter la règle chemin dans `SecurityConfig` (AC1)**
  - [x] 1.1 Dans `securityFilterChain`, bloc `authorizeHttpRequests` (lignes 87-92), insérer **après** `.requestMatchers(PUBLIC_PATHS).permitAll()` et **avant** `.anyRequest().authenticated()` :
        `.requestMatchers("/api/v1/admin/**").hasRole("SUPER_ADMIN")`
  - [x] 1.2 Ne rien changer d'autre (CSRF, CORS, sessions, formLogin, httpBasic, exceptionHandling, JwtAuthFilter wiring — tous préservés).
  - [x] 1.3 Ajouter un commentaire inline court documentant *pourquoi* la règle est au niveau chemin (défense-en-profondeur S3) + que les contrôleurs gardent aussi `requireSuperAdmin()` (cf. AC2), pour qu'un futur dév ne retire pas l'un en pensant l'autre suffisant.
- [x] **Task 2 — Préserver les gardes par-méthode (AC2)**
  - [x] 2.1 Vérifier qu'aucun `requireSuperAdmin()` n'est retiré dans les 4 contrôleurs admin (`AdminCatalogController`, `AdminSubscriptionController`, `AdminTenantController`, `AdminSyncMonitoringController`). Aucune modification attendue — lecture seule.
- [x] **Task 3 — TDD prouvant l'enforcement au niveau chemin (AC3/AC4)**
  - [x] 3.1 Vérifier que les tests existants `AdminTenantControllerTest.listTenants_withOwnerRole_returns403` / `listTenants_withSuperAdminRole_returns200` restent GREEN (ils couvrent déjà `/api/v1/admin/tenants` OWNER→403 + SUPER_ADMIN→200 — voir Dev Notes « TDD subtil »).
  - [x] 3.2 **(Recommandé, vrai RED→GREEN)** Ajouter un `SecurityConfigAdminPathEnforcementTest` dédié qui prouve que la filter-chain bloque OWNER **indépendamment** du garde par-méthode : `@WebMvcTest` + `@Import(SecurityConfig.class)` + un stub `@RestController` test-only mappé sur `/api/v1/admin/__test_only` **sans** `requireSuperAdmin()` (retourne juste 200). OWNER → 403, SUPER_ADMIN → 200. Ce test est **RED sans la règle** (stub sans garde + pas de règle chemin → OWNER aurait 200) et **GREEN avec la règle**. Voir Dev Notes pour le squelette.
  - [x] 3.3 (Alternative minimale si 3.2 jugé trop lourd) : commenter temporairement `requireSuperAdmin()` dans `AdminTenantController.listTenants()` et confirmer que `listTenants_withOwnerRole_returns403` reste GREEN (prouve que la filter-chain bloque seule) → **restaurer** `requireSuperAdmin()` (AC2). Documenter la vérification dans Debug Log.
- [x] **Task 4 — Non-régression (AC5)**
  - [x] 4.1 Lancer les 4 tests contrôleurs admin : `mvn test -Dtest='AdminTenantControllerTest,AdminSubscriptionControllerTest,AdminCatalogControllerTest,AdminSyncMonitoringControllerTest'` — tous GREEN.
  - [x] 4.2 Lancer la suite security/applicative pertinente : `mvn test -Dtest='*Security*,*Auth*,JwtTokenProvider*'` (si présente) — pas de régression.
- [x] **Task 5 — Dev Agent Record**
  - [x] 5.1 Renseigner Agent Model, Debug Log, Completion Notes, File List.

## Dev Notes

### 🔬 TDD subtil — les tests AC3/AC4 EXISTENT DÉJÀ (et passent avant ET après)

`AdminTenantControllerTest` (`keevo/backend/src/test/java/com/keevo/admin/tenant/adapter/in/rest/AdminTenantControllerTest.java`) couvre **déjà** les assertions de l'AC :

- `listTenants_withOwnerRole_returns403` → `ROLE_OWNER` sur `GET /api/v1/admin/tenants` → 403 (`@WebMvcTest @Import(SecurityConfig.class)`, mocke `JwtAuthFilter` via `setupFilterAs("ROLE_OWNER")`).
- `listTenants_withSuperAdminRole_returns200` → `ROLE_SUPER_ADMIN` → 200.

**Subtilité critique à comprendre AVANT de coder :** actuellement (avant la story), le OWNER→403 vient du **garde par-méthode** `requireSuperAdmin()` qui lève `DomainException(ErrorCode.FORBIDDEN)` → `GlobalExceptionHandler` mappe à 403 (`GlobalExceptionHandler.java:148-152,221`). Après la story, le 403 vient de la **filter-chain** `hasRole("SUPER_ADMIN")` → `AccessDeniedException` → `accessDeniedHandler` (`SecurityConfig.java:83-84` `res.sendError(403)`). **Les deux produisent 403**, donc le test existant passe avant ET après — il ne distingue pas l'origine du 403. C'est pourquoi AC3 demande un test qui prouve l'enforcement au niveau chemin **indépendamment** du garde par-méthode (Task 3.2). Ne pas se contenter de dire « le test passe déjà » — la valeur de la story est la défense-en-profondeur, il faut un test qui l'atteste.

### Convention d'autorité (ROLE_ prefix) — NE PAS SE TROMPER

`JwtAuthFilter.java:179-181` peuple le `SecurityContext` avec :
```java
new UsernamePasswordAuthenticationToken(userId, null,
    List.of(new SimpleGrantedAuthority("ROLE_" + role)));   // role = "SUPER_ADMIN" → "ROLE_SUPER_ADMIN"
```
Spring Security `hasRole("SUPER_ADMIN")` **préfixe automatiquement avec `ROLE_`** et vérifie `ROLE_SUPER_ADMIN`. → Match. ✓

- **Ne pas** écrire `hasRole("ROLE_SUPER_ADMIN")` (double préfixe → ne matchera jamais → tous les admin endpoints cassés). Toujours `hasRole("SUPER_ADMIN")`.
- Le role claim JWT est `"SUPER_ADMIN"` (cf. `AdminAccountInitializer.java:115,136`, `UserTenantMembership.java:40`). Cohérent avec les 4 `requireSuperAdmin()` existants qui vérifient `.equals("ROLE_SUPER_ADMIN")`.

### Fichier à toucher (UPDATE, pas NEW)

| Fichier | Ligne | État actuel | Changement story | À préserver |
|---|---|---|---|---|
| `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/SecurityConfig.java` | 87-92 | `authorizeHttpRequests` : `dispatcherTypeMatchers permitAll()` + `PUBLIC_PATHS permitAll()` + `anyRequest().authenticated()` | Ajouter `.requestMatchers("/api/v1/admin/**").hasRole("SUPER_ADMIN")` entre `PUBLIC_PATHS` et `anyRequest()` + commentaire S3 | CSRF disable, CORS, STATELESS, formLogin/httpBasic disable, `HttpStatusEntryPoint`(401), `accessDeniedHandler`(403), `addFilterBefore(jwtAuthFilter, ...)`, `PUBLIC_PATHS`, BCrypt(12) |
| (nouveau, si Task 3.2) `keevo/backend/src/test/java/com/keevo/shared/infrastructure/security/SecurityConfigAdminPathEnforcementTest.java` | — | — | TDD filter-chain (stub contrôleur test-only) | conventions `@WebMvcTest` du projet |

Aucun contrat API, aucun schéma DB, aucun workflow CI, aucun Dockerfile touché. Changement backend pur, 1 ligne + commentaire + (option) 1 test.

### Squelette suggéré — `SecurityConfigAdminPathEnforcementTest` (Task 3.2)

```java
@WebMvcTest(controllers = StubAdminController.class)
@Import(SecurityConfig.class)
@DisplayName("SecurityConfig — /api/v1/admin/** path-level enforcement")
class SecurityConfigAdminPathEnforcementTest {

    @Autowired MockMvc mockMvc;
    @MockBean JwtAuthFilter jwtAuthFilter;   // mocké : on contrôle le rôle via SecurityContext

    // Stub SANS requireSuperAdmin() — si la filter-chain ne bloque pas, OWNER aurait 200 (RED sans la règle).
    @RestController
    static class StubAdminController {
        @GetMapping("/api/v1/admin/__test_only")
        ResponseEntity<Void> stub() { return ResponseEntity.ok().build(); }
    }

    @Test
    @DisplayName("OWNER sur /api/v1/admin/** → 403 au niveau filter-chain (sans garde par-méthode)")
    void ownerBlockedAtPathLevel() throws Exception {
        setupFilterAs("ROLE_OWNER");
        mockMvc.perform(get("/api/v1/admin/__test_only")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SUPER_ADMIN sur /api/v1/admin/** → 200")
    void superAdminAllowed() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");
        mockMvc.perform(get("/api/v1/admin/__test_only")).andExpect(status().isOk());
    }

    private void setupFilterAs(String role) throws Exception {
        doAnswer(inv -> {
            var auth = new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority(role)));
            SecurityContextHolder.getContext().setAuthentication(auth);
            inv.getArgument(2, FilterChain.class).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }
}
```

> Le stub `@RestController` en classe interne statique n'a **pas** `requireSuperAdmin()` → un 200 pour OWNER ne peut venir QUE de l'absence de blocage. Avec la règle `hasRole`, OWNER → 403 (filter-chain). C'est le seul test qui prouve AC3 sans ambiguïté. Sur Spring Boot 3.x / Security 6.x, une classe `@RestController` interne à un `@WebMvcTest` est scannée si `controllers` la nomme explicitement (sinon l'annoter `@Component` ou la sortir). Vérifier que le `@WebMvcTest(controllers = StubAdminController.class)` la charge — sinon utiliser `@Import(StubAdminController.class)`.

### Pourquoi ne PAS supprimer `requireSuperAdmin()` (AC2)

Défense-en-profondeur (layered enforcement) :
1. **Filter-chain** (`SecurityConfig`) : bloque tout `/api/v1/admin/**` non-SUPER_ADMIN **avant** d'atteindre le contrôleur — protège aussi les futurs endpoints qui oublieraient le garde.
2. **Method-level** (`requireSuperAdmin()`) : seconde barrière si jamais la règle chemin est retirée par erreur ou contournée.

Les deux restent. Le commentaire inline (Task 1.3) doit le documenter pour éviter qu'un futur dév retire l'un en pensant l'autre redondant.

### Tests existants à ne pas casser

- `AdminTenantControllerTest` (4 tests, `@WebMvcTest @Import(SecurityConfig.class)`) — OWNER→403, SUPER_ADMIN→200, detail 200, notFound 404.
- `AdminSubscriptionControllerTest`, `AdminCatalogControllerTest`, `AdminSyncMonitoringControllerTest` — mêmes patterns (`@MockBean JwtAuthFilter` + `setupFilterAs`). Tous doivent rester GREEN.
- Aucun `SecurityConfigTest` dédié n'existe aujourd'hui (Task 3.2 en crée un).

## Architecture Compliance

- **S3 / défense-en-profondeur** : cette story ferme la couture « enforcement only per-method ». C'est la remédiation exacte pointée par l'audit (`AUDIT_CONFORMITE_BMAD.md:272,332`).
- **NFR — RBAC (architecture.md:91,249,1125)** : « Super Admin endpoints: `/api/v1/admin/` (separate RBAC) ». La règle chemin réalise ce contrat au niveau infrastructure, pas seulement application.
- **OWASP A01 (Broken Access Control) / A05 (Security Misconfiguration)** : least-privilege — un endpoint admin oubliant le garde par-méthode n'expose plus la plateforme.
- **Pas de nouveau contrat API/DB**, pas de workflow CI, pas de changement deploy. Backend pur, régression-born.

## Library / Framework Requirements

- **Spring Security 6.x** (`spring-boot-starter-security`, déjà en place) — `authorizeHttpRequests` DSL, `hasRole`, `AccessDeniedHandler`. Aucune nouvelle dépendance.
- Aucune version à changer. Aucune migration. JDK 21 (déjà utilisé).

## File Structure Requirements

- Config sécurité : `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/SecurityConfig.java` (UPDATE, 1 ligne + commentaire).
- Tests : `keevo/backend/src/test/java/com/keevo/shared/infrastructure/security/` (NEW `SecurityConfigAdminPathEnforcementTest` si Task 3.2) + `keevo/backend/src/test/java/com/keevo/admin/*/adapter/in/rest/*ControllerTest.java` (lecture/vérification GREEN).
- Tracker : `_bmad-output/implementation-artifacts/sprint-status.yaml` (statut → ready-for-dev).

## Testing Requirements

- **TDD (AC3/AC4, obligatoire)** : un `@WebMvcTest` asserte OWNER→403 + SUPER_ADMIN→200 sur `/api/v1/admin/**`. Soit via le test dédié `SecurityConfigAdminPathEnforcementTest` (Task 3.2, recommandé — prouve le filter-chain), soit via les tests existants `AdminTenantControllerTest` (Task 3.1) **+** vérification 3.3 que le 403 tient sans le garde par-méthode.
- **Non-régression (AC5)** : `mvn test -Dtest='AdminTenantControllerTest,AdminSubscriptionControllerTest,AdminCatalogControllerTest,AdminSyncMonitoringControllerTest'` GREEN.
- Pas d'environnement runtime/deploy requis (changement de config sécurité, testé en `@WebMvcTest`).

## Previous Story Intelligence

- **Story 12.1 (`v1s-12-1`, done)** : patterns V1-stab — branche `v1-stabilization`, tag A, cherry-pickable sur la refonte. A introduit le **pinning UID/GID 1001** (`keevo` dans le Dockerfile) — hors scope ici mais rappelle que les stories V1-stab restent chirurgicales (1 fichier, défense-en-profondeur). Le grep lint `grep-lint-story-12-1.sh` et le wiring CI (`backend-ci.yml`) sont le modèle si un test automatisé doit être wire en CI — **non requis ici** (pas de regression grep à surveiller, le test est Java `@WebMvcTest`).
- **Story 10-3 (`ProdSecretsValidator`)** : a posé les fail-fast boot. Rappel du précepte « réutiliser/étendre l'existant plutôt que réinventer » — ici, **réutiliser** `SecurityConfig` + `accessDeniedHandler` existants (403 JSON), ne pas ajouter un nouveau filtre/handler.
- **Story 9.1 (Epic 9, Super Admin dashboard)** : a posé les contrôleurs admin + `requireSuperAdmin()`. Cette story ajoute la couche filter-chain au-dessus — cohérent avec l'architecture admin (architecture.md:1125 « separate RBAC »).

## Git Intelligence

- **Branche courante `v1-stabilization`** (off `deploy` d766035) — V1 reste déployable pendant la refonte.
- `SecurityConfig.java` n'a pas été modifié récemment (commit structure V1). La section `authorizeHttpRequests` (lignes 87-92) est l'état V1 original — insert chirurgical.
- Aucun commit n'a touché au RBAC admin path-level : S3 est un gap de conception d'origine (audit post-mortem), pas une régression récente.

## Latest Technical Information

- **Spring Security 6.x `hasRole`** : préfixe automatiquement `ROLE_`. `hasRole("SUPER_ADMIN")` vérifie l'autorité `ROLE_SUPER_ADMIN`. Confirme la convention `JwtAuthFilter` (préfixe `ROLE_` à la ligne 181). Ne pas doubler le préfixe.
- **Ordre des matchers** : `authorizeHttpRequests` évalue les `requestMatchers` en séquence ; le **premier** match gagne. `/api/v1/admin/**` doit précéder `.anyRequest()` (sinon `anyRequest().authenticated()` l'emporte et un OWNER authentifié passe). Insérer entre `PUBLIC_PATHS permitAll()` et `anyRequest().authenticated()`.
- **`@WebMvcTest` + `@Import(SecurityConfig.class)`** : charge la filter-chain Spring Security en contexte de test (les tests admin existants le font déjà). Le `JwtAuthFilter` est `@MockBean`-mocké pour contrôler le rôle via `SecurityContextHolder` — pattern à réutiliser pour le test dédié.
- Pas de breaking change dépendances. Aucune migration.

## Project Context Reference

- [Source: `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md`#Story-12.3] — AC BDD, refs S3/Epic 9 Story 9.1/defense-in-depth.
- [Source: `AUDIT_CONFORMITE_BMAD.md:14,272,332`] — preuve file:line S3 (Haute, défense-en-profondeur) + remédiation exacte.
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/SecurityConfig.java:87-92`] — état actuel (bloc `authorizeHttpRequests` sans règle admin).
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java:179-181`] — convention autorité `ROLE_` + rôle JWT `SUPER_ADMIN`.
- [Source: `keevo/backend/src/test/java/com/keevo/admin/tenant/adapter/in/rest/AdminTenantControllerTest.java:52-77,119-128`] — tests AC3/AC4 existants (OWNER→403, SUPER_ADMIN→200) + helper `setupFilterAs`.
- [Source: `keevo/backend/src/main/java/com/keevo/admin/{tenant,catalog,sync_monitoring}/adapter/in/rest/*Controller.java`] — 4 contrôleurs admin + `requireSuperAdmin()` (à préserver, AC2).
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java:148-152,221`] — mapping `ErrorCode.FORBIDDEN` → 403 (origine actuelle du 403 OWNER par-méthode).
- [Source: `_bmad-output/implementation-artifacts/v1-stabilization-stories.md:17`] — index track (sécurité backend, rapide).

## Dev Agent Record

### Agent Model Used

Claude Code (deepseek-v4-pro) via bmad-dev-story workflow

### Debug Log References

- **SecurityConfigAdminPathEnforcementTest initial failure**: `superAdminAllowed` → 500 (NoResourceFoundException: No static resource api/v1/admin/__test_only). Root cause: inner static `@RestController` `StubAdminController` not auto-detected by `@WebMvcTest(controllers = ...)`. Fix: added `@Import(StubAdminController.class)` to the test class. After fix: 2/2 GREEN.
- **AuthControllerTest pre-existing failure**: 13 errors due to `NoSuchBeanDefinitionException: GetUserProfileUseCase` — confirmed pre-existing via `git stash` + test run before change. Not caused by this story.
- **Full suite regression**: Before change: 1432 tests, 2 failures, 40 errors. After change: 1432 tests, 1 failure, 40 errors. Zero regressions introduced; 1 pre-existing failure resolved (unrelated to this change).

### Completion Notes List

- ✅ **AC1 (règle chemin)**: Added `.requestMatchers("/api/v1/admin/**").hasRole("SUPER_ADMIN")` in `SecurityConfig.authorizeHttpRequests` between `PUBLIC_PATHS permitAll()` and `anyRequest().authenticated()`. Included inline comment documenting defense-in-depth rationale (S3) and that controllers retain `requireSuperAdmin()` (AC2).
- ✅ **AC2 (garde par-méthode préservé)**: Verified all 4 admin controllers (`AdminCatalogController`, `AdminSubscriptionController`, `AdminTenantController`, `AdminSyncMonitoringController`) retain their `requireSuperAdmin()` guards. No changes needed.
- ✅ **AC3 (TDD OWNER → 403)**: Created `SecurityConfigAdminPathEnforcementTest` with stub controller `StubAdminController` (NO `requireSuperAdmin()` guard) mapped to `/api/v1/admin/__test_only`. `ownerBlockedAtPathLevel()` test: OWNER → 403 — proves filter-chain blocks independently of per-method guard. GREEN.
- ✅ **AC4 (TDD SUPER_ADMIN → 200)**: `superAdminAllowed()` test: SUPER_ADMIN → 200 — filter-chain allows SUPER_ADMIN through. GREEN.
- ✅ **AC5 (non-régression)**: All 16 admin controller tests GREEN (AdminTenant: 4, AdminSubscription: 3, AdminCatalog: 4, AdminSyncMonitoring: 5). Security tests GREEN: JwtAuthFilterTest (11), JwtTokenProviderTest (17), SecurityConfigAdminPathEnforcementTest (2). Full suite: 0 regressions.
- ✅ **Task 3.1**: Existing `AdminTenantControllerTest` 4/4 GREEN (OWNER→403, SUPER_ADMIN→200, detail 200, notFound 404).
- ✅ **Task 3.3 (skipped)**: Not needed — Task 3.2 dedicated test provides definitive proof of filter-chain enforcement.

### File List

- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/SecurityConfig.java` — **MODIFIED**: Added `.requestMatchers("/api/v1/admin/**").hasRole("SUPER_ADMIN")` + inline comment (S3 defense-in-depth)
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/security/SecurityConfigAdminPathEnforcementTest.java` — **NEW**: 2 tests proving filter-chain enforcement (OWNER→403, SUPER_ADMIN→200) via stub controller without per-method guard
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — **MODIFIED**: v1s-12-3 status updated ready-for-dev → in-progress → review

### Review Findings

Code review `bmad-code-review` (2026-07-21) — 3 couches adversariales (Blind Hunter, Edge Case Hunter, Acceptance Auditor). AC1–AC5 toutes PASS. 0 `decision-needed`, 0 `patch`, 6 `defer` (tous low), 12 dismiss. Aucun HIGH/MEDIUM — la story passe à `done`.

- [x] [Review][Defer] Le garde par-méthode `requireSuperAdmin()` devient code mort pour le chemin de rejet (la filter-chain bloque tout non-SUPER_ADMIN avant) ; `AdminTenantControllerTest` OWNER→403 passe désormais via la filter-chain, n'exerçant plus le garde par-méthode — une régression supprimant `requireSuperAdmin()` ne serait plus attrapée [`SecurityConfig.java:96`, `AdminTenantControllerTest.java:70-77`] — deferred, by-design layered defense (AC2 = préservation vérifiée par inspection), à réévaluer lors de la refonte
- [x] [Review][Defer] Trous de couverture dans `SecurityConfigAdminPathEnforcementTest` : pas de cas EMPLOYEE, pas d'anonymous→401, pas de token authentifié-sans-rôle, seul GET exercé [`SecurityConfigAdminPathEnforcementTest.java:63-77`] — deferred, non requis par AC1-AC5 (règle role/method-agnostic), durcissement optionnel
- [x] [Review][Defer] `SecurityContextHolder` non nettoyé entre tests (`setupFilterAs` ne réplique pas `JwtAuthFilter:197 clearContext()`) ; fuite latente si un futur test oumet `setupFilterAs` [`SecurityConfigAdminPathEnforcementTest.java:79-87`] — deferred, convention codebase-wide (aucun test admin n'a `@AfterEach clearContext`), hygiene `@AfterEach` optionnelle
- [x] [Review][Defer] Magic string `"SUPER_ADMIN"` dupliquée (`SecurityConfig.java:96`, `JwtAuthFilter.java:181`, `AdminAccountInitializer`, corps `requireSuperAdmin()`) ; l'enum `Role` existe mais n'est pas référencé au niveau filtre — un rename ne remonterait pas en erreur compile [`SecurityConfig.java:96`] — deferred, pre-existing codebase-wide
- [x] [Review][Defer] `accessDeniedHandler` utilise `res.sendError(403)` (page d'erreur servlet, HTML pour navigateurs) au lieu d'un corps JSON, divergeant du javadoc « 403 JSON response » ; pré-existant, la règle admin augmente le trafic vers ce handler [`SecurityConfig.java:83-84`] — deferred, pre-existing behavior non introduit par cette story
- [x] [Review][Defer] `@MockBean` déprécié depuis Spring Boot 3.4+ (projet en 3.5.0) en faveur de `@MockitoBean` ; le nouveau test propage la déprécation [`SecurityConfigAdminPathEnforcementTest.java:46`] — deferred, convention codebase-wide (tests admin existants identiques)

**Dismissed (12) — bruit/faux positifs :** préfixe nu `/api/v1/admin` non matché (FAUX — PathPatternParser `/**` couvre le préfixe) ; collision `adminBAD` (segment littéral ≠, non matché) ; bypass FORWARD/ERROR permitAll (pattern standard pour pages d'erreur, aucun vecteur externe forward-vers-admin) ; chevauchement PUBLIC_PATHS (vérifié : aucun) ; filter mocké ne valide pas l'ordering (convention spec-endorsée) ; `@Import` redondant du stub (intentionnel per Debug Log) ; stub routable en prod (spéculatif — test sources non scannées en main) ; absence de preuve RED/GREEN (le test EST le GREEN, RED implicite confirmé par l'auditor) ; `superAdminAllowed→200` « optimiste » (javadoc clair, stub sans garde par design) ; commentaire cite AC2/3/4 non dans le diff (ACs dans la spec chargée) ; absence de RoleHierarchy (by-design, cohérent) ; « protected by default » trompeur (exact pour `/api/v1/admin/**` per architecture.md:1125).

