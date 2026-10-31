import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/auth/presentation/page/auth_page.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:shared_preferences/shared_preferences.dart';

Future<Widget> _buildApp({
  required String initialLocation,
  bool termsAccepted = false,
}) async {
  SharedPreferences.setMockInitialValues({kTermsAcceptedKey: termsAccepted});
  final prefs = await SharedPreferences.getInstance();
  return ProviderScope(
    overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
    child: MaterialApp.router(
      routerConfig: GoRouter(
        initialLocation: initialLocation,
        routes: [
          GoRoute(
            path: '/auth/login',
            builder: (_, __) => const AuthPage(initialMode: AuthMode.login),
          ),
          GoRoute(
            path: '/auth/register',
            builder: (_, __) => const AuthPage(initialMode: AuthMode.register),
          ),
          GoRoute(
            path: '/terms',
            builder: (_, __) => const Scaffold(body: Text('Terms')),
          ),
          GoRoute(
            path: '/onboarding',
            builder: (_, __) => const Scaffold(body: Text('Onboarding')),
          ),
        ],
      ),
    ),
  );
}

Finder _modeLink(String text) => find.byWidgetPredicate(
      (w) => w is RichText && w.text.toPlainText().contains(text),
    );

Future<void> _tapModeLink(WidgetTester tester, String text) async {
  await tester.ensureVisible(_modeLink(text));
  await tester.tap(_modeLink(text));
  await tester.pumpAndSettle();
}

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('AuthPage — back navigation', () {
    testWidgets('login opened as the first screen has no back arrow',
        (tester) async {
      await tester.pumpWidget(await _buildApp(initialLocation: '/auth/login'));
      await tester.pumpAndSettle();

      expect(find.byIcon(Icons.arrow_back), findsNothing);
    });

    testWidgets('register goes back to the terms, not the welcome screen',
        (tester) async {
      await tester.pumpWidget(await _buildApp(
        initialLocation: '/auth/register',
        termsAccepted: true,
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.byIcon(Icons.arrow_back));
      await tester.pumpAndSettle();

      expect(find.text('Terms'), findsOneWidget);
    });

    testWidgets('back undoes an in-page mode switch', (tester) async {
      await tester.pumpWidget(await _buildApp(
        initialLocation: '/auth/login',
        termsAccepted: true,
      ));
      await tester.pumpAndSettle();

      await _tapModeLink(tester, 'Pas encore de compte');
      expect(find.text('Inscription'), findsOneWidget);

      await tester.tap(find.byIcon(Icons.arrow_back));
      await tester.pumpAndSettle();

      expect(find.text('Connexion'), findsOneWidget);
      expect(find.byIcon(Icons.arrow_back), findsNothing);
    });
  });

  group('AuthPage — terms before registration', () {
    testWidgets('switching to sign-up without accepted terms opens the terms',
        (tester) async {
      await tester.pumpWidget(await _buildApp(initialLocation: '/auth/login'));
      await tester.pumpAndSettle();

      await _tapModeLink(tester, 'Pas encore de compte');

      expect(find.text('Terms'), findsOneWidget);
    });

    testWidgets('switching to sign-up with accepted terms stays on the page',
        (tester) async {
      await tester.pumpWidget(await _buildApp(
        initialLocation: '/auth/login',
        termsAccepted: true,
      ));
      await tester.pumpAndSettle();

      await _tapModeLink(tester, 'Pas encore de compte');

      expect(find.text('Inscription'), findsOneWidget);
    });
  });
}
