import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/pos/presentation/widget/cart_pill.dart';

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  Widget wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

  group('CartPill', () {
    testWidgets('hidden when itemCount is 0', (tester) async {
      await tester.pumpWidget(wrap(CartPill(
        itemCount: 0,
        totalAmount: 0,
        onEncaisser: () {},
      )));
      await tester.pumpAndSettle();
      expect(find.byType(FilledButton), findsNothing);
    });

    testWidgets('visible when itemCount > 0', (tester) async {
      await tester.pumpWidget(wrap(CartPill(
        itemCount: 3,
        totalAmount: 1500,
        onEncaisser: () {},
      )));
      await tester.pumpAndSettle();
      expect(find.byType(FilledButton), findsOneWidget);
    });

    testWidgets('displays item count and total', (tester) async {
      await tester.pumpWidget(wrap(CartPill(
        itemCount: 2,
        totalAmount: 3000,
        onEncaisser: () {},
      )));
      await tester.pumpAndSettle();
      expect(find.textContaining('2 articles'), findsOneWidget);
      expect(find.textContaining('Encaisser'), findsOneWidget);
    });

    testWidgets('singular article for 1 item', (tester) async {
      await tester.pumpWidget(wrap(CartPill(
        itemCount: 1,
        totalAmount: 500,
        onEncaisser: () {},
      )));
      await tester.pumpAndSettle();
      expect(find.textContaining('1 article'), findsOneWidget);
    });

    testWidgets('onEncaisser callback fires on tap', (tester) async {
      var tapped = false;
      await tester.pumpWidget(wrap(CartPill(
        itemCount: 1,
        totalAmount: 500,
        onEncaisser: () => tapped = true,
      )));
      await tester.pumpAndSettle();
      await tester.tap(find.byType(FilledButton));
      expect(tapped, isTrue);
    });
  });
}
