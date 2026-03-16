import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/auth/domain/model/auth_tokens.dart';
import 'package:keevo/features/auth/domain/usecase/change_password_usecase.dart';
import 'package:keevo/features/auth/presentation/page/password_change_page.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';

class _MockChangePasswordUseCase extends Mock
    implements ChangePasswordUseCase {}

void main() {
  late _MockChangePasswordUseCase mockUseCase;

  setUp(() {
    mockUseCase = _MockChangePasswordUseCase();
  });

  Widget buildPage({List<Override> overrides = const []}) {
    return ProviderScope(
      overrides: [
        changePasswordUseCaseProvider.overrideWithValue(mockUseCase),
        ...overrides,
      ],
      child: MaterialApp.router(
        routerConfig: GoRouter(routes: [
          GoRoute(
              path: '/',
              builder: (_, __) => const PasswordChangePage()),
          GoRoute(
              path: '/pos', builder: (_, __) => const Scaffold()),
        ]),
      ),
    );
  }

  group('PasswordChangePage', () {
    testWidgets('renders current and new password fields', (tester) async {
      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      expect(find.text('Mot de passe temporaire'), findsOneWidget);
      expect(find.text('Nouveau mot de passe'), findsOneWidget);
      expect(find.text('Confirmer'), findsOneWidget);
    });

    testWidgets('shows validation error when new password too short',
        (tester) async {
      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      // Enter current password
      await tester.enterText(find.byType(TextFormField).first, 'tempPwd1');
      // Enter too-short new password
      await tester.enterText(find.byType(TextFormField).last, 'Ab1');
      await tester.tap(find.text('Confirmer'));
      await tester.pumpAndSettle();

      expect(find.text('Au moins 8 caractères'), findsOneWidget);
    });

    testWidgets('shows validation error when new password has no digit',
        (tester) async {
      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextFormField).first, 'tempPwd1');
      await tester.enterText(
          find.byType(TextFormField).last, 'abcdefgh');
      await tester.tap(find.text('Confirmer'));
      await tester.pumpAndSettle();

      expect(
          find.text('Doit contenir au moins un chiffre'), findsOneWidget);
    });

    testWidgets('calls use case with correct values on valid submit',
        (tester) async {
      when(() => mockUseCase.execute(
            currentPassword: any(named: 'currentPassword'),
            newPassword: any(named: 'newPassword'),
          )).thenAnswer((_) async => const AuthTokens(
            accessToken: 'new-token',
            refreshToken: 'new-refresh',
            userId: 'u1',
            tenantId: 't1',
            role: 'EMPLOYEE',
            expiresIn: 3600,
            storeId: 's1',
          ));

      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      await tester.enterText(
          find.byType(TextFormField).first, 'tempPwd1');
      await tester.enterText(
          find.byType(TextFormField).last, 'NewPass99');
      await tester.tap(find.text('Confirmer'));
      await tester.pumpAndSettle();

      verify(() => mockUseCase.execute(
            currentPassword: 'tempPwd1',
            newPassword: 'NewPass99',
          )).called(1);
    });

    testWidgets('cannot navigate back (canPop is false)', (tester) async {
      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      // No back button should exist
      expect(find.byType(BackButton), findsNothing);
      expect(find.byIcon(Icons.arrow_back), findsNothing);
    });
  });
}
