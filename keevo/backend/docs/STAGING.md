# Keevo — Environnement de Staging

> Documentation opérationnelle pour l'environnement de staging iso-prod.
>
> Story 10.7 : le staging n'a plus son propre Traefik ni de port publié — il est servi en HTTPS par
> le **Traefik prod** via le réseau externe `keevo_edge`. Procédures serveur (mise en route, routeur
> Traefik, secrets) : [`DEPLOYMENT_CHECKLIST.md`](DEPLOYMENT_CHECKLIST.md) §14 et §15.

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
- **Aucun Traefik ni port publié** — TLS terminé par le Traefik prod (80/443), routeur `staging`
- **Secrets dédiés** (JWT, mot de passe DB, Firebase) — jamais ceux de la prod
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
│  │ keevo_traefik  (80/443) ─────┼──┼─► staging.api.keevo.cm        │ │
│  │ keevo_postgres (internal)    │  │ keevo_staging_postgres       │ │
│  │ keevo_backend  (:4500)      │  │ keevo_staging_backend (:4500)│ │
│  │                              │  │                              │ │
│  │ volume: postgres_data        │  │ volume: postgres_staging_data│ │
│  │ network: keevo_net (+edge)   │  │ net: keevo_staging_net+edge  │ │
│  │ profile: prod                │  │ profile: staging             │ │
│  │ tag: latest                  │  │ tag: staging-latest          │ │
│  └──────────────────────────────┘  └──────────────────────────────┘ │
│                                                                     │
│  Réseau externe keevo_edge : Traefik prod + keevo_staging_backend   │
│  Firewall: ports 80, 443 uniquement (8080/8443 fermés)              │
└─────────────────────────────────────────────────────────────────────┘
```

### Convention de nommage

| Ressource | Production | Staging |
|---|---|---|
| Répertoire | `~/keevo/` | `~/keevo-staging/` |
| Traefik | `keevo_traefik` | *(aucun — Traefik prod, routeur `staging`)* |
| PostgreSQL | `keevo_postgres` | `keevo_staging_postgres` |
| Backend | `keevo_backend` | `keevo_staging_backend` |
| Réseau | `keevo_net` | `keevo_staging_net` + `keevo_edge` (externe) |
| Volume DB | `postgres_data` | `postgres_staging_data` |
| Ports publiés | 80 / 443 (Traefik) | aucun |
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
| `STAGING_JWT_PRIVATE_KEY_PEM` | Clé RSA privée JWT **dédiée** (jamais celle de prod — refusée par le workflow) | contenu PEM |
| `STAGING_JWT_PUBLIC_KEY_PEM` | Clé RSA publique correspondante | contenu PEM |
| `STAGING_DB_PASSWORD` | Mot de passe PostgreSQL staging (remplace celui du `.env`) | `openssl rand -hex 20` |
| `STAGING_FIREBASE_CREDENTIALS` | Optionnel — JSON du compte de service Firebase **staging** (non défini → pas de montage, FCM désactivé) | contenu JSON |
| `GHCR_PAT` | Personal Access Token GitHub avec scope `read:packages` (peut être partagé avec prod) | `ghp_XXXX...` |

> **Note (Story 10.7) :** les clés JWT, le mot de passe DB et le compte Firebase du staging sont
> **dédiés** : une compromission du staging ne doit jamais permettre de forger des tokens prod.
> Le workflow échoue avant tout accès au serveur si un secret requis manque. `STAGING_APP_DOMAIN`
> et `ACME_EMAIL` ne sont plus utilisés (le TLS est géré par le Traefik prod).

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
# POSTGRES_PASSWORD : fourni par le secret STAGING_DB_PASSWORD (prioritaire sur ce fichier)

# CORS (Story 11.4) : origine du dashboard staging
DASHBOARD_ORIGIN=https://<origine_dashboard_staging>

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
> Sans `.env`, le déploiement s'arrête (`missing .env`).

### 4.2 Fichier Firebase

Écrit par le workflow depuis le secret `STAGING_FIREBASE_CREDENTIALS` (groupe 2001, mode `640`) et
monté uniquement dans ce cas. **Ne pas** copier le fichier prod : sans secret, le workflow supprime
tout `firebase-credentials.json` résiduel.

### 4.3 Clés JWT

Les clés JWT sont écrites automatiquement par le workflow de déploiement depuis les secrets GitHub dans `~/keevo-staging/keys/`, avec la même stratégie que la prod (`chgrp 2001` + `640`, assertions avant démarrage). Prérequis hôte : l'utilisateur de déploiement est membre du groupe `keevo-jwt` (GID 2001), voir `DEPLOYMENT_CHECKLIST.md` §8.1.

---

## 5. Procédure de premier déploiement

### 5.1 Prérequis serveur

```bash
# Docker + Docker Compose plugin (si pas déjà installés)
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER

# Aucun port staging à ouvrir (servi par le Traefik prod via keevo_edge).
# Si un ancien staging avait ouvert 8080/8443 : les refermer
sudo ufw delete allow 8080/tcp; sudo ufw delete allow 8443/tcp

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

# Firebase : rien à copier (secret STAGING_FIREBASE_CREDENTIALS)
```

### 5.4 bis Routage TLS via le Traefik prod

Une seule fois, au premier bring-up réel (touche la prod) : `DEPLOYMENT_CHECKLIST.md` §15
(réseau `keevo_edge`, routeur `keevo/ops/traefik/staging-router.yml`, DNS, vérification, rollback).

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
ssh ubuntu@<VPS_HOST> "cd ~/keevo-staging && ./compose.sh ps"

# Attendu : 2 conteneurs Up (postgres, backend)

# Vérifier le health endpoint (certificat valide, pas de -k)
curl https://staging.api.keevo.cm/actuator/health
# Attendu : {"status":"UP"}
```

---

## 6. Déclencher un déploiement manuellement

1. Aller sur GitHub → **Actions** → **Deploy Staging Backend**
2. Cliquer sur **Run workflow**
3. Sélectionner la branche `staging`
4. Cliquer sur **Run workflow**

Le déploiement s'exécute avec les mêmes étapes qu'un push automatique. Lancé depuis une autre
branche que `staging`, le workflow n'exécute **aucun** job. Le déploiement en cours n'est jamais
annulé ; seul le lancement en attente le plus récent est conservé (un plus ancien en attente est
remplacé). Le job échoue si `keevo_staging_backend` n'est pas sain sous 120 s.

**Opérations manuelles sur le serveur** : toujours via `~/keevo-staging/compose.sh`, écrit à chaque
déploiement (commande compose complète, avec le fichier Firebase s'il est actif ; mot de passe DB
lu dans `.env.secrets`, mode `600`) :

```bash
cd ~/keevo-staging
./compose.sh ps
./compose.sh logs -f backend
./compose.sh up -d backend      # après une modification du .env
```

**Use cases :**
- Redéployer sans commit (ex. après changement de secrets)
- Tester un hotfix sur staging avant merge
- Forcer un redémarrage des conteneurs staging

---

## 7. Smoke test staging

Après chaque déploiement, vérifier :

```bash
# Health check
curl https://staging.api.keevo.cm/actuator/health
# Attendu : {"status":"UP"}

# Vérifier les migrations Flyway
curl https://staging.api.keevo.cm/actuator/flyway
# Attendu : migrations V0 (baseline) + V2 appliquées

# Vérifier que la DB est bien staging (pas prod)
ssh ubuntu@<VPS_HOST> "docker exec keevo_staging_postgres psql -U keevo -d keevo_staging -c 'SELECT current_database();'"
# Attendu : keevo_staging
```

> **Note :** le staging est servi sur le port 443 standard par le Traefik prod (Story 10.7) ; le
> backend staging ne publie aucun port (`docker port keevo_staging_backend` → vide).

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
