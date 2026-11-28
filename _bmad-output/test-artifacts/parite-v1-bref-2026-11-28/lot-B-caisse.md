# Lot B : caisse, ventes, accueil, contacts. Keevo V1 2.0.1 (prod) comparé à l'app RN Bref Point (commit 00daac2)

J'ai tout lu dans le code actuel. Je n'ai rien modifié, rien compilé et lancé aucun appareil.
- Racine V1 : `scratch:v1/keevo/app/lib/`.
- Racine RN : `scratch:rn/`.
- Backend : `/home/toor/Project/FreeLance/AI/Keevo/keevo/backend/src/main/java`.
- Colonne « Testé appareil » : source = `RAPPORT.md`, `constats.md` et `constats-multiboutiques.md` du 23/11. Ces tests ont tous été faits sur une base vierge, avec un compte créé par l'app RN.

## Constat transversal à lire avant les tableaux

**Dans l'app RN, l'historique des ventes, le détail d'une vente, le ticket, l'annulation et l'accueil ne lisent que des factures.** Il n'existe aucun appel à `/sales/history` ni à `/sales/{id}` côté RN : la liste « Ventes » est la table locale `invoices` (`features/invoice/hooks/use-infinite-invoices.ts:19-35`), remplie par `GET /invoices` (`lib/database/sync.ts:46-52`).

Une vente de caisse ne devient une facture que si le serveur la facture automatiquement. Il ne le fait que si les modules **Facturation ET Caisse sont actifs** (`invoicing/invoice/adapter/in/event/SaleInvoicingListener.java:47-56`).

Or la migration `V50__module_owner_activation.sql:13-17,71-75` **désactive la facturation pour toutes les entreprises existantes**, donc pour toutes les entreprises V1. La règle par défaut du parcours « Caisse » la désactive aussi (`:50-59`).
- Aucune migration plus récente ne la réactive : j'ai cherché « invoicing » dans V51 à V94.
- L'app RN n'appelle jamais `PUT /tenants/me/modules/{id}/activation`. Le propriétaire ne peut donc pas la réactiver depuis l'app (à vérifier côté dashboard web).
- Même réactivée, la facturation ne créerait pas de facture pour les ventes passées : il n'y a ni rattrapage ni import.
- De plus, `GET /invoices` et `GET /invoices/stats` exigent ce même module (`InvoiceController.java:157-160,227-229`).

Pour un commerçant V1 qui passe sur l'app RN :
- onglet Ventes vide, aucune vente passée ni nouvelle vente de caisse visible ;
- accueil à 0 ;
- détail, ticket et annulation impossibles ;
- probablement un bandeau d'échec de synchronisation permanent (`sync.ts:159`), à vérifier.

Ce scénario n'a jamais été testé sur appareil : le compte de test avait la facturation active.

---

## 1. `pos_page` (caisse, grille et recherche) avec `cart_pill`, `cart_bottom_sheet` et `discount_sheet`

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Ouvrir la caisse (onglet « Caisse », accueil de l'employé) | `core/router/app_router.dart:413-416` | Bouton central « + » → `/pos` (ou `/invoice/create` si le parcours est « Documents ») `app/(tabs)/_layout.tsx:98-102` | CONFORME | oui (#15) | — |
| Recherche produit par nom | `pos_page.dart:197-233` | `pos-screen.tsx:78-81,363-371` (nom + référence/code-barres) | CONFORME (mieux) | oui (#15) | — |
| Filtre par catégorie (puces « Tous » + catégories) | `pos_page.dart:238-244`, puces l.~690-705 | Catégorie affichée sur la carte mais pas de filtre `pos-screen.tsx:426-428` | ABSENT | non | Caisse : aucune puce de catégorie |
| Grille triée par produits les plus vendus, pagination 24 | `pos_page.dart:338-358,70-83` | Tri alphabétique, liste entière `pos-catalog.api.ts:65` | DIFFÉRENT (acceptable) | non | — |
| Carte produit : photo, prix, badge stock, « Rupture » non cliquable, « Brouillon » | `widget/product_card.dart:65-82,218-246` ; `pos_page.dart:400-420` | `pos-screen.tsx:398-442,459-487` (rupture, stock bas, « disponible » pour un service) | CONFORME (pas de badge Brouillon) | oui (#16 puis rejeu « stock dans la boutique » levé) | — |
| Ajout au panier avec refus au-delà du stock (message) | `pos_page.dart:289-312`, `cart_item.dart:35-39` | `pos-screen.tsx:89-99` (stock de la boutique, `use-pos-catalog.ts`) | CONFORME | oui (M8/M9 puis rejeu) | — |
| Choix de la boutique (boutique active / « toutes ») | `pos_page.dart:101-102` (boutique des paramètres) | Puces de boutique pour le propriétaire de plusieurs boutiques ; vendeur = sa boutique `pos-screen.tsx:60-69,346-361` | CONFORME (mieux) | oui (M8, B3 levé) | Changer de boutique vide le panier |
| Bouton « Mes Ventes » dans l'en-tête | `pos_page.dart:175-179` | Onglet « Ventes » `(tabs)/_layout.tsx:84-91` | DIFFÉRENT (acceptable) | oui | Vide pour une entreprise V1 (constat transversal) |
| Indicateur de synchro dans l'en-tête | `pos_page.dart:180` | Pas dans la caisse ; ventes en attente d'envoi en tête de l'onglet Ventes `invoices-screen.tsx:515` | PARTIEL | oui (#41) | — |
| Bouton « + » : créer un produit brouillon à la volée et l'ajouter | `pos_page.dart:128-133,315-335`, l.511 « Créer '…' à la volée » | Aucun ; lien « article libre → facture » `pos-screen.tsx:384-386` | ABSENT | non | Le lien mène à la facture, bloquée pour une entreprise V1 |
| Saisie du stock initial d'un brouillon au moment d'encaisser | `cart_bottom_sheet.dart:66-97,655-700` | — | ABSENT | non | Conséquence de la ligne précédente |
| Panier : +/−, quantité tapée au clavier, suppression par glissement | `cart_bottom_sheet.dart:199-226,556-615` | +/− seulement ; à 0 la ligne disparaît `pos-screen.tsx:100-115,511-521` | PARTIEL | oui (#15) | Pas de saisie directe de la quantité (gênant pour 50 unités) |
| Modifier le prix d'une ligne (négociation, badge « modifié ») | `cart_bottom_sheet.dart:354-415,476-535` | Prix catalogue imposé (`appliedUnitPrice = unitPrice`) `pos-screen.tsx:147-148` | ABSENT | non | — |
| Bouton « Vider » le panier | `cart_bottom_sheet.dart:176-182` | Aucun ; quitter la caisse (×) perd le panier (état local) | PARTIEL | non | — |
| Remise en % ou montant fixe, contrôle ≤ 100 % et ≤ sous-total, « Supprimer la réduction » | `discount_sheet.dart:54-57,108-178` | Champ « Remise » en montant fixe seulement, ramené au sous-total sans message `pos-screen.tsx:86,268-275` | PARTIEL | oui (#15, M30) | Pas de % (le % existe dans la facture) |
| Sous-total / réduction / total à payer | `cart_bottom_sheet.dart:234-263` | `pos-screen.tsx:313-318` | CONFORME | oui | — |
| Barre panier (nombre d'articles + total) → Encaisser | `widget/cart_pill.dart` ; `pos_page.dart:274-283` | `pos-screen.tsx:446-451` | CONFORME | oui | — |
| État vide (« Votre boutique est prête », « Ajouter un produit » réservé au propriétaire) | `pos_page.dart:~590-640` | « Catalogue vide » + « Synchroniser » `pos-screen.tsx:373-387` | CONFORME | non | Caisse sur une entreprise sans produit |
| Hors ligne complet (base locale, démarrage sans réseau) | `sale_repository_impl.dart:19,42-60` | Vente mise en file `use-create-sale.ts`, catalogue de repli local `use-pos-catalog.ts` | PARTIEL | oui (#41, #44 ; B4 démarrage levé au rejeu) | — |

## 2. `checkout_page` (encaissement)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Client facultatif : recherche dans tous les clients de l'entreprise, puis retrait | `checkout_page.dart:183-188,444-560` | « Choisir » / « client de passage », recherche nom ou téléphone, 6 résultats, **clients de la boutique seulement** `pos-screen.tsx:117-126,277-311` | CONFORME | oui (#15, #39) | Voir l'écart sur les anciens clients en multi-boutiques |
| Mode de paiement Espèces / Mobile Money | `checkout_page.dart:191-218` | `pos-screen.tsx:253-257` (Espèces par défaut) | CONFORME | oui (#15, #40) | — |
| Référence Mobile Money (n'est pas envoyée si on repasse en espèces) | `checkout_page.dart:270-276,376-378` | `pos-screen.tsx:258-266` ; `sale-writes.ts:49` | CONFORME | oui (#40) | — |
| Montant reçu + **monnaie à rendre** (espèces) | `checkout_page.dart:222-265` | — | ABSENT | non | — |
| Avis « panier avec brouillons » (propriétaire / employé) | `checkout_page.dart:58-63,282-283` | — (pas de brouillon en caisse) | ABSENT | non | — |
| Validation : bouton grisé si panier vide ou pas de mode, une seule vente par double appui | `checkout_page.dart:74-78,355-357` | `pos-screen.tsx:128-134,320` (même identifiant de vente) | CONFORME | oui | — |
| Erreurs : stock insuffisant, compte en lecture seule, synchro exigée (fenêtre) | `checkout_page.dart:79-98` | Message du serveur `pos-screen.tsx:159` ; hors ligne la vente est « enregistrée » tout de suite | DIFFÉRENT | non | Un refus après coup (hors ligne) n'apparaît qu'au journal |
| Retour à la caisse en rouvrant le panier | `checkout_page.dart:46-52` | « ← » revient à l'étape articles `pos-screen.tsx:234` | CONFORME | oui | — |
| Endpoint | `POST /api/v1/sales` `remote_sale_datasource.dart:20` | `POST /api/v1/sync/push` (`CREATE_SALE`) `sale-writes.ts:38-63` → `SyncController.java:134`, `SaleSyncHandler.java:48` | CONFORME | oui | — |

## 3. `sale_success_page`

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| « Vente enregistrée ! » + montant, retour automatique à la caisse après 1,8 s | `sale_success_page.dart:54-56,98-130` | Écran « Vente enregistrée » + total + client + mode, boutons « Nouvelle vente » / « Fermer » `pos-screen.tsx:204-227` | CONFORME (mieux) | oui (#15) | — |
| Variante « Vente en attente » (orange) | `sale_success_page.dart:65-73` | — | ABSENT | non | Mineur : la V1 2.0.1 ne crée plus de vente en attente (`record_sale_usecase.dart` statut par défaut COMPLETED) |
| Ticket depuis l'écran de succès | (n'existait pas en V1) | (pas sur l'écran de succès ; depuis la fiche Ventes) | — | — | — |

## 4. `sales_history_page` (« Mes ventes » / « Ventes de la boutique »)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Liste des ventes avec heure, nombre d'articles, client, total, remise, badge En attente / Annulée | `sales_history_page.dart:380-397,440-560` | Onglet Ventes, cartes de factures `invoices-screen.tsx` + `invoice-card.tsx` | **CASSÉ pour une entreprise V1** (facturation désactivée : la liste ne contient que des factures) ; CONFORME pour un nouveau compte | oui (#18, nouveau compte seulement) | Se connecter avec une entreprise V1 migrée → Ventes : vérifier si les ventes de caisse apparaissent |
| Historique passé (ventes V1 déjà faites) | `GET /api/v1/sales/history` `remote_sale_datasource.dart:137-138` | Aucune lecture de `/sales/history` ; seulement les 500 dernières factures `sync.ts:38,46-52` | ABSENT | non | Aucune vente d'avant le passage n'est visible |
| Filtres de période : Aujourd'hui / Cette semaine / Ce mois / Personnalisé (365 j) | `sales_history_page.dart:185-300` | Tout / Aujourd'hui / Ce mois / Cette année / Personnalisé `invoices-screen.tsx:89-95,188-199` | PARTIEL | non | Pas de « Cette semaine » ; Ventes › filtre › chaque période |
| Employé : ses ventes ; propriétaire : toutes ; filtre par employé (mode admin) | `sales_history_page.dart:104-180` | Filtre vendeur pour propriétaire et gérant `invoices-screen.tsx:50,209-226,618` | CONFORME (à vérifier : le vendeur voit-il seulement les siennes ?) | non | Connecté en vendeur → Ventes : ne doit montrer que ses ventes |
| Filtre par statut | badge seulement | Puces payées / partielles / en attente / en retard / annulées `invoices-screen.tsx:80-87` | CONFORME (mieux) | non | — |
| Recherche | (absente en V1) | Nom, numéro, téléphone + recherche serveur `invoices-screen.tsx:265-290` | Mieux | oui (#18) | Reste connu : « la recherche d'une vente par son numéro ne trouve rien » |
| Tri | (absent) | Récent / ancien / montant `invoices-screen.tsx:72-77` | Mieux | non | — |
| « Exporter » (propriétaire) | `sales_history_page.dart:68-74` | Pas de bouton dans Ventes ; module Exports `/exports` (`use-module-items.ts:231`) | PARTIEL | non | Hors lot : module Exports |
| Pas de boutique active → message | `sales_history_page.dart:82-92` | Filtre « Toutes les boutiques » | CONFORME | — | — |
| Tirer pour actualiser | `sales_history_page.dart:381-385` | `invoices-screen.tsx:313,507` | CONFORME | oui (#40) | — |
| Ventes hors ligne pas encore envoyées | (dans la liste locale) | Cartes « En attente d'envoi » `features/pos/components/pending-sales.tsx` | CONFORME | oui (#41) | — |

## 5. `sale_detail_page`

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Statut, mode de paiement, date et heure | `sale_detail_page.dart:110-125` | Fiche facture `app/invoice/[id]/index.tsx` → `invoice-detail-screen.tsx:115-125` | CONFORME (si la facture existe) | oui (#19, libellé « Autre » corrigé `invoice.mapper.ts:119-128`) | — |
| **Référence Mobile Money** affichée | `sale_detail_page.dart:126-131` | Pas affichée (aucun champ dans la fiche) | ABSENT | non | — |
| Client | `sale_detail_page.dart:140-148` | `invoice-detail-screen.tsx:638` | CONFORME | oui | — |
| Articles, sous-total, remise, total | `sale_detail_page.dart:150-175` | `invoice-detail-screen.tsx:162-164,716-719` | CONFORME | oui (#19) | Rejouer : remise caisse 200 → vérifier qu'elle est en ligne « Remise » et pas fondue dans le prix |
| Vendeur | — | « Vendeur : … » `invoice-detail-screen.tsx:536-538` | Mieux | non | — |
| **Annuler entièrement** (propriétaire, vente terminée, justification ≥ 10 caractères, permis même après la clôture avec avertissement) | `sale_detail_page.dart:176-210,265-275,590-625` ; `POST /sales/{id}/cancel` | « Annuler » sur la facture automatique, propriétaire seulement, motif non vide `invoice-detail-screen.tsx:203-212,338-360` → `CANCEL_SALE` par la synchro `use-cancel-sale.ts`, `CancelSaleSyncHandler.java:43,60-82` | PARTIEL | oui (B5 levé au rejeu, « annulation hors ligne, avec avoir ») | Serveur `sameDayOnly = true` : **une vente d'un jour précédent ne s'annule plus** (la V1 le permettait). Pas de règle ≥ 10 caractères (le serveur accepte). Impossible sans facture (entreprise V1) |
| **Corriger un article** (quantités + justification) | `sale_detail_page.dart:212-225,278-290,641-735` ; `POST /sales/{id}/correct` | Rien dans l'app (le backend a `PendingSaleController.java:97` et `SaleCreditNoteUseCase`) | ABSENT | non | — |
| Ticket / PDF / impression / partage | (absent en V1) | 8 modèles, impression, partage `invoice-detail-screen.tsx:39,102-109` | Mieux | oui (#20) | Ne pas ouvrir WhatsApp en test |

## 6. `pending_sales_page` et `pending_sale_detail_page` (ventes en attente, propriétaire)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Liste des ventes en attente de validation (accès par le bandeau des Rapports) | `pending_sales_page.dart:35-66` ; `reports_page.dart:289` ; `GET /sales/pending` | — (l'écran « ventes en attente » de RN = ventes pas encore envoyées, autre chose) | ABSENT | non | Mineur si aucune vente `PENDING_VALIDATION` ne reste en prod : à vérifier en base prod |
| Valider la vente (+ valider le produit brouillon, stock) | `pending_sale_detail_page.dart:190-200,259-345,365-407` ; `POST /sales/{id}/validate` | — | ABSENT | non | Endpoint présent `PendingSaleController.java:65` |
| Annuler une vente en attente | `pending_sale_detail_page.dart:173-183,237-255` | — | ABSENT | non | — |

## 7. `pos_placeholder_page`, `pos_speed_dial`, `day_summary_bottom_sheet`

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| `PosPlaceholderPage`, `PosSpeedDial` | Aucune route et aucun usage (`app_router.dart:413-416` route `/pos` → `PosPage`) | — | Sans objet (code mort en V1) | — | — |
| Clôture de journée : **résumé avant confirmation** (ventes, CA, top produit, espèces / MoMo, ventes en attente) puis « Confirmer la clôture » | `day_summary_bottom_sheet.dart:272-361` ; `reports_page.dart:539-598` | Modules › Clôture de journée `features/governance/screens/day-closure-screen.tsx:73,133-134` ; résumé affiché **après** la clôture (l.97-121) | PARTIEL | oui (M34) | Pas d'aperçu des chiffres avant de clôturer |
| Clôture par l'**employé** | `reports_page.dart:385-392` (bouton pour tous les rôles) | Écran seulement dans l'onglet Modules, masqué pour le vendeur `(tabs)/_layout.tsx:42,124-131` ; `use-module-items.ts:110-118` ; état de clôture lu pour le propriétaire seulement (l.51) | CASSÉ pour le vendeur (inaccessible) | non | Connecté en vendeur : chercher un accès à « Clôture » (aucun attendu) |
| Clôture hors ligne | `day_closure_repository_impl.dart` (locale puis envoi) | `POST /day-closures` (`DayClosureController.java:57`) ; mise en file constatée | CONFORME | oui (M34) | — |
| Jour d'avant (propriétaire) | — | Puces Aujourd'hui / Hier `day-closure-screen.tsx:90` | Mieux | non | — |

## 8. `dashboard_page` (tableau de bord du propriétaire)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Accès réservé au propriétaire (accueil du propriétaire) | `app_router.dart:166-180,409-412` | Accueil pour tous les rôles ; le vendeur voit sa boutique `features/dashboard/screens/dashboard-screen.tsx:85-86,122-124` | DIFFÉRENT (acceptable) | oui (#8, #35) | — |
| Salutation selon l'heure + nom de l'entreprise | `dashboard_page.dart:122-140,262-267` | « Bonjour » + nom `dashboard-screen.tsx:200-210` | CONFORME | oui | — |
| Cloche de notifications, bouton Actualiser | `dashboard_page.dart:144-150` | `dashboard-screen.tsx:249` ; tirer pour actualiser l.166 | CONFORME | oui (M18) | — |
| « Chiffre d'affaires total », période Semaine / Mois / Année / Depuis le début | `dashboard_page.dart:158-159,269-292` ; `dashboard_snapshot.dart:117-121` | Carte CA, période Tout / Aujourd'hui / Mois / Année / Personnalisé `dashboard-screen.tsx:88-118,399-417` (factures, `/invoices/stats`) | **CASSÉ pour une entreprise V1** (CA à 0 : rien n'est facturé) ; PARTIEL sinon (pas de « Semaine ») | oui (#8, M10, nouveau compte) | Accueil d'une entreprise V1 migrée : CA attendu ≠ 0 |
| Carte « CA d'aujourd'hui » + tendance vs hier + mini-graphe 7 jours | `morning_summary_hero_card.dart:73-142` | Période « Aujourd'hui » sans tendance ni graphe | PARTIEL | non | — |
| Carte de motivation (fermable) | `dashboard_page.dart:172-179` | — | ABSENT | — | Mineur |
| Indicateurs : CA d'hier, Stock bas (→ stock), Transactions du mois (→ ventes), Panier moyen, avec tendances | `dashboard_page.dart:316-330` ; `metric_badge_card.dart:27-80` | Encaissé, En attente, En retard, Nombre de ventes, Clients `dashboard-screen.tsx:429-499` ; panier moyen dans Rapports (`report-detail-screen.tsx`) | PARTIEL | oui (#8) | Pas de CA d'hier, pas de stock bas sur l'accueil (Modules › Stock / Alertes), pas de tendance |
| « Vos boutiques » : carte par boutique (CA du jour, statut STABLE / ATTENTION / EN BAISSE, employés) → tableau de la boutique ; « VOIR TOUT » → boutiques | `dashboard_page.dart:186-228` ; `shop_status_card.dart:144-146` | Sélecteur de boutique qui filtre l'accueil `dashboard-screen.tsx:279-311` ; Boutiques depuis Modules | DIFFÉRENT | oui (M10 filtre) | Pas de vue comparée des boutiques ni de statut |
| Graphe « Évolution des ventes » Jour / Semaine / Mois / Année + 7 / 14 / 30 j | `dashboard_page.dart:340-375` ; `sales_evolution_chart.dart:134,155` | Aucun graphe sur l'accueil | ABSENT | non | Hors lot : vérifier les Rapports avancés |
| Top produits 7 j (→ Rapports) et moins vendus 7 j | `dashboard_page.dart:236-247` ; `top_products_section.dart` | Top produits dans Rapports (`/invoices/top-products`, `InvoiceController.java:266`) | PARTIEL | non | Moins vendus absent ; Modules › Rapports › Top produits |
| Hors ligne (calcul sur l'appareil, « onDeviceOnly ») | `dashboard_page.dart:285-291` | Repli sur les factures locales `use-invoice-stats.ts` | CONFORME | non | — |
| « Factures récentes » / « Résultat du mois » / Marketing | — | `dashboard-screen.tsx:509-619` | Nouveau | oui (#8) | — |

## 9. `store_dashboard_page` (tableau d'une boutique)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Nom + statut, CA aujourd'hui / hier, employés | `store_dashboard_page.dart:101-150` | Fiche boutique : statut actif, CA par période, clients, produits, dépenses `features/shop/screens/shop-detail-screen.tsx:69-76,154-239` | PARTIEL | non | Modules › Boutiques › une boutique. Pas d'aujourd'hui vs hier ni d'employés |
| Graphe d'évolution par boutique | `store_dashboard_page.dart:152-170` | — | ABSENT | non | — |
| Top / moins vendus de la boutique | `store_dashboard_page.dart:186-200` | — (Rapports par boutique : hors lot) | ABSENT ici | non | — |
| Produits en stock bas de la boutique | `store_dashboard_page.dart:95,202` | — (Modules › Stock) | ABSENT ici | non | — |
| « Rapports de la boutique » / « Ventes de la boutique » (filtre employé) | `store_dashboard_page.dart:218-232` | Ventes avec filtres boutique + vendeur `invoices-screen.tsx:106-143` | PARTIEL | non | Ventes › filtre › boutique + vendeur |

## 10. `client_list_page` et `client_form_page`

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Accès propriétaire (Plus › Clients) | `settings_page.dart:228` ; `app_router.dart:167` | Modules / Accueil › Clients, propriétaire et gérant `use-module-items.ts:41-52` ; `dashboard-screen.tsx:475` | CONFORME | oui (#21) | — |
| Recherche (nom, téléphone) | `client_list_page.dart:165-209` | `customer-list-screen.tsx:81-89,103-115` | CONFORME | oui | — |
| Tri | — | Récent, ancien, plus / moins dépensé `customer-list-screen.tsx:67-73` | Mieux | non | — |
| Liste par boutique | (clients de l'entreprise) | Sélecteur de boutique `customer-list-screen.tsx:77,126-145` ; base locale filtrée par `shop_id` `use-infinite-customers.ts` | DIFFÉRENT | non | Voir l'écart sur les anciens clients sans boutique |
| « Voir / masquer les archivés », archiver (appui long + confirmation), désarchiver | `client_list_page.dart:107-120,336-339,468-508` ; `DELETE /clients/{id}` | Aucun : ni archivage ni vue des archivés (le backend a `ClientController.java:190`) | ABSENT | non | — |
| Bouton « Synchroniser » + message | `client_list_page.dart:122-127,40-70` | Synchro de l'app (pas de bouton ici) | DIFFÉRENT | — | Mineur |
| Créer un client (nom*, téléphone*, e-mail, notes), contrôles téléphone et e-mail, hors ligne | `client_form_page.dart:58-62,189-230` | Particulier / Entreprise, nom, téléphone (tous pays), e-mail, date de naissance, adresse, NUI / RCCM, notes ; création hors ligne `customer-edit-screen.tsx:103-140,195-310` | CONFORME (mieux) | oui (#21 ; M24 corrigé au rejeu) | — |
| Modifier un client, téléphone compris, hors ligne | `client_form_page.dart` (même formulaire) ; `PATCH /clients/{id}` | Modification **en ligne seulement**, **téléphone non modifiable** `customer-edit-screen.tsx:115-117,212-231` ; `customer-detail-screen.tsx:84-90` | PARTIEL | oui (#21, en ligne) | Fiche client › Modifier, en mode avion → message « hors ligne » attendu |
| Fiche client (total dépensé, achats, consentement WhatsApp) | — | `customer-detail-screen.tsx:150-223` | Nouveau | oui (#21) | — |

## 11. `supplier_list_page` et `supplier_form_page`

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Accès (Plus › Fournisseurs, propriétaire) | `settings_page.dart:236` | Modules › Fournisseurs (propriétaire et gérant) `use-module-items.ts:91-98` | CONFORME | oui (#49 balayage) | — |
| Liste : nom, téléphone, nombre de produits ; recherche | `supplier_list_page.dart:176-209,380-445` | `suppliers-screen.tsx:23-28,43,81` (+ bouton d'appel l.87) | CONFORME | non | Modules › Fournisseurs › rechercher |
| Archivés : voir, archiver (appui long), désarchiver | `supplier_list_page.dart:107-120,336-339` | Archiver depuis la fiche `supplier-detail-screen.tsx:33-40,111` ; pas de vue des archivés ni de désarchivage | PARTIEL | non | Fiche fournisseur › Archiver → disparaît ; aucun moyen de le restaurer |
| Créer / modifier : nom, téléphone*, e-mail, contrôles | `supplier_form_page.dart:198-225` | `supplier-form-screen.tsx:48-70,98-123` (création mise en file hors ligne) | CONFORME | oui (M25) | Modification hors ligne : non testée |
| Produits associés (multi-sélection, produits déjà pris par un autre fournisseur exclus) | `supplier_form_page.dart:230-240,469-475` | `features/supplier/components/product-picker.tsx` (aucune exclusion) | PARTIEL | non | À vérifier : comportement du serveur si un produit est donné à 2 fournisseurs |
| Fiche : appel, e-mail, produits fournis | — | `supplier-detail-screen.tsx:76-98` | Nouveau | non | Ne pas toucher WhatsApp en test |
| Endpoints | `/api/v1/suppliers` GET/POST/PATCH/DELETE | `supplier.api.ts` → `SupplierController` l.49-110 | CONFORME | — | — |

**Écrans « bientôt disponibles »** : `features/dashboard/config/coming-soon-modules.ts` (rapports avancés, rentabilité, exports, alertes, MCP) n'est relié à aucune navigation. Aucun `router.push('/coming-soon/…')` ; seul « Achats » est marqué `comingSoon` (`use-module-items.ts:106`). Aucune fonction de ce lot n'y tombe.

---

## Écarts à retenir

### Bloquants pour un utilisateur V1 qui passe sur la nouvelle app
1. **Pour une entreprise V1, la facturation est désactivée, donc l'onglet Ventes, l'accueil, la fiche de vente, le ticket et l'annulation sont vides ou inutilisables.** La V50 désactive la facturation pour toutes les entreprises existantes, et l'app RN ne lit les ventes que sous forme de factures automatiques. L'app n'offre aucun moyen de la réactiver. Jamais testé : tous les tests ont été faits sur un compte neuf.
2. **L'historique des ventes V1 disparaît.** Il n'y a ni lecture de `/sales/history` ni facturation rétroactive. Même avec la facturation activée, seules les ventes faites après la bascule (et au plus les 500 dernières factures synchronisées) sont visibles.
3. **Annulation limitée au jour même.** La V1 annulait toute vente terminée, même après la clôture. RN passe par `CANCEL_SALE` avec la règle « même jour » : une erreur de caisse découverte le lendemain ne se corrige plus.
4. **Le vendeur ne peut plus clôturer sa journée.** L'écran Clôture n'est accessible que depuis l'onglet Modules, masqué pour le vendeur ; la V1 le permettait à l'employé.

### Gênants
5. **Correction d'une vente** (« Corriger un article ») absente ; seule l'annulation entière existe.
6. **Monnaie à rendre** (montant reçu) absente à l'encaissement en espèces.
7. **Remise en %** absente en caisse (montant fixe seulement) ; **modification du prix d'une ligne** (négociation) absente.
8. **Quantité tapée au clavier** absente : seulement +/−. **Filtre par catégorie** absent dans la caisse.
9. **Création d'un produit à la volée en caisse** remplacée par « article libre → facture ». Ce passage est lui-même bloqué pour une entreprise V1 (facturation désactivée).
10. **Clients** : ni archivage ni vue des archivés. Modification uniquement en ligne. Téléphone non modifiable.
11. **Anciens clients V1 sans boutique, en multi-boutiques** : la synchro les rattache à la première boutique lue (`sync.ts:330-385`, le `shopId` n'est pas mis à jour). Ils sont donc introuvables dans les listes et dans la caisse des autres boutiques. À vérifier sur appareil.
12. **Accueil** : pas de comparaison aujourd'hui / hier, pas de graphe d'évolution, pas de vue par boutique avec statut, pas de stock bas, pas de « moins vendus ». La fiche boutique n'a ni graphe, ni top produits, ni stock bas.
13. **Clôture** : plus d'aperçu des chiffres avant de confirmer.

### Mineurs
14. Référence Mobile Money non affichée dans la fiche de la vente.
15. Périodes : « Cette semaine » absente dans Ventes et dans le CA de l'accueil.
16. Ventes en attente de validation (liste, valider, annuler) absentes. Sans effet si la prod n'en contient plus : à vérifier en base.
17. Fournisseurs : pas de vue des archivés ni de désarchivage ; le choix des produits n'exclut pas ceux d'un autre fournisseur.
18. Pas de bouton « Vider » le panier ; quitter la caisse perd le panier.
19. Tri de la grille alphabétique au lieu de « plus vendus d'abord » ; carte de motivation absente.
20. Écran « bientôt disponibles » : configuration non reliée (code mort), sans impact sur ce lot.

### Tests prioritaires sur l'émulateur, pas encore faits
1. **Entreprise V1 migrée** (copie d'un dump prod, jamais l'entreprise pilote) : ouvrir l'accueil et l'onglet Ventes, faire une vente en caisse, ouvrir Ventes, puis la fiche et « Annuler ».
2. **Vendeur** : chercher un accès à la clôture de journée.
3. **Annuler une vente de la veille** : un refus est attendu.
4. **Deux boutiques avec d'anciens clients sans boutique** : Clients › changer de boutique, puis Caisse › Choisir un client.
