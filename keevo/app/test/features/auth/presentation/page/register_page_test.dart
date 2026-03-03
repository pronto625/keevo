import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/auth/domain/model/registration_result.dart';
import 'package:keevo/features/auth/domain/usecase/register_user_usecase.dart';
import 'package:keevo/features/auth/presentation/page/register_page.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';

// ── Mocks ────────────────────────────────────────────────────────────────────

class MockRegisterUserUseCase extends Mock implements RegisterUserUseCase {}

// ── Helpers ──────────────────────────────────────────────────────────────────

GoRouter _testRouter() {
  return GoRouter(
    initialLocation: '/auth/register',
    routes: [
      GoRoute(
        path: '/auth/register',
        builder: (_, __) => const RegisterPage(),
      ),
      GoRoute(
        path: '/onboarding',
        builder: (_, __) => const Scaffold(body: Text('Onboarding')),
      ),
      GoRoute(
        path: '/auth/login',
        builder: (_, __) => const Scaffold(body: Text('Login')),
      ),
    ],
  );
}

Widget _buildPage(List<Override> overrides) {
  return ProviderScope(
    overrides: overrides,
    child: MaterialApp.router(
      routerConfig: _testRouter(),
    ),
  );
}

void main() {
  setUpAll(() {
    GoogleFonts.config.allowRuntimeFetching = false;
  });

  late MockRegisterUserUseCase mockUseCase;

  setUp(() {
    mockUseCase = MockRegisterUserUseCase();
  });

  group('RegisterPage', () {
    testWidgets('renders phone/password fields and submit button',
        (tester) async {
      await tester.pumpWidget(_buildPage([
        registerUserUseCaseProvider.overrideWithValue(mockUseCase),
      ]));
      await tester.pump();

      expect(find.byKey(const Key('phoneField')), findsOneWidget);
      expect(find.byKey(const Key('passwordField')), findsOneWidget);
      expect(find.byKey(const Key('registerButton')), findsOneWidget);
    });

    testWidgets('shows validation error when form submitted empty',
        (tester) async {
      await tester.pumpWidget(_buildPage([
        registerUserUseCaseProvider.overrideWithValue(mockUseCase),
      ]));
      await tester.pump();

      await tester.tap(find.byKey(const Key('registerButton')));
      await tester.pump();

      expect(find.text('Le numéro de téléphone est requis'), findsOneWidget);
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
          find.byKey(const Key('phoneField')), '+237600000001');
      await tester.enterText(
          find.byKey(const Key('passwordField')), 'SecurePass123!');
      await tester.tap(find.byKey(const Key('registerButton')));
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

      await tester.enterText(
          find.byKey(const Key('phoneField')), '+237600000001');
      await tester.enterText(
          find.byKey(const Key('passwordField')), 'SecurePass123!');
      await tester.tap(find.byKey(const Key('registerButton')));
      await tester.pump();

      verify(() => mockUseCase.execute(
            phoneNumber: '+237600000001',
            password: 'SecurePass123!',
          )).called(1);
    });
  });
}
