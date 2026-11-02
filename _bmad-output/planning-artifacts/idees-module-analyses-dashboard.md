---
title: 'Idées — module « Analyses » (dashboard client Keevo)'
status: 'idea'
created: '2026-11-02'
author: Dalois-30 (avec Claude)
---

# Module « Analyses » — notes de réflexion (02/11/2026)

> Statut : **idée**, pas encore planifiée. Décision utilisateur du 02/11/2026 : ce sera **un module Keevo à ajouter** (manifeste, gating, `db/modules/<id>/`). À reprendre en brainstorming puis en epic (`bmad-create-epics-and-stories`) quand la refonte est en production. Dépendances : refonte (epics 16–17), couche d'outils IA (fusion, story 21-8), modules Bref (`expenses`, `finance`, factures/clients — epic 20).

## Principe

Pas des graphiques à interpréter : des **cartes d'action**. Chaque analyse se termine par une décision proposée avec un bouton (« Commander », « Créer le transfert », « Relancer sur WhatsApp »).

## Idées par thème

1. **Stock**
   - Jours de stock restants par produit (rythme de vente) → date de rupture prévue.
   - Suggestion de réapprovisionnement avec quantité.
   - Stock dormant (aucune vente depuis 30 / 60 jours) et argent immobilisé.
   - Rééquilibrage entre boutiques → transfert prêt à valider (`multi-store`).
2. **Ventes**
   - Heures et jours forts (carte de chaleur) → horaires et nombre de vendeurs.
   - Analyse ABC (20 % des produits = 80 % du chiffre d'affaires).
   - Produits souvent achetés ensemble → packs, rangement.
   - Comparaisons de périodes expliquées (« −12 %, surtout à cause de X »).
3. **Marges** (`profitability`)
   - Marge par produit / catégorie.
   - Alerte : produit vendu sous son prix d'achat.
   - Coût des prix modifiés à la caisse (données de 17-7).
4. **Contrôle et anomalies** (forte valeur pour l'owner absent)
   - Score de risque par employé : annulations, prix modifiés, ajustements de stock, écarts de clôture.
   - Schémas inhabituels (annulations avant clôture, ajustements à la baisse répétés).
5. **Clients et impayés** (après la fusion Bref)
   - Segmentation RFM : fidèles, en perte de vitesse, perdus.
   - Clients à risque → campagne WhatsApp en un geste.
   - Liste d'appel des impayés (montant, ancienneté).
6. **Prévisions**
   - Chiffre d'affaires attendu (saisonnalité : fêtes, rentrée).
   - Trésorerie prévisionnelle (ventes attendues − dépenses prévues, module `finance`).
7. **Comparaison avec des commerces similaires** (plus tard)
   - « Votre panier moyen vs boutiques de votre secteur / ville ».
   - Consentement explicite, statistiques anonymes sur au moins N commerces, jamais de données individuelles ; cadrage RGPD avant tout code.

## Choix techniques envisagés

- **Calculs à l'avance** : job nocturne par tenant qui remplit des tables d'agrégats (jour × produit × boutique × vendeur) ; le dashboard lit ces chiffres (rapide, cache hors ligne sur le téléphone).
- **Statistiques simples et explicables** (moyennes mobiles, règles claires) ; pas d'apprentissage automatique au départ. Chaque indicateur affiche sa période et ses données de base.
- **Un seul endroit pour les calculs** : use cases du module, exposés aussi comme **outils IA** (21-8) → l'assistant et MCP répondent avec les mêmes chiffres que le dashboard.
- **Gating** : indicateurs de base gratuits ; stock intelligent, anomalies, clients, prévisions en Premium (nouveau module payant, ou extension d'`advanced-reports` — à trancher).
- **Canaux** : cartes dans l'app, résumé hebdomadaire WhatsApp (à côté du rapport de fin de journée), assistant IA.
- **Données imparfaites à gérer** : produits sans suivi de stock (17-12), ventes synchronisées en retard (hors ligne), prix modifiés à la caisse.

## Priorités proposées

1. Jours de stock restants + suggestion de réapprovisionnement.
2. Stock dormant.
3. Anomalies employés (prix modifiés, annulations, ajustements).
4. Puis clients / impayés (après la fusion), prévisions, et la comparaison en dernier.

## Questions ouvertes

- Un module unique « Analyses » ou plusieurs (stock intelligent, contrôle, clients) ?
- Quels indicateurs restent gratuits ?
- Fréquence de calcul (nuit seulement, ou aussi à la clôture de journée) ?
- Seuils des anomalies : fixes ou réglables par l'owner ?
