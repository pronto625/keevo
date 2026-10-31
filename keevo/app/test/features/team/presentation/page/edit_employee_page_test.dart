// Story 14.11 — EditEmployeePage (AC6).
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:intl_phone_field/intl_phone_field.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/domain/model/store_type.dart';
import 'package:keevo/features/stores/domain/repository/store_repository.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';
import 'package:keevo/features/team/domain/exception/employee_action_requires_connection_exception.dart';
import 'package:keevo/features/team/domain/model/employee_model.dart';
import 'package:keevo/features/team/domain/repository/employee_repository.dart';
import 'package:keevo/features/team/presentation/page/edit_employee_page.dart';
import 'package:keevo/features/team/presentation/provider/employee_provider.dart';

class _MockEmployeeRepo extends Mock implements EmployeeRepository {}

class _MockStoreRepo extends Mock implements StoreRepository {}

void main() {
  late _MockEmployeeRepo empRepo;
  late _MockStoreRepo storeRepo;
  final now = DateTime(2025, 6, 1);
  final storeA = StoreModel(
      id: 'store-1', name: 'Boutique A', type: StoreType.store,
      createdAt: now, updatedAt: now);
  final storeB = StoreModel(
      id: 'store-2', name: 'Boutique B', type: StoreType.store,
      createdAt: now, updatedAt: now);
  final closed = StoreModel(
      id: 'store-3', name: 'Boutique fermée', type: StoreType.store,
      isActive: false, createdAt: now, updatedAt: now);

  final employee = EmployeeModel(
    id: 'emp-1',
    userId: 'u-emp',
    firstName: 'Jean',
    lastName: 'Mbida',
    storeId: 'store-1',
    status: 'ACTIVE',
    passwordChangeRequired: false,
    createdAt: now,
    phoneNumber: '+237699000000',
  );

  setUp(() {
    empRepo = _MockEmployeeRepo();
    storeRepo = _MockStoreRepo();
    when(() => storeRepo.getStores())
        .thenAnswer((_) async => [storeA, storeB, closed]);
    when(() => storeRepo.syncFromRemote()).thenAnswer((_) async {});
  });

  Future<void> pump(WidgetTester tester,
      {EmployeeModel? target, String currentUserId = 'u-owner'}) async {
    await tester.binding.setSurfaceSize(const Size(800, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final router = GoRouter(initialLocation: '/edit', routes: [
      GoRoute(
          path: '/edit',
          builder: (_, __) => EditEmployeePage(employee: target ?? employee)),
      GoRoute(
          path: '/settings/team',
          builder: (_, __) => const Scaffold(body: Text('liste équipe'))),
    ]);
    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [
        employeeRepositoryProvider.overrideWithValue(empRepo),
        storeRepositoryProvider.overrideWithValue(storeRepo),
        currentUserIdProvider.overrideWith((ref) async => currentUserId),
      ],
      child: MaterialApp.router(routerConfig: router),
    ));
    await tester.pumpAndSettle();
  }

  Finder storeDropdown() => find.byType(DropdownButtonFormField<String>).at(0);
  Finder roleDropdown() => find.byType(DropdownButtonFormField<String>).at(1);

  void stubUpdate({Object? error}) {
    final call = when(() => empRepo.updateEmployee(
          any(),
          firstName: any(named: 'firstName'),
          lastName: any(named: 'lastName'),
          phoneNumber: any(named: 'phoneNumber'),
          storeId: any(named: 'storeId'),
        ));
    if (error != null) {
      call.thenThrow(error);
    } else {
      call.thenAnswer((inv) async => employee.copyWith(
            firstName: inv.namedArguments[#firstName] as String? ??
                employee.firstName,
          ));
    }
  }

  group('splitE164', () {
    test('splits on the longest matching dial code', () {
      final cm = splitE164('+237699123456');
      expect(cm?.country.code, 'CM');
      expect(cm?.national, '699123456');
      final fr = splitE164('+33612345678');
      expect(fr?.country.code, 'FR');
      expect(fr?.national, '612345678');
    });

    test('null or empty input gives null', () {
      expect(splitE164(null), isNull);
      expect(splitE164('+'), isNull);
    });
  });

  testWidgets('form is pre-filled from the employee', (tester) async {
    await pump(tester);

    expect(find.widgetWithText(TextFormField, 'Jean'), findsOneWidget);
    expect(find.widgetWithText(TextFormField, 'Mbida'), findsOneWidget);
    // E.164 split into country (CM) + national part.
    expect(find.widgetWithText(TextField, '699000000'), findsOneWidget);
    expect(find.text('Boutique A'), findsOneWidget);
    expect(find.text('Employé'), findsOneWidget);
    expect(find.text('Sécurité'), findsOneWidget);
    expect(find.text('Définir le mot de passe'), findsOneWidget);
  });

  testWidgets('inactive stores are not offered', (tester) async {
    await pump(tester);

    await tester.tap(storeDropdown());
    await tester.pumpAndSettle();

    expect(find.text('Boutique B'), findsOneWidget);
    expect(find.text('Boutique fermée'), findsNothing);
  });

  testWidgets('nothing changed — nothing is sent', (tester) async {
    await pump(tester);

    await tester.tap(find.text('Enregistrer'));
    await tester.pumpAndSettle();

    expect(find.text('Aucune modification à enregistrer'), findsOneWidget);
    verifyNever(() => empRepo.updateEmployee(any(),
        firstName: any(named: 'firstName'),
        lastName: any(named: 'lastName'),
        phoneNumber: any(named: 'phoneNumber'),
        storeId: any(named: 'storeId')));
    verifyNever(() => empRepo.changeRole(any(), any()));
  });

  testWidgets('only the changed fields are sent, then the page closes',
      (tester) async {
    stubUpdate();
    await pump(tester);

    await tester.enterText(find.byType(TextFormField).at(0), 'Paul');
    await tester.tap(find.text('Enregistrer'));
    await tester.pumpAndSettle();

    verify(() => empRepo.updateEmployee('emp-1',
        firstName: 'Paul',
        lastName: null,
        phoneNumber: null,
        storeId: null)).called(1);
    verifyNever(() => empRepo.changeRole(any(), any()));
    expect(find.text('liste équipe'), findsOneWidget);
  });

  testWidgets('a changed phone number is sent in E.164, store change included',
      (tester) async {
    stubUpdate();
    await pump(tester);

    await tester.enterText(
        find.descendant(
            of: find.byType(IntlPhoneField), matching: find.byType(TextField)),
        '699123456');
    await tester.tap(storeDropdown());
    await tester.pumpAndSettle();
    await tester.tap(find.text('Boutique B').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Enregistrer'));
    await tester.pumpAndSettle();

    verify(() => empRepo.updateEmployee('emp-1',
        firstName: null,
        lastName: null,
        phoneNumber: '+237699123456',
        storeId: 'store-2')).called(1);
  });

  testWidgets('an invalid typed phone number blocks the save', (tester) async {
    await pump(tester);

    await tester.enterText(
        find.descendant(
            of: find.byType(IntlPhoneField), matching: find.byType(TextField)),
        '12');
    await tester.tap(find.text('Enregistrer'));
    await tester.pumpAndSettle();

    expect(find.text('Numéro invalide pour ce pays'), findsOneWidget);
    verifyNever(() => empRepo.updateEmployee(any(),
        firstName: any(named: 'firstName'),
        lastName: any(named: 'lastName'),
        phoneNumber: any(named: 'phoneNumber'),
        storeId: any(named: 'storeId')));
  });

  testWidgets('role change asks for confirmation, then calls changeRole',
      (tester) async {
    when(() => empRepo.changeRole(any(), any())).thenAnswer((_) async {});
    await pump(tester);

    await tester.tap(roleDropdown());
    await tester.pumpAndSettle();
    await tester.tap(find.text('Propriétaire').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Enregistrer'));
    await tester.pumpAndSettle();

    expect(find.text('Changer le rôle ?'), findsOneWidget);
    expect(find.textContaining('sera déconnecté(e)'), findsOneWidget);
    verifyNever(() => empRepo.changeRole(any(), any()));

    await tester.tap(find.text('Confirmer'));
    await tester.pumpAndSettle();

    verify(() => empRepo.changeRole('emp-1', 'OWNER')).called(1);
    verifyNever(() => empRepo.updateEmployee(any(),
        firstName: any(named: 'firstName'),
        lastName: any(named: 'lastName'),
        phoneNumber: any(named: 'phoneNumber'),
        storeId: any(named: 'storeId')));
    expect(find.text('liste équipe'), findsOneWidget);
  });

  // J31 (device test): after a promotion, setting « Employé » back showed
  // « Une erreur inattendue… » and sent nothing.
  testWidgets('J31: a promoted owner can be set back to Employé',
      (tester) async {
    when(() => empRepo.changeRole(any(), any())).thenAnswer((_) async {});
    await pump(tester, target: employee.copyWith(role: 'OWNER'));

    await tester.tap(roleDropdown());
    await tester.pumpAndSettle();
    await tester.tap(find.text('Employé').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Enregistrer'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Confirmer'));
    await tester.pumpAndSettle();

    expect(find.textContaining('erreur inattendue'), findsNothing);
    verify(() => empRepo.changeRole('emp-1', 'EMPLOYEE')).called(1);
    expect(find.text('liste équipe'), findsOneWidget);
  });

  testWidgets('J31: an unexpected failure says the role was not changed',
      (tester) async {
    when(() => empRepo.changeRole(any(), any()))
        .thenThrow(StateError('boom'));
    await pump(tester, target: employee.copyWith(role: 'OWNER'));

    await tester.tap(roleDropdown());
    await tester.pumpAndSettle();
    await tester.tap(find.text('Employé').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Enregistrer'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Confirmer'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Le rôle n\'a pas pu être modifié'),
        findsOneWidget);
    expect(find.textContaining('erreur inattendue'), findsNothing);
    expect(find.text('liste équipe'), findsNothing);
  });

  testWidgets('cancelling the role confirmation sends nothing',
      (tester) async {
    await pump(tester);

    await tester.tap(roleDropdown());
    await tester.pumpAndSettle();
    await tester.tap(find.text('Propriétaire').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Enregistrer'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Annuler'));
    await tester.pumpAndSettle();

    verifyNever(() => empRepo.changeRole(any(), any()));
    expect(find.byType(EditEmployeePage), findsOneWidget);
  });

  testWidgets('editing oneself: role locked, no security section',
      (tester) async {
    await pump(tester, currentUserId: 'u-emp');

    final role = tester.widget<DropdownButtonFormField<String>>(roleDropdown());
    expect(role.onChanged, isNull);
    expect(find.text('Vous ne pouvez pas modifier votre propre rôle'),
        findsOneWidget);
    expect(find.text('Sécurité'), findsNothing);
    expect(find.text('Définir le mot de passe'), findsNothing);
  });

  testWidgets('a server refusal is shown and the page stays open',
      (tester) async {
    stubUpdate(
      error: DioException(
        requestOptions: RequestOptions(path: '/x'),
        type: DioExceptionType.badResponse,
        response: Response(
          requestOptions: RequestOptions(path: '/x'),
          statusCode: 409,
          data: {
            'domainCode': 'PHONE_ALREADY_REGISTERED',
            'error': 'Ce numéro de téléphone est déjà utilisé par un autre compte',
          },
        ),
      ),
    );
    await pump(tester);

    await tester.enterText(find.byType(TextFormField).at(1), 'Martin');
    await tester.tap(find.text('Enregistrer'));
    await tester.pumpAndSettle();

    expect(
        find.text('Ce numéro de téléphone est déjà utilisé par un autre compte'),
        findsOneWidget);
    expect(find.byType(EditEmployeePage), findsOneWidget);
  });

  testWidgets('offline edit tells the user a connection is needed',
      (tester) async {
    stubUpdate(error: const EmployeeActionRequiresConnectionException());
    await pump(tester);

    await tester.enterText(find.byType(TextFormField).at(1), 'Martin');
    await tester.tap(find.text('Enregistrer'));
    await tester.pumpAndSettle();

    expect(find.text('Cette action nécessite une connexion internet.'),
        findsOneWidget);
    expect(find.byType(EditEmployeePage), findsOneWidget);
  });
}
