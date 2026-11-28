# Lot A : compte et administration, Keevo V1 2.0.1 comparé à l'app RN « Bref Point » (00daac2)

Lecture seule. Chaque verdict vient du code actuel :
- V1 : `scratch:v1/keevo/app/lib` ;
- RN : `scratch:rn` ;
- backend : routes extraites des contrôleurs refonte, liste complète dans `/tmp/claude-1000/-home-toor-Project-FreeLance-AI-Keevo/4e9a5721-ffbb-4cd1-833e-96fe25c9e37d/scratchpad/routesA.txt`.

La colonne « Testé appareil » renvoie à RAPPORT.md, constats.md et constats-multiboutiques.md.

**Rôles.** En RN, un EMPLOYEE V1 devient « seller » et un MANAGER devient « admin » (`features/authentication/api/auth.mapper.ts:42-47`).

---

## 1. Connexion et inscription (auth_page, forgot_password, reset_password, tenant_picker)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Connexion du propriétaire par téléphone + mot de passe | auth_page.dart:352, 391 | Code à usage unique par téléphone ou e-mail : login-screen.tsx:41 → login-by-owner-screen.tsx:61,77 ; `POST /auth/otp/request` et `/auth/otp/verify` existent | DIFFÉRENT (acceptable) | oui (constats n° 1, 8) | Le mot de passe V1 du propriétaire ne sert plus. La connexion dépend de la livraison du code par SMS ou WhatsApp en production. |
| Connexion d'un employé V1 (téléphone + mot de passe) | auth_page.dart:352 (même écran) | « Je suis un commercial » : login-by-credentials-screen.tsx:82 envoie `username` (auth.api.ts:93) | **CASSÉ pour les employés V1** | non (testé seulement avec un identifiant créé dans RN) | Les employés V1 n'ont pas d'identifiant : la migration V41 ajoute la colonne sans la remplir. Un numéro saisi dans « Identifiant » est cherché comme identifiant et refusé. Le seul accès possible passe par « Je suis propriétaire » → téléphone → code, ce qui est trompeur. Test : émulateur, compte V1 migré avec un employé, Connexion → « Je suis un commercial » → numéro + mot de passe V1. |
| Google / Apple | — | login-by-owner-screen.tsx:268, 280 | Nouveau | non | — |
| Messages d'erreur (identifiants faux, compte verrouillé 15 min, accès désactivé) | auth_page.dart:161-170 | Message du serveur en français ; compte désactivé : auth.api.ts:80, 101 | CONFORME | partiel (compte désactivé : avant correction, roue sans message) | Rejouer : désactiver un membre, puis tenter sa connexion. |
| Inscription → bascule automatique vers la connexion si le numéro existe | auth_page.dart:205-208 | Un code envoyé à un numéro connu ouvre directement la session | CONFORME | oui | — |
| CGU : liens dans le formulaire d'inscription, page à lire et cocher avant l'inscription, consultation en lecture seule | auth_page.dart:448, 57-59 ; terms_page.dart:74, 81-85 ; app_router.dart:381 (`/terms/view`) | Aucun écran. Les textes `register.terms*` existent (fr/auth.json:103-106) mais ne sont utilisés que dans plan-screen.tsx:648 | **ABSENT** | non | Aucune acceptation des conditions à l'inscription. Point juridique. Les liens visent brefpoint.app. |
| Mot de passe oublié (code par SMS / WhatsApp, puis nouveau mot de passe) | auth_page.dart:553 ; forgot_password_page.dart:118 ; reset_password_page.dart:62, 154 | Aucun écran | DIFFÉRENT | non | Acceptable pour le propriétaire, qui se connecte par code. Un membre RN qui oublie son mot de passe dépend du propriétaire (fiche membre → réinitialiser). `/auth/forgot-password` et `/auth/reset-password` existent mais ne sont pas appelés. |
| Données non envoyées de l'ancien compte : question avant de les perdre | previous_account_data_dialog.dart:7-40 ; auth_page.dart:127-135 | clear-local-data.ts:55-69 efface sans demander | PARTIEL | non | La déconnexion avertit bien (logout-confirm-dialog.tsx:21-53). Une session expirée suivie d'une connexion d'un autre compte efface les actions en attente sans prévenir. |
| Choix de l'entreprise (plusieurs entreprises) : nom, rôle, retour | tenant_picker_page.dart:213, 245, 116-118 | company-picker-host.tsx:27-52 (nom, rôle, Annuler) ; `/auth/select-tenant` existe ; le nom de l'entreprise est corrigé côté serveur | CONFORME | non | Test : compte membre de 2 entreprises → connexion → la fenêtre de choix s'ouvre. |
| Changement de mot de passe imposé (+ « Se déconnecter ») | password_change_page.dart:156-248 | login/change-password (change-password-screen.tsx) : ancien, nouveau ≥ 8, confirmation ; `/auth/change-password` existe | PARTIEL | oui (n° 35) | Pas de bouton « Se déconnecter » sur l'écran. Sans changement, la session n'est pas ouverte. |
| Changement volontaire de son mot de passe (Mon compte) | account_page.dart:118-119 ; user_password_change_page.dart:67-215 | Aucun | **ABSENT** | non | Un membre RN ne peut pas changer lui-même son mot de passe. |

## 2. Démarrage et liens profonds (startup_route, app_router, deep_link_*)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé appareil | Remarque / test à faire |
|---|---|---|---|---|---|
| Démarrage : session gardée hors ligne, jeton renouvelé, retour à la connexion seulement si le serveur refuse | startup_route.dart:58-115 | app/_layout.tsx:102-117 ; api.interceptor.ts:72 | CONFORME | oui (sans réseau : 3,9 s et 5,7 s, B4 levé) | — |
| Reprise de l'assistant d'installation non terminé | startup_route.dart:103-109 | (tabs)/_layout.tsx:42, 55 ; use-company-profile.ts:31-43 | CONFORME | oui (F00) | — |
| Accueil selon le rôle (propriétaire → tableau de bord, employé → caisse) | startup_route.dart:18 | Accueil pour tous ; « + » ouvre la caisse ou les documents | DIFFÉRENT (acceptable) | oui | — |
| Garde des écrans réservés au propriétaire | app_router.dart:167-182, 212-224 | Entrées masquées : (tabs)/_layout.tsx:37-40, use-module-items.ts ; refus du serveur | CONFORME | partiel | Un vendeur n'a ni onglet Modules ni onglet Produits. |
| Lien inconnu → retour au démarrage | app_router.dart:281-288 | ErrorBoundary ((tabs)/_layout.tsx:14) | CONFORME | non | — |
| Toucher une notification push ouvre l'écran visé (`deepLink` : produit, stock, historique des ventes, rapports, ventes en attente, transferts) | deep_link_navigation.dart (normalizeDeepLink, openDeepLink) ; deep_link_entity_page.dart ; main_shell.dart:87-117 | app/_layout.tsx:236-246 : ne gère que `whatsapp_message` et `invoice`/`payment` avec `invoiceId`, sinon `/notification` | **CASSÉ** | non (push coupé en test) | Le serveur envoie `deepLink` (FcmNotificationAdapter.java:120, ExpoPushAdapter.java:183) et jamais `invoiceId`. Tout toucher ouvre la liste, et la liste n'ouvre rien (§ 9). |
| Rafraîchir produits / stock à réception d'une notification d'employé | main_shell.dart:101-108 | _layout.tsx : `requestSync({force:true})` à chaque notification reçue | CONFORME | non | — |
| Avis « journée clôturée automatiquement » | main_shell.dart:126-160 | Rien trouvé | ABSENT | non | À trancher avec le lot rapports et clôture. |

## 3. Menus et navigation selon le rôle (main_shell, settings_page)

| Entrée V1 | Rôle V1 | Preuve V1 | Équivalent RN | Verdict | Testé | Remarque / test |
|---|---|---|---|---|---|---|
| Onglets : Dashboard / Caisse / Catalogue / Rapports / Plus | propriétaire | main_shell.dart:314-361 | Accueil / Ventes / « + » / Produits / Modules ((tabs)/_layout.tsx:77-142) ; profil par l'avatar (dashboard-screen.tsx:200) | DIFFÉRENT | oui | — |
| Onglets : Caisse / Catalogue (lecture) / Rapports (jour + brouillons) / Plus | employé | main_shell.dart:25-26, 50 | Vendeur : Accueil / Ventes / « + » seulement (Produits masqué ligne 127, Modules ligne 137) | **PARTIEL** | oui (vendeur sur le Redmi) | Le vendeur perd le catalogue en lecture et l'onglet Rapports. |
| Plus › Mon compte | tous | settings_page.dart:125-127 | Profil › Modifier le profil, infos (profile-screen.tsx:147-167) | CONFORME | oui (« nom modifiable ») | — |
| Plus › Boutique active (choix d'une boutique ou toutes) | propriétaire | settings_page.dart:147, 688-815 | Filtre de boutique sur l'accueil (dashboard-screen.tsx:274, 417) et sélecteur dans chaque écran | DIFFÉRENT | oui (M8, M10) | — |
| Mes boutiques | propriétaire | :142-144 | Modules › Boutiques (use-module-items.ts:141-148) | CONFORME (voir § 7) | oui | — |
| Stock multi-boutiques / Inventaire / Transferts | propriétaire et **employé** (:260-286) | :152-162, 250, 268-286 | Modules › Stock / Inventaires / Transferts, propriétaire ou admin seulement (use-module-items.ts:63-89) | **PARTIEL** | oui (propriétaire) | L'employé V1, devenu vendeur, n'a plus aucun accès. |
| Abonnement | propriétaire | :168-170 | Profil › Mon plan (profile-screen.tsx:150-151) | voir § 8 | oui | — |
| Équipe | propriétaire | :176-178 | Modules › Équipe (use-module-items.ts:151-158) | CONFORME | oui | — |
| Historique des actions / Exports / Mouvements de stock / Synchronisation / Clients / Fournisseurs / Catégories | propriétaire | :184-244 | Audit, Exports, Historique de synchro, Clients, Fournisseurs : présents. Catégories : pas d'écran de gestion | hors lot | — | Hors lot A. Signalé pour la vue d'ensemble. |
| Notifications (« Bientôt ») | tous | :301-305 | Modules › Alertes (propriétaire, plan Team) | sans objet | — | — |
| Rapports & WhatsApp | propriétaire | :311-313 | Modules › Rapports avancés (use-module-items.ts:205-214, plan Team) | voir § 10 | partiel | — |
| Aide & Tutoriels / Signaler un problème | tous | :328-345 | Profil › Aide (profile-screen.tsx:180) → Donner un avis (help-screen.tsx:25) | CONFORME (2 niveaux) | non | Test : Profil → Aide → Donner un avis. |
| Déconnexion avec avertissement s'il reste des opérations | tous | :380-474 | profile-screen.tsx:186 et logout-confirm-dialog.tsx | PARTIEL | non | RN n'essaie pas d'envoyer la file avant de partir et ne supprime pas le jeton push de l'appareil : aucun `DELETE /devices/token`, alors que V1 le révoque (sign_out_service.dart). |
| Bandeaux : compte suspendu ou essai expiré, suppression en cours, blocage après 7 jours sans synchro, échec critique de synchro | tous | shell_banners.dart:29-44, 65-72 | Bandeau hors ligne et fraîcheur des données (components/sync/freshness-notice.tsx). Bandeau de délai de grâce jamais affiché (subscription.mapper.ts : `gracePeriodEndsAt: null`). `tenantStatus` du profil jamais lu | **PARTIEL** | non | Aucun avis de suspension, d'essai expiré ni de suppression en cours. Pas de blocage à 7 jours : à confirmer si c'est voulu. |
| Pastilles stock bas / brouillons sur l'onglet Catalogue | propriétaire | main_shell.dart:325-345 | Aucune pastille d'onglet | ABSENT (mineur) | — | — |

## 4. Accueil et installation (onboarding, sector_selection, shop_name, terms)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé | Remarque / test |
|---|---|---|---|---|---|
| Écrans de présentation puis « Commencer » → CGU | onboarding_page.dart:49, 157 | auth/onboarding (onboarding-screen.tsx:73 Continuer, :81 « Retrouver un reçu », choix de la langue) | DIFFÉRENT | oui | — |
| Choix du secteur d'activité (catégories créées selon le secteur) | sector_selection_page.dart:79-99 ; sector_type.dart ; `POST /onboarding/complete` | Étape « Votre entreprise » : catégorie (company-profile-form.tsx:110-123), `PATCH /account/company/profile`, puis `POST /account/company/setup/complete`, qui lance l'installation du secteur (CompanyController.java:98-108) | CONFORME | oui (F00) | — |
| Nom du commerce, Terminer | shop_name_page.dart:185-211 | register-store-screen.tsx:44-50 (nom, adresse, téléphone) ; `PATCH /stores/{id}` existe | CONFORME (plus riche) | oui | — |
| Acceptation des CGU | terms_page.dart | absent | **ABSENT** | non | Voir § 1. |

## 5. Mon compte et suppression (settings/account, account/delete_account)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé | Remarque / test |
|---|---|---|---|---|---|
| Afficher prénom, nom, téléphone, rôle, boutique assignée | account_page.dart:85-103 | profile-screen.tsx:91-99, 147-167 | CONFORME | oui | « Dernière connexion » et « Actif depuis » affichent la date du jour (auth.mapper.ts:52, 64-65) : information fausse. |
| Modifier son nom | non (lecture seule en V1) | profile-edit-screen.tsx → `PUT /auth/profile` (existe) | Nouveau | oui | — |
| Supprimer mon compte, réservé au propriétaire : avertissement, puis saisie de son numéro | account_page.dart:126-135 ; delete_account_warning_dialog.dart:34-77 ; delete_account_page.dart:152-223 | Paramètres › Zone danger (settings-screen.tsx:146-210) → `POST /account/delete` (existe) | PARTIEL | non | Une seule confirmation, sans saisie du numéro. Bouton visible pour **tous les rôles** (aucune condition sur le propriétaire) : un vendeur reçoit un refus. RN déconnecte aussitôt (use-request-account-deletion.ts:13-15), alors que V1 garde la session en lecture seule. |
| Bandeau « suppression prévue le… » + « Annuler la suppression » | deletion_pending_banner.dart:83-101 ; `POST /account/delete/cancel` | AccountRecoveryWatcher (account-recovery-watcher.tsx) lit `scheduledDeletionAt`, que `toUser` met **toujours à null** (auth.mapper.ts:69) | **CASSÉ** | non | La fenêtre de récupération ne s'affiche jamais : aucun moyen d'annuler la suppression dans RN. `tenantStatus` (DELETION_PENDING) est pourtant dans `/auth/profile`. Test : Profil → Paramètres → Supprimer mon compte, se reconnecter, aucune fenêtre n'apparaît. |

## 6. Abonnement (settings/subscription, plan_limit_bottom_sheet, suspension_banner)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé | Remarque / test |
|---|---|---|---|---|---|
| Plan actif et date d'expiration | subscription_page.dart:177-212 | plan/current (current-plan-screen.tsx:149-269) ; `GET /subscription/me` | CONFORME | oui (M21) | « Date de début : Non définie », car le serveur ne la donne pas. |
| Consommation : boutiques, produits, employés (x / max) | subscription_page.dart:92-108, 267-321 | Aucune jauge (seulement les quotas SMS et e-mail par plan, :31-33) | **PARTIEL** | oui (absence constatée) | — |
| Passer au plan supérieur (« bientôt » en V1) | :386 | Offres, périodes, paiement Orange / MTN (plan/index, plan/payment) | Mieux | oui (B6 levé : numéro demandé, paiement validé) | — |
| Feuille « limite du forfait atteinte » | plan_limit_bottom_sheet.dart:68-157 | lib/plan-limit-alert.ts:17-24 ; shop-form-screen.tsx:155, 175 ; user-form-screen.tsx:139-146 | CONFORME | non | Test : plan gratuit, créer une 2ᵉ boutique. |
| Bandeau compte suspendu / essai expiré | suspension_banner.dart | absent | ABSENT | non | — |

## 7. Boutiques (stores_list + store_card + store_form_bottom_sheet)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé | Remarque / test |
|---|---|---|---|---|---|
| Liste, accès | stores_list_page.dart ; settings :142 | Modules › Boutiques → (tabs)/shops (shops-screen.tsx) | CONFORME | oui | — |
| Créer une boutique : nom, adresse, téléphone | store_form_bottom_sheet.dart:126, 183, 193 | shop/add (shop-form-screen.tsx:255-289, logo et description en plus) ; opération de synchro `CREATE_STORE` | CONFORME | oui (M1, M2, hors ligne aussi) | — |
| Type Boutique / **Entrepôt** | store_form_bottom_sheet.dart:248-259 ; store_type.dart | Toujours `type: 'STORE'` (shop.api.ts:62) ; `toShop` ignore le type (shop.mapper.ts:42-61) | **PARTIEL** | non | Impossible de créer un entrepôt. Les entrepôts V1 existants apparaissent comme des boutiques ordinaires : à vérifier dans la caisse. |
| Modifier | store_card.dart:150 | shop-detail-screen.tsx:169 → `/shop/{id}/edit` ; `PATCH /stores/{id}` | CONFORME | oui (M11 concerne le produit, la boutique n'est pas explicitement testée) | Test : Boutiques → fiche → Modifier. |
| Désactiver avec confirmation explicative ; réactiver | stores_list_page.dart:150-193 ; store_card.dart:141-170 | shop-detail-screen.tsx:171-174 (Activer / Désactiver) ; `PATCH …/deactivate` et `…/reactivate` existent | CONFORME | non | Test : fiche boutique → Désactiver → Activer. |
| « Supprimer » | — | shop-detail-screen.tsx:176 : désactive au serveur mais **masque** localement (use-delete-shop.ts) | DIFFÉRENT (gênant) | non | Deux actions pour un même effet serveur. La boutique « supprimée » peut revenir à la synchro suivante : à vérifier. |
| Afficher les boutiques désactivées (interrupteur) | stores_list_page.dart:271-276 | Toutes listées, avec pastille Active / Inactive (shops-screen.tsx:75) | CONFORME | — | — |
| Bouton « Synchroniser » avec retour | stores_list_page.dart:55-77, 259 | Tirer pour actualiser / synchro automatique | DIFFÉRENT | — | — |
| Recherche | — | shops-screen.tsx:167-176 | Nouveau | — | — |

## 8. Équipe (team_page, create_employee, edit_employee, employee_card, feuilles)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé | Remarque / test |
|---|---|---|---|---|---|
| Liste des employés, « Ajouter » | team_page.dart:144 | Modules › Équipe (users-screen.tsx), filtres par rôle et recherche (:50-84) | CONFORME (mieux) | oui (n° 33) | — |
| Créer : prénom, nom, **téléphone**, boutique ; mot de passe temporaire affiché | create_employee_page.dart:146-220 ; temp_password_bottom_sheet.dart | user/add : prénom, nom, **identifiant**, mot de passe choisi, boutique, rôle Admin / Vendeur / Lecteur, rôle personnalisé (user-form-screen.tsx:113-120, 235-265) ; `POST /employees` et `…/password` existent | DIFFÉRENT | oui (n° 33, 34, M35) | Plus de téléphone ni de mot de passe temporaire. |
| Invitation WhatsApp (lien de l'app + numéro, jamais le mot de passe) ; relance depuis l'activité | employee_card.dart:297-302 ; employee_activity_sheet.dart:192 | absent | ABSENT | non | — |
| Modifier nom, téléphone, boutique, rôle (y compris **Propriétaire**) | edit_employee_page.dart:54, 113-156 | user/[id]/edit (user-form-screen.tsx:154-158) → `PATCH /employees/{id}` et `/role` ; pas de champ téléphone ; pas de rôle propriétaire (user.api.ts:131) | **CASSÉ pour un employé V1** | non | Le champ « Identifiant » est obligatoire pour tout rôle (:23, 102-103) et vide pour un employé V1 : impossible d'enregistrer sans en saisir un. L'identifiant saisi n'est jamais envoyé (user.api.ts:127, « ne se modifie pas »), et le backend n'a pas de route pour le poser. Test : compte V1 migré → Équipe → employé → Modifier → Enregistrer. |
| Réassigner la boutique | employee_card.dart:338-342, 422-466 | Champ boutique du formulaire → `PATCH /employees/{id}` avec `storeId` (accepté, EmployeeController.java:242) | CONFORME (sauf blocage ci-dessus) | oui (M35, à la création) | — |
| Régénérer un mot de passe temporaire / Définir le mot de passe | employee_card.dart:351-367 | Fiche membre → Réinitialiser le mot de passe, le propriétaire le choisit (user-detail-screen.tsx:189, 299-357) → `POST /employees/{id}/password` | DIFFÉRENT (acceptable) | non | Test : Équipe → membre → Réinitialiser le mot de passe. |
| Désactiver / réactiver l'accès avec confirmation ; limite du forfait à la réactivation | employee_card.dart:379-395, 475-537, 179-186 | user-detail-screen.tsx:67, 186 ; `PATCH …/deactivate` et `…/reactivate` | CONFORME | oui (désactivation : session fermée en moins de 15 s) | Réactivation au-delà de la limite du forfait : à tester. |
| Activité par employé : dernière connexion, ventes du jour et de la semaine, session | employee_card.dart:274-276 ; employee_activity_sheet.dart:155-177 | `/employees/activity` lu seulement pour des totaux d'équipe (user.api.ts:159-166) ; la fiche montre ventes et factures (:274-289) | PARTIEL | non | Plus de dernière connexion par membre. |
| Hors ligne : actions refusées « connexion requise » | employee_action_requires_connection_exception.dart | Mises en file (use-update-user.ts) | Mieux | oui (M35) | — |

## 9. Notifications (notifications_page, notification_card, bell, announcements)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé | Remarque / test |
|---|---|---|---|---|---|
| Cloche avec compteur de non lues | notification_bell_widget.dart:31-40 | dashboard-screen.tsx:249-261 (compté sur la liste) | CONFORME | oui (M18) | — |
| Liste, « Tout marquer comme lu » | notifications_page.dart:61-62 | notification-screen.tsx:179 ; `GET /notifications`, `PATCH /notifications/read-all` | CONFORME | oui (M18) | — |
| Toucher une notification ouvre l'écran lié (`openDeepLink`) | notifications_page.dart:117-125 | `handlePress` : marque lue puis « TODO » (notification-screen.tsx:67-70) | **CASSÉ** | non | Test : Accueil → cloche → toucher une notification de transfert : rien ne s'ouvre. |
| Supprimer | (purge automatique, :28) | Poubelle (:127) qui marque seulement comme lue (notification.api.ts:32) | DIFFÉRENT (trompeur) | non | La notification revient au rafraîchissement suivant. |
| Annonces Keevo (diffusées par l'équipe Keevo) | remote_announcement_datasource.dart:33 (`/notifications/announcements`) | Jamais appelé | **ABSENT** | non | Une annonce n'est vue que si le push arrive, puis elle est perdue. |
| Enregistrement du jeton push | remote_device_token_datasource.dart:13 | _layout.tsx (savePushToken) → `POST /devices/token` (jeton Expo accepté par le backend) | CONFORME | non (push coupé) | Pas de suppression du jeton à la déconnexion (§ 3). |

## 10. Rapports et WhatsApp (settings/report_preferences, identity)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé | Remarque / test |
|---|---|---|---|---|---|
| Rapport de fin de journée : activer, heure, canal | report_preferences_page.dart:124-147 | advanced-reports-screen.tsx:46-56 ; `PUT /tenant/report-preferences` (heure convertie au bon format, use-tenant-prefs.ts:28) | PARTIEL | oui (écran ouvert ; changement : R3 corrigé dans l'app, à revérifier) | Heure choisie dans une liste fixe au lieu d'un sélecteur libre. |
| Rapport hebdo : jour, heure, canal ; **aperçu** | :159-217 (`/reports/trigger-weekly`) | :59-70 ; pas d'aperçu | PARTIEL | non | — |
| Rapport d'inventaire | :239-255 | :74-80 | CONFORME | non | — |
| Alertes de stock bas, tendances de ventes | :267-292 | Modules › Alertes (alerts-screen.tsx:34-52) | CONFORME | oui (s'ouvre) | — |
| Envoyer un rapport test | :336-348 | :97-101 → `POST /tenant/report-test` | CONFORME | non | Test : Modules → Rapports avancés → Envoyer un rapport test. |
| Accès pour tout propriétaire | settings :307-313 (aucune condition de plan) | Module verrouillé sous le plan Team (module-plans.ts:12-14) ; modification réservée à « report-config » (Pro), :24, 44 | **DIFFÉRENT (gênant)** | non | Un propriétaire V1 au plan gratuit perd la configuration des rapports WhatsApp et des alertes : à confirmer selon le plan donné aux comptes migrés. |
| Classement visible des employés | — | :88-92 | Nouveau | — | — |

## 11. Aide et avis (help_tutorials, feedback_form)

| Fonction V1 | Preuve V1 | Équivalent RN | Verdict | Testé | Remarque / test |
|---|---|---|---|---|---|
| Tutoriels vidéo (« bientôt » si pas de lien), ouverture externe | help_tutorials_page.dart:61-74, 117, 135 | help-screen.tsx:17-38 ; `GET /help/tutorials` | CONFORME | non | Test : Profil → Aide. Pas de message propre hors ligne. |
| Avis : type (Suggestion / Problème / J'adore Keevo), 10 à 500 caractères | feedback_form_page.dart:123-128, 226 ; feedback_type.dart | feedback-screen.tsx:13, 21, 36-39 (Bug / Suggestion / Autre) ; `POST /feedback` | CONFORME | non | Test : Profil → Aide → Donner un avis. |
| Capture d'écran jointe | :64-111 | absent | ABSENT (sans effet) | — | Le backend refonte n'enregistre aucune capture (aucune occurrence de « screenshot »). |
| Écran d'origine joint ; envoi hors ligne (file de synchro) | :26, 136 ; :23 | Pas de `screenContext`, envoi direct seulement | PARTIEL | non | Hors ligne, l'avis échoue avec un message d'erreur. |

## 12. Paramètres RN sans équivalent V1 (pour mémoire)

Thème, 7 langues, code PIN / biométrie (security), mode de vente par défaut, comptes liés Google / Apple, historique de synchro, « Noter l'app ».

**Écrans « coming-soon ».** `app/coming-soon/[key].tsx` n'est la cible d'aucune navigation ; c'est une page orpheline. Aucune fonction du lot n'y tombe : Rapports avancés, Alertes et Exports pointent vers leurs vrais écrans.

---

## Écarts à retenir

### Bloquant pour un utilisateur V1 qui passe sur la nouvelle app

1. **Les employés V1 ne peuvent pas se connecter comme avant.** L'écran « commercial » n'accepte qu'un identifiant, que les employés V1 n'ont pas (migration V41 sans remplissage). Le propriétaire ne peut pas leur en donner un : le formulaire l'exige, mais ne l'envoie jamais. Seul contournement : « Je suis propriétaire » → code par SMS ou WhatsApp sur leur numéro.
2. **Le propriétaire ne peut plus modifier la fiche d'un employé V1.** L'identifiant obligatoire et vide bloque l'enregistrement, et le téléphone n'est plus modifiable.
3. **L'employé V1 (vendeur RN) perd stock multi-boutiques, inventaire, transferts, catalogue et rapports du jour.** Il n'a ni onglet Modules ni onglet Produits.
4. **La suppression de compte ne peut pas être annulée dans l'app.** La fenêtre de récupération ne s'affiche jamais (`scheduledDeletionAt` toujours vide) et la demande déconnecte immédiatement. En V1, le propriétaire restait connecté avec un bouton « Annuler la suppression ».
5. **Pas d'acceptation des CGU à l'inscription,** ni de page pour les consulter (point juridique).

### Gênant

6. Toucher une notification, dans la liste comme en push, n'ouvre jamais l'écran concerné : le `deepLink` du serveur est ignoré et l'action est marquée « TODO ».
7. Les annonces Keevo ne sont plus listées (`/notifications/announcements` n'est pas appelé).
8. Rapports & WhatsApp et alertes de stock sont réservés au plan Team, la modification au plan Pro : un propriétaire V1 au plan gratuit perd ces réglages. À confirmer avec le plan donné aux comptes migrés.
9. Pas de bandeau compte suspendu / essai expiré / suppression en cours, et pas de blocage après 7 jours sans synchro : `tenantStatus` est disponible mais jamais lu.
10. Entrepôts : impossible d'en créer, et les entrepôts V1 sont traités comme des boutiques.
11. Pas de changement volontaire de mot de passe pour un membre ; le changement imposé n'a pas de bouton de déconnexion.
12. Le jeton push n'est pas supprimé à la déconnexion : l'appareil continue de recevoir les notifications du compte.
13. « Supprimer mon compte » est visible pour les vendeurs, qui reçoivent un refus du serveur.
14. Plus d'invitation WhatsApp ni d'activité par employé (dernière connexion, ventes du jour et de la semaine).

### Mineur

15. Abonnement : plus de jauges boutiques / produits / employés utilisés sur le maximum.
16. Notification « supprimée » qui revient au rafraîchissement suivant. « Dernière connexion » et « Actif depuis » du profil affichent la date du jour.
17. Avis : pas d'envoi hors ligne ni d'écran d'origine joint. Heure du rapport limitée à quelques choix, pas d'aperçu du rapport hebdo.
18. « Supprimer » et « Désactiver » une boutique font la même chose côté serveur. Effacement silencieux des actions en attente lors d'un changement de compte après une session expirée.
19. Pas de pastilles stock bas / brouillons ; pas d'avis de clôture automatique.

### Non testé sur appareil et prioritaire

Connexion d'un employé V1 migré, choix entre plusieurs entreprises, suppression puis reconnexion, toucher une notification, aide et avis, désactivation / réactivation de boutique, réinitialisation du mot de passe d'un membre, rapport test.
