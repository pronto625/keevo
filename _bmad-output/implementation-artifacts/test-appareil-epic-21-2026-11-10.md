# Test sur appareil — epic 21 (2026-11-10)

**But :** vérifier que les changements serveur de l'epic 21 (V60–V67 : catalogue d'offres et prix, paiement Mobile Money, abonnements App Store, packs de crédits et de SMS, outils et assistant IA, accès MCP ; nouveaux modules `ai-assistant` et `mcp-access`, désactivés par l'owner pour les entreprises existantes) ne cassent pas l'app Flutter installée (client compatible V1).

**Appareil :** Redmi (adb USB `JRPJDYVK69GYY9KZ`), app de test `com.example.keevo` 2.0.22 (build debug déjà installé, non réinstallé ; aucun en-tête `X-App-Version`). Le téléphone joint le backend local par `adb reverse tcp:4500 tcp:4500` ; « hors ligne » = suppression de ce lien (`adb reverse --remove`).
**Backend :** local, `refonte/modulaire` @ `a8e7352e`, profil dev, base `keevo_dev` (V67 appliquée au démarrage). WhatsApp noop, FCM et Expo coupés, SMS/e-mail sans fournisseur, paiements en **sandbox** (Mobile Money simulé, RevenueCat simulé), IA **désactivée** (aucune clé Anthropic). Arrêté avec `fuser -k 4500/tcp`.
**Comptes :** owner KV-EW02A2 (Boutique Test) ; vendeur existant de cette entreprise (rôle EMPLOYEE, compte de test de l'epic 19 : mot de passe temporaire régénéré par l'owner puis changé dans l'app). Entreprise `kv_11b5zm` non touchée (vérifié).
**Captures :** `/tmp/claude-1000/-home-toor-Project-FreeLance-AI-Keevo/1906a8d9-562f-4594-b59f-133c7ec5c31c/scratchpad/device-21/sNN-*.png` (dossier de travail, non versionné).

| # | Scénario | Attendu | Observé | Capture | Résultat |
|---|---|---|---|---|---|
| 1a | Owner : ouverture de l'app, tableau de bord | Accueil sans erreur | « Bon après-midi Patron », CA du mois, CA du jour | s01 | ✅ |
| 1b | Plus › Abonnement | Plan exact, sans erreur | « Essai Premium — Actif — Expire le 01/05/2027 », boutiques 1/∞, produits 5/∞, employés 2/∞ | s03 | ✅ |
| 1c | Plus › Mes modules | Liste en français, nouveaux modules présentés proprement | Inclus (8), payants en essai jusqu'au 01 mai 2027, « Facturation — Désactivé » ; section DISPONIBLES : « Accès IA externe (MCP) — Non activé », « Assistant IA — Non activé », « Dépenses », « Finance ». Aucun code technique, pas de plantage | s04, s05 | ✅ |
| 2a | Vente en ligne (1 article suivi en stock, espèces) | Vente sur le serveur, stock décrémenté | Caisse « En ligne », vente 5 000 XAF en base (COMPLETED), stock 7 → 6 | s07–s10 | ✅ |
| 2b | Vente hors ligne puis retour du lien | Vente gardée sur le téléphone puis remontée | « Synchronisation… » pendant la coupure, aucune vente en base ; au retour du lien, la vente remonte (COMPLETED), Caisse « En ligne », stock 5 | s11–s13 | ✅ |
| 3a | Vendeur : connexion avec mot de passe temporaire | Écran « Créer votre mot de passe » puis Caisse | Conforme ; 4 onglets (Caisse, Catalogue, Rapports, Plus), pas de tableau de bord | s26–s29 | ✅ |
| 3b | Vendeur : vente | Vente rattachée au vendeur | Vente 5 000 XAF en base, `employee_id` = vendeur, stock 4 | s30 | ✅ |
| 3c | Vendeur : périmètre | Toujours restreint | Plus : Compte, Consultation (stock, inventaire, transferts), Support — rien de la gestion. API : `/employees` 403, `/payments/checkout` 403 « Seul le propriétaire peut payer l'abonnement », `/mcp/tokens` 403, `/ai/conversations` 403 « Ce module est désactivé… » | s31 | ✅ |
| 4a | Paiement Mobile Money sandbox (Pro mensuel, 3 500 XAF) pendant que l'app est ouverte | Plan Pro reflété après rafraîchissement / relance, sans erreur | Serveur : paiement CONFIRMED « Votre plan Pro est actif jusqu'au 09/12/2026 ». App ouverte : l'écran Abonnement garde « Essai Premium » (donnée gardée en mémoire par l'app pour la session — comportement V1 existant). Après relance : « Premium — Actif — Expire le 09/12/2026 », boutiques 1/3 (le serveur renvoie `planType` = PREMIUM pour Pro : compatibilité V1 respectée). Mes modules : modules du plan Pro « Actif jusqu'au 09 déc. 2026 », les autres « Désactivé », IA / MCP / Facturation / Finance / Dépenses « Désactivé » (interrupteurs owner respectés) | s14–s18 | ✅ |
| 4b | Expiration du plan (événements RevenueCat simulés : achat puis EXPIRATION) | Retour au plan gratuit reflété sans écran d'erreur | Serveur : Pro → Gratuit (cause EXPIRY). Après relance : bandeau « Vous êtes sur le plan Gratuit. Passez au plan Premium pour continuer. », Abonnement « Gratuit — Actif », produits 5/500, employés 2/1, carte « Passez au Premium » ; Mes modules sans plantage | s19–s21 | ✅ |
| 4c | Restauration du plan d'origine | État identique à avant le test | Voir « Données restaurées » ; après relance : plus de bandeau, Mes modules identique à 1c | s22, s34 | ✅ |
| 5a | Déconnexion (owner, puis vendeur) | — (bogue V1 connu) | ⚠️ **Écran noir après « Déconnecter », toujours présent** (les deux fois). Relancer l'app affiche l'écran de connexion. Bogue V1 de l'epic 19 (`settings_page` `_withProgress`), non corrigé ici comme demandé | s24, s32 | ⚠️ connu |
| 5b | Reconnexion owner | Accueil | Connexion directe (une seule entreprise), CA du jour 15 000 XAF = les 3 ventes du test | s25, s33 | ✅ |

## Régressions dues à l'epic 21

**Aucune.** L'app V1 lit `/subscription/me` sans erreur avec les nouveaux champs (offres, quotas, crédits) ; les plans payants du nouveau catalogue sont renvoyés sous l'ancien `planType` (PREMIUM) que l'app connaît ; les nouveaux modules apparaissent avec des libellés français et restent désactivés pour l'entreprise existante ; vente en ligne, hors ligne et synchronisation inchangées ; le périmètre du vendeur est inchangé. Aucune ligne `ERROR` dans le journal du backend pendant le test. Aucun correctif, aucun élément routé.

**Observations (sans action) :**
- L'écran Abonnement de l'app V1 ne se recharge pas quand le plan change pendant la session : il faut relancer l'app. Comportement existant de l'app (fournisseur gardé en mémoire), indépendant de l'epic 21.
- Cas combiné provoqué par le test : un Pro payé en Mobile Money (jusqu'au 09/12) puis un achat App Store du même plan (jusqu'au 10/12) puis une EXPIRATION App Store → retour au plan gratuit, la période Mobile Money étant couverte par la période Apple. C'est la règle écrite dans la spec 21.2 (« un paiement Mobile Money *postérieur* n'est jamais coupé ») ; cas peu réaliste (Android paie en Mobile Money, iPhone par l'App Store).

## Données restaurées

| Élément (entreprise KV-EW02A2) | Avant | Pendant | Après |
|---|---|---|---|
| Plan (`kv_ew02a2.subscriptions`, `public.tenants`) | Essai Premium, expire 01/05/2027, limites illimitées | Pro (09/12 puis 10/12/2026) → Gratuit | Essai Premium, expire 01/05/2027, limites illimitées (valeurs d'origine, `updated_at` compris) |
| `public.tenant_modules` | 16 lignes | 20 lignes (Pro puis Gratuit) | les 16 lignes d'origine (recopiées de la sauvegarde) |
| `tenant_plan_events` | 1 (REGISTRATION) | 4 | 1 |
| `payment_requests` / `tenant_payments` / `external_subscriptions` / `revenuecat_webhook_event` | 0 / 0 / 0 / 0 | 1 / 2 / 1 / 2 | 0 / 0 / 0 / 0 |
| Interrupteurs owner (`tenant_module_activation`) | inchangés | inchangés | inchangés |
| Tables du schéma `kv_ew02a2` | — | — | identiques (comparaison du schéma) |

Gardé volontairement : les 3 ventes de test (2 owner dont 1 hors ligne, 1 vendeur ; stock « Sandales test 19 » 7 → 4) et les notifications internes liées aux changements de plan ; mot de passe du vendeur de test changé dans l'app. Sauvegardes `pg_dump` (schémas `public` et `kv_ew02a2`) prises avant le test dans le dossier de travail.

**Téléphone laissé :** app Keevo fermée, Wi-Fi actif, mode avion coupé, lien `adb reverse` retiré. Aucun partage, WhatsApp ou lien wa.me ouvert (les boutons « Demander l'activation » n'ont pas été touchés).
