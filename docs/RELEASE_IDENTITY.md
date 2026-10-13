# Keevo — Identité de publication (iOS / Android)

> Story v1s-16-18. Ce document fixe les identifiants de publication et décrit la
> bascule Android, qui reste à faire après une action manuelle dans Firebase.

## 1. Identifiants décidés

| Élément | Valeur | État |
|---------|--------|------|
| Bundle id iOS (Runner) | `com.keevo.keevo` | Appliqué (`ios/Runner.xcodeproj/project.pbxproj`) |
| Bundle id iOS (RunnerTests) | `com.keevo.keevo.RunnerTests` | Appliqué |
| `applicationId` Android | `com.keevo.keevo` | **En attente** : reste `com.example.keevo` jusqu'à la bascule (§3) |
| `namespace` Android + package Kotlin | `com.keevo.keevo` | **En attente** (§3) |
| `aps-environment` iOS | Debug et Profile : `development` (`Runner/RunnerDevelopment.entitlements`). Release : `production` (`Runner/Runner.entitlements`) | Appliqué |

L'organisation `com.keevo` vient de architecture.md (`flutter create --org com.keevo --project-name keevo`).
Apple refuse le préfixe `com.example`, d'où la bascule iOS dès maintenant.

Pourquoi Profile reste en `development` : les builds Profile (`flutter run --profile`)
sont installés sur l'appareil avec la signature automatique de *développement*, dont
le profil de provisionnement porte `aps-environment=development`. Seul Release est
archivé et exporté avec le profil App Store / Ad Hoc. Un `flutter run --release`
local avec la signature de développement peut donc échouer sur ce point : utiliser
Profile pour les tests de performance sur appareil.

## 2. iOS — enregistrement (à faire sur un Mac)

1. **Apple Developer** → Certificates, Identifiers & Profiles → Identifiers → `+` →
   App ID `com.keevo.keevo`, cocher **Push Notifications**.
2. **Clé APNs** : Keys → `+` → cocher Apple Push Notifications service (APNs) →
   télécharger le `.p8` (une seule fois). Noter le Key ID et le Team ID.
3. **Firebase** (projet `keevo-1d289`) → Paramètres du projet → Cloud Messaging →
   Configuration de l'app Apple → importer la clé `.p8` (Key ID + Team ID).
4. **Firebase** → Ajouter une application → iOS → bundle id `com.keevo.keevo` →
   télécharger `GoogleService-Info.plist`.
5. Déposer le fichier dans `keevo/app/ios/Runner/GoogleService-Info.plist`, puis dans
   Xcode (`open ios/Runner.xcworkspace`) l'ajouter à la cible **Runner**
   (glisser dans le groupe Runner, cocher « Add to targets: Runner »).
   Il n'est pas committé : il est dans `.gitignore` (règle 16.2, fichier propre à
   chaque déploiement). Le `google-services.json` Android, lui, reste suivi par git
   (choix existant du projet).
6. Xcode → Runner et RunnerTests → Signing & Capabilities → choisir la Team. Vérifier
   que Push Notifications apparaît sans erreur d'entitlements en Debug et en Profile.
   Choisir la Team écrit `DEVELOPMENT_TEAM` dans `ios/Runner.xcodeproj/project.pbxproj` :
   ce diff est attendu et doit être committé (c'est l'identifiant de l'équipe, pas un
   secret).
7. `cd ios && pod install`, puis `flutter run` sur un iPhone.

### Vérification iOS

- Icône de l'écran d'accueil : le « K. » Keevo, sans coins blancs.
- Écran de lancement : le logo centré sur fond clair `#F8F9FA` (fond `#0D1B2A` en mode
  sombre). Si iOS affiche un ancien écran de lancement en cache, supprimer puis
  réinstaller l'app.
- Rotation : portrait et paysage, comme sur Android.
- Archive (Release) → Distribute → App Store Connect / TestFlight. Dans l'`.ipa`
  exporté : `codesign -d --entitlements - Payload/Runner.app` affiche
  `aps-environment = production`. Un build TestFlight reçoit une notification push.
- Rejouer la checklist 16.2 (push premier plan / arrière-plan / app tuée ; la
  déconnexion révoque le jeton) sur le build TestFlight.

## 3. Android — bascule `com.example.keevo` → `com.keevo.keevo` (en attente)

La bascule n'est **pas** faite dans le dépôt : le plugin Gradle `google-services`
fait échouer le build si `google-services.json` n'a pas de client pour
l'`applicationId`. Il faut donc d'abord enregistrer le nouvel id dans Firebase.

1. **Firebase** (projet `keevo-1d289`) → Ajouter une application → Android →
   nom de package `com.keevo.keevo`. Ajouter les empreintes SHA-1 et SHA-256 de la
   clé de signature release (`keytool -list -v -keystore <keystore> -alias <alias>`)
   et de la clé debug si l'authentification Firebase en a besoin.
2. Télécharger le nouveau `google-services.json`. Il contient les deux clients
   (`com.example.keevo` et `com.keevo.keevo`) ; c'est attendu.
3. Le déposer à la place de `keevo/app/android/app/google-services.json`
   (même gestion que le fichier actuel, et mettre à jour le secret CI s'il est
   généré par le workflow).
4. Lancer la bascule depuis la racine du dépôt :

   ```bash
   keevo/app/tool/switch_android_application_id.sh --dry-run   # affiche les changements
   keevo/app/tool/switch_android_application_id.sh             # applique
   ```

   Le script refuse de tourner si le nouveau `google-services.json` ne déclare pas
   `com.keevo.keevo` (`--dry-run` affiche seulement un avertissement, pour prévisualiser
   avant l'étape Firebase). Il fonctionne sous Linux et macOS et demande `python3`. Il réécrit `namespace` et `applicationId` dans
   `android/app/build.gradle.kts`, change le package de `MainActivity.kt` et le
   déplace dans `kotlin/com/keevo/keevo/`. Il est idempotent : une deuxième
   exécution ne change rien.
5. Incrémenter la version dans `keevo/app/pubspec.yaml`, puis `flutter run` sur un
   appareil Android.
6. Supprimer l'application `com.example.keevo` dans Firebase uniquement quand plus
   aucun build en circulation ne l'utilise.

### Avis aux testeurs (à envoyer avec le premier build basculé)

> Keevo change d'identifiant technique. Le nouveau build s'installe comme une
> **nouvelle application** à côté de l'ancienne. Synchronisez vos données sur
> l'ancienne app (connexion internet active), puis **déconnectez-vous de l'ancienne
> app** : cela révoque son jeton de notifications, sinon elle continue de recevoir
> les notifications tant qu'elle est installée. Désinstallez-la ensuite, puis
> installez la nouvelle et reconnectez-vous. Les données locales non synchronisées de l'ancienne
> app ne sont pas reprises.

### Vérification Android

- `grep -rn "com.example.keevo" keevo/app/android --include=*.kts --include=*.kt` ne
  renvoie rien.
- `adb shell pm list packages | grep keevo` affiche `package:com.keevo.keevo` après
  installation.
- La connexion fonctionne et un push de test (alerte stock) arrive au premier plan et
  en arrière-plan ; le jeton est enregistré côté backend avec la plateforme `ANDROID`.
