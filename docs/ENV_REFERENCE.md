# Keevo — Référence des variables d'environnement

> Généré le 2026-07-27.  
> Ce fichier recense toutes les valeurs connues extraites du codebase.  
> ⚠️ **Ne jamais commiter les secrets réels dans le dépôt.**

---

## 1. GitHub Secrets (Settings → Secrets and variables → Actions)

| Secret | Description | Valeur connue / placeholder |
|--------|-------------|----------------------------|
| `GHCR_PAT` | GitHub PAT, scope `read:packages` | `ghp_...` *(à générer)* |
| `DEPLOY_SSH_HOST` | IP/hostname du serveur | *(à renseigner)* |
| `DEPLOY_SSH_USER` | Utilisateur SSH | *(à renseigner)* |
| `DEPLOY_SSH_PRIVATE_KEY` | Clé privée SSH (ed25519) | `-----BEGIN OPENSSH PRIVATE KEY-----\n...` |
| `DEPLOY_SSH_PORT` | Port SSH *(optionnel, défaut 22)* | `22` |
| `APP_DOMAIN` | Domaine public (Traefik + Let's Encrypt) | *(à renseigner, ex: keevo.lipagar.com)* |
| `JWT_PRIVATE_KEY_PEM` | Clé privée RSA 2048 PEM | `-----BEGIN PRIVATE KEY-----\n...` |
| `JWT_PUBLIC_KEY_PEM` | Clé publique RSA 2048 PEM | `-----BEGIN PUBLIC KEY-----\n...` |
| `ACME_EMAIL` | E-mail du compte Let's Encrypt | *(à renseigner)* |
| `ADMIN_DOMAIN` | Domaine du tableau de bord d'administration (sans `https://`, différent de `APP_DOMAIN`) — route Traefik + `DASHBOARD_ORIGIN` du backend. **Obligatoire** : le déploiement échoue sans lui | *(à renseigner, ex: admin.keevo.cm)* |
| `ADMIN_PHONE` | Numéro du super-admin créé au premier démarrage (même numéro que le super-admin déjà en prod) | `+237XXXXXXXXX` |
| `ADMIN_PASSWORD` | Mot de passe initial du super-admin : ≥ 12 caractères, 3 classes sur 4, ≤ 72 octets, ni défaut ni placeholder, sans `'`. Sert uniquement à la création du compte, n'écrase jamais un mot de passe existant | *(à générer)* |
| `ADMIN_STAGING_DOMAIN` | *(environnement `staging`, optionnel)* domaine du tableau de bord de staging → `DASHBOARD_ORIGIN` du backend de staging | *(ex: staging.admin.keevo.cm)* |

> `GITHUB_TOKEN` est auto-fourni par GitHub Actions.

---

## 2. Fichier `.env` serveur (`~/keevo/.env`)

| Variable | Valeur dev actuelle | Valeur prod recommandée |
|----------|---------------------|------------------------|
| `POSTGRES_HOST` | `localhost` | `postgres` (nom du service docker-compose) |
| `POSTGRES_PORT` | `5444` | `5432` |
| `POSTGRES_DB` | `keevo_dev` | `keevo` |
| `POSTGRES_USER` | `keevo` | `keevo` |
| `POSTGRES_PASSWORD` | `keevo_local_pwd` | ⚠️ **Générer un mdp fort** |
| `JWT_SECRET` | `super_secret_local_jwt_key_that_is_long_enough_for_dev_only_usage` | ⚠️ `openssl rand -base64 64` |
| `JWT_EXPIRATION_MS` | `15552000000` (180 jours) | `15552000000` |
| `ADMIN_PHONE` | *(défaut: `+237600000000`)* | ⚠️ **Ton vrai numéro** |
| `ADMIN_PASSWORD` | *(défaut: `Admin@1234!`)* | ⚠️ **Mdp fort — `Admin@1234!` rejeté en prod** |
| `SPRING_PROFILES_ACTIVE` | `dev` | `prod` |
| `APP_PORT` | `4500` | `4500` |
| `FLYWAY_ENABLED` | `true` | `true` |
| `LOG_LEVEL` | `DEBUG` | `INFO` |
| `KEEVO_FCM_ENABLED` | `true` | `true` |
| `GOOGLE_APPLICATION_CREDENTIALS` | `/home/toor/Project/FreeLance/AI/Keevo/keevo/keevo-1d289-firebase-adminsdk-fbsvc-4094494885.json` | `/run/secrets/firebase` |
| `KEEVO_WHATSAPP_PROVIDER` | `wassender` | `wassender` (ou `noop`) |
| `WASSENDER_API_URL` | `https://www.wasenderapi.com` | `https://www.wasenderapi.com` |
| `WASSENDER_API_TOKEN` | `628d5b5845e63ce015ad22f4e9915a2c19d7506dac270440e28c024c2fee6573` | ⚠️ **Régénérer le token** |
| `ACME_EMAIL` | *(non défini)* | **Requis** — email pour Let's Encrypt |
| `KEEVO_HELP_TUTORIAL_VENDRE_URL` | *(vide)* | Lien http(s) (YouTube…) de la vidéo « Vendre » — vide = « Bientôt disponible » |
| `KEEVO_HELP_TUTORIAL_CATALOGUE_URL` | *(vide)* | Lien http(s) (YouTube…) de la vidéo « Catalogue & stock » — vide = « Bientôt disponible » |
| `KEEVO_HELP_TUTORIAL_INVENTAIRE_URL` | *(vide)* | Lien http(s) (YouTube…) de la vidéo « Inventaire » — vide = « Bientôt disponible » |
| `KEEVO_HELP_TUTORIAL_RAPPORTS_URL` | *(vide)* | Lien http(s) (YouTube…) de la vidéo « Rapports » — vide = « Bientôt disponible » |
| `KEEVO_HELP_TUTORIAL_EQUIPE_URL` | *(vide)* | Lien http(s) (YouTube…) de la vidéo « Équipe » — vide = « Bientôt disponible » |
| `KEEVO_HELP_TUTORIAL_HORS_LIGNE_URL` | *(vide)* | Lien http(s) (YouTube…) de la vidéo « Hors ligne & synchro » — vide = « Bientôt disponible » |

### Template `.env` prod

```ini
# PostgreSQL
POSTGRES_HOST=postgres
POSTGRES_PORT=5432
POSTGRES_DB=keevo
POSTGRES_USER=keevo
POSTGRES_PASSWORD=<MOT_DE_PASSE_FORT>

# JWT
JWT_SECRET=<OUTPUT_DE_openssl_rand_-base64_64>
JWT_EXPIRATION_MS=15552000000

# Admin bootstrap — en prod, remplacés par ~/keevo/.env.admin (secrets GitHub ADMIN_PHONE /
# ADMIN_PASSWORD, écrit par le workflow). DASHBOARD_ORIGIN est imposé par le compose
# (https://<ADMIN_DOMAIN>) : inutile de le mettre ici.
ADMIN_PHONE=+237XXXXXXXXX
ADMIN_PASSWORD=<MOT_DE_PASSE_ADMIN_FORT>

# Spring
SPRING_PROFILES_ACTIVE=prod
APP_PORT=4500

# Flyway
FLYWAY_ENABLED=true

# Logging
LOG_LEVEL=INFO

# FCM Push
KEEVO_FCM_ENABLED=true

# WhatsApp (WasenderAPI)
KEEVO_WHATSAPP_PROVIDER=wassender
WASSENDER_API_URL=https://www.wasenderapi.com
WASSENDER_API_TOKEN=<NOUVEAU_TOKEN>

# Aide & Tutoriels (FR7) — liens externes, vide = « Bientôt disponible »
KEEVO_HELP_TUTORIAL_VENDRE_URL=
KEEVO_HELP_TUTORIAL_CATALOGUE_URL=
KEEVO_HELP_TUTORIAL_INVENTAIRE_URL=
KEEVO_HELP_TUTORIAL_RAPPORTS_URL=
KEEVO_HELP_TUTORIAL_EQUIPE_URL=
KEEVO_HELP_TUTORIAL_HORS_LIGNE_URL=

# Let's Encrypt
ACME_EMAIL=admin@<TON_DOMAINE>
```

### Build de l'app mobile (`--dart-define`)

| Flag | Défaut | Rôle |
|------|--------|------|
| `KEEVO_APP_DOWNLOAD_URL` | *(vide)* | Lien de téléchargement inséré dans l'invitation WhatsApp d'un employé (FR65). Vide = le message dit « Installez l'application Keevo » sans lien. Ex. : `flutter build apk --dart-define=KEEVO_APP_DOWNLOAD_URL=https://play.google.com/store/apps/details?id=…` |

---

## 3. Fichiers supplémentaires sur le serveur (`~/keevo/`)

| Fichier | Description |
|---------|-------------|
| `.env.admin` | Écrit par le workflow (mode 600) depuis les secrets `ADMIN_PHONE` / `ADMIN_PASSWORD` ; lu par le backend après `.env` — ne pas éditer |
| `firebase-credentials.json` | Clé de compte de service Firebase (actuellement `keevo-1d289-firebase-adminsdk-fbsvc-4094494885.json`) |
| `.env.backup` | Variables pour les sauvegardes S3 : `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_DEFAULT_REGION`, `BACKUP_S3_BUCKET`, `BACKUP_S3_PREFIX` ; optionnels `BACKUP_PHOTOS` (1 par défaut, archive aussi les photos), `PHOTOS_CONTAINER` (défaut `keevo_backend`) |

---

## 4. Prérequis serveur (une seule fois, en root)

```bash
sudo groupadd -g 1001 keevo-jwt
sudo usermod -aG keevo-jwt "${USER:-$(id -un)}"
# Se reconnecter après
```

---

## 5. Clés RSA JWT

Les clés en dev sont dans `keevo/backend/src/main/resources/keys/`.  
Pour la prod, générer de nouvelles clés :

```bash
openssl genpkey -algorithm RSA -out private_key.pem -pkeyopt rsa_keygen_bits:2048
openssl rsa -pubout -in private_key.pem -out public_key.pem
```

Le contenu de `private_key.pem` → secret GitHub `JWT_PRIVATE_KEY_PEM`  
Le contenu de `public_key.pem` → secret GitHub `JWT_PUBLIC_KEY_PEM`

---

## 6. Docker Compose (généré par le workflow)

`deploy-backend.yml` (push sur `deploy`) génère un seul `docker-compose.prod.yml` sur le serveur avec
tous les services — `docker compose up` les démarre ensemble. Variante « pile complète » (port 80 libre)
avec `traefik` (fournisseur fichier `traefik/dynamic.yml`), variante « légère » sans `traefik` (Traefik
externe, labels sur le réseau `traefik-public`).

| Service | Conteneur | Image | Ports | Mémoire | Routage |
|---------|-----------|-------|-------|---------|---------|
| `traefik` | `keevo_traefik` | `traefik:v3.3` | 80, 443 | 128 Mo | *(pile complète uniquement)* |
| `postgres` | `keevo_postgres` | `postgres:16-alpine` | *(interne)* | 384 Mo | — |
| `backend` | `keevo_backend` | `ghcr.io/<repo>/backend:latest` | *(interne:4500)* | 768 Mo | `https://<APP_DOMAIN>` |
| `dashboard` | `keevo_dashboard` | `ghcr.io/<repo>/dashboard:latest` | *(interne:3000)* | 256 Mo | `https://<ADMIN_DOMAIN>` |

Le tableau de bord appelle l'API côté serveur (`/api/backend/*` → `http://backend:4500/api/v1/*` sur
le réseau `keevo_net`) ; cette adresse est figée dans l'image à la construction (voir
`keevo/dashboard/Dockerfile`). Les images ne sont reconstruites que si leur dossier a changé
(`.github/scripts/changed-components.sh`), l'autre est reprise depuis `:latest`. Staging
(`deploy-staging.yml`) : mêmes services `backend` + `dashboard` (`keevo_staging_*`, tags `staging-latest`).

Volumes persistants (nommés, conservés entre les déploiements — jamais `docker compose down -v`) :

| Volume | Monté dans | Contenu | Sauvegarde |
|--------|-----------|---------|------------|
| `postgres_data` | `postgres:/var/lib/postgresql/data` | Base de données | `backup-db.sh` (pg_dump) |
| `product_photos` | `backend:/app/data/product-photos` | Photos produit (vignettes JPEG, seule copie) — `KEEVO_PRODUCT_PHOTO_DIR` | `backup-db.sh` (`keevo_photos_<date>.tar.gz`) |
| `traefik_letsencrypt` | `traefik:/letsencrypt` | Certificats TLS | — (réémis automatiquement) |
