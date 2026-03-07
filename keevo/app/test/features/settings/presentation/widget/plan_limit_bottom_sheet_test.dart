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
  });
}
