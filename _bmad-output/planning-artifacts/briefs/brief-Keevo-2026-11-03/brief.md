---
title: "Brief produit : fusion Keevo × Bref Point"
status: draft
created: 2026-11-03
updated: 2026-11-03
sources:
  - _bmad-output/planning-artifacts/fusion-keevo-bref-analyse-2026-10-31.md
  - _bmad-output/planning-artifacts/epics-fusion-keevo-bref.md   # exigences FUS-*, epics 18–25, Annexe C (ADR-01 à 09)
  - _bmad-output/planning-artifacts/research/bref-evolutions/journal-evolutions-bref.md
---

# Brief produit : fusion Keevo × Bref Point

## Résumé

Keevo et Bref Point servent les mêmes commerçants d'Afrique centrale et de l'Ouest, avec deux produits complémentaires. Keevo gère la **caisse et le stock** (multi-boutiques, hors ligne jusqu'à 7 jours). Bref Point gère la **facturation et le suivi des services** (devis, factures, impayés, relances WhatsApp, assistant IA). Chacun a des utilisateurs réels. Maintenir deux apps et deux backends coûte double et oblige un commerçant qui a besoin des deux à jongler entre deux outils.

La fusion donne **une seule app, Bref Point, et un seul backend, celui de Keevo**. Chaque commerçant y retrouve ses repères et ses données, et peut activer, quand il le veut, ce que l'autre produit savait faire : la caisse et le stock pour un utilisateur Bref, les devis et les dépenses pour un utilisateur Keevo. La règle d'or : **personne ne perd de données, ne change de mot de passe ni ne paie deux fois.**

Pourquoi maintenant : le backend Keevo vient d'être refondu en modules (refonte terminée, pas encore en production). C'est précisément l'architecture qui permet d'accueillir les fonctions de Bref sous forme de modules. La refonte ne partira en production qu'avec la fusion complète (décision du 03/11/2026).

## Le problème

- **Pour les commerçants.** Un boutiquier qui vend au comptoir et facture aussi des clients professionnels a besoin des deux outils. Aujourd'hui il ressaisit ses clients et ses produits, ne voit pas son chiffre d'affaires en un seul endroit et paie deux abonnements.
- **Pour l'équipe.** Deux apps (Flutter, React Native), deux backends (Spring, NestJS), deux schémas de données, deux chaînes de publication. Le backend de Bref ne compte que 4 tests et certains flux critiques n'y sont pas transactionnels ; celui de Keevo apporte la plateforme (synchronisation hors ligne, modules, audit, 4 235 tests) mais pas de paiement en libre-service ni de présence sur les stores.
- **Le coût de ne rien faire.** Chaque nouvelle fonction se fait deux fois ou ne se fait que d'un côté ; les deux bases d'utilisateurs divergent.

## Pour qui

| Profil | Aujourd'hui | Ce qu'il attend de la fusion |
|---|---|---|
| **Commerçant Keevo** (boutique, caisse, stock, souvent plusieurs boutiques et employés) | App Keevo en APK, hors ligne, rapports WhatsApp du soir | Retrouver sa caisse et son stock à l'identique, sans rien perdre ; une app sur le store ; payer seul son abonnement |
| **Prestataire ou commerçant Bref** (services, facturation, devis, impayés) | App Bref Point sur les stores, facture en 5 étapes, relances, assistant | Ne pas sentir la migration ; garder ses factures, ses clients, son plan et ses crédits |
| **Employé** (vendeur, caissier) | Accès limité à sa boutique | Le même périmètre, rien de plus à apprendre |
| **Super-admin** (équipe Keevo/Bref) | Deux dashboards | Une seule console : comptes, offres, migrations, santé de la plateforme |


## La solution

Une seule app React Native publiée sous **Bref Point** (`com.brefpoint.app`, Android et iOS), construite sur la base de l'app Bref, avec la couche hors ligne de Keevo. Un seul backend Keevo, qui absorbe **toutes** les fonctions de Bref sous forme de modules, y compris son API super-admin.

- **Chacun garde ses repères.** Un ex-Keevo arrive sur la caisse, un ex-Bref sur la création de facture. Mêmes onglets, même ordre, mêmes mots ; seul l'habillage change.
- **Rien de nouveau n'est imposé.** Une fonction nouvelle pour un utilisateur existant est comprise dans son plan mais désactivée ; l'owner l'active dans les paramètres.
- **Une offre commerciale pilotée sans code.** Plans, modules, plafonds, quotas, prix, essais et remises se gèrent depuis le dashboard super-admin, sans redéploiement.
- **Paiement en libre-service.** Le commerçant paie seul, par Mobile Money (MyCoolPay) ou par achat intégré sur iOS ; plus besoin d'activation manuelle.
- **Migration invisible pour les utilisateurs Bref.** Leurs comptes sont migrés un propriétaire de compte (owner) à la fois, vérifiés au franc près, avec retour arrière possible jusqu'à l'activation. Les utilisateurs Keevo (aujourd'hui installée en APK, hors des stores) installent Bref Point une fois et se reconnectent avec leurs identifiants.

## Ce qui rend l'approche solide

- **On garde le meilleur de chaque côté** : de Keevo, le backend et le protocole hors ligne ; de Bref, l'app, le design, la présence sur les stores et le paiement. On ne réécrit pas ce qui marche.
- **Pas de big-bang** : les utilisateurs Keevo n'ont aucune donnée à migrer ; les owners Bref migrent par vagues, pilotes volontaires d'abord.
- **Un seul go-live, contrôlé** : toute la fusion validée sur staging (jalon GL, pour go-live) avant la production, avec un inventaire de parité fonction par fonction et des tests avec de vrais utilisateurs.

La réussite dépendra surtout de l'exécution et de la qualité de la migration.

## Critères de succès

- **Zéro perte** : 0 opération hors ligne perdue lors du passage à la nouvelle app ; 100 % des owners Bref migrés réconciliés au franc près avant activation.
- **Parité** : inventaire de parité Keevo et Bref entièrement coché sur staging avant le go-live ; tests utilisateurs concluants (3 à 5 commerçants de chaque côté).
- **Adoption** `[ASSUMPTION]` : 90 % des appareils Keevo actifs sur Bref Point 4 semaines après la bêta ; moins de 2 % d'appareils restés sur l'APK Keevo avant son retrait.
- **Stabilité** : taux de sessions sans crash d'au moins 99,5 % pendant la bêta ; moins de 0,5 % des opérations mises en quarantaine lors de la synchronisation.
- **Rétention** `[ASSUMPTION]` : le nombre d'owners actifs 30 jours après chaque vague n'est pas inférieur à celui d'avant la vague.
- **Fin de l'exploitation en parallèle** : le backend Bref, sans owner actif pendant 4 semaines, est arrêté.

## Périmètre

**Dans la fusion :** toutes les fonctions actuelles de Keevo et de Bref (y compris celles que Bref livre pendant la fusion, suivies par le journal des évolutions) ; identité étendue (OTP, Google, Apple, username) ; rôles owner, manager, employé, lecteur ; plans et offre entièrement configurables ; paiement en libre-service ; migration des owners Bref ; une seule app sur les stores ; cinq pays dès la fusion : Cameroun, Gabon, Tchad, RDC, Sénégal (TVA, devise et arrondi gérés comme aujourd'hui dans Bref).

**Hors périmètre :** nouvelles fonctions sans équivalent dans Keevo ou Bref (par exemple le futur module « Analyses ») ; facturation électronique DGI (en attente des spécifications) ; refonte du dashboard super-admin (gérée dans un autre dépôt) ; app web.

**Contraintes de calendrier :** les apps Keevo V1 restent en service jusqu'au go-live ; Bref n'est pas gelé et continue de recevoir des correctifs ; estimation de 32 à 40 semaines pour un développeur backend assisté par IA, l'app étant construite en parallèle par un second développeur.

## Ce qu'on ne sait pas encore

- **Les volumes réels** : nombre d'owners Bref actifs et payants, numéros de téléphone présents dans les deux bases, factures incohérentes. Le kit de mesures est prêt ; il doit tourner sur les réplicas de production avant l'étape de contrôle G0.
- **L'accès aux comptes stores** de Bref Point (Play Console, App Store Connect, clés de signature, EAS).
- **Le rythme des évolutions de Bref** pendant la fusion, qui peut allonger le calendrier.

## Vision

Dans deux ou trois ans, Bref Point est l'outil de gestion unique des petits commerces et prestataires d'Afrique francophone : un commerçant commence par la facture ou par la caisse, active le reste quand son activité grandit, et pilote tout depuis son téléphone, même hors ligne. Les modules (restauration, analyses, facturation électronique) s'ajoutent sans nouvelle app.
