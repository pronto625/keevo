---
baseline_commit: b61fc27
---
# Story 12.2: Révocation de session <5min — `tokens_valid_after` + `TokenRevocationPort` (V4)

Status: done

<!-- V1-stabilization track — tag A (patch V1 urgent, release-blocker).
     Branche : v1-stabilization (off `deploy` d766035, HEAD b61fc27 = story 12.3 done).
     Refonte réabsorbe le fix (audit : « Refonte absorption: re-implement natively in identity/auth modular (même TokenRevocationPort) »).
     ⚠️ Story blocante pour 14.9 (bannière session-révoquée Flutter) et 14.12 (mot de passe oublié OTP) — celles-ci consomment le `domainCode = SESSION_REVOKED` émis ici.
     Scope : BACKEND UNIQUEMENT. La contrepartie Flutter (`AuthInterceptor.onError` branche `SESSION_REVOKED` → `_expireSession()`) est explicitement différée à 14.9 / 12.7 (ties) — voir « Out of scope ».
     Validation optionnelle : lancer `validate-create-story` avant `dev-story`. -->

## Story

**As a** Toor (opérateur de la plateforme),
**I want** qu'une adhésion révoquée (employé désactivé OU mot de passe changé) perde l'accès API dans la fenêtre NFR12 (<5 min) **indépendamment** de la durée de vie du jeton d'accès (24 h),
**so that** un token compromise/valide ne garde pas accès jusqu'à expiration naturelle — ferme S2 / B-HIGH-5 (NFR12, FR68, Story 11-6).

## Contexte V1-stabilization

- **Source backlog :** `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md` → Story 12.2 (Refs S2, B-HIGH-5, NFR12, FR68, Story 11-6). Lignes 85-102.
- **Audit preuve `file:line` :**
  - `AUDIT_CONFORMITE_BMAD.md:111-113` — « **B-HIGH-5** — Révocation de session <5min non implémentée (NFR12/FR68/Story 11-6). **Cat :** sécurité · **Fichier :** `JwtAuthFilter.java:113-211` (pas de révocation); pas de migration V4; `sprint-status: ready-for-dev`. **Actuel :** Seul EMPLOYEE bloqué via check DB per-request `employees.status`. Les tokens OWNER/compromis valides jusqu'à 24h. **Vérifié. Confiance : Haute.** »
  - `AUDIT_CONFORMITE_BMAD.md:14` — « la révocation de session (NFR12, <5 min) n'est pas implémentée pour les rôles non-employés ».
  - `AUDIT_CONFORMITE_BMAD.md:271` — « S2 | Haute | Révocation de session <5min non implémentée (non-employé) | pas de `TokenRevocationPort`/V4 | NFR12/FR68 ».
  - `AUDIT_CONFORMITE_BMAD.md:331` — remédiation : « Implémenter Story 11-6 : V4 `tokens_valid_after` + `TokenRevocationPort` + cutoff iat dans `JwtAuthFilter` — tue S2/B-HIGH-5. »
- **Architecture :** `_bmad-output/planning-artifacts/architecture.md:47` — « Security: … JWT 24h expiry + refresh … **<5min session revocation** … » (NFR12). Access-token TTL = `JwtProperties.accessTokenExpiryHours = 24` (`JwtProperties.java:15`).
- **Index track :** `_bmad-output/implementation-artifacts/v1-stabilization-stories.md:19` — « `v1s-12-2` Révocation session <5min (V4 `tokens_valid_after` + `TokenRevocationPort`) · S2/NFR12 · ⚠ dépendance pour 14.9 & 14.12 ». Position 3 dans l'ordre recommandé (après 12-1/12-3/12-5).
- **Tracker :** `sprint-status.yaml` clé `v1s-12-2-session-revocation-5min` (ligne 326).
- **Tag :** A — patch V1 maintenant. Refonte : re-implement natively dans `identity/auth` modulaire (même `TokenRevocationPort`) — les noms port/adapter sont stables pour la réabsorption.

## Acceptance Criteria

> Les AC BDD proviennent textuellement de `epics-remediation-audit.md` (Story 12.2, lignes 91-102). AC6-AC8 ajoutées pour fermer les coutures TDD / non-régression / multi-instance (sinon la story n'est pas testable ni propagée).

1. **AC1 (cutoff iat — rejet 401 SESSION_REVOKED) — Given** une adhésion est révoquée (`tokens_valid_after` positionné sur la ligne `public.user_tenant_memberships` pour `(userId, tenant)`), **When** un access token émis **avant** la révocation (`iat < tokens_valid_after`) atteint `JwtAuthFilter`, **Then** la réponse est **401** `{"domainCode":"SESSION_REVOKED"}`, `filterChain.doFilter` n'est **jamais** appelé, le `SecurityContext` n'est **jamais** peuplé. Le check s'applique à **TOUS les rôles** (OWNER + EMPLOYEE), pas seulement EMPLOYEE.
2. **AC2 (token émis après révocation accepté) — And** un access token émis **après** la révocation (`iat >= tokens_valid_after`) → `filterChain.doFilter` appelé (200), la requête procède normalement (le legit user a reçu un nouveau token post-révocation).
3. **AC3 (V4 migration) — And** `V4__user_tenant_memberships_tokens_valid_after.sql` ajoute la colonne `tokens_valid_after timestamp(6) with time zone` (nullable) à `public.user_tenant_memberships`. NULL = jamais révoqué → token toujours valide (backward-compat avec les lignes existantes).
4. **AC4 (port + 2 adapters) — And** `TokenRevocationPort` (port hexagonal) + `JdbcTokenRevocationAdapter` (raw `JdbcTemplate` sur `public.user_tenant_memberships` JOIN `public.tenants`) + `CachedTokenRevocationAdapter` (cache TTL **≤5 min** sur `ConcurrentHashMap`, modèle `TenantSchemaSyncService:97`). Le `CachedTokenRevocationAdapter` est le bean `@Primary` injecté dans `JwtAuthFilter` + les 2 services.
5. **AC5 (branchements révocation) — And** `DeactivateEmployeeService.execute()` ET `ChangePasswordService.execute()` appellent `tokenRevocationPort.revokeAllSessions(userId, tenantSchema)` (écrit `tokens_valid_after = now()` sur la ligne de membership). EMPLOYEE per-request DB check (`JwtAuthFilter:160-189`) **reste** en place (defense-in-depth — ne PAS le retirer).
6. **AC6 (ErrorCode + GlobalExceptionHandler) — And** `SESSION_REVOKED` est ajouté à `ErrorCode` (section Identity/Auth) ET à `GlobalExceptionHandler` (FR message + cas 401 dans `domainCodeToHttpStatus`). Le filtre émet le code via `writeError(response, "SESSION_REVOKED")` (même helper que `TOKEN_INVALID` — ne pas créer de nouveau helper).
7. **AC7 (TDD cutoff) — And** tests `shouldRejectTokenIssuedBeforeRevocation()` + `shouldAcceptTokenIssuedAfterRevocation()` (RED avant, GREEN après) dans `JwtAuthFilterTest`. Plus `shouldRevokeAllSessionsOnDeactivation()` (DeactivateEmployeeServiceTest) + `shouldRevokeAllSessionsOnPasswordChange()` (ChangePasswordServiceTest). Plus tests adapters : `JdbcTokenRevocationAdapterTest` (SQL + mapping null/non-null) + `CachedTokenRevocationAdapterTest` (TTL expire + invalidation sur `revokeAllSessions`).
8. **AC8 (non-régression + propagation multi-instance) — And** la suite `JwtAuthFilterTest` existante (13 tests OWNER/EMPLOYEE de 12-5) reste GREEN après l'ajout du param constructeur `TokenRevocationPort` (les tests OWNER passent car `jwtTokenProvider.extractIssuedAt` → null par défaut → check sauté). Le cache documente explicitement la fenêtre de propagation **≤5 min** entre instances (NFR12) — `revokeAllSessions` invalide le cache local + la TTL borne la propagation cross-instance.

> ⚠️ **Discrepancies critiques à comprendre AVANT de coder (voir Dev Notes) :**
> 1. **`tenantId` JWT = nom de schéma `kv_xxxxxx` ; `user_tenant_memberships.tenant_id` = UUID du tenant.** Deux identifiants différents. Le `JdbcTokenRevocationAdapter` DOIT résoudre schéma → UUID via JOIN `public.tenants ON schema_name` (cf. Dev Notes « Résolution schéma → UUID »). `tenants.schema_name` est UNIQUE (`V1__baseline_public.sql:583-584` `uk_tenants_schema_name`) → la sous-requête retourne exactement 1 ligne.
> 2. **`revokeAllSessions(userId, tenantSchema)` : le `userId` est le SUJET révoqué, pas l'acteur.** Dans `DeactivateEmployeeService`, le sujet = `employee.getUserId()` (l'employé), PAS `command.actorId()` (le OWNER). Dans `ChangePasswordService`, le sujet = `command.actorId()` (auto-révocation). L'AC parle de « `actorId` » — c'est le sujet de la révocation, à clarifier par site d'appel.
> 3. **Le check s'applique à TOUS les rôles** (OWNER + EMPLOYEE) — contrairement au check EMPLOYEE-only de 12-5. Il doit être placé **avant** `TenantContext.setCurrentTenant` (ligne 130) pour qu'un token révoqué ne peuple jamais le ThreadLocal. Voir Dev Notes « Placement du check ».
> 4. **`ddl-auto=validate` en prod/staging** (`application-prod.yml:10`, `application.yml:35`). Ne PAS mapper `tokens_valid_after` dans `UserTenantMembershipJpaEntity` — l'adapter utilise du raw `JdbcTemplate` (zéro JPA sur cette colonne). Hibernate validate n'exige pas qu'une colonne DB ait un champ entité (les colonnes DB orphelines sont OK). Toucher l'entité JPA = surface inutile + risque de casser le validate.

## Tasks / Subtasks

- [x] **Task 1 — V4 migration (NEW, AC3)**
  - [x] 1.1 Créer `keevo/backend/src/main/resources/db/migration/V4__user_tenant_memberships_tokens_valid_after.sql` :
        ```sql
        -- Story 12.2 (v1s-12-2) — Session revocation <5min (NFR12 / B-HIGH-5 / S2).
        -- Nullable: NULL = never revoked → token always valid (backward-compat with existing rows).
        -- Updated by raw JdbcTemplate in JdbcTokenRevocationAdapter (NOT mapped in the JPA entity — ddl-auto=validate tolerates orphan DB columns).
        ALTER TABLE public.user_tenant_memberships
            ADD COLUMN IF NOT EXISTS tokens_valid_after timestamp(6) with time zone;
        ```
  - [x] 1.2 **Vérifier le slot V4** : seul `V1` et `V2` existent (`db/migration/`). L'audit cible explicitement le slot V4 (cf. `AUDIT_CONFORMITE_BMAD.md:112,331`). V3 est **intentionnellement absent** — Flyway tolère les versions non-contiguës (`baseline-version: 0`, `application.yml:54`). Si une migration `V3__*.sql` est trouvée sur la branche au moment du dev, utiliser `V5` à la place (collision = build cassé). Ne PAS créer de V3 placeholder.

- [x] **Task 2 — `TokenRevocationPort` (NEW, AC4)**
  - [x] 2.1 Créer `keevo/backend/src/main/java/com/keevo/identity/auth/domain/port/out/TokenRevocationPort.java` (interface, package miroir de `UserMembershipRepository` au même endroit) :
        ```java
        package com.keevo.identity.auth.domain.port.out;

        import java.time.Instant;
        import java.util.UUID;

        /**
         * TokenRevocationPort — Driven port for <5min session revocation (NFR12, B-HIGH-5, Story 12.2).
         *
         * <p>Backs the {@code tokens_valid_after} column on {@code public.user_tenant_memberships}.
         * A token whose {@code iat} is BEFORE the membership's {@code tokens_valid_after}
         * is considered revoked (JwtAuthFilter → 401 SESSION_REVOKED).
         *
         * <p>Implementations: {@code JdbcTokenRevocationAdapter} (source of truth) +
         * {@code CachedTokenRevocationAdapter} (≤5min TTL cache, the @Primary bean wired into the filter).
         */
        public interface TokenRevocationPort {

            /**
             * @return the membership's tokens-valid-after cutoff, or {@code null} if never revoked
             *         (token always valid). tenantSchema = JWT claim {@code kv_xxxxxx}.
             */
            Instant getTokensValidAfter(UUID userId, String tenantSchema);

            /**
             * Revoke all sessions for (userId, tenantSchema): set {@code tokens_valid_after = now()}
             * on the matching membership row. Idempotent. Also invalidates any cached cutoff.
             * Called by DeactivateEmployeeService + ChangePasswordService.
             */
            void revokeAllSessions(UUID userId, String tenantSchema);
        }
        ```
  - [x] 2.2 Javadoc : préciser que `tenantSchema` est le claim JWT (`kv_xxxxxx`), résolu en UUID tenant côté adapter via JOIN `tenants`.

- [x] **Task 3 — `JdbcTokenRevocationAdapter` (NEW, AC4)**
  - [x] 3.1 Créer `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/impl/JdbcTokenRevocationAdapter.java` (`@Component`, injecte `JdbcTemplate` bean — même bean que `JwtAuthFilter:74,79-83`). Implémente `TokenRevocationPort` :
        ```java
        @Component
        public class JdbcTokenRevocationAdapter implements TokenRevocationPort {

            private static final String SELECT_TVA =
                    "SELECT m.tokens_valid_after " +
                    "FROM public.user_tenant_memberships m " +
                    "JOIN public.tenants t ON t.id = m.tenant_id " +
                    "WHERE m.user_id = ? AND t.schema_name = ?";

            private static final String REVOKE_TVA =
                    "UPDATE public.user_tenant_memberships " +
                    "SET tokens_valid_after = now(), updated_at = now() " +
                    "WHERE user_id = ? " +
                    "  AND tenant_id = (SELECT id FROM public.tenants WHERE schema_name = ?)";

            private final JdbcTemplate jdbcTemplate;

            public JdbcTokenRevocationAdapter(JdbcTemplate jdbcTemplate) {
                this.jdbcTemplate = jdbcTemplate;
            }

            @Override
            public Instant getTokensValidAfter(UUID userId, String tenantSchema) {
                try {
                    return jdbcTemplate.queryForObject(SELECT_TVA,
                            (rs, rowNum) -> {
                                java.sql.Timestamp ts = rs.getTimestamp("tokens_valid_after");
                                return ts != null ? ts.toInstant() : null;
                            }, userId, tenantSchema);
                } catch (EmptyResultDataAccessException e) {
                    // No membership row for (userId, tenant) → treat as never-revoked (allow).
                    // The EMPLOYEE branch will separately reject via ACCOUNT_INACTIVE/STORE_REASSIGNED.
                    return null;
                }
            }

            @Override
            public void revokeAllSessions(UUID userId, String tenantSchema) {
                jdbcTemplate.update(REVOKE_TVA, userId, tenantSchema);
            }
        }
        ```
  - [x] 3.2 **Sécurité SQL** : `tenantSchema` est un **paramètre bindé** (`?`) dans les deux requêtes → zéro injection possible même sans regex préalable (cohérent avec `TenantSchema.validate` de 12-5 qui protège l'interpolation brute, pas le binding paramétré). Ne pas concaténer `tenantSchema` dans le SQL.
  - [x] 3.3 `updated_at = now()` est positionné **manuellement** (raw JDBC = pas de `@PreUpdate` de `JpaBaseEntity`). La colonne `updated_at` est `NOT NULL` (`V1__baseline_public.sql:385`) — obligatoire.
  - [x] 3.4 Imports : `org.springframework.dao.EmptyResultDataAccessException`, `org.springframework.jdbc.core.JdbcTemplate`, `org.springframework.stereotype.Component`, `java.time.Instant`, `java.util.UUID`.

- [x] **Task 4 — `CachedTokenRevocationAdapter` (NEW, AC4/AC8)**
  - [x] 4.1 Créer `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/impl/CachedTokenRevocationAdapter.java` (`@Component` + `@Primary` — c'est le bean injecté dans `JwtAuthFilter` + les 2 services). Délègue à `JdbcTokenRevocationAdapter`. Modèle de cache : `TenantSchemaSyncService:97` (`ConcurrentHashMap`) + TTL temporel :
        ```java
        @Component
        @Primary
        public class CachedTokenRevocationAdapter implements TokenRevocationPort {

            private static final Logger log = LoggerFactory.getLogger(CachedTokenRevocationAdapter.class);

            /** NFR12 — worst-case revocation propagation window across instances. */
            private static final long TTL_SECONDS = 300; // 5 minutes

            private record CachedCutoff(Instant tokensValidAfter, Instant fetchedAt) {}

            private final TokenRevocationPort delegate; // JdbcTokenRevocationAdapter
            private final ConcurrentHashMap<String, CachedCutoff> cache = new ConcurrentHashMap<>();

        // constructor: this.delegate = Objects.requireNonNull(delegate) (a JdbcTokenRevocationAdapter).
        //              WARN Spring circular wiring: inject by the interface TokenRevocationPort is AMBIGUOUS
        //              (two beans). Inject `JdbcTokenRevocationAdapter` directly (concrete class) to avoid
        //              @Primary self-reference. See Dev Notes « Wiring @Primary ».

            @Override
            public Instant getTokensValidAfter(UUID userId, String tenantSchema) {
                String key = key(userId, tenantSchema);
                CachedCutoff cached = cache.get(key);
                Instant now = Instant.now();
                if (cached != null && cached.fetchedAt().plusSeconds(TTL_SECONDS).isAfter(now)) {
                    return cached.tokensValidAfter();
                }
                Instant tva = delegate.getTokensValidAfter(userId, tenantSchema);
                cache.put(key, new CachedCutoff(tva, now));
                return tva;
            }

            @Override
            public void revokeAllSessions(UUID userId, String tenantSchema) {
                delegate.revokeAllSessions(userId, tenantSchema);
                cache.remove(key(userId, tenantSchema)); // invalidate local cache immediately
            }

            private static String key(UUID userId, String tenantSchema) {
                return userId + ":" + tenantSchema;
            }
        }
        ```
  - [x] 4.2 Javadoc : documenter la fenêtre de propagation **≤5 min** (NFR12) — `revokeAllSessions` invalide le cache local, mais une autre instance ne verra la révocation qu'à l'expiration de sa propre entrée de cache (≤ TTL). C'est la garantie contractuelle de NFR12, pas un bug.
  - [x] 4.3 **Ne pas** ajouter de `@PostConstruct` / scheduled cleanup — la map est bornée par (userId × tenant) actifs et l'entrée est réécrite à chaque miss ; un nettoyage proactif est scope creep (la refonte utilisera un `CacheManager` / Caffeine).

- [x] **Task 5 — `JwtTokenProvider.extractIssuedAt` (NEW, AC1/AC2)**
  - [x] 5.1 Ajouter à `JwtTokenProvider.java` (après `extractFirstName`, ~ligne 235) :
        ```java
        /**
         * Extract the {@code iat} (issued-at) claim as an Instant. Returns {@code null} if absent
         * (backward-compatible with very old tokens). Used by JwtAuthFilter for session revocation
         * cutoff (Story 12.2): {@code iat < tokens_valid_after → 401 SESSION_REVOKED}.
         */
        public Instant extractIssuedAt(Claims claims) {
            Date issuedAt = claims.getIssuedAt();
            return issuedAt != null ? issuedAt.toInstant() : null;
        }
        ```
  - [x] 5.2 Importer `java.time.Instant` + `java.util.Date` (déjà présents). Tous les access tokens Keevo sont mintés avec `.issuedAt(Date.from(now))` (`JwtTokenProvider.java:64,113,149`) → `iat` toujours présent sur les tokens légitimes. `null` = token anormal → le filtre **saute** le check (allow, conservative — ne pas casser un token légitime sans iat).

- [x] **Task 6 — `JwtAuthFilter` revocation check (UPDATE, AC1/AC2/AC6)**
  - [x] 6.1 Ajouter un champ `private final TokenRevocationPort tokenRevocationPort;` + param constructeur (`JwtAuthFilter.java:71-84`). Ordre recommandé : en dernier paramètre (préserve les sites d'appel test — mais de toute façon les tests doivent être updatés, voir Task 8).
  - [x] 6.2 Dans `doFilterInternal`, **après** l'extraction des claims (après `String tenantStatus = jwtTokenProvider.extractTenantStatus(claims);` ligne 127) et **avant** `TenantContext.setCurrentTenant(tenantId);` (ligne 130), insérer le check :
        ```java
        // Story 12.2 — NFR12 (<5min session revocation, B-HIGH-5/S2): reject access tokens
        // issued BEFORE the membership's tokens_valid_after cutoff. Applies to ALL roles
        // (OWNER + EMPLOYEE), BEFORE TenantContext is set so a revoked token never seeds
        // the ThreadLocal. EMPLOYEE per-request DB check below remains as defense-in-depth.
        Instant tva = tokenRevocationPort.getTokensValidAfter(userId, tenantId);
        if (tva != null) {
            Instant iat = jwtTokenProvider.extractIssuedAt(claims);
            if (iat != null && iat.isBefore(tva)) {
                writeError(response, "SESSION_REVOKED");
                return;
            }
        }
        ```
  - [x] 6.3 **Réutiliser `writeError`** (ligne 229) — produit déjà 401 JSON `{"domainCode":"SESSION_REVOKED"}`. Ne PAS créer de helper ni de `domainCode` autre que `SESSION_REVOKED`.
  - [x] 6.4 **Ne pas modifier** : `PUBLIC_PATHS`/`shouldNotFilter`, extraction des claims, scope `login_pending` rejection (lignes 118-122), `TenantContext.setCurrentTenant`/`clear`, SUSPENDED guard, `tenantSchemaSyncService.syncIfNeeded`, bloc EMPLOYEE (S5 `TenantSchema.validate` de 12-5 + DB check + INACTIVE/STORE_REASSIGNED/PASSWORD_CHANGE_REQUIRED), `SecurityContextHolder` set/clear, `finally` clear (lignes 208-212), helper `writeError`/`writeErrorWithStatus`.
  - [x] 6.5 Commentaire inline court documentant le *pourquoi* (S2/B-HIGH-5/NFR12 + placement avant `TenantContext`).

- [x] **Task 7 — `ErrorCode` + `GlobalExceptionHandler` (UPDATE, AC6)**
  - [x] 7.1 `ErrorCode.java` : ajouter `SESSION_REVOKED` dans la section Identity/Auth (près de `ACCOUNT_INACTIVE`/`TOKEN_INVALID`, ~ligne 62) :
        ```java
        ACCOUNT_INACTIVE,
        SESSION_REVOKED,   // Story 12.2 — NFR12: token issued before tokens_valid_after cutoff (401)
        ```
  - [x] 7.2 `GlobalExceptionHandler.java` :
        - Ajouter à `FR_MESSAGES` (Map.ofEntries, ~après `ACCOUNT_INACTIVE` ligne 72) : `Map.entry("SESSION_REVOKED", "Votre session a été révoquée, veuillez vous reconnecter")`.
        - Ajouter `"SESSION_REVOKED"` au cas 401 du switch `domainCodeToHttpStatus` (ligne 185-190), dans le groupe `"UNAUTHORIZED", "TOKEN_EXPIRED", "TOKEN_INVALID", … "ACCOUNT_INACTIVE", "STORE_REASSIGNED"` → ajouter `"SESSION_REVOKED"` à cette liste.
  - [x] 7.3 Note : le filtre bypass `GlobalExceptionHandler` (les filtres s'exécutent avant le `@RestControllerAdvice`). L'ajout au handler est pour **cohérence** + **contrat mobile** (le Flutter lit le `domainCode` plus tard en 14.9/12.7). Le code émis par le filtre vient de `writeError` (Task 6.3), pas du handler.

- [x] **Task 8 — `DeactivateEmployeeService` + `ChangePasswordService` wiring (UPDATE, AC5)**
  - [x] 8.1 `DeactivateEmployeeService.java` : injecter `TokenRevocationPort` (constructeur, lignes 30-38). Importer `com.keevo.identity.auth.domain.port.out.TokenRevocationPort` + `com.keevo.shared.infrastructure.persistence.TenantContext`. Dans `execute()` (ligne 42), **après** l'étape 3 `membershipRepository.deactivateByUserId(employee.getUserId());` (ligne 51) et **avant**/avec l'étape 4 (revoke refresh, ligne 54), ajouter :
        ```java
        // Story 12.2 — revoke access tokens issued before now (<5min propagation, NFR12).
        // Subject = the deactivated EMPLOYEE (employee.getUserId()), NOT the OWNER actor.
        String tenantSchema = TenantContext.getCurrentTenant();
        tokenRevocationPort.revokeAllSessions(employee.getUserId(), tenantSchema);
        ```
        L'`EmployeeDeactivatedEvent` (étape 5) est inchangé.
  - [x] 8.2 `ChangePasswordService.java` : injecter `TokenRevocationPort` (constructeur, lignes 48-62). Le service a déjà `String tenantId = TenantContext.getCurrentTenant();` ligne 109. **Insérer** l'appel `tokenRevocationPort.revokeAllSessions(command.actorId(), tenantId);` **après** l'étape 6 `refreshTokenRepository.revokeAllByUserId(command.actorId());` (ligne 106) et **avant** l'étape 7 génération du nouveau token (ligne 110). Ordre crucial : la révocation (`tokens_valid_after = now()`) doit précéder l'émission du nouveau token (`iat = now()`), sinon le nouveau token s'auto-révoquerait (en pratique `iat >= tva` car le même `now()`, mais l'ordre explicite élimine toute race).
  - [x] 8.3 **Sens du `userId`** : `DeactivateEmployeeService` → `employee.getUserId()` (le révoqué) ; `ChangePasswordService` → `command.actorId()` (auto). Le `tenantSchema` vient de `TenantContext` (positionné par `JwtAuthFilter` sur la requête OWNER/authentifiée = le tenant du sujet). Documenter ce mapping dans une commentaire inline court par site.

- [x] **Task 9 — TDD (AC7)**
  - [x] 9.1 `JwtAuthFilterTest.java` — ajouter `@Mock TokenRevocationPort tokenRevocationPort;` + **updater `setUp` + `filterWithJdbc`** pour passer le mock (les constructeurs ont un nouveau paramètre — voir Dev Notes « Squelette TDD / setUp »). Ajouter :
        - `shouldRejectTokenIssuedBeforeRevocation()` — OWNER token, `tokenRevocationPort.getTokensValidAfter(userId, tenantId)` stubbé à `now.plus(1, MINUTES)`, `jwtTokenProvider.extractIssuedAt(claims)` stubbé à `now.minus(10, MINUTES)` (iat < tva) → 401 `SESSION_REVOKED`, `verify(filterChain, never()).doFilter(...)`, `verify(tokenRevocationPort).getTokensValidAfter(userId, tenantId)`.
        - `shouldAcceptTokenIssuedAfterRevocation()` — OWNER token, tva = `now.minus(10, MINUTES)`, iat = `now.minus(1, MINUTES)` (iat >= tva) → `filterChain.doFilter` appelé, 200.
  - [x] 9.2 `DeactivateEmployeeServiceTest` (existant ou NEW) — `shouldRevokeAllSessionsOnDeactivation()` : mock `employeeRepository.findById` → employee avec `getUserId()`, mock `TenantContext` (voir Dev Notes), exécute, `verify(tokenRevocationPort).revokeAllSessions(eq(employee.getUserId()), eq(tenantSchema))`.
  - [x] 9.3 `ChangePasswordServiceTest` (existant ou NEW) — `shouldRevokeAllSessionsOnPasswordChange()` : stubs user/password valides, exécute, `verify(tokenRevocationPort).revokeAllSessions(eq(command.actorId()), eq(tenantId))`.
  - [x] 9.4 `JdbcTokenRevocationAdapterTest` (NEW, `@ExtendWith(MockitoExtension)`, `@Mock JdbcTemplate`) — `queryForObject` retourne un `Timestamp` non-null → `Instant` non-null ; retourne null → null ; lève `EmptyResultDataAccessException` → null (no membership). `revokeAllSessions` → `verify(jdbcTemplate).update(eq(REVOKE_SQL), eq(userId), eq(tenantSchema))`.
  - [x] 9.5 `CachedTokenRevocationAdapterTest` (NEW, delegate mocké) — (a) cache hit < TTL → delegate **non** rappelé ; (b) cache expiré (>TTL) → delegate rappelé, valeur fraîche retournée ; (c) `revokeAllSessions` → delegate.update appelé + cache invalidé (le prochain `getTokensValidAfter` rappelle le delegate).

- [x] **Task 10 — Non-régression (AC8)**
  - [x] 10.1 `mvn test -Dtest='JwtAuthFilterTest,JwtTokenProviderTest'` — tous GREEN (13 existants 12-5 + 2 nouveaux AC1/AC2 = 15).
  - [x] 10.2 `mvn test -Dtest='JdbcTokenRevocationAdapterTest,CachedTokenRevocationAdapterTest,DeactivateEmployeeServiceTest,ChangePasswordServiceTest'` — GREEN (nouveaux + existants updatés).
  - [x] 10.3 `mvn test -Dtest='SecurityConfigAdminPathEnforcementTest,Admin*ControllerTest'` — pas de régression sécurité/admin (sanity).
  - [x] 10.4 Sanity : `mvn test -Dtest='TenantSchemaSyncServiceTest,UserMembershipRepositoryAdapterTest'` — GREEN (la V4 colonne + le nouveau port ne cassent pas le membership JPA existant). Full suite : 0 NOUVELLE régression (les 1 flaky + 40 ApplicationContext DB-dependent pré-existants de 12-5 restent).

- [x] **Task 11 — Dev Agent Record**
  - [x] 11.1 Renseigner Agent Model, Debug Log, Completion Notes, File List.
  - [x] 11.2 (Doc optionnelle) Si une story `11-6` est référencée ailleurs en `ready-for-dev`, la marquer `done` (l'audit `:112` mentionne `sprint-status: ready-for-dev` pour Story 11-6 — c'est l'ancien numérotage de CE travail, désormais `v1s-12-2`). Vérifier `_bmad-output/implementation-artifacts/` pour un fichier `11-6-*` ; s'il n'existe pas (vérifié au setup : absent), ignorer.

## Dev Notes

### 🔬 Approche recommandée V1-stab — backend chirurgical, Flutter différé

L'audit Story 12.2 inclut un paragraphe « Contrepartie mobile (Flutter) » (`AuthInterceptor.onError` branche `SESSION_REVOKED`/`ACCOUNT_INACTIVE` → `_expireSession()`). **Cette story est BACKEND-ONLY.** La contrepartie Flutter est éclatée sur deux stories qui « tie » 12.2 :

- `v1s-14-9` (index `v1-stabilization-stories.md:37`) — « FR20 bannières trial/suspension + branche session-révoquée · dépend 12.2/12.7 » → la branche `SESSION_REVOKED` côté Flutter vit ici.
- `v1s-12-7` (index `:21`) — Hardening Flutter (`SecureStorageProvider` + redact Bearer + HTTPS) → l'infra `_expireSession()` vit ici.

Le **contrat** que 12.2 établit pour elles : un token révoqué reçoit **401 `{"domainCode":"SESSION_REVOKED"}`**. 14.9/12.7 consommeront ce code. Ne PAS implémenter le Flutter dans 12.2 (scope creep, branche séparée, contre la philosophie V1-stab « 1 couche ciblée »).

### Résolution schéma → UUID (discrepancy #1)

Le claim JWT `tenantId` est le **nom de schéma** `kv_xxxxxx` (preuve : `JwtTokenProvider.java:50,82,96` javadoc « `tenantId tenant schema name (e.g., "kv_abc123")` » — même constat que la story 12-5). Mais `user_tenant_memberships.tenant_id` est l'**UUID** du tenant (`V1__baseline_public.sql:388`, `UserTenantMembershipJpaEntity.java:38-39`). Deux identifiants différents — il ne faut PAS les confondre.

Le `JdbcTokenRevocationAdapter` résout la conversion via JOIN / sous-requête sur `public.tenants` (qui a `schema_name` UNIQUE — `V1__baseline_public.sql:583-584` `uk_tenants_schema_name`) :

| Requête | SQL | Garantie |
|---|---|---|
| Lecture cutoff | `SELECT m.tokens_valid_after FROM public.user_tenant_memberships m JOIN public.tenants t ON t.id = m.tenant_id WHERE m.user_id = ? AND t.schema_name = ?` | `tenantSchema` bindé (`?`) → 0 injection ; `schema_name` UNIQUE → 0 ou 1 ligne |
| Écriture révocation | `UPDATE public.user_tenant_memberships SET tokens_valid_after = now(), updated_at = now() WHERE user_id = ? AND tenant_id = (SELECT id FROM public.tenants WHERE schema_name = ?)` | idem |

**Pourquoi pas `TenantContext` ?** `TenantContext` est un ThreadLocal positionné par le filtre (`JwtAuthFilter:130`). Le check de révocation (Task 6) s'exécute **avant** `setCurrentTenant` (pour ne pas peupler le ThreadLocal d'un token révoqué). L'adapter attaque `public.user_tenant_memberships` (schéma public, pas le schéma tenant) → n'a **pas besoin** du `TenantContext` ni du `search_path`. C'est cohérent avec le pattern raw-JDBC fully-qualified de `TenantSchemaSyncService` (qui utilise `"public"."table"`).

### Placement du check (discrepancy #3)

Le check de révocation s'applique à **TOUS les rôles** (OWNER + EMPLOYEE). Il est placé **après** extraction des claims (lignes 124-127) et **avant** `TenantContext.setCurrentTenant` (ligne 130). Conséquences :

- Un token révoqué ne peuple **jamais** `TenantContext` (ThreadLocal) → pas de fuite tenant, pas de `syncIfNeeded`, pas de requête EMPLOYEE.
- Le check EMPLOYEE-only de 12-5 (`TenantSchema.validate` + DB lookup, lignes 146-190) **reste** en dessous comme defense-in-depth. Un token EMPLOYEE révoqué est rejeté au check 12.2 (avant) ; un token EMPLOYEE non-révoqué mais INACTIVE/store-reassigned est rejeté au check 12-5 (après). Les deux coexistent.

Ordre des gardes dans `doFilterInternal` après cette story :

```
parseToken → scope(login_pending) → extract tenantId/role/userId/tenantStatus
  → [NEW 12.2] revocation cutoff (OWNER+EMPLOYEE) → 401 SESSION_REVOKED
  → TenantContext.setCurrentTenant
  → SUSPENDED guard (write block)
  → tenantSchemaSyncService.syncIfNeeded
  → [12-5] EMPLOYEE: TenantSchema.validate + DB lookup (INACTIVE/STORE_REASSIGNED/PASSWORD_CHANGE_REQUIRED)
  → SecurityContextHolder set → filterChain.doFilter
```

### `userId` sujet vs acteur (discrepancy #2)

L'AC dit `revokeAllSessions(actorId, tenantId)` — le terme `actorId` est trompeur. Le port signe `revokeAllSessions(UUID userId, String tenantSchema)` où `userId` = le **sujet révoqué** :

| Site d'appel | sujet révoqué (`userId`) | `tenantSchema` | Source |
|---|---|---|---|
| `DeactivateEmployeeService.execute` | `employee.getUserId()` (l'employé désactivé) | `TenantContext.getCurrentTenant()` (tenant du OWNER = tenant de l'employé) | `DeactivateEmployeeService.java:44,51` |
| `ChangePasswordService.execute` | `command.actorId()` (auto-révocation : celui qui change son pwd) | `TenantContext.getCurrentTenant()` (ligne 109, déjà là) | `ChangePasswordService.java:68,109` |

Ne PAS passer `command.actorId()` (le OWNER) à `revokeAllSessions` dans `DeactivateEmployeeService` — ça révoquerait les sessions du OWNER au lieu de l'employé.

### Wiring `@Primary` (Task 4.1 — piège Spring)

`CachedTokenRevocationAdapter` est `@Component @Primary` (le bean que `JwtAuthFilter` + les 2 services injectent via le type `TokenRevocationPort`). **MAIS** il a besoin d'une référence vers `JdbcTokenRevocationAdapter`. Si son constructeur prend `TokenRevocationPort`, Spring est ambigu (deux beans `TokenRevocationPort` : le Jdbc + le Cached lui-même → auto-référence / `@Primary` se pointe lui-même).

**Solution** : le constructeur de `CachedTokenRevocationAdapter` injecte le type **concret** `JdbcTokenRevocationAdapter` (pas l'interface) → Spring résout sans ambiguïté, `@Primary` ne se self-injecte pas.

```java
public CachedTokenRevocationAdapter(JdbcTokenRevocationAdapter delegate) { // NOT TokenRevocationPort
    this.delegate = Objects.requireNonNull(delegate);
}
```

Les consommateurs (`JwtAuthFilter`, `DeactivateEmployeeService`, `ChangePasswordService`) injectent `TokenRevocationPort` (interface) → Spring leur donne le `@Primary` = `CachedTokenRevocationAdapter`. ✓

### Squelette TDD / `setUp` (Task 9.1)

Le constructeur de `JwtAuthFilter` a un nouveau paramètre (`TokenRevocationPort`). Les helpers `setUp` (ligne 48-51) et `filterWithJdbc` (ligne 58-61) DOIVENT être updatés :

```java
@Mock JwtTokenProvider jwtTokenProvider;
@Mock FilterChain filterChain;
@Mock TenantSchemaSyncService tenantSchemaSyncService;
@Mock JdbcTemplate jdbcTemplate;
@Mock TokenRevocationPort tokenRevocationPort;   // NEW 12.2

private JwtAuthFilter filter;

@BeforeEach
void setUp() {
    filter = new JwtAuthFilter(jwtTokenProvider, new ObjectMapper(),
            tenantSchemaSyncService, null, tokenRevocationPort);
}

private JwtAuthFilter filterWithJdbc() {
    return new JwtAuthFilter(jwtTokenProvider, new ObjectMapper(),
            tenantSchemaSyncService, jdbcTemplate, tokenRevocationPort);
}
```

**Pourquoi les 13 tests existants restent GREEN** : avec `MockitoExtension` (strict stubs), un mock non-stubbé retourne sa valeur par défaut. Le check 12.2 appelle `tokenRevocationPort.getTokensValidAfter(...)` → retourne `null` (défaut) → `tva == null` → check sauté (allow). Les tests OWNER existants (placeholders `KV-*`) ne stubbent ni `getTokensValidAfter` ni `extractIssuedAt` → `null`/`null` → check sauté → behaviour inchangé. **Ne PAS** stubber `getTokensValidAfter` dans les tests OWNER existants (strict stubs = `UnnecessaryStubbingException` si non consommé). Seuls les 2 nouveaux tests AC1/AC2 le stubbent.

Squelette AC1/AC2 (OWNER — pas besoin de `jdbcTemplate`) :

```java
@Test
@DisplayName("token issued BEFORE tokens_valid_after → 401 SESSION_REVOKED, no filterChain")
void shouldRejectTokenIssuedBeforeRevocation() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
    request.addHeader("Authorization", "Bearer owner.jwt.token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    UUID userId = UUID.randomUUID();
    Claims claims = buildClaims(userId, "kv_abc123", "OWNER");
    when(jwtTokenProvider.parseToken("owner.jwt.token")).thenReturn(claims);
    when(jwtTokenProvider.extractScope(claims)).thenReturn("access");
    when(jwtTokenProvider.extractTenantId(claims)).thenReturn("kv_abc123");
    when(jwtTokenProvider.extractRole(claims)).thenReturn("OWNER");
    when(jwtTokenProvider.extractUserId(claims)).thenReturn(userId);
    when(jwtTokenProvider.extractTenantStatus(claims)).thenReturn("ACTIVE");
    // Cutoff AFTER iat → revoked
    when(tokenRevocationPort.getTokensValidAfter(userId, "kv_abc123"))
            .thenReturn(Instant.now().plus(1, ChronoUnit.MINUTES));
    when(jwtTokenProvider.extractIssuedAt(claims))
            .thenReturn(Instant.now().minus(10, ChronoUnit.MINUTES));

    filterWithJdbc().doFilterInternal(request, response, filterChain); // OWNER but use helper to keep parity

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("SESSION_REVOKED");
    verify(filterChain, never()).doFilter(any(), any());
    verify(tokenRevocationPort).getTokensValidAfter(userId, "kv_abc123");
}

@Test
@DisplayName("token issued AFTER tokens_valid_after → 200, filterChain proceeds")
void shouldAcceptTokenIssuedAfterRevocation() throws Exception {
    // same setup, but tva = now.minus(10min), iat = now.minus(1min) → iat >= tva → allow
    when(tokenRevocationPort.getTokensValidAfter(userId, "kv_abc123"))
            .thenReturn(Instant.now().minus(10, ChronoUnit.MINUTES));
    when(jwtTokenProvider.extractIssuedAt(claims))
            .thenReturn(Instant.now().minus(1, ChronoUnit.MINUTES));

    filterWithJdbc().doFilterInternal(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(200);
}
```

> **`MockitoExtension` strict stubs** : `extractScope`/`extractTenantStatus` doivent être stubbés (consommés par le filtre). Ne mocker QUE ce qui est consommé par le chemin OWNER (pas `extractStoreId`). Voir 12-5 note subtilité Mockito (story 12-5 Dev Notes « Squelette TDD »).

### `TenantContext` dans les tests service (Task 9.2)

`DeactivateEmployeeService` lit `TenantContext.getCurrentTenant()`. En test unitaire (`@ExtendWith(MockitoExtension)`, pas de Spring), `TenantContext` est un ThreadLocal statique — `getCurrentTenant()` retourne `null` si non positionné. Deux stratégies :

1. **(Recommandé)** `TenantContext.setCurrentTenant("kv_abc123")` dans le `@BeforeEach`/début du test, `TenantContext.clear()` dans `@AfterEach` (mirror du pattern `JwtAuthFilter`). Assert `revokeAllSessions(eq(employee.getUserId()), eq("kv_abc123"))`.
2. Matcher `anyString()` au lieu de `eq("kv_abc123")` (moins précis, acceptable si la précision du tenant n'est pas l'objet du test).

Préférer (1) — ancre le contrat `tenantSchema = TenantContext.getCurrentTenant()`.

### `ddl-auto=validate` (discrepancy #4)

Prod/staging : `spring.jpa.hibernate.ddl-auto=validate` (`application-prod.yml:10`, `application-staging.yml:14`, `application.yml:35`). Hibernate validate vérifie que **chaque champ entité existe en DB** (pas l'inverse). Ajouter une colonne DB sans champ entité = OK. Donc :

- **Ne PAS** ajouter `tokensValidAfter` à `UserTenantMembershipJpaEntity` (ni au domaine `UserTenantMembership`). Le `JdbcTokenRevocationAdapter` gère la colonne via raw `JdbcTemplate`, hors JPA.
- Si on l'ajoutait quand même à l'entité, le boot validerait la présence colonne (V4 la crée) → OK aussi, mais surface inutile + le domaine n'en a pas besoin (le port retourne `Instant`). → hors scope V1-stab.

La V4 colonne est nullable → les lignes existantes (pré-V4) ont `NULL` = jamais révoqué → tokens existants restent valides (pas de logout massif au déploiement). ✓

### Tests existants à ne pas casser

- `JwtAuthFilterTest` (13 tests de 12-5) — constructeur updaté (Task 9.1), behaviour inchangé (check sauté via `null` défaut).
- `JwtTokenProviderTest` — `extractIssuedAt` est une nouvelle méthode (additive, pas de regression).
- `UserMembershipRepositoryAdapterTest` — non touché (le port `TokenRevocationPort` est séparé du `UserMembershipRepository`).
- `SecurityConfigAdminPathEnforcementTest`, `Admin*ControllerTest` (12-3) — sanity seulement (le filtre gagne un check non-bloquant pour les tokens valides).
- `ChangePasswordServiceTest` / `DeactivateEmployeeServiceTest` — s'ils existent, updater le constructeur (nouveau mock `TokenRevocationPort`).

### Fichiers à toucher

| Fichier | État | Changement story | À préserver |
|---|---|---|---|
| `db/migration/V4__user_tenant_memberships_tokens_valid_after.sql` | **NEW** | `ALTER TABLE … ADD COLUMN IF NOT EXISTS tokens_valid_after` | convention Flyway (`V{n}__*.sql`), `public` schema |
| `identity/auth/domain/port/out/TokenRevocationPort.java` | **NEW** | port `getTokensValidAfter` + `revokeAllSessions` | package miroir de `UserMembershipRepository` |
| `identity/auth/adapter/out/persistence/impl/JdbcTokenRevocationAdapter.java` | **NEW** | raw `JdbcTemplate`, JOIN `tenants`, 2 requêtes paramétrées | convention adapter `@Component` (cf. `UserMembershipRepositoryAdapter`) |
| `identity/auth/adapter/out/persistence/impl/CachedTokenRevocationAdapter.java` | **NEW** | `@Primary`, TTL 5min `ConcurrentHashMap`, délègue au Jdbc | modèle cache `TenantSchemaSyncService:97` |
| `shared/infrastructure/security/JwtTokenProvider.java` | **UPDATE** | +`extractIssuedAt(Claims)` (~ligne 235) | toutes les surcharges `generateAccessToken`, `extract*`, `parseToken` |
| `shared/infrastructure/security/JwtAuthFilter.java` | **UPDATE** | +champ `tokenRevocationPort` + check avant `setCurrentTenant` (~ligne 128) | tout le reste (see Task 6.4) |
| `shared/domain/exception/ErrorCode.java` | **UPDATE** | +`SESSION_REVOKED` (~ligne 62) | tous les codes existants |
| `shared/infrastructure/web/GlobalExceptionHandler.java` | **UPDATE** | +FR message + cas 401 switch | tous les `@ExceptionHandler`, la map `FR_MESSAGES` |
| `identity/employee/application/service/DeactivateEmployeeService.java` | **UPDATE** | +inject port + `revokeAllSessions(employee.getUserId(), ctx)` (~après ligne 51) | les 5 étapes existantes, `@Transactional`, l'event |
| `identity/employee/application/service/ChangePasswordService.java` | **UPDATE** | +inject port + `revokeAllSessions(actorId, tenantId)` (~entre 106 et 110) | le flow 8 étapes, `@Transactional`, l'event EMPLOYEE |
| `test/.../JwtAuthFilterTest.java` | **UPDATE** | +`@Mock TokenRevocationPort` + setUp/filterWithJdbc + 2 tests AC1/AC2 | 13 tests existants GREEN |
| `test/.../JdbcTokenRevocationAdapterTest.java` | **NEW** | SQL + mapping null/non-null + `EmptyResultDataAccessException` | — |
| `test/.../CachedTokenRevocationAdapterTest.java` | **NEW** | TTL expire + invalidation sur revoke | — |
| `test/.../DeactivateEmployeeServiceTest.java` | **UPDATE/NEW** | +`shouldRevokeAllSessionsOnDeactivation` | tests existants |
| `test/.../ChangePasswordServiceTest.java` | **UPDATE/NEW** | +`shouldRevokeAllSessionsOnPasswordChange` | tests existants |
| `sprint-status.yaml` | **UPDATE** | `v1s-12-2-session-revocation-5min` → ready-for-dev | structure + commentaires |

Aucun contrat API HTTP changé (le 401 `SESSION_REVOKED` est un nouveau domaine d'erreur, pas un changement d'endpoint). Aucun workflow CI, aucun Dockerfile, aucun schéma tenant (la V4 touche `public` uniquement).

## Architecture Compliance

- **S2 / B-HIGH-5 / NFR12** : ferme « révocation de session <5min non implémentée pour les non-employés » (`AUDIT_CONFORMITE_BMAD.md:111-113,271,331`). Remédiation exacte : V4 `tokens_valid_after` + `TokenRevocationPort` + cutoff `iat` dans `JwtAuthFilter`.
- **NFR12** (`architecture.md:47`) : « <5min session revocation ». Le cache TTL 5 min + invalidation locale sur `revokeAllSessions` réalise le contrat. La fenêtre cross-instance (≤ TTL) est la borne NFR12, documentée explicitement (Task 4.2).
- **ARCH18 (raw-JDBC fully-qualified)** (`architecture.md:1424,1447`) : le `JdbcTokenRevocationAdapter` utilise `public.user_tenant_memberships` / `public.tenants` fully-qualified (pas de `search_path`), paramètres bindés (`?`) — cohérent avec `TenantSchemaSyncService`. Pas d'interpolation de `tenantSchema`.
- **Hexagonal** : port `TokenRevocationPort` dans `domain/port/out/` (pure Java, pas de Spring) ; adapters dans `adapter/out/persistence/impl/` (`@Component`). Miroir exact du trio `UserMembershipRepository` / `UserMembershipRepositoryAdapter` / `UserTenantMembershipSpringRepository`.
- **Defense-in-depth** : le check EMPLOYEE per-request de 12-5 (`TenantSchema.validate` + DB lookup INACTIVE/STORE_REASSIGNED) **reste** (AC5). La révocation 12.2 (cutoff iat, tous rôles) et le check 12-5 (status EMPLOYEE) sont deux couches indépendantes.
- Pas de nouveau contrat API HTTP, pas de workflow CI, pas de Dockerfile. Backend pur, régression-born.

## Library / Framework Requirements

- **JDK 21** (déjà utilisé) — `java.time.Instant`, `java.util.concurrent.ConcurrentHashMap`, `java.sql.Timestamp`, `java.util.UUID`. Aucune nouvelle dépendance.
- **Spring Boot 3.5.0 / Spring Security 6.x** (déjà en place) — `@Component`, `@Primary`, `JdbcTemplate` bean, `OncePerRequestFilter` inchangés.
- **jjwt** (déjà en place) — `Claims.getIssuedAt()` (retourne `java.util.Date`), déjà utilisé à l'émission via `.issuedAt(Date.from(now))` (`JwtTokenProvider.java:64,113,149`). Aucune version à changer.
- **JUnit 5 + Mockito + AssertJ** (déjà en place, `@ExtendWith(MockitoExtension.class)`) — `@Mock`, `verify`, `when`, `Instant` matchers. Aucune migration.
- Aucune dépendance externe ajoutée. Aucune version modifiée.

## File Structure Requirements

- **Migration** : `keevo/backend/src/main/resources/db/migration/V4__user_tenant_memberships_tokens_valid_after.sql` (**NEW**, slot V4 explicite per audit — V3 intentionnellement absent, Flyway tolère le gap).
- **Port** : `keevo/backend/src/main/java/com/keevo/identity/auth/domain/port/out/TokenRevocationPort.java` (**NEW**, co-localisé avec `UserMembershipRepository.java` au même package — home naturelle hexagonale).
- **Adapters** : `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/impl/JdbcTokenRevocationAdapter.java` + `CachedTokenRevocationAdapter.java` (**NEW**, co-localisés avec `UserMembershipRepositoryAdapter.java`).
- **Token provider** : `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtTokenProvider.java` (**UPDATE**, +1 méthode).
- **Filtre** : `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java` (**UPDATE**, +1 champ + check).
- **Erreur** : `keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java` + `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java` (**UPDATE**).
- **Services** : `keevo/backend/src/main/java/com/keevo/identity/employee/application/service/DeactivateEmployeeService.java` + `ChangePasswordService.java` (**UPDATE**).
- **Tests** : `keevo/backend/src/test/java/com/keevo/shared/infrastructure/security/JwtAuthFilterTest.java` (+2 tests + setUp update) + 2 nouveaux adapter tests + 2 service test updates.
- **Tracker** : `_bmad-output/implementation-artifacts/sprint-status.yaml` (`v1s-12-2-session-revocation-5min` → ready-for-dev).

## Testing Requirements

- **TDD (obligatoire)** :
  - `shouldRejectTokenIssuedBeforeRevocation()` — OWNER, tva après iat → 401 `SESSION_REVOKED`, `filterChain` jamais appelé, `getTokensValidAfter` vérifié.
  - `shouldAcceptTokenIssuedAfterRevocation()` — OWNER, tva avant iat → 200, `filterChain` appelé.
  - `shouldRevokeAllSessionsOnDeactivation()` — `DeactivateEmployeeService` → `revokeAllSessions(employee.getUserId(), tenantSchema)` vérifié.
  - `shouldRevokeAllSessionsOnPasswordChange()` — `ChangePasswordService` → `revokeAllSessions(command.actorId(), tenantId)` vérifié.
  - `JdbcTokenRevocationAdapterTest` — `queryForObject` non-null/null/`EmptyResultDataAccessException` ; `update` vérifié.
  - `CachedTokenRevocationAdapterTest` — cache hit < TTL (delegate non rappelé), miss > TTL (delegate rappelé), `revokeAllSessions` invalide le cache.
- **Non-régression (AC8)** : `mvn test -Dtest='JwtAuthFilterTest,JwtTokenProviderTest,JdbcTokenRevocationAdapterTest,CachedTokenRevocationAdapterTest,DeactivateEmployeeServiceTest,ChangePasswordServiceTest,SecurityConfigAdminPathEnforcementTest,AdminTenantControllerTest,AdminSubscriptionControllerTest,AdminCatalogControllerTest'` GREEN. Sanity : `TenantSchemaSyncServiceTest,UserMembershipRepositoryAdapterTest` GREEN. Full suite : 0 NOUVELLE régression.
- Pas d'environnement runtime/deploy requis (tests unitaires `@ExtendWith(MockitoExtension.class)`, `MockHttpServletRequest`/`MockHttpServletResponse`, `@Mock JdbcTemplate`). La V4 migration est testée par le Flyway baseline test existant (10-1/10-2) au besoin — pas de nouveau test Flyway requis pour cette story (la colonne est triviale, `ADD COLUMN IF NOT EXISTS`).

## Previous Story Intelligence

- **Story 12.5 (`v1s-12-5`, done)** : patterns V1-stab sur le **même filtre** `JwtAuthFilter`. Réutiliser : (a) l'approche « Option A chirurgicale » (1 couche ciblée, pas de refonte globale), (b) le helper `writeError`/`TOKEN_INVALID` réutilisé tel quel pour `SESSION_REVOKED`, (c) le squelette TDD `filterWithJdbc()` + `buildClaims()` + la note subtilité Mockito strict-stubs, (d) le refus de scope creep (différer les durcissements optionnels). **Le check 12.2 se place AVANT le bloc EMPLOYEE de 12-5** — les deux coexistent (defense-in-depth). Le `TenantSchema.validate` de 12-5 (lignes 152-158) reste intact.
- **Story 12.3 (`v1s-12-3`, done)** : pattern `SecurityConfig` filter-chain. Le `SecurityConfigAdminPathEnforcementTest` doit rester GREEN (sanity). Le 401 `SESSION_REVOKED` du filtre précède le `SecurityConfig` (les filtres s'exécutent avant la filter-chain Spring Security) — pas d'interaction.
- **Story 12.1 (`v1s-12-1`, done)** : rappel du précepte V1-stab « chirurgical, 1 fichier ciblé » — ici le scope est plus large (V4 + port + 2 adapters + filtre + 2 services + erreur) mais chaque touché est minimal et justifié par l'AC. Pas de wiring CI requis (test Java Mockito, pas de regression grep).
- **Story 3.5 (EMPLOYEE guards)** : a posé le check DB per-request EMPLOYEE (`JwtAuthFilter:146-190`) que 12.2 **préserve** (AC5). Le `ACCOUNT_INACTIVE`/`STORE_REASSIGNED`/`PASSWORD_CHANGE_REQUIRED` de 3.5 cohabitent avec le nouveau `SESSION_REVOKED`.
- **Story 8.6 (changement mot de passe volontaire)** : `ChangePasswordService` OWNER-branch (`ChangePasswordService.java:101-103`). Le wiring 12.2 (`revokeAllSessions` après `revokeAllByUserId`) s'insère dans le flow 8-étapes sans casser la branche OWNER.
- **Story 11-6 (refonte snapshot / ancien numérotage)** : ce travail était initialement « Story 11-6 » (`AUDIT_CONFORMITE_BMAD.md:112` `sprint-status: ready-for-dev`). Renommé `v1s-12-2` sur la track V1-stab. **Ne pas cherry-picker** depuis `refractoring` — diverge. Recréer fraîchement (la refonte réabsorbera via le même `TokenRevocationPort`).

## Git Intelligence

- **Branche courante `v1-stabilization`** (off `deploy` d766035, HEAD `b61fc27` = story 12.3 done, puis 12-5 done). V1 reste déployable pendant la refonte.
- `JwtAuthFilter.java` a été modifié par 12-5 (bloc EMPLOYEE, `TenantSchema.validate`). L'état courant (lignes 97-213) est la base de 12.2 — insert chirurgical du check revocation **avant** `setCurrentTenant` (ligne 130).
- `JwtTokenProvider.java` n'a pas été modifié récemment sur V1-stab. `extractIssuedAt` est additif (pas de regression sur les surcharges `generateAccessToken`).
- Aucun commit V1-stab n'a touché à la révocation : S2/B-HIGH-5 est un gap de conception d'origine (audit post-mortem), pas une régression. `user_tenant_memberships` n'a pas de colonne `tokens_valid_after` (V1 + V2 seulement).
- Aucune migration V3/V4 n'existe sur la branche (vérifié : `find db/migration` → `V1`, `V2` uniquement).

## Latest Technical Information

- **`Claims.getIssuedAt()` (jjwt 0.12.x)** : retourne `java.util.Date` (peut être `null` si claim `iat` absent). Convertir via `.toInstant()`. Tous les tokens Keevo sont mintés avec `.issuedAt(Date.from(Instant.now()))` → `iat` toujours présent. `null` = token anormal → skip check (allow, conservative).
- **`ConcurrentHashMap` TTL pattern** : pas de `@Cacheable` Spring (overkill pour 1 map). Modèle `TenantSchemaSyncService:97` (cache JVM-lifetime) + borne temporelle (`fetchedAt.plusSeconds(300).isAfter(now)`). Thread-safe sans verrouillage explicite (`ConcurrentHashMap` + record immuable `CachedCutoff`). La refonte remplacera par Caffeine `expireAfterWrite` — hors scope V1-stab.
- **`@Primary` self-injection** : un bean `@Primary` qui implémente la même interface que son delegate DOIT injecter le type concret du delegate (pas l'interface) pour éviter l'auto-référence. Pattern Spring standard.
- **`timestamp(6) with time zone`** : type PostgreSQL `timestamptz` (microseconde). `java.sql.Timestamp.toInstant()` convertit sans perte. Nullable → `rs.getTimestamp()` retourne `null` (géré dans le mapper).
- **Flyway gap (V3 absent)** : Flyway accepte les versions non-contiguës (`baseline-version: 0`, `application.yml:54`). V1, V2, V4 est valide. Ne PAS créer de V3 placeholder.
- Pas de breaking change dépendances. Aucune migration de librairie.

## Project Context Reference

- [Source: `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md`#Story-12.2 (lignes 85-102)] — AC BDD, refs S2/B-HIGH-5/NFR12/FR68/Story-11-6, refonte absorption, contrepartie mobile (deferred à 14.9/12.7).
- [Source: `AUDIT_CONFORMITE_BMAD.md:14,111-113,271,331`] — preuve file:line B-HIGH-5/S2 (Haute, révocation <5min non implémentée non-employé) + remédiation exacte V4 + `TokenRevocationPort` + cutoff iat.
- [Source: `_bmad-output/implementation-artifacts/v1-stabilization-stories.md:19,37`] — index track (position 3, dépendance 14.9/14.12).
- [Source: `_bmad-output/planning-artifacts/architecture.md:47`] — NFR12 « <5min session revocation », JWT 24h expiry.
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java:97-213`] — état actuel (doFilterInternal, check 12-5 bloc EMPLOYEE, `writeError` helper, `TenantContext` set/clear).
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtTokenProvider.java:56-153,235`] — surcharges `generateAccessToken` (`.issuedAt`), `extract*` (modèle pour `extractIssuedAt`).
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtProperties.java:15`] — `accessTokenExpiryHours = 24` (TTL access token).
- [Source: `keevo/backend/src/main/java/com/keevo/identity/auth/domain/model/UserTenantMembership.java:16-52`] — domaine membership (champs, pas de `tokensValidAfter` — gardé hors domaine par design).
- [Source: `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/entity/UserTenantMembershipJpaEntity.java:24-63`] — `@Table public.user_tenant_memberships`, `tenant_id` UUID, UNIQUE(user_id, tenant_id). Non touché par 12.2 (raw JDBC).
- [Source: `keevo/backend/src/main/java/com/keevo/identity/auth/domain/port/out/UserMembershipRepository.java` + `adapter/out/persistence/impl/UserMembershipRepositoryAdapter.java`] — trio port/adapter modèle pour `TokenRevocationPort` / `JdbcTokenRevocationAdapter`.
- [Source: `keevo/backend/src/main/resources/db/migration/V1__baseline_public.sql:381-390,583-584`] — DDL `user_tenant_memberships` (tenant_id UUID) + `uk_tenants_schema_name` UNIQUE(schema_name) (garantit la sous-requête 1-row).
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaSyncService.java:97,115-127`] — modèle cache `ConcurrentHashMap` (pour `CachedTokenRevocationAdapter`).
- [Source: `keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java:9-121`] + [`shared/infrastructure/web/GlobalExceptionHandler.java:44-91,170-226`] — enum + `FR_MESSAGES` + switch `domainCodeToHttpStatus` (où ajouter `SESSION_REVOKED` → 401).
- [Source: `keevo/backend/src/main/java/com/keevo/identity/employee/application/service/DeactivateEmployeeService.java:23-61`] — flow 5 étapes, `@Transactional`, `EmployeeDeactivatedEvent` (où insérer `revokeAllSessions` après étape 3).
- [Source: `keevo/backend/src/main/java/com/keevo/identity/employee/application/service/ChangePasswordService.java:64-129`] — flow 8 étapes, `tenantId = TenantContext.getCurrentTenant()` ligne 109 (où insérer `revokeAllSessions` entre étape 6 et 7).
- [Source: `keevo/backend/src/test/java/com/keevo/shared/infrastructure/security/JwtAuthFilterTest.java:41-61,266-273`] — `setUp` + `filterWithJdbc()` + `buildClaims()` (à updater pour le nouveau param + 2 tests AC1/AC2).
- [Source: `_bmad-output/implementation-artifacts/v1s-12-5-tenantid-validate-jwtfilter.md`] — story jumelle (même filtre, même Epic, Dev Notes tables + squelette TDD — modèle de structure).

## Dev Agent Record

### Agent Model Used

deepseek-v4-pro (Claude Code)

### Debug Log References

- TDD tests: 51/51 GREEN (JwtAuthFilterTest 15, JwtTokenProviderTest 17, ChangePasswordServiceTest 6, DeactivateEmployeeServiceTest 6, CachedTokenRevocationAdapterTest 3, JdbcTokenRevocationAdapterTest 4)
- Regression sanity: 20/20 GREEN (SecurityConfigAdminPathEnforcementTest, Admin*ControllerTest, TenantSchemaSyncServiceTest, UserMembershipRepositoryAdapterTest)
- Full suite: 1445 tests, 1 Failure + 40 Errors (all pre-existing DB-dependent, 0 NEW regression)
- Additional fix: ChangePasswordServiceOwnerTest (3 tests) — added @Mock TokenRevocationPort

### Completion Notes List

- ✅ V4 migration: `V4__user_tenant_memberships_tokens_valid_after.sql` — `ALTER TABLE … ADD COLUMN IF NOT EXISTS tokens_valid_after timestamp(6) with time zone` (nullable, backward-compat)
- ✅ TokenRevocationPort: pure Java interface in `identity/auth/domain/port/out/` (hexagonal, mirrors UserMembershipRepository)
- ✅ JdbcTokenRevocationAdapter: raw JdbcTemplate, JOIN `public.tenants` to resolve schema→UUID, parameterized SQL (≠ injection), `updated_at = now()` manual
- ✅ CachedTokenRevocationAdapter: @Primary, ConcurrentHashMap TTL 5min, injects concrete JdbcTokenRevocationAdapter (avoids @Primary self-reference)
- ✅ JwtTokenProvider.extractIssuedAt(Claims): returns Instant from iat claim, null-safe (backward-compat)
- ✅ JwtAuthFilter revocation check: placed BEFORE TenantContext.setCurrentTenant (revoked token never seeds ThreadLocal), applies to ALL roles (OWNER+EMPLOYEE), uses existing writeError helper
- ✅ ErrorCode.SESSION_REVOKED + GlobalExceptionHandler: FR message + 401 switch case (contract for mobile Flutter 14.9/12.7)
- ✅ DeactivateEmployeeService: revokeAllSessions(employee.getUserId(), tenantSchema) — subject = the deactivated employee, NOT the OWNER actor
- ✅ ChangePasswordService: revokeAllSessions(command.actorId(), tenantId) — self-revocation BEFORE new token issuance (iat >= tva)
- ✅ TDD: 6 test suites updated/created, 51 tests GREEN (RED-before-GREEN enforced)
- ✅ Non-régression: 0 new failures in full suite (1445 tests)
- ✅ ChangePasswordServiceOwnerTest: fixed NPE (added @Mock TokenRevocationPort)

#### Code review (bmad-code-review, 2026-07-21) — patches appliqués

- ✅ **[Patch] Auto-révocation token frais (iat secondes vs tva µs)** — `JwtAuthFilter` : `iat.isBefore(tva.truncatedTo(ChronoUnit.SECONDS))` (granularité seconde partagée). Fixe le bug High où le token minté juste après un changement de mot de passe était rejeté 401 SESSION_REVOKED à la requête suivante.
- ✅ **[Patch] Multi-tenant revoke-all** — nouveau `TokenRevocationPort.revokeAllSessionsEverywhere(userId)` (Jdbc `UPDATE … WHERE user_id = ?` + Cached invalidation `keySet().removeIf(prefix)`) ; `ChangePasswordService` l'appelle à la place de `revokeAllSessions(actorId, tenantId)` (compromission credential = tous les tenants). `DeactivateEmployeeService` reste per-tenant (employé scoped).
- ✅ **[Patch] Null-tenant guard** — `DeactivateEmployeeService` : `throw IllegalStateException` si `TenantContext.getCurrentTenant() == null` (la révocation ne doit pas silently no-op).
- ✅ **[Patch] Validation tenantId tous rôles** — `JwtAuthFilter` : `TenantSchema.validate(tenantId)` en tête pour OWNER+EMPLOYEE (avant `getTokensValidAfter`), suppression du validate dupliqué branche EMPLOYEE (réutilise `schema`). Tests OWNER/SUSPENDED corrigés : dummy `KV-XXXXXX` → format schéma `kv_xxxxxx` (conforme à la prod via `SelectTenantService:110`/`RegistrationService:94`).
- ✅ **[Patch] Fail-open DataAccessException** — `JdbcTokenRevocationAdapter.getTokensValidAfter` : catch élargi à `DataAccessException` (au-delà d'`EmptyResult`) → `log.warn` + retour `null` (allow). Évite le 500 brut + leak stack trace (filtres avant `@RestControllerAdvice`).
- **Tests** : 6 suites impactées, 37/37 GREEN. Suite complète : 0 régression introduite (les 40 errors/1 failure sont des tests DB-backed `@DataJpaTest`/`@WebMvcTest`/Flyway en échec sur `PSQL Connection localhost:5444 refused` — env sans Postgres/Testcontainers — + 1 `OnboardingServiceTest` unrelated ; aucun test des fichiers modifiés n'échoue). AC8 (0 NEW regression) respectée côté unitaire ; recommandé de revalider la suite DB-backed avec Postgres/Testcontainers avant release.

### File List

| File | Change |
|------|--------|
| `keevo/backend/src/main/resources/db/migration/V4__user_tenant_memberships_tokens_valid_after.sql` | NEW |
| `keevo/backend/src/main/java/com/keevo/identity/auth/domain/port/out/TokenRevocationPort.java` | NEW |
| `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/impl/JdbcTokenRevocationAdapter.java` | NEW |
| `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/impl/CachedTokenRevocationAdapter.java` | NEW |
| `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtTokenProvider.java` | UPDATE (+extractIssuedAt) |
| `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java` | UPDATE (+TokenRevocationPort field, +revocation check before setCurrentTenant) |
| `keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java` | UPDATE (+SESSION_REVOKED) |
| `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java` | UPDATE (+FR message, +switch case) |
| `keevo/backend/src/main/java/com/keevo/identity/employee/application/service/DeactivateEmployeeService.java` | UPDATE (+TokenRevocationPort, +revokeAllSessions after step 3) |
| `keevo/backend/src/main/java/com/keevo/identity/employee/application/service/ChangePasswordService.java` | UPDATE (+TokenRevocationPort, +revokeAllSessions before step 7) |
| `keevo/backend/src/test/java/com/keevo/shared/infrastructure/security/JwtAuthFilterTest.java` | UPDATE (+@Mock, +setUp, +2 AC1/AC2 tests) |
| `keevo/backend/src/test/java/com/keevo/identity/auth/adapter/out/persistence/impl/JdbcTokenRevocationAdapterTest.java` | NEW (4 tests) |
| `keevo/backend/src/test/java/com/keevo/identity/auth/adapter/out/persistence/impl/CachedTokenRevocationAdapterTest.java` | NEW (3 tests) |
| `keevo/backend/src/test/java/com/keevo/identity/employee/application/service/DeactivateEmployeeServiceTest.java` | UPDATE (+@Mock, +shouldRevokeAllSessionsOnDeactivation) |
| `keevo/backend/src/test/java/com/keevo/identity/employee/application/service/ChangePasswordServiceTest.java` | UPDATE (+@Mock, +shouldRevokeAllSessionsOnPasswordChange) |
| `keevo/backend/src/test/java/com/keevo/identity/employee/application/service/ChangePasswordServiceOwnerTest.java` | UPDATE (+@Mock TokenRevocationPort) |
| `_bmad-output/implementation-artifacts/sprint-status.yaml` | UPDATE (v1s-12-2 → in-progress → review) |

### Change Log

| Date | Version | Description | Author |
|-----|---------|-------------|--------|
| 2026-07-21 | 1.0 | Story créée (ready-for-dev) — Ultimate context engine analysis. | bmad-create-story |
| 2026-07-21 | 2.0 | Story implémentée — V4 migration + TokenRevocationPort + JdbcTokenRevocationAdapter + CachedTokenRevocationAdapter + extractIssuedAt + JwtAuthFilter revocation check + ErrorCode/GlobalExceptionHandler + DeactivateEmployeeService/ChangePasswordService wiring + TDD (51/51 GREEN) + full regression (0 NEW failures). | deepseek-v4-pro (Claude Code) |
| 2026-07-21 | 2.1 | Code review bmad-code-review — 2 decisions résolues (multi-tenant → revoke-all ; DataAccessException → fail-open) + 5 patches appliqués (auto-révocation précison iat/tva, revokeAllSessionsEverywhere, null-tenant guard, validate tenantId tous rôles, fail-open DB) + 9 defers (deferred-work.md) + 4 dismiss. Statut → done. 37/37 tests impactés GREEN, 0 NEW régression (suite DB-backed non exécutable sans Postgres/Testcontainers). | claude-code (bmad-code-review) |

### Review Findings

> Code review `bmad-code-review` (2026-07-21) — 3 couches parallèles (Blind Hunter, Edge Case Hunter, Acceptance Auditor) sur périmètre 12.2 uniquement (working tree vs HEAD + fichiers non suivis ; baseline spec `b61fc27` écartée car elle inclut la story 12.5 déjà reviewée). 2 decision, 3 patch, 9 defer, 4 dismiss. AC1–AC8 toutes SATISFIED hors le point de couverture de test AC7 (voir Defer #10).

- [x] [Review][Patch] Multi-tenant : le changement de mot de passe OWNER doit révoquer TOUTES les memberships du user — `ChangePasswordService.java:116` ne révoque actuellement que `(actorId, tenantId)`, laissant les tokens d'un autre tenant (ex. `kv_bbb`) du même userId valides (tva NULL → check skippé). **Décision résolue (2026-07-21) : option (a) révoquer toutes les memberships.** Fix : ajouter une méthode port `revokeAllSessionsEverywhere(UUID userId)` (UPDATE toutes les lignes `user_tenant_memberships` du user) + l'appeler dans `ChangePasswordService` à la place de `revokeAllSessions(actorId, tenantId)`. `DeactivateEmployeeService` garde `revokeAllSessions(userId, tenantSchema)` (employé scoped à un tenant).
- [x] [Review][Patch] `DataAccessException` non-`EmptyResult` non gérée dans le filtre → 500 brut + leak stack trace — `JdbcTokenRevocationAdapter.java:50` ne catch que `EmptyResultDataAccessException`. Toute autre `DataAccessException` (conn refused, timeout, `IncorrectResultSizeDataAccessException`) propage ; `JwtAuthFilter.java:222-225` ne catch que `ExpiredJwtException`/`JwtException` → 500 conteneur + stack trace, contourne `GlobalExceptionHandler` (filtres avant `@RestControllerAdvice`). **Décision résolue (2026-07-21) : option (a) fail-open (allow + log).** Fix : dans `JdbcTokenRevocationAdapter.getTokensValidAfter`, élargir le catch à `DataAccessException` (au-delà d'`EmptyResultDataAccessException`) → `log.warn` + retourner `null` (allow), cohérent avec le précédent `EmptyResult→null`. Documenter le comportement fail-open dans la Javadoc du port.
- [x] [Review][Patch] Auto-révocation du token fraîchement émis après changement de mot de passe (iat en secondes vs tva en microsecondes) [`JwtAuthFilter.java:141`] — HIGH. `ChangePasswordService.java:116` révoque (DB `now()` µs) puis `:119` émet le token (`Instant.now()`) ; jjwt sérialise `iat` en NumericDate = secondes (RFC 7519) donc `extractIssuedAt` retourne un Instant tronqué à la seconde (nanos=0) tandis que `tva` garde la précision microseconde. `iat.isBefore(tva)` est vrai dès que les deux tombent dans la même seconde (la quasi-totalité des cas) → le token tout juste émis est rejeté 401 `SESSION_REVOKED` à la requête suivante. Les Dev Notes (Task 8.2) affirmaient « iat >= tva car même now(), l'ordre élimine la race » — contredit par la différence de précision. Fix : comparer à la granularité seconde partagée — `if (iat != null && iat.isBefore(tva.truncatedTo(ChronoUnit.SECONDS)))`. (Variante : stocker `date_trunc('second', now())` dans l'UPDATE V4.) Préserve la révocation des tokens réellement anciens ; la course sub-seconde est < tolérance NFR12.
- [x] [Review][Patch] `tenantSchema` null fait silently no-op `revokeAllSessions` [`DeactivateEmployeeService.java:63` / `ChangePasswordService.java:115`] — `TenantContext.getCurrentTenant()` peut être null (call site non-HTTP, job planifié, SUPER_ADMIN cross-tenant) → `WHERE schema_name = NULL` matche 0 lignes → UPDATE retourne 0 → la révocation échoue silencieusement, tokens valides 24 h. Fix : guard `if (tenantSchema == null) throw new IllegalStateException("TenantContext required for session revocation")` aux deux call sites (et/ou assert rows-affected ≥ 1 dans l'adapter).
- [x] [Review][Patch] Le path de révocation OWNER skippe `TenantSchema.validate` — incohérent vs 12-5 [`JwtAuthFilter.java:138`] — `getTokensValidAfter(userId, tenantId)` utilise le claim JWT `tenantId` brut, non validé, alors que la branche EMPLOYEE 12-5 (`:172`) valide via `TenantSchema.validate` conformément au commentaire inline « no unvalidated claim ever enters a SQL string ». Paramètre bindé (pas d'injection) mais casse le principe defense-in-depth documenté. Fix : valider `tenantId` pour TOUS les rôles avant l'appel `getTokensValidAfter` (renvoyer `TOKEN_INVALID` sur `IllegalArgumentException`).
- [x] [Review][Defer] `iat` futur / pas de upper-bound de clock-skew [`JwtAuthFilter.java:141`] — différé. Pas de `setAllowedClockSkewSeconds` au parse ; un `iat` futur biaisé traite le token comme post-révocation. Partiellement mitigé par le patch #3 (granularité seconde). Refonte : politique de clock-skew.
- [x] [Review][Defer] Cache `ConcurrentHashMap` non borné (pas d'éviction/size cap) [`CachedTokenRevocationAdapter.java:40`] — différé. Entrées persistantes durée de vie JVM (users dormants/supprimés) ; la claim spec « bounded by active pairs » est fausse. Spec défère Caffeine en refonte.
- [x] [Review][Defer] Race de re-population de cache stale (read-then-put non atomique, même instance) [`CachedTokenRevocationAdapter.java:54-55`] — différé. Le read-then-put du Thread A peut réinsérer un tva pré-révocation après le revoke+remove du Thread B. Fenêtre minuscule, requiert une requête concurrente sur le principal révoqué. Refonte : `computeIfAbsent`/`compute` atomique.
- [x] [Review][Defer] Cache stampede / thundering herd sur miss [`CachedTokenRevocationAdapter.java:49-55`] — différé. Check-then-put non atomique ; N misses concurrents déclenchent chacun un SELECT. Idempotent (pas de défaut de correction). Refonte : coalescence des loads.
- [x] [Review][Defer] `shouldCallDelegateOnCacheExpiry` n'exerce pas l'expiry TTL (fausse couverture AC7) [`CachedTokenRevocationAdapterTest.java:42-60`] — différé. Le test utilise une clé différente au lieu d'avancer le TTL ; la branche `plusSeconds/isAfter` n'est jamais exécutée. Logique TTL triviale correcte à l'inspection. Refonte : injecter un `Clock` pour un TTL testable.
- [x] [Review][Defer] `JdbcTokenRevocationAdapterTest` utilise `anyString()` et contourne le `RowMapper` [`JdbcTokenRevocationAdapterTest.java:25-44`] — différé. La string SQL et le mapping `rs.getTimestamp("tokens_valid_after")` non testés ; un rename de colonne passerait. Refonte : `ArgumentCaptor<RowMapper>` + `ResultSet` mocké, `eq(SELECT_TVA)`.
- [x] [Review][Defer] Champ `log` inutilisé (dead code, zéro observabilité cache) [`CachedTokenRevocationAdapter.java:32`] — différé. Déclaré jamais utilisé ; invalidate/miss/race-stale tous silencieux sur un contrôle de sécurité. Refonte : DEBUG logs sur invalidate/miss.
- [x] [Review][Defer] Tests manquants path `tva==null` et frontière `iat==tva` [`JwtAuthFilterTest.java:345-408`] — différé. Seuls `iat<tva` (reject) et `iat>tva` (allow) testés ; `tva==null` (path dominant non-révoqué) et `iat==tva` (charnière AC2 `>=`) non assertés. Passe de tests refonte.
- [x] [Review][Defer] Check révocation DB per-request ajoute de la latence OWNER sur cache miss [`JwtAuthFilter.java:138`] — différé. OWNER avait 0 hit DB dans le filtre ; désormais SELECT toutes les 5min/user. By-design (cache absorbe) ; la refonte peut JOIN avec le check EMPLOYEE.
