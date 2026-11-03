# Rapport de mesures Bref × Keevo — 2026-11-03

**Sources.**
- **Keevo** : `pg_dump` en lecture seule de la base de production (serveur `oracle`, conteneur `keevo_postgres`), le 2026-11-03, restauré sur une base locale jetable.
- **Bref** : dump fourni par l'utilisateur le 2026-11-03 (`bd_local_20261103_213348.sql.gz`), retenu par décision de l'utilisateur comme source Bref de 18.1. **Signes d'une base de développement** relevés avant la mesure : 5 utilisateurs créés les 22–23/10, migrations jusqu'à `AddNuiRccm` (branche `develop`, non déployée sur `main`), 297 jetons de session pour 5 utilisateurs, nom « bd_local ». Si une base de production Bref plus volumineuse existe, relancer le kit dessus (README) et revoir le dimensionnement de l'epic 24.

Les deux copies, les fichiers d'empreintes et le dump ont été supprimés après la mesure. Agrégats uniquement.

## Synthèse

| Question | Résultat |
|---|---|
| Q1 owners Bref | 1 owner live, actif (3 proxies), payant **business** par `admin_grant` (abonnement en vigueur) ; aucune demande MyCoolPay, aucun achat Apple |
| Q2 volumes Bref | 1 boutique, 3 membres (1 admin, 2 vendeurs), 19 documents (16 ventes dont 14 payées et 2 annulées, 3 devis), 36 lignes, 8 paiements, 3 clients, 6 produits, 0 dépense ; base de 9,5 Mo |
| Q3 identités | l'owner a un téléphone et un email vérifié ; les 3 membres n'ont qu'un username (aucun téléphone) |
| Q4 collisions | **1** : le téléphone de l'owner Bref est celui d'un **OWNER Keevo** actif ; 0 membre en collision |
| Q5 pays, TVA, montants | 1 boutique CM ; aucun montant non entier ; **17 documents sur 19 sans TVA** (« TVA ignorée » à l'émission), 2 conformes |
| Q6 crédits, SMS | aucun crédit de compte, aucun pack SMS acheté ; 4 SMS consommés |
| Q7 totaux | 0 total incohérent, avant comme après la bascule `develop` |
| Q8 plan sans abonnement | 0 |
| Keevo (prod) | 25 entreprises (12 FREE, 12 essai, 1 Premium), 24 owners, 14 employés, 3 entreprises actives sur 30 jours, aucun paiement, 40 téléphones E.164 sans doublon ; base de 86 Mo |
| Q9 / Q13 | dépôts : Keevo `com.example.keevo` ; api-bref `main` = `develop` du 26/10 (a3faeb2). Confirmations console et GHCR : story 18.4 |

## Conséquences pour la suite

- **Epic 24 (vagues)** : avec cette source, une seule migration Bref, qui est aussi une **collision** (même personne owner des deux côtés) → c'est le cas « liaison » de l'ADR identité (même `public.users`, deuxième membership OWNER).
- **Membres sans téléphone** : la connexion par username (19.4) est indispensable dès la première vague.
- **TVA ignorée sur 17/19 documents** : l'import doit conserver l'exonération par document (20.5, `vat_exempt`).
- **Capacité FUS-NFR3** : 26 tenants après migration, cible 52 schémas.

---

- Généré par `_bmad-output/planning-artifacts/research/fusion-mesures/run-mesures.sh` (story 18.1).
- Statut de la run : complet
- Révision du kit (dépôt Keevo) : `9d35ae58`, dossier du kit propre
- Bref (api-bref) : DSN fournie — PostgreSQL 16.15 | primaire ou dump restauré (pas de rejeu en cours)
- Keevo : DSN fournie — PostgreSQL 16.15 | primaire ou dump restauré (pas de rejeu en cours)
- Date de bascule `develop` utilisée pour Q7 (`DEVELOP_DEPLOY_DATE`) : 2026-10-26
- Horodatage : 2026-11-03T20:38:06Z (UTC)

> Agrégats uniquement : aucun téléphone, nom, email, identifiant ni référence de document n'apparaît dans ce rapport.
> Toutes les requêtes ont tourné dans des transactions PostgreSQL en lecture seule.
> Les mentions « PROXY » signalent une mesure indirecte (aucune colonne source directe) ; « non mesuré (objet absent) »
> signale une table ou colonne absente du schéma interrogé (ex. objets `develop` sur un réplica de `main`).

## Partie 1 — Base Bref (api-bref) : Q1–Q8

### Q1 — Owners : total, actifs < 90 j (proxies), payants par plan × moyen de paiement

Source : `bref/q1-owners-plans-paiements.sql`

```text

#### Q1.a — Owners : total, actifs/inactifs, supprimés (soft delete)
 etat | compte_actif | owners 
------+--------------+--------
 live | t            |      1


#### Q1.b — Owners « actifs < 90 jours » : trois PROXIES (aucune colonne de dernière connexion)
                          mesure                          | owners 
----------------------------------------------------------+--------
 owners live (base)                                       |      1
 PROXY 1 : refresh_token < 90 j (owner ou membre)         |      1
 PROXY 2 : invoice créée < 90 j dans ses boutiques        |      1
 PROXY 3 : push_token mis à jour < 90 j (owner ou membre) |      1
 au moins un proxy (union 1+2+3)                          |      1


#### Q1.c — Owners payants (abonnement EN VIGUEUR) par plan × moyen de paiement
   plan   | moyen_paiement | owners 
----------+----------------+--------
 TOTAL    | tous moyens    |      1
 business | admin_grant    |      1
 business | tous moyens    |      1


#### Q1.d — Owners payants : total distinct et owners ayant > 1 abonnement en vigueur (anomalie)
                mesure                 | valeur 
---------------------------------------+--------
 owners payants distincts              |      1
 owners avec > 1 abonnement en vigueur |      0


#### Q1.e — Toutes les lignes subscription par statut × moyen de paiement (historique inclus)
  status   | moyen_paiement | lignes | owners | somme_amount_paid 
-----------+----------------+--------+--------+-------------------
 active    | admin_grant    |      1 |      1 |                 0
 cancelled | admin_grant    |      3 |      1 |                 0


#### Q1.f — Abonnements en grâce Apple (endsAt dépassé, gracePeriodEndsAt > now)
 abonnements_en_grace | owners 
----------------------+--------
                    0 |      0


#### Q1.g — Demandes de paiement MyCoolPay (plan_payment_request) par statut × moyen
 status | moyen_paiement | demandes | somme_amount | somme_credit_applique | somme_due 
--------+----------------+----------+--------------+-----------------------+-----------

```

### Q2 — Volumes par table et répartition par owner

Source : `bref/q2-volumes.sql`

```text

#### Q2.a — Toutes les tables de public : estimation catalogue (pg_stat_user_tables.n_live_tup, taille totale)
             table              | lignes_estimees | mortes_estimees | taille_totale 
--------------------------------+-----------------+-----------------+---------------
 account_credit                 |               0 |               0 | 16 kB
 ai_conversation                |               2 |               0 | 48 kB
 app_version                    |               0 |               0 | 16 kB
 apple_iap_transaction          |               0 |               0 | 40 kB
 apple_iap_verification_request |               0 |               0 | 32 kB
 campaign                       |               3 |               0 | 64 kB
 client                         |               0 |               0 | 32 kB
 customer                       |               3 |               0 | 48 kB
 customer_campaign              |               0 |               0 | 24 kB
 expense                        |               0 |               0 | 32 kB
 invoice                        |              19 |               0 | 48 kB
 invoice_item                   |              36 |               0 | 32 kB
 invoice_outbox                 |               4 |               0 | 48 kB
 invoice_payment                |               8 |               0 | 32 kB
 migrations                     |              36 |               0 | 32 kB
 notification                   |              69 |               0 | 104 kB
 otp                            |              18 |               0 | 48 kB
 owner_whatsapp_template        |               1 |               0 | 48 kB
 plan_payment_request           |               0 |               0 | 16 kB
 product                        |               6 |               0 | 48 kB
 push_token                     |               3 |               0 | 64 kB
 refresh_token                  |             297 |               0 | 160 kB
 revenuecat_webhook_event       |               0 |               0 | 16 kB
 shop                           |               1 |               0 | 32 kB
 sms_recharge_request           |               0 |               0 | 16 kB
 subscription                   |               4 |               0 | 32 kB
 usage_counter                  |               4 |               0 | 48 kB
 user                           |               5 |               0 | 80 kB
 web_push_subscription          |               0 |               0 | 32 kB
 whatsapp_account               |               0 |               0 | 24 kB
 whatsapp_chat_message          |               0 |               0 | 32 kB
 whatsapp_message               |               6 |               0 | 80 kB


#### Q2.b — Comptages exacts par table (live / soft-deleted quand la table a "deletedAt")
              table              |     etat     | lignes 
---------------------------------+--------------+--------
 account_credit                  | all          |      0
 ai_conversation                 | all          |      2
 app_version                     | all          |      0
 apple_iap_transaction           | all          |      0
 apple_iap_verification_request  | all          |      0
 campaign                        | all          |      3
 client (comptes portail)        | all          |      0
 customer                        | all          |      3
 customer_campaign               | all          |      0
 expense                         | live         |      0
 expense                         | soft-deleted |      0
 invoice                         | live         |     19
 invoice                         | soft-deleted |      0
 invoice_item                    | all          |     36
 invoice_outbox                  | all          |      4
 invoice_payment                 | all          |      8
 notification                    | all          |     69
 otp                             | all          |     18
 owner_whatsapp_template         | all          |      1
 plan_payment_request            | all          |      0
 product                         | all          |      6
 product (isArchived)            | all          |      0
 product (mergedIntoId not null) | all          |      0
 push_token                      | all          |      3
 shop                            | live         |      1
 shop                            | soft-deleted |      0
 sms_recharge_request            | all          |      0
 subscription                    | all          |      4
 usage_counter                   | all          |      4
 user                            | live         |      5
 user                            | soft-deleted |      0
 web_push_subscription           | all          |      0
 whatsapp_account                | all          |      0
 whatsapp_chat_message           | all          |      0
 whatsapp_message                | all          |      6


#### Q2.b-bis — refresh_token (table develop seulement)
     table     | etat | lignes | valides 
---------------+------+--------+---------
 refresh_token | all  |    297 |       6


#### Q2.c — Utilisateurs par rôle (live)
    role    | utilisateurs 
------------+--------------
 admin      |            1
 owner      |            1
 seller     |            2
 superadmin |            1


#### Q2.d — Documents (invoice live) par type × statut
 type  |    status    | documents | somme_total 
-------+--------------+-----------+-------------
 TOTAL | tous statuts |        19 |  1949447.00
 quote | pending      |         3 |    14600.00
 quote | tous statuts |         3 |    14600.00
 sale  | cancelled    |         2 |   976150.00
 sale  | paid         |        14 |   958697.00
 sale  | tous statuts |        16 |  1934847.00


#### Q2.e — Documents par année de création (invoice live)
 annee | documents 
-------+-----------
  2026 |        19


#### Q2.f — Répartition par owner (percentiles sur les owners live) : taille des vagues
  mesure   | total | min | p50  | p90  | p99  | max | owners_a_zero 
-----------+-------+-----+------+------+------+-----+---------------
 clients   |     3 |   3 |  3.0 |  3.0 |  3.0 |   3 |             0
 depenses  |     0 |   0 |  0.0 |  0.0 |  0.0 |   0 |             1
 documents |    19 |  19 | 19.0 | 19.0 | 19.0 |  19 |             0
 lignes    |    36 |  36 | 36.0 | 36.0 | 36.0 |  36 |             0
 membres   |     3 |   3 |  3.0 |  3.0 |  3.0 |   3 |             0
 paiements |     8 |   8 |  8.0 |  8.0 |  8.0 |   8 |             0
 produits  |     6 |   6 |  6.0 |  6.0 |  6.0 |   6 |             0
 shops     |     1 |   1 |  1.0 |  1.0 |  1.0 |   1 |             0


#### Q2.g — Owners par tranche de documents (ordre des vagues)
 tranche_documents | owners | documents 
-------------------+--------+-----------
 10-99             |      1 |        19


#### Q2.h — Taille de la base
 taille_base 
-------------
 9511 kB

```

### Q3 — Identités sans téléphone et qualité des téléphones

Source : `bref/q3-identites-sans-telephone.sql`

```text

#### Q3.a — Owners (live) sans téléphone, par provider et présence d email
 provider | a_un_email | email_verifie | owners 
----------+------------+---------------+--------


#### Q3.b — Owners (live) : synthèse téléphone / email / social
 owners_live | avec_telephone | sans_telephone | sans_tel_avec_email | sans_tel_sans_email | avec_social_id | email_verifie 
-------------+----------------+----------------+---------------------+---------------------+----------------+---------------
           1 |              1 |              0 |                   0 |                   0 |              0 |             1


#### Q3.c — Membres (admin/seller/viewer, live) sans téléphone, par rôle et identifiant disponible
  role  |  identifiant  | membres 
--------+---------------+---------
 admin  | username seul |       1
 seller | username seul |       2


#### Q3.d — Membres (live) : total, avec / sans téléphone, rattachés à une boutique ; superadmin compté à part (hors membres, hors Q4)
    role    | utilisateurs | avec_telephone | sans_telephone | sans_boutique |         traitement         
------------+--------------+----------------+----------------+---------------+----------------------------
 admin      |            1 |              0 |              1 |             0 | membre
 seller     |            2 |              0 |              2 |             0 | membre
 superadmin |            1 |              1 |              0 |             1 | exclu des membres et de Q4


#### Q3.e — Qualité des téléphones (tous rôles, live) : format après normalisation, préfixe pays connu
    role    | telephones | vide_apres_normalisation | format_e164_ok | format_invalide | prefixe_pays_connu | sans_prefixe_connu | modifies_par_normalisation 
------------+------------+--------------------------+----------------+-----------------+--------------------+--------------------+----------------------------
 owner      |          1 |                        0 |              1 |               0 |                  1 |                  0 |                          0
 superadmin |          1 |                        0 |              1 |               0 |                  1 |                  0 |                          0


#### Q3.f — Préfixes pays des téléphones (4 premiers caractères après normalisation, owners live)
 prefixe | owners 
---------+--------
 +237    |      1


#### Q3.g — Doublons internes après normalisation (téléphones portés par > 1 utilisateur live)
 telephones_en_doublon | utilisateurs_concernes 
-----------------------+------------------------
                     0 |                      0


#### Q3.h — Owners live avec suppression programmée (scheduledDeletionAt)
 owners_suppression_programmee 
-------------------------------
                             0

```

### Q5 — Boutiques hors CM, TVA, montants non entiers

Source : `bref/q5-pays-tva-montants.sql`

```text

#### Q5.a — Boutiques par pays (live / soft-deleted)
 country | etat | boutiques | owners 
---------+------+-----------+--------
 CM      | live |         1 |      1


#### Q5.b — Boutiques hors CM (live) et documents qu elles portent
 boutiques_hors_cm | owners_hors_cm | documents_hors_cm 
-------------------+----------------+-------------------
                 0 |              0 |                 0


#### Q5.c — Contrôle TVA par pays (documents live) : conforme / TVA ignorée / écart
 country | taux_attendu | documents | conforme | tva_ignoree | ecart 
---------+--------------+-----------+----------+-------------+-------
 CM      |       0.1925 |        19 |        2 |          17 |     0


#### Q5.d — Documents (live) dont la TVA effective ≠ 19,25 % (tax > 0), par taux effectif observé
 taux_effectif | documents 
---------------+-----------


#### Q5.e — Montants non entiers (value <> trunc(value)) par colonne — live + soft-deleted confondus
        colonne         | non_entiers | lignes 
------------------------+-------------+--------
 expense.amount         |           0 |      0
 invoice.deliveryFee    |           0 |     19
 invoice.discount       |           0 |     19
 invoice.initialPayment |           0 |     19
 invoice.subtotal       |           0 |     19
 invoice.tax            |           0 |     19
 invoice.total          |           0 |     19
 invoice_item.total     |           0 |     36
 invoice_item.unitPrice |           0 |     36
 invoice_payment.amount |           0 |      8
 product.unitPrice      |           0 |      6


#### Q5.f — Quantités décimales (invoice_item.quantity) — légitimes depuis develop (numeric(12,3)) — live + soft-deleted confondus
 lignes_quantite_decimale | documents_concernes | lignes 
--------------------------+---------------------+--------
                        2 |                   2 |     36


#### Q5.g — Documents (live) avec montants négatifs ou remise > sous-total (bornes)
 total_negatif | remise_sup_sous_total | composant_negatif 
---------------+-----------------------+-------------------
             0 |                     0 |                 0

```

### Q6 — Crédits de compte et packs SMS

Source : `bref/q6-credits-sms.sql`

```text

#### Q6.a — Crédits de compte (account_credit)
 owners_avec_ligne | owners_credit_positif | owners_credit_negatif | somme_credits_positifs | credit_max | p50_credit_positif 
-------------------+-----------------------+-----------------------+------------------------+------------+--------------------
                 0 |                     0 |                     0 |                      0 |            |                   


#### Q6.b — Crédits positifs : par tranche et par état du plan de l owner
 tranche_xaf | owners | somme 
-------------+--------+-------


#### Q6.c — Compteurs d usage (usage_counter) par métrique : période courante et cumul
      metric      | owners_periode_courante | used_periode_courante | owners_compteur_a_vie | used_a_vie | used_toutes_periodes_mensuelles | periodes_distinctes 
------------------+-------------------------+-----------------------+-----------------------+------------+---------------------------------+---------------------
 ai_credits       |                       0 |                     0 |                     0 |          0 |                               4 |                   1
 daily_ai_credits |                       0 |                     0 |                     0 |          0 |                               4 |                   1
 email            |                       0 |                     0 |                     0 |          0 |                               1 |                   1
 sms              |                       0 |                     0 |                     0 |          0 |                               4 |                   1


#### Q6.d — SMS gratuits (sms_free, à vie, limite 5) : owners par nombre utilisé
 sms_free_utilises | owners 
-------------------+--------


#### Q6.e — Packs SMS (sms_recharge_request) par statut : demandes, SMS, montants
 status | moyen_paiement | demandes | owners | sms_total | somme_due_xaf | avec_completed_at 
--------+----------------+----------+--------+-----------+---------------+-------------------
 TOTAL  | tous moyens    |        0 |      0 |           |               |                 0


#### Q6.f — Bilan SMS : achetés (complétés) vs consommés (PROXY usage_counter.sms)
 sms_achetes_completes | owners_ayant_achete | sms_consommes_proxy | sms_gratuits_consommes 
-----------------------+---------------------+---------------------+------------------------
                     0 |                   0 |                   4 |                      0


#### Q6.g — Owners live ayant un pack complété : par plan en vigueur (none = pas d abonnement en vigueur)
 plan_en_vigueur | owners | sms_achetes 
-----------------+--------+-------------

```

### Q7 — Totaux incohérents (avant / jour de la bascule / depuis develop)

Source : `bref/q7-totaux-incoherents.sql`

```text

> Date de bascule develop utilisée (develop_deploy_date) : 2026-10-26

#### Q7.a — total ≠ recalcul : par période (avant / jour de la bascule / depuis develop) × type
    periode     | type  | etat | documents | total_incoherent | somme_ecarts_abs 
----------------+-------+------+-----------+------------------+------------------
 avant develop  | quote | live |         1 |                0 |                0
 avant develop  | sale  | live |        13 |                0 |                0
 depuis develop | quote | live |         2 |                0 |                0
 depuis develop | sale  | live |         3 |                0 |                0


#### Q7.b — total ≠ recalcul (live) : par statut × période
  status   |    periode     | documents | total_incoherent 
-----------+----------------+-----------+------------------
 pending   | avant develop  |         1 |                0
 pending   | depuis develop |         2 |                0
 paid      | avant develop  |        11 |                0
 paid      | depuis develop |         3 |                0
 cancelled | avant develop  |         2 |                0


#### Q7.c — Taille des écarts total ≠ recalcul (live) : tranches en XAF
 ecart_xaf | documents 
-----------+-----------


#### Q7.d — subtotal ≠ somme des lignes (live) : par période
    periode     | documents | sans_ligne | subtotal_incoherent 
----------------+-----------+------------+---------------------
 avant develop  |        14 |          0 |                   0
 depuis develop |         5 |          0 |                   0


#### Q7.e — Lignes dont total ≠ round(quantity × unitPrice) (tolérance 1 XAF) — live + soft-deleted confondus
 lignes | ligne_incoherente 
--------+-------------------
     36 |                 0


#### Q7.f — Cohérence paiements (live, type sale) par statut : encaissé (initialPayment + paiements isPaid) vs total
  status   | documents | encaisse_inferieur_total | encaisse_superieur_total 
-----------+-----------+--------------------------+--------------------------
 paid      |        14 |                        0 |                        0
 cancelled |         2 |                        0 |                        0

```

### Q8 — Owners plan ≠ free sans abonnement en vigueur

Source : `bref/q8-plan-sans-abonnement.sql`

```text

#### Q8.a — Matrice user.plan (colonne) × plan en vigueur (abonnement) — owners live
 user_plan_colonne | plan_en_vigueur | diagnostic | owners 
-------------------+-----------------+------------+--------
 business          | business        | cohérent   |      1


#### Q8.b — Q8 strict : owners live, plan ≠ free, aucun abonnement en vigueur — par plan et dernier état d abonnement
 user_plan_colonne | dernier_statut_abonnement | dernier_moyen_paiement | owners 
-------------------+---------------------------+------------------------+--------


#### Q8.c — Q8 strict : ancienneté de l expiration (endsAt du dernier abonnement) — grâce 30 j ?
 anciennete_expiration | owners 
-----------------------+--------


#### Q8.d — Abonnements « actifs » en base mais hors vigueur (status = active, prédicat faux) — par cause
 cause | abonnements | owners 
-------+-------------+--------

```

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
 87 MB       | 64 MB             | 2504 kB


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

## Partie 3 — Q4 : intersection des téléphones normalisés Bref ∩ Keevo

Méthode : normalisation `PhoneNumbers.normalize` des deux côtés en SQL, export `md5(sel de la run || numéro normalisé)` (une empreinte par ligne, distincte, triée ; sel aléatoire non conservé), intersection locale par `comm -12`. Bref : owners et membres live avec téléphone (superadmin exclu) ; Keevo : utilisateurs actifs OWNER et EMPLOYEE (SUPER_ADMIN exclu). Les numéros Bref sans indicatif pays ne peuvent pas coïncider (voir Q3.e). Les fichiers `*.hashes.csv` restent dans `out/` (ignorés par git) et doivent être supprimés après lecture du rapport.

| Ensemble | Téléphones distincts (normalisés) |
|---|---|
| Bref owners (live, avec téléphone) | 1 |
| Bref membres (live, avec téléphone) | 0 |
| Bref owners ∪ membres | 1 |
| Keevo OWNER actifs | 24 |
| Keevo EMPLOYEE actifs | 14 |
| Keevo OWNER ∪ EMPLOYEE | 38 |
| **Bref owners ∩ Keevo (tous)** | **1** |
| **Bref membres ∩ Keevo (tous)** | **0** |
| Bref (owners ∪ membres) ∩ Keevo (tous) | 1 |

Matrice des collisions par rôle (nombre de téléphones distincts) :

| Bref \\ Keevo | OWNER actif | EMPLOYEE actif |
|---|---|---|
| owner | 1 | 0 |
| membre | 0 | 0 |

Fichiers d'empreintes à supprimer : `out/2026-11-03-fusion/*.hashes.csv` (6 fichiers).

## Partie 4 — Q13 / Q9 : faits lus dans les dépôts (non mesurés en base)

Ces éléments sont lus dans les clones locaux au moment de la run (aucun `git fetch` n'est fait : l'état peut être en retard sur GitHub). Ils ne valent pas confirmation de ce qui tourne réellement en production : voir la checklist.

### Q13 — api-bref : quand `develop` a atteint `main` (seule branche construite et déployée)

| Fait (dépôt local api-bref, refs `main` / `develop`) | Valeur |
|---|---|
| Point de divergence `merge-base main develop` (dernier commit commun = dernier passage de develop en prod) | daeb28c 2026-10-26 |
| Dernier commit de fusion sur `main` | a3faeb2 2026-10-26 |
| Tête de `main` | a3faeb2 2026-10-26 |
| Tête de `develop` | 376ad70 2026-10-31 |
| Commits de `develop` absents de `main` | 7 |
| Workflow GHCR : branches construites | main  |
| Image déployée (docker-compose.prod.yml via `latest`) | ghcr.io/dsrdesign/api-bref |

Lecture : la date du point de divergence est la meilleure estimation de la mise en production de `develop` (`DEVELOP_DEPLOY_DATE` par défaut = 2026-10-26). Les 7 commits de `develop` postérieurs ne sont **pas** déployés tant qu'ils ne sont pas sur `main` — dont les règles de statut calculées côté serveur (b957867).

### Q9 — Identités d'application déclarées dans les dépôts

| Fait (dépôts) | Valeur |
|---|---|
| Keevo `namespace` (build.gradle.kts) | com.example.keevo |
| Keevo `applicationId` (build.gradle.kts) | com.example.keevo |
| Keevo signature release (workflow) | keystore `keevo-release.keystore` décodé depuis les secrets GitHub (ANDROID_KEYSTORE_BASE64), un seul flavor |
| Bref (app.json) | package `com.brefpoint.app`, bundle `com.brefpoint.app`, équipe Apple `N9534K29PS`, projet EAS `bb0b6dbe-c342-43e4-95cc-2902d7bd4d45` (lu dans `app.json`) |

### À confirmer par l'opérateur (hors base, non mesuré ici)

- [ ] **Q13 — GHCR / VPS** : tag `ghcr.io/dsrdesign/api-bref:sha-*` effectivement en service sur le VPS (`docker inspect` du conteneur api-bref, `/opt/api-bref`) et date de ce déploiement ; confirmer que ce `sha` correspond bien au point de divergence ci-dessus. Si la date diffère, relancer ce kit avec `DEVELOP_DEPLOY_DATE=<date réelle>`.
- [ ] **Q9 — Play Console** : nom de package réellement publié (fiche Keevo), état de **Play App Signing** (clé d'upload vs clé de signature gérée par Google), et disponibilité du keystore d'upload pour EAS.
- [ ] **Q9 — Comptes développeur** : compte Google Play développeur disponible (propriétaire, accès), compte Apple Developer (équipe `N9534K29PS` côté Bref) et décision iOS pour Keevo.
- [ ] **Q1 (production)** : la définition « actif < 90 j » retenue en 18.2 parmi les proxies rapportés.
- [ ] **Hygiène** : supprimer `out/<date>/*.hashes.csv` une fois le rapport lu ; ne jamais commiter `out/`.

