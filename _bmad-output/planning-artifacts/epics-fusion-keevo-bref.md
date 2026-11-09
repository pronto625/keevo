---
stepsCompleted: [step-01-validate-prerequisites, step-02-design-epics, step-03-create-stories]
status: ready  # 2026-11-03 : décisions 18.2 prises (Annexe C) ; le gate G0 attend encore les chiffres de prod 18.1
date: 2026-11-01
author: Dalois-30 (avec Claude)
inputDocuments:
  - _bmad-output/planning-artifacts/fusion-keevo-bref-analyse-2026-10-31.md   # analyse source (D1–D7, §5–§13)
  - _bmad-output/planning-artifacts/epics-refonte-modulaire.md                # epics 10–16 (socle modulaire)
  - _bmad-output/planning-artifacts/prd.md
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/implementation-artifacts/sprint-status.yaml                  # epics 16 et 17 en backlog
  - plan-refonte-modulaire.md
  - spec-socle-modulaire.md
reposAnalysed:
  keevo: "AI/Keevo @ refonte/modulaire c4e69b0a (31/10/2026)"
  api-bref: "AI/api-bref @ develop 376ad70 (31/10/2026)"
  bref: "AI/bref @ develop a435e9b (31/10/2026)"
scope: "Fusion Keevo × Bref Point : un backend (Keevo Spring Boot), une app React Native (Expo), migration progressive des utilisateurs et des données des deux produits, puis décommissionnement d'api-bref et de l'app Flutter. Epics 18 à 25. Préserve les epics 1–17."
decisionsLocked:
  - "D1 Backend unique = Keevo Spring Boot (Java 21, Spring Modulith) ; api-bref absorbé en modules puis arrêté"
  - "D2 App unique = Expo / React Native, shell Bref + couche données portée du protocole de sync Keevo (sync_queue, push/pull keyset, quarantaine, gate 7 j) ; app Flutter retirée"
  - "D3 Un owner Bref = un tenant Keevo ; shops → stores ; membres → employees + memberships"
  - "D4 Identité étendue (email, username, OTP, Google, Apple) ; réconciliation téléphone E.164 puis email ; hash bcrypt Bref réutilisés tels quels"
  - "D5 Plans Bref → profils/bundles Keevo ; quotas d'usage ; module payments (MyCoolPay, StoreKit/RevenueCat) = auto-service"
  - "D6 Strangler à double piste : Keevo sans migration de données ; Bref owner par owner (gel → export → import idempotent → réconciliation bloquante → activation), api-bref maintenu pour les non-migrés"
  - "D7 (révisée 03/11, ADR-02) Une seule identité store : com.brefpoint.app (Bref Point) ; Keevo, distribué en APK hors store, rejoint cette fiche"
  - "Deux parcours de vente : Caisse (pos → sales + stock) et Document (invoicing, sans stock) ; reporting = union sans double comptage"
  - "Factures émises non modifiables (conformité DGI) ; devis éditables ; correction = avoir + nouvelle facture"
decisionsTaken:  # 2026-11-03, story 18.2 — détail et stories impactées : Annexe C
  - "ADR-01 Q12 multi-pays dès la fusion (CM, GA, TD, CD, SN) : TVA par pays, indicatifs, journées au fuseau du pays"
  - "ADR-02 Q11 marque finale = Bref Point ; une seule identité store (com.brefpoint.app) — Keevo n'est sur aucun store (APK GitHub, com.example.keevo)"
  - "ADR-03 Q10 FREE = 1 employé ; tenants Keevo FREE au-delà gardent leurs employés (droit acquis, pas d'ajout)"
  - "ADR-04 Q10 essai du plan le plus complet réduit à 1 mois pour tout nouvel inscrit ; essais en cours honorés"
  - "ADR-05 Q10 rien n'est figé dans le code : plans, modules par plan, plafonds, quotas, cycles, prix, essai, remises et promotions se créent, se modifient et s'archivent depuis le dashboard super-admin, sans redéploiement ; la grille Bref et les valeurs des ADR-03/04/06 ne sont que les données de départ"
  - "ADR-06 Keevo PREMIUM → TEAM, prix inchangé jusqu'à la fin de la période payée"
  - "ADR-07 §7.10 owner Bref expiré → FREE à la migration (règle SUBSCRIPTION_IN_FORCE, pas de grâce) : il ne doit pas sentir la migration"
  - "ADR-08 §7.5 clients par établissement (boutique, restaurant…), vue « tous mes établissements » pour l'owner, partage optionnel par établissement, pas de fusion automatique"
  - "ADR-09 §9.5 publication précoce de l'app unifiée sous com.brefpoint.app avec mode legacy"
  - "§7.3 TRANCHÉ le 2026-11-01 : suivi de stock réglé par produit (track_stock), livré par 17.12"
---

# Keevo × Bref Point — Plan de fusion : epics et stories

## Overview

Ce document est le **plan d'exécution BMAD** de la fusion décidée dans `fusion-keevo-bref-analyse-2026-10-31.md` (désigné ci-après « l'analyse », sections citées en `§n`). Il transforme les décisions D1–D7 en **exigences numérotées**, en **8 epics (18 à 25)** et en **stories implémentables** avec critères d'acceptation, dans l'ordre où elles doivent être livrées.

Il **complète** les epics 1–17 sans les remplacer.

> **Décision du 03/11/2026 (owner produit) — la mise à jour de la refonte attend tout le merge Keevo × Bref.** La mise en production de la refonte (17.9 gate, 16.7 canary) n'a lieu qu'au jalon **GL** : tout le merge Keevo × Bref : (1) epics 19, 20, 21 — toutes les fonctionnalités Bref dans l'API Keevo ; (2) app React Native à parité Keevo et Bref, validée sur staging (22.1–22.13, 23.1–23.11) : **inventaire de parité fonction par fonction** (22.17) entièrement coché « présente, même comportement », et **tests avec de vrais utilisateurs** (22.18) concluants ; (3) outil de migration prêt et dry-run 100 % réconcilié sur copie de prod (24.1–24.10, 24.12). La refonte n'est donc plus un préalable de la fusion : tout se construit sur la branche et sur staging dès G0. Ne viennent **après** le go-live que les étapes qui exigent la prod : dernière release Flutter, bêta et publications store (22.14–22.16, 23.12), pré-migrateur OTA (24.11), runbook, pilotes et vagues (24.13–24.16), puis l'epic 25.

Les epics 16 et 17 restent liés à la fusion :

- **Epic 16 (Licences, Migration & Go-Live)** : la production Keevo tourne encore sur la V1 (`deploy` = `34949fec`, 47 commits derrière `refonte/modulaire`). Depuis le 03/11/2026, la refonte et les modules Bref partent en production **ensemble**, après G1 (16-2 bundles, 16-3 modules actifs côté app (sans claim dans le jeton), 16-11 sessions longues et sûres, 16-4 console, 16-5 backfill, 16-7 canary, 16-8 profils configurables).
- **Epic 17 (Complétion fonctionnelle)** : rapprochement du 2026-11-01 — 17.1, 17.3, 17.4, 17.5 livrés (V1 puis portés), 17.6 remplacée ; **17.9 (régression E2E) est le gate du go-live** de la refonte ; 17.2, 17.7, 17.8, 17.10, 17.11, 17.12 sont fonctionnelles et indépendantes de la fusion. **17.12 (suivi de stock par produit) livre `products.track_stock`, dont la fusion dépend (FUS-FR13, story 20.1).**
- **10-8** (exécution serveur des runbooks) et **16.10** (scope employé des transferts) font aussi partie du go-live de la refonte. Le chemin complet est dans `epics-refonte-modulaire.md`, section « État de la refonte au 1er novembre 2026 ».

**Hypothèses de charge** : un développeur plein temps, mode de travail identique à la refonte (spec BMAD par story puis `bmad-build-auto`, revue `bmad-code-review`). Estimation totale : **32 à 40 semaines** calendaires (§13 de l'analyse), avec recouvrement P1/P2.

**Règle d'or de la fusion** : *aucun utilisateur ne réinstalle, ne perd de données, ne change de mot de passe, ni ne paie deux fois.* Chaque story qui touche une donnée de production le démontre dans ses critères d'acceptation.

### Correspondance phases ↔ epics

| Phase (§9.2) | Epic(s) | Durée | Sortie (jalon) |
|---|---|---|---|
| Socle | **16** (refonte, sur la branche) | livré sauf 16-7/16-12/16-9 | socle modulaire prêt ; **go-live 16-7 au jalon GL** (tout le merge, décision du 03/11/2026) |
| **P0** Cadrage | **18** | 2 sem | chiffres connus, décisions écrites, évolutions de Bref suivies (pas de gel, 03/11) |
| **P1a** Socle d'accueil | **19** | 6 sem | login OTP/social/username sur Keevo, rôles, quotas, plans ; app Flutter intacte |
| **P1b** Modules Bref | **20**, **21** | 8 sem | API complète pour l'app unifiée ; parcours Bref vert sur Keevo |
| **P1c** Outil de migration | **24** (24.1 → 24.10) | 3 sem (recouvrement P1b) | 100 % d'une copie de prod Bref importée sans écart |
| **P2** App RN parité Keevo | **22** | 10–12 sem (démarre à P1a + 4 sem) | tous les utilisateurs Keevo sur RN, Flutter retiré |
| **P3** App RN parité Bref | **23** | 8–10 sem | app unifiée prête pour la fiche Bref Point (ADR-02) |
| **P4** Vagues Bref | **24** (24.11 → 24.16) | 4–8 sem | plus aucun owner actif sur api-bref |
| **P5** Convergence | **25** | 2–4 sem | une app, un backend |

---

## Requirements Inventory

### Exigences fonctionnelles de fusion (`FUS-FR`)

**Identité et accès**

- **FUS-FR1 :** Un utilisateur peut se connecter par téléphone + mot de passe (Keevo), par code OTP reçu par SMS, WhatsApp ou email, par Google, par Apple, ou par identifiant (username) + mot de passe (membres Bref).
- **FUS-FR2 :** Un compte possède au moins un identifiant parmi téléphone E.164, email, username ; le téléphone n'est plus obligatoire.
- **FUS-FR3 :** Une même personne peut être owner d'un tenant et membre d'un autre ; après login elle choisit son entreprise (tenant-picker existant).
- **FUS-FR4 :** Les rôles de membership sont OWNER, MANAGER, EMPLOYEE, VIEWER ; chaque module déclare les permissions de chaque rôle ; VIEWER n'écrit jamais.
- **FUS-FR5 :** Un owner crée un membre avec téléphone ou username ; le mot de passe initial n'est jamais renvoyé en clair une seconde fois.
- **FUS-FR6 :** Un compte social se lie automatiquement à un compte existant par email seulement si l'email est vérifié et si le compte n'est pas un membre (règle Bref `develop`).

**Plans, quotas, paiement**

- **FUS-FR7 :** Les plans sont des **données** (catalogue en base, plus d'énumération de plans dans le code) ; FREE, PRO, TEAM, BUSINESS sont créés au départ comme profils configurables (bundles de modules + plafonds) ; PREMIUM Keevo est servi par TEAM (ADR-06) ; FREE = 1 employé, droit acquis pour les tenants Keevo existants (ADR-03) ; essai d'1 mois (ADR-04).
- **FUS-FR8 :** Les quotas mensuels (documents, SMS, emails, requêtes IA) sont décomptés de façon atomique par tenant et période ; un dépassement renvoie une erreur explicite sans bloquer le cœur.
- **FUS-FR9 :** Un owner peut souscrire ou renouveler un plan en libre-service par Mobile Money (MyCoolPay) sur Android et par achat intégré (StoreKit via RevenueCat) sur iOS ; le paiement confirmé active le bundle sans intervention du super-admin.
- **FUS-FR10 :** Le crédit de compte et les packs SMS achetés sont conservés, affichés et consommables.
- **FUS-FR40 :** **Rien de l'offre commerciale n'est figé dans le code** (ADR-05). Depuis le dashboard super-admin, on crée, modifie, ordonne, masque et archive : les plans eux-mêmes (code, libellé, description, visibilité, plan gratuit par défaut), leurs modules, plafonds (boutiques, produits, employés…) et quotas mensuels (documents, SMS, emails, crédits IA…), les cycles de facturation et leurs prix, la politique d'essai (plan, durée, bénéficiaires), les remises et promotions (pourcentage ou montant, période, plans et cycles visés, tenant ciblé, code promo). Tout changement s'applique sans redéploiement de l'API ni de l'app ; les valeurs des ADR-03, 04 et 06 et la grille Bref ne sont que des données de départ.
- **FUS-FR11 :** Le super-admin peut toujours accorder un plan manuellement (`admin_grant`), avec trace dans `tenant_plan_events`.

**Boutiques, catalogue, clients**

- **FUS-FR12 :** Une boutique porte catégorie, description, logo, NIU, RCCM, pays.
- **FUS-FR13 :** Un produit peut ne pas suivre de stock (`track_stock=false`) : pas de contrôle, pas de mouvement, pas d'alerte, hors inventaire ; l'owner peut activer le suivi plus tard, avec un stock initial facultatif (0 par défaut). Le réglage est **par produit**, jamais déduit du secteur ou du type de boutique (décision du 2026-11-01). **Livré dans la refonte par la story 17.12** ; la fusion n'ajoute que le défaut `false` à l'import Bref et la dérivation depuis les documents.
- **FUS-FR14 :** Un produit porte une clé de déduplication (`product_key`), un code-barres, un prix manuel ; deux produits peuvent être fusionnés (le fusionné est archivé et redirige).
- **FUS-FR15 :** Un client porte type (particulier/entreprise), NIU, RCCM, adresse, contact, site web, date de naissance, consentement marketing (date d'accord, de retrait, auteur), boutique d'origine optionnelle ; le service avertit en cas de doublon de téléphone.
- **FUS-FR16 :** Créer un document peut créer ou mettre à jour le client et les produits correspondants (dérivation optionnelle, désactivable par tenant).

**Ventes et documents**

- **FUS-FR17 :** Un tenant dispose de deux parcours de vente : Caisse (vente + stock + facture automatique) et Document (facture ou devis sans stock) ; il choisit son parcours par défaut.
- **FUS-FR18 :** Un devis est éditable, convertible en facture (la date d'émission est celle de la conversion) et annulable.
- **FUS-FR19 :** Une facture accepte remise, frais de livraison (hors assiette TVA), taux de TVA propre ou exonération, date d'échéance, NIU/RCCM/email du client, lignes liées ou non à un produit, quantités à 3 décimales.
- **FUS-FR20 :** Une facture accepte un acompte, des paiements partiels et un échéancier ; son état d'encaissement (impayée, partielle, payée, en retard) est calculé par le serveur ; un job quotidien passe en retard les échéances dépassées.
- **FUS-FR21 :** Une facture se rend selon 8 modèles (dont ticket 58/80 mm) avec signatures client/vendeur facultatives ; le rendu fonctionne hors ligne dans l'app et côté serveur pour le lien public.
- **FUS-FR22 :** Une facture s'envoie par WhatsApp, SMS ou email (quotas FUS-FR8) avec un lien public dont la durée est paramétrable ; les liens Bref historiques restent valides.
- **FUS-FR23 :** Les factures historiques Bref conservent leur référence (`BRF-…`) ; les nouvelles suivent la numérotation sans trou `FAC-`.
- **FUS-FR24 :** Le chiffre d'affaires, les rapports, la rentabilité et le classement des vendeurs comptent les ventes caisse et les factures sans vente source, sans double comptage.

**Nouveaux modules**

- **FUS-FR25 :** Module `expenses` : dépenses par boutique et catégorie (10 catégories Bref), hors ligne.
- **FUS-FR26 :** Module `finance` : résultat (CA HT − dépenses), marge, variations, séries par période.
- **FUS-FR27 :** Module `ai-assistant` : questions guidées gratuites, chat libre décompté par quota, fournisseur configurable. **Le chat libre répond par appels d'outils (FUS-FR39), jamais par génération de SQL** ; parité fonctionnelle avec « Bref Point » dès la vague de migration. *(Décision utilisateur du 02/11/2026.)*
- **FUS-FR28 :** Module `whatsapp-cloud` : compte WhatsApp Business par tenant, boîte de réception, modèles de messages, campagnes clients respectant le consentement marketing.
- **FUS-FR29 :** Module `client-portal` : un client final retrouve, par OTP sur son téléphone, toutes ses factures quel que soit le commerçant.
- **FUS-FR30 :** Notifications in-app persistantes (liste, lu/non lu) en plus des push FCM.
- **FUS-FR31 :** Le super-admin gère les versions minimales d'app par plateforme et identité ; l'app force la mise à jour en dessous.
- **FUS-FR32 :** Le super-admin envoie des campagnes multi-canal (push, WhatsApp, SMS, email) à des segments de tenants.

**Application mobile unifiée**

- **FUS-FR33 :** Une seule base de code React Native produit une seule application, publiée sous l'identité Bref Point (`com.brefpoint.app`) ; les utilisateurs Keevo (APK hors store) y passent avec leurs identifiants (ADR-02).
- **FUS-FR34 :** L'app fonctionne hors ligne pour toutes les écritures métier (ventes, documents, paiements, stock, catalogue, clients, dépenses) via la file d'opérations du protocole Keevo.
- **FUS-FR35 :** L'app n'affiche que les modules actifs du tenant et les permissions du rôle ; une route d'un module inactif n'est jamais atteignable.
- **FUS-FR36 :** L'app propose verrouillage par PIN et biométrie, 7 langues (fr et en exposées d'abord), thème clair/sombre.
- **FUS-FR37 :** L'app ex-Bref publiée sur `com.brefpoint.app` sait dire à un owner non encore migré quand il le sera et lui laisse consulter ses factures (mode legacy, ADR-09).
- **FUS-FR38 :** L'API Keevo absorbe **toute** l'API Bref, y compris sa partie super-admin (dashboard Bref) : vue plateforme (`/v1/dashboard` overview, growth, top-shops, activity, owner-summary), listes inter-comptes (clients, produits, dépenses `*-admin`), gestion des utilisateurs (liste, stats, appareils, statut, plan, suppression), versions d'app, offres, campagnes plateforme. Après la convergence, aucun client (app ou dashboard) n'appelle plus api-bref. *(Décision utilisateur du 01/11/2026.)*
- **FUS-FR39 :** Une **couche d'outils IA** unique, construite sur les use cases Keevo (ports `in/` purs, commandes/requêtes `{Action}{Entity}Command/Query`), sert deux consommateurs : l'assistant intégré (`ai-assistant`, appel d'outils par le fournisseur de modèle) et un **serveur MCP** (Spring AI MCP Server, `adapter/in/mcp/` de chaque module) pour les clients IA externes. Chaque outil applique les mêmes contrôles que le REST (tenant, module `@RequiresEntitlement`, rôle, périmètre employé, quota) ; l'acteur et le tenant viennent toujours de la session, jamais du modèle ; outils en lecture d'abord, écriture seulement avec confirmation explicite ; chaque appel est audité. *(Décision utilisateur du 02/11/2026 : approche par outils MCP directement, sans porter le constructeur SQL de Bref.)*

### Exigences de migration (`FUS-MIG`)

- **FUS-MIG1 :** Un owner Bref est migré seul, de façon idempotente : rejouer son import est sans effet ou complète ce qui manque.
- **FUS-MIG2 :** Tous les identifiants Bref (UUID) sont conservés ; chaque ligne importée est tracée dans `migration_records` (table, id source, checksum, note).
- **FUS-MIG3 :** L'activation d'un owner migré est bloquée tant que la réconciliation (§10.3) présente un écart hors tolérance.
- **FUS-MIG4 :** Pendant la migration d'un owner, ses écritures (et celles de ses membres) sont refusées par api-bref avec 423 `ACCOUNT_MIGRATING`, ses lectures restent possibles.
- **FUS-MIG5 :** Le retour arrière d'un owner est possible jusqu'à son activation (suppression du tenant importé, dégel api-bref).
- **FUS-MIG6 :** Aucune mutation locale Bref n'est perdue : le gel est refusé tant que l'appareil a des mutations en attente ; les lignes locales sans équivalent serveur sont montrées à l'owner.
- **FUS-MIG7 :** Aucune opération Keevo locale n'est perdue : l'app Flutter ne propose la mise à jour que file vide et sans quarantaine.
- **FUS-MIG8 :** Abonnements en vigueur, crédits, packs SMS et compteurs du mois sont reportés à l'identique ; la règle `SUBSCRIPTION_IN_FORCE` à la date de migration fait foi, jamais `user.plan` ⚠️§7.10.
- **FUS-MIG9 :** Les liens publics Bref, les webhooks RevenueCat/MyCoolPay/Meta et les comptes WhatsApp Business restent fonctionnels avant, pendant et après la migration de chaque owner.
- **FUS-MIG10 :** Chaque vague produit un rapport archivé (owners, compteurs, sommes, anomalies, durée) consultable dans le dashboard.

### Exigences non fonctionnelles (`FUS-NFR`)

- **FUS-NFR1 :** Compatibilité ascendante de `/api/v1` : toute évolution du backend pendant P1–P3 est additive (colonnes nullables, nouveaux endpoints, nouveaux champs de delta) ; la suite de tests Flutter et les `curl-tests-story-*.sh` restent verts jusqu'au retrait de Flutter.
- **FUS-NFR2 :** Les règles d'architecture existantes (ArchUnit, Modulith `verify`, `EndpointEntitlementCoverageTest`, table feature→module) s'appliquent aux nouveaux modules sans exception.
- **FUS-NFR3 :** Le backend supporte le nombre de tenants mesuré en P0 × 2 (boot, provisioning, migrations par module, sauvegardes) avec un temps de démarrage < 2 min et un import d'owner moyen < 60 s.
- **FUS-NFR4 :** Données locales de l'app chiffrées (SQLCipher), clés en stockage sécurisé ; secrets serveur externalisés et fail-fast (10-3) ; tokens WABA chiffrés au repos.
- **FUS-NFR5 :** L'app RN a une couverture de tests au moins équivalente à la spécification Flutter sur la couche données (mergers, politique de rejet, gate, calculs de montants) et des parcours Maestro hors ligne.
- **FUS-NFR6 :** Contrats JSON partagés (`keevo/contracts/`) vérifiés à la fois par le backend et l'app (routes de modules, payloads de sync, datasets d'export).
- **FUS-NFR7 :** Gel d'un owner < 15 min de bout en bout (gel → activation) pour un owner moyen ; aucune indisponibilité globale planifiée.
- **FUS-NFR8 (révisée 03/11, ADR-01) :** Montants dans la devise de la boutique, avec l'arrondi que Bref applique pour ce pays (entiers pour XAF et XOF ; règle Bref reprise telle quelle pour la RDC) ; calcul des totaux identique dans l'app et le serveur (même jeu de cas de test partagé). Keevo V1 reste en XAF entiers.
- **FUS-NFR9 (révisée 03/11, ADR-01) :** Journées métier au fuseau du pays de la boutique (Africa/Douala par défaut ; Dakar, Kinshasa, Lubumbashi… selon le pays) ; dates civiles importées telles quelles. Le fuseau aujourd'hui figé dans le code (`KeevoZones.WAT`, exports, planificateurs) devient une donnée par boutique.
- **FUS-NFR10 :** Observabilité de la migration : métriques par vague (durée, échecs, écarts), logs corrélés `tenantId`/`migrationBatchId`.
- **FUS-NFR11 (03/11/2026) :** Chaque implémentation applique le design pattern adapté à la situation et une complexité algorithmique optimale (pas de N+1, SQL ensembliste, pagination keyset, index adaptés, traitements par lots) ; vérifié dans chaque spec et à chaque revue, une violation est un constat bloquant.

### Exigences UX (`FUS-UX`)

- **FUS-UX1 :** Le shell visuel de Bref (HeroUI Native, uniwind, thème, typographie) est le design system de l'app unifiée ; les écrans Keevo sont redessinés dedans.
- **FUS-UX2 :** Un owner ex-Keevo retrouve son parcours Caisse comme écran d'accueil ; un owner ex-Bref retrouve son parcours Document (création de facture en 5 étapes).
- **FUS-UX3 :** États de synchronisation visibles et compréhensibles : indicateur de connexion, bannières J5/J6/J7, écran « opérations bloquées », journal de synchronisation.
- **FUS-UX4 :** Écrans de migration : bannière d'annonce, refus motivé (mutations en attente), « migration en cours », « compte migré, mettez à jour ».
- **FUS-UX5 :** Écran « Mon plan » unique : plan, échéance, quotas consommés, crédit, souscription en libre-service.
- **FUS-UX6 (03/11/2026) — mêmes repères :** chaque utilisateur existant garde ses repères, pas une navigation identique au pixel près : même écran d'accueil, mêmes onglets principaux dans le même ordre, mêmes libellés pour les mêmes fonctions, actions fréquentes au même endroit. Deux préréglages de navigation, « Caisse » (repères Keevo) et « Document » (repères Bref), calculés à partir du profil d'interface et des modules activés (une seule app, pas deux navigations codées). Seul l'habillage change.
- **FUS-UX7 (03/11/2026) — n'afficher que ce qui le concerne :** un utilisateur existant ne voit que les fonctions qu'il avait dans son ancienne app. Toute fonction nouvelle pour lui (stock et caisse pour un ex-Bref ; devis, dépenses, assistant pour un ex-Keevo ; tout module ajouté plus tard) est **désactivée** par défaut, même si son plan l'inclut : elle n'apparaît nulle part et n'agit pas (aucun écran, aucune tâche planifiée, aucune notification). L'owner peut l'**activer dans Paramètres › Mes modules**, et la désactiver à nouveau. Pas de bannière ni d'écran promotionnel sur les écrans habituels. Un employé ne voit en plus que ce que son rôle permet.
- **FUS-UX8 (03/11/2026) — accueil au passage :** au premier lancement de Bref Point, un ex-Keevo voit un écran court « Keevo devient Bref Point : vos données, vos boutiques et vos ventes sont là » ; un ex-Bref migré voit « Votre application a été mise à jour, vos factures sont là ». Une seule fois, sans étape obligatoire.

### Exigences additionnelles (architecture)

- Nouveaux modules backend (manifeste, `db/modules/<id>/`, permissions, `DeltaEntityProvider`, routes d'app) : `expenses`, `finance`, `ai-assistant`, `whatsapp-cloud`, `client-portal`, `payments`, `migration` (temporaire) ; extensions de `identity`, `messaging`, `invoicing`, `catalog`, `crm`, `store`, `admin`, `entitlement` (Annexe B de l'analyse).
- Tables `public` nouvelles : `user_authenticators` (ou colonnes `users`), `otp_challenges`, `usage_counters`, `external_subscriptions`, `tenant_credits`, `app_versions`, `invoice_phone_index`, `portal_clients`, `migration_batches`, `migration_records`.
- App : monorepo pnpm `packages/{core,data,ui,pos,invoicing,catalog,inventory,crm,expenses,finance,...}` ; registre de modules client ; table feature→module testée contre `contracts/module-routes.json`.
- Infrastructure : routeur de webhooks (reverse proxy à double livraison), redirection des liens publics, réplica en lecture ou endpoint d'export api-bref, stockage fichiers (logos, signatures, PDF).

---

## Couverture des exigences

| Exigence | Epic | Note |
|---|---|---|
| FUS-FR1, FR2, FR3, FR5, FR6 | **19** | identité étendue (backend) ; écrans en **22** (Keevo) et **23** (OTP/social) |
| FUS-FR4 | **19** | rôles + permissions par module |
| FUS-FR7, FR8, FR11 | **19** | profils/bundles (s'appuie sur 16-2/16-8) + compteurs |
| FUS-FR9, FR10 | **21** (backend) / **23** (écrans) | paiement libre-service |
| FUS-FR12 | **19** | extension store |
| FUS-FR13–FR16 | **20** | catalog/crm étendus, dérivation |
| FUS-FR17 | **19** (préférence) / **22** + **23** (navigation) | deux parcours |
| FUS-FR18–FR23 | **20** (backend) / **23** (écrans) | invoicing étendu |
| FUS-FR24 | **20** | reporting multi-source |
| FUS-FR25, FR26 | **20** / **23** | expenses, finance |
| FUS-FR27–FR32 | **21** / **23** | IA, WhatsApp Cloud, portail, notifications, versions, campagnes |
| FUS-FR38 | **20** (20.13) | API super-admin héritée de Bref |
| FUS-FR39 | **21** (21.8, 21.4, 21.9) / **23** (23.6) | couche d'outils IA, assistant par outils, serveur MCP |
| FUS-FR40 | **19** (19.7) / **21** (21.10) | offre commerciale entièrement configurable (ADR-05) |
| FUS-FR33–FR36 | **22** | app RN (identité unique Bref Point), offline, modules |
| FUS-FR37 | **23** | mode legacy |
| FUS-MIG1–MIG5, MIG8–MIG10 | **24** | outil de migration et vagues |
| FUS-MIG6 | **24** (pré-migrateur OTA) | |
| FUS-MIG7 | **22** (dernière release Flutter) | |
| FUS-NFR1 | **transverse**, contrôlé à chaque story 19–21 | |
| FUS-NFR2, NFR4 | **transverse** (19–21, 24) | voir leur énoncé |
| FUS-NFR3 | **19** (story 19.11) | |
| FUS-NFR5, NFR6, NFR8 | **22** (NFR8 aussi 20.5) | |
| FUS-NFR9 | **20** (20.5, reporting) / **22** | fuseau par pays (ADR-01) |
| FUS-NFR11 | **transverse** | patterns et complexité, chaque spec et revue |
| FUS-NFR7, NFR10 | **24** | |
| FUS-UX1–UX3 | **22** | |
| FUS-UX4 | **23** / **24** | |
| FUS-UX5 | **23** |
| FUS-UX6, UX7 | **19** (19.13) / **22** (22.9) / **23** (23.10) | mêmes repères, modules nouveaux désactivés |
| FUS-UX8 | **22** (22.14) / **23** (23.11) | accueil au passage | |

---

## Epic List

### Epic 18 : Cadrage de la fusion (P0)
**Valeur :** décider sur des chiffres, pas sur des suppositions. Mesurer les bases, trancher les décisions ouvertes, suivre les évolutions de Bref (sans gel), sécuriser les identités d'app.
**Exigences :** prépare toutes les autres ; aucune FR livrée.
**Dépend de :** rien. **Bloque :** 19 (décisions Q10/Q12), 24 (mesures Q1–Q8).

### Epic 19 : Socle d'accueil — identité, rôles, plans, quotas (P1a)
**Valeur :** le backend Keevo sait accueillir un utilisateur Bref tel qu'il est : sans téléphone, connecté par OTP ou Google/Apple, avec ses rôles et son plan.
**Exigences :** FUS-FR1–FR8, FR11, FR12, FR17 (préférence), FUS-NFR1, FUS-NFR3.
**Dépend de :** Epic 16 (16-2, 16-3, 16-5, 16-8 — livrées sur la branche, pas besoin qu'elles soient en prod), story 18.2.

#### Story 19.14 : Rôles et permissions personnalisés par le propriétaire

As a **propriétaire**,
I want **créer mes propres rôles, y rattacher mes employés et choisir exactement leurs droits**,
So that **je configure mon équipe simplement, sans dépendre des rôles figés (OWNER / MANAGER / EMPLOYEE / VIEWER)**.

Scope (2026-11-08 user request): a per-tenant role editor — the owner creates a named role, picks permissions from the module catalogue (grouped by module, French labels, no technical codes on screen), assigns it to members, and can restrict it to stores. The existing fixed roles become built-in presets (editable copies), and every permission check (`@RequiresPermission`, sync handlers, pull scopes, document/expense/client scopes) reads the member's effective permissions. Absorbs the open fine-grained questions of 19-5b (product merge, quote end, sessions, ceilings) as configurable permissions instead of fixed decisions.

- [ ] [Decided 2026-11-08] Expenses today: owner + manager only (fix 20-10) — becomes a configurable permission
- [ ] [Decided 2026-11-08] Documents today: employee = own documents, manager/viewer = their store, owner = all (20-14) — becomes a configurable scope (own / store / all)

Interim AC: Given an owner who creates the role « Caissier » with « Créer des ventes » and « Voir les dépenses », When an employee with that role signs in, Then they can sell and read expenses and nothing else, on REST and sync alike.

## Epic 20 : Modules métier Bref dans Keevo — documents, catalogue, clients, dépenses, finance (P1b)
**Valeur :** tout ce qu'un commerçant Bref fait au quotidien existe dans Keevo, sans casser ce qu'un commerçant Keevo fait déjà.
**Exigences :** FUS-FR13–FR26.
**Dépend de :** 19.1, 19.5, 19.7, 19.8.

#### Story 20.13 : API super-admin héritée de Bref (vue plateforme, listes inter-comptes, utilisateurs)

As a **super-admin**,
I want **retrouver dans l'API Keevo tout ce que le dashboard Bref obtient aujourd'hui d'api-bref**,
So that **le dashboard super-admin (livré depuis un autre dépôt) n'appelle plus qu'une seule API (FUS-FR38)**.

**Périmètre (inventaire `api-bref` develop, 01/11/2026) :** `dashboard.controller.ts` (owner-summary, overview, growth, top-shops, activity) ; `customer-admin.controller.ts` (liste, détail, création, modification, consentement marketing) ; `product-admin.controller.ts` et `expense-admin.controller.ts` (listes inter-comptes) ; `users.controller.ts` côté admin (liste, stats, `:id/devices`, `:id/status`, `:id/plan`, suppression) ; `app-version.controller.ts` (recoupe 19.10) ; `plans.controller.ts` ; `campaign.controller.ts` (recoupe 21.6). Pour chaque route : équivalent Keevo existant (console 16-4, `/admin/tenants`, sessions 16-11, profils 16-8…), à étendre, ou nouveau ; contrat documenté dans la section « Dashboard super-admin (autre dépôt) » de la spec.

**Acceptance Criteria:**

**Given** la table de correspondance route Bref → route Keevo
**When** on la confronte à `api-bref/src/**/*.controller.ts` (develop)
**Then** chaque route admin Bref a un équivalent Keevo livré et testé, ou une décision d'abandon écrite par l'utilisateur ; les indicateurs plateforme (comptes, croissance, boutiques les plus actives, activité) couvrent les tenants Keevo et les owners migrés sans double comptage (R4)

---

## Epic 21 : Monétisation en libre-service et engagement (P1b)
**Valeur :** l'owner paie seul, Keevo encaisse et active ; les fonctions d'engagement de Bref (IA, WhatsApp Business, portail client, notifications, campagnes) sont reprises. L'IA passe par une couche d'outils unique, exposée aussi en MCP.
**Exigences :** FUS-FR9, FR10, FR27–FR32, FR39. Ordre IA : 21.8 (outils) → 21.4 (assistant) → 21.9 (MCP externe).
**Dépend de :** 19.7, 19.8, 19.9.

#### Story 21.8 : Couche d'outils IA sur les use cases (MCP-ready)

As a **plateforme**,
I want **définir une seule fois, sur les use cases Keevo, les outils que l'IA peut appeler**,
So that **l'assistant intégré et les clients MCP externes utilisent les mêmes règles métier, sans SQL généré (FUS-FR39)**.

**Acceptance Criteria:**

**Given** les use cases de lecture utiles aux questions (ventes et CA par période/boutique/vendeur, encaissements, impayés et liste d'appel, clients, produits et stock, dépenses et résultat, classement des vendeurs, comparaison de périodes)
**When** la couche est livrée
**Then** chaque outil est un bean `{Module}McpTools` dans `adapter/in/mcp/` (Spring AI `@Tool`), paramètres = records `…Query` existants (schéma JSON généré), description en français, résultat structuré sans identifiant technique inutile

**Given** un appel d'outil
**When** il s'exécute
**Then** acteur et tenant viennent du contexte de session (jamais des paramètres), le module est contrôlé comme `@RequiresEntitlement` (READ), le rôle et le périmètre employé (16-13) s'appliquent, l'appel est audité (outil, acteur, durée, sans contenu personnel)

**Given** le build
**When** les tests tournent
**Then** un test de couverture (équivalent de `EndpointEntitlementCoverageTest`) échoue si un outil n'a pas de module ou d'exemption justifiée, ou s'il expose un paramètre d'acteur/tenant ; un tableau des outils est généré (`docs/ai/tools.md`)

**Given** un outil d'écriture (plus tard)
**When** il est ajouté
**Then** il exige une confirmation explicite de l'utilisateur avant exécution (hors périmètre de cette story : lecture seule)

**Notes :** Spring AI compatible avec la version Spring Boot du backend à vérifier ; le placeholder `AuditMcpPlaceholder` et la règle 10 de l'architecture sont remplacés par le vrai adaptateur.

### Story 21.9 : Serveur MCP externe

As a **owner Premium**,
I want **brancher mon assistant IA (Claude, ChatGPT…) ou celui de mon comptable sur mes données Keevo**,
So that **je pose mes questions depuis l'outil de mon choix (FUS-FR39)**.

**Acceptance Criteria:**

**Given** le serveur MCP (Spring AI, transport HTTP/SSE)
**When** un client MCP se connecte
**Then** il s'authentifie par un jeton personnel créé par l'owner dans l'app (révocable, limité à des outils, expirant), chaque requête passe par les mêmes contrôles que 21.8 et compte dans le quota IA du tenant

**Given** le module payant `mcp-access` désactivé (Free)
**When** un client se connecte
**Then** refus `MODULE_NOT_ENTITLED` ; aucune donnée exposée

**Given** l'owner révoque le jeton
**When** le client rappelle
**Then** refus immédiat (même mécanisme que les sessions 16-11)

## Epic 22 : App React Native — fondations et parité Keevo (P2)
**Valeur :** les utilisateurs Keevo passent sur la nouvelle app par une simple mise à jour, sans perte, avec un hors-ligne au moins aussi fiable qu'en Flutter.
**Exigences :** FUS-FR33–FR36, FUS-MIG7, FUS-NFR5, NFR6, NFR8, FUS-UX1–UX3.
**Dépend de :** 18.4 (accès aux comptes stores Bref Point, ADR-02), 19.2 (login), contrats `keevo/contracts/`.

### Epic 23 : App React Native — parité Bref (P3)
**Valeur :** un owner Bref retrouve dans l'app unifiée tous ses parcours, et l'app peut être publiée sous `com.brefpoint.app`.
**Exigences :** FUS-FR9 (écrans), FR17–FR22 (écrans), FR25–FR29 (écrans), FR36, FR37, FUS-UX2, UX4, UX5.
**Dépend de :** 22 (fondations), 20, 21.

### Epic 24 : Migration Bref par vagues (P1c + P4)
**Valeur :** chaque owner Bref et ses données arrivent dans Keevo, vérifiés au franc près, avec retour arrière possible jusqu'à l'activation.
**Exigences :** FUS-MIG1–MIG6, MIG8–MIG10, FUS-NFR7, NFR10.
**Dépend de :** 19, 20, 21 (cibles d'import) ; 23 (app à proposer aux migrés) avant la première vague.

### Epic 25 : Convergence et décommissionnement (P5)
**Valeur :** une app, un backend, une facture d'hébergement ; plus de code transitoire.
**Dépend de :** 24 terminé (aucun owner actif sur api-bref), 22.19 (Flutter retiré).

### Ordonnancement

```
Epic 16 (socle, branche) ─────┐   GL = 19–21 + app RN à parité (22.1–22.13, 23.1–23.11) + outil 24.1–24.10/24.12 ──► 17.9 + 16.7 GO-LIVE ──► 22.14–22.16, 23.12, 24.11, vagues 24.13–24.16
Epic 18 (P0) ─────────────────┼──► Epic 19 (P1a) ──► Epic 20 + 21 (P1b) ──► Epic 24.1–24.10 (outil, dry-run)
                              │          │                                          │
                              │          └─(+4 sem)──► Epic 22 (P2, app RN Keevo) ──┤
                              │                              │                      │
                              │                              └──► Epic 23 (P3) ─────┴──► Epic 24.11–24.16 (vagues, P4)
                              │                                                                   │
                              └───────────────────────────────────────────────────────────────────┴──► Epic 25 (P5)
```

**Point de non-retour** : story 24.15 (première vague pilote activée). Avant, toute la fusion peut être suspendue sans impact utilisateur.

---

## Epic 18 : Cadrage de la fusion (P0)

> Aucune ligne de code produit. Sortie : un dossier de mesures, des décisions écrites, un journal des évolutions de Bref (Bref n'est pas gelé : décision du 03/11/2026). Durée : 2 semaines.

### Story 18.1 : Mesurer les bases Bref et Keevo (Q1–Q9, Q13)

As a **responsable de la fusion**,
I want **des chiffres exacts sur les owners, données, identités et abonnements des deux bases**,
So that **le dimensionnement, l'ordre des vagues et les décisions produit reposent sur des faits**.

**Acceptance Criteria:**

**Given** un réplica (ou dump restauré en local) de la base api-bref de production et de la base Keevo de production
**When** le script de mesures en lecture seule est exécuté
**Then** il produit un rapport daté contenant : owners total/actifs < 90 j/payants par plan et par moyen de paiement (Q1), volumes par table (Q2), owners et membres sans téléphone (Q3), intersection des téléphones normalisés E.164 Bref ∩ Keevo (Q4), boutiques hors CM et TVA ≠ 19,25 %, montants non entiers (Q5), crédits et packs SMS restants (Q6), factures dont `total` ≠ recalcul (Q7), owners `plan ≠ free` sans abonnement en vigueur (Q8)

**Given** les historiques de déploiement (GHCR, consoles stores)
**When** on les consulte
**Then** le rapport indique la date de mise en production de `develop` sur api-bref (Q13) et l'identité réelle de l'app Keevo publiée : `applicationId`, signature, comptes développeur Google/Apple disponibles (Q9, R11)

**Implementation Notes:**
- Scripts SQL versionnés dans `_bmad-output/planning-artifacts/research/fusion-mesures/` (aucun accès en écriture, aucune donnée personnelle dans le rapport : agrégats seulement).
- Normalisation téléphone : réutiliser la logique de `PhoneNumbers.normalize` (Keevo) appliquée aux deux côtés pour Q4.

### Story 18.2 : Trancher les décisions produit ouvertes (gate P0)

As a **owner produit**,
I want **une décision écrite pour chaque question ouverte**,
So that **aucune story ne démarre sur une hypothèse non validée**.

**Acceptance Criteria:**

**Given** le rapport 18.1
**When** la session de décision est tenue
**Then** un ADR par décision est ajouté en annexe de ce document : Q10 (plan FREE unifié, trial, grille), Q11 (marque), Q12 (pays), §7.10 (grâce 30 j ou FREE), §7.5 (clients tenant), §9.5 (publication précoce sous `com.brefpoint.app`), et le champ `status` du frontmatter passe à `ready`

**Given** une décision qui contredit une recommandation de l'analyse
**When** l'ADR est rédigé
**Then** les stories impactées (tag ⚠️) sont listées et mises à jour dans ce document avant leur passage en `ready-for-dev`

### Story 18.3 : Suivre les évolutions de Bref pendant la fusion

> **Décision du 03/11/2026 (owner produit) : pas de gel de Bref.** Bref est en production et reçoit de temps en temps des correctifs mineurs ; il continue d'évoluer pendant toute la fusion. La cible bouge donc : on la **suit** au lieu de la figer.

As a **responsable de la fusion**,
I want **voir chaque évolution livrée sur Bref et savoir ce qu'elle change pour la fusion**,
So that **ce que Keevo reproduit et ce que l'importeur lit restent alignés avec le Bref réellement en production**.

**Acceptance Criteria:**

**Given** les dépôts `AI/bref` et `AI/api-bref`
**When** la story démarre
**Then** le commit de référence de `main` (ce qui tourne en prod) de chaque dépôt est noté avec sa date (aucun tag ni règle imposés aux dépôts Bref), et le journal est ouvert dans `research/bref-evolutions/journal-evolutions-bref.md` avec son script de contrôle `check-bref.sh`

**Given** un merge sur `main` d'un dépôt Bref (déployé en prod) depuis le dernier commit journalisé
**When** la revue est faite (**au début de chaque session de travail sur la fusion** — décision du 03/11/2026 —, et au moins à chaque début de story des epics 20, 21, 23 et 24 et avant chaque vague de migration)
**Then** chaque commit est classé dans le journal : *sans impact* (texte, style, correctif local), *règle métier* (calcul, statut, quota, plan, TVA, numérotation), *schéma* (table, colonne, type, contrainte), *API/app* (contrat, écran, parcours)

**Given** un commit classé *règle métier* ou *API/app*
**When** il est journalisé
**Then** une story miroir est ajoutée (Epic 20/21 pour le backend, 23 pour l'app RN) ou la story existante concernée est mise à jour, et son identifiant est noté dans le journal

**Given** un commit classé *schéma*
**When** il est journalisé
**Then** l'importeur (Epic 24), le kit de mesures 18.1 (`research/fusion-mesures/`, gardes « objet absent ») et la réconciliation sont mis à jour ou une story Epic 24 est ouverte

**Implementation Notes:**
- Le gel **par owner** pendant sa migration (24.1, 423 `ACCOUNT_MIGRATING`, < 15 min, FUS-NFR7) n'est pas concerné : il ne touche que l'owner en cours de migration.
- La seule demande faite à l'équipe Bref reste l'ajout des endpoints de l'Epic 24 (statut de migration, gel d'un owner, export, pré-migrateur) ; elle ne limite pas leurs autres correctifs.

---

## Epic 19 : Socle d'accueil — identité, rôles, plans, quotas (P1a)

> Tout est **additif** (FUS-NFR1) : l'app Flutter en production doit continuer de fonctionner sans mise à jour. Chaque story vérifie la non-régression avec la suite Flutter et les `curl-tests-story-*.sh` concernés.

### Story 19.1 : Étendre `public.users` (identifiants multiples, profil)

As a **plateforme**,
I want **un compte utilisateur qui accepte téléphone, email ou username comme identifiant**,
So that **les comptes Bref email-only et les membres sans téléphone puissent exister dans Keevo**.

**Acceptance Criteria:**

**Given** la migration Flyway `public`
**When** elle s'applique sur une copie de prod Keevo
**Then** `phone_number` et `password_hash` deviennent nullables, `email` (UNIQUE, nullable, insensible à la casse), `username` (UNIQUE, nullable, insensible à la casse), `first_name`, `last_name`, `email_verified_at`, `phone_verified_at` sont ajoutés, une contrainte CHECK impose au moins un identifiant, et aucune ligne existante n'est modifiée

**Given** un utilisateur Keevo existant
**When** il se connecte avec l'app Flutter actuelle
**Then** le comportement est identique (téléphone + mot de passe, `select-tenant`)

**Given** les DTO exposés à l'app Flutter
**When** un champ est nullable côté base
**Then** les réponses existantes ne renvoient jamais `null` là où Flutter attend une valeur (champ omis ou chaîne vide documentés)

### Story 19.2 : Authenticator OTP (SMS, WhatsApp, email)

As a **commerçant**,
I want **me connecter avec un code reçu par SMS, WhatsApp ou email**,
So that **je n'ai pas besoin de mot de passe (parcours Bref)**.

**Acceptance Criteria:**

**Given** un identifiant (téléphone E.164 ou email)
**When** `POST /api/v1/auth/otp/request` est appelé
**Then** un code à 6 chiffres est envoyé par le canal demandé, stocké haché avec expiration (5 min), compteur d'essais en base (5 max), limitation par identifiant et par IP, et la réponse ne révèle pas si le compte existe

**Given** un code valide
**When** `POST /api/v1/auth/otp/verify` est appelé
**Then** la réponse suit le login 2 étapes Keevo (`loginToken` puis `select-tenant`, ou tokens directs si un seul tenant) ; si le compte n'existe pas, un compte est créé sans tenant et l'app enchaîne sur la création d'entreprise ; `phone_verified_at`/`email_verified_at` est renseigné

**Given** un téléphone hors des préfixes autorisés par la décision Q12
**When** un code est demandé
**Then** la demande est refusée avec `PHONE_COUNTRY_NOT_SUPPORTED`

- [ ] [Deferred-from: review 19-1, 2026-11-03] recherche par email : `findByEmail` via `lower(email) = lower(?)` (index `uq_users_email_lower`) et erreur métier en cas de violation d'unicité à l'enregistrement.

**Implementation Notes:** porter la normalisation et le décompte d'échecs de `api-bref/src/otp/*` ; réutiliser `WhatsAppMessageService` existant pour le canal WhatsApp ; SMS/email via 19.9.

### Story 19.3 : Authenticators Google et Apple

As a **commerçant**,
I want **me connecter avec Google ou Apple**,
So that **mon compte Bref social fonctionne dans l'app unifiée**.

**Acceptance Criteria:**

**Given** un `idToken` Google (audiences Android/iOS/web configurées) ou Apple (JWKS, `aud` = bundle(s) autorisés)
**When** `POST /api/v1/auth/social/{google|apple}` est appelé
**Then** le token est vérifié côté serveur, le compte est trouvé par `(provider, social_id)`, sinon lié par email **uniquement** si l'email est vérifié et que le compte trouvé n'est pas uniquement membre (FUS-FR6), sinon créé ; la suite suit le login 2 étapes

**Given** Apple qui ne renvoie l'email qu'au premier consentement
**When** le compte existe déjà avec ce `social_id`
**Then** l'absence d'email n'empêche pas la connexion

**Given** les deux bundles (Keevo, `com.brefpoint.app`)
**When** l'un ou l'autre envoie un token
**Then** les deux audiences sont acceptées

### Story 19.3b : Comptes Google/Apple — nonce, liaison et retrait, révocation Apple

> **Créée le 2026-11-04** (revue 19.3).

- [ ] [Deferred-from: revue 19.3, 2026-11-04] nonce : l'app envoie un nonce haché à Google/Apple et le nonce clair au backend, qui vérifie la claim `nonce` (rejeu d'un jeton capturé jusqu'à son expiration) — à faire avec 23.1 côté app.
- [ ] [Deferred-from: revue 19.3, 2026-11-04] endpoints authentifiés pour lier, lister et retirer Google/Apple (aujourd'hui un 409 `EMAIL_ALREADY_EXISTS` n'a pas d'issue : membre ou email non prouvé).
- [ ] [Deferred-from: revue 19.3, 2026-11-04] suppression de compte : révoquer le jeton Sign in with Apple (`appleid.apple.com/auth/revoke`, exigence App Store) — demande la clé privée Apple (client secret) en configuration [AccountDeletionService].

**Critère provisoire :** **Given** un compte lié à Apple **When** il est supprimé **Then** la révocation Apple est appelée et les liens `user_authenticators` disparaissent.

### Story 19.4 : Connexion par username pour les membres

As a **membre d'équipe ex-Bref**,
I want **me connecter avec l'identifiant et le mot de passe que mon patron m'a donnés**,
So that **je n'ai rien à changer après la migration**.

**Acceptance Criteria:**

**Given** un compte avec `username` et `password_hash` bcrypt (`$2a$`/`$2b$`, coût 12)
**When** `POST /api/v1/auth/login` reçoit `{username, password}`
**Then** `BCryptPasswordEncoder` valide le hash sans réécriture forcée, et la suite suit le login 2 étapes

**Given** un owner
**When** il crée un membre (`POST /employees`) avec un username sans téléphone
**Then** le membre est créé ; l'unicité du username est globale ; le mot de passe temporaire n'est renvoyé qu'une fois (FUS-FR5)

- [ ] [Deferred-from: review 19-1, 2026-11-03] recherche par identifiant : `findByUsername` via `lower(username) = lower(?)` (index `uq_users_username_lower`) ; désambiguïsation à la connexion entre un identifiant tout en chiffres (autorisé par la règle Bref `^[A-Za-z0-9._-]{1,50}$`) et un téléphone saisi sans « + ».


**Éléments reportés routés vers 19.4 (revue 19.3, 2026-11-04) :**
- [ ] [Deferred-from: revue 19.3, 2026-11-04] réserver les préfixes `google-` et `apple-` (usernames techniques des comptes sociaux sans email, SocialSignInService) : un username choisi ne doit pas pouvoir les prendre, sinon la première connexion sociale de la victime échoue.

### Story 19.5 : Rôles MANAGER et VIEWER, permissions par rôle

As a **owner**,
I want **donner à mes membres un rôle gérant, vendeur ou lecteur**,
So that **chacun voit et fait seulement ce qui le concerne (équivalent admin/seller/viewer Bref)**.

**Acceptance Criteria:**

**Given** l'enum de rôle de membership
**When** la migration s'applique
**Then** MANAGER et VIEWER s'ajoutent à OWNER/EMPLOYEE, sans modifier les memberships existantes

**Given** `KeevoModule.employeePermissions()`
**When** il est généralisé en `permissionsFor(Role)`
**Then** chaque manifeste déclare les permissions par rôle ; VIEWER n'a aucune permission d'écriture ; MANAGER = EMPLOYEE + gestion de l'équipe de sa boutique + lecture CRM/produits ; `ModulePermissions` valide au boot que chaque rôle a une déclaration explicite par module

**Given** un VIEWER
**When** il appelle n'importe quel endpoint d'écriture ou pousse une opération de sync
**Then** il reçoit 403 `FORBIDDEN` (HTTP) ou `REJECTED/FORBIDDEN` (sync), vérifié par un test paramétré sur tous les endpoints de `EndpointEntitlementCoverageTest`

**Given** le profil `/auth/profile` (le rôle n'est plus dans le jeton, refonte 16.11)
**When** l'app Flutter actuelle reçoit un rôle MANAGER ou VIEWER
**Then** elle ne plante pas (rôle inconnu traité comme EMPLOYEE en lecture) — test sur le parseur Flutter

### Story 19.5b : Droits fins du gérant et du lecteur (décisions produit)

> **Routé depuis la revue 20.1 (06/11/2026) :** - [ ] [Deferred-from: revue 20.1, 2026-11-06] La fusion de produits (`POST /api/v1/products/{id}/merge`) est irréversible mais ouverte aux mêmes rôles que l'archivage, vendeur compris. Décider si elle est réservée au propriétaire (et au gérant) [keevo/backend/src/main/java/com/keevo/catalog/product/adapter/in/web/ProductController.java].

> **Créée le 2026-11-05** (revue 19.5). La 19.5 applique la règle « jamais moins restreint qu'un vendeur » ; ces points demandent une décision produit avant d'être codés.

- [ ] [Deferred-from: revue 19.5, 2026-11-05] MANAGER : voir les ventes, l'historique et les sessions d'inventaire de toute sa boutique (aujourd'hui seulement les siennes, comme un vendeur) et pouvoir annuler une session d'un de ses vendeurs [SaleHistoryController, GetSalesHistoryService, CancelInventorySessionService].
- [ ] [Deferred-from: revue 19.5, 2026-11-05] VIEWER : lectures en POST (demandes d'export, aperçus) refusées par la règle « jamais d'écriture » ; et historique des ventes vide (il ne vend pas) — définir ce qu'un lecteur doit voir [ViewerWriteGuardFilter, SaleHistoryController].
- [ ] [Deferred-from: revue 19.5, 2026-11-05] MANAGER : sessions d'appareils de ses vendeurs (`/employees/{id}/sessions`) toujours réservées au patron ; réactivation par le gérant d'un membre désactivé par le patron (alerte au patron ?) [DeviceSessionController, EmployeeTeamScope].
- [ ] [Deferred-from: revue 19.5, 2026-11-05] MANAGER et VIEWER comptent dans le plafond d'employés du plan (`UserCountAdapter` compte toute ligne active) — confirmer la règle commerciale.

**Critère provisoire :** **Given** les décisions validées par l'utilisateur **When** un gérant ou un lecteur utilise l'app **Then** il voit et fait exactement ce qui est décidé, testé par rôle.

- [ ] [Deferred-from: review 20-3, 2026-11-06] Who may end a quote: delete needs INVOICE_CREATE while cancel needs INVOICE_MANAGE (owner), so an employee can delete but not cancel with a reason [InvoicingModule permissions] — decide the permission for cancelling a never-numbered quote

- [x] [Deferred-from: review 20-10, 2026-11-08] (decided 2026-11-08: owner-only like Bref — fix(20-10)) Who may see and record expenses: Bref keeps expenses owner-only (« elles révèlent les marges »), Keevo 20.10 gives MANAGER/EMPLOYEE VIEW+CREATE and VIEWER VIEW on their store (manifest `keevo/modules/expenses/module.json` rolePermissions) — owner decision: keep, or restrict to owner/manager

- [x] [Deferred-from: review 20-7, 2026-11-08] (decided 2026-11-08: an employee acts only on the documents they created, like Bref — 20-14) An EMPLOYEE may rotate a document's public link (INVOICE_SEND) — owner decision: keep or restrict to owner/manager
- [x] [Deferred-from: review 20-14, 2026-11-08] (decided 2026-11-08: employee = own documents, manager/viewer = their store, owner = all, like Bref — 20-14) Invoice reads (`GET /invoices`, pull) are not filtered by store for store members, although creation is store-scoped — decide whether members see only their store's documents

### Story 19.6 : Une personne, plusieurs rôles dans plusieurs entreprises

As a **commerçant qui utilise Keevo et Bref**,
I want **un seul compte pour mes deux entreprises, ou pour être patron ici et employé là**,
So that **la réconciliation d'identité ne me force pas à choisir**.

**Acceptance Criteria:**

**Given** un utilisateur existant (par téléphone, email ou username)
**When** un owner l'ajoute comme membre
**Then** une membership est ajoutée au compte existant au lieu de lever `USER_ALREADY_EXISTS` ; l'utilisateur est notifié ; son mot de passe n'est pas modifié

**Given** un utilisateur avec deux memberships
**When** il se connecte
**Then** le tenant-picker liste les deux entreprises avec leur rôle

**Éléments reportés routés vers 19.6 (revue 19.2, 2026-11-04) :**
- [ ] [Deferred-from: revue 19.2, 2026-11-04] `POST /auth/create-company` refuse (`COMPANY_ALREADY_EXISTS`) un utilisateur dont `users.role` n'est pas OWNER, car `ChangePasswordService` émet les jetons avec ce rôle global [CreateCompanyService.java] — quand le rôle sera porté par la membership (plusieurs rôles), autoriser un employé à créer sa propre entreprise.
- [ ] [Deferred-from: revue 19.3, 2026-11-04] la liaison Google/Apple par email refuse un compte dont `users.role` n'est pas OWNER (règle « pas uniquement membre », FUS-FR6) [SocialSignInService.link] — avec plusieurs rôles par personne, juger sur les memberships (au moins une OWNER, ou aucune) plutôt que sur le rôle global.

### Story 19.6b : Ajout d'un compte existant — consentement, confidentialité, récupération

> **Créée le 2026-11-05** (revue 19.6). La 19.6 rattache immédiatement un compte existant ajouté par un patron ; ces points demandent une décision produit.

- [ ] [Deferred-from: revue 19.6, 2026-11-05] consentement : rattachement immédiat (19.6) ou invitation acceptée par la personne ; aujourd'hui tout patron peut savoir si un téléphone/identifiant a un compte (`existingAccount`) et y rattacher la personne sans son accord, et chaque essai envoie un message [CreateEmployeeService, MemberAddedNotifier].
- [ ] [Deferred-from: revue 19.6, 2026-11-05] confidentialité : un patron qui ajoute par identifiant voit ensuite le téléphone du compte (et inversement) dans la fiche et la liste [EmployeeRoleResolver].
- [ ] [Deferred-from: revue 19.6, 2026-11-05] récupération : un membre partagé avec seulement un identifiant (sans téléphone ni email vérifié) qui oublie son mot de passe n'a aucun recours (le patron ne peut plus le réinitialiser) [SharedAccountGuard].

**Critère provisoire :** **Given** la décision de l'utilisateur **When** un patron ajoute un compte existant **Then** le parcours (direct ou invitation), les données visibles et la récupération suivent cette décision.

### Story 19.7 : Profils PRO, TEAM, BUSINESS et bundles associés (ADR-03, 04, 06)

> **Découpage du 03/11/2026 (taille mesurée : `PlanType` dans 80 fichiers de production et 128 de test, 9 points qui cassent sur un code inconnu, contraintes CHECK dans `public` et dans chaque schéma tenant, essai de 6 mois écrit en SQL).** La story est livrée en deux parties, sans changer son périmètre :
> - **19.7a — Plans en données (`19-7a-plans-en-donnees`)** : catalogue des plans en base (code, libellé, description, ordre, visibilité, archivage, code de compatibilité V1), `plan_profiles` indexé par code de plan, suppression de l'énumération `PlanType` comme liste fermée (valeur métier « code de plan » lue dans le catalogue) et des CHECK sur les codes (`plan_profiles`, `tenant_plan_events`, `subscriptions` des schémas tenant via une migration de module V2), les 9 lectures `valueOf`/`values()` passent par le catalogue, correspondance configurable vers `FREE`/`PREMIUM_TRIAL`/`PREMIUM` pour les apps V1 (`GET /subscription/me`, `PLAN_LIMIT_EXCEEDED.details.plan`), durée d'essai lue en base au lieu du SQL figé. **Comportement identique** avec les trois codes actuels (non-régression complète).
> - **19.7b — Plans de la fusion (`19-7b-plans-fusion`)** : données de départ FREE, PRO, TEAM, BUSINESS (ADR-05), passage des tenants PREMIUM à TEAM au même prix jusqu'à la fin de la période payée (ADR-06, événement de plan tracé), FREE à 1 employé avec droit acquis (ADR-03), essai d'1 mois du plan le plus complet pour tout nouvel inscrit, porté par un plan de nature « essai » `BUSINESS_TRIAL` (« Essai Business », profil = BUSINESS) — choix de conception du 04/11 : un indicateur d'essai sur l'abonnement aurait dédoublé la nature du plan dans une quinzaine d'endroits (revenus, audiences, événements), PREMIUM et PREMIUM_TRIAL archivés, essais PREMIUM_TRIAL en cours menés à terme (ADR-04), libellés français et revenus (`tenant_plan_events`) alignés, correspondance V1 étendue (un plan payant → `PREMIUM`, un essai → `PREMIUM_TRIAL`, FREE → `FREE`).
>
> **Éléments reportés routés vers 19.7b (revue 19.7a, 2026-11-04) :**
> - [ ] [Deferred-from: revue 19.7a, 2026-11-04] « Activer Premium » (`AdminPlanService.activatePremium`) attribue encore la constante `PlanType.PREMIUM` — le plan payant attribué doit devenir un paramètre (ou un réglage), autorisé seulement par la liste blanche du test `PlanCodeLiteralGateTest` jusque-là.
> - [ ] [Deferred-from: revue 19.7a, 2026-11-04, préexistant] L'activation d'un plan par le super-admin n'impose pas `expiresAt` pour un plan d'essai ou payant (ni son absence pour un plan gratuit) : un plan payant sans date n'expire jamais — valider date ↔ nature du plan (422, message français).
> Les critères ci-dessous restent ceux de l'ensemble ; chaque spec dit lesquels elle couvre.



As a **super-admin**,
I want **des plans qui sont des données, avec les plans Bref créés au départ**,
So that **chaque owner migré retrouve exactement ses droits et que la grille soit unique**.

**Acceptance Criteria:**

**Given** `plan_profiles`/`profile_modules` (16-8), aujourd'hui limités aux trois valeurs de l'énumération `PlanType`
**When** la story est livrée
**Then** un plan est identifié par un code en base (plus de liste fermée de plans dans le code ni de contrainte CHECK sur les codes) ; un plan créé depuis l'API super-admin est utilisable tout de suite par l'inscription, les paiements, les gardes de plafonds et les modules ; les apps V1 2.0.1 continuent de recevoir FREE / PREMIUM_TRIAL / PREMIUM via une correspondance configurable (compatibilité)

**Given** le catalogue vide de plans de fusion
**When** les données de départ s'appliquent
**Then** les profils FREE, PRO, TEAM, BUSINESS existent avec modules et plafonds issus de l'ADR Q10 (base : free = 1 boutique / 1 membre / 300 documents par mois / 5 SMS / 100 emails ; PRO + dépenses, finance ; BUSINESS + campagnes, modèles, boîte WhatsApp) et les tenants PREMIUM existants sont rattachés à TEAM sans perte de module ni changement de prix avant la fin de leur période payée (ADR-06)

**Given** un tenant Keevo FREE qui a déjà plus d'1 employé actif (ADR-03)
**When** le profil FREE à 1 employé s'applique
**Then** aucun employé n'est désactivé ; l'ajout d'un employé est refusé (`PLAN_LIMIT_EXCEEDED`) tant que le tenant reste au-dessus de la limite

**Given** un nouvel inscrit (Keevo ou Bref) (ADR-04)
**When** son compte est créé
**Then** il reçoit 1 mois d'essai du plan le plus complet puis FREE ; les essais PREMIUM_TRIAL en cours vont à leur terme

**Given** les limites membres de Bref (par boutique)
**When** un owner ajoute un membre au-delà de la limite de son profil
**Then** il reçoit `PLAN_LIMIT_EXCEEDED` avec la limite et l'usage

**Given** l'app Flutter actuelle
**When** elle lit `/tenants/me` (plan)
**Then** un tenant PRO/TEAM/BUSINESS est présenté comme Premium (compatibilité)

### Story 19.8 : Compteurs d'usage et quotas atomiques

As a **plateforme**,
I want **décompter documents, SMS, emails et requêtes IA par tenant et par mois**,
So that **les quotas Bref sont appliqués sans course entre appareils**.

> **Routé depuis 19.7b (04/11/2026) :** le quota de documents (FREE : 300/mois) est livré ici ; l'**octroi du module `invoicing` au plan FREE part en 19.13** (décision de conception du 04/11 : accordé maintenant, il apparaîtrait dans les apps V1 des tenants FREE et chaque vente caisse créerait une facture automatique, contraire à FUS-UX7) ; les quotas SMS (5/20/50/80), emails (100/∞) et crédits IA (0/500/750/∞ par mois, 0/20/30/50 par jour) de `plan-policy.constant.ts` sont les valeurs de départ des profils.

**Acceptance Criteria:**

**Given** `public.usage_counters(tenant_id, metric, period, used)` et les limites du profil
**When** `UsageQuotaService.assertAndIncrement(tenant, metric, n)` est appelé en concurrence (10 threads)
**Then** une seule requête `UPDATE … SET used = used + n WHERE used + n <= limit RETURNING used` garantit qu'aucun dépassement n'a lieu (test d'intégration Testcontainers)

**Given** un dépassement
**When** l'opération est une écriture du cœur (vente caisse, paiement)
**Then** elle n'est jamais bloquée par un quota ; seuls documents (au-delà du plafond FREE), SMS, emails et IA le sont, avec `QUOTA_EXCEEDED {metric, limit, resetAt}`

**Given** une opération de sync rejouée (`DUPLICATE`)
**When** elle était déjà décomptée
**Then** le compteur n'est pas incrémenté une seconde fois

**Given** les packs SMS achetés (crédit hors période)
**When** le quota mensuel est épuisé
**Then** le pack est consommé ensuite

### Story 19.9 : Fournisseurs SMS et email, notifications persistantes

As a **plateforme**,
I want **envoyer des SMS (MboaSMS, Infobip) et des emails (Resend), et conserver les notifications in-app**,
So that **OTP, envoi de documents et notifications Bref fonctionnent dans Keevo**.

> **Routé depuis la revue 19.8 (04/11/2026) :** - [ ] [Deferred-from: revue 19.8, 2026-11-04] décompter un SMS ou un email par `UsageQuotaPort.assertAndIncrement` dans une transaction courte **après** la confirmation du fournisseur, jamais autour de l'appel HTTP (sinon chaque envoi du tenant attend sur la ligne de compteur pendant l'appel réseau).

**Acceptance Criteria:**

**Given** les ports `SmsSender` et `EmailSender` du module `messaging`
**When** un fournisseur échoue
**Then** le suivant est essayé (ordre configurable), l'échec est journalisé sans secret, et le quota n'est décompté qu'en cas de succès

**Given** les secrets des fournisseurs
**When** l'application démarre en profil prod sans eux
**Then** elle échoue au démarrage (fail-fast 10-3) seulement si le canal est activé

**Given** `notifications` (par tenant : user, type, titre, corps, données, lu_at)
**When** un événement métier notifiable survient
**Then** une notification est persistée, exposée par `GET /notifications` (paginé, compteur non lus) et `PATCH /notifications/{id}/read`, et un push FCM est envoyé si un token existe

### Story 19.9b : Canaux Bref — WhatsApp plateforme par Meta Cloud API, Web Push

> **Créée le 04/11/2026** (décision utilisateur pendant 19.9 : « pour tout ce qui est notification, WhatsApp, on fera comme Bref — les fournisseurs de Bref dominent »). La 19.9 livre déjà SMS (MboaSMS, Infobip), email (Resend, Infobip) et le push Expo ; restent :

- [ ] WhatsApp plateforme par Meta WhatsApp Cloud API (`graph.facebook.com/v21.0/{phoneNumberId}/messages`, modèles système Bref `bref_point_otp`, `bref_facture` — `api-bref/src/whatsapp/whatsapp.service.ts`, `system-templates.ts`) comme premier fournisseur de `WhatsAppSender` ; Wassender/Twilio en repli tant que les envois en texte libre de Keevo (rapport quotidien, alertes) n'ont pas de modèle Meta approuvé.
- [ ] Modèles Meta pour les messages Keevo envoyés hors fenêtre de 24 h (rapport quotidien, rappels d'abonnement, alertes) ou bascule de ces envois vers push/inbox ; liste et libellés à valider.
- [ ] [Deferred-from: revue 19.9, 2026-11-04] reçus Expo (`getReceipts`) : les `DeviceNotRegistered`/`MessageRateExceeded` signalés après coup ne sont pas lus, les jetons Expo périmés s'accumulent [`messaging/notification/adapter/out/ExpoPushAdapter.java`] — relever les reçus par lot (job court) et supprimer les jetons en un ordre SQL.
- [ ] Web Push (VAPID) comme Bref : `GET /web-push/vapid-public-key`, `POST/DELETE /web-push/subscribe`, abonnements par utilisateur, suppression sur 404/410 (`api-bref/src/web-push/*`), branché sur le composite de push de la 19.9.

> **Livraison 19.9b (04/11/2026) :** WhatsApp plateforme par Meta pour les codes (`bref_point_otp`) ; `bref_facture` part du numéro de l'owner chez Bref → routé vers 21.5 ; modèles des messages Keevo en texte libre → 19.9c.

**Critère provisoire :** **Given** `keevo.whatsapp.provider` réglé sur Meta avec ses secrets **When** un OTP ou une facture part par WhatsApp **Then** le message passe par le modèle Meta Bref, le repli n'intervient qu'en échec, et le fail-fast 10-3 exige les secrets Meta seulement si le canal est activé.

### Story 19.9c : Modèles Meta pour les messages WhatsApp de Keevo

> **Créée le 04/11/2026** (découpage de la 19.9b). Les messages WhatsApp en texte libre de Keevo (rapport quotidien et hebdomadaire, rappels et changements d'abonnement, alertes de stock, de silence de synchro, suppression de compte) partent encore par Wassender/Twilio : Meta n'accepte le texte libre que dans les 24 h suivant un message du destinataire. Il faut, pour chacun, soit un modèle Meta approuvé (catégorie UTILITY), soit une bascule vers push + boîte de notifications.

- [ ] [Split-from: 19.9b, 2026-11-04] liste des messages et libellés des modèles à valider par l'utilisateur, puis soumission à Meta (Business Manager) ; ensuite envoi par modèle via la chaîne `WhatsAppProvider` de la 19.9b, texte libre Wassender/Twilio en repli.

**Critère provisoire :** **Given** un modèle Meta approuvé pour un message Keevo **When** ce message part **Then** il passe par le modèle Meta et le repli Wassender/Twilio n'intervient qu'en échec.

### Story 19.10 : Versions minimales d'app

As a **super-admin**,
I want **imposer une version minimale par plateforme et par identité d'app**,
So that **je peux retirer Flutter et l'ancienne app Bref en temps voulu (FUS-FR31)**.

**Éléments reportés routés vers 19.10 (revue 19.5, 2026-11-05) :**
- [ ] [Deferred-from: revue 19.5, 2026-11-05] un VIEWER sur l'app V1 2.0.1 voit l'interface d'un vendeur (rôle affiché EMPLOYEE) ; ses ventes saisies hors ligne sont refusées à la synchro (REJECTED/FORBIDDEN, définitif) et donc perdues — imposer une version minimale aux comptes MANAGER/VIEWER, ou refuser leur connexion depuis V1 avec un message clair.

**Acceptance Criteria:**

**Given** `public.app_versions(app_identity, platform, min_version, latest_version, store_url, message)`
**When** `GET /api/v1/app-version/check?identity=&platform=&version=` est appelé sans authentification
**Then** la réponse indique `{updateRequired, updateAvailable, storeUrl, message}`, et la page dashboard permet de modifier ces valeurs avec audit

### Story 19.11 : Tenir la charge d'un grand nombre de tenants (R1)

> **Routé depuis la revue 19.9 (04/11/2026) :** - [ ] [Deferred-from: revue 19.9, 2026-11-04] rétention de la boîte de notifications : aucune purge de `kv_x.notifications` (une ligne par destinataire et par événement, pour toujours ; lignes orphelines quand un utilisateur quitte le tenant, `user_id` sans FK) [`db/modules/messaging/V2__notifications.sql`, `JdbcNotificationInboxAdapter.java`] — purge ensembliste par tenant (ex. lues > 90 j) dans le job multi-tenant de cette story, et suppression des lignes d'un utilisateur retiré.

As a **exploitant**,
I want **un backend qui démarre et migre vite avec le nombre de tenants mesuré en P0 × 2**,
So that **l'arrivée des owners Bref ne dégrade pas le service (FUS-NFR3)**.

**Acceptance Criteria:**

**Given** le DDL tenant encore produit par `TenantSchemaProvisioner` + `TenantSchemaSyncService` au boot
**When** la story est livrée
**Then** le DDL des tables du cœur est sous Flyway par module (item deferred 16-5), `TenantSchemaSyncService` n'est plus appelé au boot, et un test de charge sur staging avec N schémas (N = mesure Q1 × 2) mesure : démarrage < 2 min, provisioning d'un tenant < 5 s, application d'une migration de module à tous les schémas < 10 min, durée de sauvegarde/restauration

**Given** un résultat de charge hors objectif
**When** le rapport est rédigé
**Then** une décision d'architecture (pool de connexions, migrations paresseuses par tenant, schéma partagé pour les tenants FREE inactifs) est actée avant l'Epic 24

### Story 19.11b : Écrans super-admin sans boucle par compte

As a **super-admin**,
I want **que les écrans plateforme restent rapides quel que soit le nombre de comptes**,
So that **la console tient la charge d'un grand nombre de tenants (R1, FUS-NFR11)**.

Scope: move the remaining per-tenant query loops of the super-admin services to the chunked cross-tenant union pattern delivered by 20-13 (`CrossTenantSql`, chunked `UNION ALL`, per-chunk isolation, one catalogue query per request).

- [ ] [Deferred-from: review 20-13, 2026-11-08] `AdminAnalyticsService.getOverview` (stores, active stores, products), `getTopStores`, `getAdoption`, `getGeography` still run one query per tenant
- [ ] [Deferred-from: review 20-13, 2026-11-08] `AdminOperationsService.listTenantActivity`, `listInactiveTenants` still run one query per tenant
- [ ] [Deferred-from: review 20-13, 2026-11-08] `AdminCatalogService` (products list, summary, CSV export) still runs one query per tenant

Interim AC: Given 500 tenants, When each of these endpoints is called, Then it issues a bounded number of statements (one per chunk of 100 tenants), and one broken tenant schema is skipped with a warning instead of failing the call.

### Story 19.12 : Boutiques étendues et préférence de parcours de vente

As a **owner**,
I want **décrire mes boutiques comme dans Bref et choisir mon parcours de vente par défaut**,
So that **un ex-Bref arrive sur ses documents et un ex-Keevo sur sa caisse (FUS-FR12, FR17)**.

**Acceptance Criteria:**

**Given** la table `stores`
**When** la migration s'applique
**Then** `category` (19 valeurs Bref), `description`, `logo_path`, `niu`, `rccm`, `country` (défaut CM) sont ajoutés, exposés en lecture/écriture (OWNER) et dans le delta de sync (champs additionnels ignorés par Flutter)

**Given** `tenant_preferences.default_sale_flow ∈ {POS, DOCUMENT}`
**When** un tenant existant est lu
**Then** la valeur est POS ; un tenant importé de Bref reçoit DOCUMENT ; l'owner peut la modifier

**Given** un logo envoyé
**When** il est stocké
**Then** il passe par le port de stockage de fichiers (disque aujourd'hui, S3 optionnel), taille et type contrôlés


### Story 19.13 : Profil d'interface — modules nouveaux désactivés, activables dans les paramètres (FUS-UX6, UX7)

As a **utilisateur existant (Keevo ou Bref)**,
I want **que l'app ne me montre que ce que j'utilisais déjà, avec mes repères**,
So that **je ne sois pas perdu par des écrans que je ne connais pas**.

> **Routé depuis 19.8 (04/11/2026) :** accorder le module `invoicing` au plan FREE (`profile_modules`), **désactivé par défaut** pour les tenants existants comme tout module nouveau pour eux ; le quota de 300 documents/mois est déjà appliqué par 19.8 ; vérifier que le pont vente → facture ne s'active pas pour un owner qui n'a pas activé le module.

**Acceptance Criteria:**

**Given** un tenant
**When** l'API renvoie ses modules (`GET /tenants/me/modules`, 16-3) et ses préférences
**Then** elle renvoie aussi son profil d'interface : préréglage de navigation (`CAISSE` ou `DOCUMENT`, issu de `default_sale_flow`, 19.12) et, pour chaque module compris dans le plan, s'il est **activé** ou **désactivé par l'owner** ; un module désactivé n'apparaît dans aucun menu, onglet, accueil ni raccourci, et n'exécute rien (pas de tâche planifiée, pas de notification, écritures refusées comme pour un module inactif)

**Given** un tenant Keevo existant au go-live
**When** son profil d'interface est initialisé (migration Flyway idempotente)
**Then** seuls les modules qu'il avait actifs dans la V1 sont activés ; tout module nouveau pour lui (documents enrichis, dépenses, finance, assistant, WhatsApp Business, campagnes…) est compris dans son plan s'il y a droit, mais **désactivé**

**Given** un owner Bref importé (24.2)
**When** son profil d'interface est créé
**Then** seules les fonctions équivalentes à celles qu'il avait dans Bref (selon son plan Bref à la migration) sont activées ; caisse, stock, transferts, inventaire, fournisseurs sont **désactivés**

**Given** un module compris dans le plan et désactivé
**When** l'owner l'active dans Paramètres › Mes modules, ou le désactive à nouveau
**Then** l'état change pour tout le tenant, est synchronisé vers les appareils et tracé ; désactiver ne supprime aucune donnée (réactiver les retrouve) ; un module non compris dans le plan n'est pas activable ici (il relève de « Mon plan ») ; un employé ne voit en plus que ce que son rôle permet

**Given** un nouveau tenant créé après la fusion
**When** il choisit son activité et son parcours à l'inscription
**Then** les modules activés au départ suivent ce choix (règles en base, modifiables depuis le dashboard super-admin, ADR-05 : rien de figé dans le code)

**Implementation Notes:**
- Deux notions distinctes : le **droit** (le plan comprend le module, 16-1/16-3/19.7) et l'**activation par l'owner** (19.13). Un module est utilisable seulement s'il est à la fois compris dans le plan et activé. La désactivation par l'owner réutilise l'état DISABLED du cycle de vie des modules avec une origine « owner », sans purge (comme 16-6 l'exige pour tout module DISABLED).
- Champs additifs uniquement sur les réponses existantes (apps V1 2.0.1 inchangées).

---

## Epic 20 : Modules métier Bref dans Keevo — documents, catalogue, clients, dépenses, finance (P1b)

> Référence fonctionnelle : api-bref `develop` (`src/invoice/*`, `src/expense/*`, `src/finance/*`, `product-key.ts`, `customer.repository.ts`). Référence technique : socle modulaire Keevo (manifeste, `db/modules/<id>/`, `DeltaEntityProvider`, opérations de sync idempotentes, capacités). Chaque nouvelle opération de sync est ajoutée à `contracts/invoice-sync-payloads.json` (ou à un contrat dédié) pour être vérifiée par l'app RN.

### Story 20.1 : Catalogue étendu — produits sans stock, clé, code-barres, fusion

> **2026-11-01** : §7.3 tranché — le suivi de stock par produit (`track_stock`, défaut `true`, case dans le formulaire produit, activation/désactivation) est livré par la story **17.12** de la refonte. Ici, il ne reste qu'à vérifier que le comportement couvre les produits Bref (défaut `false` à l'import et à la dérivation depuis une facture, 20.x) ; les deux premiers critères ci-dessous sont des critères de non-régression.

As a **commerçant de services ou de documents**,
I want **des produits qui ne suivent pas de stock et qui se dédupliquent**,
So that **mes articles Bref fonctionnent dans Keevo sans fausses ruptures (FUS-FR13, FR14)**.

**Acceptance Criteria:**

**Given** un produit `track_stock=false`
**When** il est vendu à la caisse
**Then** aucun contrôle `INSUFFICIENT_STOCK`, aucun mouvement `SALE`, aucune ligne `stock_levels`, aucune alerte de seuil ; il est exclu des exports de stock et de l'inventaire

**Given** un produit `track_stock=false`
**When** l'owner active le suivi, avec ou sans stock initial (facultatif, 0 par défaut)
**Then** un mouvement `INITIAL` est créé et le produit se comporte ensuite comme un produit Keevo

**Given** `product_key` (`bc:<code-barres>` ou `nm:<nom normalisé>`, logique de `product-key.ts`)
**When** deux produits sont fusionnés (`POST /products/{id}/merge`)
**Then** le fusionné est archivé avec `merged_into_id`, les lookups le redirigent, les lignes historiques restent intactes, et le pull de sync transmet la redirection (déjà géré par l'app Flutter)

**Given** un code-barres scanné
**When** `GET /products/lookup?barcode=` est appelé
**Then** le produit (ou sa cible de fusion) est renvoyé

### Story 20.2 : CRM étendu, clients par établissement (ADR-08)

As a **commerçant**,
I want **des fiches clients complètes et un consentement marketing tracé**,
So that **mes clients Bref et mes campagnes sont conformes (FUS-FR15)**.

**Acceptance Criteria:**

**Given** la table `clients`
**When** la migration Flyway du module `crm` s'applique
**Then** `client_type`, `niu`, `rccm`, `address`, `contact_name`, `website`, `birth_date`, `store_id` (établissement d'origine, obligatoire pour les nouvelles fiches), `phone_normalized`, `marketing_consent_at`, `marketing_opt_out_at`, `marketing_consent_by` sont ajoutés et synchronisés

**Given** la liste des clients
**When** un employé ou l'owner l'ouvre
**Then** elle est filtrée sur l'établissement courant ; l'employé ne voit que son établissement ; l'owner peut afficher « tous mes établissements » ; un établissement dont l'owner a activé « partager mes clients » voit aussi les fiches des autres établissements partageurs

**Given** la création d'un client dont le téléphone normalisé existe déjà dans le même établissement
**When** l'owner ou l'employé valide
**Then** le serveur renvoie un avertissement `DUPLICATE_PHONE` avec la fiche existante, sans contrainte d'unicité en base ; le même numéro dans un autre établissement n'est pas un doublon (pas de fusion automatique)

**Given** un retrait de consentement
**When** il est enregistré
**Then** le client est exclu de toute campagne (21.6) dès l'enregistrement

### Story 20.3 : Devis (`kind = QUOTE`)

As a **commerçant**,
I want **faire un devis, le modifier, puis le convertir en facture**,
So that **je retrouve le parcours devis de Bref (FUS-FR18)**.

> **Routé depuis 19.8 (04/11/2026) :** un devis ne consomme pas le quota DOCUMENTS ; sa conversion en facture le consomme (comme Bref, `invoice.controller.ts` validate), via `UsageQuotaService.assertAndIncrement`.

**Acceptance Criteria:**

**Given** le module `invoicing`
**When** un devis est créé, modifié (contrôle `expected_updated_at` → 409 en cas de conflit) ou annulé
**Then** il n'a pas de numéro `FAC-`, ne consomme pas le compteur de facturation légale, et apparaît dans les listes avec le filtre Devis

**Given** un devis
**When** il est converti (`POST /invoices/{id}/convert` ou opération de sync `CONVERT_QUOTE` idempotente)
**Then** une facture ISSUED est créée avec un numéro sans trou, `issue_date` = date de conversion, le devis passe CONVERTED avec lien vers la facture

**Given** une facture émise
**When** on tente de la modifier
**Then** le refus actuel est conservé (conformité DGI)

### Story 20.4 : Encaissements — acompte, paiements partiels, échéancier, retard

As a **commerçant**,
I want **enregistrer un acompte, des paiements partiels et un échéancier**,
So that **je sais qui me doit quoi (FUS-FR20)**.

**Acceptance Criteria:**

**Given** `invoice_payments(id, invoice_id, amount, due_date, paid_at, method, note)`
**When** des paiements sont ajoutés (HTTP ou opérations de sync `ADD_INVOICE_PAYMENT`/`SETTLE_INVOICE_PAYMENT` idempotentes)
**Then** `payment_state ∈ {UNPAID, PARTIAL, PAID, OVERDUE}` est recalculé par le serveur sous verrou de ligne, la somme payée ne dépasse jamais le total (`OVERPAYMENT`), et `paid_at` historique est renseigné quand l'état devient PAID

**Given** le job quotidien (WAT, `@SchedulerLock`)
**When** une échéance non payée est dépassée
**Then** la facture passe OVERDUE et une notification est émise à l'owner

**Given** une facture Keevo existante (un seul `paid_at`)
**When** elle est lue
**Then** elle est présentée comme un paiement unique : aucune migration destructive, compatibilité Flutter conservée

**Given** les cas de calcul (acompte = total, échéances multiples, annulation)
**When** les tests s'exécutent
**Then** le jeu de cas est publié dans `contracts/` et réutilisé par les tests de l'app RN (FUS-NFR8)

### Story 20.5 : Remise, livraison, TVA par facture, échéance, données client

As a **commerçant**,
I want **une facture qui porte remise, frais de livraison, TVA propre ou exonération et date d'échéance**,
So that **mes factures Bref sont reproduites à l'identique (FUS-FR19)**.

**Acceptance Criteria:**

**Given** `discount_amount`, `delivery_fee`, `vat_rate` (nullable, défaut vendeur), `vat_exempt`, `due_date`, `client_rccm`, `client_email`, `client_contact_name`, `client_website` (recopiés sur le document comme Bref le fait depuis `6faa8b4`, journal 18.3 ; `client_address` existe déjà), `invoice_items.product_id` (nullable), `invoice_items.reference`
**When** une facture est calculée
**Then** remise appliquée avant TVA, livraison hors assiette TVA, arrondi HALF_UP par ligne puis total, et le résultat est identique au calcul Bref sur 50 factures de référence extraites de la copie de prod (montants entiers XAF)

**Given** le pays de la boutique (ADR-01 : multi-pays)
**When** aucun taux n'est donné
**Then** le taux par défaut du pays s'applique (table Bref : CM 19,25 %, SN/GA… 18 %, CD 16 %…) ; la devise affichée et l'arrondi reproduisent le comportement actuel de Bref pour chaque pays (à vérifier dans le code Bref au démarrage de la story, puis décrit dans la spec)

### Story 20.6 : Dérivation catalogue et CRM depuis un document

As a **commerçant pressé**,
I want **que mes clients et articles se créent tout seuls quand je fais une facture**,
So that **je retrouve le comportement Bref sans saisie préalable (FUS-FR16)**.

**Acceptance Criteria:**

**Given** les modules `crm` et `catalog` actifs et la préférence `derive_from_documents=true` (défaut DOCUMENT)
**When** une facture est émise avec un client non lié
**Then** le client est trouvé par téléphone normalisé ou créé, via `ClientLookupPort`, dans la même transaction que la facture (contrairement à Bref, où ces effets n'étaient pas transactionnels)

**Given** une ligne sans `product_id`
**When** la facture est émise
**Then** le produit est trouvé par `product_key` ou créé `track_stock=false` avec le prix de la ligne, via `ProductLookupPort`, et la ligne est liée

**Given** `crm` ou `catalog` inactif
**When** la facture est émise
**Then** aucune dérivation n'a lieu et la facture reste valide (autonomie du module)

- [x] [Deferred-from: review 20-2, 2026-11-06] (done in 20-6: opt-in keyset pages, pg_trgm search, bounded lookup) Client list and search are unbounded and use `LIKE '%q%'` without a trigram index (pre-existing shape kept by 20.2) [keevo/backend/src/main/java/com/keevo/crm/contact/adapter/out/persistence/ClientSpringRepository.java findAllScoped/searchScoped] — keyset pagination + `pg_trgm` index when client lookup by phone/name serves document derivation

### Story 20.7 : Modèles de rendu, signatures, liens publics

As a **commerçant**,
I want **choisir parmi les 8 modèles Bref, signer et partager un lien qui dure**,
So that **mes documents ont la même allure qu'avant (FUS-FR21, FR22, FR23)**.

**Acceptance Criteria:**

**Given** `invoice_template` (8 valeurs), `ticket_width ∈ {58, 80, A4}`, `signature_client_path`, `signature_seller_path`
**When** le PDF serveur est généré
**Then** les 8 modèles Thymeleaf reproduisent les modèles de `features/invoice/components/invoice-templates/*` (comparaison visuelle sur 3 factures types par modèle)

**Given** le lien public
**When** sa durée est configurée par tenant (défaut 30 j Keevo, 90 j pour les tenants importés)
**Then** le lien signé HMAC respecte cette durée et peut être régénéré (rotation)

**Given** `legacy_public_token` et `legacy_reference` (`BRF-xxxxxxxx`)
**When** une facture importée est ouverte par un ancien lien Bref redirigé
**Then** elle s'affiche ; la référence `BRF-` apparaît sur le PDF à côté du numéro légal s'il existe

- [x] [Deferred-from: review 20-3, 2026-11-06] (done in 20-7) A quote cannot be sent to the client: `Invoice.assertSendable` refuses quotes [keevo/backend/src/main/java/com/keevo/invoicing/invoice/domain/model/Invoice.java] — allow the public link / WhatsApp sending of a quote (PDF « DEVIS DV-… » already exists)

- [x] [Deferred-from: review 20-4, 2026-11-06] (done in 20-7) The invoice PDF and public link show neither deposits, payments, balance due nor the payment schedule [InvoiceDocumentUseCase, InvoicePdfRenderer] — render amountPaid / amountDue / payments[] (from InvoiceResponseDto) in the 8 templates and the ticket

### Story 20.8 : Envoi des documents par SMS et email

As a **commerçant**,
I want **envoyer une facture par SMS ou email en plus de WhatsApp**,
So that **je joins aussi les clients sans WhatsApp (FUS-FR22)**.

**Acceptance Criteria:**

**Given** une facture émise et un quota disponible
**When** `POST /invoices/{id}/send {channel: SMS|EMAIL|WHATSAPP}` est appelé
**Then** le message contient le lien public, l'envoi est journalisé, le quota (19.8) est décompté après succès, et un échec n'altère pas la facture

**Given** un envoi hors ligne depuis l'app
**When** l'opération `SEND_INVOICE` est poussée
**Then** elle est idempotente (un seul envoi par `operationId`)

### Story 20.9 : Reporting multi-source sans double comptage (R4)

As a **owner**,
I want **un chiffre d'affaires qui compte mes ventes caisse et mes factures, une seule fois chacune**,
So that **mes tableaux de bord restent justes quel que soit mon parcours (FUS-FR24)**.

**Acceptance Criteria:**

**Given** la définition « CA = ventes COMPLETED ∪ factures ISSUED/PAID dont `source_sale_id IS NULL` (hors devis, hors annulées, avoirs déduits) »
**When** le dashboard owner, les rapports quotidien/hebdomadaire, la rentabilité, le classement vendeurs et les exports sont calculés
**Then** tous lisent une même source (`RevenueQueryPort` ou vue SQL) et un test d'acceptation couvre : caisse seule, documents seuls, mixte avec pont POS→facture (pas de double comptage), avoir, devis non compté

**Given** un tenant ex-Bref importé (copie de prod, 10 owners)
**When** on compare ses KPI mensuels Keevo aux KPI Bref (`owner-dashboard.service.ts`)
**Then** l'écart est nul, à la règle de date près (date civile de la facture, FUS-NFR9)

**Given** les factures sans produit lié
**When** la rentabilité est calculée
**Then** elles comptent au CA mais pas dans la marge par produit (marge inconnue affichée comme telle)

### Story 20.10 : Module `expenses`

As a **commerçant**,
I want **enregistrer mes dépenses par boutique et par catégorie, même hors ligne**,
So that **je retrouve les dépenses Bref (FUS-FR25)**.

**Acceptance Criteria:**

**Given** le manifeste `expenses` (premium, profil PRO+) et `db/modules/expenses/V1__*.sql`
**When** le module est activé pour un tenant
**Then** la table `expenses(id, store_id, category, amount, date, label, note, created_by, timestamps, archived_at)` est provisionnée et les routes sont gatées par entitlement et permission

**Given** les opérations `CREATE_EXPENSE`, `UPDATE_EXPENSE`, `DELETE_EXPENSE`
**When** elles sont poussées deux fois
**Then** la seconde est `DUPLICATE` ; le pull transmet les suppressions (tombstones) ; LWW sur `clientTimestamp`

**Given** un EMPLOYEE confiné à une boutique
**When** il liste les dépenses
**Then** il ne voit que celles de sa boutique

### Story 20.11 : Module `finance`

As a **owner**,
I want **voir mon résultat (CA HT − dépenses), ma marge et leur évolution**,
So that **je pilote mon commerce comme dans Bref (FUS-FR26)**.

**Acceptance Criteria:**

**Given** `requires: [expenses, reporting]`
**When** `GET /finance/summary?period=&storeId=` est appelé
**Then** les valeurs (CA HT via 20.9, dépenses, résultat, marge, variation vs période précédente, séries) sont égales à celles de `finance-math.ts` sur le jeu de cas porté de `finance-math.spec.ts`

### Story 20.14 : Boutique des documents et derniers consommateurs du CA

As a **owner multi-boutiques**,
I want **que mes factures soient rattachées à une boutique**,
So that **mes rapports quotidiens, hebdomadaires et par boutique comptent aussi mes factures (FUS-FR24)**.

Scope: invoices (and quotes, credit notes through their invoice) carry the establishment they were made in, so that every store-scoped revenue figure of 20.9 (end-of-day and weekly reports, store overviews, store comparison, store-filtered leaderboard and profitability) includes documents; the last revenue readers still on `sales` only move to the single revenue source.

- [x] [Deferred-from: review 20-9, 2026-11-08] (done in 20-14) Invoices have no store: store-scoped reports never include documents [reporting/revenue/adapter/out/persistence/RevenueRelations.java] — add nullable `invoices.store_id` (invoicing module migration), set at creation (member → assigned store, owner → requested or oldest active store, quote conversion copies it, POS bridge → the sale's store), expose it additively, and use it in `RevenueRelations`; 24.4 fills it from Bref `shopId` on import
- [x] [Deferred-from: review 20-9, 2026-11-08] (done in 20-14) `ClientsExportDatasetProvider` computes total_spent / sales_count / last_purchase from sales only — read them from the single revenue source
- [x] [Deferred-from: review 20-9, 2026-11-08] (done in 20-14) `SalesTrendDetectionScheduler` reads sales only — read the single revenue source

Interim AC: Given an EMPLOYEE of store B issuing an invoice, When the end-of-day report of store B is built, Then the invoice's revenue is in it once, and the tenant-wide figures are unchanged.

### Story 20.12 : Test d'acceptation « parcours Bref sur Keevo »

As a **responsable de la fusion**,
I want **un test de bout en bout qui rejoue la journée type d'un owner Bref sur l'API Keevo**,
So that **P1b est déclaré terminé sur une preuve, pas sur une liste de tickets**.

**Acceptance Criteria:**

**Given** un tenant DOCUMENT avec profil PRO
**When** le scénario s'exécute (login OTP → boutique → devis → conversion → acompte → échéance → retard → paiement → envoi SMS → dépense → résumé finance → lien public)
**Then** chaque étape réussit, les quotas sont décomptés, le CA et le résultat sont exacts ; le scénario symétrique Keevo (caisse → facture auto → clôture) reste vert

---

- [x] [Deferred-from: review 20-3, 2026-11-06] (decided in 20-12: Bref offers no duplicate/re-quote, Keevo behaves the same; `POST /invoices/{id}/duplicate` proposed in spec-20-12, not built) A quote whose converted invoice is later cancelled stays CONVERTED with no way forward [ConvertQuoteUseCase, uq_invoices_source_quote] — check what Bref offers (duplicate the quote or re-quote) and decide during the acceptance run

## Epic 21 : Monétisation en libre-service et engagement (P1b)

### Story 21.1 : Module `payments` — Mobile Money (MyCoolPay)

As a **owner sur Android**,
I want **payer mon plan par Mobile Money depuis l'app**,
So that **mon abonnement s'active sans attendre le support (FUS-FR9)**.

**Acceptance Criteria:**

**Given** `POST /payments/checkout {plan, period, method}`
**When** une demande est initiée
**Then** une seule demande en cours par tenant (verrou `INITIATING`), le flux USSD/OTP MyCoolPay est porté de `src/payment-gateway/*`, et la réponse donne l'étape suivante

**Given** le webhook MyCoolPay signé
**When** un paiement est confirmé (livraison multiple possible)
**Then** le traitement est idempotent, `tenant_payments` est écrit, le profil est appliqué par `ModuleLifecycleService` (bundle activé, `expires_at` prolongé), `tenant_plan_events` est tracé, et l'owner est notifié

**Given** un webhook pour un owner encore sur api-bref (pendant P4)
**When** Keevo le reçoit via le routeur (24.10)
**Then** il est ignoré sans erreur (owner inconnu)

**Éléments routés vers 21.1 (story 21.10, 2026-11-09) :**
- [x] [Deferred-from: 21.10, 2026-11-09] (fait en 21-1, 2026-11-09) Prix figé à l'initiation : appeler le port `subscription.api.OfferPricing.quote(tenant, plan, cycle, promoCode)` à l'initiation du paiement et enregistrer le prix remisé obtenu (montant de base, remise appliquée, montant dû) avec la demande ; la confirmation (webhook) applique ce prix enregistré, jamais le prix du moment — critère « un paiement déjà initié garde le prix affiché » de 21.10. Un devis à 0 FCFA (remise de 100 % ou montant ≥ prix) est une offre gratuite : l'activer sans passer par MyCoolPay.
- [x] [Deferred-from: 21.10, 2026-11-09] (fait en 21-1, 2026-11-09) Compter les utilisations d'un code promo (et un éventuel plafond d'utilisations) au paiement confirmé — 21.10 applique le code sans compteur faute de paiement.
- [x] [Deferred-from: 21.10, 2026-11-09] (fait en 21-1, 2026-11-09) Écrire `tenant_payments.plan_code` et `cycle_code` (colonnes ajoutées par 21.10, V60) à la confirmation, pour la ventilation des revenus par plan et cycle.

### Story 21.2 : Achats intégrés iOS (StoreKit / RevenueCat) et abonnements externes

As a **owner sur iPhone**,
I want **payer mon plan par l'App Store**,
So that **mon abonnement Apple actuel continue après la migration (FUS-FR9, FUS-MIG8)**.

**Acceptance Criteria:**

**Given** `external_subscriptions(tenant_id, provider, original_transaction_id, product_id, status, expires_at, environment)`
**When** un événement RevenueCat (INITIAL_PURCHASE, RENEWAL, CANCELLATION, EXPIRATION, BILLING_ISSUE, TRANSFER) arrive
**Then** il est dédoublonné (`revenuecat_webhook_event`), la sandbox est filtrée en prod, la période de grâce Apple est respectée, et le profil du tenant suit l'état de l'abonnement

**Given** l'`app_user_id` RevenueCat d'un owner migré
**When** il était l'id utilisateur Bref
**Then** il reste valide car les UUID utilisateur sont conservés (FUS-MIG2) — vérifié en sandbox avant la vague Apple

**Éléments routés vers 21.2 (story 21.10, 2026-11-09) :**
- [x] [Deferred-from: 21.10, 2026-11-09] (fait en 21-2, 2026-11-09 : produits App Store dans le catalogue, prix Apple saisi ou constaté sur un achat RevenueCat, écart avec la grille dans `GET /api/v1/admin/offers` → `storeProducts`) Écart de prix App Store : les prix Apple se règlent dans App Store Connect ; lire les produits StoreKit (RevenueCat) et afficher au super-admin, dans le catalogue d'offres (`GET /api/v1/admin/offers`), l'écart entre le prix Apple et la grille configurable par plan et cycle.

### Story 21.3 : Crédit de compte et packs SMS

As a **owner**,
I want **retrouver et utiliser mon crédit et mes SMS achetés**,
So that **je ne perds pas d'argent dans la fusion (FUS-FR10, R9)**.

> **Routé depuis la revue 19.8 (04/11/2026) :** - [ ] [Deferred-from: revue 19.8, 2026-11-04] la consommation payée par un pack n'est enregistrée nulle part (`usage_credits.remaining` baisse seulement) : ajouter l'historique des consommations de packs (qui, quand, quel compteur) et l'afficher dans « Mon plan » à côté du quota du mois.

**Acceptance Criteria:**

**Given** `tenant_credits` (solde, mouvements) et les packs SMS (19.8)
**When** un paiement de plan est effectué
**Then** le crédit est imputé en premier si l'owner le choisit, chaque mouvement est journalisé (append-only) et le solde ne devient jamais négatif

**Given** une recharge SMS (`sms_recharge_request` Bref)
**When** elle est confirmée
**Then** le pack est crédité et visible dans « Mon plan »

### Story 21.4 : Module `ai-assistant` — assistant par outils, parité « Bref Point »

As a **owner**,
I want **poser des questions sur mon activité, par questions guidées ou en texte libre**,
So that **je retrouve l'assistant de Bref, avec des réponses justes qui respectent mes droits (FUS-FR27, FR39)**.

**Acceptance Criteria:**

**Given** l'API de « Bref Point » (`POST /v1/ai/chat`, CRUD `/v1/ai/conversations`, suppression groupée)
**When** le module `ai-assistant` est livré
**Then** Keevo expose les mêmes opérations (mêmes formes de réponse que celles consommées par l'app Bref, contrat dans `keevo/contracts/`), conversations stockées par tenant (`kv_x.ai_conversations`), limite de 20 messages par conversation et quota IA journalier/mensuel (19.8) identiques

**Given** les sujets guidés de Bref (diagnostic, chiffre d'affaires, impayés, encaissements, vendeurs, boutiques, clients ; période, comparaison, drill-down boutique → vendeurs, liste d'appel des impayés)
**When** l'app les utilise
**Then** chaque sujet a ses données servies par un outil de 21.8 (ou un endpoint de statistiques existant), **gratuitement** (aucun appel au modèle, aucun crédit)

**Given** une question en texte libre
**When** elle est posée
**Then** le fournisseur de modèle (configurable, porté de `src/ai/*` : OpenAI / Gemini / Ollama) reçoit la question et la liste des outils de 21.8 autorisés pour cet utilisateur, **appelle des outils** (aucune génération ni exécution de SQL), et rédige la réponse en français à partir de leurs résultats ; un appel décompte le quota une fois par question (pas par outil)

**Given** un corpus de parité (≥ 30 questions réelles tirées des sujets Bref et des conversations migrées, avec réponses attendues calculées sur des données de test)
**When** il est rejoué contre l'assistant Keevo
**Then** toutes les réponses chiffrées sont exactes ; le corpus tourne en test (fournisseur simulé pour les appels d'outils attendus) et sert de critère d'acceptation de la vague 24.x

**Given** un employé (périmètre 16-13), un module désactivé ou un autre tenant
**When** il pose une question qui toucherait ces données
**Then** l'outil refuse ou filtre exactement comme le REST ; aucune donnée d'un autre tenant ou d'une autre boutique n'apparaît (test d'isolation)

**Given** le fournisseur indisponible, une erreur d'outil ou une question en cours pour le même compte
**When** l'appel échoue ou est refusé
**Then** le quota n'est pas consommé, un message clair en français est renvoyé, une seule question à la fois par compte (comme Bref)

### Story 21.5 : Module `whatsapp-cloud` — compte, boîte de réception, modèles

As a **owner BUSINESS**,
I want **connecter mon WhatsApp Business et répondre à mes clients depuis l'app**,
So that **je garde ma boîte WhatsApp Bref (FUS-FR28)**.

**Acceptance Criteria:**

**Given** un compte WABA par tenant (token chiffré au repos, FUS-NFR4)
**When** le webhook Meta (unique par app Meta) livre un message
**Then** il est routé vers le bon tenant par `phone_number_id`, stocké, notifié, et la signature `X-Hub-Signature-256` est vérifiée

**Given** les modèles de messages
**When** l'owner en crée ou en envoie
**Then** le cycle de validation Meta est suivi et l'envoi respecte la fenêtre de 24 h

**Éléments reportés routés vers 21.5 (revue 19.9b, 2026-11-04) :**
- [ ] [Deferred-from: revue 19.9b, 2026-11-04] envoi des factures et devis par le modèle Meta `bref_facture` (PDF en en-tête, `api-bref/src/whatsapp/whatsapp.service.ts:324-410`) depuis le numéro WABA de l'owner, jamais depuis le numéro plateforme (règle Bref) ; aujourd'hui `SendInvoiceUseCase` envoie un lien signé en texte libre via `WhatsAppSender.sendReport` (Wassender/Twilio) [invoicing/…/SendInvoiceUseCase] — brancher l'envoi sur le compte WABA du tenant quand il est connecté, texte libre plateforme en repli.

### Story 21.6 : Campagnes clients et campagnes plateforme

As a **owner BUSINESS / super-admin**,
I want **envoyer des campagnes à mes clients consentants / à des segments de tenants**,
So that **les campagnes Bref sont reprises (FUS-FR28, FR32)**.

**Acceptance Criteria:**

**Given** une campagne client
**When** elle est envoyée
**Then** seuls les clients avec consentement actif sont ciblés (20.2), l'envoi est asynchrone avec suivi (envoyé, délivré, échec), et les quotas sont décomptés

**Given** une campagne plateforme (dashboard super-admin)
**When** elle cible un segment (plan, pays, activité)
**Then** elle part par les canaux choisis (push, WhatsApp, SMS, email) avec audit

### Story 21.7 : Module `client-portal`

As a **client final**,
I want **retrouver toutes mes factures avec mon numéro de téléphone**,
So that **le portail Bref « retrouver mes factures » continue (FUS-FR29)**.

**Acceptance Criteria:**

**Given** `public.invoice_phone_index(phone_normalized, tenant_id, invoice_id)` alimenté à l'émission (et à l'import)
**When** un client s'authentifie par OTP (19.2, compte `portal_clients` distinct des comptes commerçants)
**Then** il voit la liste de ses factures tous commerçants confondus, chaque document étant lu dans le schéma de son tenant avec le minimum de champs, et rien d'autre (test d'isolation)

**Given** un commerçant qui désactive le portail pour son tenant
**When** le client consulte
**Then** les factures de ce commerçant n'apparaissent plus

---

### Story 21.10 : Catalogue d'offres entièrement configurable — plans, prix, essais, remises (ADR-05)

As a **super-admin**,
I want **créer, modifier et archiver toute l'offre commerciale depuis le dashboard**,
So that **rien ne soit figé dans le code et que l'offre évolue sans redéploiement de l'API ni de l'app (FUS-FR40)**.

**Acceptance Criteria:**

**Given** les profils FREE, PRO, TEAM, BUSINESS (19.7)
**When** la migration du module `payments` s'applique
**Then** les prix par plan et par cycle (mensuel, trimestriel, annuel, en XAF) sont stockés en base avec les valeurs initiales de la grille Bref (PRO 3 500 / 9 500 / 32 760 ; TEAM 5 500 / 15 000 / 51 480 ; BUSINESS 8 500 / 23 000 / 79 560), et l'historique des changements est tracé (auteur, date, ancien et nouveau prix)

**Given** l'API super-admin du catalogue d'offres
**When** le super-admin crée, modifie, réordonne, masque ou archive un plan (libellé, description, modules, plafonds, quotas mensuels), un cycle de facturation et son prix, la politique d'essai (plan, durée, bénéficiaires), ou une remise (pourcentage ou montant ; promotion datée sur des plans et cycles ; remise d'un tenant ; code promo)
**Then** le changement est validé (messages français), tracé, et visible tout de suite par l'app et le dashboard ; archiver un plan ne touche pas les tenants qui l'ont (ils le gardent jusqu'à leur changement de plan)

**Given** un prix ou une remise modifiés
**When** un nouveau paiement est initié
**Then** il utilise le prix remisé en vigueur (21.1, 21.2), sans redéploiement ; un paiement déjà initié garde le prix affiché au moment de l'initiation

**Given** l'app et l'endpoint public des offres
**When** l'owner ouvre « Mon plan »
**Then** il voit les prix en vigueur et la remise applicable (prix barré, fin de la promotion), lus depuis l'API

**Implementation Notes:**
- Les prix Apple (StoreKit) se règlent dans App Store Connect : la grille configurable s'applique au Mobile Money et aux plans accordés ; l'écart éventuel est affiché au super-admin.
- Section « Dashboard super-admin (autre dépôt) » obligatoire dans la spec : le dashboard doit pouvoir tout créer, modifier et archiver (aucun écran en lecture seule sur l'offre).
- Garde-fou de revue : aucun libellé, prix, plafond, quota, durée d'essai ou code de plan en dur dans le code (hors données de départ Flyway et repli de sécurité journalisé).

---


**Éléments reportés routés (revue 19.7a, 2026-11-04) :**
- [x] [Deferred-from: revue 19.7a, 2026-11-04] (fait en 21-10, 2026-11-09) Changer la nature (kind) d'un plan déjà attribué ne resynchronise pas les lignes de modules des tenants concernés (`JdbcPlanCatalog.save`) — la CRUD doit soit interdire le changement de nature d'un plan utilisé, soit déclencher la resynchronisation des profils.
- [x] [Deferred-from: revue 19.7a, 2026-11-04] (fait en 21-10, 2026-11-09) (les deux chemins d'activation super-admin refusent déjà un plan archivé depuis 19.7b) `archived_at` et `visible` ne sont pas appliqués à l'attribution : un plan archivé ou masqué peut encore être attribué (`AdminSubscriptionController`, `AdminPlanProfileController.parsePlanType`, `ActivatePlanService`) — refuser un plan archivé pour une nouvelle attribution.
- [x] [Deferred-from: revue 19.7a, 2026-11-04] (fait en 21-10, 2026-11-09) Les revenus comptent tous les plans payants dans un seul seau « PREMIUM » (`RevenueMetrics`) faute de prix par plan — à ventiler par plan et cycle une fois les prix en base.

## Epic 22 : App React Native — fondations et parité Keevo (P2)

> Base : fork du shell Bref (`app/`, `components/ds`, `lib/i18n`, `lib/theme`, `lib/app-lock`, `eas.json`, EAS Update). **À ne pas reprendre** : `lib/database/sync.ts`, `local-mutations.ts`, `mutation-defaults.ts`, schéma WatermelonDB. **Spécification de la couche données** : le code Flutter et ses 388 fichiers de test (`rest_sync_service.dart`, `sync_rejection_policy.dart`, `sync_quarantine_service.dart`, `sync_gate_state.dart`, `entitlements_provider.dart`, `feature_modules.dart`). Emplacement proposé : `keevo/mobile/` dans le monorepo Keevo (à côté de `app/` Flutter jusqu'à son retrait).

### Story 22.1 : Monorepo, shell et identité Bref Point (ADR-02)

As a **développeur**,
I want **un monorepo RN avec le shell Bref et deux profils de build**,
So that **un même code produise l'app unifiée Bref Point (FUS-FR33)**.

**Acceptance Criteria:**

**Given** `keevo/mobile` (pnpm workspaces : `apps/merchant`, `packages/{core,data,ui,i18n,…}`)
**When** `eas build --profile production` est lancé
**Then** un binaire `com.brefpoint.app` (nom, icônes et thème Bref Point) est produit ; un canal EAS Update distinct reste réservé à l'ancienne app Bref (pré-migrateur 24.11)

**Given** la CI
**When** une PR est ouverte
**Then** typecheck, lint, tests unitaires et vérification des contrats (`keevo/contracts/`) s'exécutent et bloquent

### Story 22.2 : Stockage local chiffré et schéma

As a **commerçant**,
I want **que mes données locales soient chiffrées et structurées comme sur Keevo**,
So that **le hors-ligne est sûr et compatible avec le protocole serveur (FUS-NFR4)**.

**Acceptance Criteria:**

**Given** SQLite chiffré (op-sqlite + SQLCipher ; clé 32 octets générée au premier lancement et stockée dans le Keystore/Keychain)
**When** l'app démarre
**Then** les 22 tables Drift, `module_records` et `sync_queue` sont créées par migrations versionnées, et un test vérifie que le fichier n'est pas lisible sans la clé

**Given** l'ancienne base Drift ou WatermelonDB présente après la mise à jour
**When** l'app RN démarre pour la première fois
**Then** elle les supprime sans les lire (le serveur fait foi, FUS-MIG7 garantit la file vide)

### Story 22.3 : Authentification, sessions et choix d'entreprise

As a **utilisateur**,
I want **me connecter avec mes identifiants Keevo et choisir mon entreprise**,
So that **j'accède à mes données dès le premier lancement**.

**Acceptance Criteria:**

**Given** le login 2 étapes (téléphone + mot de passe ; username + mot de passe) et le tenant-picker
**When** l'utilisateur se connecte
**Then** les tokens sont dans `expo-secure-store`, le refresh est rotatif et sérialisé (une seule requête de refresh à la fois, rejeu détecté → déconnexion), et un full pull est déclenché pour un nouvel appareil

**Given** un 403 `MODULE_NOT_ENTITLED` / `MODULE_READ_ONLY` ou un 423 `SYNC_REQUIRED`
**When** l'API le renvoie
**Then** l'intercepteur produit l'état d'interface correspondant (comme `auth_interceptor.dart`)

### Story 22.4 : File d'opérations et push

As a **commerçant hors ligne**,
I want **que chaque action soit enregistrée localement puis envoyée de façon sûre**,
So that **rien ne se perd ni ne se double (FUS-FR34)**.

**Acceptance Criteria:**

**Given** une écriture métier
**When** elle est faite
**Then** la modification locale et l'opération (`operationId` UUID, `operation`, `entityId`, `payload`, `clientTimestamp`) sont écrites dans **la même transaction** SQLite

**Given** le service de push (lots de 50, toutes les 30 s et à la reconnexion, single-flight)
**When** le serveur répond `APPLIED`/`DUPLICATE`/`REJECTED`/`CONFLICT` par opération
**Then** chaque opération est traitée selon sa réponse, le dédoublonnage one-shot / append-only de Flutter est reproduit, et les tests portés de `rest_sync_service_test` passent

### Story 22.5 : Pull delta et fusion locale

As a **commerçant sur plusieurs appareils**,
I want **recevoir les changements des autres appareils**,
So that **mes données sont cohérentes partout**.

**Acceptance Criteria:**

**Given** `GET /sync/pull?since&cursor` (keyset, pages de 1 000, marge 30 s, curseur en stockage sécurisé)
**When** le pull s'exécute
**Then** chaque entité a son merger porté de Flutter (dont `replaceUnlessPending` pour `module_records`, redirection des produits fusionnés, tombstones), et une donnée locale encore en file n'est jamais écrasée

**Given** un appareil réinstallé
**When** le curseur est absent
**Then** un full pull complet est fait avec indicateur de progression

- [ ] [Deferred-from: review 20-2, 2026-11-06] A client moved by the owner to another store stays on the old store's devices: the pull sends no removal row for a record leaving the actor's visible set [keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/ClientDeltaProvider.java] — removal markers in the pull, or a reseed trigger on store moves, with the app purging the record

### Story 22.6 : Politique de rejet, quarantaine, journal

As a **commerçant**,
I want **savoir quand une opération ne passe pas et que faire**,
So that **une erreur ne fait pas disparaître une vente (FUS-UX3)**.

**Acceptance Criteria:**

**Given** la politique de `sync_rejection_policy.dart` (transitoire → retry + backoff ; held → conservée 6 h si module inactif ; terminal → quarantaine pour argent/stock, rollback pour catalogue/contact)
**When** chaque cas est simulé
**Then** le comportement est identique à Flutter (tests portés un à un)

**Given** l'écran « Opérations bloquées »
**When** une opération est en quarantaine
**Then** l'owner peut Renvoyer, Rattacher (au produit/client cible) ou Abandonner après export, et le journal des conflits est consultable

### Story 22.7 : Limite de 7 jours hors ligne

As a **plateforme**,
I want **empêcher un appareil de diverger trop longtemps**,
So that **les conflits restent gérables**.

**Acceptance Criteria:**

**Given** la date du dernier push réussi
**When** elle atteint J5 / J6 / J7
**Then** bannière d'information / bannière rouge / écritures bloquées (lecture autorisée) ; un 423 `SYNC_REQUIRED` serveur produit le même blocage ; une synchronisation réussie lève le blocage

### Story 22.8 : Entitlements, modules UI et garde de routes

As a **commerçant**,
I want **voir uniquement les modules de mon plan et de mon rôle**,
So that **l'app reste simple et cohérente avec le serveur (FUS-FR35)**.

**Acceptance Criteria:**

**Given** le snapshot `GET /tenants/me/modules` gardé sur l'appareil (aucune liste de modules dans le jeton, décision du 2026-11-01, refonte 16.3)
**When** l'app construit sa navigation
**Then** chaque écran appartient à un module (table feature→module), une route d'un module inactif redirige vers « Mes modules », READ_ONLY désactive les actions d'écriture, et un test compare la table à `contracts/module-routes.json`

**Given** un package de module (`packages/invoicing`, …)
**When** le module est inactif
**Then** son code n'est pas chargé (import différé) et ses opérations de sync locales sont mises en attente (held)

**Given** `GET /tenants/me/extensions/{pointId}`
**When** un slot d'extension est affiché (ex. « Facturer » au checkout)
**Then** seules les actions contribuées par des modules actifs apparaissent

### Story 22.9 : Écrans cœur — accueil, boutiques, équipe, paramètres, « Mes modules »

As a **owner Keevo**,
I want **retrouver mon tableau de bord, mes boutiques, mon équipe et mes réglages**,
So that **le changement d'app est transparent (FUS-UX1, UX2)**.

**Acceptance Criteria:**

**Given** l'inventaire des écrans Flutter (§5.2 de l'analyse, colonne Keevo)
**When** les écrans du module `core` sont livrés dans le design system Bref
**Then** chaque écran Keevo du périmètre a son équivalent, avec les mêmes règles (employé confiné à sa boutique, changement de mot de passe, déconnexion, multi-entreprise), et l'accueil d'un tenant POS est la caisse ; la navigation suit le préréglage « Caisse » (FUS-UX6 : onglets, ordre et libellés de l'app Keevo) et n'affiche que les modules activés du profil d'interface (19.13, FUS-UX7)

### Story 22.10 : Catalogue, stock, transferts, inventaire

As a **commerçant Keevo**,
I want **gérer produits, catégories, prix, stock, transferts et inventaires dans la nouvelle app**,
So that **je ne perds aucune fonction de gestion de stock**.

**Acceptance Criteria:**

**Given** les opérations de sync existantes (création/édition produit, ajustement, transfert, session d'inventaire)
**When** elles sont exécutées hors ligne puis synchronisées
**Then** le résultat serveur est identique à celui produit par l'app Flutter (mêmes payloads, vérifiés par contrat), y compris `STOCK_NEGATIVE` → conflit et bannière

**Given** le formulaire « Nouveau produit »
**When** l'owner saisit une quantité initiale, ou laisse le champ vide (il est facultatif)
**Then** le comportement de la story refonte 17.11 est reproduit (mouvement STOCK_ENTRY dans la même transaction locale si une quantité est saisie ; sinon stock à 0, sans mouvement ni blocage)

**Given** le scanner de code-barres (repris de Bref)
**When** un code est scanné
**Then** le produit est trouvé localement, ou par `GET /products/lookup` en ligne

### Story 22.11 : Caisse (POS)

As a **vendeur**,
I want **encaisser vite, même hors ligne**,
So that **la caisse Keevo fonctionne au moins aussi bien qu'avant**.

**Acceptance Criteria:**

**Given** le panier (quantités décimales via `lib/quantity.ts`, prix modifiable, remise, CASH/MOMO, client facultatif)
**When** la vente est validée hors ligne
**Then** la vente, ses lignes et la décrémentation locale du stock sont écrites en une transaction avec l'opération `CREATE_SALE`, le reçu s'affiche immédiatement, et le stock disponible limite la quantité pour les produits `track_stock=true` uniquement

**Given** une vente
**When** l'owner l'annule ou la corrige, ou clôture la journée (WAT)
**Then** le comportement est celui de Keevo (avoir automatique via le pont, clôture), y compris l'action « Facturer » si `invoicing` est actif

### Story 22.12 : CRM, facturation Keevo, rapports, exports, alertes

As a **owner Keevo**,
I want **mes clients, factures, rapports, exports et alertes**,
So that **la parité Keevo est complète**.

**Acceptance Criteria:**

**Given** les 5 opérations de facturation hors ligne de 15-5b, les rapports serveur d'abord, les exports, les alertes de stock et les push FCM (token natif `getDevicePushTokenAsync` → `POST /devices/token`)
**When** chaque parcours est exécuté
**Then** il est équivalent au parcours Flutter correspondant, vérifié par un scénario Maestro par parcours

### Story 22.13 : Tests de la couche données et parcours hors ligne

As a **responsable qualité**,
I want **une preuve que l'app RN est au moins aussi fiable que Flutter**,
So that **R5 est maîtrisé avant toute mise en production (FUS-NFR5)**.

**Acceptance Criteria:**

**Given** les tests Flutter de la couche données (mergers, politique de rejet, gate, calcul des montants, file)
**When** la story est terminée
**Then** chacun a un équivalent Jest (liste de correspondance dans `keevo/mobile/docs/test-parity.md`), et 6 scénarios Maestro passent sur émulateur Android : vente hors ligne puis sync, deux appareils en conflit de stock, quarantaine puis renvoi, gate J7, réinstallation et full pull, module désactivé pendant une vente en file

### Story 22.14 : Dernière release Flutter — mise à jour seulement file vide

As a **commerçant Keevo**,
I want **ne passer à la nouvelle app qu'une fois toutes mes opérations envoyées**,
So that **rien ne se perd au changement d'app (FUS-MIG7)**.

**Acceptance Criteria:**

**Given** la release Flutter N+1 (dernière)
**When** la nouvelle version minimale approche
**Then** l'app force une synchronisation, affiche « Passez à Bref Point » (lien Play Store `com.brefpoint.app`, mêmes identifiants) uniquement si `sync_queue` est vide et sans quarantaine, sinon liste les opérations à traiter, et propose l'export CSV local comme filet ; l'APK Keevo (`com.example.keevo`, hors store) ne peut pas être mis à jour vers la fiche Play : une installation de Bref Point est nécessaire, sans perte de données ni de mot de passe (ADR-02) ; au premier lancement de Bref Point, l'écran « Keevo devient Bref Point » s'affiche une fois (FUS-UX8)

**Given** la page `/monitoring/sync` du dashboard
**When** on la consulte
**Then** elle liste les appareils Flutter avec des opérations non poussées depuis plus de 24 h

### Story 22.15 : Bêta et déploiement progressif sur la fiche Bref Point (ADR-02)

As a **responsable de la fusion**,
I want **déployer l'app RN aux utilisateurs Keevo par paliers**,
So that **un défaut touche peu de monde et peut être corrigé avant la généralisation**.

**Acceptance Criteria:**

**Given** la piste de test fermée de la fiche Play `com.brefpoint.app`
**When** 2 semaines de bêta passent sans anomalie bloquante (aucune perte de donnée, crash-free ≥ 99,5 %)
**Then** le déploiement passe à 10 %, 50 %, 100 % avec une semaine d'observation par palier ; l'app Flutter reste téléchargeable 4 semaines

**Given** l'app iOS Bref Point existante (équipe `N9534K29PS`)
**When** la build iOS est prête
**Then** elle est publiée comme mise à jour de Bref Point (pas d'App ID Keevo à créer)

### Story 22.16 : Retrait de l'app Flutter

As a **exploitant**,
I want **ne plus maintenir deux apps Keevo**,
So that **l'effort se concentre sur l'app unifiée**.

**Acceptance Criteria:**

**Given** 100 % du parc sur RN depuis 4 semaines et moins de 2 % d'appareils Flutter actifs
**When** la version minimale de l'APK Keevo est relevée (19.10)
**Then** les appareils Flutter restants voient l'écran de mise à jour obligatoire, `keevo/app` passe en lecture seule (tag `flutter-final`), et la CI Flutter est retirée

---

### Story 22.17 : Inventaire de parité fonction par fonction (critère GL)

As a **responsable de la fusion**,
I want **une liste vivante de toutes les fonctions de Keevo et de Bref, cochée une à une dans l'app unifiée**,
So that **aucun utilisateur existant ne perd une fonction qu'il utilise**.

**Acceptance Criteria:**

**Given** les fonctions de l'app Flutter Keevo (écrans, actions, règles, y compris tout ce qui est livré depuis le 31/10 : classement des vendeurs, notification de changement de prix, réglages des rapports, appareils connectés, bandeau lecture seule, suivi de stock par produit, restauration d'un produit archivé — clés `m16-*`/`m17-*`) et celles de l'app Bref en production (complétées à chaque entrée du journal 18.3)
**When** l'inventaire est tenu dans `_bmad-output/planning-artifacts/parite-fonctions-keevo-bref.md`
**Then** chaque ligne indique la fonction, l'app d'origine, l'écran et le module cible, le préréglage de navigation concerné, la story qui la livre, et l'état « présente, même comportement » vérifié sur staging (scénario ou test cité)

**Given** le jalon GL
**When** on l'évalue
**Then** aucune ligne n'est ouverte, sauf abandon écrit par l'owner produit

### Story 22.18 : Tests avec de vrais utilisateurs avant le go-live

As a **owner produit**,
I want **voir des utilisateurs Keevo et Bref se servir de l'app unifiée avant sa mise en prod**,
So that **on vérifie qu'ils retrouvent leurs repères (FUS-UX6, UX7)**.

**Acceptance Criteria:**

**Given** l'app sur staging avec des copies de leurs données (anonymisées si besoin)
**When** 3 à 5 commerçants Keevo (caisse) et 3 à 5 utilisateurs Bref (documents) font leurs tâches habituelles sans aide (vendre, clôturer, ajouter un produit ; créer une facture, encaisser, relancer un impayé)
**Then** chaque tâche est réussie sans hésitation bloquante, les points de confusion sont notés et corrigés avant GL, et le compte rendu est joint à l'inventaire 22.17

---

## Epic 23 : App React Native — parité Bref (P3)

> Référence : écrans et parcours de `AI/bref` `develop` (§3.5 et §5.2 de l'analyse). Les écrans Bref sont réutilisés au maximum (composants, styles, i18n) ; seule leur source de données change (repositories de la couche 22.x au lieu de WatermelonDB/TanStack).

### Story 23.1 : Onboarding et connexion Bref (OTP, Google, Apple)

As a **owner ex-Bref**,
I want **me connecter comme avant**,
So that **la migration ne change pas mes habitudes (FUS-FR1)**.

**Acceptance Criteria:**

**Given** l'écran d'onboarding Bref
**When** l'utilisateur choisit OTP (SMS/WhatsApp/email), Google ou Apple
**Then** les authenticators 19.2/19.3 sont utilisés, et un nouveau compte sans entreprise est conduit à la création d'entreprise (secteur, boutique, parcours DOCUMENT par défaut)

### Story 23.2 : Parcours Document en 5 étapes

As a **commerçant Bref**,
I want **créer une facture ou un devis en 5 étapes**,
So that **je retrouve mon parcours principal (FUS-FR17–FR19, FUS-UX2)**.

**Acceptance Criteria:**

**Given** les étapes Infos (type de client obligatoire, NIU/RCCM), Articles (recherche, scanner, quantités décimales), Paiement (remise, livraison, TVA/exonération, acompte, échéancier), Signature, Récapitulatif/envoi
**When** le document est validé hors ligne
**Then** il est écrit localement avec ses opérations de sync (création, paiements), les montants sont calculés avec le même code que le serveur (jeu de cas de 20.4/20.5), et l'écran de succès propose PDF local, partage et envoi

**Given** un tenant POS
**When** il ouvre le menu « Nouveau document »
**Then** le parcours est disponible si `invoicing` est actif

### Story 23.3 : Devis, encaissements et suivi des impayés

As a **commerçant**,
I want **convertir mes devis, enregistrer mes paiements et suivre mes impayés**,
So that **la gestion d'encaissement Bref est complète (FUS-FR18, FR20)**.

**Acceptance Criteria:**

**Given** la liste des documents (filtres type, état d'encaissement, client, période)
**When** l'utilisateur convertit un devis, ajoute un paiement ou marque une échéance payée
**Then** les opérations correspondantes sont mises en file, l'état affiché localement est provisoire jusqu'au retour serveur, puis remplacé par l'état calculé par le serveur

### Story 23.4 : Modèles de reçu, impression et partage

As a **commerçant**,
I want **imprimer ou partager mes documents avec mon modèle habituel, hors ligne**,
So that **mes clients reçoivent le même document qu'avant (FUS-FR21)**.

**Acceptance Criteria:**

**Given** les 8 modèles React de Bref et la signature (désormais persistée, 20.7)
**When** un PDF est généré hors ligne
**Then** il est identique au rendu Bref actuel ; en ligne, le lien public renvoie le rendu serveur équivalent

### Story 23.5 : Dépenses et finance

As a **owner PRO**,
I want **mes dépenses et mon résultat dans l'app**,
So that **je pilote comme dans Bref (FUS-FR25, FR26)**.

**Acceptance Criteria:**

**Given** les modules `expenses` et `finance` actifs
**When** l'owner saisit une dépense hors ligne puis consulte le résumé
**Then** la dépense est synchronisée (20.10) et le résumé (serveur d'abord, repli local) correspond au calcul serveur

### Story 23.6 : Assistant IA, boîte WhatsApp, modèles et campagnes

As a **owner PRO/BUSINESS**,
I want **l'assistant et la boîte WhatsApp dans l'app**,
So that **mes outils d'engagement Bref sont conservés (FUS-FR27, FR28)**.

**Acceptance Criteria:**

**Given** les modules `ai-assistant` et `whatsapp-cloud`
**When** l'utilisateur les ouvre
**Then** les écrans Bref sont branchés sur les API 21.4/21.5/21.6 (lectures en ligne via TanStack Query, jamais d'écriture métier hors file), et le quota restant est affiché

### Story 23.7 : Mon plan, paiement en libre-service, crédit

As a **owner**,
I want **voir mon plan, mes quotas et payer seul**,
So that **je gère mon abonnement sans le support (FUS-FR9, FR10, FUS-UX5)**.

**Acceptance Criteria:**

**Given** l'écran « Mon plan »
**When** il s'affiche
**Then** il montre profil, échéance, quotas consommés/limites, crédit et packs SMS ; sur Android le paiement Mobile Money (21.1), sur iOS RevenueCat (21.2) ; à la confirmation, les modules s'activent sans redémarrage (rafraîchissement du snapshot d'entitlements)

### Story 23.8 : Portail client « retrouver mes factures »

As a **client final**,
I want **retrouver mes factures depuis l'app ou le web**,
So that **le portail Bref continue (FUS-FR29)**.

**Acceptance Criteria:**

**Given** l'entrée « Je suis client » de l'onboarding Bref et la page web publique
**When** le client s'authentifie par OTP
**Then** il voit ses factures (21.7) et peut ouvrir chaque lien public

### Story 23.9 : Sécurité locale, langues, notifications in-app

As a **utilisateur**,
I want **verrouiller l'app, choisir ma langue et voir mes notifications**,
So that **je retrouve le confort de Bref (FUS-FR30, FR36)**.

**Acceptance Criteria:**

**Given** PIN et biométrie (`lib/app-lock` Bref), i18n (fr, en ; 5 autres langues prêtes), notifications in-app (19.9)
**When** chaque fonction est utilisée
**Then** elle fonctionne pour les ex-Keevo comme pour les ex-Bref ; tous les écrans Keevo portés en 22.x ont leurs clés fr et en

### Story 23.10 : Bascule Caisse / Document

As a **owner**,
I want **choisir mon parcours principal et passer de l'un à l'autre**,
So that **chaque type de commerce a l'accueil qui lui convient (FUS-FR17)**.

**Acceptance Criteria:**

**Given** `default_sale_flow` (19.12)
**When** l'owner le change dans les paramètres
**Then** l'accueil, le bouton d'action principal et l'ordre des onglets changent ; les deux parcours restent accessibles selon les modules compris dans le plan **et activés** (19.13)

**Given** un ex-Bref en préréglage « Document »
**When** il ouvre l'app
**Then** il retrouve les onglets, l'ordre et les libellés de l'app Bref (FUS-UX6), sans caisse ni stock tant qu'il ne les a pas activés dans les paramètres (FUS-UX7)

### Story 23.11 : Routage double backend et mode legacy (ADR-09)

As a **owner Bref pas encore migré qui a reçu la mise à jour**,
I want **comprendre ce qui se passe et consulter mes factures**,
So that **la mise à jour ne me bloque pas (FUS-FR37, FUS-UX4)**.

**Acceptance Criteria:**

**Given** l'identité Bref et un compte inconnu de Keevo au login (OTP/social)
**When** Keevo répond « compte inconnu »
**Then** l'app interroge `api-bref /v1/auth/me/migration` (24.1) et affiche « Migration prévue le … » ou, si aucune n'est planifiée, un mode legacy en lecture seule (liste et PDF de ses factures via api-bref), sans aucune écriture

**Given** l'owner migré entre-temps
**When** il relance l'app
**Then** le login Keevo réussit et un full pull charge ses données

**Given** un owner Bref dont la migration est activée (ou l'un de ses membres)
**When** il ouvre l'app unifiée pour la première fois après la migration
**Then** l'écran « Votre application a été mise à jour, vos factures sont là » s'affiche une fois, sans étape obligatoire (FUS-UX8)

### Story 23.12 : Publication sous l'identité Bref Point

As a **responsable de la fusion**,
I want **publier l'app unifiée sur la fiche `com.brefpoint.app`**,
So that **les owners migrés la reçoivent comme une mise à jour**.

**Acceptance Criteria:**

**Given** l'ADR-09 (publication précoce, mode legacy)
**When** la build `production` est publiée (Play et App Store) dès le début des vagues
**Then** elle passe la revue Apple (achats intégrés RevenueCat inchangés, connexion Apple présente), la version minimale de l'ancienne app n'est pas encore relevée, et le canal EAS Update de l'ancienne app reste dédié au pré-migrateur (24.11)

---

## Epic 24 : Migration Bref par vagues (P1c + P4)

> Stories 24.1 à 24.10 = **outil** (P1c, en recouvrement de P1b, validé par dry-run). Stories 24.11 à 24.16 = **exécution** (P4). Ordre d'import et contrôles : §10 de l'analyse. Évolutions demandées à api-bref par la fusion : 24.1 et 24.11 ; les autres correctifs Bref continuent et sont suivis par 18.3.

### Story 24.1 : api-bref — statut de migration, gel et export d'un owner

As a **outil de migration**,
I want **geler un owner, exporter toutes ses données et publier son statut**,
So that **l'import part d'un instantané figé et l'app sait où en est l'owner (FUS-MIG4)**.

**Acceptance Criteria:**

**Given** la colonne `user.migration_status ∈ {none, scheduled, frozen, migrated}` (+ `scheduled_at`, `migrated_at`)
**When** l'owner est `frozen`
**Then** toute écriture de l'owner et de ses membres (HTTP, mutations rejouées) renvoie 423 `ACCOUNT_MIGRATING` via un guard global, les lectures restent autorisées, et `unfreeze` rétablit l'état précédent

**Given** `GET /v1/admin/migration/owners/:id/export` (superadmin, streaming JSON, checksum)
**When** il est appelé sur un owner gelé
**Then** il sérialise user, shops, membres, customers, products, invoices (+items, +payments), expenses, subscriptions, plan payment requests, usage counters, account credit, Apple transactions, WhatsApp (compte, modèles, campagnes, messages), conversations IA, notifications ; il refuse un owner non gelé

**Given** `GET /v1/auth/me` et `GET /v1/auth/me/migration`
**When** l'app les appelle
**Then** ils exposent `{status, scheduledAt, redirectBundle, minVersion}`

**Given** un owner `migrated`
**When** il tente de se connecter sur api-bref
**Then** la connexion est refusée avec `ACCOUNT_MIGRATED` et le lien store

### Story 24.2 : Module `migration` — lots, correspondances, identité, boutiques, membres

As a **super-admin**,
I want **importer l'identité, les boutiques et l'équipe d'un owner**,
So that **son tenant existe avec les bons accès (FUS-MIG1, MIG2)**.

> **Routé depuis la revue 19.12 (05/11/2026) :** - [ ] [Deferred-from: revue 19.12, 2026-11-05] `default_sale_flow=DOCUMENT` s'écrit par `TenantSaleFlowCommandPort.set(schema, SaleFlow.DOCUMENT)` (identity.api, livré par 19.12, audité) ; les logos de boutiques passent par `StoreLogoUseCase` (port de stockage, nom de fichier unique, `logo_path`) — ne pas écrire ces colonnes en SQL direct.

> **Routé depuis 19.13 (05/11/2026) :** - [ ] [Deferred-from: spec 19.13, 2026-11-05] Profil d'interface de l'owner importé : après `TenantSaleFlowCommandPort.set(schema, DOCUMENT)`, appeler `ModuleActivationRulesPort.applyInitialRules(schema)` (19.13 : désactive caisse, stock, inventaire, transferts selon les règles en base). Les fournisseurs font partie du module `crm` avec les clients et ne peuvent pas être désactivés seuls — décider ici comment les masquer à un owner Bref (sous-module ou masquage par le préréglage DOCUMENT) [keevo/resources/keevo/modules/crm/module.json] — à trancher avec l'import des clients.

> **Routé depuis la revue 19.13 (05/11/2026) :** - [ ] [Deferred-from: revue 19.13, 2026-11-05] Les règles d'activation sont par parcours (DOCUMENT) et non par plan Bref : les autres modules compris dans le plan (ex. exports, rapports avancés) restent activés pour un owner importé. Décider ici la liste exacte des fonctions équivalentes à Bref selon son plan au moment de la migration, puis l'appliquer par un jeu de règles (secteur ou jeu dédié) ou par `OwnerModuleActivation` après `applyInitialRules` [keevo/backend/src/main/resources/db/migration/V50__module_owner_activation.sql] — à trancher avec la table de correspondance des plans Bref.

**Acceptance Criteria:**

**Given** `migration_batches` et `migration_records(target_table, target_id, source_table, source_id, checksum, note)` et le module `migration` (plateforme, SUPER_ADMIN)
**When** l'export d'un owner est importé (étapes 1 à 3 du §10.2)
**Then** le compte owner est créé avec l'UUID Bref ou lié par téléphone puis email (bridge 24.7), le tenant est créé sans boutique par défaut (`seedDefaultStore=false`), `default_sale_flow=DOCUMENT`, les boutiques avec leurs logos rapatriés, les membres avec hash bcrypt copiés et rôles mappés (admin→MANAGER, seller→EMPLOYEE, viewer→VIEWER), et chaque ligne est tracée

**Given** le même import relancé
**When** il s'exécute
**Then** il est sans effet (`ON CONFLICT (id) DO NOTHING`) ou complète les lignes manquantes

### Story 24.3 : Import des produits (fusion par clé) et des clients (par établissement)

As a **outil de migration**,
I want **importer catalogue et clients (produits fusionnés par clé, clients gardés par établissement)**,
So that **le tenant respecte les règles d'unicité Keevo sans perdre d'historique**.

**Acceptance Criteria:**

**Given** des produits de même `productKey` dans plusieurs boutiques
**When** ils sont importés
**Then** un seul produit tenant (`track_stock=false`) est créé, les autres ids source pointent vers lui dans `migration_records`, et les produits `mergedIntoId` sont importés archivés et redirigés

**Given** des clients Bref (une fiche par boutique et téléphone)
**When** ils sont importés (ADR-08)
**Then** chaque fiche est importée dans l'établissement issu de sa boutique, sans fusion entre établissements ; le partage entre établissements est désactivé par défaut

### Story 24.4 : Import des documents, paiements et dépenses

As a **outil de migration**,
I want **importer factures, devis, lignes, paiements et dépenses**,
So that **l'historique commercial et les impayés sont intacts**.

**Acceptance Criteria:**

**Given** les documents Bref
**When** ils sont importés via le port d'import du module `invoicing` (sans `InvoiceLifecycleUseCase.issue`, sans événement de pont, sans consommation de quota)
**Then** devis puis factures sont insérés avec `legacy_reference = BRF-<8 premiers caractères de l'UUID>`, `legacy_public_token`, `issue_date` = `invoice.date` civile, `created_at`/`updated_at` d'origine, lignes liées par `product_key`, paiements dans `invoice_payments`, `payment_state` recalculé, et compteurs client/produit recalculés

**Given** un document dont le total stocké diffère du recalcul de plus de 1 XAF ou un montant non entier
**When** il est importé
**Then** la valeur Bref est conservée telle quelle, l'anomalie est inscrite dans `migration_records.note` et dans le rapport, sans correction silencieuse (R3)

- [ ] [Deferred-from: review 20-7, 2026-11-08] Imported tenants must get 90-day public links and their old Bref links: set `invoicing_document_preferences.public_link_days = 90` (`DocumentPreferences.IMPORTED_LINK_DAYS`), and call `InvoicePublicLinkUseCase.attachLegacy` for each imported invoice (`legacy_public_token`, `BRF-` reference); map Bref papers 53 mm → 58 and 110 mm → A4

### Story 24.5 : Import de la monétisation (ADR-07)

As a **outil de migration**,
I want **reporter plans, abonnements, crédits et compteurs**,
So that **personne ne perd ce qu'il a payé (FUS-MIG8)**.

**Acceptance Criteria:**

**Given** les abonnements, demandes de paiement, transactions Apple, crédit, packs SMS et compteurs du mois
**When** ils sont importés
**Then** le profil du tenant est celui que donne `SUBSCRIPTION_IN_FORCE` à la date de migration (grâce Apple incluse ; un owner sans abonnement en vigueur arrive en FREE, sans grâce supplémentaire : ADR-07), `expires_at` = fin de la période payée, `external_subscriptions` et `tenant_payments` sont alimentés, crédit et packs reportés au franc près, et `tenant_plan_events` reçoit `MIGRATION_BREF`

**Éléments routés vers 24.5 (story 21.2, 2026-11-09) :**
- [ ] [Deferred-from: 21.2 review, 2026-11-09] Import des événements RevenueCat déjà traités par api-bref [keevo/backend/src/main/resources/db/migration/V62__app_store_subscriptions.sql] — la table Keevo `revenuecat_webhook_event` a des colonnes différentes de celle de Bref (`id`, `type`, `app_user_id`, `original_transaction_id`, `environment`, `event_timestamp_ms`, `tenant_id`, `outcome`, détails d'achat, `processed_at` contre `subjectKey`, `eventTimestampMs`, `processedAt`, `createdAt` côté Bref) : l'import doit faire la correspondance (`subjectKey` → `original_transaction_id` quand c'est un identifiant de transaction, `outcome = 'APPLIED'`, `processed_at`) et créer les `external_subscriptions` des abonnements Apple en vigueur (`granted_expires_at` = `expires_at` importé, `last_event_ms` = dernier événement importé) pour qu'aucun événement ne soit rejoué ni qu'un événement ancien soit appliqué. Réglé par un test d'import sur une copie de la base Bref.

### Story 24.6 : Import des données annexes et index du portail

As a **outil de migration**,
I want **importer WhatsApp Business, IA, notifications et indexer les factures pour le portail**,
So that **l'owner et ses clients retrouvent tout**.

**Acceptance Criteria:**

**Given** le compte WABA, modèles, campagnes, messages, conversations IA, notifications
**When** ils sont importés
**Then** le token WABA est rechiffré avec la clé Keevo, les modules correspondants sont activés selon le profil, `invoice_phone_index` est alimenté, `ModuleProvisioner.provisionActiveModules` est appelé et l'audit reçoit `TENANT_IMPORTED {source: bref, counts}`

### Story 24.7 : Bridge d'identité et cas de collision

As a **commerçant présent dans Keevo et Bref**,
I want **retrouver mes deux entreprises sous un seul compte**,
So that **je n'ai pas deux identités (R2)**.

**Acceptance Criteria:**

**Given** un owner Bref dont le téléphone normalisé (puis l'email vérifié) existe dans Keevo
**When** il est importé
**Then** le compte Keevo existant est conservé (hash Keevo conservé), une membership OWNER est ajoutée sur le tenant importé, et il voit deux entreprises au login (19.6)

**Given** un username Bref déjà pris ou égal à un téléphone existant
**When** il est détecté
**Then** l'import de l'owner est bloqué avant écriture avec un cas listé dans le rapport à résoudre manuellement

**Éléments reportés routés vers 24.7 (revue 19.3, 2026-11-04) :**
- [ ] [Deferred-from: revue 19.3, 2026-11-04] importer `users.social_id`/`provider` de Bref dans `public.user_authenticators` (V48, `(provider, subject)` unique) pour chaque owner importé — sans ces lignes, un owner Bref Google/Apple n'est pas retrouvé par son identité sociale et retombe sur la liaison par email ou un nouveau compte [`JdbcUserAuthenticatorRepository`].

### Story 24.8 : Réconciliation automatique et bloquante

As a **responsable de la fusion**,
I want **comparer chaque owner importé à sa source, au franc près**,
So that **aucun owner n'est activé avec des données fausses (FUS-MIG3)**.

**Acceptance Criteria:**

**Given** les contrôles du §10.3 (compteurs par table après fusions expliquées, Σ ventes non annulées par boutique et par mois, Σ encaissé, Σ dépenses, impayés, échantillon de 20 documents champ à champ, abonnement/crédit, 5 liens publics)
**When** la réconciliation s'exécute
**Then** elle produit un rapport JSON + page dashboard ; tout écart hors tolérance met l'owner en état `RECONCILIATION_FAILED` et empêche l'activation

### Story 24.9 : Pilotage des vagues dans le dashboard et rollback

As a **super-admin**,
I want **planifier, exécuter, suivre et annuler une vague**,
So that **la migration est maîtrisée et observable (FUS-MIG5, MIG10, FUS-NFR10)**.

**Acceptance Criteria:**

**Given** une vague (liste d'owners signée)
**When** elle est lancée
**Then** chaque owner passe par planifié → gelé → exporté → importé → réconcilié → activé, avec durée par étape, métriques et logs corrélés `migrationBatchId`

**Given** un owner non encore activé
**When** le super-admin demande un rollback
**Then** `TenantDeletionService.deleteNow` supprime le tenant importé (schéma, memberships, comptes créés pour l'occasion uniquement, pas les comptes liés), `migration_records` est purgé pour cet owner, et api-bref est dégelé

**Given** l'activation
**When** elle est confirmée
**Then** api-bref passe l'owner `migrated`, le tenant passe ACTIVE, et un WhatsApp/SMS de confirmation avec le lien store est envoyé

### Story 24.10 : Continuité externe — liens publics et webhooks

As a **client final / owner**,
I want **que mes liens, paiements et messages WhatsApp continuent de fonctionner pendant la migration**,
So that **rien ne casse côté extérieur (FUS-MIG9)**.

**Acceptance Criteria:**

**Given** un lien public Bref d'un owner migré
**When** il est ouvert
**Then** api-bref (ou le reverse proxy) répond 302 vers la page publique Keevo qui résout `legacy_public_token` ; les liens des non-migrés sont inchangés

**Given** le routeur de webhooks (RevenueCat, MyCoolPay, Meta) devant les deux backends
**When** un événement arrive
**Then** il est livré aux deux, chacun ignore les owners qu'il ne connaît pas, et aucun événement n'est perdu si l'un des deux est indisponible (file de relivraison)

### Story 24.11 : Pré-migrateur OTA dans l'app Bref actuelle

As a **owner Bref**,
I want **être prévenu et que mon appareil soit prêt avant ma migration**,
So that **mes ventes en attente ne se perdent pas (FUS-MIG6, R8)**.

**Acceptance Criteria:**

**Given** une mise à jour EAS Update sur le canal `production` de l'app Bref actuelle
**When** elle est appliquée
**Then** l'app affiche la bannière de migration (date si planifiée), force `syncAll()` puis `resumePausedMutations()`, signale au serveur le nombre de mutations en attente, et liste les lignes locales sans équivalent serveur

**Given** un owner planifié avec des mutations en attente sur un appareil
**When** le gel est demandé (24.9)
**Then** le gel est refusé pour cet owner et le motif est visible dans le dashboard et dans l'app

**Given** un owner `migrated`
**When** il ouvre l'ancienne app
**Then** il voit « Votre compte a été migré, mettez à jour l'application » avec le lien store

### Story 24.12 : Dry-run complet sur copie de production

As a **responsable de la fusion**,
I want **importer 100 % des owners d'une copie de prod Bref sur staging**,
So that **les vagues réelles ne découvrent aucun cas nouveau**.

**Acceptance Criteria:**

**Given** une copie anonymisée ou protégée de la base api-bref et le staging Keevo
**When** tous les owners sont importés et réconciliés
**Then** 100 % sont réconciliés sans écart ou ont un cas documenté (et une décision), la durée moyenne par owner est < 60 s, le temps total d'une vague de 100 owners est mesuré, et le rapport est archivé dans `research/fusion-mesures/`

**Given** un deuxième dry-run une semaine plus tard
**When** il est relancé sur une copie fraîche
**Then** les résultats sont identiques (idempotence et stabilité)

### Story 24.13 : Runbook et communication

As a **exploitant / support**,
I want **un runbook de vague et des messages prêts**,
So that **chaque vague se déroule pareil et les owners savent quoi faire**.

**Acceptance Criteria:**

**Given** le runbook (J-3 notification, J0 gel/import/réconciliation/activation, J+1 vérification, critères d'arrêt, rollback, contacts)
**When** il est relu sur le dry-run
**Then** chaque étape est exécutable telle qu'écrite ; les messages WhatsApp/SMS/email (annonce, rappel, confirmation, incident) existent en fr et en

### Story 24.14 : Vagues pilotes

As a **responsable de la fusion**,
I want **migrer 3 à 5 owners volontaires et les accompagner**,
So that **le procédé est validé sur de vrais commerçants avant d'élargir**.

**Acceptance Criteria:**

**Given** 3 à 5 owners volontaires (mix free/payant, Android/iOS si possible)
**When** ils sont migrés
**Then** chacun est contacté après sa première connexion, ses retours sont consignés, aucun écart de données n'est constaté à J+7, et la décision « go » pour les vagues suivantes est écrite

### Story 24.15 : Vagues de masse (2 à 6)

As a **responsable de la fusion**,
I want **migrer les owners par groupes de risque croissant**,
So that **les incidents arrivent quand ils coûtent le moins**.

**Acceptance Criteria:**

**Given** l'ordre (2) free inactifs > 60 j, (3) free actifs, (4) payants Mobile Money, (5) payants Apple — après validation sandbox de 21.2 —, (6) collisions (24.7)
**When** chaque vague est exécutée
**Then** son rapport est archivé, le taux d'échec de réconciliation est < 1 % (les échecs repassent individuellement), et aucune vague ne démarre tant que la précédente a un incident ouvert de sévérité haute

### Story 24.16 : Retardataires et gel global

As a **responsable de la fusion**,
I want **traiter les owners qui ne se sont jamais reconnectés**,
So that **api-bref peut être arrêté (R7)**.

**Acceptance Criteria:**

**Given** les owners restants après la vague 6
**When** la date de gel global annoncée arrive
**Then** ils sont migrés sans interaction (mêmes contrôles), reçoivent un message avec le lien store, et l'ancienne app affiche l'écran « compte migré » ; à la fin, api-bref ne compte plus aucun owner `none`/`scheduled`

---

## Epic 25 : Convergence et décommissionnement (P5)

### Story 25.1 : api-bref en lecture seule puis arrêt

As a **exploitant**,
I want **arrêter api-bref proprement**,
So that **on ne maintient plus deux backends**.

**Acceptance Criteria:**

**Given** aucun owner actif sur api-bref et 4 semaines sans écriture
**When** le service passe en lecture seule puis est arrêté
**Then** une sauvegarde finale chiffrée est archivée (durée de conservation légale décidée), `api.brefpoint.app` renvoie une page de migration (410/301), et les redirections de liens publics sont servies par le reverse proxy de façon permanente

### Story 25.2 : Webhooks et intégrations sur Keevo seul

As a **exploitant**,
I want **pointer RevenueCat, MyCoolPay et Meta directement sur Keevo**,
So that **le routeur transitoire disparaît**.

**Acceptance Criteria:**

**Given** les consoles des fournisseurs
**When** les URLs sont repointées
**Then** un événement de test de chaque fournisseur est traité par Keevo, et le routeur est retiré

### Story 25.3 : Retrait du code transitoire

As a **développeur**,
I want **supprimer ce qui ne servait qu'à la migration**,
So that **le code reste simple**.

**Acceptance Criteria:**

**Given** le module `migration`, le mode legacy de l'app (23.11), le pré-migrateur et les canaux EAS de l'ancienne app
**When** ils sont retirés
**Then** `migration_records` est archivé hors base chaude (export daté), le module est désinscrit, les tests restent verts, et les ArchUnit/contrats sont mis à jour

- [ ] [Deferred-from: review 20-6, 2026-11-07] `GET /api/v1/clients` without `limit` still returns the whole client list (kept for installed V1 apps) [keevo/backend/src/main/java/com/keevo/crm/contact/adapter/in/web/ClientController.java listClients] — once V1 apps are retired, make `limit` mandatory (or cap it server-side)

### Story 25.4 : Fin de la distribution Keevo hors store (ADR-02)

As a **owner produit**,
I want **appliquer la décision de marque**,
So that **les utilisateurs voient un produit cohérent**.

**Acceptance Criteria:**

**Given** l'ADR-02 (marque finale Bref Point, une seule fiche)
**When** moins de 2 % des appareils actifs utilisent encore l'APK Keevo
**Then** les releases GitHub de l'APK sont retirées, les liens de téléchargement Keevo pointent vers la fiche Play Bref Point et la page d'accueil Keevo annonce le passage à Bref Point

### Story 25.5 : Rétrospective et mise à jour de l'architecture

As a **équipe**,
I want **capitaliser sur la fusion**,
So that **la documentation reflète le système réel**.

**Acceptance Criteria:**

**Given** les rapports de vagues et les incidents
**When** `bmad-retrospective` est exécuté sur les epics 18–25
**Then** `architecture.md`, le PRD et les contrats sont à jour, et les écarts restants sont versés au backlog

---

## Jalons de décision (go / no-go)

| Jalon | Quand | Critères pour continuer | Si non |
|---|---|---|---|
| **G0** fin P0 | après 18.2 | décisions écrites, chiffres connus (la refonte n'a plus à être en prod : décision du 03/11/2026) — **ouvert le 03/11/2026** (ADR-01 à 09 ; mesures `research/fusion-mesures/rapport-mesures-2026-11-03-fusion.md`, source Bref retenue par l'utilisateur) | on ne démarre pas P1 |
| **G1** fin P1b | après 20.12, 20.13 et l'epic 21 | **toutes** les fonctionnalités Bref répliquées dans l'API Keevo (parcours Bref vert, API super-admin héritée), suite Flutter verte, charge 19.11 tenue | corriger avant P3 / avant l'outil |
| **GL** go-live de la refonte | après G1, 22.13, 23.11 et 24.12 | tout le merge Keevo × Bref : (1) epics 19, 20, 21 — toutes les fonctionnalités Bref dans l'API Keevo ; (2) app React Native à parité Keevo et Bref, validée sur staging (22.1–22.13, 23.1–23.11) ; (3) outil de migration prêt et dry-run 100 % réconcilié sur copie de prod (24.1–24.10, 24.12) ; puis 17.9 (régression multi-surface : parcours Keevo et Bref, app Flutter V1 et app RN) | **pas de mise en production de la refonte** (16.7) |
| **G2** fin bêta P2 | 22.15 palier 10 % | zéro perte de donnée, crash-free ≥ 99,5 %, quarantaine < 0,5 % des opérations | rester à 10 %, corriger |
| **G3** avant vagues | après 24.12 et 23.12 | dry-run 100 % réconcilié, app Bref unifiée publiée, runbook relu | pas de vague |
| **G4** après pilotes | 24.14 | aucun écart à J+7, retours pilotes traités | pas de vague de masse |
| **G5** avant arrêt api-bref | 25.1 | zéro owner actif, 4 semaines sans écriture | prolonger la lecture seule |

## Risques suivis (renvoi vers les stories)

| Risque (§11) | Stories qui le traitent |
|---|---|
| R1 volume de tenants | 18.1, 19.11 |
| R2 collisions d'identité | 18.1, 19.6, 24.7, vague 6 de 24.15 |
| R3 factures incohérentes | 18.1 (Q7, Q13), 24.4, 24.8 |
| R4 reporting multi-source | 20.9, 20.12 |
| R5 qualité de l'app RN | 22.4–22.7, 22.13, G2 |
| R6 abonnements Apple | 21.2, 24.10, vague 5 |
| R7 retardataires | 19.10, 24.11, 24.16 |
| R8 mutations locales Bref | 24.11 |
| R9 crédits et packs | 21.3, 24.5, 24.8 |
| R10 secrets | 19.9, 21.5, 24.6 |
| R11 identité de l'app Keevo | 18.4 |
| R12 charge solo | ordonnancement, jalons G0–G5 ; 18.3 (suivi des évolutions Bref, sans gel) |

## Annexe C — Décisions du gate P0 (story 18.2, session du 03/11/2026)

> Décisions prises par l'owner produit le 03/11/2026, avant les chiffres de production de 18.1 (kit livré, run sur réplica en attente). Les ADR marqués « à revoir avec 18.1 » peuvent être rouverts si les chiffres le justifient. Deux décisions de cadrage du même jour complètent ce gate : **pas de gel de Bref** (18.3 = suivi des évolutions) et **go-live de la refonte au jalon GL**, après tout le merge.

| ADR | Question | Décision | Recommandation de l'analyse | Stories impactées (mises à jour) |
|---|---|---|---|---|
| ADR-01 | Q12 pays | **Multi-pays dès la fusion** : CM, GA, TD, CD, SN ; TVA par pays (table Bref), indicatifs, journées au fuseau du pays ; devise et arrondi reproduits à l'identique de Bref, pays par pays | suivie | 20.5 (taux et devise par pays), 19.1 (indicatifs), reporting (fuseau des journées) |
| ADR-02 | Q11 marque | **Marque finale Bref Point, une seule identité store `com.brefpoint.app`.** Fait établi : Keevo n'est sur aucun store (APK GitHub Releases, `com.example.keevo`, préfixe refusé par Google Play) | contraire (recommandait deux marques, décision plus tard) | D7 révisée, FUS-FR33, 18.4 (plus d'identité Keevo à sécuriser), 22.1, 22.14 (passage APK → Play Bref Point : installation unique, mêmes identifiants, aucune perte), 22.15, 22.16, 25.4 |
| ADR-03 | Q10 FREE | **1 employé** dans FREE (valeur de départ, modifiable) ; les tenants Keevo FREE au-delà gardent leurs employés (droit acquis), sans nouvel ajout | contraire sur le nombre (recommandait 3) ; droit acquis suivi | 19.7 |
| ADR-04 | Q10 essai | **Essai d'1 mois** du plan le plus complet pour tout nouvel inscrit (valeur de départ, modifiable) ; essais de 6 mois en cours honorés | suivie | 19.7, 21.10 |
| ADR-05 | Q10 grille | **Rien n'est figé dans le code** : plans, modules, plafonds, quotas, cycles, prix, essais, remises, promotions créés, modifiés et archivés depuis le dashboard super-admin, sans redéploiement ; grille Bref = données de départ | étendue (l'analyse fixait une grille) | FUS-FR7, FUS-FR40 (nouvelle), 19.7 (plans = données), 21.10 (nouvelle), 21.1, 21.2, 23.7 |
| ADR-06 | PREMIUM Keevo | **TEAM, prix inchangé** jusqu'à la fin de la période payée | suivie | 19.7 |
| ADR-07 | §7.10 expirés | **FREE à la migration**, sans grâce : on applique `SUBSCRIPTION_IN_FORCE` comme Bref le fait déjà, l'owner ne doit pas sentir la migration | contraire (recommandait 30 j de grâce) | 24.5 |
| ADR-08 | §7.5 clients | **Clients par établissement** (boutique, restaurant…) : liste filtrée sur l'établissement courant, employé limité au sien, owner « tous mes établissements », **partage optionnel** réglé par l'owner pour chaque établissement, pas de fusion automatique, doublon signalé seulement dans le même établissement | contraire (recommandait des clients au niveau tenant avec fusion) | FUS-FR15, 20.2, 20.6 (dérivation dans l'établissement courant), 24.3, 21.6 (cibles de campagne par établissement) |
| ADR-09 | §9.5 publication | **Publication précoce** de l'app unifiée sous `com.brefpoint.app` avec mode legacy | suivie | 23.11, 23.12 |

**Points à reprendre dans les specs (pas de nouvelle décision nécessaire) :**
- ADR-01 : la devise de chaque pays (XAF pour CM/GA/TD, XOF pour SN, CDF ou USD pour CD) doit reproduire Bref ; 20.5 le vérifie dans le code Bref avant d'écrire sa spec.
- ADR-02 : Bref Point devient l'app de tous ; les écrans Keevo (caisse, stock, inventaire…) y arrivent par les modules (epic 22), sous la marque Bref Point.
- ADR-08 : 20.6 (dérivation depuis un document) cherche le client par téléphone **dans l'établissement courant**.


## Annexe A — Bloc de suivi dans `sprint-status.yaml`

> **Ajouté au `sprint-status.yaml` le 2026-11-01** (entre l'epic 17 et la piste V1). Ce bloc reste ici comme référence ; la source de vérité des statuts est `sprint-status.yaml`. Ne pas créer de fichier de story 19+ avant le gate G0.

```yaml
  # ─────────────────────────────────────────────────────────
  # FUSION KEEVO × BREF POINT — epics-fusion-keevo-bref.md
  # Ordre (03/11/2026) : tout le merge sur branche/staging (18 → 19–21 → 22.1–22.13, 23.1–23.11 → 24.1–24.10, 24.12) = GL → go-live refonte (17-9, 16-7) → stores, OTA, vagues (22.14–22.16, 23.12, 24.11, 24.13–24.16) → 25
  # ─────────────────────────────────────────────────────────
  epic-18: backlog  # P0 Cadrage
  18-1-mesures-bases-bref-keevo: backlog
  18-2-decisions-produit-gate-p0: backlog
  18-3-suivi-evolutions-bref: backlog
  18-4-identites-app-comptes-stores: backlog
  18-5-brief-prd-architecture-fusion: backlog
  epic-18-retrospective: optional

  epic-19: backlog  # P1a Socle d'accueil
  19-1-users-identifiants-multiples: backlog
  19-2-authenticator-otp: backlog
  19-3-authenticators-google-apple: backlog
  19-4-login-username-membres: backlog
  19-5-roles-manager-viewer-permissions: backlog
  19-6-multi-roles-multi-entreprises: backlog
  19-7-profils-pro-team-business: backlog
  19-8-compteurs-usage-quotas: backlog
  19-9-sms-email-notifications-persistantes: backlog
  19-10-versions-minimales-app: backlog
  19-11-charge-nombre-tenants: backlog
  19-12-boutiques-etendues-parcours-vente: backlog
  epic-19-retrospective: optional

  epic-20: backlog  # P1b Modules métier Bref
  20-1-catalogue-sans-stock-cle-fusion: backlog
  20-2-crm-etendu-consentement: backlog
  20-3-devis: backlog
  20-4-encaissements-echeancier-retard: backlog
  20-5-remise-livraison-tva-echeance: backlog
  20-6-derivation-catalogue-crm: backlog
  20-7-modeles-signatures-liens-publics: backlog
  20-8-envoi-sms-email: backlog
  20-9-reporting-multi-source: backlog
  20-10-module-expenses: backlog
  20-11-module-finance: backlog
  20-12-acceptation-parcours-bref: backlog
  epic-20-retrospective: optional

  epic-21: backlog  # P1b Monétisation & engagement
  21-1-payments-mobile-money: backlog
  21-2-storekit-revenuecat: backlog
  21-3-credit-packs-sms: backlog
  21-4-ai-assistant: backlog
  21-5-whatsapp-cloud: backlog
  21-6-campagnes: backlog
  21-7-client-portal: backlog
  21-8-couche-outils-ia-mcp: backlog  # avant 21-4
  21-9-serveur-mcp-externe: backlog  # après 21-4
  epic-21-retrospective: optional

  epic-22: backlog  # P2 App RN parité Keevo
  22-1-monorepo-shell-deux-identites: backlog
  22-2-stockage-chiffre-schema: backlog
  22-3-auth-sessions-tenant-picker: backlog
  22-4-file-operations-push: backlog
  22-5-pull-delta-fusion: backlog
  22-6-rejet-quarantaine-journal: backlog
  22-7-gate-7-jours: backlog
  22-8-entitlements-modules-routes: backlog
  22-9-ecrans-coeur: backlog
  22-10-catalogue-stock-inventaire: backlog
  22-11-caisse-pos: backlog
  22-12-crm-facturation-rapports-exports: backlog
  22-13-tests-parite-maestro: backlog
  22-14-derniere-release-flutter: backlog
  22-15-beta-deploiement-ios: backlog
  22-16-retrait-flutter: backlog
  epic-22-retrospective: optional

  epic-23: backlog  # P3 App RN parité Bref
  23-1-onboarding-otp-social: backlog
  23-2-parcours-document-5-etapes: backlog
  23-3-devis-encaissements-impayes: backlog
  23-4-modeles-impression-partage: backlog
  23-5-depenses-finance: backlog
  23-6-ia-whatsapp-campagnes: backlog
  23-7-mon-plan-paiement: backlog
  23-8-portail-client: backlog
  23-9-securite-langues-notifications: backlog
  23-10-bascule-caisse-document: backlog
  23-11-double-backend-mode-legacy: backlog
  23-12-publication-identite-bref: backlog
  epic-23-retrospective: optional

  epic-24: backlog  # P1c outil + P4 vagues
  24-1-api-bref-statut-gel-export: backlog
  24-2-migration-identite-boutiques-membres: backlog
  24-3-import-produits-clients: backlog
  24-4-import-documents-paiements-depenses: backlog
  24-5-import-monetisation: backlog
  24-6-import-annexes-index-portail: backlog
  24-7-bridge-identite-collisions: backlog
  24-8-reconciliation-bloquante: backlog
  24-9-pilotage-vagues-rollback: backlog
  24-10-liens-publics-webhooks: backlog
  24-11-pre-migrateur-ota: backlog
  24-12-dry-run-copie-prod: backlog
  24-13-runbook-communication: backlog
  24-14-vagues-pilotes: backlog
  24-15-vagues-de-masse: backlog
  24-16-retardataires-gel-global: backlog
  epic-24-retrospective: optional

  epic-25: backlog  # P5 Convergence
  25-1-api-bref-lecture-seule-arret: backlog
  25-2-webhooks-keevo-seul: backlog
  25-3-retrait-code-transitoire: backlog
  25-4-marque-fiche-store: backlog
  25-5-retrospective-architecture: backlog
  epic-25-retrospective: optional
```

## Annexe B — Prochaines étapes BMAD

1. Exécuter **18.1** (mesures) et **18.2** (décisions) : ce sont les seules stories démarrables aujourd'hui.
2. `bmad-product-brief` → `bmad-prd` → `bmad-architecture` (story 18.5) pour aligner PRD et architecture sur ce plan.
3. `bmad-sprint-planning` : vérifier la complétude (readiness) après G0 ; le bloc de l'Annexe A est déjà dans `sprint-status.yaml`.
4. **Go-live de la refonte (17.9 puis 16.7) au jalon GL** seulement : tout le merge Keevo × Bref (backend, app RN, outil de migration validé) doit d'abord être prêt (décision du 03/11/2026).
