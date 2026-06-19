# Deferred Work

## Deferred from: code review of 10-1-flyway-baseline-schema-public (2026-06-19)

- **[10-1] `UserSyncStateDdlInitializer` duplique `user_sync_state` (désormais dans V1)** : l'initializer crée programmatiquement la table à `ApplicationReadyEvent` alors que V1 la possède maintenant → deux sources de vérité. À retirer une fois Flyway propriétaire confirmé (post-10.2). Fichier : `sync/sync/adapter/out/persistence/UserSyncStateDdlInitializer.java`.
- **[10-1] Override `docker-java` 3.4.1 vs Testcontainers 1.20.4** (`pom.xml:17-29`) : seuls `docker-java-api` et `docker-java-transport-zerodep` sont épinglés ; si `docker-java-core` reste sur la version de Testcontainers, risque de `NoSuchMethodError`/`NoClassDefFoundError`. Vérifier `mvn dependency:tree`. Lié à la dette Testcontainers déjà déférée.

## Deferred from: code review of 10-2-migrations-versionnees-fin-ddl-auto (2026-06-19)

- **[10-2] Commit hygiene — `admin.password` story 10-3 change dans working tree 10-2** : le défaut `Admin@1234!` a été supprimé dans `application.yml` avec un commentaire `Story 10.3`, mais ce changement se retrouve dans le diff de la story 10-2. Non bloquant fonctionnellement ; à ne pas reproduire dans les prochaines stories.
- **[10-2] `v2IsIdempotent` assert `migrationsExecuted==0` trop large** : dès que V3 sera créé, le test échouera de façon trompeuse si V3 est présent mais non encore appliqué au moment du test. Adresser lors de la création de V3 en restreignant l'assertion à "pas de nouvelle migration V2".
- **[10-2] `@BeforeAll` DROP DATABASE sans `pg_terminate_backend` préalable** (pre-existing, Story 10.1) : si un run précédent s'est crashé, le DROP échoue silencieusement et la suite démarre sur une DB stale avec des migrations déjà appliquées. Fix : ajouter `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='keevo_flyway_test'` avant le DROP.
- **[10-2] `@ActiveProfiles("flyway-test")` sans fichier `application-flyway-test.yml`** (pre-existing, Story 10.1) : l'annotation est trompeuse. Si un futur développeur crée ce fichier de profil, il pourrait écraser le `@DynamicPropertySource` et pointer le test vers une mauvaise datasource. Envisager de supprimer l'annotation ou de créer un fichier minimal.
- **[10-2] DDL_USERS dans TenantSchemaProvisioner incomplet** (pre-existing) : `failed_attempts`, `locked_until`, et potentiellement d'autres colonnes sont absentes du DDL statique — elles sont ajoutées dynamiquement par `TenantSchemaSyncService` au premier login. Fenêtre de vulnérabilité entre la création du tenant et son premier accès authentifié.
