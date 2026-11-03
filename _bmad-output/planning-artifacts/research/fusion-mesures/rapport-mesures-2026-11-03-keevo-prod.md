# Rapport de mesures — base Keevo de production (2026-11-03)

Copie de la base Keevo de production (pg_dump en lecture seule du 2026-11-03, restauré sur une base locale jetable), kit `run-mesures.sh`, partie Keevo seulement (`BREF_DSN` absent : Q1–Q8 et Q4 restent à mesurer). Agrégats uniquement.

- Statut de la run : complet avec avertissements (1) : BREF_DSN absent : Q1–Q8 et l'intersection Q4 ne sont pas mesurés. ; 
- Révision du kit (dépôt Keevo) : `e8d7e4a0`, dossier du kit propre
- Bref (api-bref) : non mesuré (BREF_DSN absent)
- Keevo : DSN fournie — PostgreSQL 16.15 | primaire ou dump restauré (pas de rejeu en cours)
- Date de bascule `develop` utilisée pour Q7 (`DEVELOP_DEPLOY_DATE`) : 2026-10-26
- Horodatage : 2026-11-03T20:33:54Z (UTC)

## Partie 2 — Base Keevo (schéma public)

### K1 — Tenants, utilisateurs, memberships, schémas kv_%, activité, paiements

Source : `keevo/k1-tenants-users-memberships.sql`

```text

#### K1.a — Tenants par plan_type × status
   plan_type   |    status    | tenants | avec_suppression_programmee 
---------------+--------------+---------+-----------------------------
 FREE          | ACTIVE       |      12 |                           0
 FREE          | tous statuts |      12 |                           0
 PREMIUM       | ACTIVE       |       1 |                           0
 PREMIUM       | tous statuts |       1 |                           0
 PREMIUM_TRIAL | ACTIVE       |      12 |                           0
 PREMIUM_TRIAL | tous statuts |      12 |                           0
 TOTAL         | tous statuts |      25 |                           0


#### K1.b — Capacité FUS-NFR3 : tenants non supprimés × 2 (DELETED comptés à part)
 tenants_non_supprimes | tenants_actifs | capacite_cible_x2 | suppression_programmee | tenants_deleted | lignes_tenants 
-----------------------+----------------+-------------------+------------------------+-----------------+----------------
                    25 |             25 |                50 |                      0 |               0 |             25


#### K1.c — Tenants par année de création
 annee | tenants 
-------+---------
  2026 |      25


#### K1.d — Utilisateurs (public.users) par role × is_active
    role     | is_active | utilisateurs | verrouilles 
-------------+-----------+--------------+-------------
 EMPLOYEE    | t         |           14 |           0
 OWNER       | t         |           24 |           0
 SUPER_ADMIN | t         |            2 |           0


#### K1.e — Memberships (user_tenant_memberships) par role × is_active
    role     | is_active | memberships | utilisateurs | tenants 
-------------+-----------+-------------+--------------+---------
 EMPLOYEE    | t         |          14 |           14 |       8
 OWNER       | t         |          24 |           24 |      24
 SUPER_ADMIN | t         |           3 |            3 |       1


#### K1.f — Utilisateurs par nombre de memberships (multi-tenant)
 memberships_par_utilisateur | utilisateurs 
-----------------------------+--------------
                           1 |           39
                           2 |            1


#### K1.g — Utilisateurs avec > 1 membership ; tenants sans aucun membership ; tenants sans OWNER actif
 users_multi_membership | tenants_sans_membership | tenants_sans_owner_actif 
------------------------+-------------------------+--------------------------
                      1 |                       0 |                        1


#### K1.h — Schémas kv_% (pg_namespace) vs tenants déclarés (hors DELETED)
 schemas_kv | tenants_non_supprimes | tenants_sans_schema | tenants_deleted_schema_restant | schemas_orphelins 
------------+-----------------------+---------------------+--------------------------------+-------------------
         24 |                    25 |                   0 |                              0 |                 0


#### K1.i — Taille de la base, des schémas kv_% et de public (pg_total_relation_size des tables, index et TOAST inclus)
 taille_base | taille_schemas_kv | taille_public 
-------------+-------------------+---------------
 86 MB       | 64 MB             | 2720 kB


#### K1.j — Refresh tokens Keevo (public.refresh_tokens) : actifs / révoqués / expirés (proxy d activité < 90 j)
 tokens | valides | utilisateurs_token_cree_90j 
--------+---------+-----------------------------
     64 |      53 |                          10


#### K1.k — Activité MESURÉE (public.user_daily_activity) : utilisateurs et tenants actifs < 30 j et < 90 j
 utilisateurs_actifs_30j | tenants_actifs_30j | utilisateurs_actifs_90j | tenants_actifs_90j | premiere_date_connue 
-------------------------+--------------------+-------------------------+--------------------+----------------------
                       7 |                  3 |                       7 |                  3 | 2026-10-18


#### K1.l — Paiements, événements de plan et suppressions de compte (public)
-- tenant_payments par méthode (paiements non annulés)
 method | paiements | tenants | somme_xaf 
--------+-----------+---------+-----------

-- tenants couverts par un paiement (period_end >= aujourd hui), paiements annulés
 tenants_payes_periode_courante | paiements_annules 
--------------------------------+-------------------
                              0 |                 0

-- tenant_plan_events par cause × plan cible
    cause     |    to_plan    | evenements | tenants 
--------------+---------------+------------+---------
 EXPIRY       | FREE          |          4 |       4
 REALIGNMENT  | FREE          |          8 |       8
 REGISTRATION | PREMIUM_TRIAL |         24 |      24

account_deletion_log : non mesuré (objet absent)
```

### K2 — Téléphones : préfixes et conformité E.164

Source : `keevo/k2-telephones.sql`

```text

#### K2.a — Téléphones : conformité ^+[0-9]{8,15}$ après normalisation, préfixe pays connu, numéros modifiés par la normalisation
 utilisateurs | vide_apres_normalisation | format_e164_ok | format_invalide | prefixe_pays_connu | sans_prefixe_connu | modifies_par_normalisation | telephones_distincts_normalises 
--------------+--------------------------+----------------+-----------------+--------------------+--------------------+----------------------------+---------------------------------
           40 |                        0 |             40 |               0 |                 39 |                  1 |                          0 |                              40


#### K2.b — Préfixes (4 premiers caractères après normalisation)
 prefixe | utilisateurs 
---------+--------------
 +237    |           39
 +336    |            1


#### K2.c — Doublons après normalisation (un même numéro porté par > 1 utilisateur)
 telephones_en_doublon | utilisateurs_concernes 
-----------------------+------------------------
                     0 |                      0


#### K2.d — Longueur des numéros normalisés (chiffres après le +)
 chiffres | utilisateurs 
----------+--------------
       11 |            1
       12 |           39

```


## Lecture

- **25 entreprises** (12 FREE, 12 PREMIUM_TRIAL, 1 PREMIUM), dont l'entreprise technique du super-admin ; **24 owners**, **14 employés**, **2 super-admins** ; 1 utilisateur a deux entreprises.
- **Activité mesurée** (`user_daily_activity`, données depuis le 2026-10-18) : **3 entreprises et 7 utilisateurs actifs** sur les 30 et 90 derniers jours.
- **Aucun paiement enregistré** ; 4 passages en FREE par fin d'essai, 8 réalignements.
- **Téléphones** : 40 numéros, tous au format E.164, 39 en +237, 1 en +33 ; aucun doublon.
- **Capacité FUS-NFR3** : 50 schémas (25 × 2). Base de 86 Mo.
- Le volume Keevo est petit : la charge de la fusion viendra des owners Bref (Q1–Q2, à mesurer).
