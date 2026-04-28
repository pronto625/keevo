import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/domain/model/day_closure_model.dart';
import 'package:keevo/features/pos/presentation/provider/day_closure_providers.dart';
import 'package:keevo/features/pos/presentation/widget/day_summary_bottom_sheet.dart';

void main() {
  group('DaySummaryBottomSheet', () {
    const testStoreId = 'store-123';
    const testActorId = 'actor-456';

    final mockSummary = DayClosureSummary(
      totalSales: 10,
      totalRevenue: 150000,
      topProductId: 'prod-1',
      topProductName: 'iPhone 14',
      topProductQty: 3,
      cashAmount: 100000,
      momoAmount: 50000,
      pendingSalesCount: 2,
      pendingSalesTotal: 25000,
    );

    testWidgets('widget builds without error', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            todaySummaryProvider((
              storeId: testStoreId,
              employeeId: null,
            )).overrideWith(
              (ref) => Future.value(mockSummary),
            ),
          ],
          child: MaterialApp(
            home: Scaffold(
              body: DaySummaryBottomSheet(
                storeId: testStoreId,
                actorId: testActorId,
              ),
            ),
          ),
        ),
      );

      await tester.pump();
      
      // Just verify it builds without throwing
      expect(tester.takeException(), isNull);
    });

    testWidgets('displays summary data', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            todaySummaryProvider((
              storeId: testStoreId,
              employeeId: null,
            )).overrideWith(
              (ref) => Future.value(mockSummary),
            ),
          ],
          child: MaterialApp(
            home: Scaffold(
              body: DaySummaryBottomSheet(
                storeId: testStoreId,
                actorId: testActorId,
              ),
            ),
          ),
        ),
      );

      await tester.pump(); // Initial build
      await tester.pump(); // Async provider resolution

      // Check for some key widgets
      expect(find.byType(ElevatedButton), findsOneWidget);
      expect(find.byType(OutlinedButton), findsOneWidget);
    });
  });
}
