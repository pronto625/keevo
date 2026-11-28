# Lot D : rapports, rentabilité, exports, synchronisation, hors ligne, notifications (V1 2.0.1 → app RN 00daac2)

J'ai tout vérifié en lisant le code, rien n'a été lancé. Chemins utilisés ci-dessous :
- V1 = `scratch:v1/keevo/app/lib`
- RN = `scratch:rn`
- Backend = `keevo/backend/src/main/java/com/keevo`

Le préfixe `/api/v1` est porté par `EXPO_PUBLIC_API_BASE_URL` (`rn/lib/sdk/api/api.client.ts:4`). Tous les points d'accès cités côté RN existent dans le backend, sauf mention contraire.

**Accès en V1.** `/reports` mène à `OwnerReportsPage` pour le propriétaire. Elle a 5 onglets : Jour, Historique, Marges, Boutiques, Équipe (`owner_reports_page.dart:118-124`). Pour l'employé, la même route mène à `EmployeeReportsPage`, avec le seul onglet Jour (`:181-247`). Les routes `/exports`, `/settings/sync`, `/reports/rentabilite` et `/reports/boutiques` sont réservées au propriétaire (`app_router.dart:165-180`).

**Accès en RN.** Tout passe par la page Modules, groupe « Finance » (`use-module-items.ts`) :
- **Rapports :** propriétaire seulement (`:184-192`), et la route est enveloppée dans un écran d'offre Pro (`app/reports/index.tsx`, `components/pro-gate.tsx`).
- **Rapports avancés, Rentabilité, Exports :** propriétaire seulement, verrouillés selon le plan (`:205-234`).
- **Clôture de journée :** tous les rôles sauf « lecteur » (`:110-119`).
- **Historique des synchronisations :** Profil › Application (`profile-screen.tsx:173-179`).

**Écrans « bientôt disponibles ».** `coming-soon-modules.ts` liste advanced-reports, profitability, exports, alerts et mcp-access. Mais aucun `router.push('/coming-soon/…')` n'existe : seuls la route `app/coming-soon/[key].tsx` et ce fichier y font référence. C'est une configuration morte, et **aucune fonction du lot n'y tombe**. Le seul module marqué « Bientôt » est Achats.

---

## 1. Onglet « Jour » des rapports (`reports_page.dart`, propriétaire et employé)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Résumé en direct de la journée avant clôture (CA, nombre de ventes, « depuis la clôture de HH:mm ») | reports_page.dart:39-53, 143-155, 193-230 | Accueil : CA filtré « Aujourd'hui » (dashboard-screen.tsx:89-92, 142). Clôture de journée : résumé visible **seulement après** clôture (day-closure-screen.tsx:93-120, 124-130) | PARTIEL | non | Le chiffre du jour existe à l'accueil, mais l'écran de clôture affiche « pas encore clôturée » sans aucun chiffre. Test : Modules › Commerce › Clôture de journée avant de clôturer. |
| Répartition espèces / mobile money du jour | :236-256 | Seulement dans la clôture une fois faite (day-closure-screen.tsx:107-108). Le rapport détaillé donne une répartition **par état de facture**, pas par moyen de paiement (report-detail-screen.tsx:135-168) | PARTIEL | oui (M34, après clôture) | Avant la clôture, aucune vue espèces / mobile money. |
| Produit le plus vendu du jour | :260-275 | Clôture faite : day-closure-screen.tsx:112-120. Rapports : top 5 (report-detail-screen.tsx:409, 532) | CONFORME | oui (E23) | Modules › Finance › Rapports › carte du jour. |
| Bandeau « n vente(s) en attente », qui ouvre la liste des ventes en attente | :280-330 | Clôture faite : simple ligne « en attente » (day-closure-screen.tsx:109-110). Pas de liste des ventes à valider dans la caisse RN | ABSENT | non | La validation des brouillons de vente (VALIDATE_SALE) n'existe pas dans le code RN (à confirmer dans le lot caisse). |
| Lien « Historique des rapports » | :336-337 | Rapports avancés › « Historique des rapports » pousse `/reports` (advanced-reports-screen.tsx:94), c'est-à-dire la liste calculée des périodes | DIFFÉRENT | non | Le sous-titre dit « Retrouver les rapports déjà envoyés », alors que l'écran ne montre aucun rapport envoyé (voir §3). |
| Lien « Voir l'historique des ventes » | :363 | Onglet Factures et liste des ventes | CONFORME | oui | — |
| Bouton « Clôturer la journée » avec aperçu des totaux puis animation de succès | :385-396, 539-600 | Bouton, puis confirmation simple, puis résumé (day-closure-screen.tsx:64-73, 133-135) | PARTIEL | oui (M34) | Plus d'aperçu chiffré avant de confirmer. |
| Employé : résumé limité à ses propres ventes | :34-53 | Vendeur : pas de module Rapports. La clôture concerne sa boutique (day-closure-screen.tsx:45) | DIFFÉRENT | non | L'employé V1 perd sa vue « mes ventes du jour » (l'accueil vendeur n'a pas été revérifié). Test : compte vendeur › Modules. |
| Calcul hors ligne du résumé (base locale) | day_closure_repository_impl.dart:40-44 | Clôture : GET `/day-closures` en ligne seulement (day-closure-screen.tsx:52-58). Accueil : statistiques locales de secours (use-invoice-stats.ts:15-45) | PARTIEL | non | Hors ligne, l'écran de clôture ne montre rien de chiffré. |
| Bouton « Exporter » de la barre (ventes, lignes, clôtures, rapports pré-cochés) | owner_reports_page.dart:91-102 | aucun | ABSENT | non | Voir §6. |

## 2. Hub de rapports du propriétaire (`owner_reports_page.dart`)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| 5 onglets Jour / Historique / Marges / Boutiques / Équipe | :118-144 | Éclatés en modules séparés : Rapports, Rentabilité (produits + boutiques), Clôture | DIFFÉRENT | oui (balayage n°49) | Acceptable, mais l'onglet Équipe n'existe plus nulle part (§7). |
| Liens directs `/reports/rentabilite`, `/reports/boutiques` | app_router.dart:487-494 | `/profitability` | DIFFÉRENT | non | Aucun lien de notification ne pointe vers ces chemins (§11). |
| Accès sans condition de plan | app_router.dart:422-430 | Rapports enveloppés dans l'offre Pro (app/reports/index.tsx) ; Rentabilité verrouillée au plan Team (module-plans.ts:9-21) | DIFFÉRENT | non | Le backend sert GET `/reports` dès le module « reporting », sans plan payant (ReportController.java:126). À vérifier : le plan attribué aux comptes V1 migrés, sinon un propriétaire gratuit perd ses rapports. Test : compte gratuit › Modules › Rapports. |

## 3. Historique des rapports (`report_history_page.dart`)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Liste des rapports générés par le serveur (fin de journée, hebdomadaire, multi-boutique), via GET `/api/v1/reports` | remote_report_history_datasource.dart:13-36 | `/reports` = périodes jour / mois / année calculées depuis les factures (reports-screen.tsx:186-190, 106). GET `/reports` n'est appelé nulle part en RN | DIFFÉRENT | oui (E23) | Le point d'accès existe (ReportController.java:127). L'historique des rapports envoyés n'est plus consultable. |
| Filtre Tous / Journalier / Hebdo | :151-156 | Bascule Jour / Mois / Année (reports-screen.tsx:212-226) | DIFFÉRENT | oui | Ce n'est pas le même objet. |
| Filtre par employé (vue boutique) | :188-226 | aucun | ABSENT | non | — |
| Carte : statut d'envoi (pastille), CA, nombre de ventes, auteur | report_history_card.dart:60-130 | Carte période : encaissé, nombre de factures, clients (reports-screen.tsx:137-161) | DIFFÉRENT | oui | — |
| Hors ligne : lecture du cache local | report_history_repository_impl.dart:11-12, 40-42 | Statistiques calculées sur les factures du téléphone, avec le bandeau « données à jour depuis… » (use-invoice-stats.ts ; reports-screen.tsx:199) | CONFORME | non | Test : couper le réseau › Rapports. |
| Tirer pour rafraîchir, message vide « après la première clôture » | :106-125 | aucun tirer-pour-rafraîchir (FlatList sans onRefresh) | PARTIEL | non | Mineur. |

## 4. Détail d'un rapport (`report_detail_page.dart`)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Chiffre du jour, nombre de ventes | :136-145 | Indicateurs clés avec écart vs période précédente, panier moyen, taux de recouvrement, dépenses, marge nette, clients, top produits, par boutique, observations (report-detail-screen.tsx:386-566) | CONFORME (plus riche) | oui (E23) | « Marge nette » jugée trompeuse au test. Les bornes de jour sont en UTC (`T00:00:00.000Z`, :45-46) : les ventes entre 0 h et 1 h à Douala tombent la veille. |
| Infos : boutique, date, généré le, tentatives d'envoi, dernière tentative, automatique | :170-193 | aucun | ABSENT | non | Lié à l'absence de l'historique serveur. |
| Texte intégral du rapport (copiable) | :216-221 | aucun | ABSENT | non | — |
| « Renvoyer via WhatsApp » (propriétaire), avec messages de succès / échec | :80-100, 232-247 | aucun appel `/reports/{id}/resend` | ABSENT | non | Le point d'accès existe (ReportController.java:244). Rapports avancés propose seulement « Envoyer un rapport test » (advanced-reports-screen.tsx:99). |
| Pastilles de type et de statut d'envoi (Envoyé / Échec / App uniquement) | :297-345 | aucun | ABSENT | non | — |

## 5. Rentabilité : liste produits, détail produit, performances boutiques

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Période : Aujourd'hui / 7 j / 30 j / Personnalisé (calendrier) | period_filter_chips.dart:62-95 | 7 j / 30 j / Mois en cours (profitability-screen.tsx:57) | PARTIEL | oui (balayage) | Manquent « Aujourd'hui » et la plage personnalisée. |
| Filtre boutique | product_profitability_list_page.dart:22, 49 | Puces de boutiques si plus d'une (profitability-screen.tsx:58) | CONFORME | non | — |
| Résumé (marge brute, taux, CA, avertissement « lignes sans prix d'achat ») | profitability_row / modèle | profitability-screen.tsx:67-89, GET `/reporting/profitability/summary` (backend :82) | CONFORME | oui (n°49) | — |
| Tri : Marge %, Marge XAF, CA, Unités | sort_chips.dart:21-26 | aucun tri : ordre par défaut du serveur, `MARGIN_PCT_DESC` (ProfitabilityController :105-110) | ABSENT | non | Test : Rentabilité › Produits, chercher des puces de tri. |
| Ligne produit : unités, CA, marge %, marge, badge « PERTE » | profitability_row.dart:44-120 | Ligne avec couleur selon le niveau de marge (profitability-screen.tsx:102-118) | CONFORME | oui | Il n'y a pas le mot « PERTE », mais la couleur rouge la remplace. |
| Toucher une ligne ouvre le détail produit | product_profitability_list_page.dart:146-151 | ligne non cliquable | ABSENT | non | — |
| Détail : prix catalogue / achat / transport, unités, CA, coût total, marge, prix appliqués min / max / moyen, courbe de marge sur 7 jours, meilleure boutique | product_profitability_detail_page.dart:99-185 | aucun écran | ABSENT | non | Le point d'accès existe (GET `/reporting/profitability/products/{id}`, :130) mais RN ne l'appelle pas. |
| Hors ligne : calcul local et bandeau « Mode hors ligne — données de la dernière synchronisation » | profitability_repository_impl.dart:39-55 ; offline_data_banner.dart:27 | Requêtes en ligne seulement : cache persistant (24 h) sinon « Impossible de charger » (profitability-screen.tsx:44-46, 63-64) | PARTIEL | non | Test : mode avion › Rentabilité. |
| Rafraîchissement auto au retour du réseau | list_page.dart:28-33 | `refetchOnReconnect` (query-client.ts) | CONFORME | non | — |
| Boutiques : rang, nom, CA, ventes, écart %, produit phare | store_performance_row.dart:60-135 | Onglet Boutiques : rang, nom, ventes, panier, CA, écart (profitability-screen.tsx:131-147) | PARTIEL | non | Le produit phare manque. |
| Boutiques : choix du critère de classement (CA / Ventes / Panier moyen) | store_performance_page.dart:87 ; ranking_metric_toggle.dart:15-17 | aucun : `metric` par défaut, CA (backend :153-157) | ABSENT | non | Test : Rentabilité › Boutiques. |

## 6. Export serveur (`export_page.dart`), export local (`local_export_page.dart`), Mes exports (`my_exports_page.dart`)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Points d'entrée : bouton Exporter pré-rempli (Rapports, Historique des ventes, Vue stock, Journal d'audit) + Réglages › Exporter / Mes exports | export_entry_button.dart:17-24 ; owner_reports:91 ; sales_history_page:70 ; global_stock_overview:156 ; audit_log_page:365 ; settings_page:195, 204 | Seulement Modules › Finance › Exports (use-module-items.ts:225-233) | PARTIEL | oui | Plus de pré-remplissage ni de filtres du journal d'audit transmis à l'export (export_page.dart:484-492). |
| Choix des jeux de données (cases + « Tout ») | :428-438 | Cases à cocher depuis GET `/exports/datasets` (exports-screen.tsx:95, 165-173) | PARTIEL | oui | Pas de « Tout ». |
| Formats CSV (tous plans) / Excel / PDF (Premium), avec offre si refus du plan | :151-160, 568-574 | Puces CSV / Excel / PDF (:178). Tout le module est verrouillé au plan Pro (module-plans.ts:12, `gate('exports')`) | DIFFÉRENT | oui (Excel) | Le backend autorise le CSV sans module payant (ExportService.java:130-136). Un propriétaire gratuit V1 perd donc l'export CSV. |
| PDF : choix d'un **modèle** (fin de journée, inventaire, état de stock, ventes) | :457-478 | PDF proposé quel que soit le choix ; `pdfTemplate` ignoré | PARTIEL | non | Avec plusieurs jeux, ou un jeu sans modèle, le serveur refuse (`EXPORT_FORMAT_UNSUPPORTED`, PdfExportRenderer.java:133-140). Test : Exports › cocher 2 jeux › PDF. |
| Période : aujourd'hui, 7 j, mois (30 j), personnalisé, toutes dates | export_form_widgets.dart:101-139 | 7 j / 30 j / mois / tout (:183) | PARTIEL | non | Manquent « Aujourd'hui » et la plage personnalisée. |
| Filtre boutique, avec « ne s'applique pas » selon le jeu | :519-536 | Puces affichées si un jeu accepte le filtre et s'il y a plus d'une boutique (:151-152, 186-191) | CONFORME | non | — |
| Lancer, puis feuille de partage du fichier ; gros export « préparé en arrière-plan » | :205-208, 662-675 | POST `/exports` en binaire, partage, message si 202 (:108-128) | CONFORME | oui | Nom de fichier lisible (:67-72) et jeton renouvelé par `apiClient` (défaut 9 du 23/11 corrigé dans le code). Ne pas ouvrir la feuille de partage sur le Redmi. |
| Hors ligne : bandeau + « Réessayer », bouton bloqué | :270-354 | Rien : le bouton reste actif, la mutation se met en pause et la roue tourne | ABSENT | non | Test : mode avion › Exports › Exporter. |
| **Export CSV local, sans réseau** (17 jeux : produits, stock, ventes, clôtures, clients, fournisseurs, journal…) | local_export_page.dart:1-8 ; local_export_schema.dart:135-428 | aucun | ABSENT | non | — |
| Mes exports : liste, statut, créé le / **expire le**, boutique, libellés des jeux | my_exports_page.dart:305-370, 422-427 | « Récents » (10) : titre = **codes bruts** des jeux (`j.datasets.join(', ')`, exports-screen.tsx:209), date, statut | PARTIEL | non | Des codes techniques sont affichés (« sales, sale_items · XLSX ») : contraire à la règle « pas de données techniques ». Le statut `EXPIRED` n'a pas de traduction et s'affiche brut. |
| Ouvrir un export prêt | :90, 396 | Toucher, téléchargement, partage (:132-147, 211) | CONFORME | non | — |
| « Relancer » un export échoué | :125-138, 402-404 | aucun | ABSENT | non | — |
| Rafraîchissement automatique tant qu'un export est en cours | :44-50, 189 | aucun `refetchInterval` | ABSENT | non | — |
| Ouverture depuis la notification EXPORT_READY / EXPORT_FAILED, export mis en évidence et ouvert | :21-22, 151-169 | La notification ouvre la liste des notifications (§11) | ABSENT | non | — |

## 7. Classement des vendeurs (`seller_leaderboard_page.dart`)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Classement par période (Aujourd'hui / 7 / 30 / perso) | :83-88 | Aucun écran. GET `/reporting/leaderboard` sert seulement à `topSeller` (shop.api.ts:93-104), qui n'est affiché nulle part | ABSENT | non | La fiche membre montre le total et le nombre de factures du vendeur, sans période ni rang (user-detail-screen.tsx:34, 274-275). |
| Critère CA / Ventes / Panier moyen | ranking_metric_toggle.dart:15-17 | aucun | ABSENT | non | — |
| Ligne : rang, nom, badge « Propriétaire », panier moyen | seller_leaderboard_row.dart:53-126 | aucun | ABSENT | non | — |
| Hors ligne : calcul local et bandeau | :101-104 | aucun | ABSENT | non | — |
| Classement partagé aux employés (réglage du propriétaire) | (réglage V1) | Bouton « Classement visible des vendeurs » (advanced-reports-screen.tsx:92), PUT `/tenant/leaderboard-visibility` | CASSÉ | non | Le réglage s'enregistre, mais aucun écran RN n'affiche le classement, ni au propriétaire ni aux employés. |

## 8. Opérations bloquées (`blocked_operations_page.dart`)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Liste des opérations refusées par le serveur, gardées sur l'appareil (motif, boutique, produits, opérations liées) | quarantined_operations_section.dart:141-247 | Historique des synchronisations : seules les erreurs journalisées par factures, boutiques, membres, produits et dépenses (sync-history-screen.tsx:56-59, 166-180) | PARTIEL | non | Une vente de caisse refusée après relance de l'app n'est **ni gardée ni journalisée** : pas d'`onError` dans mutation-defaults.ts:73-78, pas d'`appendSyncLog` dans la caisse. |
| Renvoyer | :257-259 | aucun | ABSENT | non | — |
| Exporter l'opération (partage du texte) | :25, 262-264 | aucun | ABSENT | non | — |
| Rattacher une vente (propriétaire) | :268-270 | aucun | ABSENT | non | — |
| Abandonner (export d'abord, double confirmation) | :334-430 | « Tout effacer » de l'historique local (sync-history-screen.tsx:34-38, 66) | ABSENT | non | Rien n'a d'effet sur la file. |

## 9. Conflits de synchro (`sync_conflict_log_page.dart`) et réglages de synchro (`sync_settings_page.dart`)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Conflits serveur (stock négatif…) avec détail : type, résolution, élément, opération, date | sync_conflict_log_page.dart:19-114 ; sync_settings_page.dart:346-416 | aucun appel GET `/sync/conflicts` (backend SyncController.java:264) | ABSENT | non | — |
| Onglet Historique : événements PUSH / PULL / diagnostic, opérations abandonnées | sync_settings_page.dart:263-319 | Journal local des écritures (200 entrées), groupé par jour, détail d'erreur dépliable (sync-history-screen.tsx ; sync-log.store.ts:5) | DIFFÉRENT | non | Acceptable. Test : Profil › Historique des synchronisations. |
| Onglet File d'attente (toutes les opérations en attente) | :479-522 | Seulement 12 clés reconnues (use-pending-mutations.ts:20-65) | PARTIEL | non | Absents de la liste : ventes de caisse, transferts, comptages, paiements, clients, fournisseurs, rôles. Libellés = **identifiant brut** pour une facture modifiée ou validée et une dépense modifiée (:46-56). Le bandeau, lui, compte toutes les actions en pause (offline-banner.tsx:17-21). |
| « Forcer la sync » | :86-95 | « Actualiser » dans le bandeau d'ancienneté (freshness-notice.tsx) et tirer-pour-rafraîchir des listes | CONFORME | non | — |
| Appareils actifs sur 30 jours (« cet appareil / autre », vu il y a…) | :127-225 | aucun appel GET `/sync/devices` | ABSENT | non | Mineur. |

## 10. Indicateur de synchro (widgets `sync_indicator`)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Pastille d'état : En ligne / Synchronisation / Hors ligne Jour n/7 / Hors ligne critique | sync_indicator.dart:375-378 ; sync_status.dart | Bandeau « Vous êtes hors connexion » ou « n action(s) en attente de sync » (offline-banner.tsx), plus « données à jour depuis… » sur certains écrans (freshness-notice.tsx) | DIFFÉRENT | oui (E05) | Pas de compte des jours restants avant la limite de 7 jours. |
| Feuille de détail (en attente, erreur, dernière sync, conflits, synchroniser, réglages) | sync_detail_bottom_sheet.dart:38-159 | Ligne Profil, avec le nombre en attente (profile-screen.tsx:173-179) | PARTIEL | non | — |
| Message « Conflit de stock : X — stock négatif » avec « Voir » | sync_indicator.dart:60-90 | aucun | ABSENT | non | Le serveur renvoie `CONFLICT` pour une vente acceptée mais en stock négatif (SyncPushService.java:593-599). sync-push.ts:74 le traite comme un refus. Le nouvel essai devrait revenir `DUPLICATE` : à vérifier sur appareil. |
| Message de refus « Non synchronisé (opération) : motif » avec « Voir » | :126-161 | Message rouge seulement si l'écran d'origine est encore ouvert (pos-screen.tsx:159), ou `onError` des valeurs par défaut pour certaines clés (mutation-defaults.ts:85) | PARTIEL | non | — |
| Synchro requise après 7 jours : écran bloquant, « Continuer en lecture seule » | sync_required_modal.dart:135-200 ; sync_indicator.dart:179-202 | Sur réponse 423, remise à jour puis renvoi automatiques (sync-push.ts:80-122) | DIFFÉRENT (mieux en ligne) | non | Pas d'avertissement quand on approche des 7 jours hors ligne. |
| Bandeau « Synchronisation en échec depuis X » + Réessayer | sync_warning_banner.dart:33-44 | freshness-notice : « échec, dernière synchro complète… » + Actualiser | CONFORME | oui (n°37) | — |

## 11. Notifications push (`core/notification/fcm_service.dart`)

V1 ouvre le `deepLink` envoyé par le serveur, quel que soit le type, ou la liste des notifications pour une annonce (fcm_service.dart:403-418). L'adaptateur Expo du backend envoie `data = metadata + deepLink + type`, avec le type **en majuscules** (ExpoPushAdapter.java:182-184). RN ne reconnaît au toucher que `whatsapp_message`, `invoice` et `payment` en minuscules ; tout le reste ouvre `/notification` (app/_layout.tsx:226-246). Dans cette liste, le toucher marque seulement comme lu, avec un `// TODO: navigate` (notification-screen.tsx:67-70).

| Type (serveur) | Lien envoyé | V1 au toucher | RN au toucher | Verdict | Testé appareil |
|---|---|---|---|---|---|
| DAY_CLOSED, TREND_UP / TREND_DOWN | `/reports/history` | Historique des rapports | liste (rien) | CASSÉ | non |
| STOCK_TRANSFER_CREATED | `/stock/transfers` | Transferts | liste (rien) | CASSÉ | réception oui (M18), toucher non |
| SALE_DRAFT_VALIDATED / _PRODUCTS_UPGRADED, SALE_PENDING_DRAFT_PRODUCTS | `/pos/sales-history/{id}`, `/pos/pending/{id}` | la vente / la vente en attente | liste (rien) | CASSÉ | non |
| STOCK_ALERT(_BATCH), EMPLOYEE_PRICE_CHANGED / _STOCK_MODIFIED / _PRODUCT_CREATED (+ _BATCH), CSV_IMPORT_DRAFTS_PENDING | `/products/{id}/edit`, `/stock/overview`, `/products` | fiche produit / vue stock | liste (rien) | CASSÉ | non |
| EXPORT_READY / EXPORT_FAILED | `/exports/{id}` | Mes exports, export ouvert | liste (rien) | CASSÉ | non |
| MODULE_EXPIRED / _DISABLED, PLAN_PAYMENT_CONFIRMED, INVOICE_OVERDUE | `/settings/modules`, `/plan`, `/invoices?paymentState=OVERDUE` | (nouveaux) | liste (rien) | CASSÉ | non |
| WHATSAPP_MESSAGE_RECEIVED (+ `contactPhone`) | `/whatsapp/conversations/{tel}` | — | RN attend `whatsapp_message` : liste au lieu de la conversation ; au premier plan, synchro au lieu du rafraîchissement de la messagerie (_layout.tsx:214-218) | CASSÉ | non |
| GLOBAL_ANNOUNCEMENT, PLATFORM_CAMPAIGN | aucun | liste | liste | CONFORME | non |
| Enregistrement du jeton (POST `/devices/token`) | fcm_service.dart:240-290 | jeton Expo envoyé à chaque démarrage (_layout.tsx:201-207 ; notification.api.ts:34-41) | CONFORME | oui (M18) | — |
| Message local « Clôture automatique : boutique — date » | auto_closure_notification_checker.dart:34-35 | push DAY_CLOSED du serveur seulement | DIFFÉRENT | non | — |

Test d'une ligne : déclencher un export lourd ou une clôture depuis un 2ᵉ appareil, toucher la notification sur l'émulateur, puis vérifier l'écran ouvert.

## 12. Hors ligne : file V1 `sync_queue` contre RN

En RN, une écriture hors ligne passe par l'une de ces trois voies :
- **(a) Synchro avec identifiant d'opération :** `pushOperation` vers POST `/sync/push`, sans doublon en cas de nouvel essai (sync-push.ts:102-122). Cinq types seulement l'utilisent : CREATE_SALE, CANCEL_SALE, CREATE_CLIENT, CREATE_STORE, UNARCHIVE_PRODUCT.
- **(b) Mutation enregistrée sur le téléphone :** elle a une clé avec un envoi par défaut (mutation-defaults.ts) et reprend après une relance de l'app.
- **(c) Mutation en mémoire seulement :** pas de clé ; elle se met en pause (`networkMode: 'offlineFirst'`, query-client.ts:17). Une fois enregistrée sans fonction d'envoi, elle **ne peut pas reprendre** après une fermeture de l'app.

**Limite commune.** Le cache enregistré expire à 24 h (`maxAge`, app/_layout.tsx:298). Si l'app reste fermée plus de 24 h avec des actions en attente, toute la file est jetée au redémarrage. En V1, la file Drift n'a pas de limite de durée. Cette limite était déjà notée dans RAPPORT.md:287 et n'a pas changé.

| Opération V1 (sync_queue) | Preuve V1 | RN | Survit à une relance | Verdict | Testé appareil |
|---|---|---|---|---|---|
| CREATE_SALE | sale_repository_impl.dart:91 | (a) `create-sale` | oui (≤ 24 h) | CONFORME | oui (E05, E26) |
| CANCEL_SALE | :329 | (a) `cancel-sale` | oui | CONFORME (à revérifier : B5 corrigé côté backend 22-19) | partiel (B5 avant correction) |
| VALIDATE_SALE (ventes en attente) | :281 | aucune | — | ABSENT | non |
| CREATE_DRAFT_PRODUCT, PROMOTE_PRODUCT | local_product_datasource.dart:313 ; sale_repository_impl.dart:80 | aucune trouvée | — | ABSENT (à vérifier, lot caisse) | non |
| CREATE_PRODUCT | local_product_datasource.dart:153 | (c) use-product-catalog.ts:64 | **non** | PARTIEL | oui sans relance (M31) |
| UPDATE_PRODUCT | product_repository_impl.dart:127 | (b) `update-catalog-product` / `update-product` | oui | CONFORME | oui (M12) |
| ARCHIVE_PRODUCT | :223 | (c) use-product-catalog.ts:83 | non | PARTIEL | oui sans relance (M13) |
| UNARCHIVE_PRODUCT | :234 | (a) via `update-product` (product.api.ts:96) | oui | CONFORME | non |
| UPLOAD / DELETE_PRODUCT_PHOTO | product_photo_outbox.dart:43-44 | (c) use-product-catalog.ts:94-95 | non | PARTIEL | non |
| RECORD_STOCK_ENTRY | stock_repository_impl.dart:207 | (c) POST `/products/{id}/stock/entry` (catalog.api.ts:164) | non | PARTIEL | oui sans relance (M32) |
| STOCK_ADJUST | :278 | (c) catalog.api.ts:168 | non | PARTIEL | oui sans relance (M32) |
| STOCK_TRANSFER | stock_transfer_repository_impl.dart:123 | (b) `create-transfer` ; réception = (c) (use-transfers.ts:59-60) | oui / non | CONFORME / PARTIEL | oui (M5, M6) |
| CREATE_INVENTORY_SESSION | inventory_session_repository_impl.dart:87 | (c) use-inventory.ts:37 | non | PARTIEL | oui sans relance (M33) |
| SAVE_INVENTORY_COUNT | inventory_count_repository_impl.dart:123 | (b) `save-count` | oui | CONFORME | oui (M33, roue) |
| VALIDATE_INVENTORY | validate_inventory_provider.dart:262 | (c) use-inventory.ts:49-50 | non | PARTIEL | oui sans relance (M33) |
| CREATE_DAY_CLOSURE | day_closure_repository_impl.dart:152 | (c) day-closure-screen.tsx:64-66 | non | PARTIEL | oui sans relance (M34) |
| CREATE_CLIENT | client_repository_impl.dart:73 | (a) `create-customer` (use-create-customer.ts:18, 56) | oui | CONFORME dans le code (échec M24 à revérifier) | à refaire |
| UPDATE_CLIENT | :116 | (c) use-update-customer.ts:13 | non | PARTIEL | non |
| ARCHIVE / UNARCHIVE_CLIENT | :136, 149 | aucune route d'archivage client en RN | — | ABSENT (à vérifier, lot clients) | non |
| CREATE_SUPPLIER | supplier_repository_impl.dart:70 | (b) `create-supplier` | oui | CONFORME | oui (M25) |
| UPDATE_SUPPLIER | :113 | (c) PATCH `/suppliers/{id}` | non | PARTIEL | non |
| ARCHIVE / UNARCHIVE_SUPPLIER | :133, 146 | suppression DELETE (supplier.api.ts:39), pas de restauration | — | DIFFÉRENT / ABSENT | non |
| CREATE_CATEGORY | category_repository_impl.dart:83 | (c) use-product-catalog.ts:101 | non | PARTIEL | non |
| RENAME_CATEGORY, TOGGLE_CATEGORY | :128-178 | aucune | — | ABSENT (à vérifier, lot catalogue) | non |
| CREATE_STORE, UPDATE_STORE, DEACTIVATE_STORE | store_repository_impl.dart:95, 145, 178 | (a)+(b) `create-shop`, (b) `update-shop`, `delete-shop` | oui | CONFORME | oui (M1) |
| REASSIGN / DEACTIVATE / REACTIVATE_EMPLOYEE | employee_repository_impl.dart:89-157 | (b) `update-user`, `toggle-user-status` | oui | CONFORME | oui (M35, création) |
| SUBMIT_FEEDBACK | feedback_repository_impl.dart:16, 33 | (c) feedback-screen.tsx:23 | non | PARTIEL | non |

Ce qui cesse de marcher hors ligne pour un utilisateur V1 :
- la validation des ventes en attente et les produits brouillons créés en caisse ;
- l'archivage et la restauration des clients et des fournisseurs ;
- le renommage et la désactivation des catégories ;
- la consultation de la rentabilité, du classement et de la clôture sans données en cache ;
- l'export CSV local.

En plus, environ 12 écritures (produit, entrées et ajustements de stock, inventaire, clôture, réception de transfert, photos, client et fournisseur modifiés, catégorie, avis) tiennent hors ligne **tant que l'app reste ouverte**, mais sont perdues si elle est fermée. Les tests du 23/11 ne coupaient l'app que pour les ventes. Test : mode avion › entrée de stock › fermer l'app (balayage) › rouvrir › réseau › vérifier en base.

---

## Écarts à retenir

### Bloquant pour un utilisateur V1 qui passe sur la nouvelle app
1. **Les toucher de notification ne mènent plus nulle part.** RN ne reconnaît pas les types envoyés par le serveur (majuscules) et ignore `deepLink`. La liste des notifications ne navigue pas non plus (TODO, notification-screen.tsx:69). Clôture, transfert, stock, vente en attente, export prêt, message WhatsApp : l'utilisateur atterrit sur une liste inerte.
2. **La file hors ligne n'est pas durable.** Le cache enregistré expire à 24 h, et une dizaine d'écritures (produit, stock, inventaire, clôture, réception de transfert…) n'ont pas de clé de reprise : app fermée, action perdue sans message. En V1, la file Drift tient jusqu'à l'envoi.
3. **Une vente refusée à la synchro disparaît.** Pas d'écran « Opérations bloquées », ni renvoi, ni export, ni abandon tracé. Après une relance, le refus est même silencieux. En V1, la vente refusée reste gardée et exportable.

### Gênant
4. Plus d'historique des rapports envoyés (statut d'envoi, texte, « Renvoyer via WhatsApp »), alors que le backend le sert. Le lien « Historique des rapports » des Rapports avancés ouvre en fait la liste des périodes, ce qui trompe l'utilisateur.
5. Classement des vendeurs absent (période, critère, badge propriétaire). Le réglage « Classement visible des vendeurs » n'a donc aucun effet visible.
6. Rentabilité appauvrie : pas de détail produit (prix, coûts, prix appliqués, courbe 7 jours, meilleure boutique), pas de tri, pas d'« Aujourd'hui » ni de plage personnalisée, pas de critère pour les boutiques, rien hors ligne sans cache.
7. Exports :
   - pas de CSV local hors ligne, pas de « Relancer », pas de rafraîchissement automatique ;
   - pas de boutons « Exporter » pré-remplis dans les autres écrans ;
   - PDF proposé sans choix de modèle (refus serveur au-delà d'un jeu) ;
   - module entier verrouillé au plan Pro, alors que le CSV était gratuit en V1 ;
   - codes bruts des jeux affichés dans « Récents ».
8. Jour en cours : plus de résumé chiffré avant la clôture (espèces / mobile money, ventes en attente), et plus d'aperçu avant de confirmer. Le vendeur perd sa vue « mes ventes du jour ».
9. Rapports enveloppés dans l'offre Pro, alors que GET `/reports` est ouvert sans plan. À vérifier selon le plan donné aux comptes V1 migrés.

### Mineur
10. La file visible dans « Historique des synchronisations » omet ventes, transferts, comptages, paiements, clients et fournisseurs. Elle affiche aussi des identifiants bruts pour certaines actions.
11. Pas de conflits de synchro, ni de liste des appareils, ni de compte des jours avant la limite de 7 jours (la remise à jour automatique sur 423 compense en ligne).
12. Rapports en bornes UTC : les ventes entre 0 h et 1 h à Douala tombent la veille. Pas de tirer-pour-rafraîchir sur la liste des rapports. Le statut d'export `EXPIRED` n'est pas traduit.
13. `coming-soon-modules.ts` est une configuration morte : aucun écran n'y renvoie.

**Fichiers clés.**
- Côté RN, dans le dossier `AI/bref@00daac2:` :
  - `app/_layout.tsx` (lignes 201-249 et 296-308)
  - `lib/sdk/mutation-defaults.ts`
  - `lib/sdk/sync-push.ts`
  - `features/insights/screens/exports-screen.tsx`
  - `features/insights/screens/profitability-screen.tsx`
  - `features/insights/screens/advanced-reports-screen.tsx`
  - `features/dashboard/screens/reports-screen.tsx`
  - `features/dashboard/screens/report-detail-screen.tsx`
  - `features/sync/screens/sync-history-screen.tsx`
  - `lib/sync-log/use-pending-mutations.ts`
  - `features/notification/screens/notification-screen.tsx`
- Côté backend : `/home/toor/Project/FreeLance/AI/Keevo/keevo/backend/src/main/java/com/keevo/messaging/notification/adapter/out/ExpoPushAdapter.java`