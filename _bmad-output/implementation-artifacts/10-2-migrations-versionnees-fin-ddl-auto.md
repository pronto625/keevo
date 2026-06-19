---
baseline_commit: 851b722e850b24588fdf97756676739ab5c4a73e
---
# Story 10.2: Basculer le schéma `public` en migrations versionnées (fin de `ddl-auto=update`)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **développeur plateforme Keevo**,
I want **toute évolution du schéma `public` passant par une migration Flyway et `ddl-auto=validate` en prod et en dev**,
so that **aucune modification de schéma non revue et non réversible n'atteigne la production — et la colonne dangling `users.tenant_id` est supprimée proprement (R1 / MOD-NFR1)**.

## Contexte

Story 10.1 a activé Flyway avec la baseline `V1__baseline_public.sql` (28 tables), en **cohabitation** avec `ddl-auto=update`. Cette story ferme la transition : elle bascule `ddl-auto` de `update` à `validate`, rendant le schéma `public` entièrement piloté par Flyway, et elle livre la première vraie migration versionnée : `V2__drop_users_tenant_id.sql`.

**Pourquoi c'est le bon moment ?**
- `FlywayBaselineIntegrationTest` (Story 10.1) prouve déjà que `V1` + `ddl-auto=validate` = pas de `SchemaManagementException`.
- La colonne `users.tenant_id` est documentée comme "dangling" dans `architecture.md` — non mappée par Hibernate, jamais lue, résidu de Story 1.2. C'est la cible idéale pour la première migration après baseline.
- Après cette story, tout ajout de colonne à une entité `public` **exige** une migration Flyway (workflow documenté dans `architecture.md`).

## Acceptance Criteria

1. **(Détection de mismatch)** **Given** `ddl-auto=validate` actif, **When** un développeur ajoute un `@Column` à une entité JPA sans créer la migration Flyway correspondante, **Then** l'application **refuse de démarrer** avec une `SchemaManagementException` clairement identifiable (le champ existe dans le mapping mais pas dans la DB).

2. **(Prod — validate obligatoire)** **Given** le profil `prod`, **When** l'app démarre, **Then** `hibernate.ddl-auto` est `validate` — jamais `update` — et la divergence schéma↔entités stoppe le boot avec une erreur explicite.

3. **(Dev — parité prod)** **Given** le profil `dev` (ou aucun profil), **When** l'app démarre, **Then** `hibernate.ddl-auto` est aussi `validate` (parité dev/prod) ; le schéma est maintenu par Flyway migrations, pas par Hibernate.

4. **(Colonne dangling supprimée — V2)** **Given** une base existante (dev ou CI), **When** Flyway s'exécute au démarrage avec `V1` (baseline) + `V2` (drop), **Then** la colonne `users.tenant_id` n'existe plus dans `public.users`, et `flyway_schema_history` contient la ligne `version=2, description=drop_users_tenant_id, success=true`.

5. **(Base vierge — V1 + V2 + validate)** **Given** une base PostgreSQL vierge (CI / nouveau dev), **When** l'app démarre, **Then** Flyway crée le schéma public depuis `V1` puis applique `V2`, et Hibernate `ddl-auto=validate` démarre sans erreur (le schéma V1+V2 correspond aux entités JPA).

6. **(Idempotence V2)** **Given** `V2` déjà appliquée, **When** l'app redémarre, **Then** Flyway ne ré-exécute pas `V2` (no-op) et l'app démarre normalement.

7. **(Non-régression tenant)** **Given** la fin de cette story, **When** un nouveau tenant s'inscrit et se connecte, **Then** `TenantSchemaProvisioner` et `TenantSchemaSyncService` fonctionnent à l'identique — les schémas `kv_*` ne sont pas affectés par le passage à `validate` sur `public`.

8. **(Non-régression suite complète)** **Given** la suite de tests, **When** `mvn test` s'exécute, **Then** aucune régression — les tests pré-existants (`AdminCatalogServiceTest`, `ProductRepositoryAdapterTest`, etc.) gardent leur statut (erreurs pré-existantes uniquement, zéro régression imputable à cette story).

## Tasks / Subtasks

- [x] **Task 1 — RED : étendre FlywayBaselineIntegrationTest pour V2 (AC4, AC5, AC6)**
  - [x] Ajouter une méthode de test `v2DropUsersTenantIdIsApplied()` à `FlywayBaselineIntegrationTest` :
    - Après que le contexte Spring s'est chargé (base vierge + V1 + V2), vérifier que `users.tenant_id` n'existe PAS dans `information_schema.columns`.
    - Vérifier que `flyway_schema_history` contient `version='2'` avec `success=true`.
  - [x] Ajouter une méthode `v2IsIdempotent()` : appeler `flyway.migrate()` une seconde fois → `migrationsExecuted == 0`.
  - [x] Lancer → confirmer l'échec (`tenant_id` colonne existe encore, V2 absent).
  - [x] ⚠️ **Contrainte Docker** : le test utilise un PostgreSQL existant via `@DynamicPropertySource` (host `localhost:5444`, db `keevo_flyway_test`) — cf. D1 Story 10.1. Ne pas tenter Testcontainers (Docker API version mismatch 1.32 vs 1.40+, pré-existant).

- [x] **Task 2 — Écrire V2__drop_users_tenant_id.sql (AC4)**
  - [x] Créer `keevo/backend/src/main/resources/db/migration/V2__drop_users_tenant_id.sql`.
  - [x] Contenu : `ALTER TABLE users DROP COLUMN IF EXISTS tenant_id;`
  - [x] Vérifier que la colonne `tenant_id` est bien dans `V1__baseline_public.sql` (elle y est dans le dump car Hibernate l'a créée en Story 1.2, même si non mappée).
  - [x] ⚠️ La colonne `tenant_id` de `users` n'est **pas** mappée par Hibernate (cf. architecture.md), donc la supprimer ne causera aucune `SchemaManagementException`.

- [x] **Task 3 — Basculer ddl-auto vers validate (AC1, AC2, AC3)**
  - [x] Dans `keevo/backend/src/main/resources/application.yml`, changer `spring.jpa.hibernate.ddl-auto: update` → `validate`.
  - [x] Vérifier que `application-prod.yml` et `application-dev.yml` ne surchargent pas `ddl-auto` avec `update` (si oui, supprimer ces surcharges).
  - [x] `application-dev.yml` : pas besoin de surcharge — il hérite de `validate` depuis la base.
  - [x] `application-prod.yml` : pas besoin de surcharge non plus (hérite). Si `validate` doit être rendu explicite en prod pour sécurité, l'ajouter quand même.

- [x] **Task 4 — GREEN + non-régression (AC5, AC6, AC7, AC8)**
  - [x] Démarrer l'app en dev → Flyway applique V2 (colonne supprimée) → app démarre sans erreur.
  - [x] Redémarrer → Flyway no-op sur V2 → OK.
  - [x] Vérifier via `psql` ou log Flyway : `\d users` ne montre plus `tenant_id`.
  - [x] Vérifier non-régression tenant : `POST /api/v1/auth/register` + login → schéma `kv_*` provisionné/synchronisé normalement.
  - [x] `mvn test` : confirmer que `FlywayBaselineIntegrationTest` (tous les tests y compris les 2 nouveaux) est vert ; noter les erreurs pré-existantes (baseline connue = 1 failure + 33 errors).

- [x] **Task 5 — Documenter le nouveau workflow colonne (AC1, architecture.md)**
  - [ ] Dans `_bmad-output/planning-artifacts/architecture.md` :
    - Mettre à jour la section **Table Creation Strategy** : `public` = Flyway exclusif (`ddl-auto=validate`), plus de transition.
    - Mettre à jour le bloc **Backend Persistence Pattern** :
      - **Core Principles** : "No manual SQL" → réécrire ("Flyway migrations pour `public`, ddl-auto=validate. Ajouter `@Column` + migration `V{n}__*.sql`").
      - Ajouter une sous-section **Workflow ajout de colonne** (procédure pas-à-pas).
    - Supprimer la ligne "❌ Writing `.sql` files for public schema tables" dans **Anti-Patterns** (ce n'est plus un anti-pattern — c'est maintenant OBLIGATOIRE).
    - Ajouter à la place : "❌ Ajouter `@Column` à une entité JPA sans créer la migration Flyway correspondante".
    - Mettre à jour le **Checklist — Adding a New Entity** pour inclure l'étape migration.

## GoF Pattern Analysis (MANDATORY)

| Question | Answer |
|---|---|
| What variability exists in this feature? | La stratégie d'évolution du schéma `public` : auto-DDL (Hibernate) vs migrations versionnées (Flyway) |
| What might change in the future? | L'outil de migration (Liquibase ?), la stratégie de branching migrations, la granularité par module (Epic 13.4) |
| Which GoF pattern(s) apply? | **Strategy** : on remplace la stratégie "Hibernate auto-DDL" par "Flyway versioned migrations" — même interface (schéma évolutif), implémentation interchangeable. Chaque migration `V{n}__*.sql` est aussi une instance de **Command** (encapsule une opération DDL réversible, rejouable). |
| How does it enable Open/Closed principle? | L'ajout d'une évolution de schéma = nouveau fichier `V{n}__*.sql`, sans modifier les entités existantes ni le code Spring |
| Where is the pattern applied? | `application.yml` (stratégie validate) + `db/migration/` (commandes DDL versionées) |

## Dev Notes

### 🔴 Piège n°1 — Ordre d'exécution Spring Boot

Spring Boot garantit que **Flyway s'exécute avant Hibernate**. L'ordre est :
1. `FlywayMigrationInitializer` → applique toutes les migrations pending (V2 incluse)
2. `HibernateJpaAutoConfiguration` → validate le schéma résultant

Donc V2 supprime `users.tenant_id` **avant** que Hibernate valide. Hibernate ne verra jamais la colonne → pas de `SchemaManagementException`. ✅

### 🔴 Piège n°2 — Colonne `tenant_id` dans V1 et dans V2

`V1__baseline_public.sql` contient la définition de `users` avec la colonne `tenant_id` (générée par `pg_dump` de la base réelle). `V2` la supprime. Sur base existante, Flyway marque V1 comme baseline (ou l'exécute si vierge), puis applique V2. Résultat final : `users` sans `tenant_id`. ✅

Sur base vierge : V1 crée `users` avec `tenant_id`, V2 la supprime immédiatement. L'entité JPA `UserJpaEntity` ne mappe pas `tenant_id` → `validate` passe. ✅

### 🔴 Piège n°3 — `ddl-auto=validate` bloque sur divergence

Avec `validate`, le boot s'arrête si **un champ JPA n'a pas de colonne DB correspondante**. Par contre, l'inverse (colonne DB non mappée) n'est PAS détecté par Hibernate — donc les colonnes "extra" dans la DB sont tolérées. Seuls les **champs manquants côté DB** causent une `SchemaManagementException`.

Implication : si un dev ajoute `@Column private String newField;` à une entité sans migration → le champ n'existe pas en DB → `SchemaManagementException` au boot. C'est exactement AC1.

### 🔴 Piège n°4 — `AdminCatalogService.java` modifié (uncommitted)

Le `git status` montre `keevo/backend/src/main/java/com/keevo/admin/catalog/application/service/AdminCatalogService.java` comme modifié (M). C'est un correctif JdbcTemplate (`params.isEmpty()` edge case) indépendant de cette story. Ne pas toucher ce fichier, ne pas inclure ce changement dans le commit de cette story.

### Workflow : ajouter une colonne à `public` (à documenter dans architecture.md)

```
AVANT (Story < 10.2) :   Ajouter @Column → Hibernate crée/altera au boot (ddl-auto=update)
APRÈS (Story 10.2+) :

1. Écrire la migration SQL :
   keevo/backend/src/main/resources/db/migration/V{n}__add_{col}_to_{table}.sql
   Exemple : ALTER TABLE products ADD COLUMN IF NOT EXISTS sku VARCHAR(50);

2. Ajouter le @Column à l'entité JPA :
   @Column(name = "sku", length = 50)
   private String sku;

3. Démarrer → Flyway applique V{n} → Hibernate validate → boot OK.

JAMAIS : ajouter @Column sans migration → boot échouera avec SchemaManagementException.
```

### Fichiers touchés

| Fichier | Type | Détail |
|---|---|---|
| `keevo/backend/src/main/resources/application.yml` | UPDATE | `spring.jpa.hibernate.ddl-auto: update` → `validate` (ligne 35) |
| `keevo/backend/src/main/resources/application-prod.yml` | UPDATE | Ajouter `spring.jpa.hibernate.ddl-auto: validate` (explicite en prod) |
| `keevo/backend/src/main/resources/db/migration/V2__drop_users_tenant_id.sql` | NEW | `ALTER TABLE users DROP COLUMN IF EXISTS tenant_id;` |
| `keevo/backend/src/test/java/com/keevo/shared/infrastructure/persistence/FlywayBaselineIntegrationTest.java` | UPDATE | Ajouter `v2DropUsersTenantIdIsApplied()` + `v2IsIdempotent()` |
| `_bmad-output/planning-artifacts/architecture.md` | UPDATE | Table Creation Strategy (validate, plus transition) + Backend Persistence Pattern + Workflow colonne + Anti-patterns |

### Ce qui NE change PAS (non-régression)

- `TenantSchemaProvisioner` et `TenantSchemaSyncService` : **inchangés**. Ils gèrent les schémas `kv_*` via JDBC — aucune relation avec `ddl-auto` du schéma `public`.
- `flyway.schemas: public` reste tel quel → Flyway ne touche pas les schémas tenant.
- Les entités JPA existantes : aucune modification (sauf supprimer `tenant_id` de `UserJpaEntity` si elle était encore là — vérifier, mais d'après architecture.md elle n'est plus mappée depuis Story 1.7).
- `application-dev.yml` : ne surcharge pas `ddl-auto` → hérite de `validate` depuis la base. La ligne `flyway.baseline-on-migrate: true` reste (utile pour la DB dev qui a peut-être été créée avant V1).

### Vérification `UserJpaEntity`

Avant d'écrire V2, confirmer que `UserJpaEntity.java` n'a **pas** de champ `tenant_id` mappé. D'après architecture.md :
> "NOTE: tenant_id column still physically exists (dangling from Story 1.2). It is NOT mapped by Hibernate — completely ignored."

Donc `UserJpaEntity` n'a pas de champ `tenant_id`. V2 peut supprimer la colonne sans impact sur Hibernate.

### Configuration Flyway de référence (inchangée par rapport à 10.1)

```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 0        # ← 0, pas 1 (corrigé en code review 10.1)
    schemas: public
    default-schema: public
    locations: classpath:db/migration
  jpa:
    hibernate:
      ddl-auto: validate       # ← CE QUE CETTE STORY CHANGE (était: update)
```

### Tests : contrainte Docker (D1 reporté de Story 10.1)

`FlywayBaselineIntegrationTest` utilise `@DynamicPropertySource` sur un PostgreSQL existant (port `5444`, DB `keevo_flyway_test`) au lieu de Testcontainers. Cause : Docker API version mismatch (1.32 vs 1.40+) sur l'environnement de développement. Ne pas tenter de revenir à Testcontainers dans cette story — c'est D1 dans `deferred-work.md`.

Le test crée la DB `keevo_flyway_test` s'elle n'existe pas (`@BeforeAll`) et la nettoie (`@AfterAll` avec `pg_terminate_backend + DROP DATABASE`). Assurer que ces hooks restent présents pour les nouveaux tests.

### V2 migration — contenu attendu

```sql
-- V2__drop_users_tenant_id.sql
-- Remove the dangling tenant_id column from public.users.
-- This column was created by Hibernate ddl-auto=update in Story 1.2 but has never been
-- mapped by any JPA entity since Story 1.7 introduced user_tenant_memberships.
-- Architecture decision: architecture.md#Multi-Tenant Identity Model (2026-03-07).
ALTER TABLE users DROP COLUMN IF EXISTS tenant_id;
```

### Architecture.md — sections à mettre à jour

1. **Table Creation Strategy table** : changer la ligne Public de "(transition, ddl-auto=update)" → "ddl-auto=validate (Story 10.2)"
2. **Backend Persistence Pattern — Core Principles** :
   - "No manual SQL | ddl-auto=update creates/alters..." → "Flyway versioned migrations | every schema change in db/migration/V{n}__*.sql. ddl-auto=validate detects drift at boot. No implicit schema changes."
   - Ajouter "Workflow — adding a column: (1) write V{n}__*.sql migration (2) add @Column to JPA entity (3) start → Flyway applies → Hibernate validates."
3. **Anti-patterns** :
   - Supprimer : "Writing `.sql` files for public schema tables → Add `@Column` to the JPA entity" (obsolète)
   - Ajouter : "Adding `@Column` to a JPA entity without a matching Flyway migration → `SchemaManagementException` at boot"
4. **Checklist — Adding a New Entity** : ajouter `[ ] Write Flyway migration V{n}__create_{table}.sql in db/migration/` avant l'étape "Start backend"

### Nommage migrations (rappel)

Convention : `V{n}__description_snake_case.sql` dans `src/main/resources/db/migration/`.
- `V1__baseline_public.sql` — baseline (Story 10.1) ✅
- `V2__drop_users_tenant_id.sql` — première vraie migration (cette story)
- Futures : `V3__add_module_definitions.sql` (Story 13.1), etc.

### Project Structure Notes

- Alignement sur la structure backend hexagonale standard.
- Cette story ne touche aucun code applicatif (domain/application/adapter) — uniquement config, SQL et tests.
- Le profil `flyway-test` de `FlywayBaselineIntegrationTest` (défini par `@ActiveProfiles("flyway-test")`) se superpose sur le profil de base qui sera maintenant `ddl-auto=validate` — le test le définit explicitement de toute façon.

### References

- [Source: _bmad-output/planning-artifacts/epics-refonte-modulaire.md#Epic 10 / Story 10.2]
- [Source: _bmad-output/implementation-artifacts/10-1-flyway-baseline-schema-public.md#Dev Notes]
- [Source: _bmad-output/planning-artifacts/architecture.md#Table Creation Strategy]
- [Source: _bmad-output/planning-artifacts/architecture.md#Backend Persistence Pattern]
- [Source: _bmad-output/planning-artifacts/architecture.md#Multi-Tenant Identity Model (dangling tenant_id)]
- [Source: keevo/backend/src/main/resources/application.yml — ddl-auto:update, flyway config]
- [Source: keevo/backend/src/main/resources/application-prod.yml — actuellement sans ddl-auto]
- [Source: _bmad-output/deferred-work.md#D1 D2 from story-10.1 — Docker mismatch + CHECK constraints]
- [Source: keevo/backend/src/test/java/com/keevo/shared/infrastructure/persistence/FlywayBaselineIntegrationTest.java]
- [Source: keevo/backend/src/main/resources/db/migration/V1__baseline_public.sql]

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Dev DB: first `spring-boot:run` with stale `target/` did not pick up V2. `mvn compile` resolved it. After recompile + restart, V2 applied successfully (`tenant_id` dropped, `flyway_schema_history` v2 success=true).
- Full suite: 1438 tests, 1 failure + 33 errors — matches pre-existing baseline (AC8 satisfied).

### Completion Notes List

- ✅ AC1: `ddl-auto=validate` in `application.yml` — mismatch between JPA entity and DB causes `SchemaManagementException` at boot.
- ✅ AC2: `application-prod.yml` — explicit `ddl-auto: validate` added for production safety.
- ✅ AC3: `application-dev.yml` inherits `validate` from base config (no override).
- ✅ AC4: V2 migration (`V2__drop_users_tenant_id.sql`) drops dangling `users.tenant_id`. Verified via psql: 0 rows for column, `flyway_schema_history` contains version=2 success=true.
- ✅ AC5: Fresh DB test (`FlywayBaselineIntegrationTest`) — V1+V2 applied, `ddl-auto=validate` passes (7/7 tests GREEN).
- ✅ AC6: Idempotence test (`v2IsIdempotent`) — `migrate()` returns 0 on re-run.
- ✅ AC7: Tenant schemas unaffected — `TenantSchemaProvisioner`/`TenantSchemaSyncService` unchanged, `flyway.schemas=public` only.
- ✅ AC8: Full suite 1438 tests — 1 failure + 33 errors pre-existing, zero regressions from this story.
- ✅ Architecture.md updated: Table Creation Strategy, Backend Persistence Pattern (Core Principles + Workflow), Anti-Patterns, Checklist.

### File List

| File | Type | Detail |
|---|---|---|
| `keevo/backend/src/main/resources/application.yml` | UPDATE | `ddl-auto: update` → `ddl-auto: validate` |
| `keevo/backend/src/main/resources/application-prod.yml` | UPDATE | Ajouter `spring.jpa.hibernate.ddl-auto: validate` explicite |
| `keevo/backend/src/main/resources/db/migration/V2__drop_users_tenant_id.sql` | NEW | `ALTER TABLE users DROP COLUMN IF EXISTS tenant_id;` |
| `keevo/backend/src/test/java/com/keevo/shared/infrastructure/persistence/FlywayBaselineIntegrationTest.java` | UPDATE | +2 tests : V2 applied + V2 idempotent |
| `_bmad-output/planning-artifacts/architecture.md` | UPDATE | Table Creation Strategy + Backend Persistence Pattern + Workflow + Anti-patterns |

### Review Findings

- [x] [Review][Patch] AC4 : `v2DropUsersTenantIdIsApplied` ne vérifie pas `description=drop_users_tenant_id` dans `flyway_schema_history` [FlywayBaselineIntegrationTest.java:184] — fixed: remplacé JDBC brut par Flyway Java API + assertion description
- [x] [Review][Patch] `v2DropUsersTenantIdIsApplied` interroge `flyway_schema_history` via JDBC brut au lieu de l'API Java Flyway — fragile si le schéma interne de Flyway change [FlywayBaselineIntegrationTest.java:184] — fixed: combiné avec le patch ci-dessus
- [x] [Review][Patch] Architecture.md : commentaire obsolète dans l'arbre de répertoires dit encore `ddl-auto=update, flyway.enabled=false` [architecture.md:1063] — fixed

- [x] [Review][Defer] Changement `admin.password` (suppression du défaut `Admin@1234!`) appartient à Story 10-3 mais commité dans ce working tree — violation d'atomicité des commits [application.yml:87] — deferred, story 10-3 boundary
- [x] [Review][Defer] `v2IsIdempotent` échouera de façon trompeuse si V3+ est ajouté mais pas encore appliqué (assert total migrations==0 plutôt que V2 spécifiquement) [FlywayBaselineIntegrationTest.java:196] — deferred, low risk, adresser lors de la création de V3
- [x] [Review][Defer] `@BeforeAll` fait DROP DATABASE sans `pg_terminate_backend` préalable — si un run précédent s'est crashé, le DROP échoue et le test démarre sur une DB stale [FlywayBaselineIntegrationTest.java:62] — deferred, pre-existing depuis Story 10.1
- [x] [Review][Defer] `@ActiveProfiles("flyway-test")` référence un profil inexistant (`application-flyway-test.yml` absent) — trompeur, un futur dev pourrait créer ce fichier et écraser le `@DynamicPropertySource` [FlywayBaselineIntegrationTest.java:~40] — deferred, pre-existing depuis Story 10.1
- [x] [Review][Defer] DDL_USERS dans TenantSchemaProvisioner ne contient pas `failed_attempts`/`locked_until` — colonnes ajoutées par TenantSchemaSyncService au premier login, mais fenêtre de vulnérabilité sur le premier accès — deferred, pre-existing, non causé par cette story
