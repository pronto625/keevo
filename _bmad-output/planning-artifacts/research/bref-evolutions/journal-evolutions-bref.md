# Journal des évolutions de Bref (story 18.3)

Bref n'est pas gelé (décision du 03/11/2026) : il reste en production et reçoit des correctifs. Ce journal suit chaque commit des dépôts `AI/bref` (app Expo) et `AI/api-bref` (API NestJS) pour que Keevo reproduise ce que Bref fait réellement.

**Quand le tenir :** au début de chaque session de travail sur la fusion (décision du 03/11/2026), et avant chaque story des epics 20, 21, 23, 24 et chaque vague de migration. Commande : `bash check-bref.sh` (lecture seule : `git fetch` puis liste des commits non journalisés).

**Classement :**
- *sans impact* : texte, style, correctif local sans effet sur les données ni les règles.
- *règle métier* : calcul, statut, quota, plan, TVA, numérotation → story miroir (20/21 backend, 23 app) ou mise à jour de la story existante.
- *schéma* : table, colonne, type, contrainte → importeur (24), kit de mesures 18.1 (gardes « objet absent ») et réconciliation.
- *API/app* : contrat, écran, parcours → inventaire de parité 22.17 et story 23 concernée.

## Références

<!-- check-bref.sh lit « prod journalisée <dépôt>: <hash> » et « dernier commit journalisé <dépôt>: <hash> » : garder ce format, les mettre à jour après chaque revue. -->

| Dépôt | Prod (`main`) | Dernier merge `develop` → `main` | Dernier commit journalisé (`develop`) |
|---|---|---|---|
| api-bref | prod journalisée api-bref: fef03df (2026-11-03, merge develop 21:18) | `a3faeb2` PR #16, 2026-10-26 | dernier commit journalisé api-bref: 6faa8b4 |
| bref | prod journalisée bref: a8536fb (2026-11-03, merge develop 21:18) | `66b73b7` PR #7, 2026-10-26 | dernier commit journalisé bref: 98840f5 |

Base de l'analyse de fusion : `develop` jusqu'à `376ad70` (api-bref) et `a435e9b` (bref). Tout ce qui précède est couvert par l'analyse (§3.7) ; **Le 03/11/2026 à 21:18, `develop` a été fusionnée dans `main` dans les deux dépôts** (api-bref `fef03df`, bref `a8536fb`) : tout ce que l'analyse couvrait (statuts calculés serveur, quantités décimales, NUI/RCCM, `emailVerified`, table `refresh_token`, fiche client) et les entrées ci-dessous sont **en production depuis le 03/11** (déploiement automatique sur push de `main`). Q13 : bascule `develop` → prod = **2026-11-03** (pour Q7, les documents antérieurs au 03/11 sont « avant develop »). Le kit 18.1 garde ses gardes « objet absent » pour d'anciennes copies.

## Entrées

| Date revue | Dépôt | Commit | En prod ? | Résumé | Classement | Action |
|---|---|---|---|---|---|---|
| 2026-11-03 | api-bref | `6faa8b4` (2026-11-01) | oui (03/11) | Adresse, personne à contacter et site web du client recopiés sur chaque facture et devis (colonnes `invoice.customerAddress`, `customerContactName`, `customerWebsite`, migration 1753810000000), création, modification et PDF | schéma + règle métier | Story 20.5 mise à jour (`client_contact_name`, `client_website` sur la facture ; `client_address` existe déjà) ; 24.4 importe les trois colonnes quand elles existent ; kit 18.1 non concerné (il ne lit pas ces colonnes) |
| 2026-11-03 | bref | `e6fccb2` (2026-11-01) | oui (03/11) | Même détail client affiché à la création, au récapitulatif, au détail et dans 4 modèles de reçu | API/app | Inventaire de parité 22.17 (parcours Document, modèles de reçu) ; 23.2 et 23.4 |
| 2026-11-03 | bref | `2b8907e` (2026-11-01) | oui (03/11) | Menu « modules » sur le tableau de bord, écrans Stock et Inventaire « bientôt disponible » | API/app | Début du travail d'app unifiée dans le dépôt bref. Rappel pour le développeur mobile : FUS-UX7 / 19.13 — un utilisateur existant ne voit que les modules activés ; pas d'écran « bientôt disponible » ni d'entrée de module désactivé chez un utilisateur Bref existant |
| 2026-11-03 | bref | `a8a4f19` (2026-11-01) | oui (03/11) | Suggestions de produits pendant la recherche, étape Articles | API/app | Inventaire 22.17 (parcours Document, étape Articles) ; 23.2 |
| 2026-11-03 | bref | `16878b9` (2026-11-01) | oui (03/11) | Bouton « créer un nouvel article » quand la recherche est vide, étape Articles | API/app | Inventaire 22.17 ; 23.2 |
| 2026-11-03 | bref | `98840f5` (2026-11-03) | oui (03/11) | Menu de modules : entrées Fournisseurs et Achats, écrans « bientôt disponible » | API/app | Suite du travail d'app unifiée dans le dépôt bref (Keevo a déjà les fournisseurs ; « Achats » n'existe ni dans Keevo ni dans le plan de fusion : à confirmer avec l'owner produit avant toute story). Rappel FUS-UX7 / 19.13 : ces entrées sont visibles en prod chez les utilisateurs Bref existants alors que les modules ne sont pas activés — contraire à la règle « modules nouveaux désactivés, activables dans les paramètres » ; à signaler au développeur mobile. |
| 2026-11-03 | les deux | merge `develop` → `main` | oui | Mise en production de tout `develop` (8 commits api-bref, 14 commits bref, détaillés dans l'analyse §3.7 et les entrées ci-dessus) | règle métier + schéma + API/app | Déjà couverts par l'analyse §3.7 et les stories 19–24 ; Q13 = 2026-11-03 ; aucune nouvelle story. |
