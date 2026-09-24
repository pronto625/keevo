# Story UI-2: Onboarding Soft/Modern Rebrand

**Epic**: 1 — Foundation, Infrastructure & Authentication
**Story ID**: UI-2 (UI improvement, hors sprint planifié — suite de UI-1)
**Status**: done
**Date**: 2026-09-12
**Branch**: (à la charge de l'utilisateur — non commité par l'agent)

---

## Overview

Première itération d'un rebrand progressif de Keevo, ciblant le flow de
premier lancement (le plus visible). Direction artistique : **plus moderne,
plus douce ("soft"), plus épurée**, en conservant l'indigo `#3B5BDB` comme
couleur d'ancrage de marque — évolution, pas rupture. Voir l'addendum de
spec : `_bmad-output/planning-artifacts/ux-design-specification.md`
§ "Addendum : Rebrand Onboarding — Direction Soft/Modern (2026-09-12)".

Périmètre : les 4 écrans du flow onboarding complet.

```
/onboarding → /terms → /onboarding/sector → /onboarding/shop-name
  (Welcome)    (CGU)      (Secteur)             (Nom boutique)
```

Aucune logique métier, navigation, clé (`Key`) ou texte de validation n'a
été modifié — uniquement l'habillage visuel (`build()` methods, tokens de
thème, décorations).

---

## Files Changed

### New files

| File | Description |
|---|---|
| `lib/features/onboarding/presentation/widget/onboarding_progress_dots.dart` | Indicateur d'étape partagé (`step`/`total`) affiché sur les 4 écrans pour matérialiser la progression du wizard — absent de la version précédente |

### Modified files

| File | Change |
|---|---|
| `lib/core/theme/app_theme.dart` | Ajout additif de tokens "soft" : `radiusXXL` (32dp), `primarySoftTint`/`primarySoftTintDark`, `softBackgroundGradient`/`softBackgroundGradientDark`. **Aucune modification du `ThemeData` global** (`light()`/`dark()`, `_cardTheme`, `_inputDecoration`, etc.) — ces tokens ne sont consommés que par les écrans onboarding pour l'instant |
| `lib/features/onboarding/presentation/page/onboarding_page.dart` | Réécriture complète : suppression de la card blanche bordée/ombrée et de son ombre `boxShadow`, fond en dégradé doux (`softBackgroundGradient`), toggle de langue simplifié en pill segmentée, illustration à un seul niveau de cercle (`primarySoftTint`) au lieu de deux cercles imbriqués, feature pills sans bordure, CTA en bouton plein simple (suppression du style bicolore "swipe" à deux ronds), ajout de `OnboardingProgressDots` (1/4) |
| `lib/features/onboarding/presentation/page/terms_page.dart` | Titre allégé (`w600` au lieu de `bold`), ajout de `OnboardingProgressDots` (2/4) sous le header, suppression du `boxShadow` sur la zone d'acceptation (bordure haute seule, plus discrète). Texte légal, logique de scroll-to-unlock et double checkbox RGPD **inchangés** |
| `lib/features/onboarding/presentation/page/sector_selection_page.dart` | AppBar : titre remplacé par `OnboardingProgressDots` (3/4) centré ; ajout d'un sous-titre séparé du titre d'écran ; bouton "Continuer" passé en pilule pleine hauteur 56dp (cohérence avec les autres CTA du flow) |
| `lib/features/onboarding/presentation/widget/sector_tile.dart` | Sélection repensée : teinte diluée (`.withAlpha`) + bordure colorée + badge check en overlay au lieu d'un remplissage plein saturé ; radius passé à `AppTheme.radiusL` (16dp) |
| `lib/features/onboarding/presentation/page/shop_name_page.dart` | AppBar : titre remplacé par `OnboardingProgressDots` (4/4) ; titre d'écran + sous-titre explicatif ajoutés ; **suppression du `border: OutlineInputBorder()` explicite** qui écrasait le style thémé global (`_inputDecoration` — fill + radius doux déjà définis dans `AppTheme` mais jamais appliqués sur cet écran) ; bouton "Terminer" passé en pilule pleine hauteur 56dp |

---

## Key Design Decisions

### Continuité de marque, pas rupture
L'indigo `#3B5BDB` reste la couleur d'ancrage (CTA, sélections, icônes) —
décision utilisateur explicite pour préserver la reconnaissance de marque
pendant le rebrand.

### Tokens additifs, pas de modification du thème global
Les nouveaux tokens (`radiusXXL`, `primarySoftTint`, `softBackgroundGradient`)
sont ajoutés à `AppTheme` mais ne sont pas branchés dans `ThemeData.light()`/
`dark()`. Objectif : ne pas impacter visuellement les écrans hors périmètre
(POS, dashboard, catalogue...) pendant que le rebrand est encore en cours.
Ils seront promus dans le thème global au fur et à mesure des itérations
suivantes.

### Bug de style corrigé en passant (`ShopNamePage`)
Le champ `shopNameField` définissait explicitement
`border: OutlineInputBorder()`, ce qui écrasait le style thémé global
(`_inputDecoration` dans `AppTheme` — déjà "soft" : fond rempli, bordure
arrondie 12dp, sans bordure carrée par défaut) partout ailleurs utilisé dans
l'app. Suppression de cette surcharge locale — l'écran hérite maintenant du
style déjà standard de l'app, sans nouveau token ni risque de régression
visuelle ailleurs.

### Indicateur de progression partagé
`OnboardingProgressDots` est un widget purement visuel, sans état partagé
(pas de provider) — chaque écran lui passe simplement son `step` local. Choix
délibéré pour rester découplé de la logique métier (providers, routing) des 4
écrans, qui appartiennent à des couches différentes (StatefulWidget simple
pour Welcome/Terms, Riverpod pour Sector/ShopName).

---

## Acceptance Criteria Met

- [x] Les 4 écrans du flow onboarding (Welcome, Terms, Sector, Shop Name)
      partagent la nouvelle direction visuelle soft/modern
- [x] La couleur de marque indigo `#3B5BDB` est conservée comme ancrage
- [x] Aucune clé (`Key`) de test existante n'a été retirée ou renommée
- [x] Aucun texte de message de validation n'a été modifié
- [x] Aucune logique métier (providers, navigation, SharedPreferences) n'a
      été modifiée
- [x] `flutter analyze` sur les fichiers modifiés — 0 erreur, 0 warning
      (seulement des `info` `prefer_const_constructors` préexistants, hors
      périmètre de cette story)
- [x] Tests widgets existants (`sector_selection_page_test.dart`,
      `shop_name_page_test.dart`) — 11/11 passent sans modification
- [x] Documentation : addendum de spec UX + présente story

---

## Dependencies Added

Aucune — uniquement des widgets Flutter/Material natifs.

---

## Suite prévue

Rebrand à étendre écran par écran (hors périmètre de cette story) :
auth (login/register), POS, dashboard, catalogue — en promouvant
progressivement les tokens "soft" dans le `ThemeData` global une fois
validés sur l'ensemble du parcours.

---

## Révision 1 — 2026-09-15 : recalibrage sur référence visuelle

**Retour utilisateur** : la V1 ci-dessus ne correspondait pas à l'intention
"soft, moderne, épurée" — trop discrète/pâle, typographie trop fine et
centrée. Un exemple concret a été fourni (écran de sélection de préférences
type "Pick topics you care about") : fond en dégradé visible mais doux,
titres **gras** alignés à gauche, items de liste avec icône + titre/sous-titre
+ case à cocher à droite, CTA plein sans icône.

### Changements

| File | Change |
|---|---|
| `lib/core/theme/app_theme.dart` | `softBackgroundGradient`/`Dark` : dégradé 3 stops nettement plus visible (indigo→sky→blanc à ~62%) au lieu d'un blanc quasi-imperceptible |
| `lib/features/onboarding/presentation/page/onboarding_page.dart` | Titre passé en gras (`w700`), aligné à gauche (plus centré), CTA "Commencer" sans icône flèche, feature pills allégées (icône+texte seuls, sans fond/bordure) |
| `lib/features/onboarding/presentation/page/sector_selection_page.dart` | **Changement structurel** : grille 2 colonnes → liste verticale à une colonne (`ListView.separated`, même `Key('sectorGrid')` sur le widget scrollable — tests inchangés). Titre gras aligné à gauche + sous-titre. Fond en dégradé doux (zone haute) |
| `lib/features/onboarding/presentation/widget/sector_tile.dart` | Réécriture complète en ligne de liste : avatar icône rond à gauche, titre + sous-titre (nouvelle map statique `_sectorSubtitles`, présentation uniquement, hors domaine) au centre, case à cocher arrondie à droite (vide/coché). `Key('sectorTile_${apiCode}')` conservée |
| `lib/features/onboarding/presentation/page/terms_page.dart` | Titre passé en gras (`w700`) ; header (bouton retour + indicateur d'étape) désormais sur fond dégradé doux, zone de contenu scrollable restée sur fond neutre pour la lisibilité du texte légal |
| `lib/features/onboarding/presentation/page/shop_name_page.dart` | Titre passé en gras (`w700`) ; fond en dégradé doux sur toute la page (formulaire court, pas de souci de lisibilité) |

### Décision : pourquoi une liste plutôt qu'une grille pour Sector Selection

La référence fournie utilise un pattern de liste verticale (icône + texte +
checkbox), pas une grille de cartes. Avec 8 secteurs, la liste reste
scannable et permet d'ajouter un sous-titre descriptif par secteur —
impossible dans les tuiles carrées précédentes. Changement structurel
assumé car il sert directement l'objectif "épuré" (une seule colonne, plus
d'espace de lecture par item) sans casser aucun test (`Key('sectorGrid')`
et `Key('sectorTile_${apiCode}')` conservées sur les nouveaux widgets).

### Vérifications (Révision 1)

- [x] `flutter analyze` — 0 erreur, 0 warning (seulement des `info`
      `prefer_const_constructors` préexistants)
- [x] Tests widgets (`sector_selection_page_test.dart`,
      `shop_name_page_test.dart`) — 11/11 passent sans modification

---

## Révision 2 — 2026-09-15 : Welcome screen conforme à la référence

**Retour utilisateur** : la Révision 1 gardait une illustration en cercle +
"pills" horizontales sur l'écran Welcome — pas assez proche de la référence.
Consigne : appliquer la structure de l'image directement sur cet écran
(cartes de liste), et **retirer l'indicateur de progression** (jugé inutile).

### Changements

| File | Change |
|---|---|
| `lib/features/onboarding/presentation/page/onboarding_page.dart` | Suppression de `_OnboardingIllustration` (cercle + pills) et de `OnboardingProgressDots` sur cet écran. Les 4 mises en avant (Stock, POS, Offline, WhatsApp) sont maintenant des cartes de liste (`_FeatureCard` : avatar icône rond, titre + sous-titre, badge "inclus" coché à droite) — structure directement calquée sur la référence fournie (icône + titre/sous-titre + indicateur à droite) |

### Décision : badge "coché" plutôt que case à cocher vide

La référence utilise une case à cocher car son écran est un vrai choix
utilisateur ("Pick topics you care about"). L'écran Welcome de Keevo est
purement informatif (aucune sélection possible) — une case vide aurait
suggéré une interaction inexistante. Le badge est donc affiché **déjà
coché** (vert/primaire), pour signifier "fonctionnalité incluse" plutôt que
"à sélectionner". Structure visuelle identique à la référence, sémantique
adaptée au contexte non-interactif.

### Note — indicateur de progression retiré sur cet écran uniquement

`OnboardingProgressDots` reste utilisé sur Terms/Sector/ShopName pour
l'instant (retrait demandé explicitement seulement sur Welcome). À
généraliser ou supprimer entièrement sur instruction future.

### Vérifications (Révision 2)

- [x] `flutter analyze lib/features/onboarding/presentation/page/onboarding_page.dart` — 0 issue

---

## Révision 3 — 2026-09-15 : extension à AuthPage (inscription/connexion)

**Contexte** : hors périmètre initial (4 écrans onboarding), mais fait partie
du même funnel de premier lancement (`/onboarding → /terms → /auth/register
→ /onboarding/sector → /onboarding/shop-name`) — traité ici par cohérence
plutôt que dans une story séparée. Référence fournie : écran "Sign up" avec
badge circulaire centré, titre gras, champs à label au-dessus, CTA plein.

### Changements

| File | Change |
|---|---|
| `lib/features/auth/presentation/page/auth_page.dart` | Badge circulaire centré (`primarySoftTint` + icône) remplace l'ancien header texte seul ; titre simplifié à une ligne grasse ("Inscription"/"Connexion", suppression du double label+headline précédent) ; champs `phoneField`/`passwordField` restylés avec label au-dessus (`Text` séparé) + bordure fine `cs.outlineVariant` (au lieu du `OutlineInputBorder()` par défaut) ; CTA renommé contextuel ("Créer mon compte"/"Se connecter" au lieu de "Continuer" générique) ; lien bas de page ("Déjà un compte ?") passé en `RichText` avec portion soulignée au lieu de `TextButton` |

### Décisions

- **Pas de case à cocher CGU sur cette page.** La référence en a une, mais
  dans le flow Keevo l'acceptation des CGU est déjà bloquante sur
  `TermsPage` (scroll-to-unlock + double checkbox RGPD) **avant** d'arriver
  ici. Ajouter une seconde case à cocher aurait dupliqué une demande de
  consentement déjà obtenue. Remplacé par une note non-interactive
  ("Vous avez accepté nos Conditions... ✓") — cohérente avec le badge
  "déjà coché" utilisé sur l'écran Welcome (Révision 2).
- **Pas de boutons de connexion sociale (Facebook/Google/Apple).** L'app
  Keevo n'a pas d'intégration OAuth — l'authentification se fait uniquement
  par numéro WhatsApp + mot de passe. Ajouter ces boutons aurait créé de
  l'UI morte (non fonctionnelle), trompeuse pour des utilisateurs à
  littératie technique modérée (persona cible du PRD). Omis délibérément.
- **Champ téléphone conservé** (`IntlPhoneField`, pas un champ email comme
  dans la référence) — l'identifiant Keevo est le numéro WhatsApp, exigence
  fonctionnelle non négociable du produit, pas un choix de style.

### Vérifications (Révision 3)

- [x] `flutter analyze` sur `auth_page.dart` — 0 issue
- [x] Tests (`register_page_test.dart`, `login_page_test.dart`) — tous
      passent sans modification (`Key('phoneField')`, `Key('passwordField')`,
      `Key('submitButton')`, `Key('errorBanner')` et textes de validation
      inchangés)

---

## Révision 4 — 2026-09-15 : AuthPage — retour bas de page, badge retiré, FR/EN ajouté

**Retour utilisateur** : "aligne tout en bas, enlève l'icône centrée et
ajoute le changement de langue en haut à droite alignée avec le bouton back."

### Changements

| File | Change |
|---|---|
| `lib/core/widgets/lang_toggle.dart` (nouveau) | Extraction du toggle FR/EN (`_LangToggle`/`_LangSegment` dupliqués dans `onboarding_page.dart`) en widget public partagé `LangToggle`, réutilisé par les deux écrans |
| `lib/features/onboarding/presentation/page/onboarding_page.dart` | Utilise désormais `LangToggle` importé — suppression des classes privées dupliquées |
| `lib/features/auth/presentation/page/auth_page.dart` | Badge circulaire centré supprimé ; `LangToggle` ajouté dans le header, aligné avec le bouton retour (`MainAxisAlignment.spaceBetween`) ; tout le contenu du formulaire poussé vers le bas de l'écran (`LayoutBuilder` + `ConstrainedBox(minHeight)` + `IntrinsicHeight` + `Column(mainAxisAlignment: end)` — scrollable si le contenu dépasse) ; toutes les chaînes visibles (titre, labels, hints, erreurs, CTA, liens) traduites FR/EN via le nouvel état `_isFrench` |

### Décision : pourquoi ce pattern pour "aligner en bas" plutôt qu'un simple `Spacer`

Un `Spacer`/`Expanded` classique ne fonctionne pas à l'intérieur d'un
`SingleChildScrollView` (hauteur non bornée). Pattern standard Flutter
utilisé à la place : `LayoutBuilder` récupère la hauteur disponible,
`ConstrainedBox(minHeight: ...)` + `IntrinsicHeight` la réappliquent au
contenu, et `Column(mainAxisAlignment: MainAxisAlignment.end)` aligne le
`Form` en bas — tout en restant scrollable si le clavier ou un message
d'erreur fait dépasser le contenu (pas de perte de fonctionnalité sur petit
écran).

### Vérifications (Révision 4)

- [x] `flutter analyze` sur les 3 fichiers modifiés — 0 issue
- [x] Tests (`register_page_test.dart`, `login_page_test.dart`,
      `sector_selection_page_test.dart`, `shop_name_page_test.dart`) —
      20/20 passent sans modification
- [x] Langue par défaut = français (`_isFrench = true`) — les assertions de
      texte des tests (messages de validation en français) restent valides
      sans changement

---

## Révision 5 — 2026-09-16 : Tab bar (navigation principale) soft/modern

**Contexte** : première fois que le rebrand touche un composant du
`ThemeData` **global** (`NavigationBarThemeData` dans `AppTheme`), plutôt
qu'un token additif consommé écran par écran. Jugé sans risque car
`NavigationBar` n'est instancié qu'à un seul endroit dans toute l'app
(`MainShell`) — modifier son thème ne peut pas affecter d'autre écran.

### Changements

| File | Change |
|---|---|
| `lib/core/theme/app_theme.dart` | `_navBarTheme` : `elevation` 2→0 (l'ombre Material par défaut est remplacée par une ombre plus douce dessinée par `MainShell`) ; `indicatorColor` : `cs.primaryContainer` (teinte saturée générée par le seed Material) → `primarySoftTint`/`primarySoftTintDark` (teinte pastel de marque, cohérente avec le reste du rebrand) |
| `lib/core/scaffold/main_shell.dart` | `bottomNavigationBar` enveloppé dans un `Container` (coins hauts arrondis `radiusXL`, ombre douce `blurRadius: 20` vers le haut) + `ClipRRect` pour que le `NavigationBar` épouse ces coins arrondis — la barre lit comme une "sheet" posée sur le contenu plutôt qu'un bandeau plat collé au bord de l'écran |

### Décision : labels toujours visibles (pas de `onlyShowSelected`)

Un pattern de nav bar "moderne" fréquent masque le label des onglets non
sélectionnés (icône seule). Délibérément **non retenu** : le persona cible
du PRD a une littératie technique faible (~4/10) — la clarté prime sur le
minimalisme. Les 4-5 labels restent visibles en permanence.

### Vérifications (Révision 5)

- [x] `flutter analyze` sur les 2 fichiers modifiés — 0 erreur (seuls des
      avertissements préexistants sans rapport, non introduits par ce
      changement)
- [x] Aucun test ne cible `MainShell`/`NavigationBar` — aucune régression
      possible à ce niveau

---

## Révision 6 — 2026-09-16 : CatalogPage puis ProductFormPage

**Contexte** : extension du rebrand à deux écrans du module Catalogue,
hors périmètre initial (onboarding) mais suite logique de l'effort
"écran par écran" annoncé en Révision 3.

### CatalogPage

| Changement | Avant | Après |
|---|---|---|
| Titre AppBar | `'📦 Catalogue'` | `'Catalogue'` |
| Sous-titre | `'🏪 $nom'` (emoji inline) | `Icon(storefront_outlined)` + texte |
| Hint recherche | `'🔍 Rechercher...'` | `'Rechercher...'` (le préfixe icône suffisait déjà) |
| Fond AppBar | Bandeau dégradé indigo→secondaire | Fond neutre (`cs.surface`) |
| Boutons d'action AppBar | Icônes blanches sur "verre" translucide | Pastilles `primarySoftTint` (nouveau `_SoftIconButton`) |
| Barre de recherche | Ombre portée (blur 12) | Bordure fine `outlineVariant` |
| Indicateur TabBar | Dégradé `primary→primary*0.8` | Couleur pleine `primary` |
| FAB | Dégradé + ombre marquée (blur 16) | Couleur pleine + ombre douce (blur 12) |
| Hauteur AppBar / alignement | 160dp, contenu aligné en bas (`mainAxisAlignment.end`) → vide visible au-dessus du titre | 96dp, contenu aligné en haut — plus de vide |

**Bug préexistant découvert (non corrigé, hors périmètre)** : le test
`hides "Créer un produit" button for EMPLOYEE on empty search` échoue déjà
avant ce rebrand — `_ProductListView` n'a jamais vérifié le rôle avant
d'afficher ce bouton dans l'état vide de recherche. Confirmé par `git diff`
que cette story n'a touché ni `_ProductListView` ni la variable `isEmployee`
sur ce point. Signalé à l'utilisateur, correction laissée à une story dédiée.

### ProductFormPage

Aucun test n'existe pour cet écran (seulement une route stub dans
`catalog_page_test.dart`) — restylage complet sans risque de régression
automatisée, vérifié manuellement + `flutter analyze`.

| Changement | Avant | Après |
|---|---|---|
| AppBar | Bandeau dégradé 3 couleurs + cercles décoratifs flottants | Fond neutre `cs.surface`, sans décoration |
| Boutons AppBar | Icônes blanches sur "verre" translucide | Pastilles `primarySoftTint` (`_SoftAppBarIconButton`) |
| Dialogue conseils | Titre `'💡 Conseils'` | `'Conseils'` (icône ampoule sur le bouton qui l'ouvre à la place) |
| `_ModernTextField` | Bordure/remplissage/radius redéfinis en local (dupliqué du thème global) | Hérite de `AppTheme._inputDecoration` — un seul style de champ dans toute l'app. Indicateur "champ requis" (label coloré primaire) conservé |
| `_CategoryDropdown` | Même duplication de style | Idem : hérite du thème global |
| Icônes de section | `edit_rounded` pour "Informations de base" (sémantiquement "modifier", pas "info") | `info_outline_rounded` ; icônes champs harmonisées en variante `_outlined` (`category_outlined`, `camera_alt_outlined`, `photo_library_outlined`, `photo_camera_outlined`, `local_shipping_outlined`) |
| Bouton Sauvegarder | Dégradé + emoji `'🚀 Créer le produit'` | Couleur pleine + ombre douce, `'Créer le produit'` |
| Icône section Photo | "Dégradé" `[warning, warning]` (2x la même couleur — artefact de copier-coller, pas un vrai dégradé) | `primarySoftTint` + icône `primary` (cohérent avec le reste de l'app) |
| Titre section Photo | `'Photo sélectionnée ✓'` | `'Photo sélectionnée'` (la vignette au-dessus suffit à indiquer l'état) |
| Options photo (caméra/galerie) | Dégradés, dont un violet `#7048E8` hors charte | Couleurs de marque pleines (`cs.secondary` caméra, `cs.primary` galerie) |
| Icône fournisseur (`_SupplierCard`) | Même "dégradé" factice `[warning, warning]` | `AppTheme.warning` plein |

### Vérifications (Révision 6)

- [x] `flutter analyze` sur les deux fichiers — 0 erreur (uniquement des
      `info` `withOpacity` dépréciée, préexistantes, hors périmètre)
- [x] `catalog_page_test.dart` — 10/11 (le seul échec est le bug préexistant
      documenté ci-dessus, confirmé non lié à cette révision par `git diff`)
- [x] Aucun dégradé (`LinearGradient`/`gradient:`) ni emoji restant dans les
      deux fichiers (vérifié par recherche exhaustive)

---

## Révision 7 — 2026-09-16 : DashboardPage (refonte complète)

**Contexte** : l'utilisateur a fourni 3 captures d'écran de dashboards de
référence ("Apixer", app de livraison, app fitness) — langage visuel commun :
cartes métriques claires à ombre douce (icône dans badge rond, badge de
tendance en pilule colorée, gros chiffre + libellé), graphique à barres aux
sommets arrondis avec bulle de tooltip flottante sur la barre sélectionnée,
zéro dégradé saturé ni emoji. Correspond à la direction "Moment Café / style
fintech" déjà documentée dans `ux-design-specification.md` mais jamais
pleinement livrée — cette révision la concrétise sur les 6 widgets composant
la page.

### Changements

| File | Avant | Après |
|---|---|---|
| `widget/metric_badge_card.dart` | `Card` Material, icône en badge carré arrondi, tendance en texte coloré simple | `Container` + ombre douce (16px blur), icône dans un **badge rond**, tendance en **pilule colorée** (fond teinté + texte), chiffre en `titleLarge` gras |
| `widget/morning_summary_hero_card.dart` | Dégradé indigo→bleu ciel + `BackdropFilter` (glassmorphism), texte blanc | Fond **pastel plein** (`primarySoftTint`), texte sombre, sparkline en couleur `primary` — plus de flou ni de dégradé |
| `widget/motivational_card.dart` | Emoji `'💡'` en `Text` | Icône `lightbulb_outline_rounded` dans un badge rond blanc sur fond `primarySoftTint` |
| `widget/shop_status_card.dart` | `Card` Material | `Container` + ombre douce (même langage que les cartes métriques). Bandeau d'accent statut conservé (signal utile) |
| `widget/sales_evolution_chart.dart` | Barres à coins uniformément arrondis, tooltip en ligne de texte statique sous le titre | Barres à **coins arrondis en haut uniquement** (look "pilule"), **bulle de tooltip flottante** positionnée au-dessus de la barre sélectionnée (nouveau widget `_TooltipBubble`) — motif directement inspiré des références |
| `widget/top_products_section.dart` | Rang `#1`/`#2` en texte simple | Rang dans un **badge rond** (teinté pour le top 3, neutre sinon) |
| `page/dashboard_page.dart` | Salutation `'Bonjour $name 👋'` | Emoji retiré : `'Bonjour $name'` |

### Décision : pas de refonte structurelle de la mise en page

Les références montrent une structure différente (barre de recherche,
sélecteur de plage horaire en dropdown, calendrier horizontal) qui ne
correspond pas au contenu réel du dashboard Keevo (hero CA, boutiques,
graphique, top produits — Story 7.1 AC1-AC8). La révision reprend le
**langage visuel** (cartes à ombre douce, badges ronds, pilules de tendance,
tooltip flottant, zéro dégradé/emoji) sans copier la structure des
références, qui ne correspond pas aux données/flux réels de l'app.

### Décision : test mis à jour, pas contourné

`motivational_card_test.dart` vérifiait littéralement `find.text('💡')`.
Plutôt que de garder l'emoji pour ne pas casser le test, le test a été mis à
jour pour vérifier `find.byIcon(Icons.lightbulb_outline_rounded)` — le test
documente maintenant le nouveau comportement voulu, pas l'ancien.

### Vérifications (Révision 7)

- [x] `flutter analyze` sur les 7 fichiers modifiés — 0 erreur
- [x] Tests unitaires par widget — tous verts après mise à jour de
      `motivational_card_test.dart`
- [x] `flutter test test/features/dashboard/` (suite complète, 70 tests) —
      69/70 ; le seul échec (`users_first_name_migration_test.dart`,
      schémaVersion attendue 25 vs réelle 26) est un test de migration Drift
      sans rapport avec cette révision (aucun fichier de données/schéma
      touché) — signalé à l'utilisateur, non corrigé ici
