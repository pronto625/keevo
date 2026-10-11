import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/storage/app_constants.dart';
import 'package:keevo/features/onboarding/presentation/page/onboarding_page.dart';
import 'package:shared_preferences/shared_preferences.dart';

Widget _buildPage({ThemeMode themeMode = ThemeMode.light}) {
  return MaterialApp.router(
    themeMode: themeMode,
    darkTheme: ThemeData.dark(),
    routerConfig: GoRouter(
      initialLocation: '/onboarding',
      routes: [
        GoRoute(
          path: '/onboarding',
          builder: (_, __) => const OnboardingPage(),
        ),
        GoRoute(
          path: '/terms',
          builder: (_, __) => const Scaffold(body: Text('Terms')),
        ),
      ],
    ),
  );
}

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  setUp(() => SharedPreferences.setMockInitialValues({}));

  group('OnboardingPage', () {
    testWidgets('renders the four feature cards and the CTA', (tester) async {
      await tester.pumpWidget(_buildPage());
      await tester.pumpAndSettle();

      expect(find.text('Stock en temps réel'), findsOneWidget);
      expect(find.text('Caisse (POS)'), findsOneWidget);
      expect(find.text('Mode hors-ligne'), findsOneWidget);
      expect(find.text('Rapports WhatsApp'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'Commencer'), findsOneWidget);
    });

    testWidgets('language toggle switches the copy to English',
        (tester) async {
      await tester.pumpWidget(_buildPage());
      await tester.pumpAndSettle();

      await tester.tap(find.text('EN'));
      await tester.pumpAndSettle();

      expect(find.text('Real-time stock'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'Get started'), findsOneWidget);
      expect(find.text('Commencer'), findsNothing);
    });

    testWidgets('CTA marks onboarding as seen and goes to /terms',
        (tester) async {
      await tester.pumpWidget(_buildPage());
      await tester.pumpAndSettle();

      await tester.tap(find.byType(FilledButton));
      await tester.pumpAndSettle();

      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getBool(kOnboardingSeenKey), isTrue);
      expect(find.text('Terms'), findsOneWidget);
    });

    testWidgets('lays out without overflow on a small phone', (tester) async {
      tester.view.physicalSize = const Size(320, 568);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);

      await tester.pumpWidget(_buildPage());
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.widgetWithText(FilledButton, 'Commencer'), findsOneWidget);
    });

    testWidgets('renders in dark mode', (tester) async {
      await tester.pumpWidget(_buildPage(themeMode: ThemeMode.dark));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.text('Stock en temps réel'), findsOneWidget);
    });
  });
}
