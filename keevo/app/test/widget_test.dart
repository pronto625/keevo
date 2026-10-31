import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';

import 'package:keevo_core/theme/app_theme.dart';

/// Theme smoke test: the light and dark [AppTheme]s each build a MaterialApp
/// without exception (it does not pump the app shell / router).
///
/// v1s-15-8: this file used to hold an empty `main()`, which makes
/// `flutter test test/widget_test.dart` exit non-zero ("No tests were found")
/// and the per-batch run red. Feature and core tests live under test/.
void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  for (final mode in [ThemeMode.light, ThemeMode.dark]) {
    testWidgets('${mode.name} AppTheme builds a MaterialApp', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.light(),
        darkTheme: AppTheme.dark(),
        themeMode: mode,
        home: const Scaffold(body: Center(child: Text('Keevo'))),
      ));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.text('Keevo'), findsOneWidget);
      final brightness =
          Theme.of(tester.element(find.text('Keevo'))).brightness;
      expect(
        brightness,
        mode == ThemeMode.dark ? Brightness.dark : Brightness.light,
      );
    });
  }
}
