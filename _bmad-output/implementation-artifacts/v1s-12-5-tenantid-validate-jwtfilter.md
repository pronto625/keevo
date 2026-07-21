---
baseline_commit: b61fc27
---
# Story 12.5: Valider `tenantId` dans `JwtAuthFilter` avant l'interpolation SQL — `TenantSchema.validate(tenantId)`

Status: done

<!-- V1-stabilization track — tag A (patch V1 urgent, release-blocker).
     Branche : v1-stabilization (off `deploy` d766035). Refonte réabsorbe le fix (audit : « Refonte absorption: re-implement natively (validation port dans shared/infrastructure) » — la validation centralisée est l'objectif refonte ; V1 patche le point d'injection).
     Validation optionnelle : lancer `validate-create-story` avant `dev-story`. -->

## Story

**As a** Toor (opérateur de la plateforme),
**I want** le `tenantId` extrait du JWT validé par `TenantSchema.validate(tenantId)` (regex `^kv_[a-z0-9]{6}$`) **avant** toute interpolation SQL brute dans `JwtAuthFilter`,
**so that** un `tenantId` forgé/malformé dans un claim JWT ne puisse pas injecter du SQL via la requête de lookup EMPLOYEE (S5, B-HIGH-6, ARCH18, NFR10, OWASP A03) — défense-en-profondeur même si la signature RS256 rend le forgeage improbable sans la clé privée.

## Contexte V1-stabilization

- **Source backlog :** `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md` → Story 12.5 (Refs S5, B-HIGH-6, ARCH18, NFR10).
- **Audit preuve `file:line` :**
  - `AUDIT_CONFORMITE_BMAD.md:115-117` — « **B-HIGH-6** — `JwtAuthFilter` interpole un tenantId non validé dans du SQL brut (ARCH18/NFR10) · **Fichier :** `JwtAuthFilter.java:157` `"...\"" + tenantId + "\".employees WHERE user_id = ?..."` · **Attendu :** `TenantSchema.validate(tenantId)` avant interpolation (le pattern existe ailleurs). Mitigé par signature RS256 mais contourne la porte de la couche de routage. **Vérifié. Confiance : Haute.** »
  - `AUDIT_CONFORMITE_BMAD.md:14,274,284,333` — « `JwtAuthFilter` interpole un `tenantId` dérivé du JWT dans du SQL brut sans validation » (S5) ; `SchemaAwareMultiTenantConnectionProvider` applique une porte regex `^kv_[a-z0-9]{6}$` MAIS la requête directe `JdbcTemplate` dans `JwtAuthFilter:157` **contourne** cette porte ; remédiation : « `JwtAuthFilter:157` : `TenantSchema.validate(tenantId)` avant interpolation — tue S5. »
- **Index track :** `_bmad-output/implementation-artifacts/v1-stabilization-stories.md:18` — « `TenantSchema.validate(tenantId)` dans `JwtAuthFilter` · S5/B-HIGH-6 » (sécurité backend, rapide, tag A).
- **Tracker :** `sprint-status.yaml` clé `v1s-12-5-tenantid-validate-jwtfilter`.
- **Tag :** A — patch V1 maintenant. Refonte : cherry-pick trivial (la classe `TenantSchema` + l'appel filtre sont petits et portables).

## Acceptance Criteria

> Les AC BDD proviennent textuellement de `epics-remediation-audit.md` (Story 12.5). AC3/AC4/AC5 ajoutées pour fermer les coutures TDD/non-régression (sinon la story est un no-op test).

1. **AC1 (validation avant interpolation, schéma validé utilisé) — Given** `JwtAuthFilter.java:147-149` interpole le `tenantId` brut (extrait du claim JWT) dans la requête `JdbcTemplate` de lookup EMPLOYEE (`"SELECT store_id, status, password_change_required FROM \"" + tenantId + "\".employees WHERE user_id = ? LIMIT 1"`), **When** le `tenantId` est extrait du claim et que la branche EMPLOYEE est exécutée, **Then** `String schema = TenantSchema.validate(tenantId)` (regex `^kv_[a-z0-9]{6}$`) est appelée **avant** tout appel `JdbcTemplate`, **And** c'est le `schema` validé (pas le `tenantId` brut) qui est interpolé dans le SQL.
2. **AC2 (rejet 401 TOKEN_INVALID) — And** un `tenantId` qui ne matche pas `^kv_[a-z0-9]{6}$` (null, vide, format code public `KV-ABC123`, ou payload d'injection comme `kv_evil"; DROP--`) → réponse HTTP **401** `{"domainCode":"TOKEN_INVALID"}`, le `filterChain.doFilter` n'est **jamais** appelé, le `SecurityContext` n'est **jamais** peuplé pour ce token.
3. **AC3 (TDD rejet format invalide) — And** un test `shouldRejectTokenWithInvalidTenantIdFormat()` (RED avant, GREEN après) asserte qu'un token EMPLOYEE dont le claim `tenantId` = `kv_evil"; DROP TABLE--` (ou `KV-ABC123`) → 401 `TOKEN_INVALID`, `filterChain` jamais appelé, `jdbcTemplate` jamais appelé.
4. **AC4 (TDD accept format valide) — And** un test `shouldAcceptValidTenantId()` asserte qu'un token EMPLOYEE avec `tenantId` = `kv_abc123` (format valide) → le `filterChain` est appelé (200), le `jdbcTemplate.queryForMap` est appelé avec le schéma validé `kv_abc123` interpolé.
5. **AC5 (non-régression) — And** la suite `JwtAuthFilterTest` existante + les tests sécurité/applicatifs (`JwtTokenProviderTest`, `SecurityConfig*`, tests contrôleurs admin) restent GREEN ; aucun endpoint légitime n'est cassé.

> ⚠️ **Discrepancy critiques à comprendre AVANT de coder (voir Dev Notes) :**
> 1. **`tenantId` JWT = nom de schéma `kv_xxxxxx`, PAS le code public `KV-ABC123`.** Prouvé par `JwtTokenProvider` javadoc (lignes 50, 82, 96 : « `@param tenantId tenant schema name (e.g., "kv_abc123")` ») et les sites d'émission (`RegistrationService:94` et `SelectTenantService:110` passent `tenant.getSchemaName()` / `schemaName`). Le `tenantId` du JWT est donc validable par `^kv_[a-z0-9]{6}$`.
> 2. **Les tests existants `JwtAuthFilterTest` utilisent des placeholders au MAUVAIS format** (`"KV-ABC123"`, `"KV-SUSP01"`, `"KV-ACT01"` — code public, pas schéma). Ils passent aujourd'hui uniquement parce qu'ils sont tous **OWNER-role** → n'atteignent jamais la branche EMPLOYEE/SQL. Selon l'approche choisie (voir Dev Notes « Approche recommandée V1-stab »), ces tests peuvent rester inchangés (Option A) ou devoir être réécrits en `kv_xxxxxx` (Option B). **Ne pas casser ces tests silencieusement.**

## Tasks / Subtasks

- [x] **Task 1 — Créer la classe utilitaire `TenantSchema` (NEW, AC1)**
  - [x] 1.1 Créer `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchema.java` (classe finale utilitaire, constructeur privé) centralisant le regex du schéma tenant :
        - `public static final Pattern SCHEMA_PATTERN = Pattern.compile("^kv_[a-z0-9]{6}$");`
        - `public static boolean isValid(String schemaName)` — `schemaName != null && SCHEMA_PATTERN.matcher(schemaName).matches();`
        - `public static String validate(String schemaName)` — retourne `schemaName` si `isValid`, sinon `throw new IllegalArgumentException("Invalid tenant schema name format: " + schemaName);` (contract **identique** à `TenantSchemaProvisioner.provision():706-708`).
  - [x] 1.2 Javadoc documentant : format `kv_[a-z0-9]{6}$`, que c'est la porte de confiance pour toute raw-JDBC tenant-qualified query (ARCH18), et que la classe centralise le regex auparavant dupliqué (`SchemaAwareMultiTenantConnectionProvider:119`, `TenantSchemaSyncService:90`, `TenantSchemaProvisioner:706,728`).
- [x] **Task 2 — Appeler `TenantSchema.validate` dans `JwtAuthFilter` (AC1/AC2)**
  - [x] 2.1 Dans `JwtAuthFilter.doFilterInternal`, **au début du bloc `if ("EMPLOYEE".equals(role))` (avant le `jdbcTemplate.queryForMap` de la ligne 147)**, insérer :
        ```java
        String schema;
        try {
            schema = TenantSchema.validate(tenantId);
        } catch (IllegalArgumentException ex) {
            writeError(response, "TOKEN_INVALID");
            return;
        }
        ```
  - [x] 2.2 Remplacer l'interpolation `tenantId` par `schema` dans la requête (ligne 148-149) : `"SELECT store_id, status, password_change_required FROM \"" + schema + "\".employees WHERE user_id = ? LIMIT 1"`. Le `userId` reste un paramètre bindé (`?`) — ne pas y toucher.
  - [x] 2.3 Ajouter un commentaire inline court documentant *pourquoi* la validation est là (S5/ARCH18 : raw-JDBC tenant-qualified query doit valider le schéma avant interpolation ; RS256 atténue mais défense-en-profondeur).
  - [x] 2.4 **Ne pas** modifier `TenantContext.setCurrentTenant(tenantId)` (ligne 129), `tenantSchemaSyncService.syncIfNeeded(tenantId)` (ligne 142), ni le `finally` clear — voir Dev Notes « Pourquoi scope EMPLOYEE ».
- [x] **Task 3 — TDD prouvant le rejet et l'accept (AC3/AC4)**
  - [x] 3.1 Ajouter `shouldRejectTokenWithInvalidTenantIdFormat()` (AC3) : token EMPLOYEE avec `tenantId` = `kv_evil"; DROP TABLE--` → 401 `TOKEN_INVALID`, `verify(filterChain, never()).doFilter(...)`, `verify(jdbcTemplate, never()).queryForMap(anyString(), any())`. Le `jdbcTemplate` peut rester `null` pour ce test (la validation court avant l'appel).
  - [x] 3.2 Ajouter `shouldAcceptValidTenantId()` (AC4) : token EMPLOYEE avec `tenantId` = `kv_abc123`, `jdbcTemplate.queryForMap(...)` mocké pour retourner une `empRow` valide (status `ACTIVE`, `store_id` matchant le `storeId` du JWT, `password_change_required=false`) → `filterChain.doFilter` appelé, statut 200. **Attention : le `setUp` actuel passe `null` pour `jdbcTemplate` (ligne 45) — ce test exige un `JdbcTemplate` mocké** (voir Dev Notes « Squelette TDD »).
  - [x] 3.3 (Optionnel, durcissement) Ajouter `shouldRejectTokenWithPublicCodeFormatAsTenantId()` — `tenantId` = `KV-ABC123` (code public, pas schéma) → 401 `TOKEN_INVALID`. Documente que le code public n'est pas un schéma valide (prévient une confusion future). → **Différé** (optionnel, peut être ajouté en code review si souhaité).
- [x] **Task 4 — Non-régression (AC5)**
  - [x] 4.1 `mvn test -Dtest='JwtAuthFilterTest'` — tous GREEN (13/13 : 11 existants + 2 nouveaux AC3/AC4).
  - [x] 4.2 `mvn test -Dtest='JwtTokenProviderTest,SecurityConfigAdminPathEnforcementTest,*Admin*ControllerTest'` — pas de régression (35/35 GREEN).
  - [x] 4.3 (Sanity) `mvn test -Dtest='TenantSchemaSyncServiceTest,TenantSchemaProvisioner*Test'` — GREEN (25/25). Full suite: 1434 tests, 0 NEW failures (1 pre-existing flaky + 40 pre-existing DB-dependent ApplicationContext errors).
- [x] **Task 5 — Dev Agent Record**
  - [x] 5.1 Renseigner Agent Model, Debug Log, Completion Notes, File List.

## Dev Notes

### 🔬 Approche recommandée V1-stab — scope EMPLOYEE (Option A), pas global (Option B)

Deux approches sont possibles ; **l'Option A est l'approche V1-stab recommandée** (chirurgicale, regression-born, alignée avec 12-1/12-3) :

- **Option A (recommandée, scope EMPLOYEE) :** valider `tenantId` **au début du bloc EMPLOYEE** uniquement, juste avant le `JdbcTemplate` call. C'est l'interpolation SQL qui est le vecteur (B-HIGH-6), et c'est la **seule** requête `JdbcTemplate` dans le filtre. Avantages : (a) zéro régression sur les tests OWNER existants (ils n'atteignent jamais cette branche), (b) satisfait l'AC1 littéralement (« before any JdbcTemplate query » = la seule requête JdbcTemplate du filtre), (c) cohérent avec la philosophie V1-stab « 1 fichier, défense ciblée ».
- **Option B (défense-en-profondeur globale, à réserver à la refonte) :** valider `tenantId` **immédiatement après extraction** (après `jwtTokenProvider.extractTenantId(claims)`, ligne 123), avant tout usage (`TenantContext`, `syncIfNeeded`, SQL). Plus robuste (ARCH18 strict), MAIS casse les 5 tests existants qui utilisent `KV-ABC123`/`KV-SUSP01`/`KV-ACT01`/`KV-SUSP02` comme placeholders → il faudrait les réécrire en `kv_xxxxxx`. L'audit dit explicitement : « **Refonte absorption:** re-implement natively (validation port dans `shared/infrastructure`) » — la validation globale/port est un objectif refonte, pas V1.

**Choisir Option A.** Documenter dans Completion Notes pourquoi Option B est différée (refonte). Si l'équipe préfère Option B, alors réécrire AUSSI les 5 tests placeholders (`should_set_tenant_context_and_proceed_for_valid_jwt`, `should_clear_tenant_context_even_on_exception`, `should_return_403_when_tenant_suspended_and_write_method`, `should_allow_get_for_suspended_tenant`, `should_allow_post_for_active_tenant`) pour utiliser `kv_abc123` / `kv_susp01` / `kv_act01` — et le faire dans la même PR (sinon la CI casse).

### Pourquoi scope EMPLOYEE est sûr (analyse du flux)

Le `tenantId` est utilisé à 3 endroits dans `doFilterInternal` ; vérifier que ne valider qu'au point EMPLOYEE ne laisse pas de vecteur :

| Usage | Ligne | Risque si `tenantId` malformé ? | Couvert ? |
|---|---|---|---|
| `TenantContext.setCurrentTenant(tenantId)` | 129 | Stocke la valeur brute dans un ThreadLocal. Le `SchemaAwareMultiTenantConnectionProvider.isTenantSchema()` (ligne 116-120) **re-jette** tout ce qui ne matche pas `^kv_[a-z0-9]{6}$` → pas de `SET search_path` appliqué → les requêtes JPA tombent sur `public` (relation not found), pas d'injection. Nettoyé dans `finally:196`. | Sûr (porte existante) |
| `tenantSchemaSyncService.syncIfNeeded(tenantId)` | 142 | `TenantSchemaSyncService:116` `if (schemaName == null || !schemaName.matches(SCHEMA_PATTERN)) return;` → **no-op** silencieux sur format invalide. | Sûr (porte existante) |
| `jdbcTemplate.queryForMap("...\"" + tenantId + "\".employees...")` | 147-149 | **Injection SQL potentielle** (B-HIGH-6) — c'est la seule interpolation SQL brute du filtre, et elle **contourne** la porte du connection provider. | **Pas couvert → cette story ajoute `TenantSchema.validate`** |

Conclusion : la seule couture non protégée est la ligne 147-149. L'Option A la ferme exactement. Les deux autres usages sont déjà gating par la porte regex existante. C'est pourquoi l'audit cible spécifiquement `JwtAuthFilter:157` (devenu 147-149 après renumérotation) et pas les autres lignes.

### Convention d'autorité — `tenantId` JWT = nom de schéma `kv_xxxxxx`

**Ne pas confondre** les deux identifiants tenant (source de bug #1) :

| Identifiant | Format | Exemple | Où |
|---|---|---|---|
| **Code public** `Tenant.code` | `KV-XXXXXX` (6 alnum majuscules, préfixe `KV-`) | `KV-ABC123` | `Tenant.java:17`, URL/publique, `Tenant.schemaNameFromCode()` |
| **Nom de schéma** `Tenant.schemaName` = claim JWT `tenantId` | `kv_xxxxxx` (6 alnum minuscules, préfixe `kv_`) | `kv_abc123` | `Tenant.java:18`, claim JWT `tenantId`, `TenantSchemaProvisioner`, `search_path` |

- **Le claim JWT `tenantId` est le nom de schéma** (`kv_xxxxxx`), PAS le code public. Preuves : `JwtTokenProvider.java:50,82,96` (javadoc « `tenantId tenant schema name (e.g., "kv_abc123")` »), `RefreshToken.java:14` (« `@param tenantId tenant identifier (schema name)` »), `RegistrationService.java:94` passe `tenant.getSchemaName()`, `SelectTenantService.java:110` passe `schemaName`.
- → La regex de validation `^kv_[a-z0-9]{6}$` s'applique bien au claim `tenantId`. ✓
- → Les tests existants qui injectent `"KV-ABC123"` comme `tenantId` utilisent le **mauvais format** (code public). Ils passent par accident (rôle OWNER, pas de branche SQL). Ne pas imiter ce placeholder pour les nouveaux tests EMPLOYEE — utiliser `kv_abc123`.

### `TenantSchema.validate` n'existe pas encore — la regex est dupliquée 4×

Aucune classe `TenantSchema` n'existe sur la branche `v1-stabilization` (vérifié : `find` + `grep` ne trouvent que `TenantSchemaSyncService`, `TenantSchemaProvisioner`, `TenantSchemaMigrationRunner`, `TenantSchemaPort`, `TenantSchemaAdapter` — pas de `TenantSchema` utilitaire). Le regex `^kv_[a-z0-9]{6}$` est **dupliqué** à :

- `SchemaAwareMultiTenantConnectionProvider.java:119` — `isTenantSchema()` (porte `search_path`)
- `TenantSchemaSyncService.java:90` (`SCHEMA_PATTERN`), `:116` — `syncIfNeeded()` no-op
- `TenantSchemaProvisioner.java:706` — `provision()` throw, `:728` — `dropSchemaIfExists()` no-op

**La classe `TenantSchema` (Task 1) centralise ce regex (DRY).** C'est l'API que l'audit nomme (`TenantSchema.validate(tenantId)`). Son contrat miroir de `TenantSchemaProvisioner.provision():706-708` (throw `IllegalArgumentException` sur invalide) — cohérent avec l'existant.

> **Note refonte (tag B, hors V1-stab) :** le snapshot refonte `ae81a6c` (Epic 11-4, branche `refractoring`) a fait un correctif similaire (« TenantSchema.validate » apparaît dans le `sprint-status` note 11-4). **Ne pas cherry-picker** depuis `refractoring` — la branche V1-stab diverge ; la classe est triviale à recréer (Task 1). Optionnel : faire appeler les 4 sites existants par `TenantSchema.isValid()` pour éliminer la duplication — **out of scope** V1-stab (scope creep, toucher à 4 fichiers non visés par l'audit) → noter comme defer.

### `validate` contract — ne pas confondre avec `JwtException`

`TenantSchema.validate` lève `IllegalArgumentException` (runtime, pas une `JwtException`). Le `catch (JwtException e)` existant (ligne 192) **ne l'attrape pas**. Donc :

- Soit utiliser un `try/catch (IllegalArgumentException)` dédié **avant** le `jdbcTemplate` call (recommandé, voir Task 2.1 — clarifie l'intent et 401 propre).
- Soit checker `if (!TenantSchema.isValid(tenantId)) { writeError(response, "TOKEN_INVALID"); return; }` puis utiliser `tenantId` (qui est valide). Les deux sont acceptables ; le `try/catch` reflète le contrat `validate` de l'AC littéral (« `String schema = TenantSchema.validate(tenantId)` »).

**Ne pas** laisser l'`IllegalArgumentException` se propager au `finally` puis au conteneur (=> 500 serveur au lieu de 401 `TOKEN_INVALID` = AC2 non satisfaite).

### Réutiliser `writeError` — ne pas ajouter de helper

`writeError(response, "TOKEN_INVALID")` (ligne 215-220) produit déjà 401 JSON `{"domainCode":"TOKEN_INVALID"}`. C'est le même code que le rejet `login_pending` (ligne 119). **Ne pas** créer un nouveau helper ni un nouveau `domainCode` — `TOKEN_INVALID` est exactement l'AC2. Vérifier la cohérence : le `login_pending` scope rejection utilise déjà `TOKEN_INVALID` pour un claim invalide → `tenantId` invalide suit la même sémantique (claim inacceptable).

### Squelette TDD — `JwtAuthFilterTest` (AC3/AC4)

Le `setUp` actuel (ligne 43-46) construit `filter = new JwtAuthFilter(jwtTokenProvider, new ObjectMapper(), tenantSchemaSyncService, null);` — **`jdbcTemplate = null`**. AC3 (rejet) fonctionne avec `null` (validation court avant l'appel SQL). **AC4 (accept EMPLOYEE) exige un `JdbcTemplate` mocké** car il atteint `jdbcTemplate.queryForMap`. Deux stratégies :

1. **(Recommandé) Constructeur local pour tests EMPLOYEE** — ne pas casser les tests OWNER existants qui dépendent du `null` :
   ```java
   @Mock JdbcTemplate jdbcTemplate;   // ajouter au champs de la classe

   private JwtAuthFilter filterWithJdbc() {
       return new JwtAuthFilter(jwtTokenProvider, new ObjectMapper(),
                                tenantSchemaSyncService, jdbcTemplate);
   }

   @Test
   @DisplayName("EMPLOYEE token with invalid tenantId format → 401 TOKEN_INVALID, no SQL call")
   void shouldRejectTokenWithInvalidTenantIdFormat() throws Exception {
       MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
       request.addHeader("Authorization", "Bearer emp.jwt.token");
       MockHttpServletResponse response = new MockHttpServletResponse();

       UUID userId = UUID.randomUUID();
       Claims claims = buildClaims(userId, "kv_evil\"; DROP TABLE--", "EMPLOYEE");
       when(jwtTokenProvider.parseToken("emp.jwt.token")).thenReturn(claims);
       when(jwtTokenProvider.extractScope(claims)).thenReturn("access");
       when(jwtTokenProvider.extractTenantId(claims)).thenReturn("kv_evil\"; DROP TABLE--");
       when(jwtTokenProvider.extractRole(claims)).thenReturn("EMPLOYEE");
       when(jwtTokenProvider.extractUserId(claims)).thenReturn(userId);
       when(jwtTokenProvider.extractTenantStatus(claims)).thenReturn("ACTIVE");

       filterWithJdbc().doFilterInternal(request, response, filterChain);

       assertThat(response.getStatus()).isEqualTo(401);
       assertThat(response.getContentAsString()).contains("TOKEN_INVALID");
       verify(filterChain, never()).doFilter(any(), any());
       verify(jdbcTemplate, never()).queryForMap(anyString(), any());
   }

   @Test
   @DisplayName("EMPLOYEE token with valid kv_xxxxxx tenantId → 200, SQL uses validated schema")
   void shouldAcceptValidTenantId() throws Exception {
       MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
       request.addHeader("Authorization", "Bearer emp.jwt.token");
       MockHttpServletResponse response = new MockHttpServletResponse();

       UUID userId = UUID.randomUUID();
       UUID storeId = UUID.randomUUID();
       Claims claims = buildClaims(userId, "kv_abc123", "EMPLOYEE");
       when(jwtTokenProvider.parseToken("emp.jwt.token")).thenReturn(claims);
       when(jwtTokenProvider.extractScope(claims)).thenReturn("access");
       when(jwtTokenProvider.extractTenantId(claims)).thenReturn("kv_abc123");
       when(jwtTokenProvider.extractRole(claims)).thenReturn("EMPLOYEE");
       when(jwtTokenProvider.extractUserId(claims)).thenReturn(userId);
       when(jwtTokenProvider.extractTenantStatus(claims)).thenReturn("ACTIVE");
       when(jwtTokenProvider.extractStoreId(claims)).thenReturn(storeId);

       // empRow valide : ACTIVE, store_id match JWT, pas de change pwd requis
       Map<String, Object> empRow = new java.util.HashMap<>();
       empRow.put("store_id", storeId);
       empRow.put("status", "ACTIVE");
       empRow.put("password_change_required", false);
       when(jdbcTemplate.queryForMap(contains("kv_abc123"), eq(userId))).thenReturn(empRow);

       filterWithJdbc().doFilterInternal(request, response, filterChain);

       verify(jdbcTemplate).queryForMap(contains("\"kv_abc123\"."), eq(userId));
       verify(filterChain).doFilter(request, response);
       assertThat(response.getStatus()).isEqualTo(200);
   }
   ```
   > **Subtilité Mockito** : `buildClaims` (helper existant ligne 266-273) ne met que `tenantId`/`role`/expiration. Les `extract*` sont toutes mockées séparément, donc le contenu réel des claims importe peu — mais `extractScope` doit retourner autre chose que `"login_pending"` (sinon rejet précoce ligne 118). Retourner `"access"` (ou `null`). Les tests OWNER existants ne mockent pas `extractScope`/`extractTenantStatus` car ils n'en ont pas besoin (chemin OWNER sans SUSPENDED) — vérifier qu'ajouter ces mocks ne casse pas `MockitoExtension` (strict stubs) : ne mocker `extractScope`/`extractTenantStatus` QUE dans les tests EMPLOYEE qui les consomment.
   > **`contains("\"kv_abc123\".")`** prouve que c'est le schéma validé qui est interpolé (AC1 « the validated schema is used »), pas le `tenantId` brut — même valeur ici, mais l'assertion ancre le contrat.

2. **(Alternative) Refactor `setUp` pour mocker `jdbcTemplate`** pour tous les tests — risque de casser les tests OWNER existants (strict stubbings inutilisées). Préférer la stratégie 1 (constructeur local).

### Fichiers à toucher

| Fichier | Ligne | État actuel | Changement story | À préserver |
|---|---|---|---|---|
| `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchema.java` | — | **N'existe pas** | NEW : classe utilitaire (`isValid` + `validate` + `SCHEMA_PATTERN`) | conventions du package `shared/infrastructure/persistence` (zero domaine, utilitaire pur) |
| `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java` | 144-176 (bloc EMPLOYEE) | `jdbcTemplate.queryForMap("...\"" + tenantId + "\".employees...")` sans validation | Ajouter `TenantSchema.validate(tenantId)` au début du bloc + interpoler `schema` + commentaire S5/ARCH18 | `PUBLIC_PATHS`, `shouldNotFilter`, extraction claims, scope `login_pending` rejection, SUSPENDED guard, `TenantContext` set/clear, `SecurityContextHolder`, `finally` clear, helper `writeError`/`writeErrorWithStatus` |
| `keevo/backend/src/test/java/com/keevo/shared/infrastructure/security/JwtAuthFilterTest.java` | 43-46 (setUp), 264-273 (buildClaims) | `jdbcTemplate=null`, tests OWNER avec placeholders `KV-*` | NEW tests AC3/AC4 (+ Option A : laisser les tests OWNER inchangés) | tous les tests existants (11) doivent rester GREEN |

Aucun contrat API, aucun schéma DB, aucun workflow CI, aucun Dockerfile touché. Backend pur, 1 nouvelle classe utilitaire + ~6 lignes dans le filtre + 2 tests.

### Tests existants à ne pas casser

- `JwtAuthFilterTest` (11 tests existants) — missing header, no Bearer, expired, malformed, valid OWNER JWT, no-sync-on-invalid, clear-context-on-exception, login_pending rejection, SUSPENDED POST/GET, ACTIVE POST. **Tous OWNER (placeholders `KV-*`)** → Option A les laisse intacts.
- `JwtTokenProviderTest`, `SecurityConfigAdminPathEnforcementTest` (story 12-3), `AdminTenantControllerTest`/`AdminSubscriptionControllerTest`/`AdminCatalogControllerTest`/`AdminSyncMonitoringControllerTest` — pas touchés par cette story (vérification sanity seulement).
- `TenantSchemaSyncServiceTest`, `TenantSchemaProvisioner*DDLTest` — ne pas casser (la nouvelle classe `TenantSchema` ne modifie pas ces classes ; import distinct).

## Architecture Compliance

- **S5 / B-HIGH-6** : cette story ferme la couture « raw SQL interpolation sans validation ». Remédiation exacte pointée par l'audit (`AUDIT_CONFORMITE_BMAD.md:115-117,274,284,333`).
- **ARCH18 (raw-JDBC tenant-qualified query)** — `architecture.md:1424,1447` : « Sync runs in the security filter before the JPA session, uses fully-qualified `"schema"."table"` names » ; « Never use bare table names (`FROM stores`) with `JdbcTemplate` ». Le schéma interpolé **doit** être validé avant interpolation — `TenantSchema.validate` réalise ce contrat. La requête EMPLOYEE de `JwtAuthFilter:147-149` contournait la porte `search_path` du connection provider ; cette story ajoute la porte manquante au point d'interpolation.
- **NFR10 (sécurité/injection)** + **OWASP A03 (Injection)** : least-trust sur un claim JWT avant interpolation SQL. Même si RS256 atténue (forge improbable sans clé privée — noter `AUDIT_CONFORMITE_BMAD.md:117` « Mitigé par signature RS256 »), la défense-en-profondeur exiger qu'aucun claim non validé n'entre dans du SQL.
- **Pas de nouveau contrat API/DB**, pas de workflow CI, pas de changement deploy. Backend pur, régression-born.

## Library / Framework Requirements

- **JDK 21** (déjà utilisé) — `java.util.regex.Pattern`, `IllegalArgumentException`. Aucune nouvelle dépendance.
- **Spring Security 6.x / Spring Boot 3.5.0** (déjà en place) — `OncePerRequestFilter`, `JdbcTemplate` inchangés.
- **JUnit 5 + Mockito + AssertJ** (déjà en place, `@ExtendWith(MockitoExtension.class)`) — `@Mock JdbcTemplate`, `verify(...never())`, `contains(...)`. Aucune version à changer, aucune migration.
- Aucune dépendance externe ajoutée. Aucune version modifiée.

## File Structure Requirements

- Utilitaire persistence : `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchema.java` (**NEW**, co-localisé avec `SchemaAwareMultiTenantConnectionProvider` / `TenantSchemaSyncService` / `TenantSchemaProvisioner` qui dupliquent le regex — home naturelle, alignée avec l'audit « validation port dans `shared/infrastructure` »).
- Filtre sécurité : `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java` (**UPDATE**, bloc EMPLOYEE ~ligne 144-176).
- Tests : `keevo/backend/src/test/java/com/keevo/shared/infrastructure/security/JwtAuthFilterTest.java` (**UPDATE**, +2 tests AC3/AC4 + helper `filterWithJdbc()`).
- Tracker : `_bmad-output/implementation-artifacts/sprint-status.yaml` (statut `v1s-12-5-tenantid-validate-jwtfilter` → ready-for-dev).

## Testing Requirements

- **TDD (AC3/AC4, obligatoire)** :
  - `shouldRejectTokenWithInvalidTenantIdFormat()` — EMPLOYEE + `tenantId` malformé → 401 `TOKEN_INVALID`, `filterChain` jamais appelé, `jdbcTemplate` jamais appelé. RED avant la story (pas de validation → la requête SQL s'exécute avec un schéma malformé / NPE sur `jdbcTemplate` null), GREEN après.
  - `shouldAcceptValidTenantId()` — EMPLOYEE + `tenantId` = `kv_abc123` → `filterChain` appelé, `jdbcTemplate.queryForMap` appelé avec le schéma validé interpolé.
  - (Optionnel) `shouldRejectTokenWithPublicCodeFormatAsTenantId()` — `tenantId` = `KV-ABC123` → 401 (documente code-public ≠ schéma).
- **Non-régression (AC5)** : `mvn test -Dtest='JwtAuthFilterTest,JwtTokenProviderTest,SecurityConfigAdminPathEnforcementTest,AdminTenantControllerTest,AdminSubscriptionControllerTest,AdminCatalogControllerTest,AdminSyncMonitoringControllerTest'` GREEN. Sanity : `TenantSchemaSyncServiceTest,TenantSchemaProvisioner*DDLTest` GREEN.
- Pas d'environnement runtime/deploy requis (test unitaire `@ExtendWith(MockitoExtension.class)`, `MockHttpServletRequest`/`MockHttpServletResponse`).

## Previous Story Intelligence

- **Story 12.3 (`v1s-12-3`, done)** : patterns V1-stab — branche `v1-stabilization`, tag A, chirurgical, défense-en-profondeur, code review passé (0 patch). Modèle de structure de story (Dev Notes en tables, squelette TDD, AC5 non-régression explicite). **À réutiliser** : le style de commentaire inline documentant le *pourquoi* (S-number + architecture ref), le `@WebMvcTest`/Mockito patterns, et le refus de scope creep (12-3 a diffé les durcissements optionnels). Le `SecurityConfigAdminPathEnforcementTest` créé par 12-3 doit rester GREEN — sanity check seulement.
- **Story 12.1 (`v1s-12-1`, done)** : a posé le pinning UID/GID et le wiring CI grep lint. Rappel du précepte V1-stab « chirurgical, 1 fichier ciblé ». Aucun wiring CI requis ici (test Java `@MockitoBean`/Mockito, pas de regression grep à surveiller).
- **Story 10-3 (`ProdSecretsValidator`)** : fail-fast boot. Rappel « réutiliser/étendre l'existant » — ici, **réutiliser** le helper `writeError`/`TOKEN_INVALID` existant et le contrat `IllegalArgumentException` de `TenantSchemaProvisioner:706`, ne pas réinventer.
- **Story 11-4 (refonte snapshot `ae81a6c`, branche `refractoring`)** : a fait un correctif `TenantSchema.validate` similaire sur la refonte. **Ne pas cherry-picker** — diverge de V1. Recréer la classe fraîchement (Task 1). La refonte réabsorbera ce fix.

## Git Intelligence

- **Branche courante `v1-stabilization`** (off `deploy` d766035, HEAD `b61fc27` story 12.3 done) — V1 reste déployable pendant la refonte.
- `JwtAuthFilter.java` n'a pas été modifié récemment sur V1-stab (structure V1 originale). La section EMPLOYEE (lignes 144-176) est l'état V1 original — insert chirurgical.
- Aucun commit V1-stab n'a touché au `tenantId` validation : S5 est un gap de conception d'origine (audit post-mortem), pas une régression récente. La porte regex existait dans le connection provider/sync/provisioner mais pas au point d'injection SQL du filtre.
- Le snapshot refonte `ae81a6c` (11-4) contient un fix similaire mais sur `refractoring` — hors de la branche V1-stab. Recréer, pas cherry-picker.

## Latest Technical Information

- **`java.util.regex.Pattern` (JDK 21)** : `Pattern.compile("^kv_[a-z0-9]{6}$")` pré-compilé en `static final` (réutilisé à chaque requête — pas de recompilation). Thread-safe. Cohérent avec `TenantSchemaSyncService:90` (`static final String SCHEMA_PATTERN`).
- **Regex `^kv_[a-z0-9]{6}$`** : exactement 6 caractères alphanumériques minuscules après `kv_`. **Pas de majuscules, pas de tiret, pas d'underscore supplémentaire.** Un `tenantId` = `KV-ABC123` (code public) ne matche PAS → 401 (c'est attendu et correct : le claim JWT doit être le schéma, pas le code). Vérifier que les schémas générés par `Tenant.schemaNameFromCode("KV-ABC123")` = `"kv_abc123"` matchent bien (6 alnum minuscules) ✓.
- **`IllegalArgumentException` vs `JwtException`** : `TenantSchema.validate` lève `IllegalArgumentException` (RuntimeException), **pas** une `io.jsonwebtoken.JwtException`. Le `catch (JwtException e)` existant (ligne 192) ne l'attrape pas → utiliser un `try/catch (IllegalArgumentException)` dédié (Task 2.1) pour produire 401 `TOKEN_INVALID`. Sinon l'exception propage au conteneur → 500 (AC2 échec).
- Pas de breaking change dépendances. Aucune migration.

## Project Context Reference

- [Source: `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md`#Story-12.5] — AC BDD, refs S5/B-HIGH-6/ARCH18/NFR10, refonte absorption.
- [Source: `AUDIT_CONFORMITE_BMAD.md:14,115-117,274,284,333`] — preuve file:line B-HIGH-6/S5 (Haute, injection SQL) + remédiation exacte `TenantSchema.validate(tenantId)` avant interpolation + note RS256 atténuation.
- [Source: `_bmad-output/implementation-artifacts/v1-stabilization-stories.md:18`] — index track (sécurité backend, S5/B-HIGH-6).
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java:144-176`] — état actuel (bloc EMPLOYEE, interpolation `tenantId` ligne 147-149 sans validation).
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java:115-121,215-220`] — pattern de rejet `TOKEN_INVALID` (scope `login_pending`) + helper `writeError` (401 JSON) à réutiliser.
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/SchemaAwareMultiTenantConnectionProvider.java:116-120`] — porte regex existante `^kv_[a-z0-9]{6}$` (le « pattern exists elsewhere » de l'audit) — modèle pour `TenantSchema`.
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaSyncService.java:90,113-124`] — `SCHEMA_PATTERN` + `syncIfNeeded` no-op sur invalide (preuve que les usages non-SQL sont déjà gating).
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java:705-708`] — contrat `validate` (throw `IllegalArgumentException` sur format invalide) à miroiter.
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtTokenProvider.java:50,82,96,110`] — javadoc/claim `tenantId` = nom de schéma `kv_abc123` (preuve de format).
- [Source: `keevo/backend/src/main/java/com/keevo/identity/auth/domain/model/Tenant.java:17-18,41-43`] — distinction code public `KV-ABC123` vs schéma `kv_abc123` + `schemaNameFromCode`.
- [Source: `keevo/backend/src/main/java/com/keevo/identity/auth/application/service/RegistrationService.java:94`] + [`SelectTenantService.java:110`] — sites d'émission passant `getSchemaName()`/`schemaName` au claim `tenantId`.
- [Source: `keevo/backend/src/test/java/com/keevo/shared/infrastructure/security/JwtAuthFilterTest.java:43-46,264-273`] — `setUp` (`jdbcTemplate=null`) + helper `buildClaims` (placeholders `KV-*` à ne pas imiter pour EMPLOYEE).
- [Source: `_bmad-output/planning-artifacts/architecture.md:1424,1447`] — ARCH18 raw-JDBC fully-qualified `"schema"."table"` + « never bare table names with JdbcTemplate ».

## Dev Agent Record

### Agent Model Used

deepseek-v4-pro (Claude Code CLI)

### Debug Log References

- RED phase: `shouldRejectTokenWithInvalidTenantIdFormat` → 200 (expected 401) — confirmed validation missing, NPE swallowed by MockHttpServletResponse default status
- GREEN phase: both AC3 (401 TOKEN_INVALID) + AC4 (200, filterChain called) pass after `TenantSchema.validate` + `catch (IllegalArgumentException)` added
- AC5.1: JwtAuthFilterTest 13/13 GREEN (11 existing OWNER + 2 new EMPLOYEE)
- AC5.2: Security + admin tests 35/35 GREEN
- AC5.3: Sanity 25/25 GREEN
- Full regression: 1434 tests, 0 NEW failures (1 pre-existing flaky `OnboardingServiceTest.categories_should_have_valid_fields` + 40 pre-existing DB-dependent `ApplicationContext` errors)

### Completion Notes List

- ✅ **AC1/AC2**: `TenantSchema.validate(tenantId)` inséré au début du bloc EMPLOYEE de `JwtAuthFilter.doFilterInternal` (ligne 147-149 → 153-160). `String schema` (validé) interpolé dans le SQL au lieu du `tenantId` brut. `catch (IllegalArgumentException)` → `writeError(response, "TOKEN_INVALID")` (401 JSON). Commentaire inline S5/ARCH18 documentant le pourquoi.
- ✅ **AC3**: `shouldRejectTokenWithInvalidTenantIdFormat()` — EMPLOYEE + `tenantId` = `kv_evil"; DROP TABLE--` → 401 `TOKEN_INVALID`, `filterChain` jamais appelé, `jdbcTemplate` jamais appelé.
- ✅ **AC4**: `shouldAcceptValidTenantId()` — EMPLOYEE + `tenantId` = `kv_abc123` → `filterChain.doFilter` appelé, `jdbcTemplate.queryForMap` appelé avec `contains("\"kv_abc123\".")` (schéma validé interpolé), 200.
- ✅ **AC5**: 13/13 JwtAuthFilterTest, 35/35 sécurité+admin, 25/25 sanity, 0 nouvelle régression sur 1434 tests.
- **Approche Option A (scope EMPLOYEE)** appliquée : validation chirurgicale au point d'injection SQL uniquement. Les 11 tests OWNER existants (placeholders `KV-*`) sont intacts. `TenantContext.setCurrentTenant` / `syncIfNeeded` / `finally` clear non modifiés (déjà gating par les portes regex existantes — voir Dev Notes table d'analyse de flux).
- **Option B (validation globale)** différée à la refonte comme documenté dans l'audit (« Refonte absorption: re-implement natively (validation port dans shared/infrastructure) »).
- **Task 3.3** (optionnel `shouldRejectTokenWithPublicCodeFormatAsTenantId`) différé — peut être ajouté en code review si souhaité.
- **Classe `TenantSchema`** (nouvelle) : utilitaire final, constructeur privé, `SCHEMA_PATTERN` compilé `static final`, `isValid(String)` + `validate(String)` (throw `IllegalArgumentException`). Package `shared/infrastructure/persistence` (co-localisé avec les 4 classes qui dupliquent le regex). Javadoc complète listant les sites de duplication.
- **Note refonte**: les 4 sites de duplication (`SchemaAwareMultiTenantConnectionProvider:119`, `TenantSchemaSyncService:90`, `TenantSchemaProvisioner:706,728`) n'ont PAS été migrés vers `TenantSchema.isValid()` — hors scope V1-stab (scope creep). La refonte les fera converger.

### File List

- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchema.java` — **NEW** — classe utilitaire de validation de schéma tenant (ARCH18)
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java` — **MODIFIED** — ajout `TenantSchema.validate(tenantId)` + `catch (IllegalArgumentException)` + interpolation `schema` validé dans le bloc EMPLOYEE (lignes 147-160)
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/security/JwtAuthFilterTest.java` — **MODIFIED** — +`@Mock JdbcTemplate`, +`filterWithJdbc()` helper, +2 tests AC3/AC4 (13 total)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — **MODIFIED** — `v1s-12-5-tenantid-validate-jwtfilter` → in-progress → review
- `_bmad-output/implementation-artifacts/v1s-12-5-tenantid-validate-jwtfilter.md` — **MODIFIED** — this file (tasks marked [x], Dev Agent Record populated, Status → review)

### Review Findings

> Code review bmad-code-review (2026-07-21) — 3 couches parallèles (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 33 findings bruts → 21 uniques → 1 décision, 2 patchs, 9 defers, 9 dismiss.

- [x] [Review][Decision] **D1 — AC2 scope littéral vs Option A intentionnelle** (résolu : Option A acceptée — scope EMPLOYEE chirurgical confirmé, validation globale différée refonte)

- [x] [Review][Patch] **P1 — `catch (IllegalArgumentException)` trop large** (appliqué : `TenantSchema.validate()` sorti du bloc try interne, catch scoped au validate only)

- [x] [Review][Patch] **P2 — `SCHEMA_PATTERN` public → private** (appliqué : champ passé en `private static final`)

- [x] [Review][Defer] **D3 — OWNER + `tenantId` invalide → 500 au lieu de 401** [`JwtAuthFilter.java:130,190`] — Les tokens OWNER avec `tenantId` invalide passent `TenantContext.setCurrentTenant(invalid)` puis les requêtes JPA échouent sur `public` → 500. Pas de SQL injection (connection provider gate bloque `search_path`). Intentionnel (Option B validation globale différée à la refonte). — deferred, pre-existing

- [x] [Review][Defer] **D4 — `jdbcTemplate=null` dans `setUp()`** [`JwtAuthFilterTest.java:50`] — Le filtre par défaut est construit avec `jdbcTemplate=null`. Si un futur test EMPLOYEE utilise `filter` au lieu de `filterWithJdbc()`, NPE. Les tests OWNER existants sont safe. Le helper `filterWithJdbc()` existe et est documenté. — deferred, pre-existing

- [x] [Review][Defer] **D5 — Message d'exception avec input attacker-controlled** [`TenantSchema.java:65`] — `"Invalid tenant schema name format: " + schemaName` concatène le claim JWT brut dans le message d'exception. Non exploitable actuellement (catch + discard dans `JwtAuthFilter`). Risque si un futur caller log l'exception sans assainissement. — deferred, pre-existing

- [x] [Review][Defer] **D6 — Pas de test EMPLOYEE INACTIVE avec `validate()`** [`JwtAuthFilterTest.java`] — `shouldAcceptValidTenantId` ne couvre que ACTIVE. Le chemin de rejet INACTIVE après validation réussie n'est pas testé. Le check INACTIVE est une simple comparaison de string, inchangé par ce diff. — deferred, pre-existing

- [x] [Review][Defer] **D7 — `TenantSchema` dit centraliser mais les 4 fichiers dupliquants ne sont pas migrés** [`TenantSchema.java:10-16` + `SchemaAwareMultiTenantConnectionProvider`, `TenantSchemaSyncService`, `TenantSchemaProvisioner`] — La Javadoc liste 4 fichiers qui dupliquent le regex. Aucun n'est migré vers `TenantSchema.isValid()`. Explicitement hors-scope V1-stab selon le spect (« noter comme defer »). La refonte les fera converger. — deferred, pre-existing

- [x] [Review][Defer] **D8 — Pas de test `tenantId=null` dans la branche EMPLOYEE** [`JwtAuthFilterTest.java`] — `TenantSchema.validate(null)` throw `IllegalArgumentException` → 401. Redondant avec AC3 (même code path). — deferred, pre-existing

- [x] [Review][Defer] **D9 — Pas de test `tenantId=""` (empty string) dans la branche EMPLOYEE** [`JwtAuthFilterTest.java`] — `""` ne matche pas le regex → même chemin que AC3. Redondant. — deferred, pre-existing

- [x] [Review][Defer] **D10 — `IncorrectResultSizeDataAccessException` (>1 row) → 500** [`JwtAuthFilter.java:155,183`] — `queryForMap` throw `IncorrectResultSizeDataAccessException` pour >1 row. Non catché par le `EmptyResultDataAccessException`. Pré-existant, non introduit par ce diff. >1 employé pour le même `user_id` serait un bug d'intégrité données. — deferred, pre-existing

- [x] [Review][Defer] **D11 — `TenantSchemaSyncService` utilise `String.matches()` au lieu de `Pattern.compile()`** [`TenantSchemaSyncService.java:116`] — Recompile le regex à chaque appel. Pré-existant, impact négligeable (appelé 1× par tenant par vie de JVM, caché dans `ConcurrentHashMap`). — deferred, pre-existing
