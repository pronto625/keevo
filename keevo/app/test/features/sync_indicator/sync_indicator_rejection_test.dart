import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/sync_gate_provider.dart';
import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo/core/theme/app_theme.dart';
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
      currentUserRoleProvider.overrideWithValue('OWNER'),
    ]);
    // Safety net if an expectation fails before the in-body dispose.
    addTearDown(container.dispose);

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

    // v1s-16-17 — AA error fill under onBrand content.
    final snack = tester.widget<SnackBar>(find.byType(SnackBar));
    expect(snack.backgroundColor, AppTheme.errorStrong);
    final msg = tester.widget<Text>(find.textContaining('Non synchronisé'));
    expect(msg.style?.color, AppTheme.onBrand);
    expect(
        tester
            .widget<Icon>(find.descendant(
                of: find.byType(SnackBar),
                matching: find.byIcon(Icons.error_outline_rounded)))
            .color,
        AppTheme.onBrand);

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

  testWidgets(
      'v1s-16-15: a quarantined notice says "action requise" and "Voir" opens '
      '/sync/blocked', (tester) async {
    final container = ProviderContainer(overrides: [
      syncStatusProvider.overrideWith((ref) => Stream.value(SyncStatus.online)),
      daysOfflineProvider.overrideWithValue(0),
      daysSinceLastSyncProvider.overrideWithValue(0),
    ]);
    // Safety net if an expectation fails before the in-body dispose.
    addTearDown(container.dispose);
    final router = GoRouter(routes: [
      GoRoute(
        path: '/',
        builder: (_, __) => Scaffold(
          appBar: PreferredSize(
            preferredSize: const Size.fromHeight(56),
            child: AppBar(actions: const [SyncIndicator()]),
          ),
          body: const SizedBox.shrink(),
        ),
      ),
      GoRoute(
        path: '/sync/blocked',
        builder: (_, __) => const Scaffold(body: Text('BLOCKED PAGE')),
      ),
    ]);

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(routerConfig: router),
    ));
    await tester.pump();

    container.read(pendingRejectionNotificationsProvider.notifier).state = [
      {
        'status': 'REJECTED',
        'operation': 'CREATE_SALE',
        'reason': 'PRODUCT_NOT_FOUND',
        'rolledBack': false,
        'quarantined': true,
      },
    ];
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(
        find.text('Bloqué (Enregistrement de vente) : Produit introuvable — '
            'action requise'),
        findsOneWidget);

    await tester.tap(find.text('Voir'));
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(find.text('BLOCKED PAGE'), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
    container.dispose();
  });

  // v1s-16-12 follow-up: /settings/sync is OWNER-only — an employee gets no
  // « Voir » on a plain rejection, but keeps it on a blocked operation.
  testWidgets('employee: « Voir » only on blocked operations', (tester) async {
    final container = ProviderContainer(overrides: [
      syncStatusProvider.overrideWith((ref) => Stream.value(SyncStatus.online)),
      daysOfflineProvider.overrideWithValue(0),
      daysSinceLastSyncProvider.overrideWithValue(0),
      currentUserRoleProvider.overrideWithValue('EMPLOYEE'),
    ]);
    addTearDown(container.dispose);
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
        'operation': 'UPDATE_PRODUCT',
        'reason': 'FORBIDDEN',
      },
    ];
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.textContaining('Non synchronisé'), findsOneWidget);
    expect(find.byType(SnackBarAction), findsNothing);

    tester
        .state<ScaffoldMessengerState>(find.byType(ScaffoldMessenger))
        .hideCurrentSnackBar();
    await tester.pumpAndSettle();

    container.read(pendingRejectionNotificationsProvider.notifier).state = [
      {
        'status': 'REJECTED',
        'operation': 'CREATE_SALE',
        'reason': 'INSUFFICIENT_STOCK',
        'quarantined': true,
      },
    ];
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.textContaining('Bloqué'), findsOneWidget);
    expect(find.widgetWithText(SnackBarAction, 'Voir'), findsOneWidget);

    tester
        .state<ScaffoldMessengerState>(find.byType(ScaffoldMessenger))
        .hideCurrentSnackBar();
    await tester.pumpAndSettle();
    container.dispose();
    await tester.pumpWidget(const SizedBox());
  });
}
