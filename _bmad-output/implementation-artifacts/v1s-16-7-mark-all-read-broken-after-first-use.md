---
baseline_commit: 6a0efea
---
# Story 16.7: Corriger "Tout marquer comme lu" — cassé après le premier usage dans une session

Status: done

<!-- V1-stabilization track — tag A. Branche : v1-stabilization (HEAD 6a0efea).
     Source : audit de cohérence 2026-07-23 (Finding #9). -->

## Story

**As a** Simon ou Loïc (utilisateur de l'app),
**I want** que le bouton "Tout marquer comme lu" fonctionne à chaque fois que je l'utilise,
**so that** je puisse effacer mes notifications non lues sans devoir redémarrer l'application.

## Contexte V1-stabilization

- **Source :** audit `_bmad-output/planning-artifacts/audit-coherence-2026-07-23.md` §3 Finding #9.
- **Tracker :** `sprint-status.yaml` — clé `v1s-16-7-mark-all-read-broken-after-first-use` sous `v1s-epic-16-mobile-hardening-parity` (statut actuel `backlog` — cette story est la première créée pour cet epic, le passer en `in-progress`).
- **Cause exacte** : `markAllAsReadProvider` (`notification_provider.dart:67-71`) est un `FutureProvider<void>` **non-family**. Riverpod n'exécute le corps d'un tel provider qu'**une seule fois** par cycle de vie — le résultat (ici `void`, mais l'exécution elle-même) est mis en cache. `notifications_page.dart:47` l'invoque via `ref.read(markAllAsReadProvider)`, qui ne fait que lire la valeur déjà en cache au 2ᵉ appel **sans ré-exécuter le corps** — donc sans ré-appeler `notificationRepositoryProvider.markAllAsRead()`.

## Acceptance Criteria

1. **AC1 (le bouton fonctionne à chaque tap)** — **Given** `markAllAsReadProvider` est un `FutureProvider<void>` non-family déjà exécuté une première fois dans la session, **When** l'utilisateur retape sur "Tout marquer comme lu" après que de nouvelles notifications non lues soient arrivées, **Then** `notificationRepositoryProvider.markAllAsRead()` est bien ré-exécuté et les nouvelles notifications passent à `isRead=true`.
2. **AC2 (pas de régression sur le 1er usage)** — **Given** le comportement au premier tap est déjà correct aujourd'hui, **When** le fix est appliqué, **Then** le comportement au 1er tap reste inchangé (toutes les notifications passent à lues, `unreadNotificationCountProvider`/`notificationsListProvider` s'invalident correctement).
3. **AC3 (test de régression couvrant précisément ce bug)** — **Given** aucun test actuel n'exerce un 2ᵉ appel au même provider dans le même test, **When** cette story est terminée, **Then** un test widget/provider simule : marquer tout comme lu → nouvelle notification non lue arrive → marquer tout comme lu à nouveau → vérifier que `markAllAsRead()` du repository a bien été appelé 2 fois (pas seulement 1).

## Tasks / Subtasks

- [x] **Task 1 — Fix (AC1, AC2)**
  - [x] 1.1 `notifications_page.dart:47` : remplacer `onPressed: () => ref.read(markAllAsReadProvider)` par `onPressed: () => ref.refresh(markAllAsReadProvider)` — `ref.refresh()` invalide puis relit immédiatement le provider, forçant la ré-exécution de son corps y compris pour un `FutureProvider` non-`autoDispose`.
  - [x] 1.2 Vérifier que `notification_provider.dart:67-71` (le provider lui-même) n'a pas besoin de changement — le fix est uniquement au site d'appel.
- [x] **Task 2 — Test (AC3)**
  - [x] 2.1 Ajouter un test (fichier de test existant pour `notifications_page`/`notification_provider` si présent, sinon le créer) : `ProviderContainer` avec un `NotificationRepository` mocké, appeler l'équivalent de "tap tout marquer comme lu" deux fois avec un état différent entre les deux (nouvelle notification simulée), `verify(mockRepository.markAllAsRead()).called(2)` (ou équivalent Mocktail).
- [x] **Task 3 — Non-régression**
  - [x] 3.1 `flutter test` (module notifications) — 0 nouvelle régression.

## Dev Notes

- **Alternative rejetée** : convertir `markAllAsReadProvider` en `.autoDispose` ne suffirait pas seul — un `FutureProvider.autoDispose` reste mis en cache tant qu'il a un listener actif (le bouton est monté en continu sur la page). `ref.refresh()` est la solution correcte et minimale, déjà idiomatique Riverpod pour ce cas ("re-trigger an action provider on demand").
- **Ne pas** transformer ce provider en `Notifier`/`AsyncNotifier` — changement plus large que nécessaire pour ce bug ponctuel, cohérent avec le principe "un fix chirurgical par story" déjà appliqué dans les autres stories v1s-*.
- **`markAsReadProvider`** (single notification, `.family<void, String>`) n'a PAS ce bug — chaque id distinct crée une nouvelle instance de provider ; seul le cas non-family (`markAllAsReadProvider`) est concerné. Ne pas y toucher.

### Project Structure Notes

- Un seul fichier de production modifié : `keevo/app/lib/features/notifications/presentation/page/notifications_page.dart` (ligne 47). Aucun changement au provider lui-même, aucune migration.

### References

- [Source: _bmad-output/planning-artifacts/audit-coherence-2026-07-23.md#3. Findings — 🟠 Importantes, Finding #9]
- [Source: keevo/app/lib/features/notifications/presentation/provider/notification_provider.dart:67-71]
- [Source: keevo/app/lib/features/notifications/presentation/page/notifications_page.dart:44-49]

## Dev Agent Record

### Agent Model Used

DeepSeek V4 Pro (GitHub Copilot)

### Debug Log References

N/A — pas de debug nécessaire, fix trivial (1 ligne).

### Completion Notes List

✅ **Task 1 (AC1/AC2)** — `notifications_page.dart:47` : `ref.read(markAllAsReadProvider)` → `ref.refresh(markAllAsReadProvider)`. Aucun changement au provider `markAllAsReadProvider` lui-même (Task 1.2 confirmée : le corps du provider était déjà correct, seul le site d'appel était fautif).

✅ **Task 2 (AC3)** — `notification_provider_test.dart` créé avec 3 tests : (a) premier appel vérifie `markAllAsRead().called(1)`, (b) `refresh()` vérifie `called(2)` — c'est le test AC3 qui prouve que le fix fonctionne, (c) test de confirmation du bug : deux `read()` successifs sans `refresh()` → `called(1)` uniquement (le bug avant fix). Pattern Mocktail + ProviderContainer cohérent avec les tests existants (`employee_list_notifier_test.dart`).

✅ **Task 3 (non-régression)** — `flutter test test/features/notifications/` : 14/14 tests GREEN (11 existants + 3 nouveaux), 0 régression. `flutter analyze lib/features/notifications/` : 3 info pré-existants (import ordering dans `notification_bell_widget.dart`, inchangé).

### File List

- `keevo/app/lib/features/notifications/presentation/page/notifications_page.dart` — modifié (1 ligne, `ref.read` → `ref.refresh`)
- `keevo/app/test/features/notifications/presentation/notification_provider_test.dart` — créé (3 tests), puis patché en revue (style `thenAnswer`)
- `keevo/app/test/features/notifications/presentation/page/notifications_page_test.dart` — créé en revue (1 test widget, couvre le wiring du bouton)

### Review Findings

- [x] [Review][Patch] Aucun test n'exerce le bouton "Tout marquer comme lu" lui-même — une régression du wiring (ex. retour à `ref.read`, ou provider mal câblé) passerait inaperçue [keevo/app/lib/features/notifications/presentation/page/notifications_page.dart:48] — corrigé : nouveau test widget `test/features/notifications/presentation/page/notifications_page_test.dart` qui pump `NotificationsPage` et tape le bouton 2 fois, vérifie `markAllAsRead()` appelé 2 fois.
- [x] [Review][Patch] `thenAnswer((_) async => {})` non-idiomatique dans le nouveau fichier de test (3 occurrences) — la convention du projet (131 occurrences ailleurs) est `thenAnswer((_) async {})` [keevo/app/test/features/notifications/presentation/notification_provider_test.dart:25,37,55] — corrigé, remplacé aux 3 occurrences.
