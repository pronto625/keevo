import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/auth/domain/exception/auth_exception.dart';
import 'package:keevo/features/auth/domain/model/auth_tokens.dart';
import 'package:keevo/features/auth/domain/usecase/login_usecase.dart';
import 'package:keevo/features/auth/presentation/page/login_page.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';

// ── Mocks ─────────────────────────────────────────────────────────────────

class MockLoginUseCase extends Mock implements LoginUseCase {}

// ── Helpers ───────────────────────────────────────────────────────────────

Widget _wrap(Widget child, {List<Override> overrides = const []}) {
  return ProviderScope(
    overrides: overrides,
    child: MaterialApp.router(
      routerConfig: GoRouter(
        routes: [GoRoute(path: '/', builder: (_, __) => child)],
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

  setUp(() {
    mockUseCase = MockLoginUseCase();
  });

  Widget buildPage() => _wrap(
        const LoginPage(),
        overrides: [
          loginUseCaseProvider.overrideWithValue(mockUseCase),
        ],
      );

  group('LoginPage', () {
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
          )).thenAnswer((_) async => const AuthTokens(
            accessToken: 'token',
            refreshToken: 'refresh',
            userId: 'uid',
            tenantId: 'kv_abc',
            role: 'OWNER',
            expiresIn: 86400,
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

    testWidgets('shows loading indicator while login is in progress',
        (tester) async {
      // Use a Completer so no pending fake timers are left when the test ends
      final completer = Completer<AuthTokens>();
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
  });
}
