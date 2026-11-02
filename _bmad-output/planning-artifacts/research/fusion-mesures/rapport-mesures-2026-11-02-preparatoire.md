# Rapport de mesures Bref × Keevo — 2026-11-02 (préparatoire, mis à jour le 2026-11-03)

Story 18.1 — Kit `_bmad-output/planning-artifacts/research/fusion-mesures/` (lecture seule, agrégats uniquement).

**Ce rapport ne contient aucun chiffre de production.** Il consigne (1) les faits Q13/Q9 lus dans les dépôts,
(2) la validation locale du kit sur des **données synthétiques et de développement**, (3) la checklist restée ouverte
pour l'opérateur et (4) la commande exacte qui produira le rapport de production. Les chiffres de production (Q1–Q8,
Q4) sont produits par l'utilisateur sur un réplica ou un dump restauré ; ils sortent du périmètre de cette story.

## 1. Q13 — Mise en production de `develop` sur api-bref (fait de dépôt)

Lu dans le clone local `AI/api-bref` (sans `git fetch`) ; le runner recalcule ces faits à chaque run :

| Fait | Valeur |
|---|---|
| Point de divergence `merge-base main develop` | **daeb28c — 2026-10-26** (dernier commit de `develop` présent sur `main`) |
| Dernier commit de fusion sur `main` | **a3faeb2 — 2026-10-26** (PR #16, fusion de `develop` jusqu'à daeb28c) |
| Tête de `main` | a3faeb2 (2026-10-26) |
| Tête de `develop` | 376ad70 (2026-10-31) |
| Commits de `develop` absents de `main` | **7** (statuts calculés serveur b957867, quantités décimales 2fdf20b, NUI/RCCM clients b06cc2c, `emailVerified` d25e2e8, fiche client 1d89629 / 376ad70, validation SMS 1ce9fcf) |
| Workflow GHCR (`.github/workflows/docker-publish.yml`) | construit **uniquement `main`**, tags `sha-<sha>` + `latest` |
| Déploiement | `/opt/api-bref`, `docker-compose.prod.yml` → `ghcr.io/dsrdesign/api-bref:latest` |

**Lecture pour Q7 / R3 :** `main` a reçu `develop` pour la dernière fois le **2026-10-26 (a3faeb2)** ; les **7 commits
`develop` postérieurs ne sont pas déployés** — dont les règles de statut calculées côté serveur (b957867). La date de
bascule par défaut du kit (`DEVELOP_DEPLOY_DATE=2026-10-26`) en découle : Q7 classe les documents en « avant develop »,
« jour de la bascule » (l'heure du déploiement n'est pas connue) et « depuis develop ».

**Conséquence pour le kit :** la prod tourne le schéma de `main`. La table `refresh_token` (migration 1753650000000) et la
colonne `"user"."emailVerified"` (migration 1753780000000) n'existent que sur `develop` : le kit les détecte
(`to_regclass`, `information_schema.columns`) et imprime « non mesuré (objet absent : schéma main) » à leur place, sans
faire échouer la run (vérifié en §4.2, passe 2).

> Ce qui tourne réellement sur le VPS n'est **pas** confirmé ici : voir la checklist (§3). Si le `sha-*` en service ne
> correspond pas à a3faeb2 ou si sa date diffère, relancer le kit avec `DEVELOP_DEPLOY_DATE=<date réelle>`.

## 2. Q9 — Identité de l'app Keevo (fait de dépôt)

| Fait | Valeur |
|---|---|
| `namespace` / `applicationId` (`keevo/app/android/app/build.gradle.kts`) | **`com.example.keevo`** |
| Signature release (`.github/workflows/release-flutter.yml`) | keystore `keevo-release.keystore` décodé depuis les secrets GitHub (`ANDROID_KEYSTORE_BASE64`, alias et mots de passe en secrets), un seul flavor |
| Bref, pour comparaison (`AI/bref/app.json`, lu par le runner) | package / bundle `com.brefpoint.app`, équipe Apple `N9534K29PS`, projet EAS `bb0b6dbe-c342-43e4-95cc-2902d7bd4d45` |

**Lecture pour R11 / 18.4 :** le dépôt déclare `com.example.keevo`. Ce que la fiche Play Store publie réellement, l'état
de Play App Signing et la disponibilité du keystore d'upload restent à vérifier dans la console (checklist §3).

## 3. À confirmer par l'opérateur (hors base, non mesuré)

- [ ] **Q13 — GHCR / VPS** : tag `ghcr.io/dsrdesign/api-bref:sha-*` effectivement en service (`docker inspect` du
      conteneur dans `/opt/api-bref`) et date du déploiement ; cohérence avec a3faeb2 / 2026-10-26.
- [ ] **Q9 — Play Console** : nom de package réellement publié pour Keevo ; état de Play App Signing (clé d'upload vs clé
      de signature Google) ; keystore d'upload disponible pour EAS.
- [ ] **Q9 — Comptes développeur** : compte Google Play développeur (propriétaire, accès) ; compte Apple Developer
      (équipe `N9534K29PS` côté Bref) ; décision iOS pour Keevo (18.4).
- [ ] **Q1–Q8 (production)** : lancer le kit sur réplica / dump (§5) ; choisir en 18.2 le proxy « actif < 90 j » retenu.
- [ ] **Hygiène** : supprimer `out/<date>/*.hashes.csv` après lecture ; ne jamais commiter `out/`.

## 4. Validation locale du kit (2026-11-03) — données synthétiques et de développement, pas de production

Rejouable avec `validation/validate-local.sh` (voir README). Dernière exécution : **34 contrôles OK, 0 échec**.

### 4.1 Montage

- **Bref** : schéma reconstruit depuis les entités TypeORM de **`develop`** (376ad70) avec `typeorm schema:sync` sur un
  Postgres 16 jetable (conteneur `bref-mesures-pg-validation`, port 5499, supprimé en fin de validation), puis
  **fixture synthétique commitée** `validation/fixture-bref-synthetique.sql` — tous les numéros, noms et emails sont
  inventés (le `+237 6 99 01 77 17` est l'exemple de la matrice de la spec) : 6 owners (payant MyCoolPay en vigueur ;
  payant Apple en grâce ; `plan='pro'` expiré depuis 10 jours ; sans téléphone via Google ; boutique hors CM ; téléphone
  vide après normalisation), 1 superadmin, 2 membres (username sans téléphone ; numéro sans indicatif pays),
  6 documents (total faux avant `develop`, TVA ignorée, remise totale, jour de la bascule, décimaux hors CM),
  crédit de compte, compteurs d'usage, packs SMS, refresh token, push token.
- **Keevo** : base locale `keevo_dev` (conteneur `keevo_postgres`, port 5444), **données de développement** —
  les chiffres ci-dessous ne décrivent pas la production.

### 4.2 Résultats attendus / obtenus

| Scénario | Attendu | Obtenu |
|---|---|---|
| Q1 — payants en vigueur | MyCoolPay + Apple en grâce ; l'expiré **non** compté | 2 (pro/mycoolpay 1, team/apple 1) ; aucune ligne `momo` |
| Q1 — actifs < 90 j (proxies) | 3 proxies étiquetés | PROXY 1 = 1, PROXY 2 = 2, PROXY 3 = 1 (via le membre), union = 2 |
| Q3 — identités sans téléphone | 1 owner (google, email) + 1 membre (username seul) ; superadmin à part | 1 + 1 ; superadmin = 1 « exclu des membres et de Q4 » |
| Q3.e — qualité des téléphones | 1 owner vide après normalisation ; 1 membre sans préfixe pays connu | conforme |
| Q5 — hors CM, TVA, décimales | 1 boutique SN ; CM : 1 « TVA ignorée » (net > 0, tax 0) et le document entièrement remisé conforme ; décimales signalées | conforme (`invoice.subtotal` 1, `invoice.total` 1, `invoice_item.unitPrice` 1, `invoice_item.total` 1) |
| Q7 — `total` ≠ recalcul | 1 incohérent « avant develop » ; le document du 2026-10-26 dans « jour de la bascule » | avant develop : 2 ventes dont 1 incohérente (écart 101–1 000 XAF) ; jour de la bascule : 1 document, 0 incohérent ; depuis develop : 0 |
| Q8 — `plan ≠ free` sans abonnement en vigueur | 1 owner (expiré < 30 j, momo) | 1 (pro, `expired`, momo, « expiré < 30 j ») |
| Q6 — crédits et SMS | crédit 2 500 ; pack 100 SMS complété ; consommés 3 (proxy) ; 5 SMS gratuits | identique ; « restant » non calculable, nommé comme tel |
| Q4 — empreintes et intersection | owners exportés 4 (le vide exclu), membres 1 ; matrice 2×2 par rôle ; 6 fichiers d'empreintes ; sel absent du rapport | conforme ; avec `keevo_dev` : 1 collision owner Bref × EMPLOYEE Keevo (le numéro-exemple de la matrice existe dans les données de dev), 0 ailleurs |
| Schéma `main` (passe 2 : `refresh_token` et `emailVerified` supprimés du jetable) | run complète, objets `develop` « non mesuré », PROXY 2/3 et Q3.b toujours imprimés | code 0 ; 5 mentions « non mesuré (objet absent » ; conforme |
| Garde-fou lecture seule | `UPDATE` refusé | `cannot execute UPDATE in a read-only transaction` |
| Entrées invalides | aucune DSN, `options=` dans une DSN, date ou `REPORT_DATE` invalides ⇒ code 2 ; DSN injoignable ⇒ INCOMPLET code 1 | conforme |
| Vie privée | aucune valeur de la fixture ni le sel dans le rapport | grep des emails, noms, téléphones, UUID de la fixture, sel : aucune occurrence |
| Grep d'écriture | `bref/` et `keevo/` sans `INSERT/UPDATE/DELETE/CREATE/ALTER/DROP/TRUNCATE` | vide (`validation/` exclu par construction) |

### 4.3 Partie Keevo sur `keevo_dev` (données de développement)

Toutes les sections attendues sont produites : tenants par `plan_type × status` (344 non supprimés, tous `ACTIVE` :
283 FREE, 56 PREMIUM_TRIAL, 5 PREMIUM ; 0 DELETED ; capacité ×2 = 688), utilisateurs par rôle (418), memberships,
multi-tenant (0), tenants sans membership (37) et sans OWNER actif (38), 345 schémas `kv_%` (2 orphelins), tailles,
activité **mesurée** `user_daily_activity` (31 utilisateurs / 23 tenants actifs à 30 et 90 j, données depuis le
2026-10-14), `tenant_payments` par méthode, `tenant_plan_events` par cause × plan, `account_deletion_log`, 418 téléphones
conformes E.164 après normalisation (0 vide, 0 doublon), préfixe `+237` majoritaire. Ces valeurs servent uniquement à
prouver que les requêtes tournent sur le schéma réel ; elles n'ont aucune valeur de dimensionnement.

## 5. Produire le rapport de production

Depuis un poste ayant accès au **réplica** (ou au dump restauré) de chaque base — jamais la prod en direct — avec un
rôle en lecture seule :

```bash
cd _bmad-output/planning-artifacts/research/fusion-mesures
BREF_DSN='postgresql://ro_user:***@hote-replica-bref:5432/bref_db' \
KEEVO_DSN='postgresql://ro_user:***@hote-replica-keevo:5432/keevo' \
DEVELOP_DEPLOY_DATE=2026-10-26 \
bash run-mesures.sh
# Rapport : out/<date>/rapport-mesures-<date>.md — puis : rm out/<date>/*.hashes.csv
```

Le rapport obtenu alimente 18.2 (décisions), 18.4 (identités) et le dimensionnement des vagues d'epic 24
(capacité FUS-NFR3 = tenants Keevo non supprimés × 2, ordre des vagues par tranches de documents Q2, vague « collisions » Q4).
