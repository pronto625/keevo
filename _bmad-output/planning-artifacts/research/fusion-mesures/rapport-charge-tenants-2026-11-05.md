# Rapport de charge — nombre de tenants (story 19.11, R1, FUS-NFR3) — 2026-11-05

Mesures locales sur une copie jetable de `keevo_dev` (363 schémas tenants vivants), avec le harnais
`keevo/backend/scripts/load/measure-tenants.sh`. Agrégats uniquement, aucune donnée personnelle.

## N cible

- **N = 52 schémas** : rapport 18-1 (`rapport-mesures-2026-11-03-fusion.md`, « Capacité FUS-NFR3 ») — 26 tenants après
  migration Bref (25 entreprises Keevo en production + 1 owner Bref) × 2.
- La copie mesurée porte 363 schémas, soit **≈ 7 × N** : une mesure conforme ici est conservatrice pour N.

## Environnement

| Élément | Valeur |
|---|---|
| Hôte | Intel Core i5-13500H, 16 threads, 15 Go de RAM (poste de développement, autres charges présentes) |
| PostgreSQL | 16.15 (conteneur Docker), `max_connections` 100, `max_locks_per_transaction` 64, `shared_buffers` 128 Mo |
| Base mesurée | 363 tenants vivants, 364 schémas `kv_`, ≈ 1 Go, 20 053 relations (≈ 54 par tenant) |
| Application | jar empaqueté (comme l'image Docker), profil `load` (niveaux de log de type prod), port 4600, fournisseurs de messagerie et planification désactivés, `-Xmx2g` |
| Migration au démarrage | `keevo.tenant-schema.upgrade-parallelism` = 4 (défaut), pool Flyway dédié de 5 connexions, pool applicatif Hikari de 10 |
| Exécutions | 5 passes (runs 1–5) le 2026-11-05/06 ; run 3 en jar extrait (`--extract`), les autres en jar empaqueté ; run 5 en parallélisme 1 |

Le harnais clone la base source (`CREATE DATABASE … TEMPLATE`), démarre l'application deux fois (rattrapage puis
démarrage à chaud), supprime la dernière ligne `flyway_history_messaging` de chaque schéma vivant puis redémarre
(migration d'un module sur tous les schémas), chronomètre des inscriptions `POST /api/v1/auth/register`, puis
`pg_dump -Fc` / `pg_restore` dans le conteneur ; il supprime les bases jetables à la fin.

## Partie DDL du critère d'acceptation

Livrée par la **story 16-12** (`spec-16-12-conversion-ddl-tenant-modules.md`) : toutes les tables tenant viennent des
migrations Flyway par module (`db/modules/<module>/V*.sql`, idempotentes), et `TenantSchemaSyncService` a été supprimé.
Vérifié pour 19.11 : aucune occurrence de `TenantSchemaSyncService` dans `keevo/backend/src/main` (les seules mentions
restantes sont des commentaires de migrations publiques déjà livrées, qui ne se modifient plus).

## Mesures

| Cible | Mesuré sur 363 schémas | Ramené à N = 52 (linéaire) | Verdict à N |
|---|---|---|---|
| Démarrage < 2 min | 10,0 à 17,2 s (vérification des tables tenants : 0,8 à 1,2 s) | ≈ 10 s | **OK** |
| Provisioning d'un tenant < 5 s | inscription complète : min 2,61 s, médiane ≈ 2,9 à 3,2 s, max 3,87 s (2 séries de 10) | ≈ 3 s (indépendant du nombre de tenants) | **OK** |
| Migration d'un module sur tous les schémas < 10 min | 1,8 à 6,4 s (≈ 10 ms par tenant, script `messaging` V3 : un index) | ≈ 0,5 s | **OK en durée ; KO en fiabilité** (≈ 6 % des tenants en échec, voir plus bas) |
| Sauvegarde | `pg_dump` unique : **échec** après 0,4 s (« out of shared memory ») ; par lots de 100 schémas : 13,8 s, 42 Mo | ≈ 2 s ; un `pg_dump` unique passe encore à N | mesuré (pas de cible) ; limite ci-dessous |
| Restauration | `pg_restore` des lots : 883 s pour 373 tenants, 0 erreur | ≈ 123 s (≈ 2 min) | mesuré (pas de cible) |

Échecs de tenants pendant la migration au démarrage (`failure=ERROR`), relancés au démarrage suivant ou à la première
requête du tenant, et réussis au démarrage suivant :

| Passe | Disposition | Étape | Tenants en échec |
|---|---|---|---|
| 1 | jar empaqueté | premier démarrage (rattrapage) | 4 / 362 |
| 1 | jar empaqueté | migration d'un module | 16 / 362 |
| 2 | jar empaqueté | premier démarrage (rattrapage) | 13 / 362 |
| 2 | jar empaqueté | migration d'un module | 297 / 362, puis chargeur de classes cassé |
| 3 | jar extrait | migration d'un module | 26 / 362 |
| 4 | jar empaqueté | migration d'un module | 22 / 362 |
| 5 | jar empaqueté, **parallélisme 1** | migration d'un module | **0 / 362** (9,8 s) |

Le run 5 (parallélisme 1, même jar empaqueté) migre les 362 tenants sans échec, en 9,8 s au lieu de 3,9 s : les échecs
viennent bien de l'exécution concurrente.

## Constat 1 — migration parallèle au démarrage depuis le jar empaqueté

- **Cause racine** (journalisée depuis le run 4) : lectures concurrentes des scripts de migration dans le jar empaqueté.
  Chaque exécution Flyway parcourt le classpath et lit les scripts dans le jar ; les 4 fils de la migration partagent le
  même fichier jar ouvert, et l'un le ferme pendant que l'autre lit. Causes relevées au run 4 :
  `IOException: Stream closed` (18), `IllegalStateException: Zip file closed` (2), `ZipException: ZipFile closed` (2).
  Aucune erreur SQL côté PostgreSQL.
- **Gravité** : au run 2, le chargeur de classes de l'application a lui-même été touché
  (`NoClassDefFoundError` / `ClassNotFoundException` sur des classes de l'application) ; toutes les inscriptions ont
  ensuite répondu HTTP 500 jusqu'à l'arrêt.
- Le démarrage par `mvn spring-boot:run` (classes en répertoire, hors jar) a migré 362 / 362 tenants sans échec : c'est
  pourquoi les mesures de 16-12 n'ont pas vu le problème.
- **Impact production** : l'image Docker exécute le jar empaqueté. Chaque mise en production qui ajoute un script de
  module ferait échouer une part aléatoire des tenants (≈ 6 % mesuré), avec un risque d'application inutilisable
  jusqu'au redémarrage.

## Constat 2 — premier démarrage après la mise en production

16-12 a mesuré le rattrapage complet (9 modules sur des tenants anciens) : 143 s pour 343 tenants, soit ≈ 0,42 s par
tenant avec un parallélisme de 4. Par extrapolation linéaire, ce premier démarrage dépasse 2 min à partir d'environ
**260 tenants** (≈ 32 s à N = 52). Il ne se produit qu'une fois, au passage à la refonte.

## Constat 3 — limite d'une sauvegarde en un seul `pg_dump`

Un `pg_dump` verrouille toutes les tables dans une seule transaction : il faut
`max_locks_per_transaction × max_connections ≥ nombre de relations`. Avec ≈ 54 relations par tenant et 64 × 100 =
6 400 verrous, la sauvegarde en un seul `pg_dump` casse vers **110 tenants**. Le `backup-db.sh` de production fait un
`pg_dump` unique.

Ce seuil est **calculé avec les réglages locaux** (64 × 100). Il doit être recalculé avec les valeurs de production :
`max_locks_per_transaction × (max_connections + max_prepared_transactions)` ≥ nombre de relations.

## Seuils par extrapolation linéaire

| Cible | Seuil de dépassement estimé |
|---|---|
| Démarrage à chaud < 2 min | non atteint dans un horizon prévisible (vérification ≈ 2 ms par tenant) |
| Premier démarrage après mise en production < 2 min | ≈ 260 tenants |
| Provisioning d'un tenant < 5 s | pas de dépendance au nombre de tenants mesurée |
| Migration d'un module < 10 min | ≈ 57 000 tenants pour un script léger ; ≈ 1 400 pour un rattrapage complet à 0,42 s par tenant |
| Sauvegarde en un seul `pg_dump` | ≈ 110 tenants avec les réglages locaux (64 × 100 verrous) ; à recalculer avec ceux de production |

## Décision

- **À N = 52, les cibles de durée sont tenues avec une large marge** : pas de changement d'architecture (pool de
  connexions, migrations paresseuses par tenant, schéma partagé pour les tenants FREE inactifs) avant l'epic 24.
  Ces leviers restent prévus si le nombre de tenants approche les seuils ci-dessus.
- **Correction de la migration parallèle REQUISE avant tout déploiement en production de la refonte et avant
  l'epic 24** — routée vers la story **16-12** :
  - lire une fois les scripts de module en mémoire au démarrage et les fournir à chaque exécution Flyway par son
    `resourceProvider`, au lieu d'un parcours du classpath par tenant (plus aucun jar ouvert pendant la migration
    parallèle) ;
  - ajouter un test qui exécute la migration parallèle depuis un jar empaqueté.
- **Sauvegarde** : avant ≈ 100 tenants (seuil local, à recalculer avec les réglages de production), relever `max_locks_per_transaction` (par exemple à 256) ou passer à des
  sauvegardes par lots de schémas, comme le fait le harnais — routé vers la story **10-8**.
- **Restauration** : ≈ 2 min à N, ≈ 15 min à 373 tenants ; à intégrer à la procédure de reprise.
- Mesure à rejouer sur staging (même harnais) : routée vers la story 17-9.

## Correctif 16-12 — passes du 2026-11-06

Correctif livré par `spec-16-12-2-upgrade-parallele-scripts-en-memoire.md` : les scripts de module sont lus une seule
fois depuis le classpath (`ModuleScriptCache`, une lecture à la fois sous verrou) et chaque exécution Flyway les reçoit
par un `resourceProvider` en mémoire, sans parcours du classpath : plus aucune lecture du jar pendant la migration
parallèle. Même harnais, même clone de `keevo_dev` (363 schémas vivants, 362 avec historique), **jar empaqueté,
parallélisme 4**, migration du dernier script `messaging` sur tous les schémas.

| Passe | Premier démarrage (historiques écrits par l'ancien code) | Démarrage à chaud | Migration d'un module | Inscriptions (médiane, 10) |
|---|---|---|---|---|
| 1 (verrou par module) | 362 vérifiés, 0 rejoué, **0 échec** | 0 échec | 362 / 362, **0 échec**, 3,6 s | 3,19 s, 0 erreur HTTP |
| 2 (version finale) | 362 vérifiés, 0 rejoué, **0 échec** | 0 échec | 362 / 362, **0 échec**, 4,7 s | 3,28 s, 0 erreur HTTP |
| 3 (version finale) | 362 vérifiés, 0 rejoué, **0 échec** | 0 échec | 362 / 362, **0 échec**, 4,1 s | 3,22 s, 0 erreur HTTP |
| 4 (version finale) | 362 vérifiés, 0 rejoué, **0 échec** | 0 échec | 362 / 362, **0 échec**, 3,7 s | 2,74 s, 0 erreur HTTP |

- Aucune occurrence de `Validate failed`, `Stream closed`, `Zip file closed`, `NoClassDefFoundError` ou
  `ClassNotFoundException` dans les journaux des quatre passes : les sommes de contrôle des historiques existants sont
  inchangées, et l'application répond normalement après la migration (inscriptions sans erreur).
- La durée de migration reste celle du parallélisme 4 (3,6 à 4,7 s pour 362 tenants, contre 9,8 s en parallélisme 1).
- Le constat 1 est **levé** ; le parallélisme par défaut reste 4. Les constats 2 (premier démarrage) et 3 (sauvegarde)
  sont inchangés (sauvegarde en un seul `pg_dump` toujours KO avec 64 × 100 verrous, lots de 100 schémas OK).

---

- Harnais : `keevo/backend/scripts/load/measure-tenants.sh` (README à côté), tests de garde
  `keevo/backend/scripts/tests/measure-tenants-guard-test.sh`.
- Bases jetables supprimées après chaque passe ; source jamais modifiée par le harnais.
