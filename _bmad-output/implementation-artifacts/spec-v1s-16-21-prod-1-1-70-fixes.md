---
title: 'v1s-16-21 Correctifs après la mise en prod 1.1.70 (super-admin mobile, CA total, avis de clôture, dernière activité)'
type: 'bugfix'
created: '2026-10-18'
status: 'done'
baseline_revision: '1921169d0cbb426e8773d7fe6e62ed823562371b'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
  - '{project-root}/docs/MOP_MISE_A_JOUR_PROD.md'
deferred: []
---

<intent-contract>

## Intent

**Problème :** la recette de la 1.1.70 en production (2026-10-18) a été faite sur :
- l'APK publié dans les Releases de `pronto625/keevo` ;
- l'API `keevo.ad2s.cm` ;
- le tableau de bord `keevo-dash.ad2s.cm`.

Elle a relevé quatre défauts :

1. **Super-admin dans l'app mobile.** Le compte super-admin (`KV-ADMIN`, rôle `SUPER_ADMIN`, schéma `public`) se connecte à l'app mobile. Il y apparaît comme « Employé » d'une boutique vide. L'app est réservée aux commerçants.
2. **CA total à 0 au premier affichage.** Juste après la connexion d'un propriétaire, la carte « Chiffre d'affaires total » affiche « 0 XAF — Aucune vente » sur toutes les périodes. Le serveur renvoie pourtant 93 500 XAF / 6 ventes (`periodTotals.allTime`). Les bons chiffres n'apparaissent qu'après « Actualiser ».
3. **Avalanche d'avis de clôture.** Sur une nouvelle installation, un bandeau « Clôture automatique : <boutique> — <date> » s'affiche pour chaque clôture automatique historique tirée du serveur (5 août, 11 août, …), l'une après l'autre.
4. **Dernière activité identique partout.** Dans le tableau de bord, page « Gestion des entreprises », la colonne « Dernière activité » vaut 17/10/2026 pour les 24 entreprises. Elle ne reflète donc pas l'activité réelle des commerçants.

**Approche :** des correctifs ciblés, sans changer le format de l'API ni le schéma de base.
- **1.** L'app refuse un compte qui n'a que l'adhésion `SUPER_ADMIN`, avec un message clair. Elle masque cette adhésion quand le compte en a d'autres. Elle purge une session super-admin déjà enregistrée.
- **2.** Le premier chargement après connexion récupère le résumé serveur. Pendant le chargement, on affiche un état de chargement plutôt qu'un 0 trompeur.
- **3.** Un seul avis groupé, uniquement pour les clôtures récentes. Les anciennes sont marquées comme vues sans être affichées.
- **4.** La dernière activité est recalculée à partir des événements métier du commerçant, et les filtres d'activité suivent la même définition.

## Boundaries & Constraints

**Always :**
- Interface en français, sans donnée technique : ni code, ni identifiant, ni UUID.
- Le backend garde la session `SUPER_ADMIN` sur `public` pour le tableau de bord web (W1).
- Tout schéma injecté dans du SQL passe par `TenantSchema.validate`.
- Montée de version `keevo/app/pubspec.yaml` : 1.1.70+69 → **2.0.0+70** (demande utilisateur 2026-10-18 : passage en 2.0.0 après ces corrections).
- `flutter analyze` : 0 erreur, 0 avertissement.
- Tests par lots uniquement : un dossier Flutter ou un paquet backend à la fois, jamais la suite complète.

**Never :**
- modifier `deferred-work.md` ;
- couper l'accès du super-admin au tableau de bord web ;
- afficher un 0 qui n'a été confirmé ni par le serveur ni par les données locales ;
- recréer un avis pour une clôture déjà signalée.

## I/O & Edge-Case Matrix

| Scénario | Entrée / état | Résultat attendu |
|----------|---------------|------------------|
| Super-admin seul | login → adhésions = [`KV-ADMIN`/`SUPER_ADMIN`] | reste sur Connexion, message « Ce compte est réservé au tableau de bord d'administration Keevo… », aucun jeton enregistré, pas d'appel select-tenant |
| Compte mixte | adhésions commerçant + `SUPER_ADMIN` | l'adhésion super-admin n'est jamais proposée ni choisie automatiquement |
| Session super-admin déjà enregistrée (1.1.70) | rôle `SUPER_ADMIN` dans le jeton au démarrage | session purgée, écran de connexion avec le même message |
| CA total, premier affichage en ligne | connexion propriétaire, serveur `allTime` = 93 500 / 6 | chargement, puis 93 500 XAF / 6 ventes sans « Actualiser » |
| CA total hors ligne | aucune réponse serveur | valeurs locales avec la mention « Données de cet appareil » |
| Nouvelle installation, clôtures anciennes | N clôtures automatiques de plus de 48 h | aucun avis, toutes marquées comme vues |
| Plusieurs clôtures récentes | 3 clôtures automatiques de moins de 48 h non signalées | un seul avis « 3 journées clôturées automatiquement » |
| Employé | clôtures d'autres boutiques | seules celles de sa boutique sont prises en compte |
| Dernière activité | ventes, synchros ou connexions à des dates différentes selon l'entreprise | date du dernier événement métier de chaque entreprise ; « — » si aucune activité |
| Filtre « Activité depuis / jusqu'au » | intervalle donné | filtre sur la même définition que la colonne |

</intent-contract>

## Tasks

- [x] **T1 (mobile, auth)** : refuser ou masquer l'adhésion `SUPER_ADMIN` au login et au choix d'entreprise ; purger une session super-admin au démarrage ou au rafraîchissement ; ajouter les tests auth.
- [x] **T2 (mobile, dashboard)** : cause du 0 au premier chargement, chargement du résumé après connexion, état de chargement, repli local étiqueté ; vérifier aussi CA d'aujourd'hui, CA d'hier et stock bas ; ajouter les tests dashboard.
- [x] **T3 (mobile, clôtures)** : fenêtre de récence, avis groupé unique, anciennes marquées comme vues, liste des identifiants signalés bornée, restriction employé conservée ; ajouter les tests.
- [x] **T4 (backend + tableau de bord)** : cause de la date uniforme, calcul sur l'activité métier par entreprise, filtres d'activité alignés, « — » si aucune activité ; tests du paquet admin (et du tableau de bord si l'interface change).
- [x] **T5** : passer en version 2.0.0+70, lancer les tests par lots et `flutter analyze`, faire une passe de revue.
- [ ] **T6** : déployer sur `pronto625/keevo` (accord explicite requis) ; recette en prod sur le téléphone et le tableau de bord, avec les comptes de recette fournis.

## Acceptance Criteria

- **AC1** : le compte super-admin ne peut pas entrer dans l'app mobile. Le message est clair et le tableau de bord web reste accessible.
- **AC2** : le premier affichage du CA total après connexion correspond au serveur, sans action manuelle.
- **AC3** : une nouvelle installation n'affiche aucun avis pour les clôtures anciennes. Les clôtures récentes donnent un seul avis groupé.
- **AC4** : « Dernière activité » reflète la dernière activité réelle de chaque entreprise, et les filtres sont cohérents avec elle.
- **AC5** : tests et analyse au vert ; app en 2.0.0+70.

## Dev Agent Record

- 2026-10-18 : story créée à la demande de l'utilisateur pour regrouper les 4 constats de la recette prod. T1 à T4 sont en cours d'implémentation (un agent par tâche, fichiers disjoints). L'intégration, la version, les tests et la revue restent à faire.
- 2026-10-18 — T1–T5 faits :
  - T1 : `login_usecase` filtre `SUPER_ADMIN` (erreur `PLATFORM_ADMIN_ACCOUNT` → message FR/EN), `startup_route` purge une session super-admin enregistrée (+ SnackBar), `JwtClaims.isPlatformAdmin`.
  - T2 : cause = `dashboardSnapshotProvider` non lié à la session (cache d'avant connexion) + repli appareil immédiat pendant la 1re synchro ; `dashboardSessionKeyProvider`, relances serveur bornées (reconnexion, y compris démarrage hors ligne ; 3 × 20 s), 1 nouvel essai sur erreur transitoire, carte en chargement au lieu d'un 0.
  - T3 : fenêtre 48 h, un seul avis groupé, liste des avis bornée.
  - T4 : cause = la clôture automatique du soir (`DAY_CLOSED`, acteur SYSTEM) écrite dans `audit_log` de toutes les entreprises ; `TenantLastActivitySql` (vente plafonnée à now(), audit des membres OWNER/EMPLOYEE hors SYSTEM, synchro, usage de l'app), colonne + filtre + page Opérations alignés.
  - T5 : version 2.0.0+70 ; `flutter analyze` 0 erreur / 0 avertissement ; régression complète par lots — backend 13 paquets 3 495 tests (admin rejoué 504), app 22 lots 2 604 tests (dashboard rejoué 141), 0 échec ; revue : rien de bloquant, 3 constats mineurs corrigés (vente datée dans le futur, reconnexion après démarrage hors ligne, commentaires).
- 2026-10-18 — T6 fait : déployé sur `pronto625/keevo` (commit f9705ce7), API UP, APK 2.0.0+70 publié (même signature). Recette prod :
  - Tableau de bord `keevo-dash.ad2s.cm` : 11 pages sans erreur ; « Dernière activité » différenciée par entreprise.
  - Téléphone : mise à jour 1.1.70 → 2.0.0 sans désinstaller (session conservée, CA exact) ; super-admin refusé avec le message prévu ; installation neuve : CA juste dès la connexion, aucun avis de clôture.
  - Compte `+237690000001` : vente en ligne, vente hors ligne puis synchro (serveur : 3 ventes / 15 500 XAF ce jour), transfert btq tal → Boutique talos, mouvements de stock à jour, inventaire partiel validé (serveur : PARTIAL / VALIDATED).
