import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/features/auth/domain/exception/auth_exception.dart';
import 'package:keevo/features/auth/domain/model/auth_tokens.dart';
import 'package:keevo/features/auth/domain/model/login_result.dart';
import 'package:keevo/features/auth/domain/usecase/login_usecase.dart';
import 'package:keevo/features/auth/presentation/page/auth_page.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

// ── Mocks ─────────────────────────────────────────────────────────────────

class MockLoginUseCase extends Mock implements LoginUseCase {}

// ── Helpers ───────────────────────────────────────────────────────────────

Widget _wrap(Widget child, {List<Override> overrides = const []}) {
  return ProviderScope(
    retry: noProviderRetry,
    overrides: overrides,
    child: MaterialApp.router(
      routerConfig: GoRouter(
        routes: [
          GoRoute(path: '/', builder: (_, __) => child),
          GoRoute(
            path: '/splash',
            builder: (_, __) => const Scaffold(body: Text('Splash')),
          ),
        ],
      ),
    ),
  );
}

/// Enters a valid Cameroonian phone number into the IntlPhoneField internal
/// TextField to trigger [onChanged] and set _phoneValid = true.
Future<void> _enterPhone(WidgetTester tester, String number) async {
  final phoneInternalField = find.descendant(
    of: find.byKey(const Key('phoneField')),
    matching: find.byType(TextField),
  );
  await tester.enterText(phoneInternalField, number);
  await tester.pump();
}

void main() {
  late MockLoginUseCase mockUseCase;
  late SharedPreferences prefs;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    mockUseCase = MockLoginUseCase();
  });

  Widget buildPage() => _wrap(
        const AuthPage(initialMode: AuthMode.login),
        overrides: [
          sharedPreferencesProvider.overrideWithValue(prefs),
          loginUseCaseProvider.overrideWithValue(mockUseCase),
        ],
      );

  group('AuthPage — Login Mode', () {
    testWidgets('renders phone field and password field', (tester) async {
      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      // IntlPhoneField wraps a TextFormField internally → 2 total
      expect(find.byType(TextFormField), findsNWidgets(2));
      expect(find.byType(Form), findsOneWidget);
      expect(find.byKey(const Key('passwordField')), findsOneWidget);
      expect(find.byKey(const Key('phoneField')), findsOneWidget);
    });

    testWidgets('shows error message on INVALID_CREDENTIALS', (tester) async {
      when(() => mockUseCase.execute(
            phoneNumber: any(named: 'phoneNumber'),
            password: any(named: 'password'),
          )).thenThrow(const AuthException(
        domainCode: 'INVALID_CREDENTIALS',
        message: 'Wrong credentials',
      ));

      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      await _enterPhone(tester, '600000001');
      await tester.enterText(find.byKey(const Key('passwordField')), 'wrongpass');
      await tester.tap(find.byType(FilledButton));
      await tester.pumpAndSettle();

      expect(
        find.textContaining('Numéro ou mot de passe incorrect'),
        findsOneWidget,
      );
    });

    testWidgets('calls use case with correct inputs on submit', (tester) async {
      when(() => mockUseCase.execute(
            phoneNumber: any(named: 'phoneNumber'),
            password: any(named: 'password'),
          )).thenAnswer((_) async => AuthenticatedResult(
            const AuthTokens(
              accessToken: 'token',
              refreshToken: 'refresh',
              userId: 'uid',
              tenantId: 'kv_abc',
              role: 'OWNER',
              expiresIn: 86400,
            ),
          ));

      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      await _enterPhone(tester, '600000001');
      await tester.enterText(find.byKey(const Key('passwordField')), 'SecurePass123!');
      await tester.tap(find.byType(FilledButton));
      await tester.pumpAndSettle();

      verify(() => mockUseCase.execute(
            phoneNumber: any(named: 'phoneNumber'),
            password: 'SecurePass123!',
          )).called(1);
      // The splash picks the destination (wizard, password change, role) —
      // an owner with an unfinished setup must not land on the dashboard.
      expect(find.text('Splash'), findsOneWidget);
    });

    testWidgets('shows lockout error message on ACCOUNT_LOCKED', (tester) async {
      when(() => mockUseCase.execute(
            phoneNumber: any(named: 'phoneNumber'),
            password: any(named: 'password'),
          )).thenThrow(const AuthException(
        domainCode: 'ACCOUNT_LOCKED',
        message: 'Compte verrouillé',
      ));

      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      await _enterPhone(tester, '600000001');
      await tester.enterText(find.byKey(const Key('passwordField')), 'anypass');
      await tester.tap(find.byType(FilledButton));
      await tester.pumpAndSettle();

      expect(
        find.textContaining('Compte verrouillé'),
        findsOneWidget,
      );
    });

    testWidgets(
        'platform super-admin account: refused with a clear message, stays on login',
        (tester) async {
      when(() => mockUseCase.execute(
            phoneNumber: any(named: 'phoneNumber'),
            password: any(named: 'password'),
          )).thenThrow(const AuthException(
        domainCode: 'PLATFORM_ADMIN_ACCOUNT',
        message: 'Platform admin account',
      ));

      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      await _enterPhone(tester, '600000001');
      await tester.enterText(find.byKey(const Key('passwordField')), 'anypass');
      await tester.tap(find.byType(FilledButton));
      await tester.pumpAndSettle();

      expect(find.text(kPlatformAdminAccountMessage), findsOneWidget);
      expect(find.text('Splash'), findsNothing);
      expect(find.byKey(const Key('passwordField')), findsOneWidget);
    });

    testWidgets('shows loading indicator while login is in progress',
        (tester) async {
      // Use a Completer so no pending fake timers are left when the test ends
      final completer = Completer<LoginResult>();
      when(() => mockUseCase.execute(
            phoneNumber: any(named: 'phoneNumber'),
            password: any(named: 'password'),
          )).thenAnswer((_) => completer.future);

      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      await _enterPhone(tester, '600000001');
      await tester.enterText(find.byKey(const Key('passwordField')), 'SecurePass123!');
      await tester.tap(find.byType(FilledButton));
      await tester.pump(); // Process tap
      await tester.pump(); // Riverpod rebuilds with AsyncLoading

      expect(find.byType(CircularProgressIndicator), findsOneWidget);
    });
  
    testWidgets('a partial phone number is refused before calling the server',
        (tester) async {
      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      await _enterPhone(tester, '6');
      await tester.enterText(find.byKey(const Key('passwordField')), 'SecurePass123!');
      await tester.tap(find.byType(FilledButton));
      await tester.pumpAndSettle();

      expect(find.text('Numéro invalide pour ce pays'), findsOneWidget);
      verifyNever(() => mockUseCase.execute(
            phoneNumber: any(named: 'phoneNumber'),
            password: any(named: 'password'),
          ));
    });
  });
}
