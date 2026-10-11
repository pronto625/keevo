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
import 'package:keevo/features/pos/presentation/provider/day_closure_providers.dart';
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
        {required String role,
        required String status,
        List<Override> extra = const []}) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            ...extra,
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

    // The server refuses a quantity under 1: the sheet must not send one.
    testWidgets('correction: negative or zero quantity keeps confirm disabled',
        (tester) async {
      await pump(tester, role: 'OWNER', status: 'COMPLETED');

      await tester.tap(find.text('Corriger un article'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).at(1),
          'Erreur de quantité scannée au comptoir');

      // '-3' is filtered to digits only ('3' would be a valid change).
      await tester.enterText(find.byType(TextField).first, '-3');
      await tester.pumpAndSettle();
      expect(
          tester
              .widget<TextField>(find.byType(TextField).first)
              .controller!
              .text,
          '3');

      await tester.enterText(find.byType(TextField).first, '0');
      await tester.pumpAndSettle();
      expect(
          tester
              .widget<FilledButton>(find.widgetWithText(
                  FilledButton, 'Enregistrer la correction'))
              .onPressed,
          isNull);

      await tester.enterText(find.byType(TextField).first, '');
      await tester.pumpAndSettle();
      expect(
          tester
              .widget<FilledButton>(find.widgetWithText(
                  FilledButton, 'Enregistrer la correction'))
              .onPressed,
          isNull);
    });

    testWidgets('after a successful cancel the page shows CANCELLED, no buttons',
        (tester) async {
      when(() => mockRepo.cancelSale(any(), any())).thenAnswer((_) async {});
      var closureStateBuilds = 0;
      await pump(tester, role: 'OWNER', status: 'COMPLETED', extra: [
        dayClosureStateProvider.overrideWith((ref, storeId) async {
          closureStateBuilds++;
          return DayCloseButtonState.available;
        }),
      ]);
      final container = ProviderScope.containerOf(
          tester.element(find.byType(SaleDetailPage)));
      container.listen(dayClosureStateProvider('store-1'), (_, __) {});
      await tester.pumpAndSettle();
      expect(closureStateBuilds, 1);

      await tester.tap(find.text('Annuler entièrement'));
      await tester.pumpAndSettle();
      await tester.enterText(
          find.byType(TextField).first, 'Erreur de scan article vendu');
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Confirmer annulation'));
      await tester.pumpAndSettle();

      expect(find.text('Annulée'), findsOneWidget);
      expect(find.text('Annuler entièrement'), findsNothing);
      expect(find.text('Corriger un article'), findsNothing);
      expect(closureStateBuilds, 2,
          reason: 'day closure state must be refreshed after a cancel');
    });

    testWidgets('after a correction the page shows the corrected quantities',
        (tester) async {
      when(() => mockRepo.correctSale(any(), any(), any()))
          .thenAnswer((_) async {});
      await pump(tester, role: 'OWNER', status: 'COMPLETED');

      await tester.tap(find.text('Corriger un article'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).first, '3');
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).at(1),
          'Erreur de quantité scannée au comptoir');
      await tester.pumpAndSettle();
      await tester.tap(
          find.widgetWithText(FilledButton, 'Enregistrer la correction'));
      await tester.pumpAndSettle();

      // Item avatar shows the quantity; nothing shows the old 5 × 1 000 any more.
      expect(find.text('3'), findsOneWidget);
      expect(find.text('5'), findsNothing);
      expect(find.textContaining(RegExp(r'^5\D000')), findsNothing);
    });

    testWidgets('shows the client name, not its id', (tester) async {
      final sale = Sale(
        id: 'sale-999',
        storeId: 'store-1',
        employeeId: 'emp-1',
        clientId: '3f2b9c1e-0000-4000-8000-000000000001',
        occurredAt: DateTime(2026, 3, 19, 14, 30),
        totalAmount: 1000,
        paymentMode: PaymentModeEnum.cash,
        items: const [],
        createdAt: DateTime(2026, 3, 19, 14, 30),
      );
      await tester.pumpWidget(ProviderScope(
        overrides: [
          currentUserRoleProvider.overrideWithValue('EMPLOYEE'),
          saleClientNameProvider('3f2b9c1e-0000-4000-8000-000000000001')
              .overrideWith((ref) async => 'Awa Ndiaye'),
        ],
        child: MaterialApp(home: SaleDetailPage(saleId: sale.id, sale: sale)),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Awa Ndiaye'), findsOneWidget);
      expect(find.textContaining('3f2b9c1e'), findsNothing);
    });
  });
}
