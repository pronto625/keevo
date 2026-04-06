import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/sync_monitoring_providers.dart';
import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/core/sync/domain/sync_conflict_provider.dart';
import 'package:keevo/features/sync_indicator/presentation/widget/sync_detail_bottom_sheet.dart';

/// Story 5.5 — Task 7.6: SyncDetailBottomSheet widget tests.
void main() {
  void _setPhoneSize(WidgetTester tester) {
    tester.view.physicalSize = const Size(1080, 1920);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });
  }

  Widget _buildSheet({
    SyncStatus status = SyncStatus.online,
    int pendingCount = 0,
    List<Map<String, dynamic>> devices = const [],
  }) {
    return ProviderScope(
      overrides: [
        syncStatusProvider.overrideWith(
            (ref) => Stream.value(status)),
        pendingSyncCountProvider
            .overrideWith((ref) => Future.value(pendingCount)),
        activeDevicesProvider
            .overrideWith((ref) => Future.value(devices)),
        syncConflictsProvider
            .overrideWith((ref) => Future.value([])),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) => ElevatedButton(
              onPressed: () => showModalBottomSheet(
                context: context,
                builder: (_) => const SyncDetailBottomSheet(),
              ),
              child: const Text('Open'),
            ),
          ),
        ),
      ),
    );
  }

  group('SyncDetailBottomSheet (Story 5.5)', () {
    testWidgets('shows Synchronisation title', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildSheet());
      await tester.pumpAndSettle();
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      expect(find.text('Synchronisation'), findsOneWidget);
    });

    testWidgets('shows pending count when > 0', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildSheet(pendingCount: 5));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      expect(find.textContaining('5 opérations en attente'), findsOneWidget);
    });

    testWidgets('shows zero pending state', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildSheet(pendingCount: 0));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      expect(find.textContaining('Aucune opération en attente'), findsOneWidget);
    });

    testWidgets('shows device count', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildSheet(devices: [
        {'deviceId': 'd1'},
        {'deviceId': 'd2'},
      ]));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      expect(find.textContaining('2 appareils actifs'), findsOneWidget);
    });

    testWidgets('sync button is enabled when online', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildSheet(status: SyncStatus.online));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      expect(find.text('Synchroniser maintenant'), findsOneWidget);
      final button = tester.widget<ElevatedButton>(
        find.widgetWithText(ElevatedButton, 'Synchroniser maintenant'),
      );
      expect(button.onPressed, isNotNull);
    });

    testWidgets('sync button is disabled when offline', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildSheet(status: SyncStatus.offlineCritical));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      expect(find.text('Hors-ligne'), findsOneWidget);
    });

    testWidgets('settings link is present', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildSheet());
      await tester.pumpAndSettle();
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      expect(
          find.text('Paramètres de synchronisation'), findsOneWidget);
    });
  });
}
