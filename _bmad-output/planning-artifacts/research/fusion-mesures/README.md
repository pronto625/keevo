# Kit de mesures Bref × Keevo (story 18.1)

Mesures en lecture seule des bases **api-bref** (schéma unique TypeORM) et **Keevo** (schéma `public`) pour les
questions Q1–Q8 de l'analyse de fusion (`fusion-keevo-bref-analyse-2026-10-31.md` §12), plus les faits Q9/Q13 lus
dans les dépôts. Le résultat est un rapport Markdown daté qui ne contient **que des agrégats**.

## Règles (non négociables)

- **Lecture seule.** Chaque fichier SQL de `bref/` et `keevo/` ne contient que des `SELECT`. Le runner exporte
  `PGOPTIONS='-c default_transaction_read_only=on'` et lance `psql -X -v ON_ERROR_STOP=1` : une écriture accidentelle
  fait échouer la run (« cannot execute … in a read-only transaction »), le rapport est marqué **INCOMPLET** et le
  code retour est 1. Une DSN contenant `options=` (qui écraserait `PGOPTIONS`) est refusée (code 2). Aucune extension
  n'est requise (`md5()` natif, pas de pgcrypto), aucune table temporaire.
- **Jamais la prod en direct.** Pointer `BREF_DSN` / `KEEVO_DSN` vers un **réplica** ou un **dump restauré en local**,
  avec un rôle en lecture seule si possible. L'en-tête du rapport note si la base interrogée est un réplica
  (`pg_last_xact_replay_timestamp()` non nul) ou un primaire / dump.
- **Aucune donnée personnelle.** Le rapport ne contient ni téléphone, ni nom, ni email, ni identifiant, ni référence
  de document : uniquement des comptages, sommes, min/max/percentiles, tranches et préfixes pays (4 caractères).
  Les DSN (qui contiennent des mots de passe) ne sont jamais écrites dans le rapport.
- **Q4 sans échange de numéros.** Le runner tire un **sel aléatoire par run** (jamais écrit) ; chaque côté exporte
  `md5(sel || numéro normalisé)` (une empreinte par ligne, distincte) ; l'intersection est calculée localement par
  `comm -12`. Les fichiers `*.hashes.csv` restent dans `out/`, sont ignorés par git (`.gitignore` de ce dossier) et
  **doivent être supprimés après lecture du rapport** (le runner le rappelle en fin de run, y compris en échec).
- **Proxies déclarés.** « Actif < 90 jours » n'a pas de colonne source dans Bref : trois proxies sont rapportés et
  étiquetés (`refresh_token.createdAt`, `invoice.createdAt` dans les boutiques de l'owner, `push_token.updatedAt`).
  Côté Keevo l'activité est **mesurée** (`public.user_daily_activity`). Le « restant » des packs SMS n'est pas
  calculable : on rapporte « achetés (complétés) » et « consommés (proxy) ».
- **Schéma `main` vs `develop`.** La prod api-bref tourne `main` ; la table `refresh_token` et la colonne
  `"user"."emailVerified"` n'existent que sur `develop`. Les requêtes qui en dépendent sont gardées par `\if`
  (`to_regclass` / `information_schema.columns`) et imprimées « non mesuré (objet absent : schéma main) » ; le reste de
  la section est calculé. Même mécanisme côté Keevo pour `user_daily_activity`, `tenant_payments`, `tenant_plan_events`,
  `account_deletion_log`.
- **Un seul prédicat « en vigueur ».** `bref/_definitions.psql` porte `IN_FORCE` (= `SUBSCRIPTION_IN_FORCE` de
  `api-bref/src/entitlement/entitlement.service.ts`), inclus par `\ir` dans Q1/Q6/Q8. Quand le clone api-bref est
  présent, le runner compare les deux textes (espaces normalisés) et **avertit** en cas de divergence.

## Prérequis

- `psql` **≥ 11** (pour `\if :{?var}` et `\gset`), `bash`, coreutils (`comm`, `sort`, `awk`, `od`).
- Accès réseau aux deux réplicas / dumps locaux.
- Pour les faits Q13 / Q9 : les clones `api-bref` et `bref` à côté de ce dépôt (`../api-bref`, `../bref` depuis
  `AI/Keevo`), ou `API_BREF_DIR` / `BREF_APP_DIR`. Sans clone, la section porte la dernière valeur connue
  (étiquetée « valeur connue au 2026-11-02 ») — la run continue.

## Variables d'environnement

| Variable | Obligatoire | Rôle |
|---|---|---|
| `BREF_DSN` | l'une des deux | URI PostgreSQL de la base api-bref (réplica ou dump). Absente ⇒ Q1–Q8 et Q4 « non mesuré ». |
| `KEEVO_DSN` | l'une des deux | URI PostgreSQL de la base Keevo (réplica ou dump). Absente ⇒ partie Keevo et Q4 « non mesuré ». |
| `DEVELOP_DEPLOY_DATE` | non | Date de bascule `develop` → prod api-bref pour Q7 (`YYYY-MM-DD`, défaut `2026-10-26`, voir Q13). |
| `REPORT_DATE` | non | Suffixe du dossier et du rapport (lettres, chiffres, `.`, `_`, `-` ; défaut : date du jour). |
| `API_BREF_DIR` | non | Chemin du clone api-bref (défaut `../../../../../api-bref` relatif à ce dossier). |
| `BREF_APP_DIR` | non | Chemin du clone bref (app Expo, `app.json`) pour l'identité Bref de Q9. |

## Lancer

```bash
cd _bmad-output/planning-artifacts/research/fusion-mesures
BREF_DSN='postgresql://ro_user:***@hote-replica-bref:5432/bref_db' \
KEEVO_DSN='postgresql://ro_user:***@hote-replica-keevo:5432/keevo' \
bash run-mesures.sh
```

Le rapport est écrit dans `out/<date>/rapport-mesures-<date>.md` ; les empreintes Q4 dans `out/<date>/*.hashes.csv`.
Le chemin du kit ne doit pas contenir d'espace (il est passé à `\o` de psql ; le runner le vérifie).

**Codes retour du runner** : `0` run complète (éventuellement « avec avertissements », listés dans l'en-tête) ;
`1` run **INCOMPLET** — au moins une section a échoué, le rapport existe mais ne doit pas servir de référence ;
`2` prérequis manquant ou entrée invalide (aucune DSN, `options=` dans une DSN, date ou `REPORT_DATE` invalides,
`psql` absent) — aucun rapport n'est produit. À l'intérieur d'un bloc fencé, « ERREUR psql (code N) » reprend le
code de **psql** lui-même (3 = erreur SQL avec `ON_ERROR_STOP`, 2 = connexion impossible), distinct du code du runner.

Après lecture : `rm out/<date>/*.hashes.csv`. Le dossier `out/` entier est ignoré par git ; le rapport de production
est à partager par un autre canal que ce dépôt si l'on veut le conserver, ou à copier à côté de
`rapport-mesures-2026-11-02-preparatoire.md` après vérification qu'il ne contient que des agrégats.

## Contenu

| Fichier | Question | Contenu |
|---|---|---|
| `bref/_definitions.psql` | — | prédicat `IN_FORCE` partagé (inclus par `\ir`) |
| `bref/q1-owners-plans-paiements.sql` | Q1 | owners total / soft-deleted, actifs < 90 j (3 proxies, PROXY 1 gardé), payants par plan × moyen (apple / admin_grant / mycoolpay), historique `subscription`, grâce Apple, `plan_payment_request` |
| `bref/q2-volumes.sql` | Q2 | estimation catalogue de toutes les tables (`pg_stat_user_tables`), comptages exacts (live / soft-deleted), documents par type × statut et par année, percentiles par owner, tranches de documents, taille |
| `bref/q3-identites-sans-telephone.sql` | Q3 | owners sans téléphone par provider / email (`emailVerified` gardé), membres sans téléphone par identifiant, superadmin compté à part, conformité E.164 et préfixe pays connu après normalisation, vides, doublons |
| `bref/q4-export-hashes-telephones.sql` | Q4 | export `md5(sel ‖ numéro)` owners et membres (fichiers `out/`) |
| `bref/q5-pays-tva-montants.sql` | Q5 | boutiques par pays, hors CM, contrôle TVA par pays (conforme / TVA ignorée sur net > 0 / écart), taux effectifs ≠ 19,25 %, montants non entiers par colonne (live + soft-deleted), quantités décimales, bornes |
| `bref/q6-credits-sms.sql` | Q6 | `account_credit`, `usage_counter` par métrique (période UTC comme le code), `sms_free` à vie, packs SMS par statut, bilan achetés vs consommés (proxy) |
| `bref/q7-totaux-incoherents.sql` | Q7 | `total` ≠ recalcul par période (avant / **jour de la bascule** / depuis develop) × type × statut, tranches d'écart, `subtotal` ≠ somme des lignes, lignes incohérentes, encaissé vs total |
| `bref/q8-plan-sans-abonnement.sql` | Q8 | matrice `user.plan` × plan en vigueur (le plus récemment commencé, comme `activePlanOf`), owners `plan ≠ free` sans abonnement en vigueur, ancienneté d'expiration, abonnements `active` hors vigueur par cause |
| `keevo/k1-tenants-users-memberships.sql` | Keevo | tenants par plan × statut, capacité ×2 hors DELETED (FUS-NFR3), users, memberships, multi-tenant, schémas `kv_%` vs tenants, tailles, refresh tokens, activité mesurée 30/90 j, paiements, événements de plan, suppressions |
| `keevo/k2-telephones.sql` | Keevo | conformité E.164 et préfixe pays connu, vides, préfixes, doublons, longueurs |
| `keevo/k4-export-hashes-telephones.sql` | Q4 | export `md5(sel ‖ numéro)` OWNER actifs et EMPLOYEE actifs (deux fichiers) |
| `run-mesures.sh` | — | validation des entrées, garde-fou lecture seule, provenance (révision du kit, version serveur, réplica), parité `IN_FORCE`, intersection Q4 + matrice 2×2 par rôle, faits Q9/Q13 (`merge-base main develop`, `app.json` Bref), checklist opérateur |
| `validation/fixture-bref-synthetique.sql` | — | fixture **synthétique** (numéros et identités inventés) pour un Postgres jetable uniquement |
| `validation/validate-local.sh` | — | validation rejouable : Postgres jetable, schéma via `typeorm schema:sync`, fixture, run, assertions de la matrice, passe « schéma main », garde-fou, entrées invalides |
| `rapport-mesures-2026-11-02-preparatoire.md` | — | rapport préparatoire commité : faits dépôts, validation locale (données de dev / synthétiques), checklist ouverte, commande de production |
| `rapport-repetition-bascule-2026-11-11.md` | 24.12 | répétition générale de la bascule sur la copie de prod Bref du 03/11 : chronométrage J0, durée de coupure, réconciliation (agrégats), retour arrière, montée en charge 4 / 8, correctif des interblocages |

Le contrôle d'écriture (`grep -rniE '^\s*(insert|update|delete|create|alter|drop|truncate)\b' bref keevo`) porte sur
`bref/` et `keevo/` seulement : `validation/` contient volontairement des `INSERT`/`DROP` destinés au Postgres jetable
et ne doit jamais être exécuté par `run-mesures.sh` ni sur une base réelle.

Définitions appliquées : abonnement en vigueur = `IN_FORCE` (`bref/_definitions.psql`, copie de `SUBSCRIPTION_IN_FORCE`
avec parité vérifiée par le runner) ; `user.plan` n'est qu'une colonne de comparaison ; normalisation téléphone =
`PhoneNumbers.normalize` de Keevo (trim, suppression espaces/`-`/`.`/`(`/`)`, préfixe `+` ; vide ⇒ exclu) ; préfixe pays
connu = `+237|+241|+235|+243|+221` (OTP Bref) ; recalcul Q7 = `(subtotal − discount) + tax + deliveryFee` avec 1 XAF de
tolérance ; taux TVA = `api-bref/src/invoice/constants/vat-rates.constant.ts`.

## Rejouer par vague (epic 24)

Le kit est idempotent et ne dépend d'aucun état local : pour chaque vague de migration, relancer la même commande sur
l'export (dump) de la vague avec un `REPORT_DATE` explicite (par ex. `REPORT_DATE=2027-02-10-vague-3`) ; l'en-tête du
rapport porte la révision du kit et la provenance des bases, ce qui permet de comparer les rapports entre eux (volumes
Q2, Q4, Q7) et avec le rapport de production initial. Si une table est ajoutée à Bref, l'estimation catalogue de Q2.a la
montre déjà ; l'ajouter ensuite aux comptages exacts de Q2.b. Si la règle de plan change, mettre à jour
`bref/_definitions.psql` (le runner signale l'écart).

## Validation locale

```bash
# Postgres jetable sur le port 5499 (PG_PORT pour changer), schéma depuis ../api-bref (API_BREF_DIR pour changer),
# partie Keevo et Q4 si KEEVO_DSN est fournie (ex. keevo_dev local) :
KEEVO_DSN='postgresql://keevo:***@localhost:5444/keevo_dev' bash validation/validate-local.sh
```

Le script supprime le conteneur et `out/validation-*` en sortie (`KEEP=1` pour les garder). Dernière exécution et
résultats : `rapport-mesures-2026-11-02-preparatoire.md` §4.
