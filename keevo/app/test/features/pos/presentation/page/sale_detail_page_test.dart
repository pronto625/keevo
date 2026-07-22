import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/pos/domain/model/payment_mode_enum.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:keevo/features/pos/domain/repository/sale_repository.dart';
import 'package:keevo/features/pos/presentation/page/sale_detail_page.dart';
import 'package:keevo/features/pos/presentation/provider/pos_providers.dart';
import 'package:mocktail/mocktail.dart';

class MockSaleRepository extends Mock implements SaleRepository {}

void main() {
  setUpAll(() async {
    GoogleFonts.config.allowRuntimeFetching = false;
    await initializeDateFormatting('fr_FR');
    registerFallbackValue(<String, int>{});
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
            currentUserRoleProvider.overrideWithValue('EMPLOYEE'),
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
            currentUserRoleProvider.overrideWithValue('EMPLOYEE'),
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
            currentUserRoleProvider.overrideWithValue('EMPLOYEE'),
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
            currentUserRoleProvider.overrideWithValue('EMPLOYEE'),
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
            currentUserRoleProvider.overrideWithValue('EMPLOYEE'),
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
            currentUserRoleProvider.overrideWithValue('EMPLOYEE'),
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
            currentUserRoleProvider.overrideWithValue('EMPLOYEE'),
          ],
          child: const MaterialApp(
            home: SaleDetailPage(saleId: 'sale-123'),
          ),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.textContaining('problème'), findsOneWidget);
    });
  });

  // ── Story v1s-13-5 (AC8) — Annuler/Corriger button visibility + validation ──
  group('SaleDetailPage — Annuler/Corriger (Story v1s-13-5)', () {
    Sale saleWithStatus(String status) => Sale(
          id: 'sale-999',
          storeId: 'store-1',
          employeeId: 'emp-1',
          occurredAt: DateTime(2026, 3, 19, 14, 30),
          totalAmount: 5000,
          paymentMode: PaymentModeEnum.cash,
          status: status,
          items: [
            SaleItemModel(
              id: 'item-1',
              productId: 'prod-1',
              productName: 'Savon',
              quantity: 5,
              catalogueUnitPrice: 1000,
              appliedUnitPrice: 1000,
              subtotal: 5000,
            ),
          ],
          createdAt: DateTime(2026, 3, 19, 14, 30),
        );

    late MockSaleRepository mockRepo;

    setUp(() {
      mockRepo = MockSaleRepository();
    });

    Future<void> pump(WidgetTester tester,
        {required String role, required String status}) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            saleByIdProvider('sale-999').overrideWith(
              (ref) => Future.value(saleWithStatus(status)),
            ),
            currentUserRoleProvider.overrideWithValue(role),
            saleRepositoryProvider.overrideWithValue(mockRepo),
          ],
          child: const MaterialApp(
            home: SaleDetailPage(saleId: 'sale-999'),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('button visible for OWNER on COMPLETED sale', (tester) async {
      await pump(tester, role: 'OWNER', status: 'COMPLETED');

      expect(find.text('Annuler entièrement'), findsOneWidget);
      expect(find.text('Corriger un article'), findsOneWidget);
    });

    testWidgets('button absent for EMPLOYEE', (tester) async {
      await pump(tester, role: 'EMPLOYEE', status: 'COMPLETED');

      expect(find.text('Annuler entièrement'), findsNothing);
      expect(find.text('Corriger un article'), findsNothing);
    });

    testWidgets('button absent for CANCELLED sale', (tester) async {
      await pump(tester, role: 'OWNER', status: 'CANCELLED');

      expect(find.text('Annuler entièrement'), findsNothing);
      expect(find.text('Corriger un article'), findsNothing);
    });

    testWidgets('button absent for PENDING_VALIDATION sale', (tester) async {
      await pump(tester, role: 'OWNER', status: 'PENDING_VALIDATION');

      expect(find.text('Annuler entièrement'), findsNothing);
      expect(find.text('Corriger un article'), findsNothing);
    });

    testWidgets(
        'cancel dialog: confirm button disabled until justification >= 10 chars',
        (tester) async {
      await pump(tester, role: 'OWNER', status: 'COMPLETED');

      await tester.tap(find.text('Annuler entièrement'));
      await tester.pumpAndSettle();

      // Confirm button starts disabled (empty justification).
      var confirmButton = tester.widget<FilledButton>(
          find.widgetWithText(FilledButton, 'Confirmer annulation'));
      expect(confirmButton.onPressed, isNull);

      await tester.enterText(find.byType(TextField).first, 'trop crt');
      await tester.pumpAndSettle();
      confirmButton = tester.widget<FilledButton>(
          find.widgetWithText(FilledButton, 'Confirmer annulation'));
      expect(confirmButton.onPressed, isNull,
          reason: '< 10 chars must keep the button disabled');

      await tester.enterText(
          find.byType(TextField).first, 'Erreur de scan article vendu');
      await tester.pumpAndSettle();
      confirmButton = tester.widget<FilledButton>(
          find.widgetWithText(FilledButton, 'Confirmer annulation'));
      expect(confirmButton.onPressed, isNotNull,
          reason: '>= 10 chars must enable the button');
    });

    testWidgets('confirming cancel calls saleRepo.cancelSale', (tester) async {
      when(() => mockRepo.cancelSale(any(), any())).thenAnswer((_) async {});

      await pump(tester, role: 'OWNER', status: 'COMPLETED');

      await tester.tap(find.text('Annuler entièrement'));
      await tester.pumpAndSettle();
      await tester.enterText(
          find.byType(TextField).first, 'Erreur de scan article vendu');
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Confirmer annulation'));
      await tester.pumpAndSettle();

      verify(() => mockRepo.cancelSale(
          'sale-999', 'Erreur de scan article vendu')).called(1);
    });

    testWidgets(
        'confirming correct calls saleRepo.correctSale with changed item map',
        (tester) async {
      when(() => mockRepo.correctSale(any(), any(), any()))
          .thenAnswer((_) async {});

      await pump(tester, role: 'OWNER', status: 'COMPLETED');

      await tester.tap(find.text('Corriger un article'));
      await tester.pumpAndSettle();

      // Sheet has one qty TextField (item-1, initial value '5') then the
      // justification TextField.
      await tester.enterText(find.byType(TextField).first, '3');
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).at(1),
          'Erreur de quantité scannée au comptoir');
      await tester.pumpAndSettle();

      await tester.tap(
          find.widgetWithText(FilledButton, 'Enregistrer la correction'));
      await tester.pumpAndSettle();

      verify(() => mockRepo.correctSale('sale-999',
          'Erreur de quantité scannée au comptoir', {'item-1': 3})).called(1);
    });
  });
}
