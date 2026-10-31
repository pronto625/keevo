import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/features/catalog/domain/model/product_model.dart';
import 'package:keevo/features/catalog/domain/model/product_status.dart';
import 'package:keevo/features/catalog/domain/repository/product_repository.dart';
import 'package:keevo/features/catalog/presentation/provider/product_provider.dart';
import 'package:keevo/features/pos/domain/model/payment_mode_enum.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:keevo/features/pos/domain/repository/sale_repository.dart';
import 'package:keevo/features/pos/presentation/page/pending_sale_detail_page.dart';
import 'package:keevo/features/pos/presentation/provider/pos_providers.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

class MockSaleRepository extends Mock implements SaleRepository {}

class MockProductRepository extends Mock implements ProductRepository {}

SaleItemModel _item(String id) => SaleItemModel(
      id: 'item-$id',
      productId: id,
      productName: 'Produit $id',
      catalogueUnitPrice: 1000,
      appliedUnitPrice: 1000,
      quantity: 1,
      subtotal: 1000,
    );

Sale _sale(List<String> productIds) => Sale(
      id: 'sale-1',
      storeId: 'store-1',
      employeeId: 'emp-1',
      paymentMode: PaymentModeEnum.cash,
      totalAmount: 1000 * productIds.length,
      status: 'PENDING_VALIDATION',
      items: productIds.map(_item).toList(),
      occurredAt: DateTime(2026, 3, 19, 10),
      createdAt: DateTime(2026, 3, 19, 10),
    );

ProductModel _product(String id, ProductStatus status) => ProductModel(
      id: id,
      name: 'Produit $id',
      status: status,
      createdAt: DateTime(2026),
      updatedAt: DateTime(2026),
    );

void main() {
  late MockSaleRepository saleRepo;
  late MockProductRepository productRepo;
  late SharedPreferences prefs;

  setUpAll(() async {
    GoogleFonts.config.allowRuntimeFetching = false;
    await initializeDateFormatting('fr_FR');
  });

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    saleRepo = MockSaleRepository();
    productRepo = MockProductRepository();
    when(() => saleRepo.validateSale(any(), any(),
            productIdRemappings: any(named: 'productIdRemappings'),
            initialStockEntries: any(named: 'initialStockEntries')))
        .thenAnswer((_) async {});
  });

  Future<void> pump(WidgetTester tester,
      {required Sale sale, String role = 'OWNER', bool byId = false}) async {
    final router = GoRouter(routes: [
      GoRoute(
        path: '/',
        builder: (_, __) => const Scaffold(body: Text('home')),
        routes: [
          GoRoute(
            path: 'detail',
            builder: (_, __) => PendingSaleDetailPage(
                saleId: sale.id, sale: byId ? null : sale),
          ),
        ],
      ),
    ]);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        currentUserRoleProvider.overrideWithValue(role),
        saleRepositoryProvider.overrideWithValue(saleRepo),
        productRepositoryProvider.overrideWithValue(productRepo),
        saleByIdProvider(sale.id).overrideWith((ref) async => sale),
      ],
      child: MaterialApp.router(routerConfig: router),
    ));
    router.push('/detail');
    await tester.pumpAndSettle();
  }

  // The page shows a spinner while validating, so pumpAndSettle never settles.
  Future<void> settle(WidgetTester tester) async {
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
  }

  Future<void> tapValidate(WidgetTester tester) async {
    await tester.tap(find.text('Valider la vente'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Confirmer'));
    await settle(tester);
  }

  testWidgets('opened by id: validation works (no forced unwrap of sale)',
      (tester) async {
    when(() => productRepo.getById(any()))
        .thenAnswer((_) async => _product('p1', ProductStatus.active));
    await pump(tester, sale: _sale(['p1']), byId: true);

    await tapValidate(tester);

    verify(() => saleRepo.validateSale('sale-1', any(),
        productIdRemappings: any(named: 'productIdRemappings'),
        initialStockEntries: any(named: 'initialStockEntries'))).called(1);
  });

  testWidgets(
      'two drafts, second promotion dialog cancelled: nothing is promoted',
      (tester) async {
    when(() => productRepo.getById(any())).thenAnswer((inv) async =>
        _product(inv.positionalArguments.first as String, ProductStatus.draft));
    when(() => productRepo.promoteToActive(any()))
        .thenAnswer((inv) async => inv.positionalArguments.first as String);
    await pump(tester, sale: _sale(['d1', 'd2']));

    await tapValidate(tester);
    // First draft: confirm.
    await tester.tap(find.text('Valider le produit'));
    await settle(tester);
    // Second draft: cancel.
    await tester.tap(find.widgetWithText(TextButton, 'Annuler'));
    await settle(tester);

    verifyNever(() => productRepo.promoteToActive(any()));
    verifyNever(() => saleRepo.validateSale(any(), any(),
        productIdRemappings: any(named: 'productIdRemappings'),
        initialStockEntries: any(named: 'initialStockEntries')));
  });

  testWidgets('employee sees no Annuler / Valider actions', (tester) async {
    await pump(tester, sale: _sale(['p1']), role: 'EMPLOYEE');

    expect(find.text('Valider la vente'), findsNothing);
    expect(find.text('Annuler'), findsNothing);
  });
}
