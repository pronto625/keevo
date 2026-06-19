# Keevo — Environnement de Staging

> Documentation opérationnelle pour l'environnement de staging iso-prod.

---

## Table des matières

1. [Vue d'ensemble](#1-vue-densemble)
2. [Architecture staging](#2-architecture-staging)
3. [GitHub Secrets requis](#3-github-secrets-requis)
4. [Fichiers requis sur le serveur](#4-fichiers-requis-sur-le-serveur)
5. [Procédure de premier déploiement](#5-procédure-de-premier-déploiement)
6. [Déclencher un déploiement manuellement](#6-déclencher-un-déploiement-manuellement)
7. [Smoke test staging](#7-smoke-test-staging)
8. [Utilisation pour la restauration de backup](#8-utilisation-pour-la-restauration-de-backup)

---

## 1. Vue d'ensemble

L'environnement **staging** reproduit la production sur le même VPS, avec une isolation complète :

- **DB dédiée** (`keevo_staging`) — jamais le volume prod
- **Conteneurs préfixés** `keevo_staging_*` — pas de collision avec `keevo_*` (prod)
- **Traefik sur ports 8080/8443** — pas de conflit avec Traefik prod (80/443)
- **Profile Spring `staging`** — clone de `prod` sans `require-ssl`

**Rôle du staging :**
- Valider les migrations Flyway avant la production
- Vérifier la restauration des backups (voir `BACKUP_RESTORE.md` §5)
- Tester les déploiements en conditions réelles
- Servir de cible pour les tests canary (futur)

**Relation avec la prod :** le staging utilise le même Dockerfile et les mêmes images Docker que la production. Seuls les paramètres (secrets, ports, volumes, profile Spring) diffèrent.

---

## 2. Architecture staging

```
┌─────────────────────────────────────────────────────────────────────┐
│ VPS Keevo                                                           │
│                                                                     │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐ │
│  │ PRODUCTION (~/keevo/)        │  │ STAGING (~/keevo-staging/)   │ │
│  │                              │  │                              │ │
│  │ keevo_traefik  (80/443)     │  │ keevo_staging_traefik (8080/ │ │
│  │ keevo_postgres (internal)    │  │ keevo_staging_postgres       │ │
│  │ keevo_backend  (:4500)      │  │ keevo_staging_backend (:4500)│ │
│  │                              │  │                              │ │
│  │ volume: postgres_data        │  │ volume: postgres_staging_data│ │
│  │ network: keevo_net           │  │ network: keevo_staging_net   │ │
│  │ profile: prod                │  │ profile: staging             │ │
│  │ tag: latest                  │  │ tag: staging-latest          │ │
│  └──────────────────────────────┘  └──────────────────────────────┘ │
│                                                                     │
│  Firewall: ports 80, 443, 8080, 8443 ouverts                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Convention de nommage

| Ressource | Production | Staging |
|---|---|---|
| Répertoire | `~/keevo/` | `~/keevo-staging/` |
| Traefik | `keevo_traefik` | `keevo_staging_traefik` |
| PostgreSQL | `keevo_postgres` | `keevo_staging_postgres` |
| Backend | `keevo_backend` | `keevo_staging_backend` |
| Réseau | `keevo_net` | `keevo_staging_net` |
| Volume DB | `postgres_data` | `postgres_staging_data` |
| Ports Traefik | 80 / 443 | 8080 / 8443 |
| Tag image | `latest` | `staging-latest` |
| Profile Spring | `prod` | `staging` |
| Domaine | `api.keevo.cm` | `staging.api.keevo.cm` |

---

## 3. GitHub Secrets requis

Créer un **environnement GitHub** nommé `staging` dans **Settings → Environments → New environment**.

### Secrets de l'environnement `staging`

| Secret | Description | Exemple |
|---|---|---|
| `STAGING_SSH_HOST` | IP ou FQDN du VPS (peut être identique à `DEPLOY_SSH_HOST`) | `123.45.67.89` |
| `STAGING_SSH_USER` | Utilisateur SSH | `ubuntu` |
| `STAGING_SSH_PRIVATE_KEY` | Clé SSH privée (ed25519) | `-----BEGIN OPENSSH PRIVATE KEY-----...` |
| `STAGING_SSH_PORT` | Port SSH (optionnel, défaut 22) | `22` |
| `STAGING_APP_DOMAIN` | FQDN du domaine staging | `staging.api.keevo.cm` |
| `STAGING_JWT_PRIVATE_KEY_PEM` | Clé RSA privée pour JWT (peut être différente de prod) | `-----BEGIN RSA PRIVATE KEY-----...` |
| `STAGING_JWT_PUBLIC_KEY_PEM` | Clé RSA publique pour JWT | `-----BEGIN PUBLIC KEY-----...` |
| `ACME_EMAIL` | Email Let's Encrypt (peut être partagé avec prod) | `admin@keevo.cm` |
| `GHCR_PAT` | Personal Access Token GitHub avec scope `read:packages` (peut être partagé avec prod) | `ghp_XXXX...` |

> **Note :** Les clés JWT staging peuvent être identiques à celles de prod pour simplifier, ou différentes pour isoler les tokens entre environnements.

### Protection rules (optionnel)

- **Required reviewers** : ajouter un reviewer pour le déploiement staging si souhaité
- **Wait timer** : non nécessaire pour staging

---

## 4. Fichiers requis sur le serveur

### 4.1 Fichier `.env`

Créer `~/keevo-staging/.env` en copiant depuis `backend/.env.example` et en adaptant les valeurs staging :

```bash
# ~/keevo-staging/.env
POSTGRES_DB=keevo_staging          # ⚠️ IMPORTANT : jamais keevo_prod
POSTGRES_USER=keevo
POSTGRES_PASSWORD=<staging_password>

# ⚠️ REQUIS — ProdSecretsValidator s'exécute aussi sous le profil `staging`.
# Sans ADMIN_PASSWORD fort (≠ Admin@1234!), le backend refuse de démarrer.
ADMIN_PASSWORD=<staging_admin_password>

# Keevo config (mêmes valeurs que prod, ou valeurs staging)
KEEVO_APP_NAME=Keevo Staging
KEEVO_DEFAULT_COUNTRY=CM
KEEVO_DEFAULT_CURRENCY=XAF
KEEVO_DEFAULT_LANGUAGE=fr
KEEVO_TRIAL_DAYS=14

# Firebase (optionnel si pas de notifications en staging)
FIREBASE_PROJECT_ID=<project_id>

# WhatsApp Wassender (optionnel en staging)
WASSENDER_API_URL=https://api.wassender.com
WASSENDER_API_TOKEN=<staging_token_ou_vide>

# Logging
LOG_LEVEL=WARN
```

> **⚠️ Sécurité :** Le fichier `.env` contient des secrets. Mode `600` recommandé. Ne jamais commiter.

### 4.2 Fichier Firebase

Copier `firebase-credentials.json` dans `~/keevo-staging/` :

```bash
scp firebase-credentials.json ubuntu@<VPS_HOST>:~/keevo-staging/firebase-credentials.json
```

### 4.3 Clés JWT

Les clés JWT sont écrites automatiquement par le workflow de déploiement depuis les secrets GitHub dans `~/keevo-staging/keys/`. Pas de configuration manuelle nécessaire.

---

## 5. Procédure de premier déploiement

### 5.1 Prérequis serveur

```bash
# Docker + Docker Compose plugin (si pas déjà installés)
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER

# Ouvrir les ports staging dans le firewall
sudo ufw allow 8080/tcp
sudo ufw allow 8443/tcp
sudo ufw reload

# Créer le répertoire staging
mkdir -p ~/keevo-staging
```

### 5.2 Configuration DNS

Ajouter un enregistrement DNS pour le domaine staging :

```
TYPE    NAME                     VALUE
A       staging.api.keevo.cm     <VPS_IP>
```

### 5.3 Configuration GitHub

1. Créer l'environnement `staging` dans **Settings → Environments**
2. Ajouter tous les secrets listés en [§3](#3-github-secrets-requis)
3. Vérifier que le repo a accès à GHCR (GitHub Container Registry)

### 5.4 Fichiers serveur

```bash
# Sur le VPS
cd ~/keevo-staging

# Copier .env.example et adapter
cp /path/to/repo/keevo/backend/.env.example .env
nano .env   # Adapter les valeurs (POSTGRES_DB=keevo_staging !)

# Copier les credentials Firebase
cp ~/keevo/firebase-credentials.json .
```

### 5.5 Branche Git

```bash
# Depuis main (ou deploy)
git checkout main
git checkout -b staging
git push origin staging
```

Le premier push sur `staging` déclenche automatiquement le workflow `deploy-staging.yml`.

### 5.6 Vérification

```bash
# Vérifier que les conteneurs tournent
ssh ubuntu@<VPS_HOST> "cd ~/keevo-staging && docker compose -f docker-compose.staging.yml ps"

# Attendu : 3 conteneurs Up (traefik, postgres, backend)

# Vérifier le health endpoint
curl -k https://staging.api.keevo.cm:8443/actuator/health
# Attendu : {"status":"UP"}
```

---

## 6. Déclencher un déploiement manuellement

1. Aller sur GitHub → **Actions** → **Deploy Staging Backend**
2. Cliquer sur **Run workflow**
3. Sélectionner la branche `staging`
4. Cliquer sur **Run workflow**

Le déploiement s'exécute avec les mêmes étapes qu'un push automatique.

**Use cases :**
- Redéployer sans commit (ex. après changement de secrets)
- Tester un hotfix sur staging avant merge
- Forcer un redémarrage des conteneurs staging

---

## 7. Smoke test staging

Après chaque déploiement, vérifier :

```bash
# Health check
curl -k https://${STAGING_APP_DOMAIN}:8443/actuator/health
# Attendu : {"status":"UP"}

# Vérifier les migrations Flyway
curl -k https://${STAGING_APP_DOMAIN}:8443/actuator/flyway
# Attendu : migrations V0 (baseline) + V2 appliquées

# Vérifier que la DB est bien staging (pas prod)
ssh ubuntu@<VPS_HOST> "docker exec keevo_staging_postgres psql -U keevo -d keevo_staging -c 'SELECT current_database();'"
# Attendu : keevo_staging
```

> **Note :** Le port `:8443` est nécessaire car Traefik staging écoute sur 8443 (pas 443). Si le DNS pointe vers le domaine staging et que TLS est configuré, le port peut être omis via un reverse proxy.

---

## 8. Utilisation pour la restauration de backup

L'environnement staging sert de cible pour les tests de restauration de backups (voir `BACKUP_RESTORE.md` §5).

**Procédure rapide :**

1. Télécharger le dernier backup depuis S3 ou le cache local
2. Restaurer dans la DB staging (`keevo_staging`)
3. Vérifier les schémas restaurés
4. Redémarrer le backend staging pour valider le schéma

```bash
# Exemple : restaurer un backup dans la DB staging.
# ⚠️ Toujours passer par le conteneur staging (jamais `-h localhost -p 5432` :
# le port 5432 de l'hôte peut pointer vers la DB PROD → restauration destructrice).
cat keevo_backup_YYYY-MM-DDTHH-MM-SSZ.dump | docker exec -i keevo_staging_postgres \
  pg_restore -U keevo -d keevo_staging \
  --no-owner --no-acl --clean --if-exists
```

> **⚠️ Attention :** Ne jamais restaurer un backup prod directement dans la DB prod sans tester d'abord sur staging.

---

## Branches Git

| Branche | Rôle | Workflow |
|---|---|---|
| `main` | Code source, CI uniquement | `backend-ci.yml` |
| `deploy` | Déploiement production | `deploy-backend.yml` |
| `staging` | Déploiement staging | `deploy-staging.yml` |

Pour déployer en staging, merger ou pousser sur la branche `staging`. Pour la prod, utiliser `deploy`.
