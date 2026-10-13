import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/features/pos/presentation/page/pos_page.dart';
import 'package:keevo/features/pos/presentation/provider/pos_providers.dart';
import 'package:keevo/features/pos/presentation/provider/pos_search_provider.dart';

import 'package:shared_preferences/shared_preferences.dart';

class _FakeFrequentProductsNotifier extends FrequentProductsNotifier {
  final List<PosProductResult> _products;
  _FakeFrequentProductsNotifier([this._products = const []]);

  @override
  Future<FrequentProductsState> build(
      ({String? storeId, String? categoryId}) arg) async {
    return FrequentProductsState(products: _products, hasMore: false);
  }
}

class _CountingFrequentProductsNotifier extends _FakeFrequentProductsNotifier {
  static int loadMoreCalls = 0;
  _CountingFrequentProductsNotifier(super.products);

  @override
  Future<void> loadMore() async => loadMoreCalls++;
}

/// Fails the first search, succeeds afterwards; records every call.
class _FlakySearchNotifier extends PosSearchNotifier {
  static final calls = <(String, String?)>[];
  String _q = '';

  @override
  String get lastQuery => _q;

  @override
  Future<void> search(String query, String? storeId) async {
    _q = query;
    calls.add((query, storeId));
    if (calls.length == 1) {
      state = AsyncError(StateError('db locked'), StackTrace.empty);
      return;
    }
    state = const AsyncData([
      PosProductResult(id: 'p1', name: 'Savon de Marseille', price: 500, stock: 3),
    ]);
  }
}

GoRouter _testRouter(Widget page) => GoRouter(
      routes: [GoRoute(path: '/', builder: (_, __) => page)],
    );

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  late SharedPreferences prefs;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
  });

  Widget buildApp({List<Override> overrides = const []}) {
    return ProviderScope(
      overrides: [
        syncStatusProvider
            .overrideWith((ref) => Stream.value(SyncStatus.online)),
        daysOfflineProvider.overrideWithValue(0),
        sharedPreferencesProvider.overrideWithValue(prefs),
        currentUserRoleProvider.overrideWithValue('OWNER'),
        frequentProductsProvider
            .overrideWith(() => _FakeFrequentProductsNotifier()),
        ...overrides,
      ],
      child: MaterialApp.router(routerConfig: _testRouter(const PosPage())),
    );
  }

  group('PosPage', () {
    testWidgets('displays search bar', (tester) async {
      await tester.pumpWidget(buildApp());
      await tester.pump();
      expect(find.byType(TextField), findsOneWidget);
      expect(find.text('Chercher un produit'), findsOneWidget);
    });

    testWidgets('displays frequent products grid (empty state)',
        (tester) async {
      await tester.pumpWidget(buildApp());
      await tester.pumpAndSettle();
      // With empty products, the empty state shows
      expect(find.text('Votre boutique est prête !'), findsOneWidget);
    });

    testWidgets('cartPill hidden initially (empty cart)', (tester) async {
      await tester.pumpWidget(buildApp());
      await tester.pump();
      // CartPill with 0 items should be hidden (AnimatedSwitcher → SizedBox.shrink)
      // The empty state may show an "Ajouter un produit" button, so check for "Encaisser" text
      expect(find.textContaining('Encaisser'), findsNothing);
    });

    testWidgets('cartPill visible after adding product', (tester) async {
      await tester.pumpWidget(buildApp(
        overrides: [
          frequentProductsProvider.overrideWith(
            () => _FakeFrequentProductsNotifier([
              const PosProductResult(
                id: 'p1',
                name: 'Savon',
                price: 500,
                stock: 10,
              ),
            ]),
          ),
        ],
      ));
      await tester.pumpAndSettle();

      // Tap the product card to add to cart
      await tester.tap(find.text('Savon'));
      await tester.pumpAndSettle();

      // CartPill should now show the "Encaisser" button
      expect(find.textContaining('ENCAISSER'), findsOneWidget);
    });

    testWidgets('haptic feedback on product tap', (tester) async {
      final log = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
        SystemChannels.platform,
        (MethodCall methodCall) async {
          log.add(methodCall);
          return null;
        },
      );

      await tester.pumpWidget(buildApp(
        overrides: [
          frequentProductsProvider.overrideWith(
            () => _FakeFrequentProductsNotifier([
              const PosProductResult(
                id: 'p1',
                name: 'Savon',
                price: 500,
                stock: 10,
              ),
            ]),
          ),
        ],
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Savon'));
      await tester.pump();

      expect(
        log.any((c) =>
            c.method == 'HapticFeedback.vibrate'),
        isTrue,
      );

      // Clear the 150ms flash timer from ProductCard
      await tester.pump(const Duration(milliseconds: 200));
    });
    testWidgets('all-stores mode still loads the next product page',
        (tester) async {
      _CountingFrequentProductsNotifier.loadMoreCalls = 0;
      await tester.pumpWidget(buildApp(
        overrides: [
          frequentProductsProvider.overrideWith(
            () => _CountingFrequentProductsNotifier([
              for (var i = 0; i < 60; i++)
                PosProductResult(
                    id: 'p$i', name: 'Produit $i', price: 500, stock: 10),
            ]),
          ),
        ],
      ));
      await tester.pumpAndSettle();

      await tester.drag(
          find.byType(CustomScrollView).first, const Offset(0, -20000));
      await tester.pumpAndSettle();

      expect(_CountingFrequentProductsNotifier.loadMoreCalls, greaterThan(0));
    });

    testWidgets(
        'search error → Réessayer re-runs the same query and shows results '
        '(v1s-16-11 follow-up)', (tester) async {
      _FlakySearchNotifier.calls.clear();
      await tester.pumpWidget(buildApp(
        overrides: [posSearchProvider.overrideWith(_FlakySearchNotifier.new)],
      ));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'savon');
      await tester.pumpAndSettle();

      expect(find.text('Réessayer'), findsOneWidget);
      expect(find.textContaining('db locked'), findsNothing);

      await tester.tap(find.text('Réessayer'));
      await tester.pumpAndSettle();

      expect(find.text('Réessayer'), findsNothing);
      expect(find.text('Savon de Marseille'), findsOneWidget);
      expect(_FlakySearchNotifier.calls, hasLength(2));
      expect(_FlakySearchNotifier.calls[1].$1, 'savon');
      expect(_FlakySearchNotifier.calls[1].$2, _FlakySearchNotifier.calls[0].$2);
      // The query survives the retry (field not cleared).
      expect(find.widgetWithText(TextField, 'savon'), findsOneWidget);
    });
  });
}
