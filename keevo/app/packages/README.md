# Packages du workspace

`keevo/app` est la racine d'un workspace Dart (Melos 7, voir `../README.md`).

| Package | Rôle | Peut importer |
|---------|------|---------------|
| `keevo_core` | noyau partagé : thème, base Drift, sync, réseau, widgets, contrat des modules (`module/`) | paquets tiers uniquement |
| `keevo_invoicing` | module Facturation (liste, création, facture, paiement, envoi WhatsApp, avoirs, informations vendeur — 15.5 ; action « Facturer ») — pilote 15.4c | `keevo_core` + paquets tiers |
| app (`keevo`, `../lib`) | composition, « app shell », features pas encore extraites | tout |

`test/architecture/package_boundary_test.dart` (côté app) échoue si un fichier
de `packages/*/lib` ou `packages/*/test` importe l'app (`package:keevo/…`), si
`keevo_core` importe une feature ou un module, ou si un module importe un
autre module.

## Le contrat d'un module (Story 15.4c)

Dans `keevo_core/lib/module/` :

- `KeevoModuleUi` (`keevo_module_ui.dart`) — le descripteur qu'un module
  expose : `id` (id du manifest backend), `routes` (`List<RouteBase>` montées
  par le routeur de l'app), `actions` (clé d'action d'extension →
  `ExtensionActionHandler`). Les libellés/ordre/permissions des boutons
  d'extension restent côté serveur.
- Ports (`module_ports.dart`) — ce dont un module a besoin de l'app sans
  pouvoir l'importer : `apiDioProvider` (client API authentifié),
  `fileSharerProvider` (partage/ouverture d'un fichier), `tempDirProvider`
  (dossier temporaire) ; Story 15.5b : `moduleSyncQueueProvider`
  (`module_sync.dart` — mettre en file une opération de synchro, ids encore
  en file, demander une synchro). Ils lèvent une `UnimplementedError` qui nomme le port
  tant que l'app ne les surcharge pas ; l'app les branche dans
  `buildRootScope` (`lib/root_scope.dart`, `modulePortOverrides`). Un test de
  module les surcharge directement (`overrideWithValue`).

- Données hors ligne (15.5b) : `module_records.dart` — table générique
  `module_records(module, entity, id, json, updated_at, pending)` de
  `AppDatabase` (schéma 32) via `ModuleRecordStore` ; le descripteur déclare
  `pulledEntities` (clé du pull → `ModuleEntityMerger`, en général
  `replaceUnlessPending`) que `RestSyncService` appelle à chaque pull
  (`composeModuleMergers(kRegisteredModules)`, `lib/core/di/providers.dart`).

Côté app :

- `lib/core/modules/registered_modules.dart` — **la** liste des descripteurs
  (`kRegisteredModules`). Le routeur (`composeModuleRoutes`) et le registre
  d'actions (`composeModuleActions`, `kExtensionActions`) en sont dérivés.
  Un module absent de la liste : aucune route montée, ses clés d'action sont
  inconnues (boutons non affichés).
- La garde de module (15.2) s'applique via `lib/core/router/module_routes.dart`
  (généré des manifests) : chaque route d'un descripteur doit y figurer pour
  son module (`test/core/modules/registered_modules_test.dart`).

## Extraire la feature suivante

1. **Recenser les dépendances** de `lib/features/<x>` hors de la feature :
   tout import relatif qui remonte au-dessus de la feature (`../../core/…`,
   `../../../auth/…`, …) ou `package:keevo/` —
   `grep -rnE "^(import|export) '(\.\./)+(core|features|[a-z_]+/(data|domain|presentation))/|package:keevo/" lib/features/<x>`
   (repère, à relire). La vérification qui fait foi est
   `test/architecture/package_boundary_test.dart`, une fois le code déplacé.
   Tout ce qui est déjà dans `keevo_core` reste importé tel quel ; pour le
   reste :
   - code sans dépendance vers l'app → le déplacer dans `keevo_core` ;
   - service de l'app (client HTTP, partage, session…) → un **port** dans
     `keevo_core/lib/module/` (provider qui lève une `UnimplementedError`
     nommant le port) + son override dans `modulePortOverrides` ;
   - autre feature/module → pas d'import direct : port, ou point d'extension
     serveur (`docs/modules/extension-slots.md`).
2. **Créer le package** `packages/keevo_<x>/` : `pubspec.yaml` (`publish_to:
   none`, `version: 0.1.0`, `resolution: workspace`, dépendances réellement
   importées : `keevo_core`, `flutter_riverpod`, …), `analysis_options.yaml`
   copié de `keevo_core` (`depend_on_referenced_packages`).
3. **Déplacer avec `git mv`** `lib/features/<x>/**` vers `packages/keevo_<x>/lib/`
   (même arborescence) et ses tests vers `packages/keevo_<x>/test/` ; réécrire
   les imports (`package:keevo/features/<x>/…` → `package:keevo_<x>/…`).
4. **Écrire le descripteur** `lib/<x>_module.dart` : routes et handlers qui
   n'atteignent les écrans qu'à travers des imports `deferred as`
   (`DeferredWidget` pour une route, `loadLibrary()` dans un handler). C'est la
   seule bibliothèque du package importée « en dur » par l'app ; les tests
   d'une route différée vivent dans leur propre fichier (une bibliothèque
   différée chargée dans l'horloge factice d'un autre test reste en attente).
5. **Brancher dans l'app** : `workspace:` et `dependencies:` du
   `pubspec.yaml` racine, le descripteur dans `kRegisteredModules`, retirer
   les routes/handlers écrits à la main (`app_router.dart`), vérifier les
   routes dans `module_routes.dart`.
6. **Outillage** : étapes analyze/test du package dans
   `.github/workflows/flutter-ci.yml` (+ couverture) ; script `generate` du
   `pubspec.yaml` racine si le package a du code généré (après `keevo_core`).
   `melos analyze`/`melos test` le prennent automatiquement.
7. **Vérifier** : `flutter analyze && flutter test` dans le package, le test
   de frontière, les dossiers de test de l'app un par un, `flutter build apk
   --debug`.

Note Riverpod 3 : en déplaçant des `Notifier`, vérifier les `state = …` —
Riverpod 3 ne notifie plus quand la nouvelle valeur est `==` à l'ancienne
(un modèle qui reposait sur l'identité doit être revu).
