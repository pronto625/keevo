# Test sur appareil — fin de l'épic 19 (2026-11-06)

**Appareil :** Redmi (Android, adb USB), app de test Flutter `com.example.keevo` 2.0.22 (build debug, se comporte comme un client V1 : aucun en-tête `X-App-Version`). **Backend :** local (`keevo_dev`), WhatsApp noop, FCM et Expo coupés, lancé depuis une copie figée des classes (commit `8e40cd5a` + 20.1 en cours). Comptes de test créés pour l'occasion (owner existant de dev, un vendeur et une lectrice) — aucune donnée personnelle ici.

| # | Scénario | Résultat |
|---|---|---|
| 1 | Lectrice (VIEWER) se connecte depuis l'app V1 | ✅ refusée : l'app affiche « Votre accès a été désactivé. Contactez votre employeur. » (19.10 : comptes gérant/lecteur masqués aux clients sans `X-App-Version`) |
| 2 | Vendeur : première connexion, mot de passe temporaire → nouveau mot de passe | ✅ écran « Créer votre mot de passe », puis Caisse « En ligne » |
| 3 | Vente en ligne (2 articles suivis en stock, espèces) | ✅ vente sur le serveur, stock 10 → 8, **aucune facture créée** (facturation désactivée par l'owner, 19.13) |
| 4 | Vente hors ligne (lien coupé) puis retour du réseau | ✅ vente gardée sur le téléphone (« Synchronisation… »), remontée au retour du lien ; serveur : 2 ventes, stock 7 |
| 5 | Owner : tableau de bord | ✅ CA du jour 15 000 XAF = les 2 ventes (dont celle faite hors ligne) |
| 6 | Owner : Plus › Mes modules | ✅ « Facturation — Désactivé » ; les autres modules payants en essai (plan d'essai) |
| 7 | Produit sans suivi de stock (17.12) dans la Caisse de l'app V1 | ⚠️ affiché « Rupture » et non vendable : l'app Flutter (V1 et client de test) ne connaît pas `trackStock` — le serveur l'accepterait. Sans effet tant qu'aucun produit n'est passé « sans suivi » ; à prendre en compte pour les entreprises mixtes (owner sur la nouvelle app, vendeurs sur la V1) → routé vers 16-7 (retrait de la V1). |
| 8 | Déconnexion depuis Plus | ⚠️ écran noir après « Déconnecter » — bogue V1 déjà signalé (`settings_page` `_withProgress`), décision de correctif V1 en attente côté utilisateur ; relancer l'app suffit. |

**Non couvert sur appareil** (pas d'écran dans l'app Flutter, vérifié par API/curl dans chaque story) : connexion par OTP / Google / Apple / identifiant (19.2–19.4), multi-entreprises (19.6), boutiques étendues et logo (19.12), interrupteurs « Mes modules » de la 19.13 (l'app de test propose encore « Demander l'activation »), vérification de version (19.10, l'app de test n'appelle pas `app-version/check`).
