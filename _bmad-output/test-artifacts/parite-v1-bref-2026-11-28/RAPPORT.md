# Parité Keevo en ligne (V1 2.0.1) → Bref du jour (app RN) — 28/11/2026

## Ce qui a été comparé

- **Keevo en ligne** : l'app Flutter V1 2.0.1 de la branche `release/2.0.x` (`keevo/app/lib`), soit 65 écrans et 21 dossiers de fonctions.
- **Bref du jour** : le dépôt `AI/bref`, au commit `00daac2` de la branche `fix/e2e-corrections-2026-11-23`.
  - Ce commit contient la dernière branche poussée par le dev mobile, `br-migrate-api-to-keevo` `a52667e` du 23/11. Le dev mobile n'a rien poussé depuis.
  - L'app compte 101 routes.
- **Backend** : refonte `681bfc29`, en local sur la base `keevo_e2e`, avec WhatsApp, push, SMS, e-mail et paiement coupés.

## Méthode

1. **Inventaire statique, fonction par fonction**, en 4 lots : lot A (compte et administration), lot B (caisse, ventes, accueil, contacts), lot C (catalogue, stock, inventaire, audit), lot D (rapports, rentabilité, exports, synchro et hors ligne).
   - Chaque bouton, filtre, option et règle de la V1 a été cherché dans le code RN, avec un verdict : CONFORME, PARTIEL, DIFFÉRENT, ABSENT ou CASSÉ.
   - Les détails sont dans `lot-*.md`.
2. **Vérification sur l'émulateur** des écarts majeurs encore jamais testés.
   - Les tests du 23/11 portaient tous sur une entreprise neuve. Cette fois, j'ai reproduit une **entreprise V1 migrée** :
     - facturation désactivée par le propriétaire, comme le fait la migration V50 ;
     - un employé avec téléphone + mot de passe et sans identifiant, comme le laisse la migration V41.
   - Les captures sont dans `screens/`.

## Bloquants confirmés sur l'appareil

| # | Constat | Preuve |
|---|---|---|
| 1 | **Les ventes d'une entreprise V1 sont invisibles.** Une vente en caisse arrive bien au serveur (35ᵉ vente en base), mais n'apparaît ni dans l'onglet Ventes ni dans le chiffre d'affaires de l'accueil. L'app ne lit les ventes qu'à travers les factures automatiques, et la facturation est désactivée pour toutes les entreprises V1 (V50). L'historique V1 n'est jamais lu (`/sales/history` n'est pas appelé). | `emu-v1-04`, `emu-v1-05` |
| 2 | **Un employé V1 ne peut pas se connecter.** « Je suis un commercial » envoie le numéro comme identifiant, d'où « Identifiant ou mot de passe incorrect ». L'API accepte pourtant `phoneNumber` + mot de passe : vérifié par curl. | `emu-v1-15`, `emu-v1-16` |
| 3 | **La fiche d'un employé V1 ne s'enregistre pas** : « L'identifiant est requis ». Si le propriétaire saisit un identifiant, l'app l'affiche comme enregistré alors que le serveur ne l'a jamais reçu : la colonne `username` reste vide et une connexion avec cet identifiant est refusée. Le téléphone de l'employé n'est affiché nulle part. | `emu-v1-08` à `emu-v1-11` |
| 4 | **Toucher une notification n'ouvre rien.** Elle est seulement marquée lue, y compris pour un transfert à réceptionner. Le `deepLink` envoyé par le serveur est ignoré. Les textes du serveur affichent « XAF ». | `emu-v1-12`, `emu-v1-13` |
| 5 | **Un produit archivé ne peut plus être restauré.** La base contient 1 archivé sur 10 produits, l'app en affiche 9, sans filtre « Archivés » ni bouton de désarchivage. | — |

## Bloquants trouvés dans le code (non rejoués, preuves dans les lots)

- **Le vendeur (employé V1) perd** le catalogue en lecture, le stock de sa boutique, l'inventaire, la réception des transferts, la clôture de journée et le rapport du jour. Il n'a ni onglet Produits ni onglet Modules (lots A, B et C).
- **Annulation d'une vente limitée au jour même** ; la V1 permettait d'annuler toute vente terminée (lot B).
- **Brouillons de produits** : ils ont disparu, et les brouillons V1 s'affichent comme des produits actifs (lot C).
- **Suppression du compte** : elle ne peut pas être annulée depuis l'app (déconnexion immédiate, fenêtre de récupération jamais affichée). Pas d'acceptation des CGU à l'inscription (lot A).
- **File hors ligne non durable** : expiration à 24 h, une dizaine d'écritures sans reprise, aucun écran « Opérations bloquées ». Une vente refusée à la synchro disparaît (lot D).

## Écarts gênants (résumé, détail dans les lots)

- **Caisse** :
  - pas de monnaie à rendre ;
  - pas de remise en % ;
  - pas de prix de ligne négocié ;
  - pas de quantité au clavier ;
  - pas de filtre par catégorie ;
  - pas de correction d'article ;
  - pas de « Vider » ;
  - pas de période « Cette semaine » ;
  - pas d'aperçu avant la clôture ;
  - la référence Mobile Money n'apparaît pas dans la fiche de la vente.
- **Catalogue et stock** :
  - pas de gestion des catégories ;
  - SKU non modifiable ;
  - pas d'écran « Mouvements de stock » ;
  - historique d'un produit réduit aux 10 derniers mouvements ;
  - pas de vue de disponibilité multi-boutiques ;
  - transferts sans filtres ;
  - import sans association des colonnes.
- **Pilotage** :
  - pas d'historique des rapports envoyés ;
  - pas de classement des vendeurs ;
  - rentabilité sans détail par produit ;
  - exports sans relance ni CSV hors ligne ;
  - accueil sans comparaison avec hier, sans graphe et sans stock bas ;
  - journal d'audit en anglais technique, sans filtres ni détail.
- **Compte** :
  - pas de changement volontaire de mot de passe ;
  - pas de mot de passe oublié pour un membre ;
  - jeton push conservé après la déconnexion ;
  - pas de bandeaux compte suspendu, essai expiré ou 7 jours sans synchro ;
  - entrepôts non gérés ;
  - pas d'activité par employé ;
  - « Actif depuis » et « Dernière connexion » affichent la date du jour.
- **Clients et fournisseurs** :
  - pas d'archivage ;
  - modification d'un client en ligne seulement ;
  - téléphone non modifiable ;
  - les anciens clients sans boutique sont rattachés à la première boutique.
- **Offre** : en V1, un propriétaire au plan gratuit avait les rapports, les exports CSV et les alertes. Dans l'app RN, ces fonctions sont réservées aux plans payants (point à trancher par le produit, voir P07).

## Ce qui est déjà conforme ou meilleur

- **Connexion** : démarrage hors ligne et choix de l'entreprise.
- **Caisse** : vente en espèces ou mobile money, annulation du jour (avec avoir), ventes hors ligne envoyées une seule fois.
- **Catalogue** : tri, filtre « Non suivi », prix conseillé, code-barres, fusion de produits, page Stock « À racheter ».
- **Inventaire** : complet ou partiel, avec rapport d'écarts.
- **Transferts** : réception.
- **Factures et devis** : bien plus riches qu'en V1.
- **Nouveautés** : dépenses, résultat du mois, WhatsApp, IA, MCP.
- **API** : toutes les routes appelées par l'app pour le catalogue et le stock existent au backend.

## Suite

Les écarts sont répartis en 9 stories, dans `_bmad-output/planning-artifacts/parite-keevo-v1-bref/` :
- P01 : thème Keevo ;
- P02 : comptes et connexion ;
- P03 : ventes sans facturation ;
- P04 : modules ;
- P05 : notifications ;
- P06 : catalogue et stock ;
- P07 : pilotage et audit ;
- P08 : clients, fournisseurs et administration ;
- P09 : hors ligne durable.

Chaque story est développée avec bmad-build-auto sur la branche `feat/parite-keevo-v1` du dépôt AI/bref, puis vérifiée sur l'émulateur avec des vidéos (`videos/`).
