import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/features/catalog/domain/model/product_model.dart';
import 'package:keevo/features/catalog/presentation/page/catalog_page.dart';
import 'package:keevo/features/catalog/presentation/provider/category_provider.dart';
import 'package:keevo/features/catalog/presentation/provider/product_provider.dart';
import 'package:keevo/features/inventory/presentation/page/local_low_stock_page.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../helpers/fake_entitlements.dart';

/// Builds CatalogPage wrapped with ProviderScope + GoRouter.
Widget _buildPage({
  List<ProductModel> activeProducts = const [],
  List<ProductModel> archivedProducts = const [],
  List<ProductModel> outOfStockProducts = const [],
  List<ProductModel> lowStockProducts = const [],
  String? currentUserRole,
  required SharedPreferences prefs,
}) {
  return ProviderScope(
    overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      currentUserRoleProvider.overrideWith((ref) => currentUserRole),
      productListProvider.overrideWith((ref) async => activeProducts),
      archivedProductListProvider.overrideWith((ref) async => archivedProducts),
      outOfStockProductListProvider
          .overrideWith((ref) async => outOfStockProducts),
      lowStockProductListProvider
          .overrideWith((ref) async => lowStockProducts),
    ],
    child: MaterialApp.router(
      routerConfig: GoRouter(
        initialLocation: '/catalog',
        routes: [
          GoRoute(
            path: '/catalog',
            builder: (_, __) => const CatalogPage(),
          ),
          GoRoute(
            path: '/products/new',
            builder: (_, __) => const Scaffold(
              body: Center(child: Text('ProductFormPage')),
            ),
          ),
        ],
      ),
    ),
  );
}

/// Like [_buildPage] but exposes the [ProviderContainer] backing the tree,
/// so tests can assert on provider state directly (debounce timing tests).
({Widget widget, ProviderContainer container}) _buildPageWithContainer({
  List<ProductModel> activeProducts = const [],
  String? currentUserRole,
  required SharedPreferences prefs,
}) {
  final container = ProviderContainer(overrides: [
    sharedPreferencesProvider.overrideWithValue(prefs),
    currentUserRoleProvider.overrideWith((ref) => currentUserRole),
    productListProvider.overrideWith((ref) async => activeProducts),
    archivedProductListProvider.overrideWith((ref) async => const []),
    outOfStockProductListProvider.overrideWith((ref) async => const []),
    lowStockProductListProvider.overrideWith((ref) async => const []),
  ]);
  final widget = UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      routerConfig: GoRouter(
        initialLocation: '/catalog',
        routes: [
          GoRoute(
            path: '/catalog',
            builder: (_, __) => const CatalogPage(),
          ),
          GoRoute(
            path: '/products/new',
            builder: (_, __) => const Scaffold(
              body: Center(child: Text('ProductFormPage')),
            ),
          ),
        ],
      ),
    ),
  );
  return (widget: widget, container: container);
}

Future<SharedPreferences> _setupPrefs({String? role}) async {
  SharedPreferences.setMockInitialValues({
    if (role != null) kUserRoleKey: role,
  });
  return SharedPreferences.getInstance();
}

void main() {
  group('CatalogPage — debounce (AC1)', () {
    testWidgets('debounces search input with 300ms delay', (tester) async {
      final prefs = await _setupPrefs();
      final built = _buildPageWithContainer(prefs: prefs);
      addTearDown(built.container.dispose);
      await tester.pumpWidget(built.widget);
      await tester.pumpAndSettle();

      final searchField = find.byType(TextField);
      expect(searchField, findsOneWidget);

      await tester.enterText(searchField, 'test');
      await tester.pump(const Duration(milliseconds: 100));
      expect(built.container.read(productSearchQueryProvider), '',
          reason: 'provider must not update before the 300ms debounce fires');

      await tester.pump(const Duration(milliseconds: 250));
      expect(built.container.read(productSearchQueryProvider), 'test',
          reason: 'provider must update once the 300ms debounce has fired');
    });

    testWidgets('debounce cancel/reset on rapid keystrokes', (tester) async {
      final prefs = await _setupPrefs();
      final built = _buildPageWithContainer(prefs: prefs);
      addTearDown(built.container.dispose);
      await tester.pumpWidget(built.widget);
      await tester.pumpAndSettle();

      final searchField = find.byType(TextField);
      await tester.enterText(searchField, 'a');
      await tester.pump(const Duration(milliseconds: 150));
      await tester.enterText(searchField, 'ab');
      await tester.pump(const Duration(milliseconds: 150));
      await tester.enterText(searchField, 'abc');
      expect(built.container.read(productSearchQueryProvider), '',
          reason: 'each keystroke must cancel and restart the timer');

      await tester.pump(const Duration(milliseconds: 100));
      expect(built.container.read(productSearchQueryProvider), '');

      await tester.pump(const Duration(milliseconds: 250));
      expect(built.container.read(productSearchQueryProvider), 'abc');
    });

    testWidgets('clearing restores the full list immediately', (tester) async {
      final prefs = await _setupPrefs();
      final built = _buildPageWithContainer(prefs: prefs);
      addTearDown(built.container.dispose);
      await tester.pumpWidget(built.widget);
      await tester.pumpAndSettle();

      final searchField = find.byType(TextField);
      await tester.enterText(searchField, 'something');
      await tester.pump(const Duration(milliseconds: 350));
      expect(built.container.read(productSearchQueryProvider), 'something');

      await tester.enterText(searchField, '');
      await tester.pump();
      expect(built.container.read(productSearchQueryProvider), '',
          reason: 'clearing must not wait for the debounce');
    });

    testWidgets('a search still active when the page is rebuilt is shown and clearable',
        (tester) async {
      final prefs = await _setupPrefs();
      final built = _buildPageWithContainer(prefs: prefs);
      addTearDown(built.container.dispose);
      // The user searched, left the catalogue and came back: the query is still set.
      built.container.read(productSearchQueryProvider.notifier).state = 'Bonbons';
      await tester.pumpWidget(built.widget);
      await tester.pumpAndSettle();

      expect(find.widgetWithText(TextField, 'Bonbons'), findsOneWidget,
          reason: 'the field must show the active filter');
      expect(find.byIcon(Icons.clear_rounded), findsOneWidget);

      await tester.tap(find.byIcon(Icons.clear_rounded));
      await tester.pump();
      expect(built.container.read(productSearchQueryProvider), '');
      expect(find.byIcon(Icons.clear_rounded), findsNothing);
    });

    testWidgets('clearing then leaving before the debounce still clears the filter',
        (tester) async {
      final prefs = await _setupPrefs();
      final built = _buildPageWithContainer(prefs: prefs);
      addTearDown(built.container.dispose);
      await tester.pumpWidget(built.widget);
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'Riz');
      await tester.pump(const Duration(milliseconds: 100));
      // Leave the page while the debounce is pending.
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
      expect(built.container.read(productSearchQueryProvider), 'Riz',
          reason: 'a pending query is applied, not lost, when the page goes away');
    });
  });

  group('CatalogPage — empty state on search (AC3)', () {
    testWidgets('shows "Aucun résultat" when search has no matches on Actifs tab',
        (tester) async {
      final prefs = await _setupPrefs(role: 'OWNER');
      await tester.pumpWidget(_buildPage(
        activeProducts: const [],
        currentUserRole: 'OWNER',
        prefs: prefs,
      ));
      await tester.pumpAndSettle();

      final searchField = find.byType(TextField);
      await tester.enterText(searchField, 'zxy_nonexistent');
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();

      expect(find.byIcon(Icons.search_off_rounded), findsOneWidget);
      expect(find.textContaining('Aucun résultat pour'), findsOneWidget);
    });

    testWidgets('shows "Créer un produit" button for OWNER on empty search',
        (tester) async {
      final prefs = await _setupPrefs(role: 'OWNER');
      await tester.pumpWidget(_buildPage(
        activeProducts: const [],
        currentUserRole: 'OWNER',
        prefs: prefs,
      ));
      await tester.pumpAndSettle();

      final searchField = find.byType(TextField);
      await tester.enterText(searchField, 'zxy_nonexistent');
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();

      expect(find.text('Créer un produit'), findsOneWidget);
    });

    // Employees may create products (FAB and empty-state button alike); only
    // the "..." menu (CSV import, categories) stays OWNER-only.
    testWidgets('shows "Créer un produit" button for EMPLOYEE on empty search',
        (tester) async {
      final prefs = await _setupPrefs(role: 'EMPLOYEE');
      await tester.pumpWidget(_buildPage(
        activeProducts: const [],
        currentUserRole: 'EMPLOYEE',
        prefs: prefs,
      ));
      await tester.pumpAndSettle();

      final searchField = find.byType(TextField);
      await tester.enterText(searchField, 'zxy_nonexistent');
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();

      expect(find.text('Créer un produit'), findsOneWidget);
    });

    testWidgets(
        'does not show the "tap + to add a product" hint on empty search',
        (tester) async {
      final prefs = await _setupPrefs(role: 'EMPLOYEE');
      await tester.pumpWidget(_buildPage(
        activeProducts: const [],
        currentUserRole: 'EMPLOYEE',
        prefs: prefs,
      ));
      await tester.pumpAndSettle();

      final searchField = find.byType(TextField);
      await tester.enterText(searchField, 'zxy_nonexistent');
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();

      expect(find.textContaining('Aucun résultat pour'), findsOneWidget);
      expect(
        find.text('Appuyez sur + pour ajouter votre premier produit'),
        findsNothing,
        reason: 'the search-empty state offers its own "Créer un produit" '
            'button; the first-product hint belongs to an empty catalogue',
      );
    });

    testWidgets('navigates to /products/new when tapping "Créer un produit"',
        (tester) async {
      final prefs = await _setupPrefs(role: 'OWNER');
      await tester.pumpWidget(_buildPage(
        activeProducts: const [],
        currentUserRole: 'OWNER',
        prefs: prefs,
      ));
      await tester.pumpAndSettle();

      final searchField = find.byType(TextField);
      await tester.enterText(searchField, 'zxy_nonexistent');
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Créer un produit'));
      await tester.pumpAndSettle();

      expect(find.text('ProductFormPage'), findsOneWidget);
    });

    testWidgets('shows generic empty state when no query on Actifs tab',
        (tester) async {
      final prefs = await _setupPrefs(role: 'OWNER');
      await tester.pumpWidget(_buildPage(
        activeProducts: const [],
        currentUserRole: 'OWNER',
        prefs: prefs,
      ));
      await tester.pumpAndSettle();

      expect(find.text('Aucun produit dans le catalogue'), findsOneWidget);
      expect(
        find.text('Appuyez sur + pour ajouter votre premier produit'),
        findsOneWidget,
      );
    });

    testWidgets(
        'archived tab still shows generic empty state when search active',
        (tester) async {
      final prefs = await _setupPrefs();
      await tester.pumpWidget(_buildPage(
        archivedProducts: const [],
        prefs: prefs,
      ));
      await tester.pumpAndSettle();

      final searchField = find.byType(TextField);
      await tester.enterText(searchField, 'zxy_nonexistent');
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Archivés'));
      await tester.pumpAndSettle();

      expect(find.text('Aucun produit archivé'), findsOneWidget);
    });

    testWidgets('out-of-stock tab still shows generic empty state',
        (tester) async {
      final prefs = await _setupPrefs();
      await tester.pumpWidget(_buildPage(
        outOfStockProducts: const [],
        prefs: prefs,
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Rupture'));
      await tester.pumpAndSettle();

      expect(find.text('Aucun produit en rupture de stock'), findsOneWidget);
    });

    testWidgets('low-stock tab still shows generic empty state',
        (tester) async {
      final prefs = await _setupPrefs();
      await tester.pumpWidget(_buildPage(
        lowStockProducts: const [],
        prefs: prefs,
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Stock Bas'));
      await tester.pumpAndSettle();

      expect(find.text('Aucun produit en stock bas'), findsOneWidget);
    });
  });

  testWidgets('category sheet shows a single drag handle', (tester) async {
    final prefs = await _setupPrefs(role: 'OWNER');
    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        currentUserRoleProvider.overrideWith((ref) => 'OWNER'),
        productListProvider.overrideWith((ref) async => const []),
        archivedProductListProvider.overrideWith((ref) async => const []),
        outOfStockProductListProvider.overrideWith((ref) async => const []),
        lowStockProductListProvider.overrideWith((ref) async => const []),
        categoriesProvider.overrideWith((ref) async => const []),
      ],
      child: MaterialApp.router(
        // Same sheet theme as the app: the theme draws a handle by default.
        theme: ThemeData(
          bottomSheetTheme:
              const BottomSheetThemeData(showDragHandle: true),
        ),
        routerConfig: GoRouter(
          initialLocation: '/catalog',
          routes: [
            GoRoute(path: '/catalog', builder: (_, __) => const CatalogPage()),
          ],
        ),
      ),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.more_vert));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Gérer les catégories'));
    await tester.pumpAndSettle();

    expect(
      find.byWidgetPredicate(
          (w) => w.runtimeType.toString() == '_DragHandle'),
      findsNothing,
      reason: 'the sheet draws its own handle',
    );
  });

  // v1s-16-11 follow-up: the retry of a tab invalidates that tab's provider.
  testWidgets('low-stock tab error → Réessayer reloads that tab only',
      (tester) async {
    final prefs = await _setupPrefs();
    var lowStockCalls = 0;
    var activeCalls = 0;
    var failing = true;
    final now = DateTime(2026);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        currentUserRoleProvider.overrideWith((ref) => 'OWNER'),
        productListProvider.overrideWith((ref) async {
          activeCalls++;
          return const [];
        }),
        archivedProductListProvider.overrideWith((ref) async => const []),
        outOfStockProductListProvider.overrideWith((ref) async => const []),
        lowStockProductListProvider.overrideWith((ref) async {
          lowStockCalls++;
          if (failing) throw StateError('database is locked');
          return [
            ProductModel(
                id: 'p1', name: 'Riz parfumé', createdAt: now, updatedAt: now),
          ];
        }),
      ],
      child: MaterialApp.router(
        routerConfig: GoRouter(
          initialLocation: '/catalog',
          routes: [
            GoRoute(path: '/catalog', builder: (_, __) => const CatalogPage()),
          ],
        ),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Stock Bas'));
    await tester.pumpAndSettle();

    expect(find.text('Réessayer'), findsOneWidget);
    expect(find.textContaining('database'), findsNothing);
    final activeBefore = activeCalls;

    failing = false;
    await tester.tap(find.text('Réessayer'));
    await tester.pumpAndSettle();

    expect(find.text('Réessayer'), findsNothing);
    expect(find.text('Riz parfumé'), findsOneWidget);
    expect(lowStockCalls, 2);
    expect(activeCalls, activeBefore);
  });

  // 13.10b — the real catalogue « Vue stock multi-boutiques » button: Free /
  // employee → local list of the active store, Premium → overview.
  group('CatalogPage — stock view button', () {
    Future<String> tapButton(WidgetTester tester, Map<String, String> modules,
        {String role = 'OWNER'}) async {
      final prefs = await _setupPrefs(role: role);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          sharedPreferencesProvider.overrideWithValue(prefs),
          productListProvider.overrideWith((ref) async => const []),
          archivedProductListProvider.overrideWith((ref) async => const []),
          outOfStockProductListProvider.overrideWith((ref) async => const []),
          lowStockProductListProvider.overrideWith((ref) async => const []),
          entitlementsOverride(modules),
        ],
        child: MaterialApp.router(
          routerConfig: GoRouter(
            initialLocation: '/catalog',
            routes: [
              GoRoute(path: '/catalog', builder: (_, __) => const CatalogPage()),
              GoRoute(
                  path: '/stock/overview',
                  builder: (_, s) => Scaffold(body: Text('OVERVIEW ${s.uri}'))),
              GoRoute(
                  path: kLocalLowStockRoute,
                  builder: (_, s) => Scaffold(body: Text('LOCAL ${s.uri}'))),
            ],
          ),
        ),
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Vue stock multi-boutiques'));
      await tester.pumpAndSettle();
      final shown = find.textContaining(RegExp('^(LOCAL|OVERVIEW) '));
      return tester.widget<Text>(shown).data!;
    }

    testWidgets('Free → local list of the active store', (tester) async {
      expect(
          await tapButton(tester,
              const {'multi-store': 'DISABLED'}),
          'LOCAL /stock/low');
    });

    testWidgets('employee → local list', (tester) async {
      expect(await tapButton(tester, const <String, String>{}, role: 'EMPLOYEE'),
          'LOCAL /stock/low');
    });

    testWidgets('Premium → whole multi-store overview', (tester) async {
      expect(
          await tapButton(tester,
              const {'multi-store': 'ENABLED'}),
          'OVERVIEW /stock/overview');
    });
  });
}
