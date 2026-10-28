# Gap Analysis — Besoins Client Importateur vs Keevo
**Date :** 15 mai 2026
**Auteur :** John (PM) | Analyse technique : basée sur le code source et les artifacts Keevo actuels

---

## Contexte

Ce document compare les 5 blocs de besoins d'un client importateur-commerçant (4 points de vente + 1 warehouse, activité import Chine) avec l'état réel de l'application Keevo à la date du 15 mai 2026.

Le niveau d'avancement actuel de Keevo : **Epics 1–6 terminés, Epic 7 en cours (7/7 stories livrées ou en review), Epic 8 partiellement terminé, Epic 9 partiellement terminé.**

---

## Bloc 1 — Achat / Vente / Gestion de Stock

*Ce que le client demande :*
> Recette/dépense par boutique depuis tablette, bénéfice par jour/semaine/mois, stock entrant avec photo, sorties inter-boutiques sans vente, magasin répertorié en quantité et valeur, accès stock total et par boutique en quantité + valeur.

### ✅ Déjà disponible dans Keevo

| Besoin | Coverage | Référence |
|--------|----------|-----------|
| Photo de l'article à l'entrée du stock | **Complet** — caméra + galerie, compression ≤500KB, stockage S3, placeholder initiales | Story 2.1 (AC3) |
| Sortie de stock inter-boutiques **sans que ce soit une vente** | **Complet** — module `stock_transfers` avec statuts `IN_TRANSIT` / `COMPLETED`, typé `TRANSFER_OUT`/`TRANSFER_IN` distinct des ventes | Story 3.3 |
| Magasin (warehouse) répertorié et évalué en quantité et valeur | **Complet** — entité Warehouse dans le module Store, affiché dans la vue globale avec `SUM(qty × price)` | Story 3.1 + 3.2 |
| Stock en quantité ET en valeur par boutique + total cumulé | **Complet** — `GlobalStockOverviewPage` : nombre de SKUs, valeur XAF par store/warehouse, footer "Valeur totale" global | Story 3.2 |
| Recette (CA) par boutique par jour | **Complet** — POS Day Close + Dashboard matinal par store | Story 4.4 + 7.1 |
| Recette par boutique par semaine | **Complet** — rapport hebdomadaire automatique envoyé via WhatsApp | Story 7.3 |
| Recette par boutique par mois | **Complet** — Dashboard rentabilité, filtres Today/7j/30j/Personnalisé | Story 7.4 |
| Accès depuis tablette | **Complet** — Flutter responsive (compact/medium/expanded breakpoints), tablette = medium breakpoint | Architecture |
| Transfert inter-boutiques avec traçabilité complète | **Complet** — `stock_movements`, historique, audit trail immutable | Story 3.3 + 1.8 |

### ⚠️ Partiellement couvert

| Besoin | Gap identifié |
|--------|--------------|
| **Dépenses par boutique** | Keevo enregistre le CA (recettes) mais **pas les charges/dépenses** (loyer, salaires, impôts). Le mot "dépense" dans le contexte client désigne les charges fixes — absent de Keevo. |
| **Bénéfice réalisé** | Keevo calcule la **marge brute** (Prix vente - Prix achat). Le **bénéfice net** (marge - charges) est impossible sans le module Charges. |

### ❌ Non disponible

| Besoin | Commentaire |
|--------|-------------|
| Charges par boutique (salaire, loyer, impôts) | Aucune table `charges`, aucun module de dépenses dans le codebase |
| Disponibilité en caisse (trésorerie réelle) | Keevo ne gère pas le cash en caisse — uniquement les ventes enregistrées dans le POS |

---

## Bloc 2 — Section Privée Propriétaire/Femme (Voyage d'achat)

*Ce que le client demande :*
> Accessible par le propriétaire ET sa femme : quantités achetées à chaque voyage, allocation au magasin ou boutiques au dédouanement. Valeurs (montants) accessibles uniquement par le propriétaire.

### ✅ Déjà disponible dans Keevo

| Besoin | Coverage |
|--------|----------|
| Base fournisseurs (nom, téléphone) | **Partiel** — Story 2.5 gère les fournisseurs avec CRUD complet (nom, téléphone, email, etc.) |
| Allocation de stock au magasin/boutiques | **Partiel** — la saisie d'entrée de stock et les transferts inter-boutiques existent (Stories 2.3, 3.3) |

### ❌ Non disponible

| Besoin | Commentaire |
|--------|-------------|
| Concept de **"Voyage d'achat"** (trip) | Aucune entité `voyage` ou `purchase_trip` dans le modèle de données |
| Quantités achetées par voyage regroupées | Keevo ne lie pas les entrées de stock à un voyage d'approvisionnement spécifique |
| **Allocation au dédouanement** (date précise de déblocage vers magasin/boutiques) | Le workflow "dédouané → distribué" n'existe pas |
| **Rôle Co-propriétaire (femme)** | Keevo n'a que 2 rôles : OWNER et EMPLOYEE. Pas de rôle SPOUSE/CO_OWNER avec permissions customisées |
| **Masquage des valeurs monétaires** selon le rôle | Le rôle EMPLOYEE masque déjà les montants (AC6 HF-2), mais le rôle "femme" doit voir les quantités ET être bloquée sur les valeurs d'achat — profil inexistant |

---

## Bloc 3 — Tableau de Suivi des Conteneurs/Cargos

*Ce que le client demande :*
> Date de chargement, date d'arrivée prévue, numéro de colis, code conteneur, nom du cargo, description marchandise, date de retrait au cargo, lieu de dépôt, date de déballage.

### ✅ Déjà disponible dans Keevo

Aucun module spécifique au suivi de conteneurs n'est implémenté. Cependant, ce besoin est **déjà identifié dans le PRD Keevo comme fonctionnalité "Growth"** :

> *"Tracking colis importés : suivi manuel configurable (Commandé → En Transit → Réceptionné → Partiellement reçu) ET intégration APIs transporteurs (DHL, FedEx, 17TRACK)"*

Le design architectural est donc prévu — pas encore implémenté.

### ❌ Non disponible (à développer)

| Champ requis par le client | Status |
|---------------------------|--------|
| Date de chargement du conteneur | ❌ |
| Date d'arrivée prévue | ❌ |
| Numéro de paquets/colis dans le conteneur | ❌ |
| Code du conteneur | ❌ |
| Nom du cargo (navire) | ❌ |
| Description de la marchandise | ❌ |
| Date de retrait au cargo | ❌ |
| Lieu de dépôt (magasin, boutique, entrepôt) | ❌ |
| Date de déballage | ❌ |
| Accès conjoint propriétaire + femme | ❌ |

Ce module est un **tracking logistique simplifié** — sans intégration API transporteur (simple saisie manuelle des dates).

---

## Bloc 4 — Tableau des Achats en Chine (Commandes Fournisseurs)

*Ce que le client demande :*
> Jour d'achat en Chine, nom du marché, nom + numéro du vendeur, numéro de la boutique destinataire, date de livraison prévue, description marchandise, quantités, prix unitaires, montant total, avance versée, reste à payer.

### ✅ Déjà disponible dans Keevo

| Besoin | Coverage |
|--------|----------|
| Nom et téléphone du vendeur (fournisseur) | **Partiel** — Story 2.5 : CRUD fournisseurs avec nom, téléphone, email, notes |
| Description de la marchandise / catégorie | **Partiel** — les catégories produits existent, peuvent être utilisées comme description |

### ❌ Non disponible

| Champ requis | Commentaire |
|-------------|-------------|
| **Bon de commande / Purchase Order** | Aucune entité `purchase_order` dans le schéma |
| Date d'achat en Chine | Non modélisé |
| Nom du marché d'achat | Non modélisé |
| Boutique destinataire par ligne de commande | Non modélisé |
| **Avance versée** (acompte) | Aucune notion de paiement partiel côté achat |
| **Reste à payer** par commande/fournisseur | Non modélisé |
| Montant total par commande | Non modélisé |
| Lien entre commande Chine → conteneur → stock reçu | Chaîne logistique entière absente |

---

## Bloc 5 — États Financiers Complets

*Ce que le client demande :*
> Charges par boutique + récapitulatif, produits (disponibilité en caisse) par boutique + récapitulatif, remontée recettes entre boutiques (vers boutique mère chaque lundi matin), gestion banques (CCA, Afriland First Bank, Focep, Firstrust, Advance) avec dépôts/retraits/soldes, dettes bancaires avec échéances, tableau charges (salaires+employés, loyers+boutiques, impôts).

### ✅ Déjà disponible dans Keevo

| Besoin | Coverage |
|--------|----------|
| CA (produits = recettes) par boutique | **Complet** — Stories 7.1 à 7.4 : dashboard, rapports EOD, hebdo, rentabilité |
| Récapitulatif CA de toutes les boutiques | **Complet** — Dashboard global + rapport hebdomadaire multi-store |
| Noms des employés avec leurs ventes | **Complet** — rapport EOD inclut breakdown par employé |
| Noms des boutiques | **Complet** — configurés dans Story 3.1 |

### ❌ Non disponible

| Besoin | Commentaire |
|--------|-------------|
| **Charges par boutique** (loyer, salaires, impôts) | Aucun module de dépenses/charges dans Keevo |
| **Tableau charges** (salaire+nom employé, loyer+nom boutique, impôt+nom) | Inexistant |
| **Disponibilité en caisse** = recettes - charges encaissées | Impossible sans module charges |
| **Remontée de recettes vers la boutique mère** (tracking du virement hebdomadaire) | Inexistant — il n'y a pas de notion de "boutique mère" ni de transfert de trésorerie entre stores |
| **Comptes bancaires** (CCA, Afriland, etc.) | Aucun module bancaire — Keevo est un outil de gestion de stock/ventes, pas un outil de trésorerie |
| **Dépôts / Retraits bancaires** avec solde | Inexistant |
| **Dettes bancaires + dates d'échéances** | Inexistant |

---

## Récapitulatif Global

| Bloc | Couvert par Keevo | À développer |
|------|:-----------------:|:------------:|
| 1. Stock, ventes, photos, transferts, valorisation | **~80%** couvert | Charges + trésorerie caisse |
| 2. Section privée voyages d'achat | **~15%** couvert | Rôle co-propriétaire + concept voyage |
| 3. Suivi conteneurs/cargos | **0%** (prévu Growth) | Module complet |
| 4. Achats Chine (purchase orders) | **~10%** couvert | Module commandes fournisseurs |
| 5. États financiers | **~25%** couvert | Charges, trésorerie, banque |

---

## Ce qui reste à développer

Les développements manquants se regroupent en **6 modules** :

---

### Module A — Charges & Dépenses par boutique
**Scope :** Saisie des charges (salaires, loyer, impôts, autres) par boutique et date. Tableau récapitulatif par boutique + total général. Intégration dans le P&L (bénéfice = CA - coût marchandises vendues - charges).

**Composants :**
- Table `charges` : `id`, `store_id`, `category` (SALAIRE/LOYER/IMPOT/AUTRE), `description`, `employee_id` (nullable, pour les salaires), `amount` (XAF), `charge_date`, `created_by`, `created_at`
- CRUD backend + endpoints REST
- Flutter : formulaire de saisie, liste par boutique, récapitulatif multi-boutiques
- Intégration dans le dashboard rentabilité (Story 7.4) pour afficher le bénéfice net

**Effort estimé :** **8–10 jours développeur** (2–3 stories)

---

### Module B — Trésorerie (caisse disponible + remontée recettes)
**Scope :** Suivi du cash disponible en caisse après déduction des charges. Tracking des remontées de recettes vers la boutique mère (enregistrement de la date et du montant remis chaque semaine).

**Dépend de :** Module A (Charges)

**Composants :**
- Vue "Caisse" par boutique : recettes du jour/semaine/mois - charges = disponible
- Formulaire "Remontée de recettes" : date, montant, boutique source → boutique mère
- Historique des remontées

**Effort estimé :** **3–5 jours développeur** (1–2 stories)

---

### Module C — Gestion Bancaire
**Scope :** Multi-comptes bancaires (CCA, Afriland First Bank, Focep, Firstrust, Advance). Dépôts et retraits avec solde courant. Tableau des dettes avec montant, banque et date d'échéance.

**Composants :**
- Table `bank_accounts` : nom de la banque, numéro de compte, solde courant
- Table `bank_transactions` : type (DEPOT/RETRAIT), montant, date, libellé
- Table `bank_debts` : banque, montant, taux, date d'échéance, statut (EN_COURS/SOLDÉ)
- Vue dashboard : solde par banque + total, alertes d'échéances imminentes
- Flutter : CRUD comptes, saisie transactions, tableau dettes

**Effort estimé :** **15–18 jours développeur** (4–5 stories)

---

### Module D — Suivi des Conteneurs / Cargos
**Scope :** Registre de chaque conteneur importé. Workflow de statuts manuels. Accessible par le propriétaire ET la femme (rôle CO_OWNER à créer).

**Composants :**
- Table `containers` : code_conteneur, nom_cargo, date_chargement, date_arrivee_prevue, description_marchandise, created_by
- Table `container_parcels` : container_id, numero_colis, date_retrait, lieu_depot, date_deballage
- Workflow statuts : `CHARGE → EN_TRANSIT → ARRIVE → RETIRE → DEPOSE → DEBALLE`
- Flutter : liste conteneurs, formulaire création, fiche détail avec timeline de statuts

**Effort estimé :** **8–10 jours développeur** (2–3 stories)

> **Note :** Ce module est déjà inscrit dans le **PRD Keevo Growth**. Ce client peut en être l'implémenteur "pilote" et bénéficier de la fonctionnalité avant sa sortie publique.

---

### Module E — Commandes Fournisseurs Chine (Purchase Orders)
**Scope :** Tableau des achats effectués sur les marchés en Chine, par voyage, avec tracking des avances et du solde dû.

**Composants :**
- Table `purchase_orders` : date_achat, nom_marche, fournisseur_id (lien Story 2.5), description_marchandise, boutique_destinataire_id, date_livraison_prevue
- Table `purchase_order_items` : produit_description, quantite, prix_unitaire, montant_total
- Table `purchase_order_payments` : montant_avance, date_versement, reste_a_payer (calculé)
- Vue : liste des PO avec statut paiement (SOLDÉ / AVANCE_PARTIELLE / NON_PAYÉ)
- Lien possible avec Module D (un PO → un conteneur)

**Effort estimé :** **8–10 jours développeur** (2–3 stories)

---

### Module F — Rôle CO_OWNER (accès conjoint avec restriction valeurs)
**Scope :** Nouveau rôle SPOUSE/CO_OWNER permettant à la femme du propriétaire d'accéder aux modules D et E (conteneurs, achats Chine) avec visibilité sur les quantités mais **masquage des valeurs monétaires d'achat** (prix unitaires, montants, avances).

**Composants :**
- Nouveau rôle `CO_OWNER` dans le système RBAC (Spring Security + JWT)
- Politique de masquage : `buyPrice`, `totalAmount`, `advance`, `remainingAmount` → remplacés par `"—"` pour CO_OWNER
- Invitation via WhatsApp (même mécanisme que l'invitation employé — Story 3.5)
- Flutter : même masquage que le rôle EMPLOYEE pour les valeurs financières sensibles

**Effort estimé :** **5–7 jours développeur** (1–2 stories)

---

## Tableau Récapitulatif des Développements

| Module | Description | Jours estimés | Pertinence |
|--------|-------------|:-------------:|:----------:|
| **A** | Charges & Dépenses par boutique | 8–10 j | Standard Keevo |
| **B** | Trésorerie / Caisse / Remontée recettes | 3–5 j | Standard Keevo |
| **C** | Gestion Bancaire (comptes, dettes) | 15–18 j | Patch client |
| **D** | Suivi Conteneurs / Cargos | 8–10 j | Growth Keevo |
| **E** | Commandes Fournisseurs Chine | 8–10 j | Patch client |
| **F** | Rôle CO_OWNER | 5–7 j | Patch client |
| | **TOTAL** | **47–60 j** | |

---

## Conclusion

### Ce qui est déjà là (immédiatement utilisable par ce client)

Keevo couvre **nativement et robustement** le cœur des besoins opérationnels quotidiens de ce client :

- Gestion de stock multi-boutiques avec photos, seuils, valorisation en XAF
- Transferts inter-boutiques tracés (non comptabilisés comme ventes)
- Warehouse répertorié en quantité et en valeur
- Vue centralisée du stock par boutique/magasin/total
- Revenus (CA) par boutique par jour, par semaine, par mois
- Dashboard temps réel depuis tablette (Flutter responsive)
- Rapports WhatsApp automatiques quotidiens et hebdomadaires
- Clôture journalière avec détail des ventes par employé

**Ces fonctionnalités représentent le cœur du bloc 1 — soit ~80% du besoin le plus urgent.**

---

### Ce qui nécessite un développement supplémentaire

Les besoins restants (blocs 2 à 5) se divisent en deux catégories :

#### Option 1 — Fonctionnalités à intégrer dans la roadmap standard Keevo

| Module | Justification |
|--------|--------------|
| **Module A (Charges)** | Chaque commerçant Keevo paie un loyer et des salaires. C'est un besoin universel. À inclure dans l'Epic 10 de la roadmap publique. Ce client en bénéficierait comme tout autre utilisateur. |
| **Module B (Trésorerie/Caisse)** | Découle directement du Module A. Le concept de "caisse disponible" est standard pour tout commerce multi-boutiques. |
| **Module D (Conteneurs)** | **Déjà inscrit dans le PRD Keevo Growth.** Ce client peut être le pilote de cette fonctionnalité. Elle profite à tous les importateurs sur la plateforme. |

**Avantage :** coûts mutualisés sur l'ensemble de la base client Keevo. Ce client peut accéder aux fonctionnalités sans surcoût de maintenance à long terme.

#### Option 2 — Patch personnalisé pour ce client

| Module | Justification |
|--------|--------------|
| **Module C (Banque)** | La gestion de comptes bancaires multiples avec dettes/échéances dépasse le scope d'un POS/inventaire. C'est une fonctionnalité de comptabilité avancée — à construire en module séparé pour ce client spécifiquement. |
| **Module E (Achats Chine)** | Très spécifique au profil d'importateur avec voyages sur marchés en Chine. Peu de marchands Keevo ont ce workflow. |
| **Module F (Rôle CO_OWNER)** | Spécifique à ce client. Le pattern de masquage valeurs peut être réutilisé, mais le rôle "épouse" n'a pas de sens dans le produit générique. |

**Avantage :** développement ciblé, sans impact sur la base utilisateur Keevo. Déployable comme extension de tenant ou section séparée dans l'app.

---

### Recommandation et coût

**Phase 1 — Immédiat (déjà livré avec Keevo)**
Démarrer avec les fonctionnalités existantes : le client peut gérer son stock, ses ventes, ses transferts et ses rapports dès aujourd'hui.
→ **0 développement supplémentaire pour cette partie.**

**Phase 2 — Modules roadmap standard (Modules A, B, D)**
Proposer à ce client de co-financer l'accélération de ces fonctionnalités dans la roadmap Keevo.
→ **~19–25 jours développeur** (~2–3 mois à temps partiel)

**Phase 3 — Patch personnalisé (Modules C, E, F)**
Développement d'un module "Importateur Premium" spécifique à ce profil de client.
→ **~28–35 jours développeur supplémentaires** (~3–4 mois à temps partiel)

**Total développement supplémentaire (tout compris) :** **47–60 jours développeur**

> **Astuce commerciale :** Les Modules A, B et D (roadmap standard) peuvent être proposés à ce client à tarif réduit ou inclus dans son abonnement Premium, en échange d'un retour terrain. Les Modules C, E et F (patch importateur) peuvent faire l'objet d'une facturation séparée en développement sur mesure. Cela vous permet de ne pas développer deux fois le même code, et d'avoir un client early-adopter qui valide votre roadmap.

---

*Document généré par John (PM) — analyse basée sur les implementation artifacts, sprint-status.yaml, PRD et architecture de Keevo au 15 mai 2026.*
