---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14]
inputDocuments:
  - _bmad-output/planning-artifacts/product-brief-AI-2026-02-13.md
  - _bmad-output/planning-artifacts/prd.md
  - _bmad-output/planning-artifacts/prd-validation-report.md
  - _bmad-output/planning-artifacts/research/market-Keevo-research-2026-02-26.md
  - _bmad-output/planning-artifacts/research/technical-stack-technique-keevo-research-2026-02-26.md
---

# UX Design Specification Keevo

**Author:** Toor
**Date:** 2026-02-26T22:45:00+01:00

---

## Executive Summary

### Project Vision

Keevo est une application mobile-first (Flutter) + desktop installable de gestion d'inventaire et de ventes, ciblant les petits commerçants informels et les grossistes multi-boutiques au Cameroun. Sa philosophie fondamentale — "Augmenter, ne pas Remplacer" — signifie que l'app s'insère dans les habitudes existantes (WhatsApp, cahiers) au lieu de forcer les utilisateurs à changer. Architecture offline-first (7 jours sans connexion), sync delta-based, multi-tenant PostgreSQL.

La vision UX s'articule autour de trois principes :
1. **Invisibilité** — L'utilisateur ne doit jamais sentir qu'il "utilise un logiciel". Keevo doit fonctionner comme une extension naturelle de ses habitudes.
2. **Adoption progressive** — "Vends d'abord, organise après". Aucun catalogue n'est requis avant la première vente.
3. **Design émotionnel bi-directionnel** — Le propriétaire et l'employé interagissent avec le même système mais vivent des émotions différentes : confiance pour le patron, protection pour l'employé.

### Target Users

| Persona | Profil | Tech Literacy | Besoin UX principal |
|---------|--------|---------------|---------------------|
| **Simon** (Propriétaire) | 28 ans, grossiste multi-boutiques, importe Chine/Turquie | 4/10 | Vue cross-boutiques instantanée, contrôle à distance, "moment café" matinal |
| **Loïc** (Employé) | 17-30 ans, vendeur en boutique, digital-native social | 5-6/10 | POS 3 taps max, clôture 1 tap, libération des tâches manuelles |
| **Toor** (Super Admin) | Fondateur Keevo | 10/10 | Dashboard plateforme, analytics globales, gestion tenants |

**Insight critique** : L'adoption dépend des deux personas simultanément. Si l'employé résiste (perçoit l'app comme surveillance), le patron abandonne. Le design doit créer le sentiment de **libération** pour l'employé, pas de contrôle.

**Variations sectorielles** : L'UX doit s'adapter émotionnellement au secteur. Un libraire voit 📚 "Manuels scolaires", un électronicien voit 📱 "Smartphones Samsung". Les templates sectoriels sont un levier de validation identitaire ("cette app a été conçue pour moi").

### Key Design Challenges

1. **Tech literacy 4/10** — Zéro jargon comptable, zéro complexité visuelle. Chaque écran = UNE action claire. Toutes les fonctionnalités critiques accessibles en ≤3 taps.

2. **Double persona, double UX** — Simon (propriétaire) veut la vue d'ensemble et les analytics. Loïc (employé) veut le minimum vital et la rapidité. Deux expériences distinctes dans la même app sans surcharger l'un ni sous-informer l'autre.

3. **Le paradoxe de confiance** — La traçabilité est perçue comme "confiance" par le patron et comme "surveillance" par l'employé au début. Le design doit accompagner la transformation émotionnelle progressive : de la peur vers la fierté.

4. **Adoption progressive obligatoire** — Impossible d'exiger la saisie d'un catalogue complet avant la première utilisation. L'onboarding doit permettre de vendre avec 0 produit pré-enregistré (création à la volée).

5. **Offline UX** — L'utilisateur doit clairement savoir s'il est connecté ou non, et avoir confiance que ses données sont en sécurité.

6. **Le concurrent principal est l'inertie** — 90% du marché est papier/Excel. L'UX doit résoudre un problème de comportement, pas un problème technique.

### Design Opportunities

1. **Les rapports WhatsApp comme extension UX** — Le rapport end-of-day est LE moment où le propriétaire ressent la valeur de Keevo. Format emoji-rich, compact, lisible en 5 secondes sur un écran de téléphone. Pas de PDF, pas de lien. L'info est DANS le message.

2. **Le "Customer Hero Moment"** — Quand Loïc dit au client "Oui, on en a dans notre boutique de Bonanjo !", c'est le moment de preuve de valeur. La recherche cross-boutique doit être instantanée et satisfaisante.

3. **Le "moment café" matinal** — Résumé ultra-compact en 3 secondes au démarrage de l'app pour le propriétaire : CA d'hier, alertes stock, tendance. Rituel de réassurance quotidien.

4. **L'onboarding "Vends d'abord, organise après"** — Les produits se créent à la volée pendant les ventes (champ libre : nom + prix + quantité). Les produits "brouillons" sont enrichis progressivement. Le catalogue se construit par l'usage, pas avant l'usage.

5. **Le leaderboard comme feature gate émotionnel** — Caché côté employé en V1 (stats privées dans le dashboard propriétaire uniquement). Toggle activable par le propriétaire quand l'équipe est prête. Transition de "surveillance" vers "compétition saine".

6. **La clôture journalière en 1 tap** — Un tap "Clôturer" → résumé flash → envoi automatique au patron. Aucune question posée à l'employé. Fallback automatique à 20h si oubli. Le rapport part TOUJOURS.

7. **Le dual-trigger de rapport intelligent** — Clôture manuelle par l'employé OU envoi automatique à 20h. Le rapport auto mentionne "⏰ Rapport auto-généré" — un nudge comportemental subtil qui encourage l'employé à clôturer lui-même sans le punir.

## Core User Experience

### Defining Experience

L'action utilisateur la plus fréquente et la plus critique de Keevo est **l'enregistrement d'une vente par l'employé (POS)**. C'est l'interaction qui se répète 15-50 fois par jour, par boutique. Si cette action prend plus de 10 secondes, tout le système s'effondre — Loïc retourne au cahier. C'est le "heartbeat" de Keevo.

**L'action secondaire critique** : la vérification de disponibilité cross-boutique. C'est le "Customer Hero Moment" — l'instant où Keevo prouve qu'il est indispensable et que l'employé passe de "désolé, on n'a plus" à "oui, on en a dans notre boutique de Bonanjo !".

**Le core loop quotidien :**
1. Matin → Simon ouvre l'app → résumé matinal 3 secondes (rituel de réassurance)
2. Journée → Loïc enregistre les ventes via POS (action répétée 15-50x)
3. Pendant la journée → Vérifications cross-boutiques (2-5x)
4. Soir → Loïc clôture en 1 tap → rapport auto-envoyé WhatsApp à Simon
5. 20h fallback → Si pas de clôture, rapport auto-généré

### Platform Strategy

| Plateforme | Usage | Rôles | Interaction | Priorité |
|-----------|-------|-------|-------------|----------|
| **Android mobile** | POS + Dashboard + tout | Employé + Propriétaire | Tactile, une main, debout | **MVP** |
| **iOS mobile** | POS + Dashboard + tout | Employé + Propriétaire | Tactile | **MVP** |
| **Linux/Windows desktop** | POS + Dashboard + tout | Employé + Propriétaire | Clavier/souris + tactile | **MVP** |
| **macOS desktop** | POS + Dashboard + tout | Employé + Propriétaire | Clavier/souris | Growth |

**Règle de parité** : Toute fonctionnalité accessible sur mobile est aussi accessible sur desktop, et inversement. Le desktop n'est PAS réservé au propriétaire — un employé peut utiliser Keevo sur un PC de comptoir ou une tablette tout comme sur son smartphone.

**Contraintes critiques :**
- Android entrée de gamme (2 Go RAM, écran 5"). App ≤ 100 Mo.
- L'employé utilise l'app debout, souvent d'une main, avec des clients qui attendent.
- Le design est **responsive-first** : touch targets larges (fonctionnent au doigt ET à la souris), layouts adaptifs (colonnes sur desktop, scroll vertical sur mobile), raccourcis clavier optionnels sur desktop pour les power users.
- **Offline = le mode par défaut**, pas un fallback. Le mode connecté est un bonus.

### Effortless Interactions

1. **Enregistrer une vente** → Sélection produit + quantité + tap "Payé". 3 gestes, 5 secondes max. Zéro calcul.
2. **Vérifier un stock ailleurs** → Tap "Chercher" + nom du produit → résultat instantané : "📍 Bonanjo : 3 disponibles"
3. **Clôturer la journée** → 1 tap. Flash résumé. Rapport auto-envoyé. Fini.
4. **Recevoir le résumé matinal** → Ouvrir l'app → 3 chiffres en 3 secondes. Pas de navigation.
5. **Créer un produit pendant une vente** → Nom + prix, c'est tout. Enrichissement plus tard.
6. **Inventaire** → Formulaire guidé : stock théorique affiché, employé saisit stock physique. Keevo calcule les écarts.
7. **Transfert inter-boutiques** → Sélection produit + boutique destination + quantité → Validé. Traçabilité automatique.

### Critical Success Moments

| Moment | Timing | Émotion utilisateur | Risque si raté |
|--------|--------|---------------------|---------------|
| **Première vente POS** | Minute 1 | "C'est aussi simple que ça ?" | "Trop compliqué" → Loïc refuse → Simon abandonne |
| **Premier rapport WhatsApp** | Jour 1, 20h | "C'est automatique ? Incroyable !" | "Encore une app qui ne fait rien" → désinstallation |
| **Premier check cross-boutique** | Semaine 1-2 | "J'aurais perdu cette vente sans Keevo" | Pas de "Aha moment" → abandon progressif |
| **Premier inventaire assisté** | Semaine 4 | "2h au lieu de 8h — plus jamais autrement" | Le déclic irréversible ne se produit pas |
| **Contrôle à distance** | Mois 2 (voyage) | "Je gère depuis Shanghai" | Simon ne perçoit jamais la valeur premium |

### Experience Principles

1. **🎯 "3 taps ou moins"** — Toute action fréquente doit être accomplie en 3 interactions maximum. Si ça en prend plus, on redésigne.

2. **🔇 "Le système travaille, l'humain profite"** — Les rapports partent seuls, les calculs se font seuls, les alertes arrivent toutes seules. L'utilisateur agit, Keevo réagit. Pas l'inverse.

3. **📱 "Debout, une main, des clients qui attendent"** — Chaque écran est conçu pour un employé debout dans une boutique bruyante avec un smartphone Android à 50 000 XAF. Large touch targets, texte lisible, feedback haptique. Et la même expérience doit être fluide sur un PC de comptoir.

4. **🌍 "Offline = Normal"** — Aucune feature ne doit "se casser" parce que le réseau est absent. L'indicateur de connexion est informatif, pas bloquant.

5. **❤️ "Libération, pas surveillance"** — Chaque interaction de traçabilité est présentée comme un outil qui libère (plus d'inventaire de 8h, plus de calculs manuels, plus d'accusations injustes), jamais comme un outil de contrôle.

## Desired Emotional Response

### Primary Emotional Goals

**Simon (Propriétaire) :**
> **Sérénité et contrôle.** "Mon business tourne et je le sais."

L'émotion dominante n'est pas l'excitation — c'est la sérénité. Simon vit dans l'anxiété permanente : "Est-ce qu'on m'a volé ? Est-ce que j'ai du stock ? Est-ce que mes boutiques tournent ?" Keevo remplace l'anxiété par la certitude tranquille.

**Loïc (Employé) :**
> **Libération et fierté.** "Mon travail est plus facile et je suis un bon vendeur."

Loïc passe de la frustration (calculs manuels, appels sans réponse, soupçons du patron) à la fierté professionnelle. Keevo fait de lui un meilleur vendeur, pas un employé surveillé.

### Emotional Journey Mapping

| Moment | Simon ressent... | Loïc ressent... |
|--------|-----------------|------------------|
| **Découverte** | Scepticisme : "Encore une app compliquée" | Anxiété : "Le patron va me surveiller" |
| **Onboarding** (5 min) | Surprise : "C'est rapide ?" | Soulagement : "C'est pas si compliqué" |
| **Première vente POS** | — | Satisfaction : "Plus simple que le cahier" |
| **Premier rapport WhatsApp** (J1) | Émerveillement : "C'est automatique !" | Libération : "Plus de 30 min de calculs" |
| **Premier check cross-boutique** (Sem. 1) | Puissance : "Je vois TOUT" | Héroïsme : "J'ai sauvé la vente !" |
| **Premier inventaire assisté** (Sem. 4) | Déclic irréversible | Victoire : "2h au lieu de 8h" |
| **Contrôle à distance** (Mois 2) | Sérénité totale : "Je gère depuis Shanghai" | Confiance : "Le patron me fait confiance" |
| **Erreur/bug** | Confiance : "Mes données sont sûres" | Protection : "La traçabilité me protège" |
| **Retour quotidien** | Rituel matinal : réassurance 3 sec | Réflexe naturel : "indispensable" |

### Micro-Emotions

**À cultiver :**
- ✅ **Confiance** > Scepticisme — Chaque interaction confirme la fiabilité du système
- ✅ **Accomplissement** > Frustration — Micro-feedback de succès après chaque action complétée
- ✅ **Fierté** > Anxiété — L'employé montre Keevo à ses amis, le propriétaire se sent "moderne"
- ✅ **Appartenance** > Isolation — Templates sectoriels : "cette app est pour MOI"

**À éviter absolument :**
- ❌ **Surveillance** — Jamais de tonalité "Big Brother". Traçabilité = protection, pas contrôle
- ❌ **Confusion** — Un écran = UNE action. Si l'utilisateur hésite, on a échoué
- ❌ **Culpabilité** — Le rapport auto "⏰" est un nudge, pas une réprimande
- ❌ **Exclusion** — Ne jamais donner l'impression que Keevo est "pour les tech-savvy"

### Design Implications

| Émotion cible | Approche UX |
|--------------|-------------|
| **Sérénité** (Simon) | Résumé matinal calme, couleurs apaisantes pour le dashboard, aucune alerte agressive |
| **Libération** (Loïc) | Micro-animations de succès après chaque vente, message "Bonne soirée !" à la clôture |
| **Confiance** | Indicateur de sync visible mais non intrusif, confirmations claires, données cohérentes |
| **Fierté** | Design premium pour que Loïc le montre à ses amis. Leaderboard (activé) = reconnaissance |
| **Appartenance** | Templates sectoriels avec icônes métier, tonalité chaleureuse en français, message motivationnel |
| **Anti-anxiété** | Mode offline transparent, pas de messages d'erreur effrayants, "tout va bien" par défaut |

### Emotional Design Principles

1. **"Sérénité par défaut"** — L'état normal de l'app est le calme. Les alertes sont réservées aux urgences réelles (stock critique). Pas de notifications agressives. Le silence = tout va bien.

2. **"Chaque tâche mérite un accomplissement"** — Micro-feedback positif après chaque action complétée : ✅ subtil, animation douce, message affirmatif. L'utilisateur finit chaque interaction en se sentant "bien".

3. **"La traçabilité comme bouclier"** — Le langage UX dit "protection", "transparence", "historique". Jamais "surveillance" ou "contrôle". L'audit trail protège TOUT LE MONDE.

4. **"Progressivité émotionnelle"** — Les features "sensibles" (leaderboard, stats comparatives) apparaissent uniquement quand la confiance est établie. Le design accompagne la maturation émotionnelle de l'utilisateur.

## UX Pattern Analysis & Inspiration

### Inspiring Products Analysis

Les utilisateurs cibles de Keevo (Simon, Loïc) n'utilisent aucune app de gestion. Leurs seules références UX sont des apps sociales et grand public. C'est un avantage stratégique : Keevo n'a pas à se comparer à des concurrents logiciels, il doit se comparer à WhatsApp et YouTube.

#### WhatsApp — La référence de communication

- **Ce qu'il fait bien :** Onboarding minimal. Numéro de téléphone = identifiant. L'app est opérationnelle rapidement.
- **Pattern clé :** La conversation comme interface — tout est organisé par conversations, pas par menus. L'utilisateur ne "navigue" pas, il "parle".
- **Leçon pour Keevo :** Les rapports sortent PAR WhatsApp, pas dans l'app. On s'intègre dans l'outil qu'ils connaissent déjà. Navigation par onglets en bas (3-4 max).

#### YouTube — Le benchmark de simplicité absolue

- **Ce qu'il fait bien :** "Tu entres, tu cherches, tu regardes." Trois actions, zéro réflexion. L'interface est une barre de recherche + une grille de résultats. Pas de configuration, pas de paramètres obligatoires.
- **Pattern clé :** L'action unique. YouTube a UNE barre de recherche en haut et le contenu occupe 95% de l'écran. Le focus est total sur le contenu, pas sur les menus.
- **Leçon pour Keevo :** "Tu ouvres, tu vends, c'est fait." L'écran POS doit être comme la home YouTube — une barre de recherche produit en haut, et les produits fréquents/récents en grille en dessous. Focus total sur l'action de vente.

#### Orange Money / MTN MoMo — L'expérience transactionnelle locale

- **Ce qu'il fait bien :** Transaction rapide. Saisie montant → confirmation → terminé. Tout le Cameroun connaît ce flow. Feedback immédiat : SMS de confirmation.
- **Pattern clé :** La confirmation transactionnelle — chaque action financière donne un feedback instantané et tangible.
- **Leçon pour Keevo :** Chaque vente enregistrée doit donner un feedback aussi satisfaisant qu'un SMS Orange Money. Le "✅ Vente enregistrée — 25 000 XAF" doit procurer la même sensation de "c'est fait, c'est sûr".

### Transferable UX Patterns

**Navigation :**
- **Barre de recherche universelle** (YouTube) → Rechercher un produit pour vendre OU vérifier stock, un seul point d'entrée
- **Feed/home intelligent** (réseaux sociaux) → L'écran d'accueil montre l'information la plus pertinente du moment (résumé matinal ou POS selon le rôle)
- **Navigation par tabs en bas** (WhatsApp) → 3-4 onglets max : Vendre, Stock, Rapports, Plus

**Interactions :**
- **Swipe actions** (réseaux sociaux) → Swipe pour actions rapides sur les produits (modifier prix, ajuster stock)
- **Pull-to-refresh** (universel) → Geste naturel pour sync manuelle
- **Long press context menu** (WhatsApp) → Actions secondaires sans surcharger l'écran principal

**Visuels :**
- **Cards avec images** (YouTube/Instagram) → Produits affichés en cards avec photo, nom, prix — pas de tableaux austères
- **Badges de notification** (réseaux sociaux) → Alertes stock bas, ventes en attente, produits brouillons à compléter
- **Avatars/icônes** (WhatsApp) → Icônes sectorielles pour les catégories produits au lieu de texte

### Onboarding Flow

**Inscription + configuration en 3 écrans (< 2 minutes) :**
1. 📱 **Écran 1** — Numéro de téléphone + mot de passe → Compte créé
2. 🏪 **Écran 2** — "Quel type de boutique ?" → Grille visuelle d'icônes :
   - 👗 Vêtements / 📱 Électronique / 📚 Librairie / 🍎 Alimentation / 💊 Pharmacie / 🔧 Quincaillerie / ➕ Autre
   - Un tap sur l'icône → Le template sectoriel se charge (catégories, unités, suggestions produits)
3. 🏷️ **Écran 3** — "Nom de votre boutique" → **Terminé !**

Après ça → directement sur l'écran POS, prêt à vendre. Pas de tutoriel obligatoire, pas de catalogue à remplir. L'ajout d'autres types de boutiques est accessible dans Paramètres > Boutiques > Ajouter une boutique avec le même sélecteur visuel.

### Anti-Patterns to Avoid

1. **❌ Formulaire d'inscription complexe** — Pas de RCCM, adresse, nombre d'employés avant de commencer. Juste téléphone + mot de passe + type de boutique + nom.
2. **❌ Dashboard comme page d'accueil** — Les apps B2B affichent 15 graphiques. Paralysant à tech literacy 4/10. L'accueil = 3 chiffres, pas 15 graphiques.
3. **❌ Menu hamburger avec 20 options** — 4 onglets max, comme WhatsApp.
4. **❌ Formulaires à champs obligatoires bloquants** — Le produit "brouillon" est valide.
5. **❌ Messages d'erreur techniques** — Jamais "Error 500". Toujours un message humain rassurant.
6. **❌ Import/export CSV comme onboarding** — Barrière infranchissable à tech literacy 4/10.

### Design Inspiration Strategy

**À Adopter :**
- Barre de recherche universelle (YouTube) → point d'entrée unique pour toutes les actions
- Confirmation transactionnelle type Mobile Money → feedback satisfaisant à chaque vente
- Navigation par onglets en bas (WhatsApp) → 4 tabs max
- Onboarding en 3 écrans avec sélection visuelle du type de boutique

**À Adapter :**
- Cards produits avec images (Instagram/YouTube) → adapter pour affichage compact en mode boutique
- Pull-to-refresh pour sync → adapter pour indiquer l'état offline/online
- Feed intelligent (réseaux sociaux) → adapter comme résumé matinal contextualisé par rôle

**À Éviter :**
- Formulaires longs (apps B2B classiques) → conflits avec adoption progressive
- Dashboards complexes (apps analytics) → conflits avec tech literacy 4/10
- Import CSV (apps de gestion) → barrière infranchissable
- Messages d'erreur techniques → conflits avec sérénité

## Design System Foundation

### Design System Choice

**Material Design 3 (Material You)** — Le design system natif de Flutter, personnalisé avec les tokens Keevo.

**Pourquoi Material 3 :**
1. Flutter = Material natif — composants les plus matures et performants dans Flutter
2. Les utilisateurs Android le connaissent instinctivement (WhatsApp, YouTube)
3. Touch targets 48dp minimum intégrés ("debout, une main, des clients qui attendent")
4. Système de thème dynamique Material You pour palette cohérente
5. Layouts adaptatifs natifs (compact / medium / expanded) pour parité mobile ↔ desktop
6. Accessibilité WCAG certifiée

### Design Tokens Keevo

**Palette "Indigo Sky" :**
- Primaire (gradient) : `#3B5BDB` → `#4DABF7` (indigo profond → bleu ciel électrique)
- Primaire unique : `#3B5BDB` (indigo royal) — boutons, nav active
- Primaire clair : `#D0EBFF` (bleu brume) — fonds de cards, sélection
- Accent CTA : `#FF6B6B` (corail chaud) — call-to-action secondaires, prix
- Succès : `#51CF66` (vert lime doux) — confirmations vente, sync OK
- Alerte : `#FCC419` (ambre doré) — stock bas, brouillons
- Danger : `#FA5252` (rouge rubis) — ruptures, erreurs
- Surface (light) : `#FFFFFF` (blanc pur)
- Surface alt : `#F8F9FA` (gris glacier)
- Texte principal : `#212529` (anthracite profond)
- Texte secondaire : `#868E96` (gris ardoise)
- Surface (dark) : `#0D1B2A` (navy nuit)
- Surface alt (dark) : `#1B2838` (navy moyen)

**Typographie :** Inter (Google Fonts) — lisible, moderne, poids variable (300-700)

**Forme :** Border radius 16dp pour cards, 24dp pour boutons — look chaleureux, accessible

**Spacing :** Grille 8dp standard Material, padding généreux pour touch targets

### Customization Strategy

**Composants Material standard :**
Buttons, Cards, Navigation Bar, App Bar, Bottom Sheets, Dialogs, Text Fields, Chips

**Composants Custom Keevo :**
- **POS Card** — affichage produit vente (photo + nom + prix + tap to add)
- **Sync Indicator** — état connexion/sync non intrusif
- **Morning Summary Card** — widget "moment café" résumé matinal
- **Shop Type Selector** — grille visuelle d'icônes pour type boutique
- **WhatsApp Report Preview** — aperçu du format de rapport
- **Day Close Button** — bouton de clôture journalière proeminent

### Implementation Approach

```
Material 3 (base Flutter)
├── Design Tokens Keevo (palette Indigo Sky, Inter, radius, spacing)
├── ThemeData Flutter (light + dark, ColorScheme dynamique)
├── Composants Material (buttons, cards, navigation, forms, dialogs)
├── Composants Custom Keevo (POS card, sync indicator, etc.)
├── Layouts Adaptatifs (LayoutBuilder → compact / medium / expanded)
└── Animations (micro-feedback succès, transitions douces)
```

## Defining Core Experience

### Defining Experience

> **"Tu ouvres, tu tapes le produit, c'est vendu."**

L'action utilisateur la plus fréquente et critique de Keevo est l'enregistrement d'une vente via le POS. C'est l'interaction qui se répète 15-50 fois par jour, par boutique. Si cette action prend plus de 10 secondes, tout le système s'effondre — Loïc retourne au cahier.

C'est cette interaction que Simon décrira à ses amis : *"Mon employé tape le nom, il vend, et moi le soir je reçois tout sur WhatsApp."*

### User Mental Model

Le modèle mental de Simon et Loïc est le **cahier de vente**. Keevo ne remplace pas le cahier — il EST le cahier, mais en mieux.

| Cahier (actuel) | Keevo (nouveau) |
|----------------|------------------|
| Écrire le nom du produit | Taper/chercher le produit |
| Écrire le prix | Prix pré-rempli (auto) |
| Écrire la quantité | Sélectionner quantité (+/-) |
| Calculer le total à la main | Total automatique |
| Bilan fin de journée = 30-45 min | Clôture = 1 tap |

Le flow de pensée est identique. L'utilisateur ne doit jamais se sentir perdu.

### Success Criteria

| Critère | Cible |
|---------|-------|
| Temps pour 1 vente (1 produit) | ≤ 5 secondes |
| Temps pour 1 vente (3 produits) | ≤ 15 secondes |
| Nombre de taps pour 1 vente simple | ≤ 3 taps |
| Temps de recherche produit | < 1 seconde (SQLite local) |
| Feedback de confirmation | < 200ms |
| Fonctionnement offline | 100% identique à online |
| Première utilisation réussie sans tutoriel | > 90% |

### Novel UX Patterns

**Approche :** Patterns établis combinés de façon innovante. Pas d'invention radicale.

**Patterns familiers :** Barre de recherche (YouTube), grille d'éléments (Instagram), confirmation transactionnelle (Orange Money), tab navigation (WhatsApp).

**Notre touche unique :** La **création de produit à la volée pendant la vente**. Aucun POS ne fait ça. Le produit n'existe pas ? Tape un nom et un prix, la vente continue. Le catalogue se construit par l'usage.

### Experience Mechanics

**1. Initiation (0 sec) :**
- Loïc ouvre l'app → déjà sur l'écran POS
- Barre de recherche en haut + grille de produits fréquents/récents
- Panier vide en bas (discret, se développe quand rempli)

**2. Interaction (~3-5 sec) :**
- **Recherche** : premières lettres → résultats instantanés (fuzzy search SQLite)
- **Sélection** : tap produit → ajouté au panier (quantité 1, prix par défaut pré-rempli)
- **Prix modifiable** : tap sur le prix dans le panier → champ numérique éditable (réductions, promos, négociation). Le prix catalogue n'est jamais modifié.
- **Quantité** : +/- pour ajuster OU saisie directe
- **Produit inconnu** : "Aucun résultat → Créer" → nom + prix → ajouté au panier ET créé en brouillon
- **Multi-produits** : répéter, panier s'actualise en temps réel

**3. Feedback (instantané) :**
- Animation subtile (card glisse dans le panier) + vibration haptique
- Total mis à jour en temps réel
- Highlight bleu clair `#D0EBFF` sur la card ajoutée

**4. Complétion (~1 sec) :**
- Tap **"Encaisser"** (bouton large, primaire indigo, en bas)
- Sélection paiement : Espèces / Mobile Money (2 boutons)
- ✅ **"Vente enregistrée — 47 500 XAF"** + animation succès lime
- Retour auto à l'écran POS vide → prêt pour la suite

## Visual Design Foundation

### Color System

**Palette Indigo Sky** appliquée avec tokens sémantiques MaterialTheme :

| Token sémantique | Light Mode | Dark Mode | Usage |
|-----------------|------------|-----------|-------|
| `colorPrimary` | `#3B5BDB` | `#4DABF7` | Boutons primaires, nav active |
| `colorOnPrimary` | `#FFFFFF` | `#0D1B2A` | Texte sur fond primaire |
| `colorPrimaryContainer` | `#D0EBFF` | `#1B3A5C` | Fonds de cards, chips, sélection |
| `colorSecondary` | `#FF6B6B` | `#FF8787` | Accent CTA, prix promotionnels |
| `colorSuccess` | `#51CF66` | `#69DB7C` | Confirmations, sync OK |
| `colorWarning` | `#FCC419` | `#FFD43B` | Stock bas, brouillons |
| `colorError` | `#FA5252` | `#FF6B6B` | Ruptures, erreurs |
| `colorSurface` | `#FFFFFF` | `#0D1B2A` | Fond principal |
| `colorSurfaceVariant` | `#F8F9FA` | `#1B2838` | Fond secondaire |
| `colorOnSurface` | `#212529` | `#F0F4F8` | Texte courant |
| `colorOnSurfaceVariant` | `#868E96` | `#ADB5BD` | Texte secondaire |

**Gradient primaire :** `LinearGradient(#3B5BDB → #4DABF7)` — AppBar, onboarding, boutons premium.

**Contrastes WCAG AA :** Texte sur surface 15.4:1 ✅ | Primaire sur blanc 5.2:1 ✅ | Vérifié light ET dark.

### Typography System

**Police :** Inter (Google Fonts) — variable font, poids 300-700.

| Niveau | Taille | Poids | Usage |
|--------|--------|-------|-------|
| **Display** | 32sp | Bold 700 | Résumé matinal ("562 500 XAF") |
| **Headline** | 24sp | SemiBold 600 | Titres d'écran |
| **Title** | 20sp | SemiBold 600 | Noms de boutique, sections |
| **Body Large** | 16sp | Regular 400 | Texte courant, noms de produits |
| **Body** | 14sp | Regular 400 | Descriptions, labels |
| **Label** | 12sp | Medium 500 | Badges, timestamps |
| **Caption** | 11sp | Regular 400 | Texte tertiaire |

**Règle :** Taille minimale 14sp pour tout texte interactif (boutons, liens, labels).

### Spacing & Layout Foundation

**Grille de base :** 8dp (Material standard)

| Token | Valeur | Usage |
|-------|--------|-------|
| `xs` | 4dp | Entre icône et label |
| `sm` | 8dp | Padding interne cards |
| `md` | 16dp | Marges standard |
| `lg` | 24dp | Séparation de blocs |
| `xl` | 32dp | Marges d'écran |
| `xxl` | 48dp | Séparation header/content |

**Breakpoints adaptatifs :**

| Breakpoint | Type | Colonnes | Usage |
|-----------|------|----------|-------|
| < 600dp | **Compact** | 1 col | Mobile — POS plein écran |
| 600-840dp | **Medium** | 2 col | Tablette portrait — POS + panier côte à côte |
| > 840dp | **Expanded** | 3 col | Desktop — POS + panier + détails |

**Touch targets :** 48x48dp minimum, espacés de 8dp.

**Navigation :** Bottom Navigation Bar (compact) → Navigation Rail (expanded). 4 destinations : 🛒 Vendre / 📦 Stock / 📊 Rapports / ⚙️ Plus.

### Accessibility Considerations

- **Contrastes :** Tous textes ≥ 4.5:1 (WCAG AA). Vérifié light ET dark.
- **Touch targets :** 48x48dp minimum (Material 3).
- **Font scaling :** Supporte Dynamic Type / zoom 200%.
- **Mode sombre :** Natif MaterialTheme, suit préférences système.
- **Non-couleur :** Alertes = couleur + icône + texte (⚠️ Stock bas, pas juste orange).
- **Clavier :** Tab order logique, focus visible sur desktop.
- **Langue :** Interface en français. RTL non requis.

## Design Direction Decision

### Directions Explorées

Trois directions visuelles générées et évaluées :

1. **Direction A — "Clean & Airy" (Light)** — Fond blanc, gradient indigo→sky en header, product cards glassmorphism, panier flottant pill-shape, bouton "Encaisser" gradient. Style Stripe/Linear.

2. **Direction B — "Dark & Premium"** — Navy `#0D1B2A`, cards glassmorphism avec blue glow, prix en bleu électrique, bouton gradient néon. Style Vercel/Raycast.

3. **Direction C — "Moment Café" (Dashboard)** — Header gradient avec salutation contextualisée, hero card CA avec sparkline tendancielle, métriques en cards compactes, cards boutiques avec indicateurs. Style fintech (Revolut/Wise).

### Chosen Direction

**Combinaison A + B + C :** Direction A (Light) comme mode **par défaut**, Direction B (Dark) en mode sombre **automatique** (suit les préférences système), Direction C comme template pour le **dashboard propriétaire**.

### Design Rationale

- Le mode clair avec glassmorphism subtil est professionnel et lisible en boutique bien éclairée
- Le dark mode navy économise la batterie OLED et offre une expérience premium en faible lumière
- Le dashboard matinal style fintech crée le "moment café" serein défini dans les objectifs émotionnels
- Layout propre sans débordement pour le dashboard (hero card contenue dans le layout)
- Prix éditable dans le panier POS pour réductions/promos sans modifier le prix catalogue

### Implementation Notes

- Gradient AppBar : `LinearGradient(#3B5BDB → #4DABF7)` en mode clair, flat `#0D1B2A` en dark
- Glassmorphism : `BackdropFilter` Flutter avec blur 10-20, opacité border 30%
- Panier flottant : `BottomSheet` persistant avec pill-shape, expand on tap
- Dark mode : `ThemeMode.system` — suit automatiquement les préférences Android/iOS

## User Journey Flows

24 flows couvrant FR1-FR93. Organisés par catégorie d'usage.

### Onboarding & Setup

#### Flow 1 — Onboarding (1ère ouverture)
**Acteur :** Simon | **Fréquence :** 1x | **FRs :** FR1-FR15

Télécharger → Créer compte (téléphone + mdp) → Choisir type boutique (grille icônes) → Template sectoriel appliqué → Nom de la boutique → Tenant provisionné → Écran POS prêt. Ajout produits optionnel (adoption progressive).

#### Flow 2 — Ajouter un Employé
**Acteur :** Simon | **Fréquence :** Occasionnel | **FRs :** FR65-68, FR35-36

Paramètres > Employés → Tap "Ajouter" → Saisir numéro téléphone → Assigner boutique → Choisir rôle: Employé → Mot de passe auto-généré → Simon communique identifiants → Loïc se connecte → **Changement mot de passe obligatoire** → Loïc crée son mdp → Accès POS.

#### Flow 15 — Ajouter une Boutique
**Acteur :** Simon | **Fréquence :** Rare | **FRs :** FR30, FR16-17

Paramètres > Mes boutiques → Tap "Ajouter" → Vérification limite plan → Saisir nom + localisation → Boutique créée → Assigner employé (optionnel).

#### Flow 21 — Connexion / Login Multi-Device
**Acteur :** Simon/Loïc | **Fréquence :** Occasionnel | **FRs :** FR3, FR69-76

Ouvrir Keevo sur device → Saisir numéro + mdp → Auth JWT → Si 1ère connexion employé: changement mdp obligatoire → Sync données locales → Prêt.

### Catalogue & Stock

#### Flow 3 — Ajouter/Modifier un Produit
**Acteur :** Simon | **Fréquence :** Régulier | **FRs :** FR21-29

Stock > Catalogue → Nouveau produit → Nom + Photo → Prix vente + Prix achat + Transport → Catégorie (pré-remplie template) → Variantes (taille/couleur) optionnelles → Seuil stock minimum → Assigner aux boutiques + quantités → Marge nette calculée auto.

#### Flow 14 — Entrée en Stock (Réception Marchandise)
**Acteur :** Simon | **Fréquence :** Hebdomadaire | **FRs :** FR27, FR34, FR89

Marchandise arrive → Stock > Entrée de stock → Sélectionner fournisseur → Ajouter produits reçus + quantités → Si produit n'existe pas: créer (nom + prix achat + transport) → Incrémenter stock → Récap (X produits, Y unités, valeur totale) → Distribuer aux boutiques via Flow 6 (optionnel).

#### Flow 17 — Import Produits CSV
**Acteur :** Simon | **Fréquence :** 1x (migration) | **FRs :** FR28

Stock > Importer CSV → Télécharger template → Remplir fichier → Uploader → Aperçu: X produits détectés → Afficher erreurs si présent → Confirmer import → Assigner aux boutiques.

#### Flow 6 — Transfert Stock Inter-Boutiques
**Acteur :** Simon | **Fréquence :** Hebdomadaire | **FRs :** FR32, FR34, FR27

Stock > Transferts → Boutique source → Boutique destination → Produits + quantités → Confirmer → Stock source réduit → Stock destination augmenté → Tracé dans historique.

#### Flow 19 — Ajustement Manuel de Stock
**Acteur :** Simon | **Fréquence :** Occasionnel | **FRs :** FR27, FR84

Stock > Historique mouvements → Sélectionner produit → Ajuster stock → Nouvelle quantité → Motif obligatoire (Casse / Perte / Don / Erreur) → Stock ajusté → Tracé dans audit trail.

#### Flow 11 — Gestion Clients & Fournisseurs
**Acteur :** Simon | **Fréquence :** Occasionnel | **FRs :** FR87-89

Plus > Clients ou Fournisseurs → Ajouter: nom + téléphone (client) ou nom + pays + contact (fournisseur) → Client disponible lors des ventes → Fournisseur associé aux produits.

### Vente Quotidienne

#### Flow 4 — Enregistrer une Vente (POS)
**Acteur :** Loïc | **Fréquence :** 15-50x/jour | **FRs :** FR37-44, FR88, FR39

Ouvrir Keevo → Écran POS → Recherche produit (fuzzy < 1s) ou tap grille → Ajouté au panier (prix par défaut pré-rempli) → **Prix modifiable** (tap → saisie promo/réduction, catalogue non modifié) → Associer client (optionnel) → Répéter pour multi-produits → Tap "Encaisser" → Paiement: Espèces / Mobile Money → ✅ "Vente enregistrée" + animation succès → Retour POS vide.

Si **produit inconnu** : "Aucun résultat → Créer" → nom + prix → ajouté au panier ET créé en brouillon.

#### Flow 5 — Vérification Stock Cross-Boutique
**Acteur :** Loïc | **Fréquence :** Plusieurs fois/jour | **FRs :** FR33, FR40, FR31

Client demande produit → Recherche dans POS → Si pas en stock ici → Voir disponibilité autres boutiques → "Boutique Bonanjo: 3 en stock ✅" → Client orienté → Vente sauvée.

#### Flow 24 — Consulter Historique Ventes
**Acteur :** Loïc | **Fréquence :** Quotidien | **FRs :** FR43

POS > Mes ventes → Liste des ventes du jour → Filtrer par date → Voir détails: produits, montants, heure, mode de paiement.

#### Flow 12 — Correction/Annulation de Vente
**Acteur :** Simon | **Fréquence :** Rare | **FRs :** FR84, FR90

Rapports > Historique ventes → Sélectionner vente → Annuler ou Corriger → Justification obligatoire → Stock réajusté auto → Modification tracée dans audit trail.

### Fin de Journée

#### Flow 8 — Clôture Journalière
**Acteur :** Loïc | **Fréquence :** 1x/jour | **FRs :** FR41-42, FR51, FR57

Notification rappel → Tap Clôturer → Résumé auto (CA, espèces, MoMo, nb ventes) → Comptage caisse optionnel (saisie montant réel → écart signalé si significatif) → Confirmer → ✅ Journée clôturée → Rapport WhatsApp → Simon → "Bonne soirée Loïc ! 🌙".

### Rapports & Monitoring

#### Flow 7 — Résumé Matinal (Dashboard)
**Acteur :** Simon | **Fréquence :** 1x/jour | **FRs :** FR50, FR56, FR63

Simon ouvre Keevo le matin → "Bonjour Simon 👋" + message motivationnel → Hero card: CA hier + tendance 7j + sparkline → Métriques: ventes / alertes / boutiques → Action si nécessaire (alertes, comparaison boutiques) ou sérénité.

#### Flow 9 — Rapport WhatsApp
**Acteur :** Simon | **Fréquence :** 1x/jour + hebdo | **FRs :** FR50-60

Simon reçoit notification WhatsApp → Rapport formaté: CA, ventes, top produits → Si besoin détails: ouvrir Keevo > Rapports → Dashboard graphiques + filtres → Export PDF/Excel (plan payant).

#### Flow 20 — Réaction aux Alertes Stock Critique
**Acteur :** Simon | **Fréquence :** Au besoin | **FRs :** FR26, FR61-62

Push + WhatsApp "Stock bas!" → Liste produits sous seuil → Commander (contacter fournisseur) ou Transférer (Flow 6) ou Ignorer (marquer lue).

### Inventaire

#### Flow 10 — Inventaire Assisté (Weekend)
**Acteur :** Simon + Loïc | **Fréquence :** Mensuel/trimestriel | **FRs :** FR45-49, FR59

Simon lance inventaire → Mode Inventaire activé → Produits par catégorie → Sélectionner produit → Quantité théorique affichée → Loïc saisit quantité réelle → Écart signalé si présent → Répéter → Résumé (comptés, écarts, valeur) → Ajuster stock en 1 clic → Rapport inventaire WhatsApp.

### Administration

#### Flow 16 — Désactiver/Révoquer un Employé
**Acteur :** Simon | **Fréquence :** Rare | **FRs :** FR67-68

Paramètres > Employés → Sélectionner → Voir activité (dernière connexion, ventes) → Désactiver: confirmation → Sessions révoquées immédiatement → Historique ventes conservé.

#### Flow 13 — Souscription & Limites
**Acteur :** Simon | **Fréquence :** Rare | **FRs :** FR16-20

Limite free atteinte → Message + CTA Premium → Contact Toor ou paiement MoMo → Toor active manuellement → Premium actif, limites levées.

#### Flow 18 — Configuration Préférences
**Acteur :** Simon | **Fréquence :** 1x (setup) + rare | **FRs :** FR12, FR60, FR7, FR91-92

Plus > Paramètres → Notifications (heure rapport), Alertes (seuils stock), Rapports (types activés), Compte (souscription, suppression), Aide (tutoriels, feedback).

### Technique

#### Flow 22 — Sync Forcée (7 jours sans connexion)
**Acteur :** Simon/Loïc | **Fréquence :** Rare | **FRs :** FR70-74

7 jours offline → Accès suspendu → "Sync obligatoire" → Internet disponible → Sync auto < 60s → Résolution conflits delta-based → Accès restauré.

### Super Admin

#### Flow 23 — Gestion Plateforme (Toor)
**Acteur :** Toor | **Fréquence :** Quotidien | **FRs :** FR77-83

Super Admin Dashboard → Vue d'ensemble → Tenants (liste, statut, plan, sync) → Revenus (MRR, conversion, churn) → Analytics (ventes, boutiques, base produits) → Alertes (J+5 sans sync, abonnements expirants) → Santé système (serveurs, logs) → Actions: activer payant, suspendre, notification push globale.

### Flow Optimization Principles

1. **Minimum de taps** — Chaque flow optimisé pour le chemin le plus court vers le succès
2. **Récupération gracieuse** — Aucune erreur bloquante. Skip toujours possible. Données jamais perdues
3. **Feedback continu** — L'utilisateur sait toujours où il en est (progression, total, sync)
4. **Sortie positive** — Chaque flow se termine par un message positif
5. **Offline = Normal** — Tous les flows critiques (4, 5, 8, 10) fonctionnent 100% offline

### Patterns Réutilisables

| Pattern | Flows concernés | Implémentation |
|---------|----------------|----------------|
| Confirmation transactionnelle | 4, 8, 10, 14 | ✅ + montant + animation lime |
| Rapport WhatsApp auto | 8, 9, 10 | Message formaté envoyé au propriétaire |
| Recherche fuzzy | 4, 5, 10 | SQLite local < 1s |
| Création à la volée | 4, 14 | Produit brouillon créé dans le flow |
| Justification obligatoire | 12, 19 | Motif requis pour audit trail |
| Notification nudge | 8, 20 | Rappel non-intrusif |
| Changement mdp forcé | 2, 21 | 1ère connexion employé |

## Component Strategy

### Design System Components (Material 3)

| Composant Material | Usage Keevo |
|-------------------|-------------|
| AppBar (Top) | Header gradient Indigo Sky + logo + sync indicator |
| NavigationBar (Bottom) | 4 onglets: Vendre / Stock / Rapports / Plus |
| NavigationRail | Version desktop (expanded layout) |
| SearchBar | Recherche produit fuzzy (POS, inventaire) |
| Card | Produits, boutiques, métriques |
| TextField | Saisie prix, quantités, noms |
| ElevatedButton | "Encaisser", "Clôturer", CTAs principaux |
| BottomSheet | Panier POS, détails produit |
| Dialog | Confirmations (désactiver employé, annuler vente) |
| Chip | Filtres (catégorie, date, boutique) |
| ListTile | Listes employés, historique ventes, mouvements stock |
| SnackBar | Feedback succès/erreur post-action |
| Switch / Checkbox | Préférences, sélection inventaire |
| Badge | Compteur panier, alertes non lues |
| ProgressIndicator | Sync, inventaire progression |
| TabBar | Sous-navigation (espèces/MoMo, jour/semaine/mois) |

### Custom Components Keevo (12)

#### 1. POS Product Card
- **Purpose :** Card produit pour la grille POS
- **Contenu :** Photo produit, nom, prix (XAF), bouton (+)
- **États :** Défaut | Sélectionné (`#D0EBFF` highlight) | En rupture (grisé + badge ⚠️)
- **Interaction :** Tap → ajout panier + animation gliss + haptique

#### 2. Cart Pill (Panier Flottant)
- **Purpose :** Résumé panier toujours visible en bas du POS
- **Contenu :** Nb articles, total XAF, bouton "Encaisser"
- **États :** Vide (masqué) | Rempli (pill visible) | Expanded (détails panier)
- **Interaction :** Tap → expand | Swipe up → détails avec prix éditables

#### 3. Morning Summary Hero Card
- **Purpose :** CA hier en grand format pour le "moment café"
- **Contenu :** Montant display (32sp), tendance ↑↓ %, sparkline 7j
- **États :** Données OK | Aucune donnée | Sync en cours
- **Glassmorphism :** Overlay sur gradient header

#### 4. Shop Status Card
- **Purpose :** Card boutique dans le dashboard
- **Contenu :** Nom boutique, CA, indicateur status (vert/orange/rouge), tendance %
- **Interaction :** Tap → détails boutique

#### 5. Metric Badge Card
- **Purpose :** Métrique compacte (ventes, alertes, boutiques)
- **Contenu :** Icône + nombre + label
- **Variantes :** Bleu (info), Orange (warning), Vert (success)

#### 6. Sync Indicator
- **Purpose :** État connexion/sync non-intrusif
- **Contenu :** Dot vert/orange/rouge + texte ("En ligne" / "Hors-ligne 2j")
- **Position :** AppBar trailing
- **États :** Online synced | Online syncing | Offline OK (<7j) | Offline critique (>5j)

#### 7. Day Close Button
- **Purpose :** Bouton prominent de clôture journalière
- **Contenu :** "Clôturer la journée" + icône lune
- **États :** Disponible | Déjà clôturé (grisé) | Rappel (badge notification)

#### 8. Shop Type Selector
- **Purpose :** Grille visuelle sélection type boutique (onboarding)
- **Contenu :** 4 templates: 👗 Vêtements, 📱 Électronique, 📚 Librairie, 🏠 Électroménager
- **Interaction :** Tap → sélection avec animation ripple

#### 9. Inventory Row
- **Purpose :** Ligne de saisie inventaire (théorique vs réel)
- **Contenu :** Nom produit, quantité théorique, champ saisie réel, badge écart
- **États :** Non saisi | Match ✅ | Écart ⚠️

#### 10. WhatsApp Report Preview
- **Purpose :** Aperçu du rapport WhatsApp avant envoi
- **Contenu :** Message formaté avec CA, ventes, top produits
- **Interaction :** Lecture seule + bouton "Envoyer sur WhatsApp"

#### 11. Stock Movement Card
- **Purpose :** Ligne d'historique mouvement de stock
- **Contenu :** Type (entrée/sortie/transfert/ajustement), quantité, date, acteur
- **Couleurs :** Vert (entrée), Rouge (sortie), Bleu (transfert), Orange (ajustement)

#### 12. Alert Action Card
- **Purpose :** Alerte stock actionnable
- **Contenu :** Produit, stock actuel, seuil, boutique
- **Actions :** Commander | Transférer | Ignorer
- **Urgence :** Amber (bas) | Rouge (rupture totale)

### Component Implementation Roadmap

| Phase | Composants | Justification |
|-------|-----------|---------------|
| **P1 — Core MVP** | POS Card, Cart Pill, Sync Indicator, Day Close Button, Shop Type Selector | Flows 1, 4, 8 — usage quotidien |
| **P2 — Dashboard** | Morning Hero, Shop Status, Metric Badge, Alert Action | Flows 7, 20 — expérience propriétaire |
| **P3 — Gestion** | Inventory Row, Stock Movement, WhatsApp Preview | Flows 10, 14, 9 — opérations hebdo/mensuelles |

### Implementation Strategy

- **Base :** Tous les composants custom sont construits sur les tokens Material 3 (couleurs, typo, spacing)
- **Thème :** `ThemeExtension<KeevoComponents>` Flutter pour intégrer les custom au système de thème
- **Accessibilité :** `Semantics` Flutter sur chaque composant custom, focus traversal testé
- **Responsivité :** Tous les composants adaptent leur layout selon le breakpoint (compact/medium/expanded)

## UX Consistency Patterns

### Button Hierarchy

| Niveau | Composant | Style | Usage |
|--------|----------|-------|-------|
| **Primaire** | `ElevatedButton` | Gradient Indigo Sky, texte blanc, 48dp | "Encaisser", "Clôturer", "Confirmer" — 1 par écran max |
| **Secondaire** | `OutlinedButton` | Bordure `#3B5BDB`, texte indigo | "Annuler", "Transférer", "Voir détails" |
| **Tertiaire** | `TextButton` | Texte indigo, sans bordure | "Plus tard", "Skip", liens |
| **Danger** | `ElevatedButton` | Fond `#FA5252`, texte blanc | "Désactiver", "Supprimer" — toujours avec Dialog |
| **Flottant** | `FAB` | Gradient, icône + | "Ajouter produit" — 1 par écran max |

Règles : 1 seul bouton primaire par écran. Boutons danger précédés d'un Dialog. Touch target 48x48dp min, espacement 8dp.

### Feedback Patterns

| Situation | Composant | Style | Durée |
|-----------|----------|-------|-------|
| Succès transactionnel | SnackBar + Haptique | Fond `#51CF66`, icône ✅ | 3s auto-dismiss |
| Erreur système | SnackBar | Fond `#FA5252`, icône ❌ + "Réessayer" | Persistant |
| Alerte attention | Banner in-page | Fond `#FCC419` 15% opacity, ⚠️ | Persistant |
| Info contextuelle | SnackBar | Fond `#3B5BDB`, icône ℹ️ | 4s auto-dismiss |
| Sync réussie | Sync Indicator | Dot vert + "Mis à jour" | 2s |
| Offline activé | Banner haut d'écran | Fond `#FCC419` | Persistant |

Règles : Succès = haptique légère. Erreurs jamais auto-dismiss. Messages en français, ton positif.

### Form Patterns

| Pattern | Comportement |
|---------|-------------|
| Validation | En temps réel (au blur), bordure `#FA5252` + texte erreur |
| Champs obligatoires | Astérisque rouge (*) |
| Prix | Clavier numérique auto, format "XXX XXX XAF" |
| Quantités | Stepper +/- (tap rapide) + saisie directe (tap long) |
| Recherche | Fuzzy < 500ms, highlight match, "Aucun résultat → Créer" |
| Auto-save | Brouillons sauvés auto. Indicateur "Sauvegardé ✓" |
| Justification | Champ texte obligatoire pour actions sensibles |

### Navigation Patterns

| Pattern | Comportement |
|---------|-------------|
| Principale | Bottom NavBar mobile (4 items) / NavRail desktop. Toujours visible |
| Retour | AppBar back arrow haut gauche. Pas de geste swipe |
| Modale vs Push | Actions courtes = BottomSheet. Écrans complets = Push navigation |
| Deep linking | Tap notification → écran concerné directement |
| Breadcrumbs | Desktop uniquement |

### Empty States & Loading

| État | Pattern |
|------|---------|
| Vide (1ère fois) | Illustration + texte encourageant + CTA |
| Vide (filtres) | "Aucun résultat" + "Effacer les filtres" |
| Chargement | Skeleton screens (shimmer), pas de spinner plein écran |
| Sync | ProgressIndicator linéaire haut + "Synchronisation..." |
| Erreur réseau | Illustration offline + "Réessayer" |
| Donnée ancienne | Label discret "Dernière MAJ: il y a 2h" |

### Confirmation & Destruction Patterns

| Action | Pattern |
|--------|---------|
| Non-destructive | Exécution immédiate + SnackBar "Annuler" (undo 5s) |
| Destructive | Dialog confirmation: titre + description + 2 boutons |
| Irréversible | Dialog + saisie obligatoire ("SUPPRIMER") |
| Multi-étapes | Stepper horizontal visible + sauvegarde à chaque étape |

### List & Data Patterns

| Pattern | Comportement |
|---------|-------------|
| Tri | Défaut: pertinence. Options: nom, prix, date, stock |
| Filtres | Chips horizontaux scrollables au-dessus de la liste |
| Pagination | Scroll infini avec indicateur de chargement |
| Action rapide | Swipe left = action secondaire. Desktop: boutons au hover |
| Sélection multiple | Long press active le mode. Barre d'action en haut |

## Responsive Design & Accessibility

### Responsive Strategy

**Approche :** Mobile-first. App conçue pour smartphone Android entrée de gamme, adaptée vers tablette et desktop.

| Device | Breakpoint | Colonnes | Navigation | Layout POS |
|--------|-----------|----------|------------|------------|
| **Mobile** | < 600dp (Compact) | 1 col | Bottom NavBar (4 items) | Grille 2 col + panier Cart Pill |
| **Tablette** | 600-840dp (Medium) | 2 col | Bottom NavBar | Grille 3 col + panier latéral |
| **Desktop** | > 840dp (Expanded) | 3 col | NavigationRail (gauche) | Grille 4 col + panier + détails |

**Adaptations par écran :**

| Écran | Compact | Medium | Expanded |
|-------|---------|--------|----------|
| POS | Grille 2 col + Cart Pill | Grille 3 col + panier droit | Grille 4 col + panier + détails produit |
| Dashboard | Cards empilées, scroll | Cards 2 colonnes | Hero + métriques + boutiques 3 col |
| Inventaire | Liste full-width | 2 col: liste + aperçu | 3 col: catégories + liste + saisie |
| Catalogue | Liste swipe actions | Grille cards | Table colonnes triables |

**Règles :** Pas de perte de fonctionnalité entre breakpoints. `LayoutBuilder` + `AdaptiveLayout` Flutter.

### Accessibilité — WCAG 2.1 AA

**Contrastes vérifiés :**

| Paire | Ratio | Cible | Statut |
|-------|-------|-------|--------|
| Texte `#212529` sur `#FFFFFF` | 15.4:1 | ≥ 4.5:1 | ✅ |
| Texte `#F0F4F8` sur `#0D1B2A` | 14.8:1 | ≥ 4.5:1 | ✅ |
| Primaire `#3B5BDB` sur blanc | 5.2:1 | ≥ 4.5:1 | ✅ |
| Succès `#51CF66` sur blanc | 2.8:1 | ≥ 3:1 (large) | ✅ icône+texte |

**Touch targets :** 48x48dp minimum (Material 3). **Font scaling :** `textScaleFactor` + Dynamic Type iOS, testé 200%. **Non-couleur :** Alertes = couleur + icône + texte. **Clavier desktop :** Tab order logique, focus ring `#3B5BDB` 2dp. **Screen reader :** `Semantics` Flutter sur tous composants custom.

### Testing Strategy

**Responsive :**
- Devices physiques : Samsung Galaxy A14 (2Go RAM), iPhone SE, iPad, desktop Linux
- Flutter DevTools responsive preview
- Tests performance Android entry-level (> 30 FPS)

**Accessibilité :**
- `flutter_test` + assertions `Semantics`
- TalkBack (Android) + VoiceOver (iOS) sur devices réels
- Navigation clavier complète desktop
- Simulation daltonisme

**Utilisateur :**
- 5-10 commerçants pilotes (tech literacy 4/10)
- Observation en boutique réelle
- Mesure temps onboarding (cible < 10 min)

### Implementation Guidelines

**Responsive Flutter :**
- `LayoutBuilder` → switch breakpoints (Compact / Medium / Expanded)
- `AdaptiveLayout` pour transitions fluides
- Pas de pixels fixes — dp + sp uniquement

**Accessibilité Flutter :**
- `Semantics(label, button, enabled)` sur tous widgets custom
- `ExcludeSemantics` pour éléments décoratifs
- `FocusTraversalGroup` pour tab order
- `MediaQuery.boldTextOf` pour texte gras système

**Performance entry-level :**
- Images : lazy loading + cache + WebP
- Listes : `ListView.builder` (jamais `children`)
- Animations : `repaintBoundary` sur composants animés
- Stockage : app < 100 Mo (hors données)
