# keevo_core

Noyau partagé de l'app Keevo (Story 15.4a) : thème, base Drift chiffrée,
sync (hors couches couplées aux features), réseau, widgets communs. Aucune
dépendance vers `keevo/app` ni vers `lib/features/**`
(`test/architecture/package_boundary_test.dart` côté app).

## Ce que l'app hôte doit fournir

Ces éléments ne sont **pas** dans le package ; une app qui l'utilise doit les
mettre en place, sinon la base ou le thème échouent à l'exécution.

- **SQLCipher** — `AppDatabase` ouvre la base avec une clé SQLCipher
  (`PRAGMA key`). L'app déclare `sqlcipher_flutter_libs` (incompatible avec
  `sqlite3_flutter_libs`) et, dans `main()`, avant tout accès à la base :
  `open.overrideFor(OperatingSystem.android, () => DynamicLibrary.open('libsqlcipher.so'))`
  (`package:sqlite3/open.dart`) — voir `keevo/app/lib/main.dart`.
- **Polices Inter** — `AppTheme` passe par `google_fonts`
  (`GoogleFonts.interTextTheme`) avec le téléchargement à l'exécution coupé :
  les fichiers `Inter-{Light,Regular,Medium,SemiBold,Bold}.ttf` doivent être
  des assets de l'app (`keevo/app/assets/fonts`, déclarés dans son
  `pubspec.yaml`). Les tests du package les servent depuis ce dossier
  (`test/helpers/inter_font_assets.dart`).
- **Overrides Riverpod** — `dbEncryptionKeyProvider` et
  `sharedPreferencesProvider` (`di/core_providers.dart`) lèvent une erreur
  tant que l'app ne les surcharge pas (`ProviderScope(overrides: …)`).
- **Ports des modules** (Story 15.4c) — `apiDioProvider`,
  `fileSharerProvider`, `tempDirProvider` (`module/module_ports.dart`) lèvent
  une `UnimplementedError` nommant le port tant que l'app ne les surcharge
  pas (`modulePortOverrides` dans `buildRootScope`). Voir
  `../README.md`.

## Commandes (depuis `packages/keevo_core`)

```bash
dart run build_runner build --delete-conflicting-outputs   # Drift
flutter analyze
flutter test
```

Depuis `keevo/app` : `dart run melos generate | analyze | test`.
