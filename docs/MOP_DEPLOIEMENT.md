# Keevo — MOP Déploiement Complet

> Mode Opératoire Pas-à-pas — de zéro à prod.  
> Date : 2026-07-27

---

## Étape 0 — Prérequis machine locale

```bash
# Vérifier que tu as ces outils
docker --version        # ≥ 24
openssl version         # n'importe quelle version
ssh-keygen -t ed25519   # si pas déjà de clé SSH
```

---

## Étape 1 — Générer les clés RSA JWT

```bash
cd /tmp
openssl genpkey -algorithm RSA -out private_key.pem -pkeyopt rsa_keygen_bits:2048
openssl rsa -pubout -in private_key.pem -out public_key.pem

# Vérifier
head -1 private_key.pem   # doit afficher : -----BEGIN PRIVATE KEY-----
head -1 public_key.pem    # doit afficher : -----BEGIN PUBLIC KEY-----

# Afficher pour copier (garde la fenêtre ouverte)
echo "=== PRIVATE KEY ===" && cat private_key.pem && echo "=== PUBLIC KEY ===" && cat public_key.pem
```

---

## Étape 2 — Configurer le serveur (une seule fois)

### 2.1 Se connecter en SSH

```bash
ssh root@<IP_DU_SERVEUR>
```

### 2.2 Créer l'utilisateur de déploiement

```bash
useradd -m -s /bin/bash deploy
usermod -aG docker deploy
mkdir -p /home/deploy/.ssh
cp ~/.ssh/authorized_keys /home/deploy/.ssh/
chown -R deploy:deploy /home/deploy/.ssh
chmod 700 /home/deploy/.ssh
chmod 600 /home/deploy/.ssh/authorized_keys
```

### 2.3 Créer le groupe keevo-jwt (GID 1001)

```bash
groupadd -g 1001 keevo-jwt
usermod -aG keevo-jwt deploy
```

### 2.4 Vérifier que Docker est installé

```bash
docker --version
docker compose version
# Si absent : https://docs.docker.com/engine/install/ubuntu/
```

### 2.5 Ouvrir les ports (AWS Security Group / pare-feu)

| Port | Protocole | Source |
|------|-----------|--------|
| 22   | TCP       | Ta IP  |
| 80   | TCP       | 0.0.0.0/0 |
| 443  | TCP       | 0.0.0.0/0 |

### 2.6 Configurer le DNS

Ajouter un enregistrement A chez ton registrar :

| Type | Nom | Valeur |
|------|-----|--------|
| A    | `@` (ou le sous-domaine) | `<IP_DU_SERVEUR>` |

```bash
# Vérifier la propagation (peut prendre 1-48h)
dig <TON_DOMAINE> +short
```

### 2.7 Déconnecter — on continue en tant que `deploy`

```bash
exit  # quitter root
```

---

## Étape 3 — Configurer les GitHub Secrets

Aller sur : `https://github.com/<TON_COMPTE>/<TON_REPO>/settings/secrets/actions`

### 3.1 Générer le GHCR_PAT

1. Aller sur : https://github.com/settings/tokens
2. **Generate new token (classic)**
3. Nom : `keevo-ghcr`
4. Scope : cocher `write:packages` + `read:packages`
5. Copier le token généré

### 3.2 Générer (ou utiliser) la clé SSH

```bash
# Si tu n'as pas de clé pour le serveur :
ssh-keygen -t ed25519 -f ~/.ssh/keevo-server -C "deploy@keevo"

# Copier la clé publique sur le serveur :
ssh-copy-id -i ~/.ssh/keevo-server.pub deploy@<IP_DU_SERVEUR>

# Afficher la clé privée (à copier dans le secret GitHub) :
cat ~/.ssh/keevo-server
```

### 3.3 Remplir TOUS les secrets

Va dans **Settings → Secrets and variables → Actions → Repository secrets** et ajoute :

| Nom du secret | Où trouver la valeur |
|---------------|---------------------|
| `GHCR_PAT` | Token généré à l'étape 3.1 |
| `DEPLOY_SSH_HOST` | IP du serveur (ex: `44.223.221.74`) |
| `DEPLOY_SSH_USER` | `deploy` |
| `DEPLOY_SSH_PRIVATE_KEY` | `cat ~/.ssh/keevo-server` (le contenu ENTIER, avec les `-----BEGIN...`) |
| `DEPLOY_SSH_PORT` | `22` |
| `APP_DOMAIN` | Ton domaine (ex: `keevo.lipagar.com`) |
| `JWT_PRIVATE_KEY_PEM` | `cat /tmp/private_key.pem` (étape 1) |
| `JWT_PUBLIC_KEY_PEM` | `cat /tmp/public_key.pem` (étape 1) |

---

## Étape 4 — Créer les fichiers sur le serveur

```bash
ssh deploy@<IP_DU_SERVEUR>
mkdir -p ~/keevo
cd ~/keevo
```

### 4.1 Créer `~/keevo/.env`

```bash
nano ~/keevo/.env
```

Coller ceci (remplacer les `<...>`) :

```ini
# ─── PostgreSQL ───────────────────────────────────────────────
POSTGRES_DB=keevo
POSTGRES_USER=keevo
POSTGRES_PASSWORD=<MDP_GENERE_AVEC_openssl_rand_-hex_20>

# ─── Spring ───────────────────────────────────────────────────
SPRING_PROFILES_ACTIVE=prod
APP_PORT=4500
LOG_LEVEL=INFO
FLYWAY_ENABLED=true

# ─── JWT ──────────────────────────────────────────────────────
# Les clés RSA sont injectées via GitHub Secrets → /run/secrets/jwt/
# JWT_SECRET n'est pas utilisé en prod (RSA), mais requis par le code
JWT_SECRET=unused_in_prod_with_rsa_keys
JWT_EXPIRATION_MS=15552000000

# ─── Admin bootstrap ──────────────────────────────────────────
ADMIN_PHONE=+2376XXXXXXXXX
ADMIN_PASSWORD=<MDP_ADMIN_FORT>

# ─── FCM Push ─────────────────────────────────────────────────
KEEVO_FCM_ENABLED=true
# GOOGLE_APPLICATION_CREDENTIALS est overload par docker-compose → /run/secrets/firebase

# ─── WhatsApp ─────────────────────────────────────────────────
KEEVO_WHATSAPP_PROVIDER=wassender
WASSENDER_API_URL=https://www.wasenderapi.com
WASSENDER_API_TOKEN=<TON_TOKEN_WASSENDER>

# ─── Let's Encrypt ────────────────────────────────────────────
ACME_EMAIL=<TON_EMAIL>
```

Sauver : `Ctrl+O` puis `Ctrl+X`

### 4.2 Copier les credentials Firebase

```bash
# Sur ta machine locale :
scp keevo/keevo-1d289-firebase-adminsdk-fbsvc-4094494885.json deploy@<IP_DU_SERVEUR>:~/keevo/firebase-credentials.json
```

### 4.3 Vérifier les permissions

```bash
# Sur le serveur
chmod 600 ~/keevo/.env
chmod 600 ~/keevo/firebase-credentials.json
ls -la ~/keevo/
# .env doit être -rw------- (600)
# firebase-credentials.json doit être -rw------- (600)
```

---

## Étape 5 — Pousser la branche `deploy`

```bash
# Sur ta machine locale, dans le dépôt Keevo
cd /home/toor/Project/FreeLance/AI/Keevo

# Vérifier que tout est commité
git status

# Merger tes changements dans la branche deploy
git checkout deploy
git merge v1-stabilization   # ou la branche que tu veux déployer
git push origin deploy
```

Le workflow `deploy-backend.yml` se déclenche automatiquement.

---

## Étape 6 — Surveiller le déploiement

Aller sur : `https://github.com/<TON_COMPTE>/<TON_REPO>/actions`

Cliquer sur le workflow en cours et vérifier les logs.

---

## Étape 7 — Vérification post-déploiement

```bash
ssh deploy@<IP_DU_SERVEUR>

# 1. Tous les containers sont UP
docker ps
# Attendu : keevo_backend, keevo_postgres, keevo_traefik

# 2. Backend healthy
curl -s http://localhost:4500/actuator/health
# Attendu : {"status":"UP"}

# 3. HTTPS fonctionne
curl -s https://<TON_DOMAINE>/actuator/health
# Attendu : {"status":"UP"}

# 4. Certificat Let's Encrypt OK (pas self-signed)
curl -vI https://<TON_DOMAINE> 2>&1 | grep "issuer"
# Attendu : issuer: ... Let's Encrypt

# 5. Logs backend — pas d'erreur au boot
docker logs --tail 50 keevo_backend 2>&1 | grep -E "ERROR|FATAL|Started"
# Attendu : Started KeevoApplication in X seconds (pas d'ERROR)
```

---

## Étape 8 — Résolution des problèmes courants

### Le backend ne démarre pas

```bash
docker logs keevo_backend 2>&1 | tail -50
```

Causes fréquentes :
- **`PRODUCT_NOT_FOUND`** ou **`Admin@1234!` rejeté** → changer `ADMIN_PASSWORD` dans `.env`
- **`Connection refused` sur postgres** → vérifier `docker ps` (postgres doit être healthy)
- **Clé JWT illisible** → le groupe `keevo-jwt` n'est pas configuré → refaire l'étape 2.3
- **Firebase credentials manquantes** → refaire l'étape 4.2

### Let's Encrypt échoue

```bash
docker logs keevo_traefik 2>&1 | grep -i "acme\|certificate\|error"
```

Causes fréquentes :
- DNS pas propagé → `dig <TON_DOMAINE> +short` doit retourner l'IP
- Port 80 pas ouvert → vérifier le firewall / security group
- `ACME_EMAIL` pas défini dans `.env`

### Traefik "client version 1.24 is too old"

```bash
docker compose -f ~/keevo/docker-compose.prod.yml up -d --force-recreate traefik
```
