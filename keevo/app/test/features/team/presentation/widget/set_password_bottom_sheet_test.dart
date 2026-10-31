// Story 14.11 — "Définir le mot de passe" mini-form (AC6).
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/team/domain/model/employee_model.dart';
import 'package:keevo/features/team/domain/repository/employee_repository.dart';
import 'package:keevo/features/team/presentation/provider/employee_provider.dart';
import 'package:keevo/features/team/presentation/widget/set_password_bottom_sheet.dart';

class _MockEmployeeRepo extends Mock implements EmployeeRepository {}

void main() {
  late _MockEmployeeRepo empRepo;
  final employee = EmployeeModel(
    id: 'emp-1',
    userId: 'u-emp',
    firstName: 'Jean',
    lastName: 'Mbida',
    storeId: 'store-1',
    status: 'ACTIVE',
    passwordChangeRequired: false,
    createdAt: DateTime(2025, 6, 1),
  );

  setUp(() => empRepo = _MockEmployeeRepo());

  /// Opens the sheet from a button; the returned getter reads what the sheet
  /// resolved to once it closed.
  Future<bool? Function()> pump(WidgetTester tester) async {
    bool? result;
    await tester.binding.setSurfaceSize(const Size(800, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [employeeRepositoryProvider.overrideWithValue(empRepo)],
      child: MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (ctx) => TextButton(
              onPressed: () async {
                result = await showSetPasswordBottomSheet(
                    context: ctx, employee: employee);
              },
              child: const Text('ouvrir'),
            ),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('ouvrir'));
    await tester.pumpAndSettle();
    return () => result;
  }

  group('validateNewPassword', () {
    test('mirrors the backend rule (≥ 8 chars, ≥ 1 digit)', () {
      expect(validateNewPassword('abc1'), 'Au moins 8 caractères');
      expect(validateNewPassword('abcdefgh'), 'Au moins un chiffre');
      expect(validateNewPassword('abcdefg1'), isNull);
    });
  });

  testWidgets('a weak or mismatched password is refused client-side',
      (tester) async {
    await pump(tester);

    await tester.enterText(find.byType(TextFormField).at(0), 'abcdefgh');
    await tester.enterText(find.byType(TextFormField).at(1), 'other');
    await tester.tap(find.text('Enregistrer le mot de passe'));
    await tester.pumpAndSettle();

    expect(find.text('Au moins un chiffre'), findsOneWidget);
    expect(find.text('Les mots de passe ne correspondent pas'), findsOneWidget);
    verifyNever(() => empRepo.setPassword(any(), any()));
  });

  testWidgets('confirmation warns about the logout before setting',
      (tester) async {
    when(() => empRepo.setPassword(any(), any())).thenAnswer((_) async {});
    final result = await pump(tester);

    await tester.enterText(find.byType(TextFormField).at(0), 'Secret123');
    await tester.enterText(find.byType(TextFormField).at(1), 'Secret123');
    await tester.tap(find.text('Enregistrer le mot de passe'));
    await tester.pumpAndSettle();

    expect(find.text('Définir le mot de passe ?'), findsOneWidget);
    expect(find.textContaining('sera déconnecté(e)'), findsOneWidget);
    verifyNever(() => empRepo.setPassword(any(), any()));

    await tester.tap(find.text('Confirmer'));
    await tester.pumpAndSettle();

    verify(() => empRepo.setPassword('emp-1', 'Secret123')).called(1);
    expect(find.byType(SetPasswordSheet), findsNothing);
    expect(result(), isTrue);
  });

  testWidgets('cancelling the confirmation sends nothing', (tester) async {
    await pump(tester);

    await tester.enterText(find.byType(TextFormField).at(0), 'Secret123');
    await tester.enterText(find.byType(TextFormField).at(1), 'Secret123');
    await tester.tap(find.text('Enregistrer le mot de passe'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Annuler'));
    await tester.pumpAndSettle();

    verifyNever(() => empRepo.setPassword(any(), any()));
    expect(find.byType(SetPasswordSheet), findsOneWidget);
  });

  testWidgets('a server refusal stays in the sheet with its message',
      (tester) async {
    when(() => empRepo.setPassword(any(), any())).thenThrow(DioException(
      requestOptions: RequestOptions(path: '/x'),
      type: DioExceptionType.badResponse,
      response: Response(
        requestOptions: RequestOptions(path: '/x'),
        statusCode: 403,
        data: {'error': 'Vous ne pouvez pas définir votre propre mot de passe'},
      ),
    ));
    await pump(tester);

    await tester.enterText(find.byType(TextFormField).at(0), 'Secret123');
    await tester.enterText(find.byType(TextFormField).at(1), 'Secret123');
    await tester.tap(find.text('Enregistrer le mot de passe'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Confirmer'));
    await tester.pumpAndSettle();

    expect(find.text('Vous ne pouvez pas définir votre propre mot de passe'),
        findsOneWidget);
    expect(find.byType(SetPasswordSheet), findsOneWidget);
  });
}
