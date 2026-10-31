import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/entitlements/entitlements_provider.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';
import 'package:keevo/features/dashboard/presentation/provider/dashboard_providers.dart';
import 'package:keevo/features/inventory/presentation/page/local_low_stock_page.dart';
import 'package:keevo/features/stores/presentation/provider/active_store_provider.dart';
import 'package:keevo_core/state/provider_retry.dart';

import '../../../../helpers/fake_entitlements.dart';

// 13.10b — the dashboard « stock bas » tile / catalogue button open the free
// local list unless the multi-store overview (premium) is available.

class _FixedStore extends ActiveStoreNotifier {
  final String? id;
  _FixedStore(this.id);
  @override
  String? build() => id;
}

const _items = [
  LowStockProduct(productId: 'p1', name: 'Huile', quantity: 0, threshold: 5),
  LowStockProduct(productId: 'p2', name: 'Riz', quantity: 2, threshold: 5),
];

Widget _app({
  required Future<Map<String, String>> Function() modules,
  List<LowStockProduct> items = _items,
  String overviewRoute = '/stock/overview?showLowOnly=true',
  bool allStores = false,
  List<String?>? listedStores,
}) {
  final router = GoRouter(routes: [
    GoRoute(
      path: '/',
      builder: (_, __) => Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => openLowStockView(context,
                overviewRoute: overviewRoute, allStores: allStores),
            child: const Text('stock bas'),
          ),
        ),
      ),
    ),
    GoRoute(
      path: '/stock/overview',
      builder: (_, state) =>
          Text('VUE MULTI-BOUTIQUES ${state.uri.queryParameters}'),
    ),
    GoRoute(
      path: kLocalLowStockRoute,
      builder: (_, state) => LocalLowStockPage(
          allStores: state.uri.queryParameters['all'] == 'true'),
    ),
  ]);
  return ProviderScope(
    retry: noProviderRetry,
    overrides: [
      entitlementsProvider
          .overrideWith(() => FakeEntitlementsNotifier(modules)),
      activeStoreIdProvider.overrideWith(() => _FixedStore('store-1')),
      localLowStockProductsProvider.overrideWith((ref, storeId) async {
        listedStores?.add(storeId);
        return items;
      }),
    ],
    child: MaterialApp.router(routerConfig: router),
  );
}

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  testWidgets('Free tenant (multi-store DISABLED) → local list',
      (tester) async {
    await tester.pumpWidget(_app(
        modules: () async =>
            const {'multi-store': 'DISABLED'}));
    await tester.tap(find.text('stock bas'));
    await tester.pumpAndSettle();

    expect(find.text('Stock bas'), findsOneWidget);
    expect(find.text('Huile'), findsOneWidget);
    expect(find.text('Rupture'), findsOneWidget);
    expect(find.text('Riz'), findsOneWidget);
    expect(find.text('Seuil : 5'), findsNWidgets(2));
    expect(
        find.textContaining('2 produits à réapprovisionner'), findsOneWidget);
    expect(find.textContaining('VUE MULTI-BOUTIQUES'), findsNothing);
  });

  testWidgets(
      'Premium (multi-store ENABLED) → multi-store overview, filter '
      'kept', (tester) async {
    await tester.pumpWidget(_app(
        modules: () async =>
            const {'multi-store': 'ENABLED'}));
    await tester.tap(find.text('stock bas'));
    await tester.pumpAndSettle();

    expect(
        find.text('VUE MULTI-BOUTIQUES {showLowOnly: true}'), findsOneWidget);
  });

  testWidgets('grace period (READ_ONLY) still opens the overview',
      (tester) async {
    await tester.pumpWidget(_app(
        modules: () async =>
            const {'multi-store': 'READ_ONLY'},
        overviewRoute: '/stock/overview'));
    await tester.tap(find.text('stock bas'));
    await tester.pumpAndSettle();

    expect(find.text('VUE MULTI-BOUTIQUES {}'), findsOneWidget);
  });

  testWidgets('no module readable → local list', (tester) async {
    await tester.pumpWidget(_app(modules: () async => const <String, String>{}));
    await tester.tap(find.text('stock bas'));
    await tester.pumpAndSettle();

    expect(find.text('Stock bas'), findsOneWidget);
  });

  testWidgets('modules unreadable (offline) → local list', (tester) async {
    await tester
        .pumpWidget(_app(modules: () async => throw Exception('hors ligne')));
    await tester.tap(find.text('stock bas'));
    await tester.pumpAndSettle();

    expect(find.text('Stock bas'), findsOneWidget);
    expect(find.text('Huile'), findsOneWidget);
  });

  testWidgets('empty list → French empty state', (tester) async {
    await tester.pumpWidget(
        _app(modules: () async => const <String, String>{}, items: const []));
    await tester.tap(find.text('stock bas'));
    await tester.pumpAndSettle();

    expect(find.text('Aucun produit en stock bas'), findsOneWidget);
  });

  testWidgets('allStores (dashboard tile) → list of every store',
      (tester) async {
    final listed = <String?>[];
    await tester.pumpWidget(_app(
        modules: () async => const <String, String>{},
        allStores: true,
        listedStores: listed));
    await tester.tap(find.text('stock bas'));
    await tester.pumpAndSettle();

    expect(listed, [null]);
    expect(find.textContaining('toutes les boutiques'), findsOneWidget);
  });

  testWidgets('a product low in 2 stores counts once (as the tile)',
      (tester) async {
    await tester.pumpWidget(_app(
      modules: () async => const <String, String>{},
      allStores: true,
      items: const [
        LowStockProduct(
            productId: 'p1', name: 'Huile', quantity: 1, threshold: 5,
            storeName: 'Centre'),
        LowStockProduct(
            productId: 'p1', name: 'Huile', quantity: 2, threshold: 5,
            storeName: 'Annexe'),
      ],
    ));
    await tester.tap(find.text('stock bas'));
    await tester.pumpAndSettle();

    expect(find.textContaining('1 produit à réapprovisionner'), findsOneWidget);
    expect(find.text('Seuil : 5 · Annexe'), findsOneWidget);
  });

  testWidgets('double tap while the modules load → one push', (tester) async {
    final gate = Completer<Map<String, String>>();
    await tester.pumpWidget(_app(modules: () => gate.future));
    await tester.tap(find.text('stock bas'));
    await tester.pump();
    await tester.tap(find.text('stock bas'));
    await tester.pump();
    gate.complete(const <String, String>{});
    await tester.pumpAndSettle();

    expect(find.text('Stock bas'), findsOneWidget);
    // Only one page above the home: one pop goes back to it.
    Navigator.of(tester.element(find.text('Stock bas'))).pop();
    await tester.pumpAndSettle();
    expect(find.text('stock bas'), findsOneWidget);
  });
}
