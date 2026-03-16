import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/team/domain/model/employee_model.dart';
import 'package:keevo/features/team/domain/repository/employee_repository.dart';
import 'package:keevo/features/team/presentation/page/team_page.dart';
import 'package:keevo/features/team/presentation/provider/employee_provider.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/domain/model/store_type.dart';
import 'package:keevo/features/stores/domain/repository/store_repository.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';

class _MockEmployeeRepo extends Mock implements EmployeeRepository {}

class _MockStoreRepo extends Mock implements StoreRepository {}

void main() {
  late _MockEmployeeRepo mockEmpRepo;
  late _MockStoreRepo mockStoreRepo;

  final now = DateTime(2025, 6, 1);
  final store = StoreModel(
    id: 'store-1',
    name: 'Boutique A',
    type: StoreType.store,
    createdAt: now,
    updatedAt: now,
  );
  final activeEmployee = EmployeeModel(
    id: 'emp-1',
    userId: 'u-1',
    firstName: 'Jean',
    lastName: 'Mbida',
    storeId: 'store-1',
    status: 'ACTIVE',
    passwordChangeRequired: false,
    createdAt: now,
  );
  final pwdPendingEmployee = EmployeeModel(
    id: 'emp-2',
    userId: 'u-2',
    firstName: 'Marie',
    lastName: 'Nguema',
    storeId: 'store-1',
    status: 'ACTIVE',
    passwordChangeRequired: true,
    createdAt: now,
  );
  final inactiveEmployee = EmployeeModel(
    id: 'emp-3',
    userId: 'u-3',
    firstName: 'Paul',
    lastName: 'Nkomo',
    storeId: 'store-1',
    status: 'INACTIVE',
    passwordChangeRequired: false,
    createdAt: now,
  );

  setUp(() {
    mockEmpRepo = _MockEmployeeRepo();
    mockStoreRepo = _MockStoreRepo();

    when(() => mockStoreRepo.getStores()).thenAnswer((_) async => [store]);
    when(() => mockStoreRepo.getStores(includeInactive: false))
        .thenAnswer((_) async => [store]);
    when(() => mockStoreRepo.syncFromRemote()).thenAnswer((_) async {});
  });

  Widget buildPage({List<EmployeeModel> employees = const []}) {
    when(() => mockEmpRepo.listEmployees())
        .thenAnswer((_) async => employees);

    return ProviderScope(
      overrides: [
        employeeRepositoryProvider.overrideWithValue(mockEmpRepo),
        storeRepositoryProvider.overrideWithValue(mockStoreRepo),
      ],
      child: MaterialApp.router(
        routerConfig: GoRouter(routes: [
          GoRoute(path: '/', builder: (_, __) => const TeamPage()),
          GoRoute(
              path: '/settings/team/new',
              builder: (_, __) => const Scaffold()),
        ]),
      ),
    );
  }

  group('TeamPage', () {
    testWidgets('shows empty state when no employees', (tester) async {
      await tester.pumpWidget(buildPage(employees: []));
      await tester.pumpAndSettle();

      expect(find.text('Aucun employé'), findsOneWidget);
    });

    testWidgets('shows employee list when loaded', (tester) async {
      await tester.pumpWidget(buildPage(employees: [activeEmployee]));
      await tester.pumpAndSettle();

      expect(find.text('Jean Mbida'), findsOneWidget);
      expect(find.text('Actif'), findsOneWidget);
    });

    testWidgets('shows passwordChangeRequired badge', (tester) async {
      await tester
          .pumpWidget(buildPage(employees: [pwdPendingEmployee]));
      await tester.pumpAndSettle();

      expect(find.text('Mot de passe non changé'), findsOneWidget);
    });

    testWidgets('shows inactive badge for inactive employee', (tester) async {
      await tester.pumpWidget(buildPage(employees: [inactiveEmployee]));
      await tester.pumpAndSettle();

      expect(find.text('Inactif'), findsOneWidget);
    });

    testWidgets('FAB navigates to create form', (tester) async {
      await tester.pumpWidget(buildPage(employees: []));
      await tester.pumpAndSettle();

      expect(find.text('Ajouter'), findsOneWidget);
      expect(find.byIcon(Icons.person_add_rounded), findsOneWidget);
    });
  });
}
