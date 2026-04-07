import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/pos/presentation/widget/product_card.dart';
import 'package:keevo/features/pos/presentation/widget/product_initials_avatar.dart';

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  Widget wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

  group('ProductCard', () {
    testWidgets('displays name and formatted price', (tester) async {
      await tester.pumpWidget(wrap(ProductCard(
        name: 'Savon',
        price: 1500,
        stockQuantity: 10,
        onTap: () {},
      )));
      expect(find.text('Savon'), findsOneWidget);
      expect(find.textContaining('1'), findsWidgets);
    });

    testWidgets('shows initials when no photo', (tester) async {
      await tester.pumpWidget(wrap(ProductCard(
        name: 'Savon Palmolive',
        price: 500,
        stockQuantity: 5,
        onTap: () {},
      )));
      expect(find.text('SP'), findsOneWidget);
    });

    testWidgets('shows rupture overlay when out of stock', (tester) async {
      await tester.pumpWidget(wrap(ProductCard(
        name: 'Savon',
        price: 500,
        stockQuantity: 0,
        onTap: () {},
      )));
      expect(find.textContaining('Rupture'), findsOneWidget);
    });

    testWidgets('flashes blue on tap', (tester) async {
      var tapped = false;
      await tester.pumpWidget(wrap(ProductCard(
        name: 'Savon',
        price: 500,
        stockQuantity: 5,
        onTap: () => tapped = true,
      )));
      await tester.tap(find.byType(ProductCard));
      await tester.pump();
      expect(tapped, isTrue);
      // After 200ms, flash ends normally
      await tester.pump(const Duration(milliseconds: 200));
    });
  });
}
