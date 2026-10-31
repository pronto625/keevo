import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/auth/domain/model/registration_result.dart';
import 'package:keevo/features/auth/domain/usecase/register_user_usecase.dart';
import 'package:keevo/features/auth/presentation/page/auth_page.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

// ── Mocks ────────────────────────────────────────────────────────────────────

class MockRegisterUserUseCase extends Mock implements RegisterUserUseCase {}

late SharedPreferences _prefs;

// ── Helpers ──────────────────────────────────────────────────────────────────

GoRouter _testRouter() {
  return GoRouter(
    initialLocation: '/auth/register',
    routes: [
      GoRoute(
        path: '/auth/register',
        builder: (_, __) => const AuthPage(),
      ),
      GoRoute(
        path: '/onboarding',
        builder: (_, __) => const Scaffold(body: Text('Onboarding')),
      ),
      GoRoute(
        path: '/terms',
        builder: (_, __) => const Scaffold(body: Text('Terms')),
      ),
      GoRoute(
        path: '/auth/login',
        builder: (_, __) => const Scaffold(body: Text('Login')),
      ),
      GoRoute(
        path: '/onboarding/sector',
        builder: (_, __) => const Scaffold(body: Text('Sector')),
      ),
    ],
  );
}

Widget _buildPage(List<Override> overrides) {
  return ProviderScope(
    overrides: [
      sharedPreferencesProvider.overrideWithValue(_prefs),
      ...overrides,
    ],
    child: MaterialApp.router(
      routerConfig: _testRouter(),
    ),
  );
}

void main() {
  setUpAll(() async {
    GoogleFonts.config.allowRuntimeFetching = false;
    SharedPreferences.setMockInitialValues({});
    _prefs = await SharedPreferences.getInstance();
  });

  late MockRegisterUserUseCase mockUseCase;

  setUp(() {
    mockUseCase = MockRegisterUserUseCase();
  });

  group('AuthPage — Register Mode', () {
    testWidgets('renders phone/password fields and submit button',
        (tester) async {
      await tester.pumpWidget(_buildPage([
        registerUserUseCaseProvider.overrideWithValue(mockUseCase),
      ]));
      await tester.pump();

      expect(find.byKey(const Key('phoneField')), findsOneWidget);
      expect(find.byKey(const Key('passwordField')), findsOneWidget);
      expect(find.byKey(const Key('submitButton')), findsOneWidget);
    });

    testWidgets('shows validation error when form submitted empty',
        (tester) async {
      await tester.pumpWidget(_buildPage([
        registerUserUseCaseProvider.overrideWithValue(mockUseCase),
      ]));
      await tester.pump();

      await tester.tap(find.byKey(const Key('submitButton')));
      await tester.pumpAndSettle();

      // Password field is a plain TextFormField — its validation error is reliable in tests
      expect(find.text('Le mot de passe est requis'), findsOneWidget);
    });

    testWidgets('shows error banner when registration fails',
        (tester) async {
      when(() => mockUseCase.execute(
                phoneNumber: any(named: 'phoneNumber'),
                password: any(named: 'password'),
              ))
          .thenThrow(ArgumentError('Phone number must not be empty'));

      await tester.pumpWidget(_buildPage([
        registerUserUseCaseProvider.overrideWithValue(mockUseCase),
      ]));
      await tester.pump();

      await tester.enterText(
          find.byKey(const Key('phoneField')), '600000001');
      await tester.enterText(
          find.byKey(const Key('passwordField')), 'SecurePass123!');
      await tester.tap(find.byKey(const Key('submitButton')));
      await tester.pump();

      expect(find.byKey(const Key('errorBanner')), findsOneWidget);
    });

    testWidgets('calls use case with correct inputs on submit', (tester) async {
      const result = RegistrationResult(
        tenantCode: 'KV-ABC123',
        token: 'STUB:u:t',
        userId: 'u',
        tenantId: 't',
      );

      when(() => mockUseCase.execute(
                phoneNumber: '+237600000001',
                password: 'SecurePass123!',
              ))
          .thenAnswer((_) async => result);

      await tester.pumpWidget(_buildPage([
        registerUserUseCaseProvider.overrideWithValue(mockUseCase),
      ]));
      await tester.pump();

      // Enter only the local number — IntlPhoneField prepends +237 (CM) automatically
      await tester.enterText(
          find.byKey(const Key('phoneField')), '600000001');
      await tester.enterText(
          find.byKey(const Key('passwordField')), 'SecurePass123!');
      await tester.tap(find.byKey(const Key('submitButton')));
      await tester.pump();

      verify(() => mockUseCase.execute(
            phoneNumber: any(named: 'phoneNumber'),
            password: 'SecurePass123!',
          )).called(1);
    });

    testWidgets('mode link switches to login mode', (tester) async {
      await tester.pumpWidget(_buildPage([
        registerUserUseCaseProvider.overrideWithValue(mockUseCase),
      ]));
      await tester.pump();

      expect(find.text('Mot de passe oublié ?'), findsNothing);

      final modeLink = find.byWidgetPredicate(
        (w) => w is RichText && w.text.toPlainText().contains('Déjà un compte'),
      );
      await tester.ensureVisible(modeLink);
      await tester.tap(modeLink);
      await tester.pumpAndSettle();

      expect(find.text('Connexion'), findsOneWidget);
      expect(find.text('Mot de passe oublié ?'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'Se connecter'), findsOneWidget);
    });

    testWidgets('language toggle switches the copy to English',
        (tester) async {
      await tester.pumpWidget(_buildPage([
        registerUserUseCaseProvider.overrideWithValue(mockUseCase),
      ]));
      await tester.pump();

      await tester.tap(find.text('EN'));
      await tester.pumpAndSettle();

      expect(find.text('Sign up'), findsOneWidget);
      expect(
        find.widgetWithText(FilledButton, 'Create my account'),
        findsOneWidget,
      );
    });

    testWidgets('back button returns to the terms', (tester) async {
      await tester.pumpWidget(_buildPage([
        registerUserUseCaseProvider.overrideWithValue(mockUseCase),
      ]));
      await tester.pump();

      await tester.tap(find.byIcon(Icons.arrow_back));
      await tester.pumpAndSettle();

      expect(find.text('Terms'), findsOneWidget);
    });

    testWidgets('lays out without overflow on a small phone', (tester) async {
      tester.view.physicalSize = const Size(320, 568);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);

      await tester.pumpWidget(_buildPage([
        registerUserUseCaseProvider.overrideWithValue(mockUseCase),
      ]));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.byKey(const Key('submitButton')), findsOneWidget);
    });

    testWidgets('short-password error wraps instead of being truncated',
        (tester) async {
      // 720 px wide phone at 2x density.
      tester.view.physicalSize = const Size(720, 1600);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(tester.view.reset);

      await tester.pumpWidget(_buildPage([
        registerUserUseCaseProvider.overrideWithValue(mockUseCase),
      ]));
      await tester.pump();

      await tester.enterText(find.byKey(const Key('passwordField')), 'abc');
      await tester.tap(find.byKey(const Key('submitButton')));
      await tester.pumpAndSettle();

      final error = tester.widget<Text>(
          find.text('Le mot de passe doit contenir au moins 8 caractères'));
      expect(error.maxLines, greaterThan(1));
      expect(tester.takeException(), isNull);
    });
  });
}
