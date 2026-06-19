# Deferred Work

## Deferred from: code review of 10-1-flyway-baseline-schema-public (2026-06-19)

- **[10-1] `UserSyncStateDdlInitializer` duplique `user_sync_state` (désormais dans V1)** : l'initializer crée programmatiquement la table à `ApplicationReadyEvent` alors que V1 la possède maintenant → deux sources de vérité. À retirer une fois Flyway propriétaire confirmé (post-10.2). Fichier : `sync/sync/adapter/out/persistence/UserSyncStateDdlInitializer.java`.
- **[10-1] Override `docker-java` 3.4.1 vs Testcontainers 1.20.4** (`pom.xml:17-29`) : seuls `docker-java-api` et `docker-java-transport-zerodep` sont épinglés ; si `docker-java-core` reste sur la version de Testcontainers, risque de `NoSuchMethodError`/`NoClassDefFoundError`. Vérifier `mvn dependency:tree`. Lié à la dette Testcontainers déjà déférée.

## Deferred from: code review of 10-6-archunit-modulith-verify-baseline (2026-06-19)

> Tous pour la **phase d'enforcement** (Epic 12.2e / Story 12.6) — la baseline warning-mode est correcte telle quelle.

- **[10-6] Convertir la baseline en ratchet à l'enforcement** : `verify()` + règles ArchUnit n'assertent rien (warning mode voulu). À l'activation, asserter le set de violations connues → échec sur toute NOUVELLE violation seulement.
- **[10-6] Corriger `allowedDependencies` (cible vs réel)** : `identity`/`messaging` nus mais importent plusieurs modules ; `catalog` sans `messaging` ; `store` sans `subscription`. Aligner avant enforcement.
- **[10-6] Wrapper `detectModulesAndGenerateDocumentation()` en try/catch** : sinon un modèle invalide fait échouer le build malgré l'intention warning-mode.
- **[10-6] Affiner règles ArchUnit** : pattern `..adapter.out.persistence..` rate `shared.infrastructure.persistence.entity` ; ajouter `withImportOption(DO_NOT_INCLUDE_TESTS)`.
- **[10-6] CI `upload-artifact@v4` : ajouter `if-no-files-found: warn`** (évite l'échec du job si les fichiers absents).
- **[10-6] Scope deps** : `spring-modulith-api` → envisager `provided` ; laisser le BOM Modulith gérer la version d'`archunit` (éviter skew vs 1.3.0 épinglé).

## Deferred from: code review of 10-5-environnement-staging (2026-06-19)

- **[10-5] 🔴 TLS staging — implémenter le routage via Traefik prod** (DÉCISION Toor) : réseau Docker externe partagé `keevo_edge` (traefik prod + keevo_staging_backend) ; router `staging.api.keevo.cm` + service `http://keevo_staging_backend:4500` dans le `traefik/dynamic.yml` prod ; retirer le Traefik staging/ACME/`:8443` ; DNS staging → VPS. Touche le pipeline prod → appliquer + tester au 1er bring-up staging. **Prérequis pour que staging serve du HTTPS valide.**
- **[10-5] Staging réutilise les secrets/clés JWT/Firebase prod** : blast radius partagé — une compromission staging avec la clé JWT prod réutilisée permet de forger des tokens prod. Générer un keypair JWT + service-account Firebase + credentials DB dédiés staging.
- **[10-5] `workflow_dispatch` sans garde de branche** (`deploy-staging.yml`) : un dispatch manuel peut builder/déployer staging depuis n'importe quel ref. Ajouter un check `github.ref`.
- **[10-5] `firebase-credentials.json` bind-mount inconditionnel + pas de garde `.env` 1er deploy** : fichier absent → Docker crée un répertoire au point de montage ; `.env` absent → `compose up` avec vars vides. Rendre le mount conditionnel + `[ -f .env ] || exit 1`.
- **[10-5] `cancel-in-progress: true`** (`deploy-staging.yml`) : peut interrompre un `docker compose up` SSH en cours → stack staging à moitié déployée. Désactiver l'annulation sur les étapes mutantes.
- **[10-5] prod `deploy-backend.yml` : clé privée JWT en `chmod 644`** (pré-existant, même bug que staging) : appliquer `chmod 600 keys/private_key.pem` aussi côté prod.

## Deferred from: code review of 10-4-backups-postgresql-restauration (2026-06-19)

- **[10-4] systemd unit `/home/ubuntu/...` hardcodé** (`keevo-backup.service`) : `EnvironmentFile`/`ExecStart` figés sur `/home/ubuntu` alors que le deploy installe sous `${HOME}/keevo`. Casse si `DEPLOY_SSH_USER != ubuntu`. Templatiser via sed au deploy.
- **[10-4] `.env.backup` non créé/validé par le deploy** : le timer est activé+démarré mais le fichier est manuel ; 1er run échoue silencieusement. Ajouter `EnvironmentFile=-` (tolérant) + un check deploy-time qui WARN si absent.
- **[10-4] Aucun alerting sur échec backup** : journald only. Ajouter `OnFailure=keevo-backup-alert@.service` (email/webhook).
- **[10-4] S3 sans IAM least-privilege / bucket versioning / Object-Lock** : un attaquant sur la box peut supprimer les backups (ransomware). Compte IAM write-only + versioning + MFA-delete.
- **[10-4] Restore `--no-owner --no-acl` perd les grants/ownership** : le rollback prod (§7) ne ré-applique pas les privilèges → RLS/permissions potentiellement cassées post-rollback. Documenter l'étape de re-grant.
- **[10-4] `script_stop:false` + `systemctl status || true`** (`deploy-backend.yml`) : un install cassé (sudo/systemd) rapporte « succès ». Gater proprement l'échec.
- **[10-4] DEPLOYMENT_CHECKLIST §7 écrit dans `docs/` racine** au lieu de `keevo/backend/docs/` (divergence vs spec). Confirmer l'emplacement voulu.

## Deferred from: code review of 10-3-externalisation-secrets-fail-fast (2026-06-19)

- **[10-3] POSTGRES_PASSWORD non validé par le fail-fast** : `application.yml:17` → `${POSTGRES_PASSWORD:keevo_local_pwd}`. En prod, si l'env est absent, le boot hérite silencieusement du mot de passe DB dev. Hors périmètre AC 10.3 mais vrai risque prod — étendre `ProdSecretsValidator` au mot de passe DB (et tokens Twilio/Wassender quand provider actif).
- **[10-3] AC7/AC8 — fail-fast non testé au niveau câblage Spring** : les tests sont unitaires (appel direct `validate()`). Le `@Profile({"prod","staging"})` + `@PostConstruct` (échec réel du boot) n'est pas couvert. Ajouter un `@SpringBootTest @ActiveProfiles("prod")` (sans DB) prouvant l'`ApplicationContextException`.
- **[10-3] Pas de contrôle de robustesse du mot de passe admin** : seul `Admin@1234!` (exact, case-sensitive) est rejeté. Le placeholder `change_me_strong_password` de `.env.example`, `admin`, `123456` passent. Ajouter une politique de force (longueur/entropie) ou au moins normaliser (trim/case).
- **[10-3] DEPLOYMENT_CHECKLIST — rotation ADMIN_PASSWORD via env = no-op** : pour un utilisateur admin déjà créé, changer `ADMIN_PASSWORD` n'a aucun effet (pas de re-hash). La doc doit renvoyer vers l'API change-password.

## Deferred from: code review of 10-2-migrations-versionnees-fin-ddl-auto (2026-06-19)

- **[10-2] Commit hygiene — `admin.password` story 10-3 change dans working tree 10-2** : le défaut `Admin@1234!` a été supprimé dans `application.yml` avec un commentaire `Story 10.3`, mais ce changement se retrouve dans le diff de la story 10-2. Non bloquant fonctionnellement ; à ne pas reproduire dans les prochaines stories.
- **[10-2] `v2IsIdempotent` assert `migrationsExecuted==0` trop large** : dès que V3 sera créé, le test échouera de façon trompeuse si V3 est présent mais non encore appliqué au moment du test. Adresser lors de la création de V3 en restreignant l'assertion à "pas de nouvelle migration V2".
- **[10-2] `@BeforeAll` DROP DATABASE sans `pg_terminate_backend` préalable** (pre-existing, Story 10.1) : si un run précédent s'est crashé, le DROP échoue silencieusement et la suite démarre sur une DB stale avec des migrations déjà appliquées. Fix : ajouter `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='keevo_flyway_test'` avant le DROP.
- **[10-2] `@ActiveProfiles("flyway-test")` sans fichier `application-flyway-test.yml`** (pre-existing, Story 10.1) : l'annotation est trompeuse. Si un futur développeur crée ce fichier de profil, il pourrait écraser le `@DynamicPropertySource` et pointer le test vers une mauvaise datasource. Envisager de supprimer l'annotation ou de créer un fichier minimal.
- **[10-2] DDL_USERS dans TenantSchemaProvisioner incomplet** (pre-existing) : `failed_attempts`, `locked_until`, et potentiellement d'autres colonnes sont absentes du DDL statique — elles sont ajoutées dynamiquement par `TenantSchemaSyncService` au premier login. Fenêtre de vulnérabilité entre la création du tenant et son premier accès authentifié.
