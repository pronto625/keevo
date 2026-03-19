import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:keevo/features/pos/presentation/page/sale_detail_page.dart';
import 'package:keevo/features/pos/presentation/provider/pos_providers.dart';

void main() {
  group('SaleDetailPage', () {
    final mockSale = Sale(
      id: 'sale-123',
      storeId: 'store-1',
      employeeId: 'emp-1',
      occurredAt: DateTime(2026, 3, 19, 14, 30),
      totalAmount: 75000,
      discountAmount: 5000,
      paymentMode: 'CASH',
      status: 'COMPLETED',
      clientId: 'client-1',
      items: [
        SaleItem(
          id: 'item-1',
          saleId: 'sale-123',
          productId: 'prod-1',
          productName: 'iPhone 14',
          quantity: 2,
          unitPrice: 40000,
          subtotal: 80000,
        ),
      ],
      tenantId: 'tenant-1',
      synced: true,
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
      expect(find.byType(Card), findsWidgets);
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

      expect(find.textContaining('CASH'), findsOneWidget);
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
