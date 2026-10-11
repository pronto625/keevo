import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/sync/sync_gate_provider.dart';
import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo/features/sync_indicator/presentation/widget/sync_indicator.dart';

/// v1s-16-12: a rejection whose local change was undone says so.
void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  testWidgets('rejection SnackBars carry the rollback suffix only when undone',
      (tester) async {
    final container = ProviderContainer(overrides: [
      syncStatusProvider.overrideWith((ref) => Stream.value(SyncStatus.online)),
      daysOfflineProvider.overrideWithValue(0),
      daysSinceLastSyncProvider.overrideWithValue(0),
    ]);

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        home: Scaffold(
          appBar: PreferredSize(
            preferredSize: const Size.fromHeight(56),
            child: AppBar(actions: const [SyncIndicator()]),
          ),
          body: const SizedBox.shrink(),
        ),
      ),
    ));
    await tester.pump();

    container.read(pendingRejectionNotificationsProvider.notifier).state = [
      {
        'status': 'REJECTED',
        'operation': 'CREATE_PRODUCT',
        'reason': 'PLAN_LIMIT_EXCEEDED',
        'rolledBack': true,
      },
      {
        'status': 'REJECTED',
        'operation': 'CREATE_SALE',
        'reason': 'INSUFFICIENT_STOCK',
        'rolledBack': false,
      },
    ];
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(
        find.text('Non synchronisé (Création de produit) : Limite de votre '
            'forfait atteinte — passez à un forfait supérieur — annulé sur cet '
            'appareil'),
        findsOneWidget);

    // SnackBars are queued: the second shows once the first is gone.
    tester
        .state<ScaffoldMessengerState>(find.byType(ScaffoldMessenger))
        .hideCurrentSnackBar();
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    expect(
        find.text(
            'Non synchronisé (Enregistrement de vente) : Stock insuffisant'),
        findsOneWidget);
    expect(find.textContaining('annulé sur cet appareil'), findsNothing);

    // Dispose inside the test: the sync notifier's periodic timers must not
    // outlive it.
    await tester.pumpWidget(const SizedBox.shrink());
    container.dispose();
  });
}
