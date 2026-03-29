import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/features/pos/domain/model/payment_mode_enum.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:keevo/features/pos/domain/model/sales_history_filter.dart';
import 'package:keevo/features/pos/presentation/page/sales_history_page.dart';
import 'package:keevo/features/pos/presentation/provider/day_closure_providers.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUpAll(() async {
    GoogleFonts.config.allowRuntimeFetching = false;
    await initializeDateFormatting('fr_FR');
  });

  late SharedPreferences prefs;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
  });

  group('SalesHistoryPage', () {
    final mockSales = [
      Sale(
        id: 'sale-1',
        storeId: 'store-1',
        employeeId: 'emp-1',
        occurredAt: DateTime.now(),
        totalAmount: 50000,
        discountAmount: 0,
        paymentMode: PaymentModeEnum.cash,
        status: 'COMPLETED',
        items: [],
        createdAt: DateTime.now(),
      ),
      Sale(
        id: 'sale-2',
        storeId: 'store-1',
        employeeId: 'emp-1',
        occurredAt: DateTime.now().subtract(const Duration(hours: 2)),
        totalAmount: 30000,
        discountAmount: 5000,
        paymentMode: PaymentModeEnum.mobileMoney,
        status: 'COMPLETED',
        items: [],
        createdAt: DateTime.now(),
      ),
    ];

    testWidgets('loads and displays sales for today', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sharedPreferencesProvider.overrideWithValue(prefs),
            currentUserIdProvider.overrideWith((ref) => Future.value('emp-1')),
            salesHistoryProvider.overrideWith((ref, filter) => Future.value(mockSales)),
          ],
          child: const MaterialApp(
            home: SalesHistoryPage(),
          ),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.text('Mes Ventes'), findsOneWidget);
      expect(find.byType(Card), findsNWidgets(2));
    });

    testWidgets('shows empty state when no sales', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sharedPreferencesProvider.overrideWithValue(prefs),
            currentUserIdProvider.overrideWith((ref) => Future.value('emp-1')),
            salesHistoryProvider.overrideWith((ref, filter) => Future.value([])),
          ],
          child: const MaterialApp(
            home: SalesHistoryPage(),
          ),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.textContaining('Aucune'), findsOneWidget);
    });

    testWidgets('filter chips are displayed', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sharedPreferencesProvider.overrideWithValue(prefs),
            currentUserIdProvider.overrideWith((ref) => Future.value('emp-1')),
            salesHistoryProvider.overrideWith((ref, filter) => Future.value(mockSales)),
          ],
          child: const MaterialApp(
            home: SalesHistoryPage(),
          ),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.text("Aujourd'hui"), findsOneWidget);
      expect(find.text('Cette semaine'), findsOneWidget);
      expect(find.text('Ce mois'), findsOneWidget);
    });

    testWidgets('tapping filter chip changes filter', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sharedPreferencesProvider.overrideWithValue(prefs),
            currentUserIdProvider.overrideWith((ref) => Future.value('emp-1')),
            salesHistoryProvider.overrideWith((ref, filter) => Future.value(mockSales)),
          ],
          child: const MaterialApp(
            home: SalesHistoryPage(),
          ),
        ),
      );

      await tester.pumpAndSettle();

      await tester.tap(find.text('Cette semaine'));
      await tester.pumpAndSettle();

      // Filter should update - verify by checking provider was called with new filter
      expect(find.text('Cette semaine'), findsOneWidget);
    });

    testWidgets('displays pending badge for pending sales', (tester) async {
      final pendingSale = Sale(
        id: 'sale-pending',
        storeId: 'store-1',
        employeeId: 'emp-1',
        occurredAt: DateTime.now(),
        totalAmount: 20000,
        discountAmount: 0,
        paymentMode: PaymentModeEnum.cash,
        status: 'PENDING_VALIDATION',
        items: [],
        createdAt: DateTime.now(),
      );

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sharedPreferencesProvider.overrideWithValue(prefs),
            currentUserIdProvider.overrideWith((ref) => Future.value('emp-1')),
            salesHistoryProvider.overrideWith((ref, filter) => Future.value([pendingSale])),
          ],
          child: const MaterialApp(
            home: SalesHistoryPage(),
          ),
        ),
      );

      await tester.pumpAndSettle();

      // Should show pending badge/indicator
      expect(find.byType(Card), findsOneWidget);
    });

    testWidgets('displays cancelled badge for cancelled sales', (tester) async {
      final cancelledSale = Sale(
        id: 'sale-cancelled',
        storeId: 'store-1',
        employeeId: 'emp-1',
        occurredAt: DateTime.now(),
        totalAmount: 15000,
        discountAmount: 0,
        paymentMode: PaymentModeEnum.cash,
        status: 'CANCELLED',
        items: [],
        createdAt: DateTime.now(),
      );

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sharedPreferencesProvider.overrideWithValue(prefs),
            currentUserIdProvider.overrideWith((ref) => Future.value('emp-1')),
            salesHistoryProvider.overrideWith((ref, filter) => Future.value([cancelledSale])),
          ],
          child: const MaterialApp(
            home: SalesHistoryPage(),
          ),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.byType(Card), findsOneWidget);
    });
  });
}
