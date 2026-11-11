---
title: "PRD : fusion Keevo × Bref Point"
status: draft
created: 2026-11-03
updated: 2026-11-03
author: Dalois-30 (avec Claude)
story: 18.5
workflowType: prd
complements: _bmad-output/planning-artifacts/prd.md   # PRD Keevo (FR1–FR98, NFR1–NFR35) : non dupliqué, étendu ici
inputDocuments:
  - _bmad-output/planning-artifacts/briefs/brief-Keevo-2026-11-03/brief.md           # brief validé (vision, utilisateurs, succès, périmètre)
  - _bmad-output/planning-artifacts/epics-fusion-keevo-bref.md                       # source des exigences FUS-*, epics 18–25, jalons G0–G5 + GL, Annexe C (ADR-01..09)
  - _bmad-output/planning-artifacts/fusion-keevo-bref-analyse-2026-10-31.md          # contexte (§0, §3.7, §5.2, §6, §7, §9, §11)
  - _bmad-output/planning-artifacts/prd.md
  - _bmad-output/planning-artifacts/research/bref-evolutions/journal-evolutions-bref.md
precedence: "Annexe C des epics (ADR-01..09) et décisions utilisateur du 03/11/2026 > epics > analyse du 31/10"
---

# PRD : fusion Keevo × Bref Point

## 0. Comment lire ce document

- Ce PRD **complète** le PRD Keevo (`prd.md`). Il ne réécrit pas les exigences Keevo : il les cite (`Keevo FRnn`, `Keevo NFRnn`) là où la fusion les étend ou les remplace.
- Les exigences de la fusion portent les **identifiants de l'inventaire des epics** (`epics-fusion-keevo-bref.md`) : `FUS-FR1`–`FUS-FR40`, `FUS-MIG1`–`FUS-MIG10`, `FUS-NFR1`–`FUS-NFR10`, `FUS-UX1`–`FUS-UX8`. Même identifiant, même sens. Le PRD les regroupe par domaine et ajoute du contexte ; en cas de doute, la formulation des epics fait foi.
- Une seule exigence est **ajoutée** par ce PRD : `FUS-NFR11` (patrons de conception et complexité algorithmique, règle projet du 03/11/2026). Elle est à reporter dans l'inventaire des epics (§12).
- Ordre de priorité des sources : **Annexe C des epics (ADR-01 à ADR-09) et décisions utilisateur du 03/11/2026** > epics > analyse du 31/10. Les écarts relevés entre sources et leur résolution sont au §12.
- La traçabilité exigence → epic/story est au §13.

## 1. Résumé

Keevo (caisse et stock multi-boutiques, hors ligne 7 jours) et Bref Point (devis, factures, impayés, relances WhatsApp, assistant IA) servent les mêmes commerçants d'Afrique centrale et de l'Ouest. Chacun a des utilisateurs réels, une app (Flutter / React Native) et un backend (Spring / NestJS). La fusion produit :

- **un seul backend : celui de Keevo** (Spring Boot, Java 21, Spring Modulith), qui absorbe **toute** l'API Bref, partie super-admin comprise, sous forme de modules (D1, FUS-FR38) ;
- **une seule app : Bref Point** (`com.brefpoint.app`, Android et iOS), en React Native sur le shell Bref, avec la couche hors ligne de Keevo (D2, ADR-02, FUS-FR33) ;
- **une migration invisible** : les utilisateurs Keevo n'ont aucune donnée à migrer (même backend) ; les owners Bref sont migrés un par un, par vagues, vérifiés au franc près, avec retour arrière jusqu'à l'activation (D6).

**Règle d'or** : personne ne perd de données, ne change de mot de passe ni ne paie deux fois. Toute story qui touche une donnée de production le démontre dans ses critères d'acceptation.

**Pourquoi maintenant** : la refonte modulaire du backend Keevo (epics 10–17) est terminée sur la branche, pas encore en production. C'est l'architecture qui permet d'accueillir Bref en modules. Décision du 03/11/2026 : **la refonte ne part en production qu'avec la fusion complète, au jalon GL** (§9).

## 2. Contexte et décisions structurantes

### 2.1 Point de départ

| | Keevo | Bref Point |
|---|---|---|
| Métier | caisse (POS), stock, transferts, inventaire, multi-boutiques, rapports WhatsApp du soir, facturation légale `FAC-` | devis, factures en 5 étapes, acomptes et échéanciers, impayés, dépenses, résultat, assistant IA, WhatsApp Business, portail client |
| App | Flutter, **APK hors store** (`com.example.keevo`, GitHub Releases) | Expo / React Native, **Play Store et App Store** (`com.brefpoint.app`) |
| Backend | Spring Boot modulaire, schéma par tenant, sync hors ligne (file d'opérations, push/pull keyset, quarantaine, gate 7 j), audit immuable, 4 235 tests | NestJS, schéma partagé, 4 tests, flux critiques non transactionnels |
| Identité | téléphone + mot de passe | OTP SMS/WhatsApp/email, Google, Apple, username pour les membres |
| Offre | trial 6 mois + Premium activé à la main par le super-admin | 4 plans (free/pro/team/business), quotas, paiement en libre-service (MyCoolPay, StoreKit/RevenueCat) |
| Pays | Cameroun, XAF (Keevo FR86) | CM, GA, TD, CD, SN |

Bref **n'est pas gelé** (décision du 03/11/2026) : il reste en production et reçoit des correctifs. Ses évolutions sont suivies dans `research/bref-evolutions/journal-evolutions-bref.md`, revu **au début de chaque session de travail sur la fusion** et avant chaque story des epics 20, 21, 23, 24 et chaque vague (story 18.3). Au 03/11, 5 commits `develop` sont journalisés (détail client recopié sur la facture → 20.5 ; suggestions et création d'article à l'étape Articles → 22.17 / 23.2 ; menu « modules » → rappel FUS-UX7).

### 2.2 Décisions verrouillées (D1–D8) et ADR du gate P0 (03/11/2026)

| Réf. | Décision | Effet sur ce PRD |
|---|---|---|
| D1 | Backend unique = Keevo ; api-bref absorbé en modules puis arrêté | FUS-FR38, epic 25 |
| D2 | App unique Expo/RN, shell Bref + protocole de sync Keevo ; app Flutter retirée | FUS-FR33–FR36, FUS-UX1 |
| D3 | Un owner Bref = un tenant Keevo ; shops → stores ; membres → employees + memberships | FUS-MIG*, 24.2 |
| D4 | Identité étendue ; réconciliation téléphone E.164 puis email ; hash bcrypt Bref réutilisés | FUS-FR1–FR6 |
| D5 | Plans Bref → profils/bundles ; quotas ; module `payments` en libre-service | FUS-FR7–FR11, FR40 |
| D6 | Strangler à double piste : Keevo sans migration de données ; Bref owner par owner | FUS-MIG1–MIG10 |
| D7 (révisée, ADR-02) | **Une seule identité store** `com.brefpoint.app` ; Keevo (APK hors store) rejoint cette fiche | FUS-FR33, 22.14, 25.4 |
| D8 | IA par **outils** (couche unique sur les use cases, exposée aussi en MCP), jamais de SQL généré | FUS-FR27, FR39 |
| — | Deux parcours de vente : Caisse (`pos` → ventes + stock) et Document (`invoicing`, sans stock) ; reporting = union sans double comptage | FUS-FR17, FR24 |
| — | Factures émises non modifiables (conformité DGI) ; devis éditables ; correction = avoir + nouvelle facture | FUS-FR18, 20.3 |
| ADR-01 | Multi-pays dès la fusion : CM, GA, TD, CD, SN ; TVA par pays, indicatifs, journées au fuseau du pays ; devise et arrondi identiques à Bref | 20.5, 19.2 ; voir §12 (FUS-NFR8/NFR9) |
| ADR-02 | Marque finale Bref Point, une seule identité store | FUS-FR33 |
| ADR-03 | FREE = 1 employé (valeur de départ) ; droit acquis pour les tenants Keevo FREE au-delà, sans nouvel ajout | FUS-FR7 |
| ADR-04 | Essai d'1 mois du plan le plus complet pour tout nouvel inscrit ; essais en cours honorés | FUS-FR7 |
| ADR-05 | **Rien de l'offre n'est figé dans le code** ; grille Bref et valeurs ADR-03/04/06 = données de départ | FUS-FR40 |
| ADR-06 | Keevo PREMIUM → TEAM, prix inchangé jusqu'à la fin de la période payée | FUS-FR7 |
| ADR-07 | Owner Bref expiré → FREE à la migration (`SUBSCRIPTION_IN_FORCE`, sans grâce) | FUS-MIG8 |
| ADR-08 | Clients **par établissement** ; vue « tous mes établissements » pour l'owner ; partage optionnel ; pas de fusion automatique | FUS-FR15 |
| ADR-09 | Publication précoce de l'app unifiée sous `com.brefpoint.app` avec mode legacy | FUS-FR37 |
| §7.3 | Suivi de stock réglé **par produit** (`track_stock`), jamais déduit du secteur ; livré par 17.12 | FUS-FR13 |

### 2.3 Répartition des livraisons (décision du 01/11/2026)

| Livrable | Dépôt / équipe | Conséquence |
|---|---|---|
| Backend (API, modules, migration, MCP) | **ce dépôt** (`AI/Keevo`) | seul livrable de ce dépôt |
| App React Native unifiée | `AI/bref`, second développeur | chaque spec backend contient une section « Mobile » ; clés `m<story>` séparées dans `sprint-status.yaml` |
| Dashboard super-admin | autre dépôt | chaque spec qui expose une API super-admin contient une section « Dashboard super-admin (autre dépôt) » |

Les exigences d'app (FUS-FR33–FR37, FUS-UX*, FUS-NFR5) restent dans ce PRD car le backend doit les servir (contrats, profil d'interface, versions, mode legacy) ; leur réalisation écran appartient au dépôt de l'app.

## 3. Objectifs et critères de succès

### 3.1 Objectifs

1. **Une app, un backend, une offre** : plus de double développement ni de double exploitation.
2. **Parité sans perte** : chaque fonction utilisée aujourd'hui dans Keevo ou dans Bref existe dans l'app unifiée, avec le même comportement.
3. **Repères conservés** : chaque utilisateur existant retrouve son accueil, ses onglets et ses mots (FUS-UX6), et ne voit rien de nouveau tant que l'owner ne l'a pas activé (FUS-UX7).
4. **Monétisation en libre-service** pilotée sans code depuis le dashboard (FUS-FR9, FR40).
5. **Arrêt d'api-bref** et de l'app Flutter, sans owner laissé de côté.

### 3.2 Critères de succès (brief, repris tels quels)

| Critère | Mesure | Où il est vérifié |
|---|---|---|
| Zéro perte | 0 opération hors ligne perdue au passage à la nouvelle app ; 100 % des owners Bref migrés réconciliés au franc près avant activation | FUS-MIG3, MIG6, MIG7 ; 22.14, 24.8, 24.11 |
| Parité | inventaire de parité Keevo et Bref entièrement coché sur staging avant GL ; tests utilisateurs concluants (3 à 5 commerçants de chaque côté) | 22.17, 22.18, jalon GL |
| Adoption `[ASSUMPTION]` | 90 % des appareils Keevo actifs sur Bref Point 4 semaines après la bêta ; < 2 % d'appareils restés sur l'APK Keevo avant son retrait | 22.15, 22.16, 25.4 |
| Stabilité | sessions sans crash ≥ 99,5 % pendant la bêta ; < 0,5 % des opérations mises en quarantaine | jalon G2, 22.15 |
| Rétention `[ASSUMPTION]` | owners actifs 30 j après chaque vague ≥ owners actifs avant la vague | rapports de vague (FUS-MIG10) |
| Fin de l'exploitation parallèle | api-bref sans owner actif pendant 4 semaines → arrêté | jalon G5, 25.1 |

Mesures techniques complémentaires : import d'un owner moyen < 60 s, gel → activation < 15 min (FUS-NFR3, NFR7) ; échec de réconciliation < 1 % par vague de masse (24.15) ; écart de KPI nul entre Keevo et Bref sur 10 owners importés (20.9).

## 4. Utilisateurs

| Profil | Aujourd'hui | Attente | Préréglage |
|---|---|---|---|
| **Commerçant Keevo (owner)** | APK Keevo, caisse, stock, plusieurs boutiques et employés, rapports WhatsApp | retrouver caisse et stock à l'identique ; une app sur le store ; payer seul | « Caisse » |
| **Prestataire / commerçant Bref (owner)** | Bref Point, factures en 5 étapes, relances, assistant | ne pas sentir la migration ; garder factures, clients, plan, crédits | « Document » |
| **Employé / membre** (vendeur, caissier, gérant, lecteur) | accès limité à sa boutique ; chez Bref, connexion par username | même périmètre, mêmes identifiants, rien à réapprendre | celui du tenant |
| **Client final** | portail Bref « retrouver mes factures », liens publics | liens qui continuent de marcher | — |
| **Super-admin** | deux dashboards | une console : comptes, offres, versions, campagnes, migrations, santé | — |
| **Nouvel inscrit** | — | choisir son activité et son parcours ; essai d'1 mois | selon son choix |

## 5. Parcours utilisateurs

**P1 — Simon, owner Keevo, passe à Bref Point.** La dernière release de l'APK Keevo force une synchronisation et ne propose « Passez à Bref Point » que si sa file est vide et sans quarantaine ; sinon elle liste les opérations à traiter et propose l'export CSV local (FUS-MIG7). Simon installe Bref Point depuis le Play Store (l'APK ne peut pas se mettre à jour vers la fiche Play), se connecte avec son téléphone et son mot de passe, choisit son entreprise ; un full pull charge ses données. Un écran court, une seule fois : « Keevo devient Bref Point : vos données, vos boutiques et vos ventes sont là » (FUS-UX8). Il arrive sur la caisse, avec ses onglets dans le même ordre (FUS-UX2, UX6). Dépenses, devis, assistant n'apparaissent nulle part : ils sont compris dans son plan mais désactivés (FUS-UX7).

**P2 — Awa, owner Bref, est migrée dans une vague.** J-3 : message WhatsApp/SMS/email. L'ancienne app (pré-migrateur OTA) affiche la bannière, synchronise, refuse le gel tant que des mutations sont en attente (FUS-MIG6, UX4). J0 : gel (écritures refusées 423 `ACCOUNT_MIGRATING`, lectures possibles, FUS-MIG4), export, import idempotent, réconciliation bloquante (FUS-MIG3), activation, message de confirmation avec le lien store. Awa met à jour Bref Point (même fiche), se connecte par OTP comme avant, voit « Votre application a été mise à jour, vos factures sont là » (FUS-UX8) et retrouve la création de facture en 5 étapes (FUS-UX2). Son abonnement Apple continue (FUS-MIG8), ses anciens liens `BRF-…` fonctionnent (FUS-FR23, MIG9). Caisse et stock restent désactivés jusqu'à ce qu'elle les active dans Paramètres › Mes modules.

**P3 — Awa pas encore migrée reçoit la mise à jour.** L'app unifiée ne la connaît pas côté Keevo : elle interroge api-bref, affiche « Migration prévue le … » ou un mode legacy en lecture seule (liste et PDF de ses factures), sans écriture (FUS-FR37, ADR-09).

**P4 — Koffi, vendeur ex-Bref.** Il se connecte avec le username et le mot de passe donnés par son patron, sans changement (FUS-FR1, D4). Rôle EMPLOYEE confiné à sa boutique ; un lecteur (VIEWER) n'écrit jamais (FUS-FR4).

**P5 — Commerçant présent des deux côtés.** Son téléphone existe dans Keevo et dans Bref : un seul compte (hash Keevo conservé), deux entreprises dans le choix d'entreprise (FUS-FR3, 24.7). Les collisions non résolubles bloquent l'import avant écriture et partent en vague dédiée.

**P6 — Owner active un module.** Dans Paramètres › Mes modules, Simon active « Dépenses » (compris dans son plan) : le module apparaît sur tous les appareils du tenant ; le désactiver ne supprime rien. Un module hors plan relève de « Mon plan » (FUS-UX7, UX5).

**P7 — Owner paie seul.** « Mon plan » affiche plan, échéance, quotas, crédit, prix en vigueur et remise ; paiement Mobile Money (Android) ou achat intégré (iOS) ; à la confirmation, les modules s'activent sans redémarrage (FUS-FR9, UX5).

**P8 — Super-admin pilote l'offre et les vagues.** Il crée une promotion datée sur PRO annuel sans redéploiement (FUS-FR40) ; il planifie une vague, suit chaque owner (planifié → gelé → exporté → importé → réconcilié → activé), annule un owner non activé, archive le rapport (FUS-MIG5, MIG10).

**P9 — Client final.** Il retrouve par OTP toutes ses factures, tous commerçants confondus, et ouvre chaque lien public (FUS-FR29).

## 6. Exigences fonctionnelles

Format : identifiant, exigence (même sens que les epics), puis contexte / lien avec le PRD Keevo quand utile.

### 6.1 Identité et accès

- **FUS-FR1** — Connexion par téléphone + mot de passe (Keevo), par code OTP reçu par SMS, WhatsApp ou email, par Google, par Apple, ou par username + mot de passe (membres Bref).
  *Étend Keevo FR1/FR3. OTP : code à 6 chiffres haché, 5 min, 5 essais, anti-énumération, préfixes pays limités (ADR-01, `PHONE_COUNTRY_NOT_SUPPORTED`). Social : jeton vérifié côté serveur, audiences des deux bundles acceptées.*
- **FUS-FR2** — Un compte possède au moins un identifiant parmi téléphone E.164, email, username ; le téléphone n'est plus obligatoire.
  *Changement additif de `public.users` (colonnes nullables, CHECK « au moins un identifiant ») ; aucune ligne existante modifiée.*
- **FUS-FR3** — Une même personne peut être owner d'un tenant et membre d'un autre ; après login elle choisit son entreprise (tenant-picker existant).
  *Lève `USER_ALREADY_EXISTS` à la création d'employé : une membership est ajoutée au compte existant, mot de passe inchangé.*
- **FUS-FR4** — Rôles de membership OWNER, MANAGER, EMPLOYEE, VIEWER ; chaque module déclare les permissions de chaque rôle ; VIEWER n'écrit jamais.
  *Étend Keevo FR66. MANAGER = EMPLOYEE + équipe de sa boutique + lecture CRM/produits. Correspondance Bref : admin→MANAGER, seller→EMPLOYEE, viewer→VIEWER.*
- **FUS-FR5** — Un owner crée un membre avec téléphone ou username ; le mot de passe initial n'est jamais renvoyé en clair une seconde fois.
- **FUS-FR6** — Un compte social se lie automatiquement à un compte existant par email seulement si l'email est vérifié et si le compte n'est pas un membre (règle Bref `develop`).

### 6.2 Offre commerciale, quotas, paiement

- **FUS-FR7** — Les plans sont des **données** (catalogue en base, plus d'énumération de plans dans le code) ; FREE, PRO, TEAM, BUSINESS sont créés au départ comme profils configurables (bundles de modules + plafonds) ; PREMIUM Keevo est servi par TEAM (ADR-06) ; FREE = 1 employé, droit acquis pour les tenants Keevo existants (ADR-03) ; essai d'1 mois (ADR-04).
  *Remplace, pour les nouveaux inscrits, le trial 6 mois de Keevo FR15/FR15b et les limites figées de Keevo FR16 ; les essais en cours vont à leur terme. Les apps V1 2.0.1 reçoivent toujours FREE / PREMIUM_TRIAL / PREMIUM via une correspondance configurable.*
- **FUS-FR40** — **Rien de l'offre commerciale n'est figé dans le code** (ADR-05). Depuis le dashboard super-admin, on crée, modifie, ordonne, masque et archive : les plans (code, libellé, description, visibilité, plan gratuit par défaut), leurs modules, plafonds (boutiques, produits, employés…) et quotas mensuels (documents, SMS, emails, crédits IA…), les cycles de facturation et leurs prix, la politique d'essai (plan, durée, bénéficiaires), les remises et promotions (pourcentage ou montant, période, plans et cycles visés, tenant ciblé, code promo). Tout changement s'applique sans redéploiement de l'API ni de l'app ; les valeurs des ADR-03, 04, 06 et la grille Bref ne sont que des données de départ.
  *Données de départ : PRO 3 500 / 9 500 / 32 760 XAF ; TEAM 5 500 / 15 000 / 51 480 ; BUSINESS 8 500 / 23 000 / 79 560 (mensuel / trimestriel / annuel). Archiver un plan ne touche pas les tenants qui l'ont. Un paiement initié garde le prix affiché. Les prix StoreKit restent réglés dans App Store Connect (écart affiché au super-admin). Les règles d'activation initiale des modules d'un nouveau tenant sont aussi des données (19.13).*
- **FUS-FR8** — Les quotas mensuels (documents, SMS, emails, requêtes IA) sont décomptés de façon atomique par tenant et période ; un dépassement renvoie une erreur explicite sans bloquer le cœur.
  *Cœur = vente caisse, paiement : jamais bloqués. Erreur `QUOTA_EXCEEDED {metric, limit, resetAt}`. Opération de sync rejouée = pas de second décompte. Décompte après succès d'envoi seulement.*
- **FUS-FR9** — Un owner souscrit ou renouvelle un plan en libre-service par Mobile Money (MyCoolPay) sur Android et par achat intégré (StoreKit via RevenueCat) sur iOS ; le paiement confirmé active le bundle sans intervention du super-admin.
  *Remplace le « Demander l'activation » manuel de Keevo (FR78 reste disponible via FUS-FR11). Webhooks idempotents, une seule demande en cours par tenant, sandbox filtrée en prod, grâce Apple respectée.*
- **FUS-FR10** — Le crédit de compte et les packs SMS achetés sont conservés, affichés et consommables.
  *Crédit append-only, jamais négatif ; pack consommé après épuisement du quota mensuel.*
- **FUS-FR11** — Le super-admin peut toujours accorder un plan manuellement (`admin_grant`), avec trace dans `tenant_plan_events`.

### 6.3 Boutiques, catalogue, clients

- **FUS-FR12** — Une boutique porte catégorie, description, logo, NIU, RCCM, pays.
  *Catégorie : 19 valeurs Bref ; pays défaut CM ; logo via le port de stockage de fichiers.*
- **FUS-FR13** — Un produit peut ne pas suivre de stock (`track_stock=false`) : pas de contrôle, pas de mouvement, pas d'alerte, hors inventaire ; l'owner peut activer le suivi plus tard, avec un stock initial facultatif (0 par défaut). Réglage **par produit**, jamais déduit du secteur ou du type de boutique. **Livré par la story 17.12** ; la fusion n'ajoute que le défaut `false` à l'import Bref et à la dérivation depuis les documents.
- **FUS-FR14** — Un produit porte une clé de déduplication (`product_key`), un code-barres, un prix manuel ; deux produits peuvent être fusionnés (le fusionné est archivé et redirige).
  *Clé `bc:<code-barres>` ou `nm:<nom normalisé>`. L'unicité Keevo par tenant est gardée : les homonymes de boutiques Bref sont fusionnés à l'import.*
- **FUS-FR15** — Un client porte type (particulier/entreprise), NIU, RCCM, adresse, contact, site web, date de naissance, consentement marketing (date d'accord, de retrait, auteur), boutique d'origine optionnelle ; le service avertit en cas de doublon de téléphone.
  *Étend Keevo FR87. Portée précisée par ADR-08 : liste filtrée sur l'établissement courant, employé limité au sien, owner « tous mes établissements », partage optionnel par établissement, avertissement `DUPLICATE_PHONE` dans le même établissement seulement, pas de fusion automatique. 20.2 rend la boutique d'origine obligatoire pour les **nouvelles** fiches ; elle reste optionnelle pour les fiches existantes (pas de contradiction : l'exigence vise le modèle).*
- **FUS-FR16** — Créer un document peut créer ou mettre à jour le client et les produits correspondants (dérivation optionnelle, désactivable par tenant).
  *Dans la même transaction que la facture (contrairement à Bref) ; client cherché par téléphone dans l'établissement courant (ADR-08) ; produit créé `track_stock=false` ; aucune dérivation si `crm` ou `catalog` est inactif.*

### 6.4 Ventes et documents

- **FUS-FR17** — Deux parcours de vente : Caisse (vente + stock + facture automatique) et Document (facture ou devis sans stock) ; le tenant choisit son parcours par défaut.
  *`default_sale_flow` : POS pour les tenants existants, DOCUMENT pour les importés de Bref ; il alimente le préréglage de navigation (FUS-UX6).*
- **FUS-FR18** — Un devis est éditable, convertible en facture (date d'émission = date de conversion) et annulable.
  *Pas de numéro `FAC-` pour un devis ; conversion idempotente ; une facture émise reste non modifiable (DGI).*
- **FUS-FR19** — Une facture accepte remise, frais de livraison (hors assiette TVA), taux de TVA propre ou exonération, date d'échéance, NIU/RCCM/email du client, lignes liées ou non à un produit, quantités à 3 décimales.
  *Remise avant TVA, arrondi HALF_UP par ligne puis total ; résultat identique à Bref sur 50 factures de référence. Taux par défaut du pays (ADR-01). Depuis `6faa8b4` (journal 18.3), contact et site web du client sont aussi recopiés sur le document.*
- **FUS-FR20** — Une facture accepte un acompte, des paiements partiels et un échéancier ; son état d'encaissement (impayée, partielle, payée, en retard) est calculé par le serveur ; un job quotidien passe en retard les échéances dépassées.
  *Somme payée ≤ total (`OVERPAYMENT`) ; une facture Keevo existante (un seul `paid_at`) se lit comme un paiement unique, sans migration destructive.*
- **FUS-FR21** — Une facture se rend selon 8 modèles (dont ticket 58/80 mm) avec signatures client/vendeur facultatives ; le rendu fonctionne hors ligne dans l'app et côté serveur pour le lien public.
  *Étend Keevo FR95 aux documents commerciaux.*
- **FUS-FR22** — Une facture s'envoie par WhatsApp, SMS ou email (quotas FUS-FR8) avec un lien public dont la durée est paramétrable ; les liens Bref historiques restent valides.
  *Défaut 30 j (Keevo), 90 j pour les tenants importés ; lien régénérable ; envoi hors ligne idempotent.*
- **FUS-FR23** — Les factures historiques Bref conservent leur référence (`BRF-…`) ; les nouvelles suivent la numérotation sans trou `FAC-`.
- **FUS-FR24** — Le chiffre d'affaires, les rapports, la rentabilité et le classement des vendeurs comptent les ventes caisse et les factures sans vente source, sans double comptage.
  *Étend Keevo FR44, FR50–FR54. CA = ventes COMPLETED ∪ factures émises/payées sans `source_sale_id` (hors devis, hors annulées, avoirs déduits), lu d'une seule source. Factures sans produit lié : comptées au CA, hors marge par produit.*

### 6.5 Nouveaux modules métier et d'engagement

- **FUS-FR25** — Module `expenses` : dépenses par boutique et catégorie (10 catégories Bref), hors ligne.
- **FUS-FR26** — Module `finance` : résultat (CA HT − dépenses), marge, variations, séries par période.
  *Mêmes valeurs que `finance-math.ts` de Bref sur son jeu de cas.*
- **FUS-FR27** — Module `ai-assistant` : questions guidées gratuites, chat libre décompté par quota, fournisseur configurable. **Le chat libre répond par appels d'outils (FUS-FR39), jamais par génération de SQL** ; parité fonctionnelle avec « Bref Point » dès la vague de migration.
  *Mêmes opérations et formes de réponse que l'API Bref ; 20 messages par conversation ; une question à la fois par compte ; quota décompté une fois par question, pas en cas d'échec ; corpus de parité ≥ 30 questions = critère d'acceptation de vague.*
- **FUS-FR39** — Une **couche d'outils IA** unique, construite sur les use cases Keevo, sert l'assistant intégré et un **serveur MCP** pour les clients IA externes. Chaque outil applique les mêmes contrôles que le REST (tenant, module, rôle, périmètre employé, quota) ; acteur et tenant viennent toujours de la session, jamais du modèle ; lecture d'abord, écriture seulement avec confirmation explicite ; chaque appel est audité.
  *Ordre : 21.8 (outils) → 21.4 (assistant) → 21.9 (MCP externe, jeton personnel révocable, module payant `mcp-access`). Test de couverture équivalent à `EndpointEntitlementCoverageTest`.*
- **FUS-FR28** — Module `whatsapp-cloud` : compte WhatsApp Business par tenant, boîte de réception, modèles de messages, campagnes clients respectant le consentement marketing.
- **FUS-FR29** — Module `client-portal` : un client final retrouve, par OTP sur son téléphone, toutes ses factures quel que soit le commerçant.
  *Compte `portal_clients` distinct des comptes commerçants ; un commerçant peut désactiver le portail pour son tenant.*
- **FUS-FR30** — Notifications in-app persistantes (liste, lu/non lu) en plus des push FCM.
- **FUS-FR31** — Le super-admin gère les versions minimales d'app par plateforme et identité ; l'app force la mise à jour en dessous.
  *Sert à retirer l'APK Keevo et l'ancienne app Bref ; « identité » distingue ces apps, pas deux fiches store (ADR-02).*
- **FUS-FR32** — Le super-admin envoie des campagnes multi-canal (push, WhatsApp, SMS, email) à des segments de tenants.
  *Étend Keevo FR82 (push global) aux segments (plan, pays, activité) et aux autres canaux.*

### 6.6 Plateforme et super-admin

- **FUS-FR38** — L'API Keevo absorbe **toute** l'API Bref, y compris sa partie super-admin : vue plateforme (overview, growth, top-shops, activity, owner-summary), listes inter-comptes (clients, produits, dépenses), gestion des utilisateurs (liste, stats, appareils, statut, plan, suppression), versions d'app, offres, campagnes plateforme. Après la convergence, aucun client (app ou dashboard) n'appelle plus api-bref.
  *Étend Keevo FR77–FR83. Indicateurs plateforme sans double comptage entre tenants Keevo et owners migrés. Toute route admin Bref a un équivalent Keevo ou une décision d'abandon écrite par l'utilisateur.*

### 6.7 Application mobile unifiée (servie par le backend, construite dans `AI/bref`)

- **FUS-FR33** — Une seule base de code React Native produit une seule application, publiée sous l'identité Bref Point (`com.brefpoint.app`) ; les utilisateurs Keevo (APK hors store) y passent avec leurs identifiants (ADR-02).
- **FUS-FR34** — L'app fonctionne hors ligne pour toutes les écritures métier (ventes, documents, paiements, stock, catalogue, clients, dépenses) via la file d'opérations du protocole Keevo.
  *Reprend Keevo FR69–FR76 et FR73a (quarantaine des opérations d'argent ou de stock) : écriture locale et opération dans la même transaction, push par lots idempotent, pull keyset, gate 7 jours.*
- **FUS-FR35** — L'app n'affiche que les modules actifs du tenant et les permissions du rôle ; une route d'un module inactif n'est jamais atteignable.
  *« Actif » = compris dans le plan **et** activé par l'owner (FUS-UX7, 19.13). Snapshot `GET /tenants/me/modules`, aucune liste de modules dans le jeton.*
- **FUS-FR36** — Verrouillage par PIN et biométrie, 7 langues (fr et en exposées d'abord), thème clair/sombre.
  *Étend Keevo NFR26 (fr, i18n prête).*
- **FUS-FR37** — L'app ex-Bref publiée sur `com.brefpoint.app` sait dire à un owner non encore migré quand il le sera et lui laisse consulter ses factures (mode legacy, ADR-09).

## 7. Exigences de migration

> **Révision du 11/11/2026 (ADR-10, `epics-fusion-keevo-bref.md` Annexe D) : bascule unique au lieu des vagues.** Le jour du go-live (avec l'app React Native) : arrêt de Bref, dump restauré à part, une commande importe tous les owners avec réconciliation et rapport, puis activation globale ; retour arrière = rallumer Bref jusqu'à la décision « go ». Les mentions de vagues, de gel par owner (FUS-MIG4), de pré-migrateur OTA (24.11), de routeur de webhooks et des stories 24.1, 24.14–24.16 ci-dessous sont remplacées par cette décision ; les exigences révisées font foi dans le document d'epics.

Principe (D6) : double piste. **Piste Keevo** : aucune donnée à migrer, seulement un changement d'app (APK → Bref Point). **Piste Bref** : un owner à la fois, gel → export → import idempotent → réconciliation bloquante → activation ; api-bref sert les non-migrés. Ordre des vagues : pilotes volontaires (3–5), free inactifs > 60 j, free actifs, payants Mobile Money, payants Apple (après validation sandbox), collisions d'identité, retardataires (gel global).

- **FUS-MIG1** — Un owner Bref est migré seul, de façon idempotente : rejouer son import est sans effet ou complète ce qui manque.
- **FUS-MIG2** — Tous les identifiants Bref (UUID) sont conservés ; chaque ligne importée est tracée dans `migration_records` (table, id source, checksum, note).
  *Garantit notamment la validité de l'`app_user_id` RevenueCat des owners migrés.*
- **FUS-MIG3** — L'activation d'un owner migré est bloquée tant que la réconciliation présente un écart hors tolérance.
  *Contrôles : compteurs par table après fusions expliquées, Σ ventes non annulées par boutique et par mois, Σ encaissé, Σ dépenses, impayés, 20 documents comparés champ à champ, abonnement et crédit, 5 liens publics. Valeurs Bref incohérentes conservées et signalées, jamais corrigées en silence (R3).*
- **FUS-MIG4** — Pendant la migration d'un owner, ses écritures (et celles de ses membres) sont refusées par api-bref avec 423 `ACCOUNT_MIGRATING` ; ses lectures restent possibles.
  *Ce gel par owner est la seule forme de gel : Bref lui-même n'est pas gelé (18.3).*
- **FUS-MIG5** — Le retour arrière d'un owner est possible jusqu'à son activation (suppression du tenant importé, dégel api-bref).
  *Les comptes Keevo préexistants liés (bridge d'identité) ne sont jamais supprimés.*
- **FUS-MIG6** — Aucune mutation locale Bref n'est perdue : le gel est refusé tant que l'appareil a des mutations en attente ; les lignes locales sans équivalent serveur sont montrées à l'owner.
- **FUS-MIG7** — Aucune opération Keevo locale n'est perdue : l'app Flutter ne propose la mise à jour que file vide et sans quarantaine.
  *Avec ADR-02, « la mise à jour » = l'installation de Bref Point depuis le Play Store (l'APK ne peut pas se mettre à jour vers la fiche Play) ; export CSV local comme filet (Keevo FR97) ; le dashboard liste les appareils avec des opérations non poussées depuis plus de 24 h.*
- **FUS-MIG8** — Abonnements en vigueur, crédits, packs SMS et compteurs du mois sont reportés à l'identique ; la règle `SUBSCRIPTION_IN_FORCE` à la date de migration fait foi, jamais `user.plan`.
  *ADR-07 : un owner sans abonnement en vigueur arrive en FREE, sans grâce supplémentaire (grâce Apple incluse dans la règle).*
- **FUS-MIG9** — Les liens publics Bref, les webhooks RevenueCat/MyCoolPay/Meta et les comptes WhatsApp Business restent fonctionnels avant, pendant et après la migration de chaque owner.
  *Liens : 302 vers la page publique Keevo qui résout `legacy_public_token`. Webhooks : routeur à double livraison, chacun ignore les owners inconnus, file de relivraison ; repointés sur Keevo seul à la convergence.*
- **FUS-MIG10** — Chaque vague produit un rapport archivé (owners, compteurs, sommes, anomalies, durée) consultable dans le dashboard.

Données importées par owner : identité, boutiques (logos), membres (hash bcrypt copiés, rôles mappés), produits (fusionnés par clé, `track_stock=false`), clients (par établissement, ADR-08), devis et factures (`legacy_reference`, `legacy_public_token`, date civile, dates d'origine, sans événement de pont ni quota), paiements, dépenses, monétisation, WhatsApp Business (token rechiffré), conversations IA, notifications, index du portail. Le schéma de la source suit le journal 18.3 : l'importeur et le kit de mesures fonctionnent avec et sans les commits `develop` non encore en production.

## 8. Exigences non fonctionnelles

- **FUS-NFR1** — Compatibilité ascendante de `/api/v1` : toute évolution du backend pendant P1–P3 est additive (colonnes nullables, nouveaux endpoints, nouveaux champs de delta) ; la suite de tests Flutter et les `curl-tests-story-*.sh` restent verts jusqu'au retrait de Flutter.
  *Prolonge Keevo NFR31. Les apps V1 2.0.1 ne reçoivent jamais `null` là où elles attendent une valeur ; rôles inconnus traités comme EMPLOYEE en lecture ; plans PRO/TEAM/BUSINESS présentés comme Premium.*
- **FUS-NFR2** — Les règles d'architecture existantes (ArchUnit, Modulith `verify`, `EndpointEntitlementCoverageTest`, table feature→module) s'appliquent aux nouveaux modules sans exception.
- **FUS-NFR3** — Le backend supporte le nombre de tenants mesuré en P0 × 2 (boot, provisioning, migrations par module, sauvegardes) avec un démarrage < 2 min et un import d'owner moyen < 60 s.
  *Objectifs de 19.11 : provisioning d'un tenant < 5 s, migration d'un module sur tous les schémas < 10 min ; DDL du cœur sous Flyway par module, plus de synchronisation de schéma au boot. Hors objectif → décision d'architecture avant l'epic 24. Prolonge Keevo NFR14–NFR15.*
- **FUS-NFR4** — Données locales de l'app chiffrées (SQLCipher), clés en stockage sécurisé ; secrets serveur externalisés et fail-fast (10-3) ; tokens WABA chiffrés au repos.
  *Reprend Keevo NFR8/FR85 pour la nouvelle app.*
- **FUS-NFR5** — L'app RN a une couverture de tests au moins équivalente à la spécification Flutter sur la couche données (mergers, politique de rejet, gate, calculs de montants) et des parcours Maestro hors ligne.
- **FUS-NFR6** — Contrats JSON partagés (`keevo/contracts/`) vérifiés à la fois par le backend et l'app (routes de modules, payloads de sync, datasets d'export).
- **FUS-NFR7** — Gel d'un owner < 15 min de bout en bout (gel → activation) pour un owner moyen ; aucune indisponibilité globale planifiée.
- **FUS-NFR8** *(révisée le 03/11/2026, ADR-01)* — Montants dans la devise de la boutique, avec l'arrondi que Bref applique pour ce pays (entiers pour XAF et XOF ; règle Bref reprise telle quelle pour la RDC) ; calcul des totaux identique dans l'app et le serveur (même jeu de cas de test partagé). Keevo V1 reste en XAF entiers.
  *Voir §12, écart E1 (devises non XAF de l'ADR-01).*
- **FUS-NFR9** *(révisée le 03/11/2026, ADR-01)* — Journées métier au fuseau du pays de la boutique (Africa/Douala par défaut) ; dates civiles importées telles quelles. Le fuseau figé dans le code (`KeevoZones.WAT`, exports, planificateurs) devient une donnée par boutique.
  *Voir §12, écart E1 (fuseau du pays de l'ADR-01). Les KPI importés utilisent la date civile de la facture, pas `createdAt`.*
- **FUS-NFR10** — Observabilité de la migration : métriques par vague (durée, échecs, écarts), logs corrélés `tenantId` / `migrationBatchId`.
- **FUS-NFR11** *(ajoutée par ce PRD, règle projet du 03/11/2026)* — Chaque implémentation applique le patron de conception adapté au problème et une complexité algorithmique optimale : aucun N+1 (chargements groupés, jointures ou requêtes par lot), traitements ensemblistes en SQL plutôt que boucles applicatives ligne à ligne (imports, réconciliation, reporting, quotas), pagination par curseur (keyset) sur toute liste ou export potentiellement volumineux, index justifiés pour chaque nouveau chemin de lecture ou de jointure. La revue de code (`bmad-code-review`) vérifie ce point sur chaque story.
  *Points d'application principaux : `assertAndIncrement` en une seule requête conditionnelle (19.8), union du CA lue d'une seule source (20.9), import et réconciliation par lots (24.2–24.8), listes inter-comptes du super-admin (20.13), index `invoice_phone_index` (21.7), pull de sync keyset déjà en place.*

Exigences Keevo qui s'appliquent sans changement à tout le périmètre fusionné : isolation 100 % entre tenants (NFR10), bcrypt coût ≥ 12 (NFR11), révocation des sessions (NFR12), audit immuable (NFR13, FR84/FR98), **aucune donnée technique visible par l'utilisateur** — ni UUID, ni code brut, libellés français (NFR35), y compris dans les rapports de vague et l'écran « Opérations bloquées ».

## 9. Exigences UX

- **FUS-UX1** — Le shell visuel de Bref (HeroUI Native, uniwind, thème, typographie) est le design system de l'app unifiée ; les écrans Keevo sont redessinés dedans.
- **FUS-UX2** — Un owner ex-Keevo retrouve son parcours Caisse comme écran d'accueil ; un owner ex-Bref retrouve son parcours Document (création de facture en 5 étapes).
- **FUS-UX3** — États de synchronisation visibles et compréhensibles : indicateur de connexion, bannières J5/J6/J7, écran « opérations bloquées », journal de synchronisation.
  *Prolonge Keevo FR74–FR75.*
- **FUS-UX4** — Écrans de migration : bannière d'annonce, refus motivé (mutations en attente), « migration en cours », « compte migré, mettez à jour ».
- **FUS-UX5** — Écran « Mon plan » unique : plan, échéance, quotas consommés, crédit, souscription en libre-service.
  *Remplace Keevo FR20 ; affiche aussi les prix en vigueur et la remise applicable (FUS-FR40).*
- **FUS-UX6** — **Mêmes repères** : chaque utilisateur existant garde ses repères, pas une navigation identique au pixel près : même écran d'accueil, mêmes onglets principaux dans le même ordre, mêmes libellés pour les mêmes fonctions, actions fréquentes au même endroit. Deux préréglages de navigation, « Caisse » (repères Keevo) et « Document » (repères Bref), calculés à partir du profil d'interface et des modules activés (une seule app, pas deux navigations codées). Seul l'habillage change.
- **FUS-UX7** — **N'afficher que ce qui le concerne** : un utilisateur existant ne voit que les fonctions qu'il avait dans son ancienne app. Toute fonction nouvelle pour lui (stock et caisse pour un ex-Bref ; devis, dépenses, assistant pour un ex-Keevo ; tout module ajouté plus tard) est **désactivée** par défaut, même si son plan l'inclut : elle n'apparaît nulle part et n'agit pas (aucun écran, aucune tâche planifiée, aucune notification). L'owner peut l'**activer dans Paramètres › Mes modules**, et la désactiver à nouveau. Pas de bannière ni d'écran promotionnel sur les écrans habituels. Un employé ne voit en plus que ce que son rôle permet.
  *Deux notions distinctes servies par le backend : le **droit** (le plan comprend le module) et l'**activation par l'owner**. Un module est utilisable s'il est compris dans le plan **et** activé. Désactiver ne supprime aucune donnée ; un module hors plan n'est pas activable ici (il relève de « Mon plan »). Pour un nouveau tenant, les modules activés au départ suivent son activité et son parcours, selon des règles en base (FUS-FR40). Pas d'écran « bientôt disponible » chez un utilisateur existant (rappel du journal 18.3, commit `2b8907e`). Les écrans de migration (FUS-UX4) ne sont pas promotionnels et restent autorisés.*
- **FUS-UX8** — **Accueil au passage** : au premier lancement de Bref Point, un ex-Keevo voit un écran court « Keevo devient Bref Point : vos données, vos boutiques et vos ventes sont là » ; un ex-Bref migré voit « Votre application a été mise à jour, vos factures sont là ». Une seule fois, sans étape obligatoire.

Écrans propres à la fusion (analyse §5.2) : connexion à onglets (mot de passe / code OTP / Google / Apple, identifiant = téléphone ou username), choix d'entreprise pour les ex-Bref, accueil composé de blocs contribués par les modules activés, liste unifiée des documents distincte de l'historique de caisse, « Mon plan », « Mes modules », « Opérations bloquées », portail client hors session, mode legacy.

## 10. Périmètre

### 10.1 Dans la fusion

- Toutes les fonctions actuelles de Keevo et de Bref, y compris celles que Bref livre pendant la fusion (journal 18.3 → story miroir ou mise à jour, inventaire de parité 22.17).
- Identité étendue, rôles OWNER/MANAGER/EMPLOYEE/VIEWER, plans et offre entièrement configurables, paiement en libre-service, quotas.
- Nouveaux modules backend : `expenses`, `finance`, `ai-assistant`, `whatsapp-cloud`, `client-portal`, `payments`, `migration` (temporaire) ; extensions de `identity`, `messaging`, `invoicing`, `catalog`, `crm`, `store`, `admin`, `entitlement`.
- API super-admin héritée de Bref (FUS-FR38), serveur MCP (FUS-FR39).
- Migration des owners Bref ; passage des utilisateurs Keevo à Bref Point ; une seule app sur les stores.
- Cinq pays dès la fusion : Cameroun, Gabon, Tchad, RDC, Sénégal (ADR-01).

### 10.2 Hors périmètre

- Nouvelles fonctions sans équivalent dans Keevo ou Bref (ex. futur module « Analyses », noté le 02/11, pas avant la mise en production).
- Facturation électronique DGI (en attente des spécifications).
- Refonte du dashboard super-admin (autre dépôt ; ce dépôt livre son API).
- App web ; option « fusionner mes deux entreprises » (le bridge crée deux memberships, pas de fusion de tenants).
- Écriture par l'IA hors confirmation explicite (21.8 livre les outils en lecture seule).
- Migration des sessions et des tokens push (les utilisateurs se reconnectent ; l'app enregistre un token FCM au premier lancement).

### 10.3 Contraintes

- **Bref n'est pas gelé** : la cible bouge, elle est suivie (18.3), ce qui peut allonger le calendrier.
- **Keevo V1** (prod `deploy` = V1 2.0.1, branche `release/2.0.x` figée) reste en service jusqu'au go-live ; l'app Flutter jusqu'à son retrait.
- La seule demande faite à l'équipe Bref : les endpoints de l'epic 24 (statut de migration, gel, export, pré-migrateur OTA).
- Estimation : 32 à 40 semaines calendaires ; un développeur backend assisté par IA dans ce dépôt, l'app construite en parallèle par un second développeur.

## 11. Jalons, phases et go-live

| Phase | Epic(s) | Sortie |
|---|---|---|
| P0 Cadrage | 18 | chiffres de prod (18.1), décisions écrites (18.2, fait), suivi de Bref (18.3, fait), accès aux comptes stores Bref Point (18.4), brief + PRD + architecture (18.5) |
| P1a Socle d'accueil | 19 | identité étendue, rôles, plans-données, quotas, versions, charge, profil d'interface |
| P1b Modules Bref | 20, 21 | API complète pour l'app unifiée ; parcours Bref vert sur Keevo |
| P1c Outil de migration | 24.1–24.10 | import d'une copie de prod sans écart |
| P2 App RN parité Keevo | 22 | utilisateurs Keevo sur Bref Point, Flutter retiré |
| P3 App RN parité Bref | 23 | app publiée sous `com.brefpoint.app` |
| P4 Vagues Bref | 24.11–24.16 | plus aucun owner actif sur api-bref |
| P5 Convergence | 25 | une app, un backend |

| Jalon | Critères pour continuer |
|---|---|
| **G0** fin P0 | décisions écrites, chiffres de prod connus (la refonte n'a plus à être en prod) |
| **G1** fin P1b | toutes les fonctions Bref dans l'API Keevo (20.12 vert, 20.13 livrée, epic 21), suite Flutter verte, charge 19.11 tenue |
| **GL** go-live de la refonte | **tout le merge prêt sur staging** : (1) epics 19, 20, 21 ; (2) app RN à parité Keevo et Bref (22.1–22.13, 23.1–23.11), inventaire de parité 22.17 entièrement coché « présente, même comportement », tests utilisateurs 22.18 concluants ; (3) outil de migration et dry-run 100 % réconcilié (24.1–24.10, 24.12) ; puis régression E2E 17.9 et canary 16.7. Si non : pas de mise en production de la refonte |
| **G2** fin bêta | zéro perte, crash-free ≥ 99,5 %, quarantaine < 0,5 % |
| **G3** avant vagues | dry-run réconcilié, app unifiée publiée, runbook relu |
| **G4** après pilotes | aucun écart à J+7, retours traités |
| **G5** avant arrêt api-bref | zéro owner actif, 4 semaines sans écriture |

**Après GL seulement** (étapes qui exigent la prod) : dernière release Flutter, bêta et publications store (22.14–22.16, 23.12), pré-migrateur OTA (24.11), runbook, pilotes et vagues (24.13–24.16), epic 25. Le déploiement en production se fait sur demande explicite (dépôt `origin2`).

## 12. Écarts entre sources, questions ouvertes, risques

### 12.1 Écarts relevés et résolution

| # | Écart | Résolution dans ce PRD |
|---|---|---|
| E1 | **FUS-NFR8** (montants en entiers XAF) et **FUS-NFR9** (journées en Africa/Douala) contre **ADR-01** (devise par pays : XOF pour SN, CDF ou USD pour CD ; journées au fuseau du pays ; SN est en UTC+0, l'est de la RDC en UTC+2). Même tension avec Keevo FR86 (XAF unique). | Les deux NFR sont **restituées telles quelles** (même identifiant, même sens). L'ADR-01 étant prioritaire, **QO-1** demande de les reformuler dans l'inventaire des epics (correct-course) avant la spec de 20.5 ; ce PRD ne les modifie pas lui-même. **Résolu le 03/11/2026 dans les epics.** |
| E2 | Analyse D6, §9.3, §9.5 : deux identités d'app sur les stores et passage Keevo par « même `applicationId` ». Restes dans les epics : tableau des phases (« publiée sous les deux identités »), 23.9 (« les deux identités d'app »), dépendance de l'epic 22 sur « 18.4 (identité Keevo) ». | **ADR-02 prévaut** : une seule fiche `com.brefpoint.app`, installation unique depuis l'APK. Les restes sont signalés pour nettoyage (QO-2). **Résolu le 03/11/2026 dans les epics.** |
| E3 | Analyse §9.2 : la refonte en production est un préalable de P1. | **Décision du 03/11 prévaut** : go-live au jalon GL, tout se construit sur la branche et sur staging. |
| E4 | Analyse §7.5 : clients au niveau tenant avec fusion à l'import. | **ADR-08 prévaut** : clients par établissement, pas de fusion automatique (FUS-FR15, 24.3). |
| E5 | Analyse §7.3 : `track_stock=false` par défaut pour le secteur Services. | **Décision du 01/11 prévaut** : réglage par produit seulement (FUS-FR13). |
| E6 | Analyse §7.10 : grâce de 30 j recommandée pour les owners expirés. | **ADR-07 prévaut** : FREE sans grâce (FUS-MIG8). |
| E7 | Keevo FR15/FR15b (trial 6 mois) et FR16 (FREE 3 employés, 500 produits) contre FUS-FR7 (essai 1 mois, FREE 1 employé). | Pour les nouveaux inscrits, FUS-FR7/FR40 remplacent ces valeurs ; essais en cours et employés existants honorés (ADR-03/04). Le PRD Keevo est à annoter (25.5). |
| E8 | Brief « un développeur backend + un second pour l'app » contre epics « un développeur plein temps ». | Le brief (décision du 01/11 : backend seul dans ce dépôt) prévaut ; l'estimation 32–40 semaines est conservée. |
| E9 | Le tableau « Couverture des exigences » des epics omet FUS-FR38, FR40, NFR2, NFR4, NFR9, UX6, UX7, UX8 ; FUS-UX8 n'a de critère d'acceptation que pour l'ex-Keevo (22.14). Les stories 18.4 et 18.5 n'ont pas de section dans les epics (seulement dans `sprint-status.yaml`). | La traçabilité du §13 les rattache aux stories qui les citent ; QO-3 demande un critère « ex-Bref migré » pour FUS-UX8 (candidats 23.11 ou 24.9) et l'ajout des lignes manquantes. **Résolu le 03/11/2026 dans les epics.** |

### 12.2 Questions ouvertes

- **QO-1** — Reformuler FUS-NFR8 / NFR9 selon l'ADR-01 (montants en unité entière de la devise du pays ; journées au fuseau du pays de la boutique) : à trancher par l'owner produit et à reporter dans les epics avant 20.5. — **Fait le 03/11/2026** (epics mis à jour : FUS-NFR8/NFR9 révisées, FUS-NFR11 ajoutée, couverture complétée, 23.11 porte FUS-UX8 pour les ex-Bref, mentions « deux identités » retirées).
- **QO-2** — Nettoyer les mentions « deux identités » (phases, 23.9) et la dépendance de l'epic 22 sur 18.4. — **Fait le 03/11/2026** (epics mis à jour : FUS-NFR8/NFR9 révisées, FUS-NFR11 ajoutée, couverture complétée, 23.11 porte FUS-UX8 pour les ex-Bref, mentions « deux identités » retirées).
- **QO-3** — Ajouter à l'inventaire FUS-NFR11 et les lignes manquantes du tableau de couverture ; donner à FUS-UX8 (ex-Bref) une story porteuse. — **Fait le 03/11/2026** (epics mis à jour : FUS-NFR8/NFR9 révisées, FUS-NFR11 ajoutée, couverture complétée, 23.11 porte FUS-UX8 pour les ex-Bref, mentions « deux identités » retirées).
- **QO-4** — Volumes réels (owners actifs et payants, téléphones communs, factures incohérentes, boutiques hors CM) : kit 18.1 prêt, à exécuter sur les réplicas de prod avant G0.
- **QO-5** — Accès aux comptes stores Bref Point (Play Console, App Store Connect, clés de signature, EAS) : story 18.4.
- **QO-6** — Rythme des évolutions de Bref pendant la fusion et effet sur le calendrier (suivi 18.3).
- **QO-7** — Durée de conservation légale de la sauvegarde finale d'api-bref (25.1).
- **QO-8** — Compatibilité Spring AI avec la version Spring Boot du backend (21.8).

### 12.3 Risques principaux (détail : analyse §11, renvois dans les epics)

| Risque | Mitigation | Stories |
|---|---|---|
| R1 volume de tenants (schéma par tenant) | mesure P0, DDL par module, test de charge, décision d'architecture si hors objectif | 18.1, 19.11 |
| R2 collisions d'identité | bridge, blocage avant écriture, vague dédiée | 19.6, 24.7, 24.15 |
| R3 factures Bref incohérentes | valeur conservée, anomalie signalée | 24.4, 24.8 |
| R4 double comptage du CA | source unique, tests d'acceptation, comparaison aux KPI Bref | 20.9, 20.12 |
| R5 app RN moins fiable que Flutter | tests Flutter portés comme spécification, contrats, Maestro, bêta | 22.4–22.7, 22.13, G2 |
| R6 abonnements Apple | routeur à double livraison, sandbox, vague Apple en dernier | 21.2, 24.10 |
| R7 retardataires | OTA, versions minimales, gel global | 19.10, 24.11, 24.16 |
| R8 mutations locales Bref | refus de gel si mutations en attente | 24.11 |
| R9 crédits et packs | report au franc près, contrôle dédié | 21.3, 24.5, 24.8 |
| R10 secrets | chiffrement au repos, fail-fast | 19.9, 21.5, 24.6 |
| R11 identité de l'app | une seule fiche (ADR-02), accès aux comptes | 18.4 |
| R12 charge de l'équipe et cible mouvante | jalons, `bmad-build-auto` par story, journal 18.3 | ordonnancement, 18.3 |

## 13. Traçabilité exigence → epic / story

Stories de l'app (22.x, 23.x) : réalisées dans `AI/bref` (clés `m<story>`), le backend fournit les contrats. « Keevo » = exigence du PRD Keevo étendue ou remplacée.

| Exigence | Epic | Stories | Keevo |
|---|---|---|---|
| FUS-FR1 | 19 / 22, 23 | 19.1, 19.2, 19.3, 19.4 ; app 22.3, 23.1 | FR1, FR3 |
| FUS-FR2 | 19 | 19.1 | FR1 |
| FUS-FR3 | 19 / 22 | 19.6 ; 24.7 ; app 22.3 | — |
| FUS-FR4 | 19 | 19.5 | FR10, FR66 |
| FUS-FR5 | 19 | 19.4, 19.6 | FR65 |
| FUS-FR6 | 19 | 19.3 | — |
| FUS-FR7 | 19 | 19.7 (sur 16-2, 16-8) ; 21.10 | FR15, FR15b, FR16 |
| FUS-FR8 | 19 | 19.8, 19.9 | — |
| FUS-FR9 | 21 / 23 | 21.1, 21.2 ; app 23.7 | FR78 |
| FUS-FR10 | 19, 21 / 23 | 19.8, 21.3 ; 24.5 ; app 23.7 | — |
| FUS-FR11 | 19 | 19.7 | FR78 |
| FUS-FR12 | 19 | 19.12 | FR30 |
| FUS-FR13 | 17, 20 | 17.12 (livré) ; 20.1, 20.6 ; 24.3 | FR26, FR45 |
| FUS-FR14 | 20 | 20.1 ; 24.3 | FR21 |
| FUS-FR15 | 20 | 20.2 ; 24.3 | FR87 |
| FUS-FR16 | 20 | 20.6 | — |
| FUS-FR17 | 19 / 22, 23 | 19.12 ; app 22.9, 23.2, 23.10 | — |
| FUS-FR18 | 20 / 23 | 20.3 ; app 23.3 | — |
| FUS-FR19 | 20 / 23 | 20.5 ; app 23.2 | — |
| FUS-FR20 | 20 / 23 | 20.4 ; app 23.3 | — |
| FUS-FR21 | 20 / 23 | 20.7 ; app 23.4 | FR95 |
| FUS-FR22 | 20 / 23 | 20.7, 20.8 ; 24.10 ; app 23.4 | FR57 |
| FUS-FR23 | 20, 24 | 20.7 ; 24.4 | — |
| FUS-FR24 | 20 | 20.9, 20.12 | FR44, FR50–FR54 |
| FUS-FR25 | 20 / 23 | 20.10 ; app 23.5 | — |
| FUS-FR26 | 20 / 23 | 20.11 ; app 23.5 | FR54 |
| FUS-FR27 | 21 / 23 | 21.4 (après 21.8) ; app 23.6 | — |
| FUS-FR28 | 21 / 23 | 21.5, 21.6 ; 24.6 ; app 23.6 | — |
| FUS-FR29 | 21 / 23 | 21.7 ; 24.6 ; app 23.8 | — |
| FUS-FR30 | 19 / 23 | 19.9 ; app 23.9 | FR61–FR62 |
| FUS-FR31 | 19 | 19.10 ; 20.13 ; 22.16 | — |
| FUS-FR32 | 21 | 21.6 ; 20.13 | FR82 |
| FUS-FR33 | 22, 23, 25 | 22.1, 22.14, 22.15, 23.12, 25.4 ; 18.4 | — |
| FUS-FR34 | 22 | 22.4, 22.5, 22.6, 22.7, 22.10, 22.11, 22.12 | FR69–FR76, FR73a |
| FUS-FR35 | 19, 22 | 19.13 ; app 22.8 | — |
| FUS-FR36 | 22, 23 | app 23.9 | NFR26 |
| FUS-FR37 | 23, 24 | 24.1 ; app 23.11 | — |
| FUS-FR38 | 20 | 20.13 (+ 19.10, 21.6, 21.10, 24.9) | FR77–FR83 |
| FUS-FR39 | 21 / 23 | 21.8, 21.4, 21.9 ; app 23.6 | — |
| FUS-FR40 | 21, 19 | 21.10 ; 19.7, 19.13 | FR16 |
| FUS-MIG1 | 24 | 24.2, 24.3, 24.4, 24.5, 24.6 | — |
| FUS-MIG2 | 24, 21 | 24.2 ; 21.2 | — |
| FUS-MIG3 | 24 | 24.8 | — |
| FUS-MIG4 | 24 | 24.1 | — |
| FUS-MIG5 | 24 | 24.9 | — |
| FUS-MIG6 | 24 | 24.11 | — |
| FUS-MIG7 | 22 | 22.14 ; app 22.2 | FR73, FR97 |
| FUS-MIG8 | 24, 21 | 24.5 ; 21.2, 21.3 | — |
| FUS-MIG9 | 24, 25 | 24.10 ; 25.2 | — |
| FUS-MIG10 | 24 | 24.9, 24.15 | — |
| FUS-NFR1 | transverse 19–21 | chaque story 19–21 (19.1, 19.5, 19.7, 19.13 explicitement) | NFR31 |
| FUS-NFR2 | transverse 19–21 | chaque nouveau module ; 21.8 (couverture des outils) | — |
| FUS-NFR3 | 19, 24 | 19.11 ; 24.12 | NFR14–NFR15 |
| FUS-NFR4 | 19, 21, 22, 24 | 19.9, 21.5, 24.6 ; app 22.2 | NFR8, FR85 |
| FUS-NFR5 | 22 | app 22.13 | — |
| FUS-NFR6 | 22, 20 | 20.x (contrats de sync), app 22.1, 22.8, 22.10 | — |
| FUS-NFR7 | 24 | 24.9, 24.12 | — |
| FUS-NFR8 | 20, 22 | 20.4, 20.5 ; app 23.2 | FR86 |
| FUS-NFR9 | 20, 24 | 20.9 ; 24.4 | — |
| FUS-NFR10 | 24 | 24.9 | — |
| FUS-NFR11 *(PRD)* | transverse 19–25 | toutes ; vérifiée par `bmad-code-review` | — |
| FUS-UX1 | 22 | app 22.1, 22.9 | — |
| FUS-UX2 | 22, 23 | app 22.9, 23.2, 23.10 | — |
| FUS-UX3 | 22 | app 22.6, 22.7 | FR74–FR75 |
| FUS-UX4 | 23, 24 | 24.11 ; app 23.11 | — |
| FUS-UX5 | 23 | 21.10 ; app 23.7 | FR20 |
| FUS-UX6 | 19, 22, 23 | 19.13 ; app 22.9, 23.10 ; 22.17, 22.18 | — |
| FUS-UX7 | 19, 22, 23 | 19.13 ; app 22.8, 22.9, 23.10 ; 22.18 | — |
| FUS-UX8 | 22, 23 | 22.14 (ex-Keevo) ; 23.11 (ex-Bref migrés) | — |

Couverture inverse (stories sans exigence FUS propre) : 18.1–18.5 (cadrage), 20.12 (preuve P1b), 22.17–22.18 (critères GL), 24.12–24.16 (exécution des vagues, au service de FUS-MIG1–MIG10), 25.1–25.5 (convergence, au service de FUS-FR38 et FUS-MIG9).
