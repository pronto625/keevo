import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/router/app_router.dart';
import 'package:keevo/features/auth/presentation/page/auth_page.dart';
import 'package:keevo/features/onboarding/presentation/page/terms_page.dart';

/// v1s-16-13: read-only CGU view opened from the register form.
void main() {
  late SharedPreferences prefs;

  setUpAll(() async {
    GoogleFonts.config.allowRuntimeFetching = false;
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
  });

  Widget app() => ProviderScope(
        overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
        child: MaterialApp.router(
          routerConfig: GoRouter(
            initialLocation: '/auth/register',
            routes: [
              GoRoute(
                path: '/auth/register',
                builder: (_, __) =>
                    const AuthPage(initialMode: AuthMode.register),
              ),
              GoRoute(
                path: '/terms/view',
                builder: (_, state) => termsViewPage(state.uri),
              ),
            ],
          ),
        ),
      );

  testWidgets('read-only mode hides the acceptance zone and the hints',
      (tester) async {
    await tester.pumpWidget(const MaterialApp(home: TermsPage(readOnly: true)));
    await tester.pumpAndSettle();

    expect(find.text('1. Acceptation des conditions'), findsOneWidget);
    expect(find.textContaining('J\'accepte'), findsNothing);
    expect(find.byType(Checkbox), findsNothing);
    expect(find.textContaining('Veuillez lire jusqu'), findsNothing);
  });

  testWidgets('the acceptance flow is unchanged by default', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: TermsPage()));
    await tester.pumpAndSettle();

    expect(find.byType(Checkbox), findsNWidgets(2));
    expect(find.text('Lisez les conditions pour continuer'), findsOneWidget);
  });

  testWidgets(
      'tapping « Conditions d\'utilisation » opens the read-only page; '
      'back returns to the form', (tester) async {
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    await tester
        .tapOnText(find.textRange.ofSubstring("Conditions d'utilisation"));
    await tester.pumpAndSettle();

    expect(find.text('1. Acceptation des conditions'), findsOneWidget);
    expect(find.byType(Checkbox), findsNothing);

    await tester.tap(find.byIcon(Icons.arrow_back));
    await tester.pumpAndSettle();

    expect(find.byType(AuthPage), findsOneWidget);
    expect(find.byType(TermsPage), findsNothing);
  });

  testWidgets('the privacy link opens the page at the privacy policy',
      (tester) async {
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    await tester
        .tapOnText(find.textRange.ofSubstring('Politique de confidentialité'));
    await tester.pumpAndSettle();

    expect(find.text('Politique de confidentialité').hitTestable(),
        findsOneWidget);
  });
}
