# keevo

Application mobile Keevo (Flutter).

## Développement local

```bash
flutter pub get
dart run build_runner build --delete-conflicting-outputs
flutter run
```

Sans `--dart-define=API_BASE_URL=…`, les builds debug et profile visent
`http://localhost:4500` (backend local ; sur un appareil physique :
`adb reverse tcp:4500 tcp:4500`). Une autre URL peut être passée avec
`--dart-define=API_BASE_URL=http://<hôte>:4500`.

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
