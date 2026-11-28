# Lot C : catalogue, stock, inventaire, audit. Keevo V1 2.0.1 comparé à l'app RN Bref Point (00daac2)

J'ai tout lu dans le code actuel, sans rien lancer.

**Conventions**
- Chemins V1 relatifs à `scratchpad/v1/keevo/app/lib/`, chemins RN relatifs à `scratchpad/rn/`. Le backend est dans `keevo/backend/src/main/java`, préfixe `/api/v1`.
- « Testé appareil » renvoie à `RAPPORT.md`, `constats.md` (n°) et `constats-multiboutiques.md` (M…), du 23/11 sur une version plus ancienne de l'app.
- Correspondance des rôles (`features/authentication/api/auth.mapper.ts:41-47`) : V1 OWNER = RN `owner`, V1 EMPLOYEE = RN `seller`, MANAGER = RN `admin`.
- Aucune fonction du lot ne tombe dans `coming-soon-modules.ts`. Cette liste ne sert qu'à la route `/coming-soon/[key]`, vers laquelle rien ne pointe. La tuile « Alertes » ouvre `/alerts`, un vrai écran, verrouillé seulement si l'option `alerts` manque (`use-module-items.ts:161-170`).

**Règle d'accès RN qui touche tout le lot**
- L'onglet Produits n'existe que pour owner/admin (`app/(tabs)/_layout.tsx:39,127`).
- Les tuiles Stock, Transferts et Inventaires sont réservées à owner/admin (`features/dashboard/hooks/use-module-items.ts:63-90`).
- L'onglet Modules est masqué au vendeur (`_layout.tsx:40,137`).
- Aucune notification ne mène vers ces écrans.
- En V1, l'EMPLOYEE avait le Catalogue (onglet, `core/scaffold/main_shell.dart:51`) et, dans Plus › Consultation, Stock multi-boutiques, Inventaire et Transferts (`features/settings/presentation/page/settings_page.dart:258-288`).

---

## 1. catalog_page (+ product_card)

| Fonction V1 | Preuve V1 (fichier:ligne) | Équivalent RN (fichier:ligne) | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Catalogue accessible au propriétaire | catalog/presentation/page/catalog_page.dart:103 ; main_shell.dart:45 | app/(tabs)/products.tsx:4 ; features/product/screens/product-list-screen.tsx:27 | CONFORME | oui (n°49) | — |
| Catalogue accessible à l'employé (lecture, création, modification) | main_shell.dart:51 ; catalog_page.dart:391-406 | _layout.tsx:39,127 (onglet masqué au vendeur) | ABSENT | non | Le vendeur n'a aucun accès aux produits. Test : se connecter en vendeur et vérifier l'absence de l'onglet Produits. |
| Recherche nom / SKU, effacer, attente de 300 ms | catalog_page.dart:74-87,271-305 | product-list-screen.tsx:45,60-70,121-127 | CONFORME | non | La recherche RN porte aussi sur le code-barres. Test : Produits › taper un nom, puis un code-barres. |
| Onglets Rupture / Stock bas | catalog_page.dart:21,532-551 ; product/presentation/provider/product_provider.dart:189-218 | product-list-screen.tsx:17,49-58,79-84 (puces Tous / Bas / Rupture / Non suivi) | CONFORME | oui (n°14, ancienne version) | Ajout « Non suivi ». Test : Produits › puce Rupture. |
| Onglet Archivés et désarchivage (avec confirmation) | catalog_page.dart:522-530 ; product_card.dart:406-427,749-835 | catalog.api.ts:218 (archivés filtrés) ; product.api.ts:96 (UNARCHIVE_PRODUCT écrit mais aucun écran ne l'appelle) | ABSENT | non | Un produit archivé par erreur ne peut plus être restauré depuis l'app. |
| Brouillons : bandeau, filtre « Brouillons uniquement », badge | catalog_page.dart:308-343 ; draft_validation_banner.dart | aucun (catalog.types.ts:15 lit `DRAFT` sans l'afficher) | ABSENT | non | `GET /products` renvoie aussi les brouillons (ProductSpringRepository.java:30) : ils s'affichent comme des produits normaux. |
| Valider un brouillon avec stock à ajouter (propriétaire), puis validation des ventes en attente | product_card.dart:367-375,515-648 | aucun | ABSENT | non | À vérifier : selon le commentaire V1 (product_repository_impl.dart:144), le serveur passe un brouillon en actif à toute modification. |
| Modifier (refusé à l'employé sur un brouillon) | product_card.dart:383-389,439-455 | product-detail-screen.tsx:153 (owner/admin) | DIFFÉRENT | oui (M11/M12) | Il faut maintenant passer par la fiche produit ; le vendeur n'a plus de modification. |
| Disponibilité dans toutes les boutiques (appui long) | product_card.dart:394-403 | product-detail-screen.tsx:258-271 (« Par boutique ») | DIFFÉRENT | non | Acceptable pour le propriétaire. Test : Produits › produit › section Stock. |
| Archiver avec confirmation | product_card.dart:406-418,657-742 | product-detail-screen.tsx:101-105,157 ; PATCH /products/{id}/archive (ProductController.java:301) | CONFORME | oui (M13) | — |
| Bouton Synchroniser + tirer pour actualiser | catalog_page.dart:182-205,645-648 | product-list-screen.tsx:193 | CONFORME | non | Plus de bouton dédié, seulement tirer pour actualiser. |
| Raccourci vers la vue multi-boutiques | catalog_page.dart:175-179 | use-module-items.ts:66-71 (tuile Stock) | DIFFÉRENT | non | Acceptable. |
| Menu ⋮ : Importer CSV, modèle CSV, Gérer les catégories (pas l'employé) | catalog_page.dart:209-251 | product-list-screen.tsx:99-115 (Importer seul ; le modèle est dans l'écran d'import) | PARTIEL | non | La gestion des catégories manque (voir § 2). |
| Bouton « Ajouter un produit » (propriétaire + employé) | catalog_page.dart:391-418 | product-list-screen.tsx:212 (owner/admin) | DIFFÉRENT | oui (n°9) | Le vendeur ne peut plus créer de produit. |
| Carte : nom, SKU, prix, stock, « Jamais stocké », badges Archivé / Brouillon | product_card.dart:60-200 | product-list-screen.tsx:217-243 ; stock-badge.tsx | CONFORME | oui (n°14) | Badges Archivé / Brouillon absents (voir plus haut). |
| Tri | aucun en V1 (ordre fixe, product_provider.dart:120) | product-list-screen.tsx:47,71-75,177-187 (nom / stock / prix) | CONFORME (mieux) | non | — |
| Stock vu par boutique (boutique active) | catalog_page.dart:94-101 | product-list-screen.tsx:33-41,131-153 ; GET /products?storeId (ProductController.java:164) | CONFORME | oui (RAPPORT § « stock dans la boutique ») | — |
| Consultation hors ligne (base locale) | product_provider.dart:93-137 | cache sur disque 24 h (app/_layout.tsx:296-300) + FreshnessNotice | DIFFÉRENT | non | Au-delà de 24 h sans réseau, la liste peut être vide. Test : mode avion, puis Produits. |

## 2. categories_page (+ feuille « Gérer les catégories »)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Écran Catégories (Plus › Catégories, propriétaire) | app_router.dart:461 ; settings_page.dart:242-245 | aucun écran | ABSENT | — | — |
| Créer une catégorie | categories_page.dart:30-50,190-201 ; catalog_page.dart:721-746 | product-form-screen.tsx:106-123,252-267 ; POST /categories (CategoryController.java:106) | PARTIEL | oui (n°9) | Seulement depuis le formulaire produit. |
| Renommer (y compris catégorie système) | categories_page.dart:67-80,273-296 | aucun ; PATCH /categories/{id} existe (CategoryController.java:148) | ABSENT | — | — |
| Supprimer / désactiver (catégorie personnalisée, avec confirmation) | categories_page.dart:109-125,283 | aucun ; DELETE /categories/{id} existe (CategoryController.java:163) | ABSENT | — | — |
| Mention « Catégorie système » | categories_page.dart:254-262 | aucun | ABSENT | — | Mineur. |

## 3. csv_import_page

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Choisir un fichier .csv | csv_import_page.dart:293-295 | product-import-screen.tsx:66-93 (.csv/.txt, 2 Mo max) | CONFORME | non | Test : Produits › icône import › Choisir un fichier. |
| Télécharger le modèle | csv_import_page.dart:308-310 ; GET /products/import/template | product-import-screen.tsx:40-64 (GET /products/import/template, ProductImportController.java:89 ; modèle intégré sinon) | CONFORME | non | Passe par la feuille de partage : en test, choisir « Enregistrer », jamais WhatsApp. |
| Correspondance manuelle des colonnes (listes déroulantes, « Ignorer ») | csv_import_page.dart:55-81,391-420 | product-import.ts:73,142 (reconnaissance automatique par noms de colonnes) | DIFFÉRENT | non | Acceptable, mais un fichier aux en-têtes non reconnus ne peut pas être associé à la main. Test : fichier avec l'en-tête « Libellé ». |
| Aperçu des 5 premières lignes | csv_import_page.dart:423-450 | product-import-screen.tsx:162-213 (8 lignes + erreurs ligne par ligne + alerte d'encodage) | CONFORME (mieux) | non | — |
| Lancer l'import, puis bilan (importés / ignorés / erreurs) | csv_import_page.dart:104-118,467-476,560-632 | product-import-screen.tsx:95-104 ; POST /products/import (ProductImportController.java:119) ; bilan avec restaurés et avertissements | CONFORME | non (RAPPORT:362 « à faire ») | — |
| « Voir le catalogue » / « Importer un autre fichier » | csv_import_page.dart:622-632 | product-import-screen.tsx (done : `finish` → router.back, `again`) | CONFORME | non | — |

## 4. product_form_page (+ stock_level_widget, feuilles entrée / ajustement / seuil, fournisseur, photo)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Nom (obligatoire), description, catégorie | product_form_page.dart:513-551 | product-form-screen.tsx:223-267,301 | CONFORME | oui (n°9) | — |
| Référence SKU modifiable (majuscules, 20 caractères) | product_form_page.dart:562-572 | aucun champ ; SKU en lecture seule (product-detail-screen.tsx:318-320) | DIFFÉRENT | non | La référence est générée par le serveur. Gênant pour un commerçant qui a ses propres codes. |
| Code-barres | absent en V1 | product-form-screen.tsx:300 (saisie manuelle) | nouveau | oui (n°9) | Pas de scanner dans le formulaire. |
| Prix de vente, prix d'achat, transport (chiffres seuls) | product_form_page.dart:576-627 | product-form-screen.tsx:233-241,269-270 | CONFORME | oui (M11/M12) | — |
| Calcul de marge en direct | product_form_page.dart:631-645 ; pricing_calculator_widget.dart | product-form-screen.tsx:272-299 (+ prix conseillé) | CONFORME (mieux) | oui (n°9) | — |
| Photo : appareil, galerie, supprimer | product_form_page.dart:147-230,701-716,1036-1080 | product-form-screen.tsx:84-104 ; POST/DELETE /products/{id}/photo (ProductPhotoController.java:59,84) | CONFORME | non (RAPPORT : envoi d'images à tester avec les clés Cloudinary) | Test : Produits › produit › Modifier › photo › galerie › Enregistrer. |
| Photo enregistrée hors ligne (miniature mise en file) | product_form_page.dart:280-290 | product-form-screen.tsx:160-163 (« photo : réseau nécessaire ») | DIFFÉRENT | non | Hors ligne, la photo n'est pas gardée ; un message le dit. |
| Création hors ligne (base locale + file) | product_repository_impl.dart:83-96 | product-form-screen.tsx:169 (`mutateAsync` sans `submitOrQueue` ni clé enregistrée, use-product-catalog.ts:62-65) | PARTIEL | oui (M31, ancienne version) | À vérifier : hors ligne, le formulaire attend sur la roue, et la création n'a pas de clé de reprise, donc elle est perdue si l'app est tuée avant le retour du réseau. Test : mode avion › Nouveau produit › Enregistrer › tuer l'app › réseau. |
| Modification hors ligne | product_repository_impl.dart:107-127 | product-form-screen.tsx:147-166 (`submitOrQueue`, clé `update-catalog-product`) | CONFORME | oui (M12) | — |
| Confirmation si on quitte avec des changements non enregistrés | product_form_page.dart:117-123 | product-form-screen.tsx:198 (fermeture directe) | ABSENT | — | Mineur. |
| Message « Seul le propriétaire peut modifier un brouillon » / « Validez ce brouillon pour gérer son stock » | product_card.dart:439-449 ; product_form_page.dart:651-670 | aucun (pas de brouillons) | ABSENT | — | Voir § 1. |
| Stock par boutique (quantité, seuil, « Stock bas ») | stock_level_widget.dart:136-225 | product-detail-screen.tsx:258-271 ; GET /products/{id}/stock (StockController.java:91) | CONFORME | oui (n°11) | Seuil par boutique non affiché, seule la couleur reste. |
| Entrée de stock : quantité > 0, référence facultative, boutique | stock_entry_bottom_sheet.dart:199-269 | stock-modal.tsx:31-41,55-87 ; POST /products/{id}/stock/entry (StockController.java:114) | CONFORME | oui (n°11, M32) | Note facultative (constat n°13 corrigé). |
| Ajustement : nouvelle quantité ≥ 0, raison obligatoire | stock_adjust_bottom_sheet.dart:134-177 | stock-modal.tsx:33-40 ; POST /stock/adjust (StockController.java:143) | CONFORME | oui (M32) | — |
| Entrée / ajustement hors ligne | via la file de synchro V1 | use-product-catalog.ts:151-159 (pas de clé de reprise, pas de `submitOrQueue`) | PARTIEL | oui (M32, ancienne version) | À vérifier : roue hors ligne et perte si l'app est tuée. Test : mode avion › fiche › Entrée › Enregistrer › tuer l'app. |
| Seuil d'alerte (≥ 0) | stock_threshold_bottom_sheet.dart:112-160 | product-detail-screen.tsx:130-140,274-290 ; PATCH /products/{id}/threshold (StockController.java:171) | CONFORME | non | Test : fiche › Seuil d'alerte › 5 › Enregistrer › puce « Bas » dans la liste. |
| Suivi du stock oui / non | absent en V1 | product-detail-screen.tsx:107-114,247-251 ; PUT /stock-tracking (StockController.java:192) | nouveau | non | Test : fiche › interrupteur « Suivre le stock ». |
| Historique des mouvements (bouton → page complète) | stock_level_widget.dart:114-120 | product-detail-screen.tsx:307-316 (10 derniers, catalog.api.ts:319) | PARTIEL | non | Voir § 5. |
| Fournisseur du produit (lecture) | product_form_page.dart:694-699,1310-1380 | product-detail-screen.tsx:206-240 (+ lier / changer, appel, e-mail) ; GET /products/{id}/supplier (ProductController.java:322) | CONFORME (mieux) | non | Test : fiche › Lier un fournisseur. |
| Fusionner deux produits | absent en V1 | product-detail-screen.tsx:371-432 ; POST /products/{id}/merge (ProductController.java:233) | nouveau | non (M14 : menu vu, non exécuté) | Test : fiche › Fusionner › choisir › confirmer. |

## 5. stock_history_page (historique complet d'un produit)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Liste paginée des mouvements | stock_history_page.dart:52-100 | product-detail-screen.tsx:307-316 ; GET /products/{id}/stock/history?size=10 (StockController.java:218) | PARTIEL | non | Seulement les 10 derniers, pas de « voir plus ». |
| Filtre par type (entrées, ventes, ajustements, transferts entrants / sortants) | stock_history_page.dart:187-210 | aucun | ABSENT | — | Les transferts s'affichent « Autre » (product-detail-screen.tsx:349-353). |
| Période (7 jours par défaut, plage au choix) | stock_history_page.dart:48-49,108-130 | aucun | ABSENT | — | — |
| Filtre par boutique | stock_history_page.dart:50,57-58 | aucun (nom de la boutique affiché par ligne) | ABSENT | — | — |

## 6. global_stock_overview_page (+ store_stock_card, cross_store_availability_bottom_sheet)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Accès propriétaire et employé | settings_page.dart:150-155,258-270 | use-module-items.ts:63-72 (owner/admin) | PARTIEL | oui (n°49, ouvert) | Pas d'accès pour le vendeur. |
| Carte par boutique (nombre de produits, stock bas), dépliable, « Voir plus » | store_stock_card.dart:60-140,347-349 ; GET /stock/overview (MultiStoreStockController.java:98) | stock-screen.tsx:129-160 (une boutique à la fois via puces ; GET /stock/stores/{id}/products, MultiStoreStockController.java:110) | DIFFÉRENT | non | Acceptable, mais plus de vue d'ensemble des boutiques côte à côte. Test : Modules › Stock › puce d'une boutique. |
| Recherche d'un produit dans toutes les boutiques | global_stock_overview_page.dart:223-263,322 | stock-screen.tsx:82-88 (dans la boutique ou l'entreprise choisie) | PARTIEL | non | La comparaison entre boutiques reste possible sur la fiche produit. |
| Valeur totale du stock (propriétaire) | global_stock_overview_page.dart:447-460 | stock-screen.tsx:76-80,166 | CONFORME | oui (n°49 « valeur 36 000 ») | — |
| Filtre stock bas (ouverture depuis une notification) | global_stock_overview_page.dart:24-45 | stock-screen.tsx:52,90-95 (puce « À racheter ») ; alerts-screen.tsx:49 → /stock | CONFORME | non | Test : Modules › Alertes › À racheter. |
| Actualiser | global_stock_overview_page.dart:176 | stock-screen.tsx:195 | CONFORME | non | — |
| Bouton Transfert, historique des transferts | global_stock_overview_page.dart:101-150 | tuile Transferts séparée (use-module-items.ts:73-81) | DIFFÉRENT | — | Acceptable. |
| Disponibilité d'un produit dans toutes les boutiques | cross_store_availability_bottom_sheet.dart:162-260 ; GET /stock/products/{id}/availability | product-detail-screen.tsx:258-271 (GET /products/{id}/stock) | DIFFÉRENT | non | Acceptable pour le propriétaire et le gérant. |
| « Initier un transfert » prérempli depuis un produit | cross_store_availability_bottom_sheet.dart:371-430 | aucun (new-transfer-modal ne s'ouvre que depuis Transferts) | ABSENT | — | Gênant. |
| « Contacter via WhatsApp » le propriétaire | cross_store_availability_bottom_sheet.dart:459-511 | aucun (en RN : appel au fournisseur, stock-screen.tsx:254-264) | ABSENT | — | Mineur. |
| Entrée / ajustement depuis la liste | absent en V1 | stock-screen.tsx:99-118 | nouveau | non | Test : Stock › toucher un produit › Entrée. |

## 7. inventory_launch_page (+ inventory_config_bottom_sheet, active_session_banner, session_history_card)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Accès propriétaire et employé | settings_page.dart:158-163,272-278 | use-module-items.ts:82-90 (owner/admin) ; inventories-screen.tsx:41 prévoit le vendeur mais aucun lien n'y mène | CASSÉ pour le vendeur | non | Écran codé pour le vendeur mais inaccessible. |
| Nouvel inventaire : boutique, Complet / Partiel + catégories | inventory_config_bottom_sheet.dart:112-160 ; POST /inventory/sessions | inventories-screen.tsx:54-66,84-95 ; start-session-modal.tsx:51-59 ; InventorySessionController.java:68 | CONFORME | oui (n°47, M33) | — |
| Session en cours : Reprendre | active_session_banner.dart:85-87 | inventories-screen.tsx:100,141-160 (progression) | CONFORME | oui (n°47) | — |
| Annuler la session (tout rôle, confirmation) | inventory_launch_page.dart:191-216 ; active_session_banner.dart:75 | inventory-session-screen.tsx:65-69,154-158 (propriétaire seul) ; POST /{id}/cancel (InventorySessionController.java:115) | PARTIEL | non | Test : Inventaires › session › Annuler l'inventaire. |
| Historique et « Voir rapport » | inventory_launch_page.dart:134 ; session_history_card.dart:96-98 ; GET /inventory/sessions | inventories-screen.tsx:114-130 (par boutique, 20 dernières) ; InventorySessionController.java:139 | CONFORME | oui (n°47) | — |
| Option `counting` absente | — | inventories-screen.tsx:75-81 (écran verrouillé + plans) | CONFORME | non | — |

## 8. inventory_counting_page (+ inventory_row, quick_add_product_sheet)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Barre de progression | inventory_counting_page.dart:182 | inventory-session-screen.tsx:88-93 | CONFORME | oui (n°47) | — |
| Recherche nom / SKU (sans accents) | inventory_counting_page.dart:64-90,320-325 | inventory-session-screen.tsx:42-49,94-97 (sans pliage des accents) | CONFORME | non | Mineur : « cafe » ne trouve pas « Café ». |
| Filtres Tous / Non comptés / Écarts seulement | inventory_counting_page.dart:268-289 | inventory-session-screen.tsx:98-102 (À compter / Comptés / Tous) | PARTIEL | oui (n°47) | « Écarts seulement » manque (le rapport les montre). |
| Saisie de la quantité physique | inventory_row.dart:192-202 (champ sur la ligne) | count-modal.tsx (feuille avec −/+) ; POST /{id}/counts (InventoryCountController.java:77) | DIFFÉRENT | oui (n°47) | Un toucher de plus par produit. |
| Comptage hors ligne | local_inventory_count_datasource.dart | inventory-session-screen.tsx:165-172 (`submitOrQueue`, clé `save-count` enregistrée, mutation-defaults.ts:98) | CONFORME | oui (M33, roue à l'époque ; corrigé depuis par `submitOrQueue`) | Rejouer : mode avion › compter 3 produits. |
| Ajout rapide d'un produit inconnu (nom, catégorie, quantité, prix facultatif) | quick_add_product_sheet.dart:149-283 | quick-add-modal.tsx:12-50 ; POST /{id}/quick-add (InventoryCountController.java:148) | CONFORME | oui (n°47) | — |
| Produit déjà existant → « Compter l'existant » | quick_add_product_sheet.dart:334,365-387 | aucun (seul le message d'erreur du serveur s'affiche) | PARTIEL | non | Test : ajout rapide avec un nom existant. |
| Ajout rapide hors ligne | — | quick-add sans clé ni `submitOrQueue` (use-inventory.ts:75-78) | à vérifier | non | Roue probable hors ligne. |
| « Valider l'inventaire » quand tout est compté → rapport | inventory_counting_page.dart:134-137 | inventory-session-screen.tsx:51-63,146-152 (propriétaire, validation directe avec avertissement s'il en manque) ; POST /{id}/validate (InventorySessionController.java:164, OWNER) | DIFFÉRENT | oui (n°47, M33) | Acceptable : la validation se fait sans passer par le rapport (rapport accessible en en-tête). |

## 9. inventory_gap_report_page

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Résumé (comptés, concordants, surplus, manquants + valeurs) | gap_report_summary_header.dart:54-80 | inventory-report-screen.tsx:44-46,71-84 (+ écart net, précision) ; GET /{id}/gap-report (InventoryReportController.java:57) | CONFORME | oui (n°47) | — |
| Sections Manquants / Surplus / Concordants repliables | inventory_gap_report_page.dart:129-139 ; concordant_collapse_section.dart | inventory-report-screen.tsx:92-121 (onglets) | CONFORME | oui (n°47) | — |
| Partage (tous rôles) | inventory_gap_report_page.dart:72-78,223-247 | inventory-report-screen.tsx:28-30,56 (feuille de partage, GET /gap-report/text, InventoryReportController.java:73) | CONFORME | non | Test : rapport › Partager › « Copier » seulement, jamais WhatsApp. |
| Rapport détaillé signé (propriétaire) | inventory_gap_report_page.dart:82-90,244-247 | aucun (seul le texte du serveur) | PARTIEL | — | Mineur. |
| Appliquer les ajustements depuis le rapport (propriétaire, confirmation, écran de succès) | inventory_gap_report_page.dart:180-218 ; apply_adjustments_button.dart:85-138 | sur l'écran de comptage (§ 8) ; rien sur le rapport | DIFFÉRENT | oui (n°47) | Ouvert depuis l'historique, le rapport d'une session en cours n'a pas de bouton Valider. |
| Message « Aucun ajustement nécessaire » | inventory_gap_report_page.dart:159-178 | à vérifier | à vérifier | non | Code non lu dans le détail. |

## 10. transfer_history_page (+ transfer_form_bottom_sheet)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Accès propriétaire et employé (employé limité à sa boutique) | settings_page.dart:282-286 ; transfer_history_page.dart:583-591 | use-module-items.ts:73-81 (owner/admin) ; transfers-screen.tsx:41-50 prévoit le membre mais aucun lien n'y mène | CASSÉ pour le vendeur | oui (M3-M6 avec le propriétaire) | Le vendeur de la boutique destinataire ne peut pas réceptionner. |
| Nouveau transfert : produit, source, destination, quantité, contrôle du stock | transfer_form_bottom_sheet.dart:115,306-436 | new-transfer-modal.tsx:43-102 (quantité ≤ stock de la source, note) ; POST /stock/transfers (StockTransferController.java:73) | CONFORME | oui (M3, M5) | — |
| Transfert hors ligne | local_stock_transfer_datasource.dart | transfers-screen.tsx:129 (`submitOrQueue`) ; clé `create-transfer` (mutation-defaults.ts:92) | CONFORME | oui (M5) | — |
| Réceptionner (en transit, boutique destinataire) | transfer_history_page.dart:708-727 | transfers-screen.tsx:50-56,80 ; POST /{id}/complete (StockTransferController.java:110) | CONFORME | oui (M4, M6) | — |
| Filtres par statut (Tous, En transit, Effectués, En attente, Conflits) | transfer_history_page.dart:404-434 | transfers-screen.tsx:48-49 (« À recevoir » / Historique) | PARTIEL | non | — |
| Filtre par boutique active, pagination | transfer_history_page.dart:112-115 ; GET avec store/status/page | use-transfers.ts:26 (50 derniers, aucun filtre) | PARTIEL | non | Au-delà de 50 transferts, les plus anciens ne s'affichent plus. |
| Badge de statut en français | transfer_history_page.dart:747-790 | transfers-screen.tsx:73 | CONFORME | oui (M3) | — |
| Option multi-boutiques absente | — | transfers-screen.tsx:92-94 ; message « il faut deux boutiques » (102) | CONFORME | oui (n°49) | — |

## 11. audit_log_page

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Accès propriétaire | app_router.dart:175 | use-module-items.ts:120,131-137 (propriétaire strict) | CONFORME | oui (n°49, n°50) | — |
| Libellés des actions en français | audit_labels.dart ; audit_presenter.dart | audit-screen.tsx:28,85 : `human(e.action) · human(e.entityType)` sur le code brut ; le `actionLabel` français du serveur est ignoré (AuditEntryResponse : actionLabel) | CASSÉ | oui (n°50 : « Inventory report generated · Inventorysession ») | Toujours présent dans le code. Contraire à la règle « pas de données techniques à l'écran ». |
| Recherche d'un objet | audit_log_page.dart:376-387 | audit-screen.tsx:55-57 (`q`, à la validation du clavier) ; GET /audit (AuditController.java:65) | CONFORME | non | Test : Journal d'audit › chercher un nom de produit. |
| Période : aujourd'hui / 7 j / 30 j / plage au choix | audit_log_page.dart:140-190 | audit-screen.tsx:36-40,59 (24 h / 7 j / 30 j) | PARTIEL | non | Pas de plage au choix. |
| Filtres Boutique, Employé, Type d'action, Objet, « Effacer » | audit_log_page.dart:196-248,401-440 | aucun (le serveur les accepte : storeId, userId, action, entityType) | ABSENT | — | Gênant pour savoir « qui a fait quoi ». |
| Regroupement par jour, défilement continu | audit_log_page.dart:54,117 | audit-screen.tsx:42-48,71-74 (défilement continu, pas de groupes) | CONFORME | non | — |
| Export du journal | audit_log_page.dart:366-367 | aucun ici (module Exports séparé) | à vérifier | — | Je n'ai pas lu si le module Exports propose le journal. |
| Consultation hors ligne | local_audit_datasource.dart | aucun | ABSENT | — | Mineur. |
| Ouvrir le détail d'une ligne | audit_log_page.dart:569 | audit-screen.tsx:79,93-97 (dépliage avant / après en texte brut) | PARTIEL | non | Voir § 12. |

## 12. audit_entry_detail_page

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Sections Qui / Quoi / Sur quoi / Quand et où / Contexte | audit_entry_detail_page.dart:134-188 | audit-screen.tsx:90-92 (acteur, boutique, date sur la ligne) | PARTIEL | non | Pas d'écran de détail ; GET /audit/{id} (AuditController.java:126) inutilisé. |
| Tableau des changements Champ / Avant / Après | audit_entry_detail_page.dart:174,286-302 | audit-screen.tsx:93-97 (`valueBefore` / `valueAfter` bruts, souvent du JSON) | CASSÉ | non | Risque d'afficher des données techniques. Test : modifier un prix, puis Journal › déplier la ligne. |
| « Voir la vente » depuis une action de vente | audit_entry_detail_page.dart:155-160 | aucun | ABSENT | — | — |

## 13. stock_movements_page (« Mouvements de stock », propriétaire)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Liste de tous les mouvements de stock (entrées, sorties, ajustements), tous produits | stock_movements_page.dart:10-45 ; settings_page.dart:206-212 | aucun écran ; le Journal d'audit RN ne peut pas filtrer par objet | ABSENT | — | Seul substitut : les 10 derniers mouvements sur chaque fiche produit. |

---

## Écarts à retenir

### Bloquants pour un utilisateur V1 qui passe sur la nouvelle app

1. **Le vendeur (employé V1) n'a plus ni catalogue, ni stock, ni inventaire, ni transferts.**
   - L'onglet Produits et les tuiles Stock, Transferts et Inventaires sont réservés à owner/admin, et l'onglet Modules est caché au vendeur (`_layout.tsx:39-40` ; `use-module-items.ts:63-90`).
   - Pourtant les écrans Inventaires et Transferts sont codés pour lui (`inventories-screen.tsx:41`, `transfers-screen.tsx:43-50`). Un employé ne peut donc plus compter un inventaire ni réceptionner un transfert dans sa boutique, alors que V1 le permettait.
   - Cela contredit aussi la décision du 10/11 « membre = stock de sa boutique ».
2. **Les produits archivés ne se restaurent plus.**
   - Le catalogue filtre les archivés (`catalog.api.ts:218`) et aucun écran ne les liste ni ne les désarchive, alors que le code d'envoi existe (`product.api.ts:96`).
   - Un archivage par erreur est définitif pour le commerçant.
3. **Les brouillons ont disparu, sans reprise des existants.**
   - Plus de bandeau, de filtre, ni de « Valider ce produit » avec stock à ajouter.
   - Les brouillons V1 déjà en base reviennent dans `GET /products` et s'affichent comme des produits actifs sans signe distinctif.
   - À vérifier : ce que deviennent les ventes en attente qui contenaient un brouillon, et si une simple modification le passe en actif.

### Gênants

4. **Journal d'audit en anglais technique.**
   - La ligne affiche « Product created · Product » à partir du code brut, alors que le serveur envoie `actionLabel` en français (`audit-screen.tsx:28,85`).
   - Le détail montre `valueBefore` / `valueAfter` bruts, souvent du JSON (constat n°50 toujours présent).
   - Plus de filtres boutique, employé, type d'action ou objet, plus de plage de dates au choix, plus d'écran de détail ni de « Voir la vente ».
5. **« Mouvements de stock » (écran propriétaire) absent**, et l'historique d'un produit est réduit aux 10 derniers mouvements, sans filtre de type, de période ni de boutique. Les transferts y apparaissent « Autre ».
6. **Gestion des catégories absente** : pas de renommage ni de suppression (les routes PATCH et DELETE existent au serveur). On ne peut que créer une catégorie depuis le formulaire produit.
7. **Écritures hors ligne fragiles.**
   - Sans `submitOrQueue` ni clé de reprise : création de produit, entrée / ajustement de stock, archivage, démarrage / validation / ajout rapide d'inventaire.
   - Hors ligne, le formulaire risque d'attendre sur la roue, et l'écriture est perdue si l'app est tuée avant le retour du réseau.
   - À rejouer sur l'émulateur : les tests du 23/11 portaient sur une version plus ancienne.
8. **Référence SKU non modifiable** : le champ a disparu du formulaire, alors que V1 permettait la saisie.
9. **Transferts** : 50 derniers seulement, sans filtre par statut ni par boutique, et plus de « Initier un transfert » depuis la disponibilité d'un produit.
10. **Consultation du catalogue hors ligne limitée au cache de 24 h**, contre une base locale complète en V1.

### Mineurs

11. Annuler un inventaire : propriétaire seul (tout rôle en V1).
12. Comptage : pas de filtre « Écarts seulement », saisie par feuille au lieu du champ sur la ligne, pas de « Compter l'existant » sur un doublon, recherche sensible aux accents.
13. Rapport d'écarts : pas de rapport détaillé signé ni de bouton Valider sur le rapport lui-même.
14. Formulaire produit : pas de confirmation en quittant avec des changements non enregistrés.
15. Import CSV : plus d'association manuelle des colonnes ; un fichier aux en-têtes non reconnus ne peut pas être corrigé dans l'app.
16. Vue multi-boutiques : plus de cartes par boutique côte à côte, plus de recherche dans toutes les boutiques à la fois, plus de « Contacter via WhatsApp ».
17. À vérifier : `GET /stock/stores/{id}/products` exige l'option `inventory` (MultiStoreStockController.java:110) ; sans elle, le stock par boutique du catalogue et de la page Stock retombe sur le compteur de l'entreprise.

### Points meilleurs qu'en V1
- Tri du catalogue (nom / stock / prix) et filtre « Non suivi ».
- Prix conseillé selon la marge.
- Code-barres.
- Suivi du stock activable.
- Fusion de deux produits.
- Lien vers le fournisseur et appel direct.
- Page Stock « À racheter » avec entrée / ajustement en deux touches.
- Inventaire validable avec des produits non comptés (avec avertissement).
- Import avec contrôle ligne par ligne avant envoi.

Toutes les routes appelées par l'app RN pour ce lot existent au backend refonte.
