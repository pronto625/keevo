import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/sync_monitoring_providers.dart';
import 'package:keevo/core/sync/sync_quarantine_service.dart';
import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/core/sync/domain/sync_conflict_provider.dart';
import 'package:keevo/features/sync_indicator/presentation/page/sync_settings_page.dart';
import 'package:keevo/core/sync/domain/sync_conflict.dart';
import 'package:mocktail/mocktail.dart';

import '../../../../helpers/no_technical_text.dart';

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

  Widget _buildPage({List<Map<String, dynamic>>? devices}) => ProviderScope(
        overrides: [
          activeDevicesProvider.overrideWith((ref) => Future.value(devices ??
              [
                {'deviceId': 'test-device', 'lastPushAt': '2024-01-01T00:00:00Z'},
              ])),
          currentDeviceIdProvider.overrideWith((ref) async => 'test-device'),
          syncHistoryProvider.overrideWith((ref) => Future.value([])),
          pendingSyncQueueProvider.overrideWith((ref) => Future.value([])),
          pendingSyncCountProvider.overrideWith((ref) => Future.value(0)),
          quarantinedSyncQueueProvider.overrideWith((ref) => Future.value([])),
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

    // Device test: a device that only pulled read « Jamais synchronisé ».
    testWidgets('a device that only received data is not « Jamais synchronisé »',
        (tester) async {
      _setPhoneSize(tester);
      final recent = DateTime.now().toUtc().subtract(const Duration(minutes: 5));
      await tester.pumpWidget(_buildPage(devices: [
        {
          'deviceId': 'test-device',
          'lastPushAt': '',
          'updatedAt': recent.toIso8601String(),
        },
      ]));
      await tester.pumpAndSettle();

      expect(find.text('Jamais synchronisé'), findsNothing);
      expect(find.textContaining('Vu '), findsOneWidget);
    });

    testWidgets('shows active devices section', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildPage());
      await tester.pumpAndSettle();

      expect(find.textContaining('Appareils actifs'), findsOneWidget);
      // v1s-16-20: whose device it is, never its id.
      expect(find.text('Cet appareil'), findsOneWidget);
      expect(find.text('test-device'), findsNothing);
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
          quarantinedSyncQueueProvider.overrideWith((ref) => Future.value([])),
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
      expect(find.textContaining('Appareils actifs — 30 derniers jours (10)'), findsOneWidget);
      // Tabs below the device list remain visible and reachable.
      expect(find.text('File d\'attente'), findsOneWidget);
      await tester.tap(find.text('File d\'attente'));
      await tester.pumpAndSettle();
      expect(find.text('Aucune opération en attente'), findsOneWidget);
    });

    testWidgets(
        'v1s-16-20 guard: devices, history, conflicts and queue show no id, '
        'code, exception text or JSON', (tester) async {
      _setPhoneSize(tester);
      const uuid = '7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f';
      final at = DateTime(2026, 10, 11, 9);
      final conflict = SyncConflict(
        id: uuid,
        operationId: uuid,
        operationType: 'UPDATE_PRODUCT',
        entityId: uuid,
        entityType: 'PRODUCT',
        conflictType: 'LAST_WRITE_WINS',
        strategy: 'LAST_WRITE_WINS',
        conflictData: {
          'entityType': 'PRODUCT',
          'entityId': uuid,
          'overwrittenTimestamp': '2026-10-11T08:00:00Z',
          'nested': {'a': 1},
        },
        resolvedAt: at,
      );
      await tester.pumpWidget(ProviderScope(
        overrides: [
          activeDevicesProvider.overrideWith((ref) => Future.value([
                {'deviceId': uuid, 'userId': uuid, 'lastPushAt': ''},
                {'deviceId': 'other-$uuid', 'lastPushAt': ''},
              ])),
          currentDeviceIdProvider.overrideWith((ref) async => uuid),
          syncHistoryProvider.overrideWith((ref) => Future.value([
                SyncEvent(
                    id: '1',
                    type: 'PUSH',
                    status: 'FAILED',
                    operationCount: 0,
                    errorMessage: 'DioException [bad response]: status code '
                        '500 for $uuid',
                    createdAt: at),
                SyncEvent(
                    id: '2',
                    type: 'SOME_TYPE',
                    status: 'SOME_STATUS',
                    operationCount: 0,
                    errorMessage: '423 gate: 8 days stale',
                    createdAt: at),
              ])),
          pendingSyncQueueProvider.overrideWith((ref) => Future.value([
                SyncQueueData(
                    id: uuid,
                    operation: 'SOME_FUTURE_OP',
                    payload: '{"productId":"$uuid"}',
                    createdAt: at,
                    synced: false,
                    retryCount: 1,
                    entityId: uuid,
                    lastError: 'SOME_UNKNOWN_CODE'),
              ])),
          pendingSyncCountProvider.overrideWith((ref) => Future.value(1)),
          quarantinedSyncQueueProvider.overrideWith((ref) => Future.value([])),
          syncConflictsProvider.overrideWith((ref) => Future.value([conflict])),
          syncStatusProvider.overrideWith(
              (ref) => Stream.value(SyncStatus.online)),
        ],
        child: const MaterialApp(home: SyncSettingsPage()),
      ));
      await tester.pumpAndSettle();
      expect(find.text('Cet appareil'), findsOneWidget);
      expect(find.text('Autre appareil'), findsOneWidget);
      expect(find.text('Envoi des données échoué'), findsOneWidget);
      expectNoTechnicalText(tester);

      await tester.tap(find.text('Conflits'));
      await tester.pumpAndSettle();
      expectNoTechnicalText(tester);
      await tester.tap(find.textContaining('Modification écrasée'));
      await tester.pumpAndSettle();
      expect(find.text('Détail du conflit'), findsOneWidget);
      expectNoTechnicalText(tester);
      Navigator.of(tester.element(find.text('Détail du conflit'))).pop();
      await tester.pumpAndSettle();

      await tester.tap(find.text('File d\'attente'));
      await tester.pumpAndSettle();
      expect(find.text('Opération'), findsOneWidget);
      expectNoTechnicalText(tester);
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

    testWidgets(
        'v1s-16-15: queue tab shows "Bloquées (1)" above the pending tiles',
        (tester) async {
      _setPhoneSize(tester);
      SyncQueueData row(String id, String operation, {bool blocked = false}) =>
          SyncQueueData(
            id: id,
            operation: operation,
            payload: '{}',
            createdAt: DateTime(2026, 10, 11, 9),
            synced: false,
            retryCount: blocked ? 1 : 0,
            entityId: 'e-$id',
            lastError: blocked ? 'PRODUCT_NOT_FOUND' : null,
            quarantinedAt: blocked ? DateTime(2026, 10, 11, 10) : null,
          );
      await tester.pumpWidget(ProviderScope(
        overrides: [
          activeDevicesProvider.overrideWith((ref) => Future.value([])),
          syncHistoryProvider.overrideWith((ref) => Future.value([])),
          pendingSyncQueueProvider.overrideWith(
              (ref) => Future.value([row('p-1', 'UPDATE_CLIENT')])),
          pendingSyncCountProvider.overrideWith((ref) => Future.value(1)),
          quarantinedSyncQueueProvider.overrideWith((ref) =>
              Future.value([row('q-1', 'STOCK_ADJUST', blocked: true)])),
          syncQuarantineServiceProvider
              .overrideWithValue(_MockQuarantine()),
          currentUserRoleProvider.overrideWithValue('OWNER'),
          syncConflictsProvider.overrideWith((ref) => Future.value([])),
          syncStatusProvider.overrideWith(
              (ref) => Stream.value(SyncStatus.online)),
        ],
        child: const MaterialApp(home: SyncSettingsPage()),
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.text('File d\'attente'));
      await tester.pumpAndSettle();

      expect(find.text('Bloquées (1)'), findsOneWidget);
      expect(find.text('Ajustement de stock'), findsOneWidget);
      expect(find.text('Modification de client'), findsOneWidget,
          reason: 'the pending tile is still listed');
      expect(find.text('Aucune opération en attente'), findsNothing);
    });
  });
}

class _MockQuarantine extends Mock implements SyncQuarantineService {}
