# Keevo — Sauvegardes PostgreSQL & Procédure de Restauration

> Document opérationnel pour les exploitants de la plateforme Keevo.
>
> Checklist de déploiement unique (secrets, installation des unités, alerte d'échec, protection S3,
> re-grant après restauration) : [`DEPLOYMENT_CHECKLIST.md`](DEPLOYMENT_CHECKLIST.md) §11–§13.

---

## Table des matières

1. [Architecture des sauvegardes](#1-architecture-des-sauvegardes)
2. [Prérequis](#2-prérequis)
3. [Configuration du backup automatique](#3-configuration-du-backup-automatique)
4. [Télécharger le dernier backup](#4-télécharger-le-dernier-backup)
5. [Restaurer sur staging](#5-restaurer-sur-staging)
6. [Smoke test](#6-smoke-test)
7. [Rollback production](#7-rollback-production)
8. [Politique de cycle de vie S3](#8-politique-de-cycle-de-vie-s3)
9. [Dépannage](#9-dépannage)
10. [Smoke test de restauration exécuté le 2026-10-11](#10-smoke-test-de-restauration-exécuté-le-2026-10-11)

---

## 1. Architecture des sauvegardes

```
┌─────────────────────────────────────────────────────────────┐
│ VPS Keevo (~/keevo/)                                        │
│                                                             │
│  ┌──────────────┐    systemd timer    ┌──────────────────┐  │
│  │ keevo_postgres│ ◄── 02:00 UTC ──── │ backup-db.sh     │  │
│  │ (Docker)     │    pg_dump -Fc     │                  │  │
│  └──────────────┘                     │ 1. pg_dump       │  │
│                                       │ 2. validate      │  │
│                                       │ 3. upload S3     │  │
│                                       │ 4. rotate 30j    │  │
│                                       └────────┬─────────┘  │
│                                                │            │
│  /var/backups/keevo/ ◄── local cache (30j)     │            │
└────────────────────────────────────────────────┼────────────┘
                                                 │
                                    ┌────────────▼─────────┐
                                    │ S3 (30j + GLACIER)   │
                                    │ keevo-backups-prod/  │
                                    │  └─ keevo/           │
                                    │     └─ keevo_backup_ │
                                    │        YYYY-MM-DD...  │
                                    └──────────────────────┘
```

**Rétention :**
- **Local** : 30 jours glissants (rotation par le script)
- **S3 STANDARD_IA** : 30 jours (Lifecycle Policy)
- **S3 GLACIER** : 30–90 jours (transition automatique)
- **Suppression définitive** : après 90 jours

**Contenu du dump :** Tous les schémas PostgreSQL (`public` + tous les `kv_XXXXXX` tenants).

---

## 2. Prérequis

### Sur le poste opérateur

| Outil | Version min. | Installation |
|---|---|---|
| `pg_restore` | **16.x exactement** (même majeure que le serveur) | `sudo apt install postgresql-client-16` |
| `psql` / `createdb` | 16.x | (inclus dans postgresql-client-16) |
| AWS CLI v2 | 2.x | [Guide AWS](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) |
| `curl` | 7.x | Pré-installé |

> **⚠️ Version du client :** un `pg_restore` **plus récent** que le serveur (17.x/18.x contre PostgreSQL 16) émet `SET transaction_timeout = 0;` à l'initialisation, que PostgreSQL 16 rejette (`unrecognized configuration parameter "transaction_timeout"`). La restauration se termine quand même (`errors ignored on restore: 1`) mais **le code de sortie est 1**, ce qui casse tout script qui vérifie `$?`. Vérifié le 2026-10-11 (§10). Deux options sûres : installer `postgresql-client-16`, ou restaurer **depuis le conteneur** avec le `pg_restore` embarqué (`docker exec`, voir §5.2 et §7) — c'est la méthode recommandée.

### Accès requis

- Accès SSH au serveur de production (`ubuntu@<DEPLOY_SSH_HOST>`)
- Accès au bucket S3 `keevo-backups-prod` (credentials AWS)
- Accès au serveur staging (pour les tests de restauration)

### Variables d'environnement

```bash
# PostgreSQL
export POSTGRES_USER=keevo
export POSTGRES_PASSWORD=<valeur>
export POSTGRES_DB=keevo_prod

# AWS
export AWS_ACCESS_KEY_ID=<valeur>
export AWS_SECRET_ACCESS_KEY=<valeur>
export AWS_DEFAULT_REGION=eu-west-1

# Backup
export BACKUP_S3_BUCKET=keevo-backups-prod
export BACKUP_S3_PREFIX=keevo/
```

---

## 3. Configuration du backup automatique

### 3.1 Fichier d'environnement `.env.backup`

Créer sur le serveur `~/keevo/.env.backup` (répertoire de l'utilisateur de déploiement, mode `600`) :

```bash
tee ~/keevo/.env.backup > /dev/null << 'EOF'
POSTGRES_USER=keevo
POSTGRES_PASSWORD=<prod_password>
POSTGRES_DB=keevo_prod
AWS_ACCESS_KEY_ID=<aws_key>
AWS_SECRET_ACCESS_KEY=<aws_secret>
AWS_DEFAULT_REGION=eu-west-1
BACKUP_S3_BUCKET=keevo-backups-prod
BACKUP_S3_PREFIX=keevo/
BACKUP_REQUIRE_S3=1
BACKUP_HEARTBEAT_URL=<url_de_ping_optionnelle>
EOF

chmod 600 ~/keevo/.env.backup
```

> **⚠️ Sécurité :** Ce fichier contient des secrets. Il ne doit **jamais** être commité dans le repo.

`BACKUP_REQUIRE_S3=1` (prod) : sans `BACKUP_S3_BUCKET`, la sauvegarde locale est faite puis le script
sort en erreur (alerte). `BACKUP_HEARTBEAT_URL` (optionnel — supprimer la ligne si non utilisé) : ping de succès vers un moniteur qui
alerte en l'absence de ping sous 26 h (`DEPLOYMENT_CHECKLIST.md` §11.5).

Webhook d'alerte en cas d'échec : `~/keevo/.env.backup-alert` (mode `600`, `BACKUP_ALERT_WEBHOOK_URL`…),
voir `DEPLOYMENT_CHECKLIST.md` §11.1. Les clés AWS doivent être celles de l'utilisateur IAM à
moindre privilège de `DEPLOYMENT_CHECKLIST.md` §12.1 (pas de suppression possible).

### 3.2 Installation du systemd timer

Le déploiement via GitHub Actions installe automatiquement les scripts, les unités et le timer. Les
unités `keevo-backup.service` et `keevo-backup-alert@.service` contiennent le marqueur
`__KEEVO_HOME__` (chemin templaté, Story 10.7) : ne **jamais** les copier telles quelles avec `cp`.
Installation manuelle :

```bash
# Rendre les unités (remplace __KEEVO_HOME__, sed échappé, contrôle du marqueur) puis les installer
UNITS_TMP="$(mktemp -d)"
~/keevo/scripts/render-backup-units.sh "${HOME}/keevo" ~/keevo/scripts "${UNITS_TMP}"
sudo install -m 644 "${UNITS_TMP}"/keevo-backup.service "${UNITS_TMP}"/keevo-backup-alert@.service \
     "${UNITS_TMP}"/keevo-backup.timer /etc/systemd/system/
rm -rf "${UNITS_TMP}"

# Recharger systemd
sudo systemctl daemon-reload

# Activer et démarrer le timer
sudo systemctl enable keevo-backup.timer
sudo systemctl start keevo-backup.timer
```

### 3.3 Vérification

```bash
# Vérifier que le timer est actif
systemctl status keevo-backup.timer
# Attendu : Active: active (waiting)

# Voir la prochaine exécution planifiée
systemctl list-timers keevo-backup.timer
# Attendu : NEXT column = prochain 02:00 UTC

# Exécuter un backup manuel immédiat
sudo systemctl start keevo-backup.service

# Consulter les logs
journalctl -u keevo-backup.service --since today
# Attendu : [BACKUP][INFO] Backup complete: keevo_backup_YYYY-MM-DDTHH-MM-SSZ.dump
```

Tout échec (`backup-db.sh` sort en code non nul) déclenche `OnFailure=` → `keevo-backup-alert@…`
(journald + webhook). Tester l'alerte : `DEPLOYMENT_CHECKLIST.md` §11.3.

### 3.4 Alternative : cron (non recommandé)

Si systemd n'est pas disponible, utiliser la crontab **root** (comme l'unité systemd, qui tourne en
root : écriture dans `/var/backups/keevo` et `/var/log`, accès Docker). Chemins absolus obligatoires
(`~` y désigne `/root`) ; remplacer `<deploy-user>` :

```cron
0 2 * * * set -a; . /home/<deploy-user>/keevo/.env.backup; set +a; /home/<deploy-user>/keevo/scripts/backup-db.sh >> /var/log/keevo-backup.log 2>&1
```

Installer avec :
```bash
DEPLOY_HOME="${HOME}"   # exécuté par l'utilisateur de déploiement
(sudo crontab -l 2>/dev/null; echo "0 2 * * * set -a; . ${DEPLOY_HOME}/keevo/.env.backup; set +a; ${DEPLOY_HOME}/keevo/scripts/backup-db.sh >> /var/log/keevo-backup.log 2>&1") | sudo crontab -
```

> - Sans systemd, pas d'alerte `OnFailure=` : surveiller `/var/log/keevo-backup.log` et configurer le
>   heartbeat `BACKUP_HEARTBEAT_URL` (seul signal d'échec dans ce mode).
> - Crontab utilisateur (non root) : passer `BACKUP_LOCAL_DIR=${HOME}/keevo/backups` et
>   `BACKUP_LOCK_FILE=${HOME}/keevo/backup.lock`, et rediriger vers `${HOME}/keevo/backup.log` ;
>   l'utilisateur doit être dans le groupe `docker`.
> - `. .env.backup` lit le fichier **comme du shell** : guillemets retirés, `$` développé, espaces
>   séparateurs, `#` en fin de ligne = commentaire. `EnvironmentFile=` (systemd) applique ses propres
>   règles (pas de développement de `$`, un `# …` en fin de ligne fait partie de la valeur). Garder
>   des valeurs simples — sans espace, `$`, guillemet ni commentaire en fin de ligne — pour qu'elles
>   soient lues pareil dans les deux modes.

---

## 4. Télécharger le dernier backup

### Depuis S3

```bash
# Lister les backups disponibles (triés par date)
aws s3 ls "s3://${BACKUP_S3_BUCKET}/${BACKUP_S3_PREFIX}" --recursive | sort

# Identifier le dernier backup
LATEST=$(aws s3 ls "s3://${BACKUP_S3_BUCKET}/${BACKUP_S3_PREFIX}" --recursive \
  | sort | tail -1 | awk '{print $4}')
echo "Dernier backup : ${LATEST}"

# Télécharger
aws s3 cp "s3://${BACKUP_S3_BUCKET}/${LATEST}" "./${LATEST##*/}"
```

### Depuis le cache local (sur le serveur)

```bash
# Lister les backups locaux
ls -lhrt /var/backups/keevo/keevo_backup_*.dump

# Copier le plus récent
LATEST_LOCAL=$(ls -t /var/backups/keevo/keevo_backup_*.dump | head -1)
scp ubuntu@<DEPLOY_SSH_HOST>:"${LATEST_LOCAL}" ./
```

### Vérifier l'intégrité du dump

```bash
pg_restore --list keevo_backup_YYYY-MM-DDTHH-MM-SSZ.dump | head -20
# Attendu : liste des schémas et tables (public + kv_*)
```

---

## 5. Restaurer sur staging

### 5.1 Créer la base de données cible

```bash
STAGING_HOST=<staging_host>
STAGING_PORT=5432
RESTORE_DB=keevo_restore

# Créer la DB vide (PGPASSWORD obligatoire : sans lui createdb bloque sur un prompt "Password:")
PGPASSWORD="${POSTGRES_PASSWORD}" createdb \
  -h "${STAGING_HOST}" -p "${STAGING_PORT}" -U "${POSTGRES_USER}" "${RESTORE_DB}"
```

> **Variante conteneur (sans client PostgreSQL sur l'hôte) :**
> ```bash
> docker exec <conteneur_postgres> psql -U "${POSTGRES_USER}" -d postgres \
>   -c "CREATE DATABASE ${RESTORE_DB} OWNER ${POSTGRES_USER};"
> ```

### 5.2 Restaurer le dump

```bash
PGPASSWORD="${POSTGRES_PASSWORD}" pg_restore \
  -h "${STAGING_HOST}" \
  -p "${STAGING_PORT}" \
  -U "${POSTGRES_USER}" \
  -d "${RESTORE_DB}" \
  --no-owner --no-acl \
  --verbose \
  keevo_backup_YYYY-MM-DDTHH-MM-SSZ.dump 2>&1 | tee restore.log
```

> **Note :** Des erreurs `role "xxx" does not exist` sont normales avec `--no-owner` et peuvent être ignorées.

> **Note (vérifié 2026-10-11) :** si `pg_restore` est plus récent que le serveur, la commande ci-dessus renvoie `exit 1` à cause de `SET transaction_timeout` (voir §2) bien que toutes les tables soient restaurées. Pour une restauration propre (`exit 0`), utiliser le `pg_restore` du conteneur :
> ```bash
> docker cp keevo_backup_YYYY-MM-DDTHH-MM-SSZ.dump <conteneur_postgres>:/tmp/restore.dump
> docker exec <conteneur_postgres> pg_restore \
>   -U "${POSTGRES_USER}" -d "${RESTORE_DB}" --no-owner --no-acl --verbose /tmp/restore.dump 2>&1 | tee restore.log
> docker exec <conteneur_postgres> rm -f /tmp/restore.dump
> ```
> Vérifier ensuite : `grep -c '^pg_restore: \(error\|warning\)' restore.log` doit renvoyer `0` (ne pas grepper `error` seul : les tables `sync_error_log` matchent).

> **Droits après restauration :** le dump est pris en `--no-owner --no-acl`, la restauration ne
> recrée ni propriétaires ni `GRANT`. Restaurer avec le rôle applicatif (`POSTGRES_USER` du backend),
> sinon appliquer l'étape de re-grant de `DEPLOYMENT_CHECKLIST.md` §13.

### 5.3 Vérifier les schémas restaurés

```bash
PGPASSWORD="${POSTGRES_PASSWORD}" psql \
  -h "${STAGING_HOST}" -U "${POSTGRES_USER}" -d "${RESTORE_DB}" \
  -c "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'kv_%' ORDER BY schema_name;"

# Attendu : liste des schémas kv_XXXXXX correspondant aux tenants
```

### 5.4 Connecter le backend staging

Mettre à jour la configuration du backend staging pour pointer vers `keevo_restore` :

```bash
# Dans le .env du backend staging
POSTGRES_DB=keevo_restore
```

Puis recréer le backend (un `restart` ne relit pas le `.env`), via le wrapper écrit par le déploiement :
```bash
cd ~/keevo-staging && ./compose.sh up -d backend
```

---

## 6. Smoke test

### 6.1 Procédure

Après restauration sur staging, vérifier que l'application démarre correctement :

```bash
# Vérifier le health endpoint (staging servi par le Traefik prod — voir STAGING.md)
curl -s https://staging.api.keevo.cm/actuator/health | python3 -m json.tool

# Attendu :
# {
#   "status": "UP"
# }
```

### 6.2 Smoke test documenté

| Champ | Valeur |
|---|---|
| **Date du test** | 2026-10-11 |
| **Dump testé** | `keevo_backup_2026-10-11T02-42-33Z.dump` (produit par `backup-db.sh`, mode `docker-exec`) |
| **Environnement** | Local : deux conteneurs `postgres:16-alpine` distincts (source → dump → cible vierge), backend jar `keevo-backend-1.0.0-SNAPSHOT` |
| **Commande pg_restore** | `docker exec keevo_smoke_restore pg_restore -U keevo -d keevo_restore --no-owner --no-acl --verbose /tmp/restore.dump` → `exit 0` |
| **Résultat `/actuator/health`** | `HTTP 200 {"status":"UP"}` après 12 s, backend démarré sur la DB restaurée |
| **Statut** | ✅ Validé — détail complet, durées et écarts de procédure en §10 |

> **Note :** Le 2026-06-19 un premier test "DB-only" avait restauré le dump sans démarrer le backend. Le test du 2026-10-11 couvre le chemin complet restauration → backend booté → `/actuator/health UP` → login d'un utilisateur créé avant la sauvegarde. L'environnement staging dédié est disponible depuis la Story 10.5 — voir `STAGING.md` pour le smoke test sur staging réel (`https://staging.api.keevo.cm/actuator/health`).

---

## 7. Rollback production

### 7.1 Quand restaurer la production ?

- Corruption de données détectée
- Migration Flyway échouée avec données corrompues
- Incident de sécurité nécessitant un retour en arrière

### 7.2 Procédure

```bash
# ÉTAPE 1 : Arrêter le backend (empêcher toute écriture)
ssh ubuntu@<DEPLOY_SSH_HOST>
docker stop keevo_backend

# ÉTAPE 2 : Identifier le backup à restaurer
ls -lhrt /var/backups/keevo/keevo_backup_*.dump
# OU télécharger depuis S3 (voir §4)

# ÉTAPE 3 : Renommer l'ancienne DB (sécurité)
docker exec -it keevo_postgres psql -U keevo -c \
  "ALTER DATABASE keevo_prod RENAME TO keevo_prod_before_restore_$(date +%Y%m%d);"

# ÉTAPE 4 : Créer une nouvelle DB vide
docker exec -it keevo_postgres psql -U keevo -c \
  "CREATE DATABASE keevo_prod OWNER keevo;"

# ÉTAPE 5 : Restaurer le dump dans le conteneur
# Copier le dump dans le conteneur
docker cp keevo_backup_YYYY-MM-DDTHH-MM-SSZ.dump keevo_postgres:/tmp/restore.dump

# Restaurer
docker exec -it keevo_postgres pg_restore \
  -U keevo -d keevo_prod --no-owner --no-acl /tmp/restore.dump

# ÉTAPE 6 : Vérifier
docker exec -it keevo_postgres psql -U keevo -d keevo_prod -c \
  "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'kv_%';"
# + droits : restauré avec le rôle applicatif (keevo) → objets possédés par lui, rien à faire ;
#   restauré avec un autre rôle → étape de re-grant, DEPLOYMENT_CHECKLIST.md §13

# ÉTAPE 7 : Redémarrer le backend
docker start keevo_backend

# ÉTAPE 8 : Vérifier le health
curl -s https://<APP_DOMAIN>/actuator/health
# Attendu : {"status":"UP"}

# ÉTAPE 9 : Vérifier les logs
docker logs --tail 50 keevo_backend
```

### 7.3 En cas d'échec du rollback

1. Ne PAS supprimer l'ancienne DB (`keevo_prod_before_restore_*`)
2. Restaurer un backup plus ancien
3. Contacter le support infrastructure

---

## 8. Politique de cycle de vie S3

### 8.1 Configuration recommandée

La rotation S3 est gérée par une **Lifecycle Policy** (pas par le script — évite des appels API coûteux pour lister/supprimer).

Appliquer via AWS Console ou CLI :

```json
{
  "Rules": [
    {
      "ID": "KeevoBackupLifecycle",
      "Status": "Enabled",
      "Filter": {
        "Prefix": "keevo/"
      },
      "Transitions": [
        {
          "Days": 30,
          "StorageClass": "GLACIER"
        }
      ],
      "Expiration": {
        "Days": 90
      },
      "NoncurrentVersionExpiration": {
        "NoncurrentDays": 7
      },
      "AbortIncompleteMultipartUpload": {
        "DaysAfterInitiation": 7
      }
    }
  ]
}
```

> **Versioning + Object Lock (Story 10.7) :** le bucket est versionné avec un verrou GOVERNANCE de
> 30 jours (`DEPLOYMENT_CHECKLIST.md` §12.2). L'expiration à 90 jours pose alors un *delete marker* ;
> `NoncurrentVersionExpiration` supprime la version masquée 7 jours plus tard (verrou de 30 jours
> expiré depuis longtemps), et `AbortIncompleteMultipartUpload` nettoie les envois interrompus.

### 8.2 Appliquer via AWS CLI

```bash
aws s3api put-bucket-lifecycle-configuration \
  --bucket keevo-backups-prod \
  --lifecycle-configuration '{
    "Rules": [
      {
        "ID": "KeevoBackupLifecycle",
        "Status": "Enabled",
        "Filter": { "Prefix": "keevo/" },
        "Transitions": [
          { "Days": 30, "StorageClass": "GLACIER" }
        ],
        "Expiration": { "Days": 90 },
        "NoncurrentVersionExpiration": { "NoncurrentDays": 7 },
        "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 7 }
      }
    ]
  }'
```

### 8.3 Vérifier la policy

```bash
aws s3api get-bucket-lifecycle-configuration \
  --bucket keevo-backups-prod
```

### 8.4 Restauration depuis GLACIER

Les objets en GLACIER nécessitent une restauration préalable (1–12 heures) :

```bash
aws s3api restore-object \
  --bucket keevo-backups-prod \
  --key "keevo/keevo_backup_YYYY-MM-DDTHH-MM-SSZ.dump" \
  --restore-request '{"Days":1,"GlacierJobParameters":{"Tier":"Standard"}}'
```

---

## 9. Dépannage

### Le backup échoue avec `[BACKUP][ERROR] pg_dump failed`

```bash
# Vérifier que le conteneur PostgreSQL tourne
docker ps | grep keevo_postgres

# Vérifier les credentials dans .env.backup
cat ~/keevo/.env.backup | grep POSTGRES

# Tester pg_dump manuellement
docker exec keevo_postgres pg_dump -U keevo --list keevo_prod
```

### Le timer systemd ne s'exécute pas

```bash
# Vérifier le statut
systemctl status keevo-backup.timer

# Vérifier les logs
journalctl -u keevo-backup.timer --since yesterday

# Vérifier que le timer est listé
systemctl list-timers --all | grep keevo
```

### L'upload S3 échoue

```bash
# Tester l'accès S3
aws s3 ls "s3://${BACKUP_S3_BUCKET}/"

# Vérifier les credentials AWS
aws sts get-caller-identity

# Vérifier la région
echo $AWS_DEFAULT_REGION
```

### Le dump est vide (0 octets)

```bash
# Vérifier que la base contient des données
docker exec keevo_postgres psql -U keevo -d keevo_prod -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public';"

# Vérifier l'espace disque
df -h /var/backups/
```

---

## 10. Smoke test de restauration exécuté le 2026-10-11

Exécution complète du chemin **backup → restauration dans une DB vierge → backend démarré sur la DB restaurée → `GET /actuator/health` UP** (AC4 Story 10.4). Environnement : poste local, Docker, `postgres:16-alpine` (PostgreSQL 16.15), jar `keevo-backend-1.0.0-SNAPSHOT` (`mvn -DskipTests package`), profil `dev`, FCM désactivé et WhatsApp `noop` via variables d'environnement. Les conteneurs utilisaient `--tmpfs /var/lib/postgresql/data` (aucun volume créé) et ont été supprimés à la fin.

### 10.1 Produire une sauvegarde réaliste (Phase A)

```bash
# 1. PostgreSQL source (même image que la prod)
docker run -d --name keevo_smoke_src --tmpfs /var/lib/postgresql/data \
  -e POSTGRES_DB=keevo_dev -e POSTGRES_USER=keevo -e POSTGRES_PASSWORD=keevo_local_pwd \
  -p 127.0.0.1:5444:5432 postgres:16-alpine          # pg_isready OK après 2 s

# 2. Backend sur la DB vide → Flyway crée le schéma public
SPRING_PROFILES_ACTIVE=dev POSTGRES_HOST=localhost POSTGRES_PORT=5444 POSTGRES_DB=keevo_dev \
POSTGRES_USER=keevo POSTGRES_PASSWORD=keevo_local_pwd APP_PORT=4500 \
KEEVO_FCM_ENABLED=false KEEVO_WHATSAPP_PROVIDER=noop \
java -jar target/keevo-backend-1.0.0-SNAPSHOT.jar     # /actuator/health UP après 10 s

# 3. Un tenant réel → schéma kv_3zt60w provisionné
curl -s -X POST http://localhost:4500/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"phoneNumber":"+237690000011","password":"Smoke2026!"}'
# → {"tenantCode":"KV-3ZT60W", "tenantId":"4f5d…", ...}
# État DB source : schémas public + kv_3zt60w, 31 tables public, 8 migrations Flyway (V1→V9)

# 4. Backup avec LE script de prod (mode docker-exec par défaut), backend toujours démarré
POSTGRES_USER=keevo POSTGRES_DB=keevo_dev DOCKER_CONTAINER=keevo_smoke_src \
BACKUP_LOCAL_DIR=./backups BACKUP_LOCK_FILE=./backup.lock bash keevo/backend/scripts/backup-db.sh
# [BACKUP][INFO] Starting backup at 2026-10-11T02-42-33Z (mode: docker-exec)...
# [BACKUP][INFO] Dump validated: 108K
# [BACKUP][WARN] BACKUP_S3_BUCKET not set — local backup only (not production-safe)
# [BACKUP][INFO] Rotation done: 0 file(s) older than 30 days removed
# [BACKUP][INFO] Backup complete: keevo_backup_2026-10-11T02-42-33Z.dump   (exit 0, < 1 s)

pg_restore --list backups/keevo_backup_2026-10-11T02-42-33Z.dump | head
# ;     TOC Entries: 258 / Format: CUSTOM / Dumped from database version: 16.15 / Dumped by pg_dump version: 16.15
# 1 SCHEMA kv_3zt60w, 118 entrées TABLE

# 5. Arrêt backend + suppression du conteneur source (la cible ne partage rien avec lui)
docker rm -f keevo_smoke_src
```

### 10.2 Restaurer dans un conteneur vierge (Phase B, §5 suivi à la lettre)

```bash
docker run -d --name keevo_smoke_restore --tmpfs /var/lib/postgresql/data \
  -e POSTGRES_DB=keevo_staging -e POSTGRES_USER=keevo -e POSTGRES_PASSWORD=keevo_local_pwd \
  -p 127.0.0.1:5445:5432 postgres:16-alpine
export STAGING_HOST=localhost STAGING_PORT=5445 RESTORE_DB=keevo_restore POSTGRES_USER=keevo POSTGRES_PASSWORD=keevo_local_pwd

# §5.1 tel qu'écrit avant correction (sans PGPASSWORD) → BLOQUE sur "Password:" (tué au bout de 2 min, exit 143)
createdb -h "${STAGING_HOST}" -p "${STAGING_PORT}" -U "${POSTGRES_USER}" "${RESTORE_DB}"
# §5.1 corrigé
PGPASSWORD="${POSTGRES_PASSWORD}" createdb -h "${STAGING_HOST}" -p "${STAGING_PORT}" -U "${POSTGRES_USER}" "${RESTORE_DB}"   # exit 0

# §5.2 avec le pg_restore de l'hôte (18.6 — plus récent que le serveur 16.15)
PGPASSWORD="${POSTGRES_PASSWORD}" pg_restore -h "${STAGING_HOST}" -p "${STAGING_PORT}" -U "${POSTGRES_USER}" \
  -d "${RESTORE_DB}" --no-owner --no-acl --verbose backups/keevo_backup_2026-10-11T02-42-33Z.dump 2>&1 | tee restore.log
# pg_restore: while INITIALIZING:
# pg_restore: error: could not execute query: ERROR:  unrecognized configuration parameter "transaction_timeout"
# Command was: SET transaction_timeout = 0;
# ... 258 entrées restaurées ...
# pg_restore: warning: errors ignored on restore: 1          → exit 1, < 1 s, contenu pourtant complet

# §5.2 refait proprement avec le pg_restore du conteneur (16.15) — méthode recommandée désormais
docker exec keevo_smoke_restore psql -U keevo -d keevo_staging \
  -c "DROP DATABASE keevo_restore;" -c "CREATE DATABASE keevo_restore OWNER keevo;"
docker cp backups/keevo_backup_2026-10-11T02-42-33Z.dump keevo_smoke_restore:/tmp/restore.dump
docker exec keevo_smoke_restore pg_restore -U keevo -d keevo_restore --no-owner --no-acl --verbose /tmp/restore.dump
# exit 0, < 1 s, 0 ligne "pg_restore: error|warning"

# §5.3 vérification
docker exec keevo_smoke_restore psql -U keevo -d keevo_restore -Atc \
  "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'kv_%';"
# kv_3zt60w
# public tables=31 | tenant tables=28 | flyway rows=8 | users=2 | tenants=2   (identique à la source)
```

### 10.3 Démarrer le backend sur la DB restaurée et vérifier (§6)

```bash
SPRING_PROFILES_ACTIVE=dev POSTGRES_HOST=localhost POSTGRES_PORT=5445 POSTGRES_DB=keevo_restore \
POSTGRES_USER=keevo POSTGRES_PASSWORD=keevo_local_pwd APP_PORT=4500 \
KEEVO_FCM_ENABLED=false KEEVO_WHATSAPP_PROVIDER=noop \
java -jar target/keevo-backend-1.0.0-SNAPSHOT.jar

curl -s -w ' HTTP %{http_code}\n' http://localhost:4500/actuator/health
# {"status":"UP"} HTTP 200        ← après 12 s
# Log Flyway : Current version of schema "public": 9 — Schema "public" is up to date. No migration necessary
#              Successfully validated 8 migrations   (flyway_schema_history restauré, rien ré-appliqué)

# Endpoints publics sur les données restaurées
curl -s -X POST http://localhost:4500/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"phoneNumber":"+237690000011","password":"Smoke2026!"}'      # HTTP 200, memberships [KV-3ZT60W / OWNER]
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:4500/v3/api-docs   # 200
# Endpoint authentifié (select-tenant → Bearer, tenantId=kv_3zt60w)
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer <accessToken>" http://localhost:4500/api/v1/auth/profile   # 200

# Nettoyage
kill <pid backend>; docker rm -f -v keevo_smoke_restore
```

### 10.4 Résultat

| Étape | Durée | Résultat |
|---|---|---|
| Démarrage postgres (×2) | 2 s chacun | OK |
| Boot backend sur DB vide (Flyway V1→V9) | 10 s | UP |
| `backup-db.sh` (docker-exec) + `pg_restore --list` | < 1 s | 104 Ko, 258 entrées TOC, exit 0 |
| `createdb` §5.1 sans `PGPASSWORD` | — | **Bloque** (corrigé dans ce doc) |
| `pg_restore` hôte 18.6 → serveur 16.15 | < 1 s | contenu complet mais **exit 1** (`transaction_timeout`) |
| `pg_restore` 16.15 dans le conteneur | < 1 s | **exit 0**, 0 erreur |
| Boot backend sur `keevo_restore` → `/actuator/health` | 12 s | **`HTTP 200 {"status":"UP"}`** |
| Login utilisateur créé avant le backup / `/v3/api-docs` / `/auth/profile` | — | 200 / 200 / 200 |

**Verdict : ✅ AC4 validée.** Écarts trouvés dans la procédure et corrigés ci-dessus : (1) `PGPASSWORD` manquant sur `createdb` en §5.1 ; (2) la version du client `pg_restore` doit être **égale** à la majeure du serveur (un client plus récent renvoie `exit 1`), d'où la recommandation `docker exec` ajoutée en §2 et §5.2 ; (3) le filtre `grep error` sur `restore.log` est trompeur à cause des tables `sync_error_log`.
