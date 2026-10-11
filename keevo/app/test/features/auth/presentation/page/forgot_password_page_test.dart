import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/auth/domain/repository/auth_repository.dart';
import 'package:keevo/features/auth/presentation/page/forgot_password_page.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';

class _MockAuthRepository extends Mock implements AuthRepository {}

Future<void> _enterPhone(WidgetTester tester, String number) async {
  await tester.enterText(
    find.descendant(
      of: find.byKey(const Key('forgotPhoneField')),
      matching: find.byType(TextField),
    ),
    number,
  );
  await tester.pump();
}

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  late _MockAuthRepository repo;

  setUp(() => repo = _MockAuthRepository());

  Widget page() => ProviderScope(
        overrides: [authRepositoryProvider.overrideWithValue(repo)],
        child: MaterialApp.router(
          routerConfig: GoRouter(routes: [
            GoRoute(path: '/', builder: (_, __) => const ForgotPasswordPage()),
            GoRoute(
              path: '/auth/reset-password',
              builder: (_, __) => const Scaffold(body: Text('Reset')),
            ),
          ]),
        ),
      );

  testWidgets('a partial number sends no code request', (tester) async {
    await tester.pumpWidget(page());
    await _enterPhone(tester, '6');

    await tester.tap(find.byKey(const Key('sendOtpButton')));
    await tester.pump();

    verifyNever(() => repo.forgotPassword(phoneNumber: any(named: 'phoneNumber')));
  });

  testWidgets('after a network error the user can retry right away', (tester) async {
    when(() => repo.forgotPassword(phoneNumber: any(named: 'phoneNumber')))
        .thenThrow(Exception('offline'));
    await tester.pumpWidget(page());
    await _enterPhone(tester, '600000001');

    await tester.tap(find.byKey(const Key('sendOtpButton')));
    await tester.pump();
    await tester.pump();
    await tester.tap(find.byKey(const Key('sendOtpButton')));
    await tester.pump();
    await tester.pump();

    verify(() => repo.forgotPassword(phoneNumber: '+237600000001')).called(2);
    await tester.pump(const Duration(seconds: 61));
  });
}
