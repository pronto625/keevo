import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/catalog/domain/model/category_model.dart';
import 'package:keevo/features/catalog/presentation/provider/category_provider.dart';
import 'package:keevo/features/inventory/data/datasource/remote_quick_add_datasource.dart';
import 'package:keevo/features/inventory/domain/model/inventory_product_row_model.dart';
import 'package:keevo/features/inventory/domain/repository/inventory_count_repository.dart';
import 'package:keevo/features/inventory/presentation/page/inventory_counting_page.dart';
import 'package:keevo/features/inventory/presentation/provider/inventory_counting_provider.dart';
import 'package:keevo/features/inventory/presentation/provider/quick_add_product_provider.dart';

class _MockCountRepo extends Mock implements InventoryCountRepository {}

class _MockRemoteDatasource extends Mock implements RemoteQuickAddDatasource {}

final _testProducts = [
  const InventoryProductRowModel(
    productId: 'prod-001',
    productName: 'Chaussures Nike',
    sku: 'SKU-001',
    photoUrl: null,
    variantId: null,
    variantLabel: null,
    theoreticalQty: 10,
    physicalQty: null,
    ecart: null,
  ),
];

final _categories = [
  CategoryModel(
    id: 'cat-001',
    name: 'Vêtements',
    parentId: null,
    isActive: true,
    isCustom: false,
    createdAt: DateTime(2026, 1, 1),
    updatedAt: DateTime(2026, 1, 1),
  ),
];

void main() {
  setUpAll(() {
    GoogleFonts.config.allowRuntimeFetching = false;
  });

  group('InventoryCountingPage quick-add (Story 6.2.a — Task 21)', () {
    late _MockCountRepo mockCountRepo;
    late _MockRemoteDatasource mockDatasource;

    setUp(() {
      mockCountRepo = _MockCountRepo();
      mockDatasource = _MockRemoteDatasource();
      when(() => mockCountRepo.getCountingProducts(any()))
          .thenAnswer((_) async => _testProducts);
    });

    Widget buildPage() => ProviderScope(
          overrides: [
            inventoryCountRepositoryProvider
                .overrideWithValue(mockCountRepo),
            categoriesProvider.overrideWith((_) async => _categories),
            remoteQuickAddDatasourceProvider
                .overrideWithValue(mockDatasource),
          ],
          child: const MaterialApp(
            home: InventoryCountingPage(sessionId: 'session-001'),
          ),
        );

    testWidgets('"+" button is visible in bottom action bar', (tester) async {
      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      // The "+" quick-add button should be present with add icon
      expect(find.byIcon(Icons.add), findsOneWidget);
      expect(find.byTooltip('Ajouter un produit'), findsOneWidget);
    });

    testWidgets('tapping "+" opens QuickAddProductSheet', (tester) async {
      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      await tester.tap(find.byIcon(Icons.add));
      await tester.pumpAndSettle();

      // Bottom sheet should show the quick-add form
      expect(find.text('Nouveau produit'), findsOneWidget);
      expect(find.text('Nom du produit'), findsOneWidget);
      expect(find.text('Créer le produit'), findsOneWidget);
    });

    testWidgets('successful quick-add shows success SnackBar', (tester) async {
      final fakeResult = QuickAddResult(
        productId: 'prod-new',
        productName: 'Robe Wax L',
        sku: 'KEV-ABC123',
        categoryId: 'cat-001',
        physicalQty: 3,
        inventoryCountId: 'cnt-001',
        stockLevelId: 'stk-001',
      );

      when(() => mockDatasource.quickAdd(
            sessionId: any(named: 'sessionId'),
            name: any(named: 'name'),
            categoryId: any(named: 'categoryId'),
            physicalQty: any(named: 'physicalQty'),
          )).thenAnswer((_) async => fakeResult);

      // After quick-add, the product list reloads with the new product
      var callCount = 0;
      when(() => mockCountRepo.getCountingProducts(any()))
          .thenAnswer((_) async {
        callCount++;
        if (callCount > 1) {
          // Second call returns updated list with new product
          return [
            ..._testProducts,
            const InventoryProductRowModel(
              productId: 'prod-new',
              productName: 'Robe Wax L',
              sku: 'KEV-ABC123',
              photoUrl: null,
              variantId: null,
              variantLabel: null,
              theoreticalQty: 0,
              physicalQty: 3,
              ecart: 3,
            ),
          ];
        }
        return _testProducts;
      });

      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      // Open quick-add sheet
      await tester.tap(find.byIcon(Icons.add));
      await tester.pumpAndSettle();

      // Fill form
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Nom du produit'), 'Robe Wax L');
      await tester.pump();

      await tester.tap(find.byType(DropdownButtonFormField<String>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Vêtements').last);
      await tester.pumpAndSettle();

      await tester.enterText(
          find.widgetWithText(TextFormField, 'Quantité physique'), '3');
      await tester.pump();

      // Submit
      await tester.tap(find.text('Créer le produit'));
      await tester.pumpAndSettle();

      // Success SnackBar
      expect(find.textContaining('Robe Wax L'), findsWidgets);
    });
  });
}
