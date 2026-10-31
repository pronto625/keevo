# keevo

Application mobile Keevo (Flutter).

## Développement local

```bash
dart run melos bootstrap   # = flutter pub get (workspace)
dart run melos generate    # build_runner : keevo_core puis l'app
flutter run
```

Sans `--dart-define=API_BASE_URL=…`, les builds debug et profile visent
`http://localhost:4500` (backend local ; sur un appareil physique :
`adb reverse tcp:4500 tcp:4500`). Une autre URL peut être passée avec
`--dart-define=API_BASE_URL=http://<hôte>:4500`.

## Workspace (Melos 7 / pub workspace)

`keevo/app` est la racine d'un workspace Dart : l'app (package `keevo`),
`packages/keevo_core` (noyau partagé : thème, base Drift, sync, réseau,
widgets communs, contrat des modules), importé via `package:keevo_core/...`,
et les packages de module (`packages/keevo_invoicing`, Story 15.4c) — voir
`packages/README.md` (contrat d'un module, comment extraire la feature
suivante).

```
keevo/app/
├── lib/                    app : composition, features, « app shell » (lib/core)
├── test/
├── packages/keevo_core/    noyau sans feature (lib/, test/, build.yaml Drift)
└── packages/keevo_invoicing/  module Facturation (dépend de keevo_core seul)
```

Melos est une dev dependency (pas d'installation globale) :

```bash
dart run melos bootstrap   # = flutter pub get à la racine
dart run melos generate    # build_runner, keevo_core d'abord puis l'app
dart run melos analyze     # flutter analyze lib test, package par package
dart run melos test        # tests package par package, dossier par dossier
```

Règle d'extraction (fermeture des dépendances) : un fichier de `lib/core` va
dans `keevo_core` seulement si tout ce qu'il importe (transitivement) y va
aussi et que rien n'importe `lib/features/**` ni `package:keevo/...`.
`test/architecture/package_boundary_test.dart` échoue sinon.

Restent dans l'app (`lib/core`, « app shell ») les fichiers couplés aux
features : routeur (`app_router`, `module_routes`, `deep_link_navigation`,
`startup_route`, `session_presence_cache`, `module_unavailable_page`),
`di/providers.dart` (`syncServiceProvider`, `syncDiagnosticRunnerProvider`,
`currentUserIdProvider`, `currentDeviceIdProvider` — les autres providers
noyau sont dans `keevo_core/di/core_providers.dart`), `main_shell`,
`shell_banners`, `session_refresher`, `auth_interceptor`, `entitlements_provider`,
`extension/`, `modules/` (descripteurs de modules enregistrés), `fcm_service`, `product_photo_providers` + `widget/product_photo`,
et dans `sync/` : `rest_sync_service`, `sync_trigger_notifier`,
`riverpod_sync_trigger_dispatcher`, `sync_gate_guard`,
`sync_monitoring_providers`, `domain/sync_conflict_provider`.

## Build release (Play Store)

Deux prérequis, sans lesquels le build n'est pas publiable :

1. **URL du backend en HTTPS** — obligatoire en release. Une app release
   construite sans `API_BASE_URL`, ou avec une URL qui n'est pas `https://`,
   s'arrête au démarrage (`InvalidApiBaseUrlError`) au lieu de partir vers
   localhost en clair.
2. **Keystore de signature** — copier `android/key.properties.example` vers
   `android/key.properties` (ignoré par git) et le remplir (chemin du keystore,
   mots de passe, alias). Sans ce fichier, Gradle signe avec la clé **debug**,
   refusée par le Play Store.

```bash
flutter build appbundle --release \
  --dart-define=API_BASE_URL=https://<domaine-api>
```

Penser à incrémenter `version:` dans `pubspec.yaml` (nom + numéro de build)
avant chaque envoi.
