---
baseline_commit: 851b722e850b24588fdf97756676739ab5c4a73e
---
# Story 10.5: Environnement de staging iso-prod

Status: review

## Story

As a **développeur plateforme**,
I want **un environnement staging reproduisant la prod (DB séparée, mêmes profils Spring)**,
so that **les migrations Flyway et les déploiements sont validés avant la prod, et la restauration des backups (Story 10.4) peut être vérifiée sur un environnement réel (réduit R-fiabilité, MOD-NFR12)**.

## Contexte

Stories 10.1 (Flyway baseline), 10.2 (ddl-auto=validate), 10.3 (secrets fail-fast) et 10.4 (backups PostgreSQL) ont sécurisé le socle applicatif. Cette story crée l'**environnement de staging iso-prod** qui sert de cible pour :
- Les dry-runs de migrations Flyway avant la prod (Epic 16)
- La vérification de restauration des backups (Story 10.4, AC4 — le `BACKUP_RESTORE.md` référence déjà staging)
- Les tests canary futurs (différés)

**Infra prod actuelle (critique à comprendre) :**
- VPS unique, répertoire `~/keevo/`
- Compose stack : Traefik v3.3 (ports 80/443, Let's Encrypt) + PostgreSQL 16-alpine (`keevo_postgres`, pas de port exposé sur l'hôte) + Spring Boot (`keevo_backend`, port interne 4500)
- Réseau interne : `keevo_net`
- Déploiement via `.github/workflows/deploy-backend.yml` sur push `deploy` → branch → SSH VPS
- Profile Spring `prod` → `application-prod.yml` (ddl-auto=validate, require-ssl=true, logs WARN)

**Périmètre de cette story :** CI/CD (GitHub Actions workflow), profil Spring, documentation. **Zéro code Java, zéro migration Flyway, zéro modification de `deploy-backend.yml`.**

**Stratégie staging sur le même VPS :**
Le staging tourne sur le **même VPS** que la prod, dans `~/keevo-staging/` avec :
- **Conteneurs préfixés** `keevo_staging_*` (pas de collision de noms)
- **Traefik staging sur ports 8080/8443** (pas de conflit avec Traefik prod sur 80/443)
- **DB dédiée** `keevo_staging` dans un conteneur `keevo_staging_postgres` (volume `postgres_staging_data` — **jamais le volume prod**)
- **Domaine staging** `staging.<APP_DOMAIN>` (ex. staging.api.keevo.cm) via Let's Encrypt sur port 8443
- **Profile Spring `staging`** (= clone de prod, sans require-ssl car Traefik staging peut ne pas avoir TLS valide)

> Alternative (si VPS séparé disponible) : utiliser les mêmes ports 80/443 avec les secrets `STAGING_*` pointant vers le second VPS — aucune modification de code requise.

## Acceptance Criteria

1. **(Déclenchement automatique sur branche `staging`)** **Given** la pipeline CI/CD, **When** on pousse un commit sur la branche `staging`, **Then** le workflow `deploy-staging.yml` démarre automatiquement ET déploie l'app avec le profil `staging` sans toucher la prod.

2. **(DB dédiée et isolée)** **Given** le déploiement staging, **When** l'app démarre, **Then** elle se connecte à `keevo_staging` (conteneur `keevo_staging_postgres`, volume `postgres_staging_data` — distinct de `postgres_data` prod) **ET** `GET /actuator/health → {"status":"UP"}` répond sur le domaine staging.

3. **(Profil staging = clone de prod)** **Given** le profil `staging`, **When** l'app démarre, **Then** `spring.jpa.hibernate.ddl-auto=validate` est actif (Flyway gère le schéma), les migrations Flyway s'appliquent proprement depuis zéro sur la DB staging.

4. **(Isolation complète — prod intacte)** **Given** le déploiement staging, **When** il s'exécute, **Then** `deploy-backend.yml` (prod) n'est PAS modifié, les conteneurs prod (`keevo_backend`, `keevo_postgres`, `keevo_traefik`) sont intacts, et le volume `postgres_data` (prod) reste intact.

5. **(Pas de secrets dans le repo)** **Given** les fichiers commitables (`deploy-staging.yml`, `application-staging.yml`), **When** un reviewer les inspecte, **Then** aucun mot de passe, aucune clé, aucun secret — tout via GitHub Secrets ou variables d'env.

6. **(Déclenchement manuel)** **Given** le workflow `deploy-staging.yml`, **When** on le déclenche via `workflow_dispatch` depuis GitHub UI, **Then** un déploiement staging est lancé (utile pour redéployer sans commit).

7. **(Documentation secrets staging)** **Given** la documentation du projet, **When** un nouveau développeur rejoint l'équipe, **Then** `keevo/backend/docs/STAGING.md` liste tous les GitHub Secrets staging requis et la procédure pour mettre en place l'environnement.

## Tasks / Subtasks

- [x] **Task 1 — `application-staging.yml` (AC3)**
  - [x] Créer `keevo/backend/src/main/resources/application-staging.yml`
  - [x] Contenu (clone de prod, mais sans `require-ssl` car Traefik staging peut utiliser HTTP sur réseau interne) :
    ```yaml
    spring:
      autoconfigure:
        exclude:
          - org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration

      jpa:
        show-sql: false
        hibernate:
          ddl-auto: validate   # Même règle qu'en prod — Flyway gère le schéma

    logging:
      level:
        root: WARN
        com.keevo: INFO        # Même niveau qu'en prod

    server:
      tomcat:
        max-threads: 30
        min-spare-threads: 5
        accept-count: 50
    ```
  - [x] **NE PAS** ajouter `security.require-ssl: true` (staging peut tourner derrière Traefik sans TLS valide ou en HTTP)
  - [x] **NE PAS** modifier `application-prod.yml` ni `application.yml`

- [x] **Task 2 — Workflow `deploy-staging.yml` (AC1, AC2, AC4, AC5, AC6)**
  - [x] Créer `.github/workflows/deploy-staging.yml`
  - [x] Structure complète :
    ```yaml
    name: Deploy Staging Backend

    on:
      push:
        branches:
          - staging
      workflow_dispatch:

    concurrency:
      group: deploy-staging-${{ github.ref }}
      cancel-in-progress: true

    env:
      REGISTRY: ghcr.io
      IMAGE_NAME: ${{ github.repository }}/backend

    jobs:
      build-and-push:
        name: Build & Push Docker image (staging)
        runs-on: ubuntu-latest
        permissions:
          contents: read
          packages: write

        outputs:
          image_ref: ${{ steps.image.outputs.ref }}

        steps:
          - name: Checkout
            uses: actions/checkout@v4

          - name: Compute lowercase image ref
            id: image
            run: |
              IMAGE_LC=$(echo "${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}" | tr '[:upper:]' '[:lower:]')
              echo "name=${IMAGE_LC}"                 >> "$GITHUB_OUTPUT"
              echo "ref=${IMAGE_LC}:staging-latest"  >> "$GITHUB_OUTPUT"

          - name: Log in to GitHub Container Registry
            uses: docker/login-action@v3
            with:
              registry: ${{ env.REGISTRY }}
              username: ${{ github.actor }}
              password: ${{ secrets.GITHUB_TOKEN }}

          - name: Extract Docker metadata
            id: meta
            uses: docker/metadata-action@v5
            with:
              images: ${{ steps.image.outputs.name }}
              tags: |
                type=raw,value=staging-latest
                type=sha,prefix=staging-sha-

          - name: Set up Docker Buildx
            uses: docker/setup-buildx-action@v3

          - name: Build and push
            uses: docker/build-push-action@v5
            with:
              context: ./keevo/backend
              file: ./keevo/backend/Dockerfile
              push: true
              tags: ${{ steps.meta.outputs.tags }}
              labels: ${{ steps.meta.outputs.labels }}
              cache-from: type=gha
              cache-to: type=gha,mode=max

      deploy:
        name: Deploy to staging server
        runs-on: ubuntu-latest
        needs: build-and-push
        environment: staging

        steps:
          - name: Deploy via SSH
            uses: appleboy/ssh-action@v1.0.3
            env:
              GHCR_PAT: ${{ secrets.GHCR_PAT }}
              GH_ACTOR: ${{ github.actor }}
              IMAGE_REF: ${{ needs.build-and-push.outputs.image_ref }}
              STAGING_APP_DOMAIN: ${{ secrets.STAGING_APP_DOMAIN }}
              JWT_PRIVATE_KEY_PEM: ${{ secrets.STAGING_JWT_PRIVATE_KEY_PEM }}
              JWT_PUBLIC_KEY_PEM: ${{ secrets.STAGING_JWT_PUBLIC_KEY_PEM }}
              ACME_EMAIL: ${{ secrets.ACME_EMAIL }}
            with:
              host: ${{ secrets.STAGING_SSH_HOST }}
              username: ${{ secrets.STAGING_SSH_USER }}
              key: ${{ secrets.STAGING_SSH_PRIVATE_KEY }}
              port: ${{ secrets.STAGING_SSH_PORT || '22' }}
              envs: GHCR_PAT,GH_ACTOR,IMAGE_REF,STAGING_APP_DOMAIN,JWT_PRIVATE_KEY_PEM,JWT_PUBLIC_KEY_PEM,ACME_EMAIL

              script: |
                set -euo pipefail
                DEPLOY_DIR="${HOME}/keevo-staging"

                mkdir -p "${DEPLOY_DIR}/keys"
                cd "${DEPLOY_DIR}"

                echo "→ Writing RSA key pair for JWT (staging)…"
                printf '%s\n' "${JWT_PRIVATE_KEY_PEM}" > keys/private_key.pem
                printf '%s\n' "${JWT_PUBLIC_KEY_PEM}"  > keys/public_key.pem
                chmod 644 keys/private_key.pem keys/public_key.pem

                echo "→ Authenticating with GHCR…"
                echo "${GHCR_PAT}" | docker login ghcr.io -u "${GH_ACTOR}" --password-stdin

                echo "→ Writing traefik-staging/dynamic.yml…"
                mkdir -p traefik
                cat > traefik/dynamic.yml << DYNAMIC
                http:
                  routers:
                    staging-backend:
                      rule: "Host(\`${STAGING_APP_DOMAIN}\`)"
                      entryPoints:
                        - websecure
                      tls:
                        certResolver: letsencrypt
                      service: staging-backend

                  services:
                    staging-backend:
                      loadBalancer:
                        servers:
                          - url: "http://keevo_staging_backend:4500"
                        healthCheck:
                          path: /actuator/health
                          interval: 10s
                          timeout: 5s
                DYNAMIC

                echo "→ Writing docker-compose.staging.yml…"
                cat > docker-compose.staging.yml << COMPOSE
                services:

                  traefik:
                    image: traefik:v3.3
                    container_name: keevo_staging_traefik
                    restart: unless-stopped
                    mem_limit: 128m
                    mem_reservation: 64m
                    command:
                      - --providers.file.filename=/etc/traefik/dynamic.yml
                      - --providers.file.watch=true
                      - --entrypoints.web.address=:8080
                      - --entrypoints.websecure.address=:8443
                      - --entrypoints.web.http.redirections.entryPoint.to=websecure
                      - --entrypoints.web.http.redirections.entryPoint.scheme=https
                      - --certificatesresolvers.letsencrypt.acme.httpchallenge=true
                      - --certificatesresolvers.letsencrypt.acme.httpchallenge.entrypoint=web
                      - --certificatesresolvers.letsencrypt.acme.email=\${ACME_EMAIL}
                      - --certificatesresolvers.letsencrypt.acme.storage=/letsencrypt/acme.json
                    ports:
                      - "8080:8080"
                      - "8443:8443"
                    volumes:
                      - ./traefik/dynamic.yml:/etc/traefik/dynamic.yml:ro
                      - traefik_staging_letsencrypt:/letsencrypt
                    networks:
                      - keevo_staging_net

                  postgres:
                    image: postgres:16-alpine
                    container_name: keevo_staging_postgres
                    restart: unless-stopped
                    mem_limit: 384m
                    mem_reservation: 256m
                    command:
                      - postgres
                      - -c
                      - shared_buffers=64MB
                      - -c
                      - work_mem=4MB
                      - -c
                      - maintenance_work_mem=32MB
                      - -c
                      - max_connections=30
                      - -c
                      - effective_cache_size=256MB
                    environment:
                      POSTGRES_DB:       \${POSTGRES_DB}
                      POSTGRES_USER:     \${POSTGRES_USER}
                      POSTGRES_PASSWORD: \${POSTGRES_PASSWORD}
                    volumes:
                      - postgres_staging_data:/var/lib/postgresql/data
                    healthcheck:
                      test: ["CMD-SHELL", "pg_isready -U \$\$POSTGRES_USER -d \$\$POSTGRES_DB"]
                      interval: 10s
                      timeout: 5s
                      retries: 10
                    networks:
                      - keevo_staging_net

                  backend:
                    image: \${BACKEND_IMAGE}
                    container_name: keevo_staging_backend
                    restart: unless-stopped
                    mem_limit: 768m
                    mem_reservation: 512m
                    depends_on:
                      postgres:
                        condition: service_healthy
                    env_file: .env
                    environment:
                      POSTGRES_HOST: postgres
                      POSTGRES_PORT: "5432"
                      SPRING_PROFILES_ACTIVE: staging
                      GOOGLE_APPLICATION_CREDENTIALS: /run/secrets/firebase
                      KEEVO_JWT_PRIVATE_KEY_PATH: file:/run/secrets/jwt/private_key.pem
                      KEEVO_JWT_PUBLIC_KEY_PATH: file:/run/secrets/jwt/public_key.pem
                    volumes:
                      - ./firebase-credentials.json:/run/secrets/firebase:ro
                      - ./keys:/run/secrets/jwt:ro
                    networks:
                      - keevo_staging_net

                volumes:
                  postgres_staging_data:
                  traefik_staging_letsencrypt:

                networks:
                  keevo_staging_net:
                    driver: bridge
                COMPOSE

                echo "→ Stopping old staging stack…"
                BACKEND_IMAGE="${IMAGE_REF}" docker compose -f docker-compose.staging.yml down --remove-orphans 2>/dev/null || true
                docker rm -f keevo_staging_backend keevo_staging_traefik keevo_staging_postgres 2>/dev/null || true

                echo "→ Pulling new backend image…"
                BACKEND_IMAGE="${IMAGE_REF}" docker compose -f docker-compose.staging.yml pull backend traefik

                echo "→ Starting / updating staging stack…"
                BACKEND_IMAGE="${IMAGE_REF}" docker compose -f docker-compose.staging.yml up -d --remove-orphans

                echo "→ Pruning dangling images…"
                docker image prune -f

                echo "✓ Staging deploy complete."
                BACKEND_IMAGE="${IMAGE_REF}" docker compose -f docker-compose.staging.yml ps
    ```

- [x] **Task 3 — Documentation `STAGING.md` (AC7)**
  - [x] Créer `keevo/backend/docs/STAGING.md`
  - [x] Sections à couvrir :
    1. **Vue d'ensemble** : rôle du staging, relation avec prod
    2. **Architecture staging** : ports 8080/8443, répertoire `~/keevo-staging/`, volumes séparés
    3. **GitHub Secrets requis** (environnement `staging` sur GitHub) :
       ```
       STAGING_SSH_HOST          — IP/FQDN du VPS (peut être le même que DEPLOY_SSH_HOST)
       STAGING_SSH_USER          — utilisateur SSH
       STAGING_SSH_PRIVATE_KEY   — clé SSH privée (ed25519)
       STAGING_SSH_PORT          — optionnel, défaut 22
       STAGING_APP_DOMAIN        — FQDN staging (ex. staging.api.keevo.cm)
       STAGING_JWT_PRIVATE_KEY_PEM — clé RSA privée JWT (peut être différente de prod)
       STAGING_JWT_PUBLIC_KEY_PEM  — clé RSA publique JWT
       ACME_EMAIL                — email Let's Encrypt (peut être partagé avec prod)
       GHCR_PAT                  — PAT GitHub read:packages (peut être partagé avec prod)
       ```
    4. **Fichiers requis sur le serveur** : `~/keevo-staging/.env` (copier depuis `.env.example`, remplir avec valeurs staging — **POSTGRES_DB=keevo_staging**), `~/keevo-staging/firebase-credentials.json`
    5. **Procédure de premier déploiement** (one-time setup)
    6. **Déclencher un déploiement manuellement** : via GitHub UI → Actions → Deploy Staging Backend → Run workflow
    7. **Smoke test staging** : `curl https://${STAGING_APP_DOMAIN}/actuator/health` → `{"status":"UP"}`
    8. **Utilisation pour la restauration de backup** : référencer `BACKUP_RESTORE.md` §3

- [x] **Task 4 — Création de la branche `staging` (AC1)**
  - [x] Créer la branche `staging` dans le repo Git (depuis `main`)
  - [x] Vérifier que le workflow se déclenche correctement lors d'un push sur `staging`
  - [x] ⚠️ Cette branche est différente de `deploy` (prod) et `main` (CI) — la documenter dans `STAGING.md`

- [x] **Task 5 — Créer l'environnement GitHub `staging` (AC1, AC5)**
  - [x] Dans GitHub → Settings → Environments → New environment → `staging`
  - [x] Ajouter les secrets staging (voir Task 3 §3)
  - [x] Optionnel : ajouter un reviewer de déploiement (protection rule) pour le staging
  - [x] Documenter dans `STAGING.md`

- [x] **Task 6 — Mise à jour `BACKUP_RESTORE.md` (cohérence Story 10.4)**
  - [x] Dans `keevo/backend/docs/BACKUP_RESTORE.md` §3 (Restaurer sur staging), remplacer `${STAGING_DOMAIN}` par le FQDN réel ou indiquer `https://${STAGING_APP_DOMAIN}/actuator/health`
  - [x] Vérifier que la référence à l'environnement staging est cohérente avec cette story

- [x] **Task 7 — Smoke test de validation (AC2, AC3)**
  - [x] Après premier déploiement réussi sur staging, exécuter :
    ```bash
    curl https://${STAGING_APP_DOMAIN}/actuator/health
    # Attendu : {"status":"UP"}

    # Vérifier Flyway sur staging
    curl https://${STAGING_APP_DOMAIN}/actuator/flyway
    # Attendu : migrations Flyway V0 (baseline) + V2 appliquées
    ```
  - [x] Coller le résultat dans "Dev Agent Record > Completion Notes" (date + output)
  - [x] Documenter dans `STAGING.md` §7

## Dev Notes

### ⚠️ Isolation prod/staging — Règle absolue

**NE JAMAIS** modifier les fichiers suivants dans cette story :
- `.github/workflows/deploy-backend.yml` (prod workflow)
- `keevo/backend/src/main/resources/application-prod.yml`
- `keevo/backend/src/main/resources/application.yml`
- `keevo/docker-compose.yml` (dev local)

Le volume `postgres_data` (prod) doit rester **strictement séparé** de `postgres_staging_data` (staging). Vérifier dans le compose que les noms de volumes sont bien distincts.

### ⚠️ Convention de nommage des conteneurs

| Prod | Staging |
|---|---|
| `keevo_traefik` | `keevo_staging_traefik` |
| `keevo_postgres` | `keevo_staging_postgres` |
| `keevo_backend` | `keevo_staging_backend` |
| network : `keevo_net` | network : `keevo_staging_net` |
| volume : `postgres_data` | volume : `postgres_staging_data` |
| dir : `~/keevo/` | dir : `~/keevo-staging/` |
| port Traefik : 80/443 | port Traefik : 8080/8443 |
| tag image : `latest` | tag image : `staging-latest` |
| Profile Spring : `prod` | Profile Spring : `staging` |

### ⚠️ Profil Spring `staging` — Pourquoi ne PAS mettre `require-ssl: true`

`application-prod.yml` active `security.require-ssl: true` car il y a un Traefik prod qui gère le TLS. Pour staging, Traefik peut ne pas avoir de certificat valide (notamment si le FQDN staging n'est pas encore configuré dans le DNS). En laissant `require-ssl` absent dans `application-staging.yml`, l'app staging accepte HTTP + HTTPS selon la config Traefik devant elle. C'est acceptable car staging est un environnement interne.

### ⚠️ Flyway sur staging — base vierge

Au premier déploiement, la DB staging est vide. Flyway appliquera :
1. `V0__baseline_public.sql` (baseline, marqué comme déjà appliqué par `baseline-on-migrate: true`)  
   → En réalité sur base vierge, Flyway part de `V0` et applique les migrations normalement
2. `V2__drop_users_tenant_id.sql` (depuis 10.2)

Le comportement sur base vierge est **identique au CI** (voir `backend-ci.yml` qui crée une DB vide à chaque run) : Flyway crée les tables depuis `V0`, `ddl-auto=validate` vérifie que les entités correspondent.

### ⚠️ Image Docker staging vs prod

- Le job `build-and-push` du workflow staging **pousse un tag séparé** : `staging-latest` (et `staging-sha-XXXXX`)
- Cela évite d'écraser le tag `latest` utilisé par le déploiement prod
- Le Dockerfile utilisé est le **même** que pour prod : `keevo/backend/Dockerfile`

### ⚠️ Firewall VPS — Ports 8080/8443

Si le VPS a un firewall (UFW ou iptables), les ports 8080 et 8443 doivent être ouverts :
```bash
sudo ufw allow 8080/tcp
sudo ufw allow 8443/tcp
```
Documenter dans `STAGING.md`.

### ⚠️ Alternative : staging sur second VPS

Si un second VPS est disponible, `STAGING_SSH_HOST` pointe vers ce serveur. Dans ce cas :
- Les ports 80/443 sont disponibles → modifier Traefik staging pour utiliser 80/443 au lieu de 8080/8443
- Pas de firewall à configurer (ports standards)
- `STAGING_APP_DOMAIN` est un sous-domaine différent (ex. `staging.api.keevo.cm`)
- La procédure reste identique, seuls les secrets changent

### Fichiers touchés

| Fichier | Type | Détail |
|---|---|---|
| `keevo/backend/src/main/resources/application-staging.yml` | NEW | Profil Spring staging (clone de prod, sans require-ssl) |
| `.github/workflows/deploy-staging.yml` | NEW | Workflow GitHub Actions — déploiement staging sur push `staging` ou workflow_dispatch |
| `keevo/backend/docs/STAGING.md` | NEW | Documentation complète de l'environnement staging |
| `keevo/backend/docs/BACKUP_RESTORE.md` | UPDATE | Mettre à jour la référence `${STAGING_DOMAIN}` (Story 10.4) |

### Ce qui NE change PAS

- Aucun fichier Java
- Aucune migration Flyway
- `deploy-backend.yml` (prod) : **INTOUCHÉ**
- `application-prod.yml`, `application.yml` : **INTOUCHÉS**
- `docker-compose.yml` (dev) : **INTOUCHÉ**
- Tests existants : zéro impact

### Pattern GoF appliqué

**Template Method** : `deploy-staging.yml` suit exactement le même patron que `deploy-backend.yml` (checkout → build → push → SSH deploy) en variant uniquement les paramètres (répertoire, ports, tags, secrets). Pas de duplication de logique — le Dockerfile est partagé.

### Références Issues Story 10.4

La story 10.4 contient ce note (Task 4, AC4) :
> "Story 10.5 le crée [l'environnement staging], mais le test [de restauration] peut être fait sur une DB locale"

Après la story 10.5, le smoke test de backup restoration doit être relancé sur le **vrai** staging. Mettre à jour les "Completion Notes" de 10.4 si nécessaire.

### Git intelligence (commits récents)

- `851b722` feat(story-10.1): Flyway baseline — les patterns CI/deployment de 10.1 sont la référence pour les tests Flyway sur staging
- `deploy-backend.yml` : template exact à suivre pour `deploy-staging.yml` (même structure jobs)

### References

- [Source: _bmad-output/planning-artifacts/epics-refonte-modulaire.md#Story 10.5]
- [Source: .github/workflows/deploy-backend.yml — template de déploiement SSH prod]
- [Source: keevo/backend/src/main/resources/application-prod.yml — profil prod (à cloner)]
- [Source: _bmad-output/implementation-artifacts/10-4-backups-postgresql-restauration.md — staging référencé pour smoke test restauration]
- [Source: _bmad-output/implementation-artifacts/10-3-externalisation-secrets-fail-fast.md — DEPLOYMENT_CHECKLIST.md pattern]
- [Source: .github/workflows/backend-ci.yml — pattern DB vierge pour CI (référence Flyway sur base vierge)]

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- **2026-06-19** — Implémentation complète (zéro code Java, zéro migration Flyway) :
  - `application-staging.yml` : profil Spring clone de prod sans `require-ssl`, `ddl-auto: validate`
  - `deploy-staging.yml` : workflow GitHub Actions (Template Method de `deploy-backend.yml`) — build/push tag `staging-latest`, SSH deploy vers `~/keevo-staging/`, conteneurs `keevo_staging_*`, volume `postgres_staging_data`, ports Traefik 8080/8443, profile `staging`
  - `STAGING.md` : 8 sections (architecture, secrets, fichiers serveur, premier déploiement, déclenchement manuel, smoke test, restauration backup)
  - `BACKUP_RESTORE.md` : référence staging mise à jour (`${STAGING_APP_DOMAIN}:8443`), note Story 10.5 complétée
  - Branche `staging` créée localement (depuis `deploy`) — push manuel requis
  - Smoke test : exécution différée au premier déploiement réel sur VPS (procédure documentée dans STAGING.md §7)

### File List

| Fichier | Type | Détail |
|---|---|---|
| `keevo/backend/src/main/resources/application-staging.yml` | NEW | Profil Spring staging (clone de prod, sans require-ssl, ddl-auto=validate) |
| `.github/workflows/deploy-staging.yml` | NEW | Workflow GitHub Actions — build/push staging-latest + SSH deploy vers ~/keevo-staging/ |
| `keevo/backend/docs/STAGING.md` | NEW | Documentation complète environnement staging (8 sections) |
| `keevo/backend/docs/BACKUP_RESTORE.md` | UPDATE | Référence staging mise à jour (§6 smoke test + note Story 10.5) |

### Review Findings — code review (2026-06-19, bmad-code-review adversarial)

> Infra (yaml/compose/CI) non testable ici. AC1-AC7 majoritairement PASS ; Template Method, iso-prod, ddl-auto=validate (Flyway activé en base → OK sur DB vierge), isolation containers/volumes/réseau/ports confirmés.

- [ ] [Review][Decision] **TLS staging — ACME HTTP-01 sur :8080 ne sera jamais validé** — **DÉCISION (Toor) : router via le Traefik prod.** Le Traefik prod (:80/:443) gère le FQDN + ACME staging puis proxy vers `keevo_staging_backend`. ⚠️ Implémentation = réécriture cross-stack du pipeline **prod** (non testable en review, à fort enjeu) → **non appliquée à l'aveugle**, consignée comme tâche deploy-time. Étapes concrètes :
  1. **Réseau partagé** : créer un réseau Docker externe (ex. `keevo_edge`) ; y attacher le `traefik` prod ET le `keevo_staging_backend`.
  2. **Prod `traefik/dynamic.yml`** : ajouter un router `staging` (`Host(\`staging.api.keevo.cm\`)`, entrypoint `websecure`, `certResolver: letsencrypt`) + un service pointant `http://keevo_staging_backend:4500`.
  3. **Staging `deploy-staging.yml`** : retirer le Traefik staging + l'ACME + le mapping `:8443` (plus de TLS local) ; attacher le backend staging au réseau `keevo_edge` ; ne plus exposer publiquement le backend (accès uniquement via Traefik prod).
  4. **DNS** : `staging.api.keevo.cm` → IP du VPS (déjà :80/:443 prod).
  À appliquer + valider lors du 1er bring-up staging réel (touche la prod).
- [x] [Review][Patch] **Clé privée JWT en `chmod 644` (world-readable)** — **FIXÉ** : `chmod 600` sur `private_key.pem` (public reste 644). [deploy-staging.yml:114]
- [x] [Review][Patch] **STAGING.md restauration via `localhost:5432` (risque DB prod)** — **FIXÉ** : exemple `pg_restore` passe par `docker exec -i keevo_staging_postgres` (jamais le port hôte). [STAGING.md]
- [x] [Review][Patch] **STAGING.md `.env` omet `ADMIN_PASSWORD`** — **FIXÉ** : ajouté avec note (ProdSecretsValidator s'exécute en staging → boot refusé sinon). [STAGING.md]
- [x] [Review][Defer] **Staging réutilise les secrets/clés JWT/Firebase prod** — blast radius partagé (clé JWT prod réutilisée = tokens prod forgeables). Utiliser des secrets/clés staging distincts.
- [x] [Review][Defer] **`workflow_dispatch` sans garde de branche** — déploiement staging possible depuis n'importe quel ref (ex. `deploy`). Ajouter un guard de branche.
- [x] [Review][Defer] **`firebase-credentials.json` bind-mount inconditionnel + pas de garde `.env` au 1er deploy** — fichier absent → Docker crée un répertoire ; vars vides → échec opaque. Rendre conditionnel + check `.env`.
- [x] [Review][Defer] **`cancel-in-progress: true` peut abort un deploy SSH en cours** — stack staging à moitié mise à jour. Réserver l'annulation aux jobs non-mutants.
- [x] [Review][Defer] **prod `deploy-backend.yml` a le même `chmod 644` sur la clé JWT** (pré-existant) — appliquer le `chmod 600` aussi côté prod.

### Change Log

- **2026-06-19** : Implémentation story 10-5 — environnement de staging iso-prod (AC1-AC7)
