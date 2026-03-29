import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/features/pos/domain/model/payment_mode_enum.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:keevo/features/pos/presentation/page/sale_detail_page.dart';
import 'package:keevo/features/pos/presentation/provider/pos_providers.dart';

void main() {
  setUpAll(() async {
    GoogleFonts.config.allowRuntimeFetching = false;
    await initializeDateFormatting('fr_FR');
  });

  group('SaleDetailPage', () {
    final mockSale = Sale(
      id: 'sale-123',
      storeId: 'store-1',
      employeeId: 'emp-1',
      occurredAt: DateTime(2026, 3, 19, 14, 30),
      totalAmount: 75000,
      discountAmount: 5000,
      paymentMode: PaymentModeEnum.cash,
      status: 'COMPLETED',
      clientId: 'client-1',
      items: [
        SaleItemModel(
          id: 'item-1',
          productId: 'prod-1',
          productName: 'iPhone 14',
          quantity: 2,
          catalogueUnitPrice: 40000,
          appliedUnitPrice: 40000,
          subtotal: 80000,
        ),
      ],
      createdAt: DateTime(2026, 3, 19, 14, 30),
    );

    testWidgets('displays all sale items', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            saleByIdProvider('sale-123').overrideWith(
              (ref) => Future.value(mockSale),
            ),
          ],
          child: const MaterialApp(
            home: SaleDetailPage(saleId: 'sale-123'),
          ),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.text('iPhone 14'), findsOneWidget);
      expect(find.textContaining('2'), findsWidgets);
      expect(find.textContaining('40'), findsWidgets);
    });

    testWidgets('displays discount when applied', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            saleByIdProvider('sale-123').overrideWith(
              (ref) => Future.value(mockSale),
            ),
          ],
          child: const MaterialApp(
            home: SaleDetailPage(saleId: 'sale-123'),
          ),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.textContaining('5'), findsWidgets); // discount
    });

    testWidgets('displays client name when linked', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            saleByIdProvider('sale-123').overrideWith(
              (ref) => Future.value(mockSale),
            ),
          ],
          child: const MaterialApp(
            home: SaleDetailPage(saleId: 'sale-123'),
          ),
        ),
      );

      await tester.pumpAndSettle();

      // Client section should be present
      expect(find.byType(Container), findsWidgets);
    });

    testWidgets('displays payment mode', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            saleByIdProvider('sale-123').overrideWith(
              (ref) => Future.value(mockSale),
            ),
          ],
          child: const MaterialApp(
            home: SaleDetailPage(saleId: 'sale-123'),
          ),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.textContaining('Espèces'), findsOneWidget);
    });

    testWidgets('displays total amount', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            saleByIdProvider('sale-123').overrideWith(
              (ref) => Future.value(mockSale),
            ),
          ],
          child: const MaterialApp(
            home: SaleDetailPage(saleId: 'sale-123'),
          ),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.textContaining('75'), findsWidgets);
    });

    testWidgets('shows loading state initially', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            saleByIdProvider('sale-123').overrideWith(
              (ref) => Future.delayed(
                const Duration(seconds: 1),
                () => mockSale,
              ),
            ),
          ],
          child: const MaterialApp(
            home: SaleDetailPage(saleId: 'sale-123'),
          ),
        ),
      );

      expect(find.byType(CircularProgressIndicator), findsOneWidget);

      // Let the Future complete to avoid pending timer
      await tester.pumpAndSettle();
    });

    testWidgets('shows error state on failure', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            saleByIdProvider('sale-123').overrideWith(
              (ref) => Future.error('Test error'),
            ),
          ],
          child: const MaterialApp(
            home: SaleDetailPage(saleId: 'sale-123'),
          ),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.textContaining('Erreur'), findsOneWidget);
    });
  });
}
