import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/sync_monitoring_providers.dart';
import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/core/sync/domain/sync_conflict_provider.dart';
import 'package:keevo/features/sync_indicator/presentation/page/sync_settings_page.dart';

/// Story 5.5 — Task 7.5: SyncSettingsPage widget tests.
void main() {
  void _setPhoneSize(WidgetTester tester) {
    tester.view.physicalSize = const Size(1080, 1920);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });
  }

  Widget _buildPage() => ProviderScope(
        overrides: [
          activeDevicesProvider.overrideWith((ref) => Future.value([
                {'deviceId': 'test-device', 'lastPushAt': '2024-01-01T00:00:00Z'},
              ])),
          syncHistoryProvider.overrideWith((ref) => Future.value([])),
          pendingSyncQueueProvider.overrideWith((ref) => Future.value([])),
          pendingSyncCountProvider.overrideWith((ref) => Future.value(0)),
          syncConflictsProvider.overrideWith((ref) => Future.value([])),
          syncStatusProvider.overrideWith(
              (ref) => Stream.value(SyncStatus.online)),
        ],
        child: const MaterialApp(home: SyncSettingsPage()),
      );

  group('SyncSettingsPage (Story 5.5)', () {
    testWidgets('displays 3 tabs: Historique, Conflits, File d\'attente',
        (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildPage());
      await tester.pumpAndSettle();

      expect(find.text('Historique'), findsOneWidget);
      expect(find.text('Conflits'), findsOneWidget);
      expect(find.text('File d\'attente'), findsOneWidget);
    });

    testWidgets('shows active devices section', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildPage());
      await tester.pumpAndSettle();

      expect(find.textContaining('Appareils actifs'), findsOneWidget);
      expect(find.text('test-device'), findsOneWidget);
    });

    testWidgets(
        'many active devices stay scrollable and never overflow or hide the tabs',
        (tester) async {
      _setPhoneSize(tester);
      final manyDevices = List.generate(
          10,
          (i) => {
                'deviceId': 'device-$i-aaaaaaaaaaaaaaaa',
                'lastPushAt': '2024-01-01T00:00:00Z',
              });
      await tester.pumpWidget(ProviderScope(
        overrides: [
          activeDevicesProvider.overrideWith((ref) => Future.value(manyDevices)),
          syncHistoryProvider.overrideWith((ref) => Future.value([])),
          pendingSyncQueueProvider.overrideWith((ref) => Future.value([])),
          pendingSyncCountProvider.overrideWith((ref) => Future.value(0)),
          syncConflictsProvider.overrideWith((ref) => Future.value([])),
          syncStatusProvider.overrideWith(
              (ref) => Stream.value(SyncStatus.online)),
        ],
        child: const MaterialApp(home: SyncSettingsPage()),
      ));
      await tester.pumpAndSettle();

      // No RenderFlex/overflow errors were thrown during layout.
      expect(tester.takeException(), isNull);
      // The count header reflects all devices even though only a few fit on screen.
      expect(find.textContaining('Appareils actifs (10)'), findsOneWidget);
      // Tabs below the device list remain visible and reachable.
      expect(find.text('File d\'attente'), findsOneWidget);
      await tester.tap(find.text('File d\'attente'));
      await tester.pumpAndSettle();
      expect(find.text('Aucune opération en attente'), findsOneWidget);
    });

    testWidgets('shows FAB for force sync', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildPage());
      await tester.pumpAndSettle();

      expect(find.text('Forcer la sync'), findsOneWidget);
      expect(find.byType(FloatingActionButton), findsOneWidget);
    });

    testWidgets('history tab shows empty state when no events',
        (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildPage());
      await tester.pumpAndSettle();

      // First tab (Historique) is active by default
      expect(find.text('Aucun événement de synchronisation'), findsOneWidget);
    });

    testWidgets('conflits tab shows empty state', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildPage());
      await tester.pumpAndSettle();

      // Tap on Conflits tab
      await tester.tap(find.text('Conflits'));
      await tester.pumpAndSettle();

      expect(find.text('Aucun conflit'), findsOneWidget);
    });

    testWidgets('queue tab shows empty state', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildPage());
      await tester.pumpAndSettle();

      // Tap on File d'attente tab
      await tester.tap(find.text('File d\'attente'));
      await tester.pumpAndSettle();

      expect(find.text('Aucune opération en attente'), findsOneWidget);
    });
  });
}
