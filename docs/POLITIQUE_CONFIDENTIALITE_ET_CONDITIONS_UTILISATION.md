# Keevo — Politique de Confidentialité & Conditions Générales d'Utilisation

> **Version :** 1.0  
> **Date d'entrée en vigueur :** 7 avril 2026  
> **Dernière mise à jour :** 7 avril 2026  
> **Document préparé par :** Keevo SAS  

> ⚠️ *Ce document a été rédigé à titre indicatif sur la base des spécifications techniques de l'application. Il est fortement recommandé de le faire réviser par un juriste spécialisé en droit numérique avant toute mise en production.*

---

## Table des matières

### Politique de Confidentialité
1. [Identité du Responsable de Traitement](#1-identité-du-responsable-de-traitement)
2. [Données Collectées](#2-données-collectées)
3. [Finalités et Bases Légales des Traitements](#3-finalités-et-bases-légales-des-traitements)
4. [Isolation Multi-Tenant et Sécurité des Données](#4-isolation-multi-tenant-et-sécurité-des-données)
5. [Conservation des Données](#5-conservation-des-données)
6. [Partage et Transfert de Données](#6-partage-et-transfert-de-données)
7. [Vos Droits (RGPD)](#7-vos-droits-rgpd)
8. [Sécurité Technique](#8-sécurité-technique)
9. [Notifications Push et Firebase](#9-notifications-push-et-firebase)
10. [Mineurs](#10-mineurs)
11. [Modifications de la Politique](#11-modifications-de-la-politique)
12. [Contact DPO](#12-contact-dpo)

### Conditions Générales d'Utilisation
13. [Objet et Acceptation](#13-objet-et-acceptation)
14. [Description du Service](#14-description-du-service)
15. [Inscription et Comptes Utilisateurs](#15-inscription-et-comptes-utilisateurs)
16. [Plans d'Abonnement et Facturation](#16-plans-dabonnement-et-facturation)
17. [Obligations et Responsabilités de l'Utilisateur](#17-obligations-et-responsabilités-de-lutilisateur)
18. [Propriété Intellectuelle](#18-propriété-intellectuelle)
19. [Disponibilité et Garanties du Service](#19-disponibilité-et-garanties-du-service)
20. [Limitation de Responsabilité](#20-limitation-de-responsabilité)
21. [Piste d'Audit et Traçabilité](#21-piste-daudit-et-traçabilité)
22. [Résiliation et Suppression de Compte](#22-résiliation-et-suppression-de-compte)
23. [Droit Applicable et Juridiction](#23-droit-applicable-et-juridiction)
24. [Dispositions Finales](#24-dispositions-finales)

---

# PARTIE I — POLITIQUE DE CONFIDENTIALITÉ

## 1. Identité du Responsable de Traitement

**Keevo SAS** (ci-après « Keevo », « nous » ou « notre société »)  
Responsable du traitement des données à caractère personnel collectées via l'application mobile **Keevo** et ses services associés.

Pour tout exercice de vos droits ou toute question relative à la protection de vos données, vous pouvez nous contacter à :

- **E-mail :** privacy@keevo.app  
- **Adresse postale :** [Adresse du siège social — à compléter]

---

## 2. Données Collectées

Keevo collecte uniquement les données strictement nécessaires à la fourniture du service de gestion d'inventaire. Nous distinguons trois catégories :

### 2.1 Données d'identité et de compte

| Donnée | Obligatoire | Source |
|--------|------------|--------|
| Nom et prénom | Oui | Saisie lors de l'inscription ou invitation |
| Adresse e-mail professionnelle | Oui | Saisie lors de l'inscription |
| Numéro de téléphone | Optionnel | Saisie utilisateur (pour notifications WhatsApp) |
| Mot de passe (haché BCrypt) | Oui | Saisie utilisateur |
| Rôle dans l'organisation | Oui | Attribution par l'administrateur du tenant |
| Photo de profil | Non | Téléversement optionnel |

### 2.2 Données métier (données de votre organisation)

Ces données appartiennent à votre organisation (tenant). Elles sont traitées par Keevo en qualité de **sous-traitant** au sens de l'article 28 du RGPD :

- Produits, SKU, prix, marges
- Stocks, seuils d'alerte, mouvements d'inventaire
- Informations clients et fournisseurs (nom, coordonnées commerciales)
- Boutiques et entrepôts (nom, adresse, configuration)
- Données de caisse et de facturation
- Rapports et exports générés

### 2.3 Données techniques et de navigation

| Donnée | Finalité |
|--------|----------|
| Adresse IP | Sécurité, détection de fraude |
| Token Firebase Cloud Messaging (FCM) | Envoi de notifications push |
| Identifiant de session JWT | Authentification sécurisée |
| Logs d'accès (horodatage, action, utilisateur) | Piste d'audit immuable |
| Version de l'application et OS de l'appareil | Compatibilité et débogage |
| Données de connectivité réseau | Gestion du mode hors-ligne |

### 2.4 Ce que nous ne collectons PAS

- Données de localisation GPS en continu
- Contacts du téléphone
- Historique de navigation extérieur à l'application
- Données biométriques
- Données de paiement directes (traitées par notre partenaire de paiement certifié PCI-DSS)

---

## 3. Finalités et Bases Légales des Traitements

| Finalité | Base légale (RGPD Art. 6) | Détail |
|----------|--------------------------|--------|
| Fourniture du service de gestion d'inventaire | Exécution du contrat (6.1.b) | Cœur du service souscrit |
| Authentification et contrôle d'accès | Exécution du contrat (6.1.b) | Sécurisation des comptes |
| Envoi de notifications push et alertes stock | Intérêt légitime (6.1.f) / Consentement (6.1.a) | Alertes métier critiques |
| Tenue de la piste d'audit immuable | Obligation légale (6.1.c) + Intérêt légitime (6.1.f) | Traçabilité et conformité |
| Facturation et gestion des abonnements | Exécution du contrat (6.1.b) | Plans et limites |
| Amélioration du service et débogage | Intérêt légitime (6.1.f) | Logs techniques anonymisés |
| Prévention de la fraude et sécurité | Intérêt légitime (6.1.f) | Détection d'anomalies |
| Support client | Exécution du contrat (6.1.b) | Résolution d'incidents |

---

## 4. Isolation Multi-Tenant et Sécurité des Données

Keevo est architecturé comme une plateforme **multi-tenant avec isolation stricte des données** :

### 4.1 Isolation au niveau base de données

- Chaque organisation (tenant) dispose de son **schéma PostgreSQL dédié**, totalement isolé de celui des autres tenants.
- Le `TenantContext` est propagé par fil d'exécution (ThreadLocal) : aucune requête ne peut accéder à des données d'un autre tenant.
- L'approvisionnement du schéma (`TenantSchemaProvisioner`) et les migrations (`TenantSchemaSyncService`) sont exécutés par tenant, garantissant l'intégrité logique et physique de la séparation.

### 4.2 Chiffrement local (application mobile)

- La base de données locale sur l'appareil mobile est chiffrée via **SQLCipher** (chiffrement AES-256).
- Les données de votre organisation stockées localement pour le mode hors-ligne sont protégées même en cas de perte ou vol de l'appareil.

### 4.3 Chiffrement en transit

- Toutes les communications entre l'application mobile et le serveur sont chiffrées via **HTTPS/TLS 1.3**.
- Les tokens JWT d'authentification sont signés et ont une durée de vie limitée.

---

## 5. Conservation des Données

| Catégorie | Durée de conservation | Justification |
|-----------|----------------------|---------------|
| Données de compte actif | Durée de l'abonnement + 30 jours | Continuité de service |
| Données après résiliation | 90 jours (téléchargement possible) | Droit à la portabilité |
| Suppression définitive | À l'issue des 90 jours | Droit à l'effacement |
| Piste d'audit (logs immuables) | 5 ans à compter de l'événement | Obligation légale et contractuelle |
| Données de facturation | 10 ans | Obligations comptables légales |
| Logs techniques de sécurité | 12 mois | Détection et investigation d'incidents |
| Données de sessions expirées | 24 heures | Sécurité |

> **Note :** La piste d'audit étant immuable par conception (aucune modification ni suppression), les entrées d'audit ne peuvent être effacées avant l'expiration de leur durée légale, conformément aux exigences réglementaires.

---

## 6. Partage et Transfert de Données

### 6.1 Sous-traitants techniques

Keevo fait appel à des sous-traitants techniques soigneusement sélectionnés, liés par des accords de traitement des données conformes au RGPD :

| Sous-traitant | Rôle | Localisation |
|---------------|------|-------------|
| **Google Firebase** (Cloud Messaging) | Notifications push | UE / USA (clauses contractuelles types) |
| **Hébergeur cloud** (à préciser) | Infrastructure serveur et base de données | UE (de préférence) |
| **Prestataire de paiement** (à préciser) | Facturation et abonnements | Certifié PCI-DSS |
| **Service e-mail transactionnel** (à préciser) | Envoi d'e-mails système | UE / clauses contractuelles types |

### 6.2 Transferts hors UE

Pour les transferts vers des pays hors Espace Économique Européen (notamment Firebase/Google aux États-Unis), Keevo s'appuie sur les **clauses contractuelles types** (CCT) approuvées par la Commission européenne, conformément à l'article 46 du RGPD.

### 6.3 Ce que nous ne ferons jamais

- **Pas de vente** de vos données à des tiers
- **Pas de partage** à des fins publicitaires
- **Pas d'accès** de nos équipes aux données métier de votre organisation sans votre autorisation explicite (sauf obligation légale)

### 6.4 Obligations légales

Keevo peut être amenée à divulguer des données aux autorités compétentes en cas d'obligation légale, d'ordonnance judiciaire ou pour protéger ses droits légaux, dans le strict respect des procédures légales applicables.

---

## 7. Vos Droits (RGPD)

En tant que personne concernée résidant dans l'Union Européenne, vous bénéficiez des droits suivants au titre du Règlement Général sur la Protection des Données (RGPD n°2016/679) :

| Droit | Description | Délai de réponse |
|-------|-------------|-----------------|
| **Droit d'accès** (Art. 15) | Obtenir une copie de vos données personnelles | 30 jours |
| **Droit de rectification** (Art. 16) | Corriger des données inexactes ou incomplètes | 30 jours |
| **Droit à l'effacement** (Art. 17) | Demander la suppression de vos données | 30 jours (hors données d'audit légales) |
| **Droit à la limitation** (Art. 18) | Limiter temporairement le traitement | 30 jours |
| **Droit à la portabilité** (Art. 20) | Recevoir vos données dans un format structuré | 30 jours |
| **Droit d'opposition** (Art. 21) | S'opposer aux traitements basés sur l'intérêt légitime | Immédiat |
| **Droit de retirer le consentement** (Art. 7) | Retirer un consentement donné à tout moment | Immédiat |

### Comment exercer vos droits

Envoyez votre demande à **privacy@keevo.app** en précisant :
- Votre identité (nom, prénom, e-mail de compte)
- Le droit que vous souhaitez exercer
- Tout justificatif nécessaire à la vérification d'identité

Vous disposez également du droit d'introduire une **réclamation auprès de la CNIL** (Commission Nationale de l'Informatique et des Libertés) — [www.cnil.fr](https://www.cnil.fr) — si vous estimez que le traitement de vos données ne respecte pas la réglementation.

---

## 8. Sécurité Technique

Keevo met en œuvre un ensemble de mesures techniques et organisationnelles pour protéger vos données :

### Mesures techniques

- **Chiffrement au repos** : Base de données chiffrée, données locales SQLCipher (AES-256)
- **Chiffrement en transit** : HTTPS/TLS 1.3 obligatoire sur toutes les connexions
- **Authentification forte** : JWT avec rotation des tokens, sessions à durée limitée
- **Isolation des données** : Schéma PostgreSQL dédié par tenant (isolation physique)
- **Piste d'audit immuable** : Journalisation non modifiable de toutes les actions sensibles
- **Gestion des accès** : Contrôle d'accès basé sur les rôles (RBAC) — Administrateur, Gestionnaire, Employé, Comptable
- **Validation des entrées** : Toutes les données saisies sont validées et assainies côté serveur

### Mesures organisationnelles

- Politique de gestion des accès aux données de production
- Formation du personnel aux bonnes pratiques de sécurité
- Procédure de réponse aux incidents de sécurité
- Revue de sécurité régulière du code et de l'infrastructure

### En cas de violation de données

En cas de violation de données à caractère personnel susceptible d'engendrer un risque pour vos droits et libertés, Keevo s'engage à :
1. Notifier la **CNIL dans les 72 heures** suivant la découverte de l'incident (Art. 33 RGPD)
2. Vous informer **sans délai injustifié** si la violation est susceptible d'engendrer un risque élevé (Art. 34 RGPD)

---

## 9. Notifications Push et Firebase

Keevo utilise **Firebase Cloud Messaging (FCM)** de Google pour vous envoyer des notifications push sur votre appareil mobile.

### Ce que Firebase collecte via notre intégration

- Token d'appareil FCM (identifiant anonymisé de l'appareil, non lié à votre identité Google)
- Confirmation de réception des notifications

### Types de notifications envoyées

- Alertes de seuil de stock bas ou rupture
- Invitations et changements de rôle dans votre organisation
- Notifications système critiques (maintenance, sécurité)

### Gestion de vos préférences

Vous pouvez désactiver les notifications push à tout moment :
- Depuis les **paramètres de l'application Keevo** > Notifications
- Depuis les **paramètres de votre appareil** (iOS/Android)

La désactivation des notifications n'affecte pas votre accès au service.

Pour plus d'informations sur les pratiques de confidentialité de Firebase/Google : [firebase.google.com/support/privacy](https://firebase.google.com/support/privacy)

---

## 10. Mineurs

Le service Keevo est exclusivement destiné à un usage **professionnel et commercial**. Il n'est pas conçu pour, ni destiné aux, personnes de moins de 18 ans. Nous ne collectons pas sciemment de données personnelles provenant de mineurs. Si vous pensez qu'un mineur nous a transmis des données personnelles, contactez-nous à privacy@keevo.app.

---

## 11. Modifications de la Politique

Keevo se réserve le droit de modifier la présente Politique de Confidentialité pour refléter des évolutions légales, réglementaires ou fonctionnelles. En cas de modification substantielle :

- Vous serez informé par **e-mail** et/ou **notification in-app** au moins **30 jours avant** l'entrée en vigueur
- Un résumé des changements vous sera communiqué
- La poursuite de l'utilisation du service après la date d'entrée en vigueur vaut acceptation

L'historique des versions est disponible sur demande à privacy@keevo.app.

---

## 12. Contact DPO

Pour toute question relative à la protection de vos données personnelles :

**Délégué à la Protection des Données (DPO)**  
Keevo SAS  
E-mail : **privacy@keevo.app**  
Objet : [DPO] + description de votre demande

---

---

# PARTIE II — CONDITIONS GÉNÉRALES D'UTILISATION (CGU)

---

## 13. Objet et Acceptation

### 13.1 Objet

Les présentes Conditions Générales d'Utilisation (ci-après « CGU ») régissent l'accès et l'utilisation de l'application mobile et des services web **Keevo** (ci-après « le Service ») fournis par **Keevo SAS** (ci-après « Keevo », « l'Éditeur »).

Keevo est un service de **gestion d'inventaire en temps réel** en mode SaaS (Software as a Service), permettant aux organisations de gérer leurs stocks, produits, clients, fournisseurs, boutiques et entrepôts au travers d'une interface mobile et d'un tableau de bord centralisé.

### 13.2 Acceptation

L'utilisation du Service implique l'acceptation pleine et entière des présentes CGU. Si vous n'acceptez pas ces conditions, vous ne devez pas utiliser le Service.

Ces CGU s'appliquent à :
- **L'Administrateur du Tenant** : toute personne physique ou morale ayant créé un compte organisationnel sur Keevo
- **Les Membres** : tout utilisateur invité à rejoindre une organisation au sein de Keevo

L'Administrateur du Tenant garantit que les Membres qu'il invite ont pris connaissance et accepté les présentes CGU.

---

## 14. Description du Service

### 14.1 Fonctionnalités principales

Le Service Keevo offre, selon le plan souscrit :

**Gestion des produits et stocks**
- Création, édition et archivage de produits (avec SKU, codes-barres, variantes)
- Moteur de calcul de prix et de marges
- Suivi des seuils de stock et alertes automatiques
- Historique des mouvements de stock
- Import de produits via fichier CSV

**Gestion multi-sites**
- Configuration de boutiques et entrepôts
- Suivi des stocks par site
- Transferts inter-sites

**Gestion commerciale**
- Répertoire clients et fournisseurs
- Gestion des commandes et bon de réception
- Génération de factures et devis

**Administration et sécurité**
- Gestion multi-utilisateurs avec rôles et permissions
- Authentification sécurisée (JWT, sessions gérées)
- Piste d'audit immuable de toutes les actions
- Connectivité hors-ligne avec synchronisation automatique

**Rapports et exports**
- Tableaux de bord et indicateurs clés
- Export des données en CSV et autres formats

### 14.2 Disponibilité géographique

Le Service est accessible depuis tout pays disposant d'un accès internet. Keevo ne garantit pas la conformité du Service aux législations locales de tous les pays. Il appartient à l'utilisateur de vérifier que l'utilisation du Service est conforme à la réglementation applicable dans son pays.

### 14.3 Évolutions du Service

Keevo se réserve le droit de faire évoluer, améliorer ou modifier toute fonctionnalité du Service à tout moment. Les modifications substantielles feront l'objet d'une notification préalable de **30 jours**.

---

## 15. Inscription et Comptes Utilisateurs

### 15.1 Création d'un compte

Pour utiliser le Service, l'Administrateur doit :
1. Créer un compte en fournissant des informations exactes et complètes
2. Configurer son organisation (tenant) via l'assistant d'intégration
3. Choisir un plan d'abonnement

### 15.2 Identifiants et sécurité

- Vous êtes **seul responsable** de la confidentialité de vos identifiants (e-mail + mot de passe)
- Vous vous engagez à ne pas partager vos identifiants et à utiliser des mots de passe robustes
- En cas de perte ou de compromission présumée de vos identifiants, vous devez **immédiatement** en informer Keevo et réinitialiser votre mot de passe
- Keevo ne vous demandera **jamais** votre mot de passe par e-mail ou téléphone

### 15.3 Connexion en deux étapes (Login Multi-Tenant)

L'architecture Keevo supporte la **multi-appartenance** : un même e-mail peut être associé à plusieurs organisations. Le processus de connexion à deux étapes vous permettra de sélectionner l'organisation active. Vous êtes responsable de vos actions dans chacune des organisations auxquelles vous appartenez.

### 15.4 Exactitude des informations

Vous vous engagez à fournir et maintenir des informations exactes, complètes et à jour. Keevo se réserve le droit de suspendre ou résilier tout compte basé sur des informations frauduleuses.

### 15.5 Comptes inactifs

Un compte sans aucune activité pendant **12 mois consécutifs** sur un abonnement gratuit ou en période d'essai pourra faire l'objet d'une notification de suppression. Le compte sera supprimé 30 jours après ladite notification sauf reconnexion de votre part.

---

## 16. Plans d'Abonnement et Facturation

### 16.1 Plans disponibles

Keevo propose différents plans d'abonnement avec des limites associées (nombre d'utilisateurs, boutiques, produits, volume de données). Les plans disponibles et leurs caractéristiques sont décrits sur la page de tarification officielle de Keevo.

### 16.2 Période d'essai

Une période d'essai gratuite peut être proposée lors de la création d'un nouveau compte. À l'issue de la période d'essai :
- Sans souscription à un plan payant, l'accès est automatiquement réduit aux fonctionnalités du plan gratuit (si disponible) ou suspendu
- Vos données sont conservées pendant **30 jours** après la fin de l'essai pour vous permettre de souscrire

### 16.3 Facturation

- L'abonnement est facturé selon la périodicité choisie (mensuelle ou annuelle) en début de période
- Les paiements sont traités par notre partenaire de paiement sécurisé (certifié PCI-DSS)
- Keevo ne stocke pas vos données de carte bancaire
- Les factures vous sont transmises par e-mail et sont disponibles dans votre espace client

### 16.4 Limites de plan et dépassement

- Chaque plan est assorti de limites techniques (nombre d'utilisateurs, de boutiques, de produits, etc.)
- Vous serez averti lorsque vous approchez des limites de votre plan
- En cas de dépassement durable des limites, Keevo pourra vous inviter à passer à un plan supérieur ou bloquer les nouvelles créations sans supprimer les données existantes

### 16.5 Modifications tarifaires

Keevo se réserve le droit de modifier ses tarifs. Toute modification tarifaire vous sera notifiée par **e-mail avec un préavis minimum de 60 jours**. La poursuite de l'utilisation du Service après la date d'effet vaut acceptation des nouveaux tarifs. En cas de désaccord, vous pouvez résilier votre abonnement avant la date d'effet.

### 16.6 Remboursements

- Les abonnements mensuels ne font pas l'objet de remboursement au prorata
- Les abonnements annuels peuvent faire l'objet d'un remboursement au prorata en cas de résiliation dans les **14 jours** suivant la facturation (droit de rétractation légal)
- Au-delà de cette période, aucun remboursement n'est accordé pour la période en cours

---

## 17. Obligations et Responsabilités de l'Utilisateur

### 17.1 Utilisation licite

Vous vous engagez à utiliser le Service exclusivement à des fins légales, loyales et dans le respect des présentes CGU. Il est notamment interdit de :

- Utiliser le Service pour stocker, distribuer ou traiter des contenus illicites
- Tenter d'accéder non autorisé aux comptes d'autres utilisateurs ou à l'infrastructure Keevo
- Contourner les mécanismes de contrôle d'accès ou d'isolation entre tenants
- Introduire des virus, logiciels malveillants ou tout code nuisible
- Procéder à de l'ingénierie inverse, décompilation ou désassemblage du Service
- Utiliser des scripts ou robots automatisés susceptibles de surcharger l'infrastructure
- Revendre, sous-licencier ou exploiter commercialement le Service sans autorisation écrite de Keevo
- Utiliser le Service pour des activités frauduleuses, de blanchiment d'argent ou financement du terrorisme
- Tenter d'identifier ou d'extraire les données d'autres tenants

### 17.2 Responsabilité des données saisies

- Vous êtes **seul responsable** des données que vous saisissez, importez ou transmettez via le Service
- Vous garantissez disposer de toutes les autorisations nécessaires pour traiter les données de vos clients, fournisseurs et employés via le Service
- En qualité de Responsable de Traitement au sens du RGPD, vous êtes responsable de la conformité légale des données de votre organisation

### 17.3 Responsabilité de l'Administrateur du Tenant

L'Administrateur est responsable de :
- La gestion des membres de son organisation (invitations, rôles, révocations)
- Le respect des présentes CGU par l'ensemble des membres de son organisation
- La configuration correcte des paramètres de sécurité et des rôles

### 17.4 Signalement d'incidents

Tout incident de sécurité, faille ou comportement anormal du Service doit être signalé immédiatement à **security@keevo.app**. Aucune divulgation publique ne doit être faite avant coordination avec Keevo (politique de *responsible disclosure*).

---

## 18. Propriété Intellectuelle

### 18.1 Droits de Keevo

Le Service Keevo (application mobile, backend, algorithmes, interfaces, documentation, marques, logos) est la propriété exclusive de **Keevo SAS** et est protégé par les lois sur la propriété intellectuelle. Toute reproduction, représentation, modification ou exploitation non autorisée est strictement interdite.

### 18.2 Droits de l'Utilisateur sur ses données

Vous conservez l'**intégralité des droits de propriété** sur toutes les données que vous saisissez dans le Service (produits, stocks, clients, fournisseurs, etc.). Ces données vous appartiennent.

Keevo ne revendique aucun droit de propriété sur vos données et s'engage à ne les exploiter qu'aux fins décrites dans la Politique de Confidentialité.

### 18.3 Licence d'utilisation limitée

Keevo vous accorde une licence d'utilisation **non exclusive, non transférable et révocable** du Service, pour vos seuls besoins internes et professionnels, pendant la durée de votre abonnement.

### 18.4 Retours et suggestions

Si vous transmettez à Keevo des suggestions, idées d'amélioration ou retours (feedback), vous accordez à Keevo le droit de les utiliser librement sans obligation de compensation, tout en conservant la confidentialité de votre identité si vous le souhaitez.

---

## 19. Disponibilité et Garanties du Service

### 19.1 Disponibilité cible

Keevo s'engage à maintenir une disponibilité cible de **99,5 % mensuel** (hors maintenances planifiées), calculée sur le temps d'accès au Service.

### 19.2 Maintenances

Keevo peut procéder à des maintenances planifiées, de préférence pendant les plages horaires à faible trafic. Les maintenances planifiées significatives (> 30 minutes) feront l'objet d'une notification **48 heures à l'avance**.

### 19.3 Mode hors-ligne

L'application Keevo intègre un **mode hors-ligne** permettant la consultation et la mise à jour des données localement en cas d'indisponibilité du réseau. La synchronisation s'effectue automatiquement lors du rétablissement de la connexion. Keevo ne garantit pas l'absence de conflits de synchronisation dans des scénarios d'utilisation hors-ligne prolongée par plusieurs utilisateurs simultanément.

### 19.4 Absence de garantie d'adéquation

Le Service est fourni **« tel quel »** et **« selon disponibilité »**. Keevo ne garantit pas que le Service répondra à tous vos besoins spécifiques ou sera exempt de toute erreur. Des sauvegardes régulières de vos données sont effectuées par Keevo, mais il est recommandé d'effectuer régulièrement vos propres exports via les fonctionnalités d'export du Service.

---

## 20. Limitation de Responsabilité

### 20.1 Exclusions de responsabilité

Keevo ne pourra être tenu responsable des dommages résultant de :

- Votre utilisation non conforme du Service
- L'inexactitude des données que vous avez saisies
- Une interruption de service liée à des causes extérieures (panne réseau, force majeure, attaque informatique d'ampleur exceptionnelle)
- La perte de données résultant d'une erreur de votre part ou d'une utilisation non recommandée du mode hors-ligne
- Les décisions commerciales ou opérationnelles prises sur la base des informations fournies par le Service
- L'indisponibilité temporaire de services tiers (Firebase, hébergeur)

### 20.2 Plafond de responsabilité

Dans les limites permises par la loi applicable et en dehors des préjudices corporels ou des fraudes, la responsabilité totale de Keevo envers vous ne pourra excéder le **montant total des sommes effectivement payées par vous à Keevo au cours des 12 derniers mois** précédant la survenance du dommage.

### 20.3 Dommages indirects

Keevo ne pourra en aucun cas être tenu responsable de dommages indirects, consécutifs, ou de pertes de profits, pertes de clientèle, pertes de données ou atteinte à la réputation, même si Keevo a été averti de la possibilité de tels dommages.

---

## 21. Piste d'Audit et Traçabilité

### 21.1 Journalisation des actions

Le Service enregistre de manière **immuable** un journal d'audit de toutes les actions effectuées par les utilisateurs au sein de leur organisation : créations, modifications, suppressions, connexions, changements de rôles, etc.

### 21.2 Utilisation de la piste d'audit

La piste d'audit est destinée à :
- Vous permettre de reconstituer l'historique des opérations de votre organisation
- Détecter et investiguer des actions non autorisées
- Répondre à des obligations légales ou réglementaires de traçabilité

### 21.3 Immutabilité

Les entrées de la piste d'audit ne peuvent être modifiées ni supprimées par les utilisateurs, y compris les Administrateurs. Cette immutabilité est un élément essentiel de sécurité du Service. La conservation de la piste d'audit est régie par les durées définies à l'Article 5 de la Politique de Confidentialité.

---

## 22. Résiliation et Suppression de Compte

### 22.1 Résiliation par l'Utilisateur

Vous pouvez résilier votre abonnement à tout moment depuis les paramètres de votre compte. La résiliation prend effet :
- **Abonnement mensuel** : à la fin de la période mensuelle en cours
- **Abonnement annuel** : à la date d'anniversaire annuelle suivante (sauf droit de rétractation)

Vous conservez l'accès au Service jusqu'à la fin de la période payée.

### 22.2 Résiliation par Keevo

Keevo se réserve le droit de suspendre ou résilier votre accès, avec un préavis de **7 jours** (sauf violation grave), en cas de :
- Non-paiement après relance
- Violation des présentes CGU
- Utilisation frauduleuse ou illicite du Service
- Inactivité prolongée (compte hors abonnement actif)

En cas de violation grave (atteinte à la sécurité du Service, tentative d'accès non autorisé, activité illégale), Keevo peut suspendre immédiatement et sans préavis l'accès au compte concerné.

### 22.3 Export et récupération des données après résiliation

- Dès la résiliation, vous disposez d'un délai de **90 jours** pour exporter vos données via les fonctionnalités d'export du Service ou en faisant une demande à support@keevo.app
- À l'issue de ce délai, vos données sont définitivement supprimées de nos systèmes, à l'exception des données soumises à des obligations légales de conservation (piste d'audit, données de facturation)

### 22.4 Suppression du compte

La suppression du compte entraîne la suppression définitive de toutes vos données personnelles dans les délais définis à l'Article 5 de la Politique de Confidentialité.

---

## 23. Droit Applicable et Juridiction

### 23.1 Droit applicable

Les présentes CGU et tout litige relatif à leur interprétation ou à leur exécution sont régis par le **droit français**.

### 23.2 Médiation

En cas de litige entre Keevo et un utilisateur consommateur (personne physique agissant à des fins non professionnelles), et à défaut de résolution amiable, vous avez le droit de recourir gratuitement à un médiateur de la consommation conformément aux articles L.612-1 et suivants du Code de la consommation.

### 23.3 Juridiction compétente

À défaut de résolution amiable dans un délai de 30 jours à compter de la saisine écrite de Keevo, tout litige relatif aux présentes CGU sera soumis à la compétence exclusive des **tribunaux de [ville du siège social — à compléter]**, nonobstant pluralité de défendeurs ou appel en garantie.

---

## 24. Dispositions Finales

### 24.1 Intégralité de l'accord

Les présentes CGU, ainsi que la Politique de Confidentialité et tout accord spécifique signé avec Keevo (notamment les Accords de Traitement des Données pour les entreprises soumises au RGPD), constituent l'intégralité de l'accord entre vous et Keevo concernant le Service.

### 24.2 Accord de Traitement des Données (ATD)

Pour les organisations agissant en qualité de Responsables de Traitement et souhaitant formaliser la relation de sous-traitance avec Keevo conformément à l'article 28 du RGPD, un **Accord de Traitement des Données (ATD)** est disponible sur demande à privacy@keevo.app.

### 24.3 Divisibilité

Si une clause des présentes CGU est déclarée invalide ou inapplicable par une juridiction compétente, les autres clauses demeureront en vigueur.

### 24.4 Non-renonciation

Le fait pour Keevo de ne pas exercer un droit ou de ne pas invoquer une disposition des présentes CGU ne constitue pas une renonciation à ce droit ou à cette disposition.

### 24.5 Langue

En cas de traduction des présentes CGU dans une autre langue, la version française fait foi.

### 24.6 Modifications des CGU

Keevo se réserve le droit de modifier les présentes CGU. Toute modification substantielle sera notifiée par e-mail et/ou notification in-app avec un préavis de **30 jours**. La poursuite de l'utilisation du Service vaut acceptation des nouvelles CGU.

### 24.7 Contact

Pour toute question relative aux présentes CGU :  
**E-mail :** legal@keevo.app  
**Support :** support@keevo.app  
**Sécurité :** security@keevo.app  
**Données personnelles (DPO) :** privacy@keevo.app

---

*Keevo SAS — [Adresse du siège — à compléter] — [N° SIRET — à compléter] — [N° TVA intracommunautaire — à compléter]*

*© 2026 Keevo SAS. Tous droits réservés.*
