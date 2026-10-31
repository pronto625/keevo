import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/widget/lang_toggle.dart';

Widget _wrap(Widget child) =>
    MaterialApp(home: Scaffold(body: Center(child: child)));

void main() {
  group('LangToggle', () {
    testWidgets('tapping EN reports false', (tester) async {
      bool? received;
      await tester.pumpWidget(_wrap(LangToggle(
        isFrench: true,
        onChanged: (fr) => received = fr,
      )));

      await tester.tap(find.text('EN'));
      expect(received, isFalse);
    });

    testWidgets('tapping FR reports true', (tester) async {
      bool? received;
      await tester.pumpWidget(_wrap(LangToggle(
        isFrench: false,
        onChanged: (fr) => received = fr,
      )));

      await tester.tap(find.text('FR'));
      expect(received, isTrue);
    });
  });
}
