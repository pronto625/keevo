# Keevo — MOP Mise à jour de la prod (1.1.12 → 1.1.70)

> Mode opératoire pour mettre à jour le serveur **déjà en prod** (`https://keevo.ad2s.cm`).
> Il couvre seulement ce qui change : DNS, 3 variables dans `~/keevo/.env`, les secrets de
> signature de l'app, puis le push. Aucune donnée existante n'est supprimée.
> Installation de zéro : `docs/MOP_DEPLOIEMENT.md`. Référence complète : `docs/ENV_REFERENCE.md`.
> Date : 2026-10-18

---

## 0. Situation de départ (vérifiée le 2026-10-18)

| Élément | Valeur |
|---|---|
| API publique | `https://keevo.ad2s.cm` → `150.136.82.10`, `/actuator/health` = `UP` |
| Version en prod | backend du commit `8e9b24fd` (21/09), base en migration **V7**, app **1.1.12** |
| Version à déployer | branche `v1s/stabilisation`, migrations **V8 → V26**, app **1.1.70** |
| Dépôt qui déploie | **`pronto625/keevo`** (remote `origin2`). Le workflow *Deploy Backend* se lance au push sur `deploy`. Sur `Dalois-30/keevo-app` (`origin`), Actions ne démarre pas. |
| Routage | Traefik **externe** déjà en place (réseau `traefik-public`, resolver `mytlschallenge`). Le workflow le détecte seul. |
| Dossier serveur | `~/keevo` de l'utilisateur SSH de déploiement |

### Ce que la mise à jour ajoute
- Le conteneur **`keevo_dashboard`** : le tableau de bord d'administration, sur son propre domaine.
- Le volume Docker **`keevo_product_photos`** pour les photos produit. Il est créé automatiquement.
- Les migrations V8 → V26, appliquées au démarrage.

### Ce qui ne bloque plus la mise à jour
- **Mot de passe Postgres** : s'il est court ou « par défaut », le backend démarre quand même et
  écrit un avertissement `[Security] POSTGRES_PASSWORD …` dans ses logs. Rien à changer pour la
  mise à jour.
- **Données anciennes non conformes** : une entreprise dont l'historique contient d'anciennes
  valeurs reçoit quand même ses nouvelles colonnes. Cela vaut pour un type de mouvement de stock
  ancien, deux produits au nom identique ou un comptage d'inventaire en double. Rien n'est modifié
  ni supprimé : la règle s'applique aux nouvelles lignes seulement, avec un `WARNING` dans les logs.
- **Contrôle avant arrêt** : la configuration est vérifiée **avant** d'arrêter quoi que ce soit.
  Si une valeur manque, le déploiement s'arrête et l'ancienne version continue de tourner.

### Ce que les migrations normalisent
Ce sont les seules écritures sur des données existantes. Elles sont idempotentes et rien n'est supprimé.

| Migration | Effet |
|---|---|
| V10 | Les numéros de téléphone écrits sans `+` (`2376…`) deviennent `+2376…`, le format utilisé à la connexion. Un numéro dont la forme `+` existe déjà est laissé tel quel. |
| V24 | Une entreprise dont le nom est encore son code technique (`KV-XXXX`) prend le nom de sa première boutique. Le code ne change pas. |
| V17 | Ajoute une ligne d'historique « inscription » par entreprise, pour le tableau de bord admin. |
| V9 / V23 et colonnes d'entreprise | Remplissent les nouvelles dates de suivi (`received_at`, `updated_at`) **seulement quand elles sont vides**, à partir des dates existantes. |
| V14 | Le journal d'audit devient non modifiable : ni modification ni suppression de ligne. |

---

## 1. DNS : domaine du tableau de bord

Domaine proposé : **`admin.keevo.ad2s.cm`**. Il doit être différent de `keevo.ad2s.cm`.

Chez le gestionnaire DNS de `ad2s.cm`, ajoutez :

| Type | Nom | Valeur |
|---|---|---|
| A | `admin.keevo` | `150.136.82.10` |

Vérifiez la propagation :

```bash
dig +short admin.keevo.ad2s.cm     # attendu : 150.136.82.10
```

À faire **avant** le push : le Traefik existant émet le certificat dès que le DNS répond.

---

## 2. Serveur : sauvegarde avant tout

Les migrations ne se défont pas. Un dump est le seul retour arrière possible.

```bash
ssh <user>@150.136.82.10
cd ~/keevo
set -a; . ./.env; set +a
docker exec keevo_postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > ~/keevo_avant_maj_$(date +%Y%m%d_%H%M).dump
ls -lh ~/keevo_avant_maj_*.dump                                                       # plus que quelques Ko
docker exec -i keevo_postgres pg_restore -l < "$(ls -t ~/keevo_avant_maj_*.dump | head -1)" | head -3   # lisible
```

Copiez le fichier hors du serveur, par exemple avec `scp` vers votre poste.

---

## 3. Serveur : `~/keevo/.env`, les 3 variables à ajouter

Ajoutez-les à la suite des variables existantes. **Ne modifiez rien d'autre.**

```ini
# ─── Tableau de bord d'administration ─────────────────────────
# Domaine du tableau de bord (sans https://, différent de keevo.ad2s.cm) — étape 1
ADMIN_DOMAIN=admin.keevo.ad2s.cm
# Compte super-admin, créé au premier démarrage s'il n'existe pas encore
ADMIN_PHONE=+2376XXXXXXXX
ADMIN_PASSWORD='<mot de passe fort>'
```

**Règles :**
- **`ADMIN_PHONE`** :
  - S'il y a déjà une ligne `ADMIN_PHONE` dans `.env`, gardez **ce numéro** : c'est le super-admin existant.
  - Ne mettez **jamais** le numéro d'un commerçant client : son compte deviendrait super-admin.
  - Si deux lignes `ADMIN_PHONE` existent, c'est la dernière qui compte.
- **`ADMIN_PASSWORD`** :
  - Au moins 12 caractères, et 3 types parmi : minuscule, majuscule, chiffre, symbole.
  - Jamais `Admin@1234!`.
  - Gardez-le **entre apostrophes** s'il contient un `$`.
  - Il sert seulement à **créer** le compte. Si le numéro existe déjà, son mot de passe actuel est conservé.
- Mettez les commentaires sur leur propre ligne. Dans `.env`, un `# …` après une valeur fait partie de la valeur.

Vérification, sans afficher aucune valeur :

```bash
cd ~/keevo
for v in ADMIN_DOMAIN ADMIN_PHONE ADMIN_PASSWORD; do grep -q "^$v=." .env && echo "$v ✓" || echo "$v ❌ manquant"; done
chmod 600 .env
```

Le workflow refait ce contrôle sur le serveur avant toute action. Il applique les mêmes règles que
le backend, et une valeur refusée arrête le déploiement sans rien couper.

### Facultatif : à ajouter quand vous voulez

Ces variables ne bloquent rien.

```ini
# Liens d'export signés : sans cette valeur, un lien d'export expire au redémarrage du serveur
KEEVO_EXPORT_LINK_SECRET=<résultat de : openssl rand -hex 32>

# Vidéos d'aide (YouTube…) — vide = « Bientôt disponible » dans l'app
KEEVO_HELP_TUTORIAL_VENDRE_URL=
KEEVO_HELP_TUTORIAL_CATALOGUE_URL=
KEEVO_HELP_TUTORIAL_INVENTAIRE_URL=
KEEVO_HELP_TUTORIAL_RAPPORTS_URL=
KEEVO_HELP_TUTORIAL_EQUIPE_URL=
KEEVO_HELP_TUTORIAL_HORS_LIGNE_URL=
```

### À ne pas mettre dans `.env`

Le déploiement impose déjà ces valeurs : `DASHBOARD_ORIGIN` (= `https://<ADMIN_DOMAIN>`),
`KEEVO_PRODUCT_PHOTO_DIR`, `POSTGRES_HOST`, `POSTGRES_PORT`, `SPRING_PROFILES_ACTIVE`,
`GOOGLE_APPLICATION_CREDENTIALS` et `KEEVO_JWT_*_KEY_PATH`.

Tout le reste du `.env` actuel (Postgres, WhatsApp, FCM…) **reste tel quel**.

---

## 4. Serveur : `~/keevo/.env.backup`

Rien d'obligatoire. Les sauvegardes nocturnes archivent maintenant aussi les photos produit
(`keevo_photos_<date>.tar.gz`). Pour désactiver cet archivage, ajoutez `BACKUP_PHOTOS=0`.

---

## 5. GitHub : signature de l'APK (dépôt `pronto625/keevo`)

Seul le **build de l'app** en a besoin ; le serveur n'est pas concerné. Ces 4 secrets n'existent
que sur `Dalois-30/keevo-app`. Sans eux, *Release Flutter* échoue sur `pronto625/keevo`, comme
depuis le 21/09 (`Failed to read key from store`).

```bash
base64 -w0 /chemin/vers/keevo-release.keystore | gh secret set ANDROID_KEYSTORE_BASE64 -R pronto625/keevo
gh secret set ANDROID_KEY_ALIAS      -R pronto625/keevo
gh secret set ANDROID_STORE_PASSWORD -R pronto625/keevo
gh secret set ANDROID_KEY_PASSWORD   -R pronto625/keevo
```

> ⚠️ Prenez **le même keystore** que celui qui a signé l'APK installé chez les utilisateurs.
> Sinon, Android refuse la mise à jour par-dessus l'ancienne app.

Les autres secrets ne changent pas. Ce sont `APP_DOMAIN` (`keevo.ad2s.cm`, qui sert aussi d'adresse
API à l'app), `DEPLOY_SSH_*`, `GHCR_PAT`, `ACME_EMAIL` et `JWT_*_PEM`. Ne touchez pas aux clés
JWT : les changer déconnecterait tout le monde.

---

## 6. Déclencher le déploiement

Depuis le poste de développement, une fois les étapes 1 à 5 faites :

```bash
cd ~/Project/FreeLance/AI/Keevo
git push origin2 v1s/stabilisation:deploy       # → Deploy Backend + Release Flutter (pronto625/keevo)
git push origin2 v1s/stabilisation:develop
git push origin  v1s/stabilisation:deploy       # garde origin à jour
git push origin  v1s/stabilisation:develop
gh run watch -R pronto625/keevo
```

Déroulé automatique :
1. Build des images.
2. Copie du contrôle sur le serveur.
3. Contrôle de `ADMIN_*`. En cas d'échec, le déploiement s'arrête ici et la prod continue de tourner.
4. Clés JWT et `docker-compose.prod.yml`.
5. `docker compose up -d`.
6. Attente que le backend et le tableau de bord soient `healthy`.

En parallèle, *Release Flutter* construit l'APK 1.1.70 avec `https://keevo.ad2s.cm` et le publie dans *Releases*.

Le premier démarrage applique les migrations à chaque entreprise. Comptez une à quelques minutes
selon le volume de données. Pendant ce temps, les apps 1.1.12 gardent leurs ventes en local et les
envoient au retour du serveur.

---

## 7. Vérifications après déploiement

Sur le serveur :

```bash
docker ps --format '{{.Names}}\t{{.Status}}' | grep keevo
# keevo_backend (healthy), keevo_dashboard (healthy), keevo_postgres (healthy)

docker logs keevo_backend 2>&1 | grep -E "FailFast|Security|CORS|AdminInit|TenantSchemaMigrationRunner|Started" | tail -20
# attendu : "Production secrets validation passed", "DASHBOARD_ORIGIN configured", "Started …"
# "[Security] POSTGRES_PASSWORD …" = avertissement seulement (mot de passe à renforcer un jour)
# "TenantSchemaMigrationRunner … FAILURES" ne doit PAS apparaître

docker logs keevo_backend 2>&1 | grep -E "WARNING|NOT VALID|skipped in" | head
# s'il y a des lignes : anciennes données tolérées (voir §0), rien n'a été modifié

set -a; . ~/keevo/.env; set +a
docker exec keevo_postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
  "select max(version::int) from flyway_schema_history where success"     # 26
```

Depuis n'importe où :

```bash
curl -s https://keevo.ad2s.cm/actuator/health               # {"status":"UP"}
curl -sI https://admin.keevo.ad2s.cm/login | head -1        # HTTP/2 200
```

Tests fonctionnels :
1. **Tableau de bord** : ouvrez `https://admin.keevo.ad2s.cm/login` avec `ADMIN_PHONE` et son mot de passe.
2. **App** : installez l'APK 1.1.70 depuis *Releases* et connectez-vous avec un compte commerçant
   existant. Faites une vente en ligne, puis une vente hors ligne, puis laissez la synchronisation
   se faire.
3. **Photo** : ajoutez une photo à un produit et vérifiez qu'elle apparaît sur un second appareil.

---

## 8. Retour arrière

- **Le déploiement s'arrête à l'étape 3** : rien n'a changé sur le serveur. Corrigez `.env`, puis
  relancez avec *Actions → Deploy Backend → Run workflow*.
- **Le backend démarre mal** : lancez `docker logs keevo_backend | grep -E "FailFast|ERROR" | head`.
  Corrigez la cause, puis `docker compose -f ~/keevo/docker-compose.prod.yml up -d backend`.
- **Revenir à la 1.1.12** : il faut **restaurer le dump** de l'étape 2. N'essayez jamais de
  redémarrer l'ancienne image sur la base migrée.

```bash
cd ~/keevo
docker compose -f docker-compose.prod.yml stop backend dashboard
set -a; . ./.env; set +a
docker exec -i keevo_postgres psql -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE \"$POSTGRES_DB\" WITH (FORCE);" -c "CREATE DATABASE \"$POSTGRES_DB\" OWNER \"$POSTGRES_USER\";"
docker exec -i keevo_postgres pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner < ~/keevo_avant_maj_<date>.dump
sed -i 's|backend:latest|backend:sha-8e9b24f|' docker-compose.prod.yml
docker compose -f docker-compose.prod.yml up -d backend
```

Les ventes faites entre-temps sont toujours dans les téléphones. Elles repartent à la
synchronisation suivante.

---

## Récapitulatif

| Où | Quoi | Obligatoire |
|---|---|---|
| DNS `ad2s.cm` | `A admin.keevo → 150.136.82.10` | ✅ |
| Serveur | dump de la base (étape 2) | ✅ |
| `~/keevo/.env` | `ADMIN_DOMAIN`, `ADMIN_PHONE`, `ADMIN_PASSWORD` | ✅ |
| `~/keevo/.env` | `KEEVO_EXPORT_LINK_SECRET`, `KEEVO_HELP_TUTORIAL_*_URL` | facultatif |
| Secrets `pronto625/keevo` | `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEY_ALIAS`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_PASSWORD` | ✅ pour publier l'app |
| Secrets `pronto625/keevo` | rien d'autre à changer | — |
| Postgres | rien : avertissement seulement s'il est faible | — |
