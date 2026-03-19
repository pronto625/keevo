import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/domain/model/day_closure_model.dart' as domain;
import 'package:keevo/features/pos/presentation/provider/day_closure_providers.dart';
import 'package:keevo/features/pos/presentation/widget/day_close_button.dart';
import 'package:keevo/features/pos/presentation/widget/day_summary_bottom_sheet.dart';
import 'package:mocktail/mocktail.dart';

class MockNotifier extends Mock {}

void main() {
  group('DayCloseButton', () {
    const testStoreId = 'store-123';
    const testActorId = 'actor-456';

    testWidgets('shows badge with count when sales exist', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            dayClosureStateProvider(testStoreId).overrideWith(
              (ref) => Future.value(DayCloseButtonState.available),
            ),
            todaySalesCountProvider(testStoreId).overrideWith(
              (ref) => Future.value(5),
            ),
          ],
          child: const MaterialApp(
            home: Scaffold(
              body: DayCloseButton(
                storeId: testStoreId,
                actorId: testActorId,
              ),
            ),
          ),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.text('Clôturer la journée'), findsOneWidget);
      expect(find.text('5'), findsOneWidget);
      expect(find.byIcon(Icons.nightlight_round), findsOneWidget);
    });

    testWidgets('shows no badge when no sales exist', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            dayClosureStateProvider(testStoreId).overrideWith(
              (ref) => Future.value(DayCloseButtonState.noSales),
            ),
            todaySalesCountProvider(testStoreId).overrideWith(
              (ref) => Future.value(0),
            ),
          ],
          child: const MaterialApp(
            home: Scaffold(
              body: DayCloseButton(
                storeId: testStoreId,
                actorId: testActorId,
              ),
            ),
          ),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.text('Clôturer la journée'), findsOneWidget);
      expect(find.text('0'), findsNothing);
    });

    testWidgets('shows disabled state when day already closed', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            dayClosureStateProvider(testStoreId).overrideWith(
              (ref) => Future.value(DayCloseButtonState.closed),
            ),
          ],
          child: const MaterialApp(
            home: Scaffold(
              body: DayCloseButton(
                storeId: testStoreId,
                actorId: testActorId,
              ),
            ),
          ),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.text('Journée clôturée ✅'), findsOneWidget);
      expect(find.byIcon(Icons.check_circle), findsOneWidget);
      
      final button = tester.widget<ElevatedButton>(
        find.byType(ElevatedButton),
      );
      expect(button.onPressed, isNull);
    });

    testWidgets('tap opens DaySummaryBottomSheet', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            dayClosureStateProvider(testStoreId).overrideWith(
              (ref) => Future.value(DayCloseButtonState.available),
            ),
            todaySalesCountProvider(testStoreId).overrideWith(
              (ref) => Future.value(3),
            ),
            todaySummaryProvider(testStoreId).overrideWith(
              (ref) => Future.value(const domain.DayClosureSummary(
                totalSales: 3,
                totalRevenue: 50000,
                cashAmount: 30000,
                momoAmount: 20000,
                topProductId: 'prod-1',
                topProductName: 'Test Product',
                topProductQty: 2,
                pendingSalesCount: 0,
                pendingSalesTotal: 0,
              )),
            ),
          ],
          child: MaterialApp(
            home: Scaffold(
              body: Builder(
                builder: (context) => DayCloseButton(
                  storeId: testStoreId,
                  actorId: testActorId,
                ),
              ),
            ),
          ),
        ),
      );

      await tester.pumpAndSettle();

      // Tap the button to open bottom sheet
      await tester.tap(find.byType(ElevatedButton));
      await tester.pump(); // Start the animation
      await tester.pump(const Duration(milliseconds: 500)); // Advance animation

      // Bottom sheet should be shown with its title
      expect(find.text('Résumé de la journée'), findsOneWidget);
    });
  });
}
