import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';
import 'package:keevo/features/dashboard/presentation/widget/top_products_section.dart';

Widget _wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('TopProductsSection', () {
    testWidgets('renders title and product list', (tester) async {
      await tester.pumpWidget(_wrap(TopProductsSection(
        products: const [
          TopProduct(
            productId: 'p1',
            name: 'Riz 5kg',
            unitsSold: 42,
            revenue: 210000,
            sharePercent: 35,
          ),
          TopProduct(
            productId: 'p2',
            name: 'Huile 1L',
            unitsSold: 30,
            revenue: 90000,
            sharePercent: 15,
          ),
        ],
      )));

      expect(find.text('Top Produits (7j)'), findsOneWidget);
      expect(find.text('Riz 5kg'), findsOneWidget);
      expect(find.text('Huile 1L'), findsOneWidget);
      expect(find.text('#1'), findsOneWidget);
      expect(find.text('#2'), findsOneWidget);
    });

    testWidgets('shows units sold', (tester) async {
      await tester.pumpWidget(_wrap(const TopProductsSection(
        products: [
          TopProduct(
            productId: 'p1',
            name: 'Savon',
            unitsSold: 99,
            revenue: 49500,
            sharePercent: 50,
          ),
        ],
      )));

      expect(find.text('99 u.'), findsOneWidget);
    });

    testWidgets('hides when products list is empty', (tester) async {
      await tester.pumpWidget(_wrap(const TopProductsSection(products: [])));

      expect(find.text('Top Produits (7j)'), findsNothing);
    });

    testWidgets('shows Voir Tout button when onViewAll is set', (tester) async {
      await tester.pumpWidget(_wrap(TopProductsSection(
        products: const [
          TopProduct(
            productId: 'p1',
            name: 'Item',
            unitsSold: 1,
            revenue: 100,
            sharePercent: 100,
          ),
        ],
        onViewAll: () {},
      )));

      expect(find.text('Voir Tout'), findsOneWidget);
    });
  });
}
