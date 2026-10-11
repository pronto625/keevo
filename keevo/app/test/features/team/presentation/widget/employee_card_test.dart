import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/domain/model/store_type.dart';
import 'package:keevo/features/stores/domain/repository/store_repository.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';
import 'package:keevo/features/team/domain/model/employee_model.dart';
import 'package:keevo/features/team/domain/repository/employee_repository.dart';
import 'package:keevo/features/team/presentation/provider/employee_provider.dart';
import 'package:keevo/features/team/presentation/widget/employee_card.dart';

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

  Widget build(EmployeeModel e, {VoidCallback? onDone}) => ProviderScope(
        overrides: [
          employeeRepositoryProvider.overrideWithValue(empRepo),
          storeRepositoryProvider.overrideWithValue(storeRepo),
        ],
        child: MaterialApp(
          home: Scaffold(
            body: EmployeeCard(employee: e, onActionComplete: onDone ?? () {}),
          ),
        ),
      );

  testWidgets('empty names do not crash the avatar', (tester) async {
    await tester.pumpWidget(build(employee(firstName: '', lastName: '')));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.byType(EmployeeCard), findsOneWidget);
  });

  testWidgets('stores load error does not show "Chargement..." forever',
      (tester) async {
    when(() => storeRepo.getStores()).thenThrow(Exception('db'));
    await tester.pumpWidget(build(employee()));
    await tester.pumpAndSettle();

    expect(find.text('Chargement...'), findsNothing);
    expect(find.text('Boutique inconnue'), findsOneWidget);
  });

  testWidgets('deactivate failure shows an error and does not report success',
      (tester) async {
    var done = 0;
    when(() => empRepo.deactivateEmployee(any()))
        .thenThrow(Exception('Accès refusé'));
    await tester.pumpWidget(build(employee(), onDone: () => done++));
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
    await tester.pumpWidget(
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
    await tester.pumpWidget(build(employee(), onDone: () => done++));
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
}
