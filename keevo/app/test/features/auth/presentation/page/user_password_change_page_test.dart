import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/auth/domain/exception/auth_exception.dart';
import 'package:keevo/features/auth/domain/model/auth_tokens.dart';
import 'package:keevo/features/auth/domain/usecase/change_password_usecase.dart';
import 'package:keevo/features/auth/presentation/page/user_password_change_page.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';

class _MockChangePasswordUseCase extends Mock implements ChangePasswordUseCase {}

Widget _buildPage(_MockChangePasswordUseCase useCase) {
  return ProviderScope(
    overrides: [
      changePasswordUseCaseProvider.overrideWithValue(useCase),
    ],
    child: MaterialApp.router(
      routerConfig: GoRouter(
        initialLocation: '/settings/change-password',
        routes: [
          GoRoute(
            path: '/settings',
            builder: (_, __) => const Scaffold(
              body: Center(child: Text('SettingsPage')),
            ),
            routes: [
              GoRoute(
                path: 'change-password',
                builder: (_, __) => const UserPasswordChangePage(),
              ),
            ],
          ),
        ],
      ),
    ),
  );
}

void main() {
  late _MockChangePasswordUseCase mockUseCase;

  setUp(() {
    mockUseCase = _MockChangePasswordUseCase();
  });

  group('UserPasswordChangePage (Story 8.6 AC5, AC6)', () {
    testWidgets('renders 3 fields and submit button', (tester) async {
      await tester.pumpWidget(_buildPage(mockUseCase));
      await tester.pumpAndSettle();

      expect(find.text('Mot de passe actuel'), findsOneWidget);
      expect(find.text('Nouveau mot de passe'), findsOneWidget);
      expect(find.text('Confirmer le nouveau mot de passe'), findsOneWidget);
      expect(find.text('Modifier le mot de passe'), findsOneWidget);
    });

    testWidgets('confirm mismatch → field 3 error', (tester) async {
      await tester.pumpWidget(_buildPage(mockUseCase));
      await tester.pumpAndSettle();

      final fields = find.byType(TextFormField);
      await tester.enterText(fields.at(0), 'CurrentPass1');
      await tester.enterText(fields.at(1), 'NewPass1234');
      await tester.enterText(fields.at(2), 'DifferentPass1');

      await tester.tap(find.text('Modifier le mot de passe'));
      await tester.pumpAndSettle();

      expect(find.text('Les mots de passe ne correspondent pas'), findsOneWidget);
    });

    testWidgets('new password too short → field 2 error', (tester) async {
      await tester.pumpWidget(_buildPage(mockUseCase));
      await tester.pumpAndSettle();

      final fields = find.byType(TextFormField);
      await tester.enterText(fields.at(0), 'CurrentPass1');
      await tester.enterText(fields.at(1), 'Ab1');
      await tester.enterText(fields.at(2), 'Ab1');

      await tester.tap(find.text('Modifier le mot de passe'));
      await tester.pumpAndSettle();

      expect(find.text('Min. 8 caractères requis'), findsOneWidget);
    });

    testWidgets('success → snackbar "Mot de passe modifié avec succès"',
        (tester) async {
      when(() => mockUseCase.execute(
            currentPassword: any(named: 'currentPassword'),
            newPassword: any(named: 'newPassword'),
          )).thenAnswer((_) async => const AuthTokens(
            accessToken: 'newAccess',
            refreshToken: 'newRefresh',
            userId: 'u1',
            tenantId: 't1',
            role: 'EMPLOYEE',
            expiresIn: 86400,
          ));

      await tester.pumpWidget(_buildPage(mockUseCase));
      await tester.pumpAndSettle();

      final fields = find.byType(TextFormField);
      await tester.enterText(fields.at(0), 'CurrentPass1');
      await tester.enterText(fields.at(1), 'NewPass1234');
      await tester.enterText(fields.at(2), 'NewPass1234');

      await tester.tap(find.text('Modifier le mot de passe'));
      await tester.pumpAndSettle();

      expect(find.text('Mot de passe modifié avec succès'), findsOneWidget);
    });

    testWidgets('INVALID_CREDENTIALS → error banner shown', (tester) async {
      when(() => mockUseCase.execute(
            currentPassword: any(named: 'currentPassword'),
            newPassword: any(named: 'newPassword'),
          )).thenThrow(const AuthException(
        domainCode: 'INVALID_CREDENTIALS',
        message: 'wrong password',
      ));

      await tester.pumpWidget(_buildPage(mockUseCase));
      await tester.pumpAndSettle();

      final fields = find.byType(TextFormField);
      await tester.enterText(fields.at(0), 'WrongPass1');
      await tester.enterText(fields.at(1), 'NewPass1234');
      await tester.enterText(fields.at(2), 'NewPass1234');

      await tester.tap(find.text('Modifier le mot de passe'));
      await tester.pumpAndSettle();

      expect(find.text('Mot de passe actuel incorrect'), findsOneWidget);
    });
  });
}
