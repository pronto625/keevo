# Epic List

## Epic 1: Foundation, Infrastructure & Authentication
Simon peut s'inscrire avec son numéro WhatsApp, obtenir son tenant isolé automatiquement, se connecter sur mobile et desktop, et voir l'état de sa connexion/sync. L'architecture monorepo Flutter + Spring Boot est initialisée avec les fondations hexagonales, multi-tenant, RBAC, subscription model, sécurité et audit.
**FRs couverts :** FR1–FR20, FR72, FR75, FR84–FR86, FR93

## Epic 2: Catalogue Produits & Base Fournisseurs/Clients
Simon peut construire et gérer son catalogue complet : produits, variantes, prix d'achat/vente/transport, marges automatiques, catégories, seuils d'alerte, import CSV, et sa base clients/fournisseurs.
**FRs couverts :** FR21–FR29, FR87, FR89

## Epic 3: Gestion Multi-Boutiques, Stock & Équipe
Simon peut créer et gérer plusieurs boutiques + warehouse, consulter les stocks de toutes ses boutiques en vue centralisée, transférer du stock entre boutiques, inviter ses employés, gérer leurs rôles et les assigner à leurs boutiques. *(Gestion opérationnelle avancée des employés — suivi d'activité FR67, désactivation FR68 — est dans Epic 8)*
**FRs couverts :** FR30–FR36, FR65–FR66

## Epic 4: Point de Vente (POS)
Loïc peut enregistrer une vente en ≤3 taps, vérifier la dispo cross-boutique, accepter les paiements (cash/MoMo), appliquer des réductions, vendre des produits inexistants (brouillons validés par l'admin), clôturer sa journée en 1 tap. Simon peut valider les ventes brouillons, corriger ou annuler les ventes.
**FRs couverts :** FR37–FR44, FR88, FR90

## Epic 5: Moteur de Synchronisation Offline-First
L'app fonctionne 100% hors-ligne pendant 7 jours, synchronise automatiquement au retour en ligne (<60s), résout les conflits multi-device via delta-based sync, et garantit zéro perte de données.
**FRs couverts :** FR69–FR71, FR73–FR74, FR76

## Epic 6: Inventaire Assisté
Simon (et Loïc) peuvent lancer un inventaire guidé, comparer stock théorique vs physique, calculer les écarts automatiquement, et ajuster le stock en 1 clic.
**FRs couverts :** FR45–FR49

## Epic 7: Rapports, Dashboard & Communication WhatsApp
Simon reçoit automatiquement ses rapports end-of-day et hebdomadaires sur WhatsApp, consulte son dashboard matinal, suit la rentabilité par produit, et configure ses préférences.
**FRs couverts :** FR50–FR60, FR63

## Epic 8: Alertes, Notifications & Gestion Opérationnelle
Simon peut recevoir des alertes de stock critique et de tendances de ventes via push et WhatsApp, consulter l'activité de ses employés, désactiver et révoquer leurs sessions à distance, et les utilisateurs peuvent soumettre un feedback ou supprimer leur compte.
**FRs couverts :** FR61–FR62, FR64, FR67–FR68, FR91–FR92

## Epic 9: Super Admin Dashboard (Plateforme Keevo)
Toor peut piloter toute la plateforme : liste tenants, activation manuelle des payants, dashboard revenus, analytics plateforme, santé système, alertes proactives, et notifications push globales.
**FRs couverts :** FR77–FR83

---
