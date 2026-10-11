import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/core/sync/domain/sync_conflict.dart';
import 'package:keevo/core/sync/domain/sync_conflict_provider.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/core/widget/app_error_widget.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:keevo/features/pos/presentation/page/sale_detail_page.dart';
import 'package:keevo/features/pos/presentation/provider/pos_providers.dart';
import 'package:keevo/features/profitability/presentation/page/store_performance_page.dart';
import 'package:keevo/features/profitability/presentation/provider/profitability_providers.dart';
import 'package:keevo/features/settings/domain/model/subscription_info.dart';
import 'package:keevo/features/settings/presentation/page/subscription_page.dart';
import 'package:keevo/features/settings/presentation/provider/subscription_info_provider.dart';
import 'package:keevo/features/sync_indicator/presentation/page/sync_conflict_log_page.dart';

/// v1s-16-11 — error → tap "Réessayer" → data shown, on representative
/// screens: a plain provider, a family provider, a sliver site and migrated
/// ad-hoc error states. Each provider fails on its first call only.
void main() {
  setUpAll(() async {
    GoogleFonts.config.allowRuntimeFetching = false;
    await initializeDateFormatting('fr_FR');
  });

  Future<void> pumpPage(
    WidgetTester tester,
    List<Override> overrides,
    Widget page,
  ) async {
    await tester.pumpWidget(ProviderScope(
      overrides: overrides,
      child: MaterialApp(home: page),
    ));
    await tester.pumpAndSettle();
  }

  Future<void> tapRetry(WidgetTester tester) async {
    expect(find.byType(AppErrorWidget), findsOneWidget);
    await tester.tap(find.text('Réessayer'));
    await tester.pumpAndSettle();
  }

  testWidgets('SyncConflictLogPage (plain provider): retry reloads the list',
      (tester) async {
    var calls = 0;
    await pumpPage(
      tester,
      [
        syncConflictsProvider.overrideWith((ref) async {
          calls++;
          if (calls == 1) throw Exception('Base locale indisponible');
          return <SyncConflict>[];
        }),
      ],
      const SyncConflictLogPage(),
    );

    expect(find.text('Base locale indisponible'), findsOneWidget);
    await tapRetry(tester);

    expect(calls, 2);
    expect(find.byType(AppErrorWidget), findsNothing);
    expect(find.text('Aucun conflit de synchronisation'), findsOneWidget);
  });

  testWidgets('SaleDetailPage (family provider): retry reloads the same sale',
      (tester) async {
    final requested = <String>[];
    await pumpPage(
      tester,
      [
        saleByIdProvider.overrideWith((ref, saleId) async {
          requested.add(saleId);
          if (requested.length == 1) throw Exception('Lecture impossible');
          return null as Sale?;
        }),
      ],
      const SaleDetailPage(saleId: 'sale-42'),
    );

    await tapRetry(tester);

    expect(requested, ['sale-42', 'sale-42']);
    expect(find.byType(AppErrorWidget), findsNothing);
    expect(find.text('Vente non trouvée'), findsOneWidget);
  });

  testWidgets(
      'StorePerformancePage (sliver + migrated ad-hoc): retry reloads data',
      (tester) async {
    var calls = 0;
    await pumpPage(
      tester,
      [
        // No status event: the page's back-online auto-refresh stays idle,
        // so only "Réessayer" can trigger the reload.
        syncStatusProvider.overrideWith((ref) => const Stream.empty()),
        storePerformanceProvider.overrideWith((ref, params) async {
          calls++;
          if (calls == 1) throw Exception('Serveur indisponible');
          return <StorePerformanceEntry>[];
        }),
      ],
      const StorePerformancePage(),
    );

    expect(find.text('Impossible de charger les données'), findsOneWidget);
    await tapRetry(tester);

    expect(calls, 2);
    expect(find.byType(AppErrorWidget), findsNothing);
    expect(find.text('Aucune boutique active'), findsOneWidget);
  });

  testWidgets('SubscriptionPage (migrated ad-hoc): retry shows the plan',
      (tester) async {
    var calls = 0;
    await pumpPage(
      tester,
      [
        subscriptionInfoProvider.overrideWith((ref) async {
          calls++;
          if (calls == 1) throw Exception('Serveur indisponible');
          return const SubscriptionInfo(
            planType: 'FREE',
            status: 'ACTIVE',
            currentStores: 1,
            maxStores: 1,
            currentProducts: 3,
            maxProducts: 50,
            currentEmployees: 0,
            maxEmployees: 1,
          );
        }),
      ],
      const SubscriptionPage(),
    );

    expect(find.text('Impossible de charger l\'abonnement'), findsOneWidget);
    await tapRetry(tester);

    expect(calls, 2);
    expect(find.byType(AppErrorWidget), findsNothing);
    expect(find.text('Gratuit'), findsWidgets);
  });
  testWidgets('retry that fails again shows the error state again',
      (tester) async {
    var calls = 0;
    await pumpPage(
      tester,
      [
        syncConflictsProvider.overrideWith((ref) async {
          calls++;
          if (calls <= 2) throw Exception('Base locale indisponible');
          return <SyncConflict>[];
        }),
      ],
      const SyncConflictLogPage(),
    );

    await tapRetry(tester);
    expect(calls, 2);
    expect(tester.takeException(), isNull);
    expect(find.text('Base locale indisponible'), findsOneWidget);

    // Button is still usable: the third attempt succeeds.
    await tapRetry(tester);
    expect(calls, 3);
    expect(find.text('Aucun conflit de synchronisation'), findsOneWidget);
  });
}
