# Keevo — Checklist de Déploiement

> Tous les éléments ci-dessous doivent être configurés **avant** de pousser sur la branche `deploy`.

---

## 1. GitHub Actions Secrets

Aller dans : **Settings → Secrets and variables → Actions → Repository secrets**

### 1.1 Déploiement serveur

| Secret | Description | Exemple / Commande |
|---|---|---|
| `DEPLOY_SSH_HOST` | IP ou domaine du serveur | `44.223.221.74` |
| `DEPLOY_SSH_USER` | Utilisateur SSH | `ubuntu` |
| `DEPLOY_SSH_PRIVATE_KEY` | Clé privée SSH (contenu complet du fichier `.pem`) | `cat ~/.ssh/keevo-server.pem` |
| `DEPLOY_SSH_PORT` | Port SSH (optionnel, défaut = 22) | `22` |
| `GHCR_PAT` | Personal Access Token GitHub avec scope `write:packages` | GitHub → Settings → Developer settings → PAT |
| `APP_DOMAIN` | Domaine de production (sans `https://`) | `keevo.bookandgo.cloud` |

### 1.2 JWT (clés RSA 2048 bits)

Générer les clés si elles n'existent pas :
```bash
openssl genrsa -out private_key.pem 2048
openssl rsa -in private_key.pem -pubout -out public_key.pem
```

| Secret | Description | Commande pour obtenir la valeur |
|---|---|---|
| `JWT_PRIVATE_KEY_PEM` | Contenu complet du fichier `private_key.pem` | `cat private_key.pem` |
| `JWT_PUBLIC_KEY_PEM` | Contenu complet du fichier `public_key.pem` | `cat public_key.pem` |

> Les clés sont écrites dans `~/keevo/keys/` sur le serveur à chaque déploiement.

### 1.3 Android (build APK)

| Secret | Description | Commande |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | Keystore encodé en base64 | `base64 -w0 keevo-release.keystore` |
| `ANDROID_KEY_ALIAS` | Alias de la clé dans le keystore | ex. `upload` |
| `ANDROID_STORE_PASSWORD` | Mot de passe du keystore | — |
| `ANDROID_KEY_PASSWORD` | Mot de passe de la clé | — |

Créer un keystore si nécessaire :
```bash
keytool -genkey -v -keystore keevo-release.keystore \
  -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

---

## 2. Fichiers à créer sur le serveur

Se connecter au serveur (`ssh ubuntu@<HOST>`) et créer le répertoire :
```bash
mkdir -p ~/keevo
```

### 2.1 `~/keevo/.env`

```env
# ─── PostgreSQL ───────────────────────────────────────────────
POSTGRES_HOST=localhost        # ignoré — compose force "postgres"
POSTGRES_PORT=5444             # ignoré — compose force "5432"
POSTGRES_DB=keevo_dev
POSTGRES_USER=keevo
POSTGRES_PASSWORD=<mot_de_passe_fort>   # générer : openssl rand -hex 20

# ─── Domaine ──────────────────────────────────────────────────
APP_DOMAIN=keevo.bookandgo.cloud
ACME_EMAIL=<votre@email.com>            # pour Let's Encrypt

# ─── Spring ───────────────────────────────────────────────────
SPRING_PROFILES_ACTIVE=prod
APP_PORT=4500
LOG_LEVEL=WARN
FLYWAY_ENABLED=true

# ─── JWT (inutile ici — injecté via GitHub Secrets) ───────────
JWT_SECRET=unused_in_prod
JWT_EXPIRATION_MS=15552000000

# ─── FCM Push Notifications ───────────────────────────────────
KEEVO_FCM_ENABLED=true
GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase

# ─── WhatsApp (WasenderAPI) ───────────────────────────────────
KEEVO_WHATSAPP_PROVIDER=wassender
WASSENDER_API_URL=https://www.wasenderapi.com
WASSENDER_API_TOKEN=<votre_token_wassender>

# ─── Admin initial ────────────────────────────────────────────
ADMIN_PHONE=+237600000000
ADMIN_PASSWORD=<mot_de_passe_admin_fort>
```

**Générer un mot de passe PostgreSQL fort :**
```bash
openssl rand -hex 20
```

### 2.2 `~/keevo/firebase-credentials.json`

Copier le fichier de credentials Firebase (service account JSON) :
```bash
# Depuis votre machine locale :
scp keevo-1d289-firebase-adminsdk-fbsvc-XXXX.json ubuntu@<HOST>:~/keevo/firebase-credentials.json
```

Vérifier que c'est bien un fichier (pas un dossier) :
```bash
file ~/keevo/firebase-credentials.json
# doit afficher : JSON data
```

### 2.3 Répertoire `~/keevo/keys/`

Créé automatiquement par le workflow. Rien à faire manuellement.

---

## 3. Prérequis sur le serveur

```bash
# Docker Engine (v24+ recommandé)
docker --version

# Docker Compose plugin (v2.x)
docker compose version

# Ports ouverts (Security Group AWS ou pare-feu)
# - 22  : SSH
# - 80  : HTTP (redirect vers HTTPS par Traefik)
# - 443 : HTTPS
```

**Note importante :** le port 4500 du backend n'est **pas** exposé directement — il transite via Traefik.

---

## 4. DNS

Pointer le domaine vers l'IP du serveur **avant** le premier déploiement (nécessaire pour Let's Encrypt) :

| Enregistrement | Type | Valeur |
|---|---|---|
| `keevo.bookandgo.cloud` | A | `44.223.221.74` |

Vérifier la propagation :
```bash
dig keevo.bookandgo.cloud +short
# doit retourner : 44.223.221.74
```

---

## 5. Problème connu : Traefik Docker API version

Le workflow injecte `DOCKER_API_VERSION: "1.44"` dans Traefik, mais Traefik v3.3 l'ignore et envoie des requêtes `/v1.24/` au daemon Docker.

**Workaround actif** : le fichier `~/keevo/docker-compose.prod.yml` est maintenu manuellement sur le serveur avec la bonne configuration. Après un déploiement automatique, vérifier que Traefik découvre bien les containers :

```bash
docker logs --tail 20 keevo_traefik
# Attendu : "Configuration loaded from Docker"
# Erreur : "client version 1.24 is too old" → voir section 5.1
```

### 5.1 Si l'erreur Traefik persiste après déploiement

Réécrire manuellement le compose et redémarrer Traefik :
```bash
# Sur le serveur
BACKEND_IMAGE=ghcr.io/dalois-30/keevo-app/backend:latest \
  docker compose -f ~/keevo/docker-compose.prod.yml up -d --force-recreate traefik
```

---

## 6. Vérification post-déploiement

```bash
# 1. Tous les containers up
docker ps

# 2. Traefik sans erreurs
docker logs --tail 20 keevo_traefik

# 3. Backend démarré
docker logs --tail 30 keevo_backend

# 4. HTTPS fonctionnel
curl https://keevo.bookandgo.cloud/actuator/health
# Attendu : {"status":"UP"}

# 5. Certificat Let's Encrypt valide (pas self-signed)
curl -vI https://keevo.bookandgo.cloud 2>&1 | grep "issuer\|subject"
# Attendu : issuer: C=US; O=Let's Encrypt
```

---

## 7. Sauvegardes PostgreSQL

Le déploiement installe automatiquement le script de backup et le systemd timer via `deploy-backend.yml`.

### 7.1 Variables d'environnement backup

Créer `/home/ubuntu/keevo/.env.backup` (mode `600`) sur le serveur :

```env
POSTGRES_USER=keevo
POSTGRES_PASSWORD=<mot_de_passe_prod>
POSTGRES_DB=keevo_prod

# AWS (pour upload S3)
AWS_ACCESS_KEY_ID=<aws_key>
AWS_SECRET_ACCESS_KEY=<aws_secret>
AWS_DEFAULT_REGION=eu-west-1

# Bucket S3
BACKUP_S3_BUCKET=keevo-backups-prod
BACKUP_S3_PREFIX=keevo/
```

```bash
sudo chmod 600 /home/ubuntu/keevo/.env.backup
sudo chown ubuntu:ubuntu /home/ubuntu/keevo/.env.backup
```

### 7.2 Vérification post-déploiement

```bash
# 1. Le timer systemd est actif
systemctl status keevo-backup.timer
# Attendu : Active: active (waiting)

# 2. Prochaine exécution planifiée
systemctl list-timers keevo-backup.timer
# Attendu : NEXT = prochain 02:00 UTC

# 3. Test manuel immédiat
sudo systemctl start keevo-backup.service
journalctl -u keevo-backup.service --since "1 minute ago"
# Attendu : [BACKUP][INFO] Backup complete: keevo_backup_YYYY-MM-DDTHH-MM-SSZ.dump
```

### 7.3 Politique de cycle de vie S3

Appliquer la Lifecycle Policy sur le bucket (voir `BACKUP_RESTORE.md` §8 pour la configuration JSON).

---

## 8. Récapitulatif GitHub Secrets requis

| Secret | Obligatoire | Utilisé par |
|---|---|---|
| `DEPLOY_SSH_HOST` | ✅ | `deploy-backend.yml` |
| `DEPLOY_SSH_USER` | ✅ | `deploy-backend.yml` |
| `DEPLOY_SSH_PRIVATE_KEY` | ✅ | `deploy-backend.yml` |
| `DEPLOY_SSH_PORT` | ☐ optionnel | `deploy-backend.yml` |
| `GHCR_PAT` | ✅ | `deploy-backend.yml` |
| `APP_DOMAIN` | ✅ | `deploy-backend.yml`, `release-flutter.yml` |
| `JWT_PRIVATE_KEY_PEM` | ✅ | `deploy-backend.yml` |
| `JWT_PUBLIC_KEY_PEM` | ✅ | `deploy-backend.yml` |
| `ANDROID_KEYSTORE_BASE64` | ✅ | `release-flutter.yml` |
| `ANDROID_KEY_ALIAS` | ✅ | `release-flutter.yml` |
| `ANDROID_STORE_PASSWORD` | ✅ | `release-flutter.yml` |
| `ANDROID_KEY_PASSWORD` | ✅ | `release-flutter.yml` |
