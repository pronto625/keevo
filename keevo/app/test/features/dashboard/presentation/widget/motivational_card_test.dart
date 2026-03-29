import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/dashboard/presentation/widget/motivational_card.dart';

Widget _wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('MotivationalCard', () {
    testWidgets('displays message text', (tester) async {
      await tester.pumpWidget(_wrap(MotivationalCard(
        message: 'Bonne journée Amadou ! Visez 50 000 FCFA !',
        onDismiss: () {},
      )));

      expect(find.textContaining('Bonne journée Amadou'), findsOneWidget);
    });

    testWidgets('displays lightbulb emoji', (tester) async {
      await tester.pumpWidget(_wrap(MotivationalCard(
        message: 'Test message',
        onDismiss: () {},
      )));

      expect(find.text('💡'), findsOneWidget);
    });

    testWidgets('close button triggers onDismiss', (tester) async {
      var dismissed = false;
      await tester.pumpWidget(_wrap(MotivationalCard(
        message: 'Dismissable message',
        onDismiss: () => dismissed = true,
      )));

      await tester.tap(find.byIcon(Icons.close));
      expect(dismissed, isTrue);
    });
  });
}
