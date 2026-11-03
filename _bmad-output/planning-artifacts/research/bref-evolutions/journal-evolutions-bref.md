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

| Dépôt | Prod (`main`) au 03/11/2026 | Dernier merge `develop` → `main` | Dernier commit journalisé (`develop`) |
|---|---|---|---|
| api-bref | prod journalisée api-bref: a3faeb2 (2026-10-26) | `a3faeb2` PR #16, 2026-10-26 | dernier commit journalisé api-bref: 6faa8b4 |
| bref | prod journalisée bref: 66b73b7 (2026-10-26) | `66b73b7` PR #7, 2026-10-26 | dernier commit journalisé bref: 16878b9 |

Base de l'analyse de fusion : `develop` jusqu'à `376ad70` (api-bref) et `a435e9b` (bref). Tout ce qui précède est couvert par l'analyse (§3.7) ; les commits de `develop` pas encore sur `main` (8 dans api-bref, 13 dans bref au 03/11) ne sont **pas en production** : le kit 18.1 et l'importeur doivent fonctionner avec et sans eux.

## Entrées

| Date revue | Dépôt | Commit | En prod ? | Résumé | Classement | Action |
|---|---|---|---|---|---|---|
| 2026-11-03 | api-bref | `6faa8b4` (2026-11-01) | non (develop) | Adresse, personne à contacter et site web du client recopiés sur chaque facture et devis (colonnes `invoice.customerAddress`, `customerContactName`, `customerWebsite`, migration 1753810000000), création, modification et PDF | schéma + règle métier | Story 20.5 mise à jour (`client_contact_name`, `client_website` sur la facture ; `client_address` existe déjà) ; 24.4 importe les trois colonnes quand elles existent ; kit 18.1 non concerné (il ne lit pas ces colonnes) |
| 2026-11-03 | bref | `e6fccb2` (2026-11-01) | non (develop) | Même détail client affiché à la création, au récapitulatif, au détail et dans 4 modèles de reçu | API/app | Inventaire de parité 22.17 (parcours Document, modèles de reçu) ; 23.2 et 23.4 |
| 2026-11-03 | bref | `2b8907e` (2026-11-01) | non (develop) | Menu « modules » sur le tableau de bord, écrans Stock et Inventaire « bientôt disponible » | API/app | Début du travail d'app unifiée dans le dépôt bref. Rappel pour le développeur mobile : FUS-UX7 / 19.13 — un utilisateur existant ne voit que les modules activés ; pas d'écran « bientôt disponible » ni d'entrée de module désactivé chez un utilisateur Bref existant |
| 2026-11-03 | bref | `a8a4f19` (2026-11-01) | non (develop) | Suggestions de produits pendant la recherche, étape Articles | API/app | Inventaire 22.17 (parcours Document, étape Articles) ; 23.2 |
| 2026-11-03 | bref | `16878b9` (2026-11-01) | non (develop) | Bouton « créer un nouvel article » quand la recherche est vide, étape Articles | API/app | Inventaire 22.17 ; 23.2 |
