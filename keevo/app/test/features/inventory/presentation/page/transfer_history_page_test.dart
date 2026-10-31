import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/catalog/presentation/provider/product_provider.dart';
import 'package:keevo/features/inventory/domain/model/stock_transfer_model.dart';
import 'package:keevo/features/inventory/domain/repository/stock_transfer_repository.dart';
import 'package:keevo/features/inventory/presentation/page/transfer_history_page.dart';
import 'package:keevo/features/inventory/presentation/provider/stock_transfer_provider.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/presentation/provider/active_store_provider.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';

import '../../../../helpers/no_technical_text.dart';

class _MockTransferRepo extends Mock implements StockTransferRepository {}

class _FixedActiveStore extends ActiveStoreNotifier {
  @override
  String? build() => 'store-1';
}

class _ActiveStore extends ActiveStoreNotifier {
  _ActiveStore(this._id);
  final String? _id;
  @override
  String? build() => _id;
}

/// Store list still loading (never completes during the test).
class _LoadingStoreList extends StoreListNotifier {
  @override
  Future<List<StoreModel>> build() => Completer<List<StoreModel>>().future;
}

class _LoadedStoreList extends StoreListNotifier {
  @override
  Future<List<StoreModel>> build() async => [
        StoreModel(
          id: 'store-1',
          name: 'Centre',
          createdAt: DateTime(2026),
          updatedAt: DateTime(2026),
        ),
      ];
}

StockTransferModel _transfer(String id) => StockTransferModel(
      id: id,
      sourceStoreId: 'store-1',
      destinationStoreId: 'store-2',
      productId: 'p1',
      quantity: 2,
      actorId: 'u1',
      occurredAt: DateTime(2026, 1, 1),
      status: 'COMPLETED',
      productName: 'Riz',
    );

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  late _MockTransferRepo repo;

  setUp(() {
    repo = _MockTransferRepo();
    when(() => repo.getHistory(
          storeId: any(named: 'storeId'),
          status: any(named: 'status'),
          page: any(named: 'page'),
          pageSize: any(named: 'pageSize'),
        )).thenAnswer((_) async => [_transfer('t1')]);
  });

  Widget buildPage({bool storesLoaded = false}) => ProviderScope(
        retry: noProviderRetry,
        overrides: [
          stockTransferRepositoryProvider.overrideWithValue(repo),
          activeStoreIdProvider.overrideWith(_FixedActiveStore.new),
          storeListNotifierProvider.overrideWith(
              storesLoaded ? _LoadedStoreList.new : _LoadingStoreList.new),
          productListForPickerProvider.overrideWith((_) async => []),
        ],
        child: const MaterialApp(home: TransferHistoryPage()),
      );

  testWidgets('renders while the store list is still loading',
      (tester) async {
    await tester.pumpWidget(buildPage());
    await tester.pump();

    expect(tester.takeException(), isNull);
  });

  testWidgets(
      'v1s-16-20 guard: unnamed stores / product and unknown status never '
      'show an id or a code', (tester) async {
    const uuid = '7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f';
    when(() => repo.getHistory(
          storeId: any(named: 'storeId'),
          status: any(named: 'status'),
          page: any(named: 'page'),
          pageSize: any(named: 'pageSize'),
        )).thenAnswer((_) async => [
          StockTransferModel(
            id: uuid,
            sourceStoreId: 'store-1',
            destinationStoreId: uuid,
            productId: uuid,
            quantity: 2,
            actorId: uuid,
            occurredAt: DateTime(2026, 1, 1),
            status: 'SOME_NEW_STATUS',
          ),
        ]);
    await tester.pumpWidget(buildPage(storesLoaded: true));
    await tester.pumpAndSettle();

    expect(find.textContaining('Some new status'), findsOneWidget);
    expectNoTechnicalText(tester);
  });

  testWidgets('pull-to-refresh reloads the history', (tester) async {
    await tester.pumpWidget(buildPage(storesLoaded: true));
    await tester.pumpAndSettle();
    expect(find.text('Centre'), findsWidgets);

    await tester.fling(find.byType(CustomScrollView), const Offset(0, 400), 1000);
    await tester.pumpAndSettle();

    verify(() => repo.getHistory(
          storeId: any(named: 'storeId'),
          status: any(named: 'status'),
          page: any(named: 'page'),
          pageSize: any(named: 'pageSize'),
        )).called(2);
  });

  testWidgets('soft header: no decorative emoji, actions keep their keys',
      (tester) async {
    await tester.pumpWidget(buildPage(storesLoaded: true));
    await tester.pumpAndSettle();

    const emoji = ['🔄', '📍', '🚚', '✅', '⏳', '❌'];
    final texts = tester
        .widgetList<Text>(find.byType(Text))
        .map((t) => t.data ?? t.textSpan?.toPlainText() ?? '');
    for (final e in emoji) {
      expect(texts.where((t) => t.contains(e)), isEmpty, reason: e);
    }
    expect(find.byKey(const Key('new_transfer_fab')), findsOneWidget);
    expect(find.byKey(const Key('new_transfer_fab_bottom')), findsOneWidget);
    expect(find.text('Effectué'), findsOneWidget);
  });

  // ── Story v1s-12-11 (routed from CR v1s-12-9) — "Réceptionner" visibility ──
  group('_shouldShowReceiveButton', () {
    const inTransitKey = Key('receive_transfer_t-it');

    Future<void> pumpWith(WidgetTester tester,
        {required String role, String? activeStoreId}) async {
      when(() => repo.getHistory(
            storeId: any(named: 'storeId'),
            status: any(named: 'status'),
            page: any(named: 'page'),
            pageSize: any(named: 'pageSize'),
          )).thenAnswer((_) async => [
            // store-1 → store-2, in transit
            _transfer('t-it').copyWith(status: 'IN_TRANSIT'),
          ]);
      await tester.pumpWidget(ProviderScope(
        retry: noProviderRetry,
        overrides: [
          stockTransferRepositoryProvider.overrideWithValue(repo),
          activeStoreIdProvider.overrideWith(() => _ActiveStore(activeStoreId)),
          currentUserRoleProvider.overrideWithValue(role),
          storeListNotifierProvider.overrideWith(_LoadedStoreList.new),
          productListForPickerProvider.overrideWith((_) async => []),
        ],
        child: const MaterialApp(home: TransferHistoryPage()),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('EMPLOYEE, active == destination → visible', (tester) async {
      await pumpWith(tester, role: 'EMPLOYEE', activeStoreId: 'store-2');
      expect(find.byKey(inTransitKey), findsOneWidget);
    });

    testWidgets('EMPLOYEE, active != destination → hidden', (tester) async {
      // active == source: the tile is listed (outgoing) but not receivable.
      await pumpWith(tester, role: 'EMPLOYEE', activeStoreId: 'store-1');
      expect(find.text('Riz'), findsWidgets);
      expect(find.byKey(inTransitKey), findsNothing);
    });

    testWidgets('EMPLOYEE, active == null → hidden', (tester) async {
      await pumpWith(tester, role: 'EMPLOYEE', activeStoreId: null);
      expect(find.text('Riz'), findsWidgets);
      expect(find.byKey(inTransitKey), findsNothing);
    });

    testWidgets('OWNER, active == null → visible', (tester) async {
      await pumpWith(tester, role: 'OWNER', activeStoreId: null);
      expect(find.byKey(inTransitKey), findsOneWidget);
    });
  });

  // ── v1s-16-23 — store/status in the query, paged list ───────────────────
  group('paged history (v1s-16-23)', () {
    // The footer spinner runs while more pages exist: no pumpAndSettle.
    Future<void> settle(WidgetTester tester) async {
      for (var i = 0; i < 10; i++) {
        await tester.pump(const Duration(milliseconds: 50));
      }
    }

    List<StockTransferModel> pageOf(int page, int count) => [
          for (var i = 0; i < count; i++)
            _transfer('p$page-$i').copyWith(productName: 'Produit $page-$i'),
        ];

    testWidgets('active store and status chip go into the query',
        (tester) async {
      await tester.pumpWidget(buildPage(storesLoaded: true));
      await tester.pumpAndSettle();

      verify(() => repo.getHistory(
            storeId: 'store-1',
            status: null,
            page: 0,
            pageSize: kTransferHistoryPageSize,
          )).called(1);

      await tester.tap(find.text('Effectués'));
      await tester.pumpAndSettle();

      verify(() => repo.getHistory(
            storeId: 'store-1',
            status: 'COMPLETED',
            page: 0,
            pageSize: kTransferHistoryPageSize,
          )).called(1);
    });

    testWidgets(
        'a store involved only in old transfers sees them, sent and received '
        '(no client filter on the page)', (tester) async {
      // store-1 is destination of one, source of the other.
      when(() => repo.getHistory(
            storeId: any(named: 'storeId'),
            status: any(named: 'status'),
            page: any(named: 'page'),
            pageSize: any(named: 'pageSize'),
          )).thenAnswer((_) async => [
            _transfer('sent').copyWith(productName: 'Envoyé'),
            _transfer('recv').copyWith(
              sourceStoreId: 'store-9',
              destinationStoreId: 'store-1',
              productName: 'Reçu',
              occurredAt: DateTime(2024, 1, 1),
            ),
          ]);
      await tester.pumpWidget(buildPage(storesLoaded: true));
      await tester.pumpAndSettle();

      expect(find.text('Envoyé'), findsOneWidget);
      expect(find.text('Reçu'), findsOneWidget);
    });

    testWidgets('scrolling to the end appends the next page without duplicate',
        (tester) async {
      when(() => repo.getHistory(
            storeId: any(named: 'storeId'),
            status: any(named: 'status'),
            page: any(named: 'page'),
            pageSize: any(named: 'pageSize'),
          )).thenAnswer((inv) async {
        final page = inv.namedArguments[#page] as int;
        if (page == 0) return pageOf(0, kTransferHistoryPageSize);
        // Overlaps the first page by one row (a new transfer shifted offsets).
        return [pageOf(0, kTransferHistoryPageSize).last, ...pageOf(1, 3)];
      });
      await tester.pumpWidget(buildPage(storesLoaded: true));
      await settle(tester);

      await tester.scrollUntilVisible(find.text('Produit 1-2'), 300,
          scrollable: find.byType(Scrollable).first);
      await settle(tester);

      expect(find.text('Produit 1-2'), findsOneWidget);
      final last = 'Produit 0-${kTransferHistoryPageSize - 1}';
      await tester.scrollUntilVisible(find.text(last), -300,
          scrollable: find.byType(Scrollable).first);
      expect(find.text(last), findsOneWidget);
      verify(() => repo.getHistory(
            storeId: any(named: 'storeId'),
            status: any(named: 'status'),
            page: 1,
            pageSize: any(named: 'pageSize'),
          )).called(1);
      verifyNever(() => repo.getHistory(
            storeId: any(named: 'storeId'),
            status: any(named: 'status'),
            page: 2,
            pageSize: any(named: 'pageSize'),
          ));
    });

    testWidgets('a failed next page shows « Réessayer », which reloads it',
        (tester) async {
      var failNext = true;
      when(() => repo.getHistory(
            storeId: any(named: 'storeId'),
            status: any(named: 'status'),
            page: any(named: 'page'),
            pageSize: any(named: 'pageSize'),
          )).thenAnswer((inv) async {
        final page = inv.namedArguments[#page] as int;
        if (page == 0) return pageOf(0, kTransferHistoryPageSize);
        if (failNext) {
          failNext = false;
          throw Exception('offline');
        }
        return pageOf(1, 2);
      });
      await tester.pumpWidget(buildPage(storesLoaded: true));
      await settle(tester);

      final retry = find.byKey(const Key('transfer_history_retry'));
      await tester.scrollUntilVisible(retry, 300,
          scrollable: find.byType(Scrollable).first);
      await settle(tester);
      expect(find.text('Réessayer'), findsOneWidget);

      await tester.tap(retry);
      await settle(tester);
      await tester.scrollUntilVisible(find.text('Produit 1-1'), 300,
          scrollable: find.byType(Scrollable).first);
      expect(find.text('Produit 1-1'), findsOneWidget);
      expect(retry, findsNothing);
    });

    testWidgets('a rebuilt list drops a stale « Réessayer » and loads on',
        (tester) async {
      var page1Calls = 0;
      when(() => repo.getHistory(
            storeId: any(named: 'storeId'),
            status: any(named: 'status'),
            page: any(named: 'page'),
            pageSize: any(named: 'pageSize'),
          )).thenAnswer((inv) async {
        final page = inv.namedArguments[#page] as int;
        if (page == 0) return pageOf(0, kTransferHistoryPageSize);
        page1Calls++;
        throw Exception('offline');
      });
      await tester.pumpWidget(buildPage(storesLoaded: true));
      await settle(tester);

      final retry = find.byKey(const Key('transfer_history_retry'));
      await tester.scrollUntilVisible(retry, 300,
          scrollable: find.byType(Scrollable).first);
      await settle(tester);
      expect(retry, findsOneWidget);
      final callsBefore = page1Calls;

      // Sync / form invalidation rebuilds the first page.
      ProviderScope.containerOf(tester.element(find.byType(TransferHistoryPage)))
          .invalidate(transferHistoryProvider);
      await settle(tester);
      await tester.drag(find.byType(Scrollable).first, const Offset(0, -300));
      await settle(tester);

      expect(page1Calls, greaterThan(callsBefore),
          reason: 'auto-load is back on after the rebuild');
    });
  });
}
