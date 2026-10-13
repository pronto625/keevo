import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/domain/model/day_closure_model.dart';
import 'package:keevo/features/pos/presentation/provider/day_closure_providers.dart';
import 'package:keevo/features/pos/presentation/widget/day_summary_bottom_sheet.dart';
import 'package:keevo/features/pos/domain/usecase/close_day_usecase.dart';
import 'package:keevo/core/theme/app_theme.dart';
import 'package:mocktail/mocktail.dart';

class _MockCloseDayUseCase extends Mock implements CloseDayUseCase {}

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
      testWidgets('closure failure never shows raw exception text',
        (tester) async {
      final useCase = _MockCloseDayUseCase();
      when(() => useCase.execute(
            storeId: any(named: 'storeId'),
            actorId: any(named: 'actorId'),
          )).thenThrow(StateError('internal'));

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            todaySummaryProvider((storeId: testStoreId, employeeId: null))
                .overrideWith((ref) => Future.value(mockSummary)),
            closeDayUseCaseProvider.overrideWithValue(useCase),
          ],
          child: MaterialApp(
            home: Scaffold(
              body: SingleChildScrollView(
                child: DaySummaryBottomSheet(
                  storeId: testStoreId,
                  actorId: testActorId,
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.pump();

      await tester.tap(find.text('Confirmer la clôture'));
      await tester.pump();
      await tester.pump();

      expect(find.textContaining('Bad state'), findsNothing);
      expect(find.textContaining('internal'), findsNothing);
      expect(find.textContaining('Réessayez'), findsOneWidget);
    });

    // v1s-15-8 (deferred from HF-2 ghost-badge): an employee-scoped sheet
    // asks for that employee's figures only, never the store-wide (null) ones.
    testWidgets('EMPLOYEE-scoped sheet requests only that employee summary',
        (tester) async {
      final requested = <({String storeId, String? employeeId})>[];
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            todaySummaryProvider.overrideWith((ref, args) async {
              requested.add(args);
              return mockSummary;
            }),
          ],
          child: const MaterialApp(
            home: Scaffold(
              body: DaySummaryBottomSheet(
                storeId: testStoreId,
                actorId: testActorId,
                employeeId: 'emp-1',
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.pump();

      expect(requested, [(storeId: testStoreId, employeeId: 'emp-1')]);
    });

    testWidgets('show() draws a single drag handle (theme handle off)',
        (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            todaySummaryProvider((storeId: testStoreId, employeeId: null))
                .overrideWith((ref) => Future.value(mockSummary)),
          ],
          child: MaterialApp(
            theme: AppTheme.light(),
            home: Consumer(
              builder: (context, ref, _) => Scaffold(
                body: TextButton(
                  onPressed: () => DaySummaryBottomSheet.show(
                    context: context,
                    ref: ref,
                    storeId: testStoreId,
                    actorId: testActorId,
                  ),
                  child: const Text('open'),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('open'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));

      final sheet = tester.widget<BottomSheet>(find.byType(BottomSheet));
      expect(sheet.showDragHandle, isFalse);
    });

    // v1s-16-11 follow-up: the retry reloads the same summary instance and
    // stops the 5 s auto-dismiss, so the sheet stays open with the data.
    testWidgets('summary error → Réessayer shows the data, sheet stays open',
        (tester) async {
      final requested = <({String storeId, String? employeeId})>[];
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            todaySummaryProvider.overrideWith((ref, args) async {
              requested.add(args);
              if (requested.length == 1) throw StateError('db locked');
              return mockSummary;
            }),
          ],
          child: MaterialApp(
            home: Consumer(
              builder: (context, ref, _) => Scaffold(
                body: TextButton(
                  onPressed: () => DaySummaryBottomSheet.show(
                    context: context,
                    ref: ref,
                    storeId: testStoreId,
                    actorId: testActorId,
                    employeeId: 'emp-1',
                  ),
                  child: const Text('open'),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('open'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.text('Réessayer'), findsOneWidget);

      await tester.tap(find.text('Réessayer'));
      await tester.pump();
      await tester.pump();
      // Well past the 5 s auto-dismiss.
      await tester.pump(const Duration(seconds: 6));

      expect(find.byType(DaySummaryBottomSheet), findsOneWidget);
      expect(find.text('iPhone 14'), findsOneWidget);
      expect(requested, [
        (storeId: testStoreId, employeeId: 'emp-1'),
        (storeId: testStoreId, employeeId: 'emp-1'),
      ]);
    });
  });
}
