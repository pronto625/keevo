import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/sync/sync_monitoring_providers.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo/features/sync_indicator/presentation/widget/sync_indicator.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/sync/sync_gate_provider.dart';
import 'package:keevo_core/sync/sync_status.dart';
import 'package:keevo_core/sync/sync_status_provider.dart';
import 'package:keevo_core/theme/app_theme.dart';

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

  // J27 (device test): « Bloqué (Transfert de stock) … » stayed on screen
  // for minutes, covering the bottom buttons: a SnackBar with an action
  // persists by default (Flutter ≥ 3.32). Once per operation, auto-dismissed,
  // closable.
  group('J27 / J25 — blocked operation notice', () {
    setUp(SyncIndicator.resetSurfacedRejections);

    Future<ProviderContainer> pumpIndicator(WidgetTester tester,
        {int blocked = 0}) async {
      final container = ProviderContainer(overrides: [
        syncStatusProvider
            .overrideWith((ref) => Stream.value(SyncStatus.online)),
        daysOfflineProvider.overrideWithValue(0),
        daysSinceLastSyncProvider.overrideWithValue(0),
        currentUserRoleProvider.overrideWithValue('OWNER'),
        quarantinedSyncCountProvider
            .overrideWith((ref) => Future.value(blocked)),
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
      await tester.pump();
      return container;
    }

    Map<String, dynamic> blockedTransfer() => {
          'operationId': 'op-transfer-1',
          'status': 'REJECTED',
          'operation': 'STOCK_TRANSFER',
          'reason': 'INSUFFICIENT_STOCK',
          'rolledBack': false,
          'quarantined': true,
        };

    testWidgets('the notice auto-dismisses and can be closed', (tester) async {
      final container = await pumpIndicator(tester);
      container.read(pendingRejectionNotificationsProvider.notifier).state = [
        blockedTransfer(),
      ];
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));

      final snack = tester.widget<SnackBar>(find.byType(SnackBar));
      expect(snack.persist, isFalse);
      expect(snack.showCloseIcon, isTrue);

      await tester.pump(const Duration(seconds: 7));
      await tester.pumpAndSettle();
      expect(find.byType(SnackBar), findsNothing);

      await tester.pumpWidget(const SizedBox.shrink());
      container.dispose();
    });

    testWidgets('the same blocked operation is announced only once',
        (tester) async {
      final container = await pumpIndicator(tester);
      final notifier =
          container.read(pendingRejectionNotificationsProvider.notifier);
      notifier.state = [blockedTransfer()];
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.byType(SnackBar), findsOneWidget);
      await tester.pump(const Duration(seconds: 7));
      await tester.pumpAndSettle();
      expect(find.byType(SnackBar), findsNothing);

      notifier.state = [blockedTransfer()];
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.byType(SnackBar), findsNothing);

      await tester.pumpWidget(const SizedBox.shrink());
      container.dispose();
    });

    testWidgets('J25: a blocked operation turns « En ligne » into '
        '« Action requise »', (tester) async {
      final container = await pumpIndicator(tester, blocked: 1);

      expect(find.text('Action requise'), findsOneWidget);
      expect(find.text('En ligne'), findsNothing);

      await tester.pumpWidget(const SizedBox.shrink());
      container.dispose();
    });

    testWidgets('J25: no blocked operation keeps « En ligne »', (tester) async {
      final container = await pumpIndicator(tester);

      expect(find.text('En ligne'), findsOneWidget);
      expect(find.text('Action requise'), findsNothing);

      await tester.pumpWidget(const SizedBox.shrink());
      container.dispose();
    });

    // J27 follow-up (device test): after « Renvoyer » succeeded the notice
    // was still covering « ENCAISSER ».
    testWidgets('the notice closes as soon as no operation is blocked',
        (tester) async {
      final blocked = StateProvider<int>((ref) => 1);
      final container = ProviderContainer(overrides: [
        syncStatusProvider
            .overrideWith((ref) => Stream.value(SyncStatus.online)),
        daysOfflineProvider.overrideWithValue(0),
        daysSinceLastSyncProvider.overrideWithValue(0),
        currentUserRoleProvider.overrideWithValue('OWNER'),
        quarantinedSyncCountProvider
            .overrideWith((ref) async => ref.watch(blocked)),
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
        blockedTransfer(),
      ];
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.byType(SnackBar), findsOneWidget);

      container.read(blocked.notifier).state = 0; // « Renvoyer » succeeded
      await tester.pump();
      await tester.pump();
      await tester.pumpAndSettle();

      expect(find.byType(SnackBar), findsNothing);
      expect(find.text('En ligne'), findsOneWidget);

      await tester.pumpWidget(const SizedBox.shrink());
      container.dispose();
    });

    // J30 (device test): « Voir » did nothing once the POS page (owner of
    // the indicator) had been left — the notice stays on every screen.
    testWidgets('« Voir » opens the blocked list even after leaving the page',
        (tester) async {
      final container = ProviderContainer(overrides: [
        syncStatusProvider
            .overrideWith((ref) => Stream.value(SyncStatus.online)),
        daysOfflineProvider.overrideWithValue(0),
        daysSinceLastSyncProvider.overrideWithValue(0),
        currentUserRoleProvider.overrideWithValue('OWNER'),
        quarantinedSyncCountProvider.overrideWith((ref) async => 1),
      ]);
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
          path: '/product-form',
          builder: (_, __) => const Scaffold(body: Text('PRODUCT FORM')),
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
        blockedTransfer(),
      ];
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      router.go('/product-form'); // the indicator's page is disposed
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      await tester.pump(const Duration(milliseconds: 600));
      expect(find.text('PRODUCT FORM'), findsOneWidget);

      await tester.tap(find.text('Voir'));
      for (var i = 0; i < 10; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(find.text('BLOCKED PAGE'), findsOneWidget);

      await tester.pumpWidget(const SizedBox.shrink());
      container.dispose();
    });
  });
}
