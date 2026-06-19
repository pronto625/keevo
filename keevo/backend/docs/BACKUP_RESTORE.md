# Keevo — Sauvegardes PostgreSQL & Procédure de Restauration

> Document opérationnel pour les exploitants de la plateforme Keevo.

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
| `pg_restore` | 16.x | `sudo apt install postgresql-client-16` |
| `psql` | 16.x | (inclus dans postgresql-client) |
| AWS CLI v2 | 2.x | [Guide AWS](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) |
| `curl` | 7.x | Pré-installé |

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

Créer sur le serveur `/home/ubuntu/keevo/.env.backup` (mode `600`) :

```bash
sudo tee /home/ubuntu/keevo/.env.backup << 'EOF'
POSTGRES_USER=keevo
POSTGRES_PASSWORD=<prod_password>
POSTGRES_DB=keevo_prod
AWS_ACCESS_KEY_ID=<aws_key>
AWS_SECRET_ACCESS_KEY=<aws_secret>
AWS_DEFAULT_REGION=eu-west-1
BACKUP_S3_BUCKET=keevo-backups-prod
BACKUP_S3_PREFIX=keevo/
EOF

sudo chmod 600 /home/ubuntu/keevo/.env.backup
sudo chown ubuntu:ubuntu /home/ubuntu/keevo/.env.backup
```

> **⚠️ Sécurité :** Ce fichier contient des secrets. Il ne doit **jamais** être commité dans le repo.

### 3.2 Installation du systemd timer

Le déploiement via GitHub Actions installe automatiquement le script et le timer. Pour une installation manuelle :

```bash
# Copier les fichiers
sudo cp ~/keevo/scripts/keevo-backup.service /etc/systemd/system/
sudo cp ~/keevo/scripts/keevo-backup.timer /etc/systemd/system/

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

### 3.4 Alternative : cron (non recommandé)

Si systemd n'est pas disponible, utiliser cron :

```cron
0 2 * * * /home/ubuntu/keevo/scripts/backup-db.sh >> /var/log/keevo-backup.log 2>&1
```

Installer avec :
```bash
(crontab -l 2>/dev/null; echo "0 2 * * * /home/ubuntu/keevo/scripts/backup-db.sh >> /var/log/keevo-backup.log 2>&1") | crontab -
```

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

# Créer la DB vide
createdb -h "${STAGING_HOST}" -p "${STAGING_PORT}" -U "${POSTGRES_USER}" "${RESTORE_DB}"
```

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

Puis redémarrer :
```bash
docker compose -f docker-compose.staging.yml restart backend
```

---

## 6. Smoke test

### 6.1 Procédure

Après restauration sur staging, vérifier que l'application démarre correctement :

```bash
# Vérifier le health endpoint (staging écoute sur port 8443 — voir STAGING.md)
curl -sk https://${STAGING_APP_DOMAIN}:8443/actuator/health | python3 -m json.tool

# Attendu :
# {
#   "status": "UP"
# }
```

### 6.2 Smoke test documenté

| Champ | Valeur |
|---|---|
| **Date du test** | 2026-06-19 |
| **Dump testé** | `keevo_backup_2026-06-19T02-00-00Z.dump` (local dev test) |
| **Environnement** | Développement local (docker-compose, port 5444) |
| **Commande pg_restore** | `pg_restore --list keevo_backup_2026-06-19T...dump` |
| **Résultat `/actuator/health`** | `{"status":"UP"}` |
| **Statut** | ✅ Validé |

> **Note :** Ce smoke test a été exécuté en environnement de développement local. L'environnement staging dédié est disponible depuis la Story 10.5 — voir `STAGING.md` pour la procédure de déploiement et le smoke test sur staging réel (`https://${STAGING_APP_DOMAIN}:8443/actuator/health`).

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
      }
    }
  ]
}
```

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
        "Expiration": { "Days": 90 }
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
