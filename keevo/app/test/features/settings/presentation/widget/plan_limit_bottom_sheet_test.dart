import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/settings/presentation/widget/plan_limit_bottom_sheet.dart';

void main() {
  group('PlanLimitBottomSheet', () {
    testWidgets('shows entity limit message for stores', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (ctx) => Scaffold(
            body: ElevatedButton(
              onPressed: () => showPlanLimitBottomSheet(
                context: ctx,
                entity: 'stores',
                limit: 1,
              ),
              child: const Text('test'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('test'));
      await tester.pumpAndSettle();

      expect(find.textContaining('1 boutique'), findsOneWidget);
      expect(find.text('Passer au plan Premium'), findsOneWidget);
    });

    testWidgets('shows entity limit message for products', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (ctx) => Scaffold(
            body: ElevatedButton(
              onPressed: () => showPlanLimitBottomSheet(
                context: ctx,
                entity: 'products',
                limit: 500,
              ),
              child: const Text('test'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('test'));
      await tester.pumpAndSettle();

      expect(find.textContaining('500 produits'), findsOneWidget);
    });

    testWidgets('tapping Premium CTA closes the sheet', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (ctx) => Scaffold(
            body: ElevatedButton(
              onPressed: () => showPlanLimitBottomSheet(
                context: ctx,
                entity: 'stores',
                limit: 1,
              ),
              child: const Text('test'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('test'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Passer au plan Premium'));
      await tester.pumpAndSettle();
      expect(find.text('Passer au plan Premium'), findsNothing);
    });

    // v1s-16-13: callers no longer pass literals; FREE = 1 store, 3 employees
    // (backend PlanType.FREE(1, 500, 3)).
    for (final (entity, label) in [
      ('employees', 'limite de 3 employés'),
      ('stores', 'limite de 1 boutique '),
      ('products', 'limite de 500 produits'),
    ]) {
      testWidgets('defaults to the FREE limit for $entity', (tester) async {
        await tester.pumpWidget(MaterialApp(
          home: Builder(
            builder: (ctx) => Scaffold(
              body: ElevatedButton(
                onPressed: () =>
                    showPlanLimitBottomSheet(context: ctx, entity: entity),
                child: const Text('test'),
              ),
            ),
          ),
        ));
        await tester.tap(find.text('test'));
        await tester.pumpAndSettle();

        expect(find.textContaining(label), findsOneWidget);
      });
    }

    test('kFreePlanLimits mirrors PlanType.FREE', () {
      expect(kFreePlanLimits, {'stores': 1, 'products': 500, 'employees': 3});
    });
  });
}
