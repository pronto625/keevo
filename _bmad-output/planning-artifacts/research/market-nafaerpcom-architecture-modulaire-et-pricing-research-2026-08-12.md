---
stepsCompleted: [1, 2, 3, 4, 5]
inputDocuments: []
workflowType: 'research'
lastStep: 1
research_type: 'market'
research_topic: 'nafaerp.com : architecture modulaire et pricing'
research_goals: 'Analyser nafaerp.com comme référence concurrentielle pour la refonte modulaire de Keevo (découpage en modules indépendants type mini-apps) et pour la mise à jour du modèle de pricing de Keevo.'
user_name: 'Toor'
date: '2026-08-12'
web_research_enabled: true
source_verification: true
---

# Research Report: market

**Date:** 2026-08-12
**Author:** Toor
**Research Type:** market

---

## Research Overview

[Research overview and methodology will be appended here]

---

<!-- Content will be appended sequentially through research workflow steps -->

# Market Research: nafaerp.com : architecture modulaire et pricing

## Research Initialization

### Research Understanding Confirmed

**Topic**: nafaerp.com : architecture modulaire et pricing
**Goals**: Analyser nafaerp.com comme référence concurrentielle pour la refonte modulaire de Keevo (découpage en modules indépendants type mini-apps) et pour la mise à jour du modèle de pricing de Keevo.
**Research Type**: Market Research (analyse concurrentielle ciblée)
**Date**: 2026-08-12

### Contexte

Une recherche de marché complète sur Keevo (segments clients, comportements, paysage concurrentiel général) a déjà été produite le 2026-02-26 (`market-Keevo-research-2026-02-26.md`). Cette nouvelle recherche est volontairement **ciblée** : elle ne refait pas l'analyse de marché générale mais se concentre sur un concurrent précis, nafaerp.com, identifié par Toor comme référence pour deux décisions produit imminentes :

1. La refonte de Keevo en architecture modulaire (modules indépendants, chacun utilisable comme une mini-app autonome).
2. La mise à jour de la grille tarifaire de Keevo.

### Research Scope

**Focus Areas:**

- **Découpage modulaire de nafaerp.com** : liste des modules proposés, périmètre fonctionnel de chacun, degré d'indépendance/autonomie entre modules, comment ils s'articulent (activation à la carte, dépendances entre modules, expérience utilisateur d'un module isolé vs. suite complète).
- **Modèle de pricing de nafaerp.com** : grille tarifaire, tarification par module vs. par palier/bundle, ce qui est inclus/exclu à chaque niveau, devise et positionnement prix, éventuelles offres par taille d'entreprise ou secteur.
- **Fonctionnalités où nafaerp.com dépasse Keevo actuellement** (à date, sur la base des fonctionnalités connues de Keevo) — pour prioriser le backlog de la refonte modulaire.
- **Enseignements actionnables** : recommandations concrètes pour (a) la structure modulaire cible de Keevo et (b) la refonte de sa grille tarifaire.

**Hors périmètre** (déjà couvert par la recherche du 2026-02-26) : taille du marché camerounais, segments clients, comportements d'achat génériques.

**Research Methodology:**

- Données web actuelles avec vérification des sources (site nafaerp.com en priorité, complété par avis clients, comparateurs, presse spécialisée si disponible)
- Confidence level assessment pour les données incertaines (ex. pricing non public, chiffres d'adoption)
- Comparaison structurée avec Keevo à chaque étape

### Next Steps

**Research Workflow:**

1. ✅ Initialization and scope setting (current step)
2. Analyse du découpage modulaire de nafaerp.com
3. Analyse du modèle de pricing de nafaerp.com
4. Synthèse stratégique et recommandations pour Keevo (architecture modulaire + pricing)

**Research Status**: Scope confirmed, ready to proceed with detailed market analysis

Scope confirmed by user on 2026-08-12.

## Analyse Concurrentielle : NAFA ERP (nafaerp.com)

### Fiche d'identité

- **Nom** : NAFA ERP (marque courte : NAFA)
- **Éditeur** : THAFAM HOLDING SARL, basé à Yaoundé, Cameroun
- **Positionnement** : "Logiciel de caisse et gestion pour commerces africains" — ERP multi-commerces offline-first
- **Zones desservies déclarées** : Cameroun, Sénégal, Côte d'Ivoire, Bénin, Burkina Faso, Mali, Togo, Gabon, Congo, Ghana, Nigeria — **couverture pan-africaine affichée**, bien plus large que le positionnement Cameroun de Keevo à date
- **Plateformes** : Android, iOS, Windows, macOS, Web (PWA). Architecture technique : SPA React + IndexedDB (Dexie) pour le offline-first, wrapper natif (probable Capacitor) pour mobile
- **Différenciateur technique notable** : assistant IA qui tourne **en local dans le navigateur/l'app** via un modèle WASM (`@mlc-ai/web-llm`), donc sans dépendance à une API cloud pour l'IA — cohérent avec leur promesse offline-first
_Source : https://nafaerp.com/ (HTML source, JSON-LD structuré), https://nafaerp.com/fonctionnalites, https://nafaerp.com/tarifs, https://nafaerp.com/faq_

### Découpage Modulaire — Comment NAFA structure son produit

**Point important à corriger par rapport à l'hypothèse de départ** : NAFA n'est **pas** découpé en modules totalement indépendants vendus/facturés séparément comme des mini-apps distinctes. C'est **une application unique** avec deux couches :

**Couche 1 — Le cœur ("tout votre commerce dans une seule application"), inclus dans tous les plans payants :**
- Caisse & ventes (POS) — espèces, Mobile Money, carte, crédit/ardoise ; tickets thermiques Bluetooth, tiroir-caisse, douchette code-barres, écran client ; fonctionne offline avec sync automatique
- Stock & achats — quantités temps réel, alertes de rupture, lots/péremption (FEFO), fournisseurs, bons de commande, CUMP (coût moyen pondéré) automatique
- Clients & fidélité (CRM) — fiche client, historique, créances/ardoise, points fidélité, reçus WhatsApp
- Comptabilité & fiscalité — journal, grand livre, balance, compte de résultat, bilan **au format SYSCOHADA/OHADA**, déclarations fiscales — cible explicitement les cabinets comptables gérant plusieurs clients
- Rapports & rentabilité — CA par jour/vendeur/produit, marge par produit, export Excel
- Assistant IA bilingue (FR/EN) — questions en langage naturel sur les ventes/stocks, recommandations

**Couche 2 — "Modules métier" activables à la carte ("Activez ce dont vous avez besoin")** — ce sont des **verticales sectorielles en feature-toggle**, pas des applications séparées avec leur propre facturation visible :
- Restaurant (plan de salle, cuisine/KDS)
- Hôtel & résidence
- Salle de jeux / cybercafé
- Gestion locative
- Rendez-vous (salon de coiffure, clinique)
- Abonnements (salle de sport, école)
- Garanties (électronique, téléphonie)

_Source : https://nafaerp.com/fonctionnalites — section "Modules métier"_

**Enseignement clé** : le "découpage modulaire" de NAFA n'est pas un découpage en mini-apps indépendantes au sens architectural strict, mais une **modularité produit orientée verticale métier**, activée par toggle sur un socle commun (POS + Stock + CRM + Compta + Rapports + IA). Le prospect/client ne voit pas "8 apps séparées" — il voit "une app qui s'adapte à son type de commerce". C'est une nuance importante si l'objectif de Keevo est une vraie architecture modulaire technique (chaque module = service/app indépendant) : NAFA résout un problème produit similaire (adapter l'app au métier) sans nécessairement avoir cette rigueur architecturale côté technique — ou en tout cas, cela n'est pas visible/communiqué publiquement.

### Modèle de Pricing de NAFA

| Plan | Prix | Inclus |
|---|---|---|
| **Starter** | **Gratuit**, sans limite de durée | Mode 100% local sur 1 appareil : caisse, ventes, stock, clients — sans internet, sans abonnement, sans carte bancaire. Idéal point de vente unique. |
| **Business** | **19 900 FCFA/mois** (~30-33 USD) | Synchronisation multi-appareils + cloud, plusieurs vendeurs/succursales, comptabilité, rapports avancés, assistant IA, support prioritaire |
| **Entreprise** | **59 900 FCFA/mois** (base), conditions négociées | Déploiement sur mesure pour groupes multi-commerces et cabinets comptables : volumes élevés, accompagnement dédié — "Contactez notre équipe" |

**Modalités de paiement** : Mobile Money, virement, espèces — pas de carte bancaire requise pour démarrer.

**Observations structurelles sur le pricing** :
- **3 paliers, pas de tarification à la carte par module.** Les "modules métier" (Restaurant, Hôtel, etc.) ne semblent pas facturés en supplément — ils sont un argument de couverture fonctionnelle du plan Business/Entreprise, pas une source de revenu additionnelle séparée. NAFA monétise sur (device count / sync cloud / multi-succursale / compta / IA), pas sur le nombre de modules métier activés.
- **Le gratuit est un vrai gratuit permanent** (pas un trial déguisé) mais fonctionnellement très limité (1 appareil, local uniquement, pas de cloud, pas de multi-succursale) — sert de produit d'appel/acquisition virale plutôt que de vitrine premium.
- **Écart de prix Business → Entreprise est net** (19 900 → 59 900, soit x3) — le palier Entreprise capture surtout les cabinets comptables et groupes multi-commerces à forte valeur, pas les PME classiques.
_Source : https://nafaerp.com/tarifs, JSON-LD SoftwareApplication (https://nafaerp.com/)_

### Comparaison avec Keevo (état actuel)

| Axe | NAFA ERP | Keevo (actuel) |
|---|---|---|
| Plan gratuit | Permanent, mais limité à 1 appareil/local uniquement | Permanent, limité à 1 boutique / 500 produits / 3 employés, **fonctionnalités core complètes** (multi-appareil/cloud inclus) |
| Onboarding payant | Direct (Starter → Business) | **Premium Trial 6 mois offerts** à l'inscription avant retombée en Free — stratégie d'acquisition différente, plus généreuse à court terme |
| Prix Premium/Business | 19 900 FCFA/mois annoncé publiquement | **Non défini publiquement à ce jour** (FR16 documente les limites du Free, mais aucun prix Premium n'est fixé dans le PRD actuel) |
| Comptabilité SYSCOHADA/OHADA | Oui, module complet (journal, grand livre, bilan) | Non présent dans les epics actuels de Keevo — écart fonctionnel net, surtout pertinent pour cibler les cabinets comptables |
| Modules métier verticaux (Restaurant, Hôtel, etc.) | Oui, 7 verticales en feature-toggle | Aucun à ce jour — Keevo reste généraliste "commerce/stock" |
| Assistant IA | Oui, local (WASM), bilingue, sur ventes/stock | IA mentionnée dans la vision Keevo (réassorts, pricing) mais pas encore livrée en usage conversationnel |
| Couverture géographique affichée | 11 pays africains | Cameroun (positionnement actuel) |
| Offline-first | Oui, cœur de la proposition de valeur | Oui, cœur de la proposition de valeur (Epic 5) — parité stratégique |

### Enseignements Actionnables pour Keevo

**Pour la refonte modulaire (architecture produit) :**
1. **Le vrai insight n'est pas "découper en mini-apps techniques" mais "activer des verticales métier sur un socle commun."** Si Keevo veut suivre ce modèle produit, la priorité est de définir un socle stable (ce que Keevo fait déjà : catalogue, stock, POS, multi-boutique) puis des packs verticaux activables (ex. Restaurant, Salon de coiffure/Rendez-vous, Location, Garantie électronique) plutôt que de fragmenter le cœur existant en services indépendants dès le départ.
2. **La comptabilité SYSCOHADA/OHADA est un angle mort de Keevo face à NAFA** — c'est un vrai différenciateur pour NAFA vis-à-vis des cabinets comptables et PME formalisées. À évaluer comme module candidat prioritaire si Keevo veut monter en gamme.
3. Si l'architecture technique modulaire (découplage en services/mini-apps réellement indépendants) est un objectif propre à Keevo au-delà de ce que fait NAFA, ce sera un sujet à traiter avec Winston (architecte) — cette recherche ne tranche pas la question technique, seulement la structuration produit visible côté marché.

**Pour la mise à jour du pricing de Keevo :**
1. Keevo n'a aujourd'hui **aucun prix Premium public** — c'est le principal manque à combler avant toute comparaison fine. NAFA donne un point de référence marché direct : **~19 900 FCFA/mois** pour un plan multi-boutique + cloud + IA, sur le même marché camerounais.
2. Le modèle Free actuel de Keevo (fonctionnalités core complètes, juste limité en volume) est **plus généreux** que le Free de NAFA (limité au mode local mono-appareil). C'est un choix de positionnement à assumer consciemment : soit un argument marketing fort ("plus généreux que NAFA"), soit un risque de cannibalisation du Premium si le Free suffit à la majorité des commerçants cibles.
3. Le Premium Trial 6 mois de Keevo est une mécanique d'acquisition sans équivalent chez NAFA (qui n'offre pas de trial du tout sur le payant) — à conserver comme différenciateur, mais attention à la date de bascule Free après 6 mois : NAFA n'ayant pas ce problème, il n'y a pas de données concurrentielles pour calibrer le taux de conversion post-trial.
4. Envisager un 3e palier type "Entreprise" (cabinets comptables, groupes multi-boutiques) à un prix nettement supérieur (NAFA fait x3 vs son plan intermédiaire) plutôt que d'étirer un seul plan payant — capture de valeur différenciée pour les gros comptes.

### Limites de cette recherche

- Le contenu a été extrait des pages marketing publiques de nafaerp.com (HTML pré-rendu + JSON-LD structuré) ; aucune capture d'écran de l'app connectée, aucun avis client tiers (Trustpilot, Google Play) n'a été consulté — la recherche web générale n'a remonté aucune couverture presse ou indexation tierce sur NAFA ERP, signe d'un produit encore jeune/peu référencé.
- Le détail exact de facturation des "modules métier" (inclus sans supplément vs. option cachée derrière un devis Entreprise) n'est pas garanti à 100% — l'information publique suggère qu'ils sont inclus, mais ce n'est confirmé nulle part explicitement pour le plan Business.
- Observation annexe hors-sujet marché : le code source HTML de nafaerp.com expose publiquement des commentaires internes très détaillés (rationale CSP, notes de debug versionnées, choix d'architecture). Sans impact sur cette analyse concurrentielle, mais à noter pour Keevo comme rappel de bonne hygiène (ne pas committer ce niveau de détail interne dans du HTML servi publiquement).

### Addendum — Recalage avec la cible architecturale déjà documentée de Keevo

`architecture.md` (déjà écrit, non issu de cette recherche) spécifie que le backend Keevo est **déjà structuré** en 10 domaines / 20+ modules hexagonaux (`identity`, `catalog`, `commerce`, `inventory`, `store`, `reporting`, `messaging`, `sync`, `subscription`, `admin`), chacun avec ports/adapters strictement séparés, et **explicitement "designed for future microservice extraction"** (contrainte non-négociable #4, architecture.md:146). L'extraction microservice elle-même est listée comme différée post-MVP (architecture.md:310) — le découplage logique existe déjà dans le code, l'extraction physique en services déployables séparément reste à faire.

**Ce que ça change dans la comparaison avec NAFA** : la cible de Keevo est une **modularité technique réelle** (chaque domaine = frontière de service potentielle, ports/adapters stricts, migrable vers microservices/MCP tools sans toucher au domaine), pas une modularité produit en feature-toggle sur un monolithe comme NAFA. NAFA résout "adapter l'app au métier du client" ; Keevo vise en plus "pouvoir déployer/faire évoluer/scaler chaque domaine indépendamment." Les deux ne sont pas en concurrence sur ce point — NAFA n'a pas cette rigueur technique visible, donc ce n'est pas un axe où s'inspirer d'eux. L'inspiration NAFA reste pertinente sur la couche produit (verticales métier activables : Restaurant, Rendez-vous, etc. — à mapper sur les domaines existants de Keevo plutôt que comme nouveaux domaines) et sur le pricing (paliers, prix de référence marché).

**Implication pour la suite** : la refonte modulaire de Keevo est donc principalement un sujet de **séquencement d'exécution de l'extraction microservice déjà prévue à l'architecture** (quels domaines extraire en premier, quelles frontières ajuster, quel impact sur le sync offline-first et le multi-tenant schema-per-tenant) — sujet pour Winston, pas une question de direction produit encore ouverte.

## Approfondissement : Pricing, Design, Intégration IA (2026-08-12)

Toor a confirmé apprécier le modèle de pricing NAFA et demande une comparaison approfondie sur trois axes : pricing, design, intégration IA.

### 1. Pricing — comparaison détaillée et proposition chiffrée pour Keevo

| | NAFA ERP | Keevo (actuel) |
|---|---|---|
| Structure | 3 paliers clairs (Starter / Business / Entreprise) | 2 états (Free / Premium) + mécanique Trial temporaire, **pas de 3e palier** |
| Palier gratuit | Permanent mais **bridé fonctionnellement** (1 appareil, local uniquement, pas de cloud, pas de multi-boutique) | Permanent, **fonctionnellement complet** (juste des limites de volume : 1 boutique/500 produits/3 employés) — le Free de Keevo est un meilleur produit que le Free de NAFA |
| Palier intermédiaire | Business — **19 900 FCFA/mois**, prix affiché publiquement | Premium — **prix non défini publiquement à ce jour** |
| Palier haut de gamme | Entreprise — **59 900 FCFA/mois** base, sur devis au-delà | Aucun équivalent aujourd'hui |
| Mécanique d'entrée | Aucun trial sur le payant — free ou payant, direct | Trial Premium 6 mois offert à l'inscription, puis retombée en Free |
| Paiement | Mobile Money, virement, espèces — pas de CB | (à documenter — Mobile Money Orange/MTN mentionné dans l'architecture) |

**Ce que le modèle NAFA fait bien et que Keevo n'a pas encore** : un **prix de référence marché public et un 3e palier qui capture la valeur haut de gamme** (multi-boutiques, cabinets comptables, gros volumes) à un multiple net du palier intermédiaire (x3). Un Free strictement local sans cloud est aussi un moyen simple de faire du Cloud/multi-appareil un vrai argument payant — chose que Keevo ne peut pas faire aussi frontalement puisque son Free inclut déjà le cloud/multi-appareil.

**Proposition chiffrée pour Keevo** (à valider par toi, ce n'est pas une décision mais une base de travail) :
- **Free** : garder généreux (différenciateur actuel vs NAFA), ne pas copier la restriction "local uniquement" de NAFA — le cloud gratuit est un moteur d'acquisition virale bouche-à-oreille déjà visé dans le PRD (30% des acquisitions M3).
- **Premium** : ancrer près du prix de référence marché NAFA, ex. **15 000 – 19 900 FCFA/mois**, sur le même marché camerounais, en Mobile Money — un prix legèrement en dessous ou à parité de NAFA est défendable puisque Keevo garde un Free plus généreux (contrepartie logique).
- **Nouveau palier "Entreprise / Multi-boutiques+"** : cible grossistes multi-boutiques et, si Keevo ajoute la comptabilité SYSCOHADA (cf. angle mort identifié), les cabinets comptables. Prix indicatif **45 000 – 60 000 FCFA/mois** ou sur devis, cohérent avec le x3 observé chez NAFA.
- Conserver le Trial 6 mois comme différenciateur d'acquisition (NAFA n'en a pas) — c'est un atout, pas un point à changer.

### 2. Design — comparaison (avec limite importante)

**Limite à signaler** : je n'ai eu accès qu'aux pages marketing publiques de nafaerp.com (landing, tarifs, fonctionnalités) — pas à l'app connectée (dashboard, POS, écrans réels), qui est derrière login. La comparaison ci-dessous porte donc sur le **langage visuel marketing**, pas sur l'expérience produit réelle de NAFA. Pour Keevo, je m'appuie sur `ux-design-specification.md`, qui documente la cible produit réelle (pas juste marketing).

| | NAFA ERP (site marketing) | Keevo (cible produit documentée) |
|---|---|---|
| Palette | Teal `#0d9488` / `#0b7d72` + slate `#0f172a`/`#334155`/`#64748b` — **palette Tailwind par défaut**, choix standard/générique, aucune personnalisation visible | Palette **"Indigo Sky" sur-mesure** — primaire `#3B5BDB`, accent `#FF6B6B`, tokens sémantiques complets (succès/warning/erreur), thème clair **et** sombre définis en détail |
| Typographie | Sans-serif générique (probable système/Tailwind par défaut), gras sur les titres marketing | **Inter** (Google Fonts), poids variable 300-700, règles explicites de taille minimale (14sp) |
| Direction visuelle | Carte OG marketing : fond sombre dégradé navy→noir, gros chiffres de traction (500+ commerces, 12 pays, 99.9% dispo) — ton "scale-up tech" générique | Direction assumée **fintech** ("moment café" façon Revolut/Wise pour le dashboard), pistes envisagées Clean & Airy / Dark & Premium / glassmorphism — recherche de signature visuelle propre |
| Accessibilité | Non documentée publiquement | Spécifiée explicitement : touch targets 48×48dp, alertes non-dépendantes de la couleur seule, support screen reader (`Semantics` Flutter), zoom 200% |
| Système de composants | Non observable (site marketing seulement) | Basé sur Material 3 + tokens Keevo, thème dynamique (Material You) |

**Enseignement** : sur le marketing, NAFA joue la carte "SaaS tech générique" (palette Tailwind par défaut, gros chiffres de traction) — efficace pour la crédibilité rapide mais sans signature propre. Keevo a une ambition design nettement plus travaillée et personnalisée dans ses specs (palette dédiée, direction fintech, accessibilité documentée) — **rien à copier de NAFA ici**, c'est plutôt un point où Keevo vise déjà plus haut sur le papier. Le vrai test sera l'exécution réelle de l'app, pas la comparaison de landing pages.

### 3. Intégration IA — comparaison

| | NAFA ERP | Keevo |
|---|---|---|
| Statut | **Shipped et commercialisé** (feature payante Business/Entreprise, mise en avant sur le site) | **Vision future uniquement** — ligne "Recommandations IA" dans le PRD, aucune story/epic actuelle ne l'implémente |
| Architecture | **Client-side, on-device** : modèle WASM via `@mlc-ai/web-llm`, tourne dans le navigateur/l'app, pas d'appel API cloud pour l'inférence | **Prévue server-side** : `adapter/in/mcp/` placeholder par module + migration path documentée vers **Spring AI MCP Server** — les use cases deviennent des tools MCP exposables à un LLM |
| Portée des données | Mono-tenant — l'IA de chaque client ne voit que ses propres données | **Cross-tenant par conception** — la vision Keevo explicite l'exploitation de "données agrégées" de tous les tenants pour les recommandations (réassorts, pricing intelligent), en lien avec la stratégie marketplace/data "Razor & Blades inversé" évoquée dans le PRD |
| Interaction utilisateur | **Conversationnelle** — chat bilingue FR/EN, questions libres ("combien j'ai vendu cette semaine ?") | Non spécifié précisément — le PRD parle de "recommandations", pas explicitement d'un chat ; l'architecture MCP suggère plutôt des **tools appelables** (par un agent ou une interface à définir) que fixée sur un format chat |
| Dépendance réseau | Aucune (inférence locale, cohérent avec le positionnement offline-first) | Nécessite une connexion pour toute fonctionnalité IA server-side (mais cohérent puisque l'agrégation cross-tenant ne peut de toute façon pas se faire on-device) |
| Coût d'inférence | Nul pour NAFA (calcul déporté sur l'appareil client) | Coût d'inférence à la charge de Keevo (API LLM serveur) — impact direct sur les coûts variables, à intégrer dans la réflexion pricing du palier qui inclura l'IA |

**Enseignement clé** : ce ne sont pas deux versions de la même chose — NAFA a fait un choix **assistant conversationnel local et privé**, Keevo vise une **intelligence de recommandation basée sur les données agrégées à l'échelle de la plateforme**, un pari plus ambitieux (vrai avantage concurrentiel structurel si l'agrégation data se confirme) mais aussi plus complexe et plus long à livrer, et pas encore commencé alors que NAFA l'a déjà en marché. Deux options non exclusives à considérer :
- Livrer un **premier niveau simple** inspiré de NAFA (chat Q&A sur les propres données du tenant, éventuellement local léger pour rester offline-first) comme "lot 1" rapide à shipper et comparable frontalement à NAFA ;
- Garder la vision cross-tenant/MCP comme différenciateur "lot 2", plus long terme, qui devient un vrai argument de vente une fois la base de tenants suffisante (le PRD fixe déjà un seuil indicatif à 50 000 références produits agrégées).
