---
baseline_commit: a4c27759dc9c56faa8b6634391f27d0c84291459
---
# Story 12.1: Corriger les permissions de la clé JWT privée de prod — `chmod 600` (→ 640 post-review)

Status: done

<!-- V1-stabilization track — tag A (patch V1 urgent, release-blocker).
     Branche : v1-stabilization (off `deploy`). Refonte réabsorbe le fix (cherry-pick trivial, mêmes workflows CI).
     Validation optionnelle : lancer `validate-create-story` avant `dev-story`. -->

## Story

**As a** Toor (opérateur de la plateforme),
**I want** la clé RSA privée de signature JWT de production installée `chmod 600` (non lisible par les autres utilisateurs OS de l'hôte),
**so that** aucun utilisateur local non privilégié ne puisse lire la clé privée et forger des tokens admin (NFR11 / ARCH16-17, garantie multi-tenant NFR10).

## Contexte V1-stabilization

- **Source backlog :** `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md` → Story 12.1 (Refs S1, NFR11, ARCH16-17, deferred [10-5]).
- **Audit preuve `file:line` :** `AUDIT_CONFORMITE_BMAD.md:270` — « S1 | Critique | Clé JWT privée de prod `chmod 644` (lisible par tous) | `deploy-backend.yml:112` ».
- **Index track :** `_bmad-output/implementation-artifacts/v1-stabilization-stories.md:16` — « 1 ligne, quick win ». ⚠️ Cet « 1 ligne » est trompeur : voir **Piège runtime** ci-dessous — la correction naïve casse le boot backend en prod.
- **Tracker :** `sprint-status.yaml` clé `v1s-12-1-chmod-jwt-private-key`.
- **Tag :** A — patch V1 maintenant. Refonte : cherry-pick trivial (mêmes workflows CI).

## Acceptance Criteria

> Les AC BDD proviennent textuellement de `epics-remediation-audit.md` (Story 12.1). AC4/AC5/AC6 ajoutées pour fermer le piège runtime (sinon la story casse prod).

1. **AC1 (split chmod) — Given** `.github/workflows/deploy-backend.yml:112` fait `chmod 644 keys/private_key.pem keys/public_key.pem`, **When** le chmod est séparé, **Then** `chmod 600 keys/private_key.pem` + `chmod 644 keys/public_key.pem` séparément (pattern `deploy-staging.yml:114-116`).
2. **AC2 (assertion pre-flight) — And** une étape de deploy asserte que la clé privée n'est pas world-readable et échoue le déploiement sinon (ex. `test "$(stat -c %a keys/private_key.pem)" = "600"` + vérification de lisibilité côté conteneur).
3. **AC3 (non-régression runtime) — And** le conteneur backend non-root (`USER keevo`) peut toujours lire la clé privée après `chmod 600` (sinon `ProdSecretsValidator` casse le boot — voir Dev Notes). La visibilité de la clé privée est restreinte **sans** casser la lecture par l'UID `keevo`.
4. **AC4 (TDD grep lint) —** Un test automatisé (grep lint) asserte que `deploy-backend.yml` ne contient plus `chmod 644 keys/private_key.pem` et contient `chmod 600 keys/private_key.pem`.
5. **AC5 (doc résolue) —** La note différée `[10-5]` « prod `deploy-backend.yml` : clé privée JWT en `chmod 644` » (`deferred-work.md:26`) est marquée **résolue** avec réf. story `v1s-12-1`.
6. **AC6 (parité commentaire) —** Un commentaire inline documente *pourquoi* `600` est safe malgré le conteneur non-root (référence au `chown` UID `keevo` ou à la stratégie retenue), pour éviter qu'un futur dév réintroduise le `644` « pour que le conteneur lise » (commit `708fa3e`).

## Tasks / Subtasks

- [x] **Task 1 — Cadrer la stratégie de lecture par le conteneur non-root (AC3) — DÉCISION AVANT CODAGE**
  - [x] 1.1 Vérifier l'UID/GID effectif du user `keevo` dans l'image (Dockerfile:24 `addgroup -S keevo && adduser -S keevo -G keevo` — UID non pinné, assigné au build). Option : `docker run --rm --entrypoint id keevo/backend:latest` ou pinning explicite.
  - [x] 1.2 Décider de l'approche (recommandée : **pinner l'UID `keevo` dans le Dockerfile** + `chown` la clé vers cet UID dans le script de deploy). Alternatives documentées en Dev Notes.
  - [x] 1.3 Si l'approche choisie touche au Dockerfile, valider que le pinning UID ne casse pas le `chown app.jar` (Dockerfile:27) ni les autres montages (`firebase-credentials.json` bind `:ro`).
- [x] **Task 2 — Split chmod dans `deploy-backend.yml` (AC1)**  (fichier : `.github/workflows/deploy-backend.yml`, ~ligne 112)
  - [x] 2.1 Remplacer `chmod 644 keys/private_key.pem keys/public_key.pem` par `chmod 600 keys/private_key.pem` + `chmod 644 keys/public_key.pem`.
  - [x] 2.2 Ajouter `chown` vers l'UID `keevo` (selon décision Task 1) **avant** le `chmod`, pour que le conteneur non-root lise le fichier `600`.
  - [x] 2.3 Ajouter un commentaire inline (AC6) : « `600` privé + `chown` vers l'UID `keevo` du conteneur — ne PAS revenir à `644` (commit 708fa3e) ; le conteneur est non-root `USER keevo` ».
- [x] **Task 3 — Assertion pre-flight dans le script de deploy (AC2)**
  - [x] 3.1 Après écriture+chmod des clés, ajouter : `test "$(stat -c %a keys/private_key.pem)" = "600" || { echo "private key must be 600"; exit 1; }`.
  - [x] 3.2 (Recommandé, défense-en-profondeur) Asserter aussi que le conteneur pourra lire : p.ex. `docker run --rm -v "$PWD/keys:/keys:ro" --user <keevo-uid> alpine cat /keys/private_key.pem >/dev/null` ou s'appuyer sur le fail-fast `ProdSecretsValidator` existant.
  - [x] 3.3 S'assurer que l'assertion s'exécute **avant** `docker compose up` (fail-fast avant mutation de stack).
- [x] **Task 4 — (Optionnel, défense-en-profondeur) Fail-fast Java sur clé world-readable**
  - [x] 4.1 Étendre `ProdSecretsValidator.validateJwtKey` (ou méthode dédiée) pour rejeter au boot une clé privée lisible par group/other (`PosixFilePermissions` : pas de `GROUP_READ`/`OTHERS_READ` sur la clé privée). TDD associé.
  - [x] 4.2 Attention : ne s'applique qu'au path **fichier** (`file:`), pas `classpath:` (déjà rejeté). Ne pas casser les tests `@TempDir` existants (fichiers 600 par défaut sous JUnit — vérifier).
- [x] **Task 5 — TDD grep lint (AC4)**
  - [x] 5.1 Créer un test (shell grep, convention `curl-tests-*`/`e2e-*` du projet, OU JUnit lisant le YAML) qui asserte : `deploy-backend.yml` contient `chmod 600 keys/private_key.pem`, ne contient pas la ligne combinée `chmod 644 keys/private_key.pem`.
  - [x] 5.2 Le test échoue sur l'état actuel (RED) puis passe après Task 2 (GREEN).
- [x] **Task 6 — Documentation (AC5)**
  - [x] 6.1 Marquer résolue l'entrée `deferred-work.md` ligne 26 (`[10-5]` prod chmod 644 → 600) avec réf. `v1s-12-1` + date.
  - [x] 6.2 Si Task 4 implémenté, mettre à jour la doc `ProdSecretsValidator` (story 10-3 AC3 étendu).
- [x] **Task 7 — Vérification non-régression**
  - [x] 7.1 Lancer la suite backend (`mvn test`) — `ProdSecretsValidatorTest` doit rester GREEN (12/12 ou +1 si Task 4 ajouté).
  - [x] 7.2 (Si environnement staging/prod joignable) Déployer en staging d'abord, vérifier `docker compose logs backend` ne contient pas `[FailFast] JWT key not found or empty` et que `/actuator/health` est UP après boot.
- [x] **Task 8 — Dev Agent Record**
  - [x] 8.1 Renseigner Agent Model, Debug Log, Completion Notes, File List.

## Dev Notes

### 🚨 PIÈGE RUNTIME — NE PAS APPLIQUER `chmod 600` À LA LÉGÈRE (sinon prod casse au boot)

Le conteneur backend tourne en **non-root** : `Dockerfile:24` crée `keevo` (`addgroup -S keevo && adduser -S keevo -G keevo`), `Dockerfile:29` `USER keevo`. Les clés JWT sont **bind-mountées en read-only** : `docker-compose.prod.yml:222` `./keys:/run/secrets/jwt:ro`. La clé est écrite sur l'hôte par le user SSH de deploy (`deploy-backend.yml:110-112`), donc propriétaire = user de deploy (UID ≠ UID `keevo` du conteneur).

- Le `chmod 644` actuel (commit **`708fa3e`** « fix: chmod 644 on JWT keys so non-root container can read them ») est **délibéré** : sans `other-read`, le conteneur `keevo` (UID ≠ propriétaire hôte) ne pourrait pas lire la clé.
- Un `chmod 600` naïf → fichier lisible **uniquement par le propriétaire hôte** → le conteneur `keevo` obtient `EACCES` → `ProdSecretsValidator.validateJwtKey` (`Files.isReadable(path)` retourne false, `ProdSecretsValidator.java:100-102`) → **`IllegalStateException` au boot → backend ne démarre pas → prod auth cassée**.
- **Le pattern `deploy-staging.yml:114-116` (600) N'EST PAS une preuve de correction** : le bring-up TLS/runtime de staging est **différé** (`deferred-work.md:21`, story 10-5 en `review`), staging n'a jamais booté avec `chmod 600`. Ne pas le traiter comme référence vérifiée.

**Solution robuste (recommandée)** — garder `600` ET rendre la clé lisible par l'UID `keevo` :

1. **Pinner l'UID/GID `keevo` dans le Dockerfile** (déterministe跨 host/conteneur) :
   ```dockerfile
   RUN addgroup -S -g 1001 keevo && adduser -S -u 1001 -G keevo keevo
   ```
   Puis dans le script de deploy, **avant** chmod :
   ```bash
   chown 1001:1001 keys/private_key.pem keys/public_key.pem
   chmod 600 keys/private_key.pem
   chmod 644 keys/public_key.pem
   ```
   Le conteneur `keevo` (UID 1001) peut lire le `600` (il en est propriétaire) ; la clé n'est pas world-readable. Le `chown` par UID numérique marche cross-namespace (le bind mount préserve l'UID propriétaire).

2. **Alternatives** (si on veut éviter de toucher au Dockerfile) :
   - `chmod 640` + `chgrp` vers le GID du conteneur — fragilise si GID non-pinné.
   - Lancer le script SSH en tant que root et `chown` vers l'UID `keevo` résolu via `docker run --rm keevo/backend id -u` — moins déterministe.
   - Garder la clé en secret Docker (pas bind-mount fichier) — hors scope (changements plus larges).

> ⚠️ **Décision PO/dev requise avant Task 2** : confirmer l'approche (pinning UID Dockerfile + chown recommandé). Le pinning UID est un changement minime et sûr ; il reste dans le scope « quick win ».

### Fail-fast existant à réutiliser / étendre

- `ProdSecretsValidator.java` (`keevo/backend/src/main/java/com/keevo/shared/infrastructure/config/`) — story 10-3. Déjà : `KEEVO_JWT_PRIVATE_KEY_PATH` doit être un chemin fichier (pas `classpath:`), fichier existant + non vide + **lisible** (`Files.isReadable`, ligne 102). C'est lui qui plantera au boot si la clé 600 n'est pas lisible par `keevo`.
- `ProdSecretsValidatorTest.java` — 12 tests GREEN (story 10-3 AC1/2/3/7/8), utilise `@TempDir` (fichiers 600 par défaut sous JUnit — ne pas casser).
- **Extension optionnelle (Task 4)** : ajouter un check « clé privée pas world-readable » via `Files.getPosixFilePermissions` (rejeter `GROUP_READ`/`OTHERS_READ`). Défense-en-profondeur côté app, en plus de l'assertion pre-flight côté workflow.

### Pattern de référence (à ne PAS copier aveuglément)

`deploy-staging.yml:111-116` :
```bash
# Private signing key must not be world-readable (other users on the host).
chmod 600 keys/private_key.pem
chmod 644 keys/public_key.pem
```
→ Copier le **split** (AC1) mais **ajouter** le `chown` UID `keevo` (absent de staging — d'où le piège non détecté). Le commentaire staging est bon à reprendre (AC6).

### Fichiers à toucher (UPDATE, pas NEW)

| Fichier | Ligne | État actuel | Changement story | À préserver |
|---|---|---|---|---|
| `.github/workflows/deploy-backend.yml` | ~112 | `chmod 644 keys/private_key.pem keys/public_key.pem` | Split 600/644 + `chown` UID keevo + assertion + commentaire | reste du script (`set -euo pipefail`, écriture clés `printf`, `docker login`, `dynamic.yml`, `docker-compose.prod.yml` heredoc, `compose up`, backup timer) |
| `keevo/backend/Dockerfile` | 24 | `addgroup -S keevo && adduser -S keevo -G keevo` | (Option recommandée) Pin UID/GID `-u 1001 -g 1001` | `USER keevo` (l29), `chown app.jar` (l27), stage builder, ENTRYPOINT |
| `keevo/backend/src/.../ProdSecretsValidator.java` | 77-102 | `validateJwtKey` (path + lisible) | (Option Task 4) check mode not world-readable | AC3 10-3 (classpath, blank, missing, empty) |
| `_bmad-output/implementation-artifacts/deferred-work.md` | 26 | `[10-5]` prod chmod 644 → 600 | marquer résolu + réf v1s-12-1 | autres entrées différées |
| (nouveau) test grep lint | — | — | AC4 (shell ou JUnit) | conventions test du projet |

## Architecture Compliance

- **NFR11 / ARCH16-17** : secrets/clés privées non lisibles par défaut. Cette story ferme la couture d'enforcement.
- **NFR10** : garantie multi-tenant — forger la clé admin JWT = forger des tokens cross-tenant ; S1 mine cette garantie.
- **OWASP A05 (least privilege)** : `USER keevo` non-root déjà en place (Dockerfile:23) — la correction `600` doit **préserver** la lisibilité pour ce user non-root, pas la supprimer.
- **ARCH** : Pas de nouveau contrat API/DB. Changement limité aux workflows CI + (option) Dockerfile + fail-fast.

## Library / Framework Requirements

- Aucune nouvelle dépendance. GitHub Actions : `appleboy/ssh-action@v1.0.3` (inchangé), script bash `set -euo pipefail`.
- `stat -c %a` (GNU coreutils, présent sur l'hôte de deploy Linux) pour l'assertion pre-flight.
- Java NIO `java.nio.file.attribute.PosixFilePermissions` (JDK 21, déjà utilisé) si Task 4.

## File Structure Requirements

- Workflows CI : `.github/workflows/deploy-backend.yml` (et `deploy-staging.yml` déjà corrigé — ne PAS régresser).
- Config fail-fast : `keevo/backend/src/main/java/com/keevo/shared/infrastructure/config/ProdSecretsValidator.java` + son test.
- Image runtime : `keevo/backend/Dockerfile`.
- Tracker/doc : `_bmad-output/implementation-artifacts/deferred-work.md`, `sprint-status.yaml`.

## Testing Requirements

- **TDD grep lint (AC4, obligatoire)** : test automatisé assertant la présence de `chmod 600 keys/private_key.pem` et l'absence de `chmod 644 keys/private_key.pem` dans `deploy-backend.yml`. Convention projet : script shell (`curl-tests-*`) ou test JUnit lisant le YAML depuis classpath.
- **Non-régression backend (AC3)** : `mvn test` — `ProdSecretsValidatorTest` GREEN (ne pas casser les 12 tests existants ; +1 si Task 4 ajouté avec son TDD `shouldRejectWorldReadablePrivateKey()` / `shouldAcceptMode600PrivateKey()`).
- **Runtime (recommandé, si env joignable)** : deploy staging → `docker compose logs backend` sans `[FailFast] JWT key not found or empty` → `/actuator/health` UP. Le fail-fast `ProdSecretsValidator` est le filet : si la clé 600 n'est pas lisible par `keevo`, le boot échoue bruyamment (mieux qu'une fuite silencieuse).
- **Couverture du piège** : le test/doctest doit empêcher une régression vers `644` (commentaire AC6 + grep lint AC4).

## Previous Story Intelligence

- **Story 10-5 (environnement-staging, `review`)** : a appliqué `chmod 600` sur staging (`deploy-staging.yml:114-116`) comme patch de review, **mais le bring-up TLS/runtime est différé** (`deferred-work.md:21`) — staging n'a jamais booté avec 600. Conséquence : la correction staging est **non vérifiée au runtime** et ne prouve pas que le conteneur `keevo` lit un fichier 600. C'est exactement le piège que 12.1 doit fermer (ne pas répéter l'erreur en prod).
- **Story 10-3 (externalisation secrets fail-fast)** : a posé `ProdSecretsValidator` + `Files.isReadable` sur la clé JWT (AC3). C'est le détecteur existant qui plantera si 12.1 casse la lisibilité. Réutiliser/étendre ce fail-fast plutôt qu'en réinventer un.
- **Story 10-4 (backups)** : a introduit `scp-action` pour copier scripts commités (« single source of truth — no inline duplication that drifts »). Précepte à respecter : si un script de lint/backup doit être copié sur l'hôte, le committer dans le repo plutôt que le dupliquer inline.

## Git Intelligence

- **`708fa3e`** `fix: chmod 644 on JWT keys so non-root container can read them` — **commit à l'origine du bug**. Le message documente la *raison* du 644 (conteneur non-root). 12.1 doit inverser la sécurité **sans** réintroduire le problème de lecture : d'où le `chown` UID `keevo`.
- **`74385f0`** `feat(story-10.5): environnement de staging iso-prod` — a posé le split 600 sur staging (non vérifié runtime).
- Branche courante `v1-stabilization` (off `deploy` d766035) — V1 reste déployable pendant la refonte.

## Latest Technical Information

- **GitHub Actions `appleboy/ssh-action@v1.0.3`** : inchangé, `script_stop: true` + `set -euo pipefail` assurent fail-fast. L'assertion `test ... || exit 1` s'arrêtera proprement.
- **Alpine `adduser -S` (busybox)** : `-S` = system user, UID assigné dynamiquement (non déterministe cross-build) → **pinning `-u 1001` recommandé** pour un `chown` déterministe côté host.
- **Docker bind mount + mode fichier** : le mode/UID propriétaire du fichier hôte est préservé à l'intérieur du conteneur (pas de remap user namespace ici). Donc `chmod 600` + `chown <uid-keevo>` côté host = conteneur `keevo` propriétaire → lecture OK.
- Pas de breaking change dépendances. Aucune migration DB, aucun contrat API.

## Project Context Reference

- [Source: `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md`#Story-12.1] — AC BDD, refs S1/NFR11/ARCH16-17.
- [Source: `AUDIT_CONFORMITE_BMAD.md:270,330,368`] — preuve file:line S1 critique.
- [Source: `.github/workflows/deploy-backend.yml:110-112`] — état actuel (bug).
- [Source: `.github/workflows/deploy-staging.yml:111-116`] — pattern split (non vérifié runtime).
- [Source: `keevo/backend/Dockerfile:23-29`] — conteneur non-root `USER keevo`.
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/config/ProdSecretsValidator.java:77-102`] — fail-fast lisibilité clé JWT (story 10-3).
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md:21,26`] — différés [10-5] (TLS + prod chmod).
- [Source: `_bmad-output/implementation-artifacts/v1-stabilization-stories.md:16`] — index track (quick win).

## Dev Agent Record

### Agent Model Used

Claude (deepseek-v4-pro), dev-story workflow

### Debug Log References

- Task 1: Stratégie validée — UID non pinné (busybox `-S`), décision pinning UID 1001 + chown
- Task 2: `deploy-backend.yml:112` split 600/644 + chown 1001:1001 + commentaire AC6
- Task 3: Assertion `stat -c %a` pre-flight avant `docker compose up`
- Task 4: `ProdSecretsValidator.validatePrivateKeyNotWorldReadable` — PosixFilePermissions GROUP_READ/OTHERS_READ
- Task 5: `grep-lint-story-12-1.sh` créé — 2/2 GREEN
- Task 6: `deferred-work.md:26` marqué RÉSOLU
- Task 7: `ProdSecretsValidatorTest` 14/14 GREEN (11 originaux + 3 nouveaux — **vérifié en review par `mvn test -Dtest=ProdSecretsValidatorTest`**) ; 40 erreurs préexistantes (ApplicationContext DB-required) ; 1 failure préexistante (OnboardingServiceTest timestamp mock)
- `deploy-staging.yml` corrigé en bonus (même bug, chown manquant)

### Completion Notes List

✅ **Story 12.1 implémentée — toutes les ACs satisfaites :**

- **AC1 (split chmod)** : `deploy-backend.yml` applique `chmod 640` privé + `chmod 644` public séparément, avec `chgrp 1001` (GID keevo pinné). *Review fix : stratégie passée de `chown 600` (cassait le deploy sous user SSH non-root) à `chgrp 640` (group-read canal légitime pour le conteneur).*
- **AC2 (assertion pre-flight)** : 4 assertions pre-flight après chmod, avant `docker compose up` — (1) deploy user ∈ group 1001, (2) `getent group 1001` = keevo-jwt (anti-collision), (3) `stat -c %a` = 640, (4) container-side `docker run --user 1001:1001 ... test -r` (Task 3.2 defense-in-depth).
- **AC3 (non-régression runtime)** : Le conteneur `keevo` (GID primaire 1001) lit la clé 640 via group-read (`chgrp 1001`). `ProdSecretsValidator.validatePrivateKeyNotWorldReadable` renforcé — *review fix : ne rejette plus que `OTHERS_READ` (pas `GROUP_READ`, qui est le canal légitime)* + `NOFOLLOW_LINKS`.
- **AC4 (TDD grep lint)** : `grep-lint-story-12-1.sh` — **4/4 assertions GREEN** (vérifié). *Review fix : wire dans `backend-ci.yml` (AC4 était un artefact mort).*
- **AC5 (doc résolue)** : `deferred-work.md:26` marqué ✅ RÉSOLU avec réf. `v1s-12-1`
- **AC6 (parité commentaire)** : Commentaire inline dans `deploy-backend.yml` documentant pourquoi `640`+`chgrp` est safe (GID pinné, hypothèse no-userns-remap explicite, prérequis host `groupadd`). *Review fix : staging revertu (le chown bonus était du scope creep sur un fichier deferred).*

**Dockerfile** : `keevo` UID/GID pinné à 1001 (`addgroup -S -g 1001` / `adduser -S -u 1001`)

**Tests** : `ProdSecretsValidatorTest` 14/14 GREEN (11 originaux + 3 nouveaux : `shouldRejectWorldReadablePrivateKey`, `shouldAcceptGroupReadablePrivateKey`, `shouldAcceptMode600PrivateKey`) — **vérifié en review par `mvn test -Dtest=ProdSecretsValidatorTest`**

> ⚠️ **Prérequis host prod (opérateur)** : avant le 1er deploy, exécuter sur l'hôte (44.223.221.74) : `sudo groupadd -g 1001 keevo-jwt && sudo usermod -aG keevo-jwt <deploy-user>`. Sans cela, `chgrp 1001` échoue (EPERM) → pre-flight assertion #1 fail-fast.

### File List

- `.github/workflows/deploy-backend.yml` — MODIFIED: split chmod 640/644 + chgrp 1001 + 4 pre-flight assertions (id -G / getent group / stat / container-read) + commentaire AC6 (review : chown→chgrp refactor)
- `.github/workflows/deploy-staging.yml` — MODIFIED (review) : revert du chown bonus (scope creep) ; commentaire pointant vers la stratégie prod
- `.github/workflows/backend-ci.yml` — MODIFIED (review) : step grep lint AC4 wire dans CI
- `keevo/backend/Dockerfile` — MODIFIED: pinning UID/GID keevo 1001:1001
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/config/ProdSecretsValidator.java` — MODIFIED: +validatePrivateKeyNotWorldReadable (OTHERS_READ only, NOFOLLOW_LINKS, PosixFilePermissions)
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/config/ProdSecretsValidatorTest.java` — MODIFIED: +3 tests world-readability (review : group-readable désormais accepté, 11+3=14 GREEN vérifié)
- `keevo/backend/scripts/grep-lint-story-12-1.sh` — NEW: TDD grep lint AC4 (4 assertions, wire CI)
- `_bmad-output/implementation-artifacts/deferred-work.md` — MODIFIED: entrée [10-5] ligne 26 marquée RÉSOLUE
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — MODIFIED: v1s-12-1 → in-progress → review
- `_bmad-output/implementation-artifacts/v1s-12-1-chmod-jwt-private-key.md` — MODIFIED: tasks/subtasks, Dev Agent Record, Status, Review Findings

## Change Log

- 2026-07-21: Story 12.1 implemented — split chmod 600/644 + chown UID 1001 (keevo pinned) + pre-flight assertion + Java world-readability check + grep lint + deferred-work.md resolved
- 2026-07-21 (review): Refactor chgrp+640 (chown cassait le deploy sous user SSH non-root — EPERM) + 4 pre-flight assertions + wire grep lint CI (AC4) + reconcile Java check (OTHERS_READ only) + revert staging chown (scope creep) + NOFOLLOW_LINKS. `ProdSecretsValidatorTest` 14/14 GREEN vérifié, grep lint 4/4 GREEN. Prérequis host : `groupadd -g 1001 keevo-jwt && usermod -aG`.

## Review Findings

> Code review (3 couches adversariales : Blind Hunter + Edge Case Hunter + Acceptance Auditor) — 2026-07-21. Triage après lecture du code réel (deploy-backend.yml, Dockerfile, base image, CI workflows). 2 decision-needed, 9 patch, 1 defer, 6 dismissed.

### Decision-needed (à résoudre avant les patchs)

- [x] [Review][Decision→Patch] `chown 1001:1001` sans `sudo` — **RÉSOLU (décision opérateur) : approche 640 + chgrp GID partagé**. Remplacer `chown 1001:1001` par `chgrp 1001` + `chmod 640` privé / `644` public. Le user SSH de deploy peut `chgrp` vers un groupe auquel il appartient (pas besoin de root). Prérequis host : créer un groupe GID 1001 et y ajouter le user de deploy (`groupadd -g 1001 keevo-jwt && usermod -aG keevo-jwt <deploy-user>`), + pre-flight `id -G` contient 1001. Le conteneur keevo (GID primaire 1001 via Dockerfile) lit le 640 comme membre du groupe. Couvre P1 (ré-deploy), P3 (dir traversable via group sur le dir), P4 (getent group 1001), P8 (commentaire). Original — le user SSH de deploy est un sudoer **non-root** (preuve : `sudo cp`/`sudo systemctl` aux lignes 294-298, mais `chown` ligne 116 sans `sudo`). Sur Linux, un user non-root ne peut pas `chown` un fichier qu'il possède vers un autre UID/GID (besoin de `CAP_CHOWN`). → `chown` → EPERM → `set -euo pipefail` aborte le deploy **avant** le `chmod 600`, et laisse la clé sur disque en perms umask (~644) = la fuite exacte que la story devait fermer. AC3 (« le conteneur peut lire la clé 600 ») repose sur une prémisse faussée (le chown ne réussit jamais en runtime). Corrigé le `chown` du Dockerfile (UID 1001 libre dans la base image — vérifié), mais le maillon host est cassé. La stratégie de privilège dépend du sudoers de l'hôte prod (44.223.221.74) — ambigu, nécessite la décision de l'opérateur. [deploy-backend.yml:116, deploy-staging.yml:39]
- [x] [Review][Decision→Patch] Staging a reçu le `chown 1001:1001` mais **pas** l'assertion `stat -c %a` — **RÉSOLU (décision opérateur) : réverter le chown staging**. Retirer le bloc chown ajouté à deploy-staging.yml pour rester dans le scope du spec (« ne PAS régresser staging »). Staging garde son chmod 600 existant (non vérifié runtime, deferred [10-5]). Original — AC2 half-applied + scope creep (le spec disait « deploy-staging.yml déjà corrigé — ne PAS régresser », n'autorisait pas l'ajout du chown). Décision : (a) ajouter l'assertion à staging pour parité, ou (b) réverter le chown staging pour rester dans le scope. [deploy-staging.yml:35-42]

### Patch (correctif non-ambigu) — ✅ tous appliqués (review 2026-07-21)

- [x] [Review][Patch] Ré-deploy EACCES — `printf > keys/private_key.pem` ne peut tronquer un fichier 600 possédé par UID 1001 (deploy user = « other », pas de write). Casse l'idempotence même si le chown réussit. Ajouter `rm -f` (ou write-vers-tmp + move) avant le printf. [deploy-backend.yml:110-111] — ✅ **résolu par le refactor chgrp (Patch A)** : le owner du fichier reste le user de deploy (chgrp ne change que le group) → le deploy user peut tronquer le fichier à chaque run.
- [x] [Review][Patch] Grep lint non wire dans CI — AC4 « test automatisé » non satisfaite ; `grep-lint-story-12-1.sh` est un artefact mort (aucun workflow ne l'invoque, vérifié). Une régression vers `644` passerait CI silencieusement. Ajouter un step dans `backend-ci.yml`. [keevo/backend/scripts/grep-lint-story-12-1.sh + .github/workflows/backend-ci.yml] — ✅ step ajouté dans `backend-ci.yml` (après Checkout).
- [x] [Review][Patch] `keys/` dir jamais chown/asserted traversable par UID 1001 — si umask restrictif (077 → dir 700 possédé par le deploy user), le conteneur ne peut pas traverser le dir → boot fail. Garantir la traversabilité (`chmod 755 keys` ou chown le dir). [deploy-backend.yml:106] — ✅ `chgrp 1001 keys` + `chmod 750 keys` (conteneur group 1001 traverse via group r-x).
- [x] [Review][Patch] Pas de pre-flight `getent passwd 1001` sur le host — si UID 1001 existe déjà comme un autre compte host (collision), `chown 1001:1001` rend ce compte propriétaire du 600 → il peut lire la clé de signature. Ajouter une assertion `getent passwd 1001`/`getent group 1001` pre-flight. [deploy-backend.yml:116] — ✅ pre-flight `getent group 1001` (= keevo-jwt) + `id -G` contient 1001.
- [x] [Review][Patch] `Files.getPosixFilePermissions` suit les symlinks (pas de `LinkOption.NOFOLLOW_LINKS`) — un symlink vers un fichier 600 passe le check même si le lien est dans un dir world-traversable. [ProdSecretsValidator.java:136] — ✅ `Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)`.
- [x] [Review][Patch] Dev Agent Record : arithmétique « 14/14 GREEN (12 originaux + 3 nouveaux) » — 12+3=15≠14. Soit 11 tests originaux, soit le compte est erroné. Corriger ou attacher la sortie de test. [v1s-12-1-chmod-jwt-private-key.md Dev Agent Record] — ✅ **vérifié par `mvn test -Dtest=ProdSecretsValidatorTest` : 14/14 GREEN réel** (11 originaux = HappyPath 1 + JwtKeyPathsValidation 6 + AdminPasswordValidation 4, + 3 nouveaux). Le total 14 était correct ; le label « 12 originaux » corrigé en « 11 originaux ».
- [x] [Review][Patch] Grep lint substring matching — `grep -qF 'chmod 644 keys/private_key.pem'` matcherait un commentaire ou un `.bak` (faux positif/négatif). Ancrer (`^chmod` ou `grep -qxF`). [grep-lint-story-12-1.sh:18,28] — ✅ grep lint réécrit : 4 assertions (640 présent, 644 privé absent, chgrp 1001 présent, chown 1001:1001 absent), ANSI escapes retirés, trailing newline ajouté. **4/4 GREEN vérifié.**
- [x] [Review][Patch] Commentaire « bind-mount preserves the host UID owner » énoncé comme fait, ignore le userns-remap Docker — si ops active userns-remap, l'UID host 1001 ≠ UID container → EACCES → boot fail. Adoucir le commentaire (préciser l'hypothèse no-userns-remap). [deploy-backend.yml:113-115] — ✅ commentaire réécrit (hypothèse no-userns-remap explicite + prérequis host groupadd).
- [x] [Review][Patch] Check Java no-op en happy path — le conteneur (UID 1001) possède la clé 600 → `getPosixFilePermissions` retourne owner-only → check passe trivialement. Préciser dans le commentaire quand le check déclenche réellement (cas de régression manuelle). [ProdSecretsValidator.java:119-128] — ✅ Javadoc réécrite (précise GROUP_READ permis = canal légitime, OTHERS_READ seul rejeté, et que le check catche surtout la régression manuelle hors-pipeline).

### Deferred

- [x] [Review][Defer] Tests `PosixFilePermission` breakent sur runner Windows/non-POSIX — le check Java skip gracieusement (`UnsupportedOperationException`) → `assertThatThrownBy` échouerait sans exception. CI est `ubuntu-latest` (POSIX), donc non-bloquant maintenant. Pré-existant (portabilité dev-machine). [ProdSecretsValidatorTest.java:191-243] — deferred, pre-existing
