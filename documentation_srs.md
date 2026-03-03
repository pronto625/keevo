# Spécification des Exigences Logicielles (SRS)
# Système de Gestion d'Inventaire en Temps Réel

## Table des matières
1. [Introduction](#introduction)
   - 1.1 [Objectif](#objectif)
   - 1.2 [Portée du projet](#portée-du-projet)
   - 1.3 [Définitions et acronymes](#définitions-et-acronymes)
2. [Description générale](#description-générale)
   - 2.1 [Perspective du produit](#perspective-du-produit)
   - 2.2 [Fonctionnalités du produit](#fonctionnalités-du-produit)
   - 2.3 [Caractéristiques des utilisateurs](#caractéristiques-des-utilisateurs)
3. [Diagrammes du système](#diagrammes-du-système)
   - 3.1 [Diagramme de cas d'utilisation](#diagramme-de-cas-dutilisation)
   - 3.2 [Diagramme de classe](#diagramme-de-classe)
   - 3.3 [Diagrammes de séquence](#diagrammes-de-séquence)
   - 3.4 [Diagramme de flux](#diagramme-de-flux)
4. [Exigences spécifiques](#exigences-spécifiques)
   - 4.1 [Exigences fonctionnelles](#exigences-fonctionnelles)
   - 4.2 [Exigences non fonctionnelles](#exigences-non-fonctionnelles)
5. [Interfaces externes](#interfaces-externes)
   - 5.1 [Interfaces utilisateur](#interfaces-utilisateur)
   - 5.2 [Interfaces logicielles](#interfaces-logicielles)

## 1. Introduction

### 1.1 Objectif
Ce document présente les spécifications détaillées pour un système de gestion d'inventaire en temps réel. Le système est conçu pour permettre une gestion efficace des stocks dans un ou plusieurs points de vente, avec un accent particulier sur le suivi en temps réel et la génération rapide d'inventaires.

### 1.2 Portée du projet
Le système couvre :
- Gestion des stocks en temps réel
- Suivi des inventaires multi-sites
- Génération de rapports d'inventaire
- Gestion des alertes de stock
- Interface d'administration centralisée

### 1.3 Définitions et acronymes
- **SKU** : Stock Keeping Unit
- **POS** : Point of Sale (Point de vente)
- **RT** : Real Time (Temps réel)
- **WMS** : Warehouse Management System

## 2. Description générale

### 2.1 Perspective du produit
Le système de gestion d'inventaire est une application autonome qui peut être intégrée avec d'autres systèmes de gestion d'entreprise. Il est conçu pour fonctionner dans le cloud, permettant un accès depuis plusieurs points de vente.

### 2.2 Fonctionnalités du produit
- Suivi en temps réel des stocks
- Gestion multi-magasins
- Génération de rapports d'inventaire
- Alertes de stock bas
- Historique des mouvements
- Dashboard administrateur
- Gestion des utilisateurs et des rôles

### 2.3 Caractéristiques des utilisateurs
- **Administrateur système** : Gestion complète du système
- **Gestionnaire de magasin** : Gestion des stocks d'un point de vente
- **Employé** : Consultation et mise à jour des stocks
- **Comptable** : Accès aux rapports et à l'historique

## 3. Diagrammes du système

### 3.1 Diagramme de cas d'utilisation
```mermaid
graph TD
    A[Administrateur] -->|Gérer utilisateurs| B((Gestion des utilisateurs))
    A -->|Configurer système| C((Configuration système))
    A -->|Voir rapports globaux| D((Rapports globaux))
    E[Gestionnaire] -->|Gérer stock| F((Gestion des stocks))
    E -->|Voir rapports locaux| G((Rapports locaux))
    H[Employé] -->|Mettre à jour stock| F
    H -->|Scanner produits| I((Scan produits))
    J[Comptable] -->|Accéder historique| K((Historique))
    J -->|Générer rapports| D
```

### 3.2 Diagramme de classe
```mermaid
classDiagram
    class Tenant {
        +String id
        +String nom
        +String description
        +String domaine
        +boolean actif
        +Date dateCreation
        +String logo
        +String theme
        +Map<String,String> configuration
        +activerTenant()
        +desactiverTenant()
        +configurerTheme()
    }
    class Organisation {
        +String id
        +Tenant tenant
        +String nom
        +String adresse
        +String telephone
        +String email
        +boolean principal
        +List<PointDeVente> pointsVente
        +gererPointsVente()
        +configurerOrganisation()
    }
    class Licence {
        +String id
        +Tenant tenant
        +String type
        +Date dateDebut
        +Date dateFin
        +int nombreUtilisateursMax
        +int nombrePointsVenteMax
        +boolean actif
        +double prix
        +String statut
        +verifierValidite()
        +renouveler()
        +mettreAJour()
    }
    class Facturation {
        +String id
        +Licence licence
        +double montant
        +Date dateFacturation
        +Date dateEcheance
        +String statut
        +String methodePaiement
        +genererFacture()
        +enregistrerPaiement()
    }
    class Produit {
        +String id
        +Organisation organisation
        +String nom
        +String description
        +String codeBarre
        +String reference
        +double prixAchat
        +double prixVente
        +double tva
        +int quantiteMinimale
        +int quantiteOptimale
        +boolean actif
        +Date dateCreation
        +Date derniereMaj
        +Image photo
        +updateStock()
        +getDetails()
        +calculerMarge()
        +verifierSeuils()
    }
    class Categorie {
        +String id
        +Tenant tenant
        +String nom
        +String description
        +Categorie parentId
        +boolean actif
        +ajouterSousCategorie()
        +getProduits()
        +getArborescence()
    }
    class CaracteristiqueProduit {
        +String id
        +Tenant tenant
        +String nom
        +String valeur
        +String unite
        +boolean requis
        +valider()
    }
    class Fournisseur {
        +String id
        +Tenant tenant
        +String nom
        +String adresse
        +String telephone
        +String email
        +String siteWeb
        +boolean actif
        +List<Produit> produits
        +ajouterProduit()
        +getProduits()
        +getHistoriqueCommandes()
    }
    class PrixProduit {
        +String id
        +Tenant tenant
        +Produit produit
        +double prix
        +Date dateDebut
        +Date dateFin
        +String type
        +boolean actif
        +appliquerPromotion()
        +verifierValidite()
    }
    class Stock {
        +String id
        +Tenant tenant
        +Produit produit
        +int quantite
        +String emplacement
        +String lotNumber
        +Date dateExpiration
        +Date derniereMaj
        +ajusterStock()
        +verifierNiveau()
        +verifierPeremption()
    }
    class PointDeVente {
        +String id
        +Tenant tenant
        +String nom
        +String adresse
        +String telephone
        +String email
        +boolean actif
        +List<Stock> stocks
        +getInventaire()
        +ajouterStock()
        +genererRapport()
    }
    class Mouvement {
        +String id
        +Tenant tenant
        +Date date
        +String type
        +int quantite
        +Produit produit
        +String reference
        +String motif
        +double prixUnitaire
        +enregistrer()
        +annuler()
    }
    class Utilisateur {
        +String id
        +Tenant tenant
        +String nom
        +String email
        +String motDePasse
        +boolean actif
        +Date derniereConnexion
        +authentifier()
        +getPermissions()
        +modifierProfil()
    }
    class Role {
        +String id
        +Tenant tenant
        +String nom
        +String description
        +List<Permission> permissions
        +ajouterPermission()
        +retirerPermission()
    }
    class Permission {
        +String id
        +Tenant tenant
        +String nom
        +String description
        +String ressource
        +String action
        +verifierAcces()
    }
    class Session {
        +String id
        +Tenant tenant
        +Utilisateur utilisateur
        +Date dateDebut
        +Date dateFin
        +String token
        +verifierValidite()
        +terminer()
    }
    class JournalActivite {
        +String id
        +Tenant tenant
        +Utilisateur utilisateur
        +Date date
        +String action
        +String details
        +enregistrer()
    }
    
    Tenant "1" -- "1" Licence
    Tenant "1" -- "n" Organisation
    Organisation "1" -- "n" PointDeVente
    Organisation "1" -- "n" Utilisateur
    Organisation "1" -- "n" Produit
    Organisation "1" -- "n" Categorie
    Organisation "1" -- "n" Fournisseur
    
    Produit "1" -- "n" Stock
    Stock "n" -- "1" PointDeVente
    Mouvement "n" -- "1" Produit
    PointDeVente "1" -- "n" Utilisateur
    Utilisateur "n" -- "n" Role
    Role "1" -- "n" Permission
    Utilisateur "1" -- "n" Session
    Utilisateur "1" -- "n" JournalActivite
    PointDeVente "1" -- "n" Mouvement
    Produit "n" -- "1" Categorie
    Produit "1" -- "n" CaracteristiqueProduit
    Produit "n" -- "n" Fournisseur
    Produit "1" -- "n" PrixProduit
    Categorie "1" -- "n" Categorie
```

### 3.3 Diagrammes de séquence

#### 3.3.1 Création et Configuration d'un Nouveau Tenant
```mermaid
sequenceDiagram
    actor A as Admin Système
    participant S as Système
    participant T as Tenant Service
    participant O as Organisation Service
    participant L as Licence Service
    participant U as User Service
    participant DB as Base de données

    A->>S: Créer nouveau tenant
    S->>T: Initialiser tenant
    T->>DB: Créer tenant
    T->>L: Créer licence d'essai
    T->>O: Créer organisation principale
    O->>U: Créer admin organisation
    U->>DB: Sauvegarder utilisateur
    O->>DB: Sauvegarder organisation
    L->>DB: Sauvegarder licence
    DB-->>S: Confirmation
    S-->>A: Tenant créé & configuré
```

#### 3.3.2 Gestion des Stocks et Inventaire
```mermaid
sequenceDiagram
    actor U as Utilisateur
    participant S as Système
    participant P as Produit Service
    participant ST as Stock Service
    participant M as Mouvement Service
    participant N as Notification Service
    participant DB as Base de données

    U->>S: Modifier stock produit
    S->>ST: Vérifier stock actuel
    ST->>DB: Lire niveau stock
    DB-->>ST: Retour niveau
    ST->>P: Vérifier seuils produit
    P-->>ST: Seuils produit
    ST->>M: Créer mouvement stock
    M->>DB: Enregistrer mouvement
    ST->>DB: Mettre à jour stock
    alt Stock < Seuil minimal
        ST->>N: Alerter stock bas
        N->>DB: Enregistrer alerte
        N-->>U: Notification stock bas
    end
    DB-->>S: Confirmation
    S-->>U: Stock mis à jour
```

#### 3.3.3 Processus d'Inventaire
```mermaid
sequenceDiagram
    actor G as Gestionnaire
    participant S as Système
    participant I as Inventaire Service
    participant ST as Stock Service
    participant M as Mouvement Service
    participant R as Rapport Service
    participant DB as Base de données

    G->>S: Démarrer inventaire
    S->>I: Initialiser inventaire
    I->>ST: Obtenir état stocks théorique
    ST-->>I: Liste stocks théoriques
    G->>S: Saisir quantités réelles
    S->>I: Comparer stocks
    I->>M: Créer mouvements d'ajustement
    M->>DB: Enregistrer mouvements
    I->>ST: Mettre à jour stocks
    ST->>DB: Sauvegarder nouveaux stocks
    I->>R: Générer rapport d'inventaire
    R->>DB: Sauvegarder rapport
    DB-->>S: Confirmation
    S-->>G: Rapport inventaire
```

#### 3.3.4 Gestion des Commandes et Approvisionnement
```mermaid
sequenceDiagram
    actor G as Gestionnaire
    participant S as Système
    participant C as Commande Service
    participant F as Fournisseur Service
    participant ST as Stock Service
    participant M as Mouvement Service
    participant DB as Base de données

    G->>S: Créer commande
    S->>F: Vérifier fournisseur
    F-->>S: Détails fournisseur
    S->>C: Créer commande
    C->>DB: Enregistrer commande
    G->>S: Réceptionner commande
    S->>C: Valider réception
    C->>ST: Mettre à jour stocks
    ST->>M: Créer mouvements entrée
    M->>DB: Enregistrer mouvements
    ST->>DB: Mettre à jour stocks
    DB-->>S: Confirmation
    S-->>G: Commande réceptionnée
```

### 3.4 Diagrammes de flux

#### 3.4.1 Flux de Gestion des Stocks
```mermaid
flowchart TD
    A[Début] --> B{Type d'opération}
    B -->|Entrée stock| C[Vérifier bon de commande]
    B -->|Sortie stock| D[Vérifier disponibilité]
    B -->|Ajustement| E[Vérifier autorisation]
    
    C --> F[Créer mouvement entrée]
    D --> G[Créer mouvement sortie]
    E --> H[Créer mouvement ajustement]
    
    F --> I{Vérifier seuils}
    G --> I
    H --> I
    
    I -->|Stock OK| J[Mettre à jour stock]
    I -->|Stock bas| K[Générer alerte]
    K --> J
    
    J --> L[Mettre à jour statistiques]
    L --> M[Enregistrer historique]
    M --> N[Fin]
```

#### 3.4.2 Flux de Gestion des Licences
```mermaid
flowchart TD
    A[Début] --> B{Type licence}
    B -->|Essai| C[Créer licence essai]
    B -->|Premium| D[Vérifier paiement]
    
    C --> E[Configurer limites essai]
    D --> F[Configurer limites premium]
    
    E --> G{Activation}
    F --> G
    
    G -->|Succès| H[Activer fonctionnalités]
    G -->|Échec| I[Notifier erreur]
    
    H --> J[Démarrer monitoring]
    I --> K[Logger incident]
    
    J --> L[Vérifier utilisation]
    L --> M{Limites dépassées?}
    
    M -->|Oui| N[Notifier client]
    M -->|Non| O[Continuer]
    
    N --> P[Fin]
    O --> P
```

#### 3.4.3 Flux d'Inventaire
```mermaid
flowchart TD
    A[Début] --> B[Initialiser inventaire]
    B --> C[Figer les mouvements]
    C --> D[Générer état théorique]
    D --> E[Saisie état réel]
    E --> F{Écarts?}
    
    F -->|Oui| G[Analyser écarts]
    F -->|Non| H[Valider inventaire]
    
    G --> I[Justifier écarts]
    I --> J{Validation responsable}
    
    J -->|Approuvé| K[Générer ajustements]
    J -->|Refusé| L[Demander révision]
    
    K --> M[Mettre à jour stocks]
    L --> E
    
    M --> N[Générer rapport]
    H --> N
    
    N --> O[Archiver inventaire]
    O --> P[Fin]
```

#### 3.4.4 Flux de Traitement des Alertes
```mermaid
flowchart TD
    A[Début] --> B{Type alerte}
    B -->|Stock bas| C[Vérifier seuils]
    B -->|Péremption| D[Vérifier dates]
    B -->|Licence| E[Vérifier validité]
    
    C --> F[Évaluer urgence]
    D --> F
    E --> F
    
    F --> G{Niveau urgence}
    G -->|Critique| H[Notification immédiate]
    G -->|Normal| I[File d'attente]
    
    H --> J[Créer ticket]
    I --> J
    
    J --> K[Assigner responsable]
    K --> L[Suivre résolution]
    
    L --> M{Résolu?}
    M -->|Oui| N[Clôturer alerte]
    M -->|Non| O[Escalader]
    
    O --> K
    N --> P[Fin]
```

## 4. Exigences spécifiques

### 4.1 Exigences fonctionnelles
1. **Gestion des stocks**
   - Suivi en temps réel des quantités
   - Alertes de stock bas automatiques
   - Historique des mouvements
   - Génération de rapports

2. **Gestion des utilisateurs**
   - Création et gestion des comptes
   - Attribution des rôles
   - Gestion des permissions
   - Historique des actions

3. **Rapports et analyses**
   - Rapports d'inventaire en temps réel
   - Analyses des tendances
   - Exportation des données
   - Tableaux de bord personnalisables

### 4.2 Exigences non fonctionnelles
1. **Performance**
   - Temps de réponse < 2 secondes
   - Disponibilité 99.9%
   - Support de 1000 utilisateurs simultanés

2. **Sécurité**
   - Authentification à deux facteurs
   - Chiffrement des données
   - Journalisation des actions
   - Sauvegarde quotidienne

3. **Utilisabilité**
   - Interface intuitive
   - Formation minimale requise
   - Support mobile
   - Documentation complète

## 5. Interfaces externes

### 5.1 Interfaces utilisateur
- Interface web responsive
- Application mobile native
- Terminal de point de vente
- Dashboard administrateur

### 5.2 Interfaces logicielles
- API REST pour l'intégration
- Connecteurs pour systèmes ERP
- Interface pour systèmes de paiement
- Export vers formats standards (CSV, Excel) 