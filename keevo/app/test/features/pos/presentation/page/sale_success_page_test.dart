import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/features/pos/presentation/page/sale_success_page.dart';

Widget _app(SaleSuccessPage page) => MaterialApp.router(
      routerConfig: GoRouter(routes: [
        GoRoute(path: '/', builder: (_, __) => page),
        GoRoute(path: '/pos', builder: (_, __) => const Text('POS')),
      ]),
    );

void main() {
  group('SaleSuccessPage — P1 (v1s-12-10)', () {
    testWidgets('employee draft sale: recorded sale + drafts-to-validate note',
        (tester) async {
      await tester.pumpWidget(_app(const SaleSuccessPage(
        totalAmount: 30000,
        hasDraftsToValidate: true,
      )));
      await tester.pump(const Duration(milliseconds: 500));

      expect(find.text('Vente enregistrée !'), findsOneWidget);
      expect(find.text('Vente en attente'), findsNothing);
      expect(find.text('Produit(s) brouillon à valider par le propriétaire'),
          findsOneWidget);

      await tester.pumpAndSettle(const Duration(seconds: 2));
      expect(find.text('POS'), findsOneWidget);
    });

    testWidgets('plain sale: no draft note', (tester) async {
      await tester.pumpWidget(_app(const SaleSuccessPage(totalAmount: 1000)));
      await tester.pump(const Duration(milliseconds: 500));

      expect(find.text('Vente enregistrée !'), findsOneWidget);
      expect(find.textContaining('brouillon'), findsNothing);

      await tester.pumpAndSettle(const Duration(seconds: 2));
    });
  });
}
