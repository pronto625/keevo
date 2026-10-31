import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/auth/domain/exception/auth_exception.dart';
import 'package:keevo/features/auth/domain/repository/auth_repository.dart';
import 'package:keevo/features/auth/presentation/page/reset_password_page.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';

class _MockAuthRepository extends Mock implements AuthRepository {}

/// Opens the page with a mocked repository, fills a valid form and submits.
Future<void> _submitForm(WidgetTester tester, AuthRepository repo) async {
  final router = GoRouter(initialLocation: '/reset', routes: [
    GoRoute(
      path: '/reset',
      builder: (_, __) => const ResetPasswordPage(phoneNumber: '+237600000001'),
    ),
    GoRoute(
      path: '/auth/login',
      builder: (_, __) => const Scaffold(body: Text('Login Page')),
    ),
  ]);
  await tester.pumpWidget(ProviderScope(
    retry: noProviderRetry,
    overrides: [authRepositoryProvider.overrideWithValue(repo)],
    child: MaterialApp.router(routerConfig: router),
  ));
  await tester.pumpAndSettle();

  final fields = find.byType(TextFormField);
  await tester.enterText(fields.at(0), '123 456');
  await tester.enterText(fields.at(1), 'secret123');
  await tester.enterText(fields.at(2), 'secret123');
  await tester.ensureVisible(find.text('Réinitialiser'));
  await tester.tap(find.text('Réinitialiser'));
  await tester.pumpAndSettle();
}

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  // v1s-15-8: the notifier never throws (AsyncValue.guard) — a refused code
  // used to show the success message and leave for the login page.
  testWidgets('a refused code shows its message and stays on the page',
      (tester) async {
    final repo = _MockAuthRepository();
    when(() => repo.resetPassword(
          phoneNumber: any(named: 'phoneNumber'),
          code: any(named: 'code'),
          newPassword: any(named: 'newPassword'),
        )).thenThrow(const AuthException(
            domainCode: 'INVALID_OR_EXPIRED_CODE', message: 'bad code'));

    await _submitForm(tester, repo);

    expect(find.text('Code invalide ou expiré'), findsOneWidget);
    expect(find.text('Mot de passe réinitialisé, connectez-vous.'),
        findsNothing);
    expect(find.text('Login Page'), findsNothing);
    expect(find.byType(ResetPasswordPage), findsOneWidget);
  });

  testWidgets('an unexpected error shows the generic message', (tester) async {
    final repo = _MockAuthRepository();
    when(() => repo.resetPassword(
          phoneNumber: any(named: 'phoneNumber'),
          code: any(named: 'code'),
          newPassword: any(named: 'newPassword'),
        )).thenThrow(StateError('boom'));

    await _submitForm(tester, repo);

    expect(find.text('Une erreur est survenue, veuillez réessayer'),
        findsOneWidget);
    expect(find.text('Login Page'), findsNothing);
  });

  testWidgets('success sends the stripped code and goes to the login page',
      (tester) async {
    final repo = _MockAuthRepository();
    when(() => repo.resetPassword(
          phoneNumber: any(named: 'phoneNumber'),
          code: any(named: 'code'),
          newPassword: any(named: 'newPassword'),
        )).thenAnswer((_) async {});

    await _submitForm(tester, repo);

    verify(() => repo.resetPassword(
          phoneNumber: '+237600000001',
          code: '123456',
          newPassword: 'secret123',
        )).called(1);
    expect(find.text('Login Page'), findsOneWidget);
  });

  testWidgets('opens from the forgot-password flow without a framework error',
      (tester) async {
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (_, __) => const Scaffold()),
      GoRoute(
        path: '/auth/reset-password',
        builder: (_, state) => ResetPasswordPage(
          phoneNumber: (state.extra as Map<String, dynamic>?)?['phoneNumber']
              as String?,
        ),
      ),
    ]);
    await tester.pumpWidget(
      ProviderScope(retry: noProviderRetry, child: MaterialApp.router(routerConfig: router)),
    );

    router.push('/auth/reset-password', extra: {'phoneNumber': '+237600000001'});
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.byType(ResetPasswordPage), findsOneWidget);
  });
}
