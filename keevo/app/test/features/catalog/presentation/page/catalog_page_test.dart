import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/catalog/domain/model/product_model.dart';
import 'package:keevo/features/catalog/presentation/page/catalog_page.dart';
import 'package:keevo/features/catalog/presentation/provider/product_provider.dart';

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

    testWidgets('clear goes through the same debounce as typing', (tester) async {
      final prefs = await _setupPrefs();
      final built = _buildPageWithContainer(prefs: prefs);
      addTearDown(built.container.dispose);
      await tester.pumpWidget(built.widget);
      await tester.pumpAndSettle();

      final searchField = find.byType(TextField);
      await tester.enterText(searchField, 'something');
      await tester.pump(const Duration(milliseconds: 350));
      expect(built.container.read(productSearchQueryProvider), 'something');

      // Mirrors the clear button's onPressed (_searchController.clear() +
      // _onSearchChanged('')) without depending on suffixIcon visibility.
      await tester.enterText(searchField, '');
      await tester.pump(const Duration(milliseconds: 100));
      expect(built.container.read(productSearchQueryProvider), 'something',
          reason: 'clear must not update the provider immediately');

      await tester.pump(const Duration(milliseconds: 250));
      expect(built.container.read(productSearchQueryProvider), '');
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

    testWidgets('hides "Créer un produit" button for EMPLOYEE on empty search',
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

      expect(find.text('Créer un produit'), findsNothing);
    });

    testWidgets(
        'does not show the "tap + to add a product" hint for EMPLOYEE on empty search',
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
        reason:
            'EMPLOYEE has no FAB access — the search-empty state must not '
            'point at a create affordance the user cannot use',
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
}
