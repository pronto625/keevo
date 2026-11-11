# Répétition générale de la bascule Bref → Keevo (story 24.12) — 11/11/2026

> Aucune donnée personnelle : owners désignés « Compte n », montants et compteurs agrégés seulement. Le dump n'est ni
> copié ni versionné ; les bases de répétition ont été supprimées à la fin.

## 1. Cadre

- **Copie de la prod Bref** : dump du 03/11/2026 fourni par l'utilisateur (`bd_local_20261103_213348.sql.gz`, 36 Ko,
  SQL texte, pg_dump 16.13), retenu comme copie de prod par décision du 11/11 (Bref est très petite). Restauré tel quel
  dans une base qui joue la base de Bref, puis **le runbook J0 a produit lui-même** le dump `-Fc` et la copie restaurée.
- **Contenu** : 1 owner (actif), 1 boutique, 1 gérant, 2 vendeurs, 19 documents (14 ventes payées, 2 ventes annulées,
  3 devis en cours), 36 lignes, 8 échéances, 6 produits, 3 clients, 4 abonnements Bref (1 actif), 0 dépense, 0 lien
  public partagé, 2 conversations de l'assistant.
- **Poste** : base PostgreSQL 16 et backend Keevo (branche refonte, commit de la répétition) sur la même machine ;
  WhatsApp en mode sans envoi, notifications désactivées ; aucun message envoyé.
- **Runbook** : `docs/RUNBOOK_BASCULE_BREF.md`, §0.4 (connexion en deux temps), §6.2–6.8, §6.10, §6.13, §6.14, §1.5,
  §6.18, §8.1 (étape 1) joués tels qu'écrits ; seules substitutions : noms de base / rôle suffixés, `scp` remplacé par une
  copie locale, `docker compose up` remplacé par le démarrage local du backend, adresse de l'API locale.

## 2. Chronométrage (copie de prod)

| Étape | Répétition 1 (« go ») | Répétition 2 (dump frais, retour arrière) |
|---|---|---|
| 6.2 dump `-Fc -Z 6` | 0,16 s (95 815 octets) | 0,16 s |
| 6.3 transfert + `sha256sum -c` | 0,01 s | 0,01 s |
| 6.4 restauration | 1,39 s | 1,85 s |
| 6.5 index d'aide (11 / 11) | 0,33 s | 0,40 s |
| 6.6 rôle en lecture seule (contrôle : écriture refusée) | 0,19 s | 0,20 s |
| 6.7 démarrage du backend | 33,4 s | 32,7 s |
| 6.8 commande (suivi toutes les 10 s) | 10,2 s ; 5,0 s côté serveur ; owner 4,9 s | 10,2 s ; 4,9 s ; owner 4,8 s |
| 6.10 rapport | 0,1 s | 0,1 s |
| 6.14 « go » | 0,08 s | — |
| 1.5 liens | 0,16 s (aucun lien public) | — |
| 6.18 contrôles | 0,13 s : aucune entreprise restée suspendue, aucune erreur au journal | — |
| 8.1 retour arrière | — | 10,1 s au suivi (0,19 s côté serveur) |

**Durée de coupure** : ≈ **50 s de machine** de l'arrêt au « go ». Avec les gestes humains qui n'existent pas sur le
poste (arrêt d'api-bref 1 min, commandes tapées ≈ 7 min, webhooks des fournisseurs 15 min en parallèle de la commande —
chemin critique —, lecture du rapport et décision 15 min, « go » 1 min) : **≈ 40 min** jusqu'au « go ». Annonce retenue
(règle §0.2 du runbook) : **retour au plus tard 3 h après l'arrêt** (1 h 35 arrondie à 2 h, plus 1 h de propagation de
l'app dans les stores), heure limite de décision = arrêt + 2 h 15. Durée moyenne par owner : 4,8–4,9 s.

## 3. Réconciliation sur les données réelles

« Compte 1 » : **Importé et réconcilié**, trois fois (répétition 1, répétition 2, troisième exécution après le retour
arrière), rapports identiques hors durées.

| Contrôle | Bref | Keevo | Écart |
|---|---|---|---|
| Propriétaire / boutiques / membres (comptes et invitations) | 1 / 1 / 3 | 1 / 1 / 3 | 0 |
| Produits / clients | 6 / 3 | 6 / 3 | 0 |
| Factures payées / à encaisser / annulées | 14 / 0 / 2 | 14 / 0 / 2 | 0 |
| Devis en cours / annulés | 3 / 0 | 3 / 0 | 0 |
| Lignes de documents | 36 | 36 | 0 |
| Encaissements et échéances (acomptes compris) | 22 | 22 | 0 |
| Dépenses / documents supprimés dans Bref | 0 / 0 | 0 / 0 | 0 |
| Ventes non annulées | 958 697 F | 958 697 F | 0 |
| Montant encaissé | 1 934 847 F | 1 934 847 F | 0 |
| Restant dû des ventes en cours | 0 F | 0 F | 0 |
| Crédit du compte | 0 F | 0 F | 0 |
| Chiffre d'affaires mensuel (règle de Bref ↔ tableau de bord Keevo) | oct. 957 777 F, nov. 920 F | oct. 957 777 F, nov. 920 F | 0 |

**Anomalies relevées (aucune ne bloque le « go »)** :
1. Deux ventes annulées dans Bref portent des encaissements (acomptes et échéances payées, 976 150 F au total) : ils
   sont repris tels quels ; « Montant encaissé » (règle 24.8 : tous les documents, annulés compris) dépasse donc
   « Ventes non annulées ». Pas un écart : Bref et Keevo donnent la même somme.
2. Quatre compteurs de consommation de périodes passées ne sont pas repris (règle 24.5, comme prévu).
3. Le logo de la boutique n'est pas rapatrié en répétition (téléchargement désactivé sur le poste) ; il l'est le jour J
   (`KEEVO_MIGRATION_BREF_LOGO_FETCHENABLED=true`).
4. Aucun lien public Bref n'a été partagé : le champ du jeton est vide sur les 19 documents (alors que la date
   d'expiration est remplie) ; la redirection des anciens liens n'aura rien à servir pour cette copie.
5. Un seul abonnement Bref est actif sur quatre (trois résiliés) : le plan repris est celui en vigueur à la date de la
   bascule, contrôle « abonnement » sans écart.

## 4. Retour arrière

Répétition 2 : retour arrière global → « Retour arrière effectué », 0,19 s côté serveur. État de la base Keevo comparé
table par table à celui d'avant la commande : identique, sauf `audit_log` et `refresh_tokens` (connexions du
super-admin pendant la répétition) et l'historique de la bascule (`migration_cutovers`, `migration_cutover_owners`,
gardés exprès). « go » après le retour arrière → 409 « Retour arrière effectué : ouvrez une nouvelle bascule ». Une
nouvelle bascule ouverte ensuite réimporte et réconcilie l'owner à l'identique.

## 5. Montée en charge (validation du verrou des DDL et extrapolation)

Copie de prod + jeu synthétique `keevo/ops/migration/bref-synthetic-load.sql` (201 owners synthétiques dont un de
20 000 documents) : 202 owners, 25 006 documents. Base Keevo remise à son état initial avant chaque essai.

| Essai | Parallélisme / connexions | Commande | Moyenne | p95 | Plus lourd | Échecs | Interblocages |
|---|---|---|---|---|---|---|---|
| 24.9 (avant le verrou) | 4 / 12 | 343,3 s | 6,8 s | 8,6 s | 24,7 s | 30 provisionnements de modules repris | **oui** |
| 24.12 essai 3 | 4 / 12 | 600,1 s | 11,8 s | 16,5 s | 29,7 s | 0 | **0** |
| 24.12 essai 4 | 8 / 12 | 1 234,2 s | 48,7 s | 125,7 s | 335,6 s | 14 (réserve de connexions épuisée), relance 45,8 s → 1 restant | **0** |

- Sommes Bref = Keevo dans les deux essais (essai 3 : ventes 1 061 585 617 F, encaissé 860 874 207 F, restant dû
  201 687 560 F, dépenses 3 015 000 F) ; index de la copie 11 / 11.
- Base : au plus 3 (essai 3) / 4 (essai 4) transactions en attente du verrou des DDL (relevé toutes les 2 s ; à 8, les
  autres owners attendaient une connexion de la réserve, épuisée, avant même d'atteindre le verrou) ; au plus 21
  connexions Keevo (réserve principale 12 + réserve des scripts de modules) et 4 du rôle en lecture seule.
- Essai 4 : l'owner resté « Import en échec » après la relance n'a pas été analysé (le retour arrière l'a effacé) ; il
  relève du même épuisement de connexions. Conséquence : l'API refuse désormais un parallélisme supérieur à 4.
- Retour arrière des 202 owners (essai 4) : 32,6 s ; plus aucun schéma ni compte importé.
- Pages suivantes des listes super-admin (`after=`) parcourues jusqu'au bout : owners de la bascule (pages de 100 puis
  de 50, filtre par statut), lots d'import (pages de 50), bascules (pages de 1) : chaque ligne une fois, positions
  croissantes.

**Lecture.** Le verrou rend la création des schémas sérielle : plus aucun interblocage, mais le débit plafonne à
≈ 0,34 owner/s (≈ 3 s de travail verrouillé par owner sur ce poste) quel que soit le parallélisme, et le temps par owner
croît avec le nombre d'entreprises déjà créées (≈ 9,5 s pour les 40 premières, ≈ 13 s au-delà de 80). Au parallélisme 8,
les owners en attente du verrou tiennent chacun 2 connexions : 12 ne suffisent plus. **Parallélisme retenu : 4.**
Extrapolation : ≈ `N × 3 s` + owner le plus lourd pour de petits `N` → 1 owner ≈ 5 s (prod du 03/11), 200 ≈ 10 min ;
la croissance du temps par owner avec le nombre de schémas rend 1 000 owners plus long que linéaire (au moins 1 h, à
mesurer si Bref grossit). Le
plus gros owner (30 s) reste loin des 30 min de reprise d'un coordinateur, qui bat de toute façon toutes les minutes
pendant qu'il attend ses owners.

## 6. Correctif de la cause des interblocages

Les scripts `V1` des modules socles lisent `information_schema.columns` pour réparer des valeurs par défaut ; la colonne
`column_default` appelle `pg_get_expr`, qui pose un verrou de lecture sur chaque table lue — PostgreSQL l'évalue sur les
tables de **tous** les schémas avant de filtrer. Deux créations d'entreprise en parallèle, chacune avec un verrou
exclusif sur ses tables neuves, s'attendaient l'une l'autre (`deadlock detected`). Les scripts appliqués ne peuvent pas
changer (sommes de contrôle Flyway) : un verrou consultatif unique (`TenantDdlLock`) est pris en tête de chaque
transaction qui modifie la structure d'un schéma d'entreprise (création, chaque script de module, suppression). Test
d'intégration : 8 créations simultanées sans interblocage (avec interblocage sans le verrou).
