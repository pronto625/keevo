import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/domain/model/store_type.dart';
import 'package:keevo/features/stores/domain/repository/store_repository.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';
import 'package:keevo/features/team/domain/model/employee_model.dart';
import 'package:keevo/features/team/domain/repository/employee_repository.dart';
import 'package:keevo/features/team/presentation/provider/employee_provider.dart';
import 'package:keevo/features/team/presentation/widget/employee_card.dart';
import 'package:keevo/features/team/presentation/widget/set_password_bottom_sheet.dart';

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

  EmployeeModel employee({
    String firstName = 'Jean',
    String lastName = 'Mbida',
    String status = 'ACTIVE',
  }) =>
      EmployeeModel(
        id: 'emp-1',
        userId: 'u-1',
        firstName: firstName,
        lastName: lastName,
        storeId: 'store-1',
        status: status,
        passwordChangeRequired: false,
        createdAt: now,
      );

  setUp(() {
    empRepo = _MockEmployeeRepo();
    storeRepo = _MockStoreRepo();
    when(() => storeRepo.getStores()).thenAnswer((_) async => [storeA, storeB]);
    when(() => storeRepo.syncFromRemote()).thenAnswer((_) async {});
  });

  List<Override> overrides({String role = 'OWNER', String userId = 'u-owner'}) =>
      [
        employeeRepositoryProvider.overrideWithValue(empRepo),
        storeRepositoryProvider.overrideWithValue(storeRepo),
        currentUserRoleProvider.overrideWithValue(role),
        currentUserIdProvider.overrideWith((ref) async => userId),
      ];

  /// The action sheet now has up to six rows: use a phone-tall surface.
  Future<void> pumpCard(WidgetTester tester, Widget w) async {
    await tester.binding.setSurfaceSize(const Size(800, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(w);
  }

  Widget build(EmployeeModel e,
          {VoidCallback? onDone, String role = 'OWNER', String userId = 'u-owner'}) =>
      ProviderScope(
        overrides: overrides(role: role, userId: userId),
        child: MaterialApp(
          home: Scaffold(
            body: EmployeeCard(employee: e, onActionComplete: onDone ?? () {}),
          ),
        ),
      );

  testWidgets('empty names do not crash the avatar', (tester) async {
    await pumpCard(tester, build(employee(firstName: '', lastName: '')));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.byType(EmployeeCard), findsOneWidget);
  });

  testWidgets('stores load error does not show "Chargement..." forever',
      (tester) async {
    when(() => storeRepo.getStores()).thenThrow(Exception('db'));
    await pumpCard(tester, build(employee()));
    await tester.pumpAndSettle();

    expect(find.text('Chargement...'), findsNothing);
    expect(find.text('Boutique inconnue'), findsOneWidget);
  });

  testWidgets('deactivate failure shows an error and does not report success',
      (tester) async {
    var done = 0;
    when(() => empRepo.deactivateEmployee(any()))
        .thenThrow(Exception('Accès refusé'));
    await pumpCard(tester, build(employee(), onDone: () => done++));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(EmployeeCard));
    await tester.pumpAndSettle();
    await tester.tap(find.text("Désactiver l'accès"));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Désactiver'));
    await tester.pumpAndSettle();

    expect(done, 0);
    expect(find.text('Accès refusé'), findsOneWidget);
  });

  testWidgets('reactivate failure shows an error and does not report success',
      (tester) async {
    var done = 0;
    when(() => empRepo.reactivateEmployee(any()))
        .thenThrow(Exception('Accès refusé'));
    await pumpCard(tester, 
        build(employee(status: 'INACTIVE'), onDone: () => done++));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(EmployeeCard));
    await tester.pumpAndSettle();
    await tester.tap(find.text("Réactiver l'accès"));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Réactiver'));
    await tester.pumpAndSettle();

    expect(done, 0);
    expect(find.text('Accès refusé'), findsOneWidget);
  });

  testWidgets('reassign failure shows an error and does not report success',
      (tester) async {
    var done = 0;
    when(() => empRepo.reassignStore(any(), any()))
        .thenThrow(Exception('Accès refusé'));
    await pumpCard(tester, build(employee(), onDone: () => done++));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(EmployeeCard));
    await tester.pumpAndSettle();
    await tester.tap(find.text("Modifier l'assignation"));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(DropdownButtonFormField<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Boutique B').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Confirmer'));
    await tester.pumpAndSettle();

    expect(done, 0);
    expect(find.text('Accès refusé'), findsOneWidget);
  });

  // ── Story 14.11 — edit + set password entries ───────────────────────────

  testWidgets('owner sees the edit and set-password entries', (tester) async {
    await pumpCard(tester, build(employee()));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(EmployeeCard));
    await tester.pumpAndSettle();

    expect(find.text('Modifier les informations'), findsOneWidget);
    expect(find.text('Définir le mot de passe'), findsOneWidget);
    // The existing regeneration entry is untouched.
    expect(find.text('Regénérer le mot de passe'), findsOneWidget);
  });

  testWidgets('an employee sees neither entry', (tester) async {
    await pumpCard(tester, build(employee(), role: 'EMPLOYEE'));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(EmployeeCard));
    await tester.pumpAndSettle();

    expect(find.text('Modifier les informations'), findsNothing);
    expect(find.text('Définir le mot de passe'), findsNothing);
  });

  testWidgets('set-password is hidden on one\'s own card', (tester) async {
    await pumpCard(tester, build(employee(), userId: 'u-1'));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(EmployeeCard));
    await tester.pumpAndSettle();

    expect(find.text('Modifier les informations'), findsOneWidget);
    expect(find.text('Définir le mot de passe'), findsNothing);
  });

  testWidgets('set-password opens the mini-form', (tester) async {
    await pumpCard(tester, build(employee()));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(EmployeeCard));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Définir le mot de passe'));
    await tester.pumpAndSettle();

    expect(find.byType(SetPasswordSheet), findsOneWidget);
  });

  testWidgets('edit entry opens the edit route with the employee, '
      'and a true result refreshes the list', (tester) async {
    var done = 0;
    EmployeeModel? received;
    final router = GoRouter(initialLocation: '/team', routes: [
      GoRoute(
        path: '/team',
        builder: (_, __) => Scaffold(
          body: EmployeeCard(employee: employee(), onActionComplete: () => done++),
        ),
      ),
      GoRoute(
        path: '/settings/team/:employeeId/edit',
        builder: (_, state) {
          received = state.extra as EmployeeModel?;
          return Scaffold(
            body: Builder(
              builder: (ctx) => TextButton(
                onPressed: () => ctx.pop(true),
                child: const Text('fermer'),
              ),
            ),
          );
        },
      ),
    ]);
    await pumpCard(tester, ProviderScope(
      overrides: overrides(),
      child: MaterialApp.router(routerConfig: router),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(EmployeeCard));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Modifier les informations'));
    await tester.pumpAndSettle();

    expect(received?.id, 'emp-1');
    expect(done, 0);

    await tester.tap(find.text('fermer'));
    await tester.pumpAndSettle();

    expect(done, 1);
  });
}
