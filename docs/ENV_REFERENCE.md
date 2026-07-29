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

# Admin bootstrap
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

# Let's Encrypt
ACME_EMAIL=admin@<TON_DOMAINE>
```

---

## 3. Fichiers supplémentaires sur le serveur (`~/keevo/`)

| Fichier | Description |
|---------|-------------|
| `firebase-credentials.json` | Clé de compte de service Firebase (actuellement `keevo-1d289-firebase-adminsdk-fbsvc-4094494885.json`) |
| `.env.backup` | Variables pour les sauvegardes S3 : `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_DEFAULT_REGION`, `BACKUP_S3_BUCKET`, `BACKUP_S3_PREFIX` |

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

Le workflow génère `docker-compose.prod.yml` sur le serveur avec 3 services :

| Service | Image | Ports | Mémoire |
|---------|-------|-------|---------|
| `traefik` | `traefik:v3.3` | 80, 443 | 128 Mo |
| `postgres` | `postgres:16-alpine` | *(interne)* | 384 Mo |
| `backend` | `ghcr.io/<repo>/backend:latest` | *(interne:4500)* | 768 Mo |
