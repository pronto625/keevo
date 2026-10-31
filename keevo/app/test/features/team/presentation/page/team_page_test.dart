import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/domain/model/store_type.dart';
import 'package:keevo/features/stores/domain/repository/store_repository.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';
import 'package:keevo/features/team/domain/model/employee_activity.dart';
import 'package:keevo/features/team/domain/model/employee_model.dart';
import 'package:keevo/features/team/domain/repository/employee_repository.dart';
import 'package:keevo/features/team/presentation/page/team_page.dart';
import 'package:keevo/features/team/presentation/provider/employee_provider.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:mocktail/mocktail.dart';

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
    when(() => mockEmpRepo.listActivity()).thenAnswer((_) async => []);
  });

  Widget buildPage({
    List<EmployeeModel> employees = const [],
    String? role,
  }) {
    when(() => mockEmpRepo.listEmployees())
        .thenAnswer((_) async => employees);

    return ProviderScope(
      overrides: [
        employeeRepositoryProvider.overrideWithValue(mockEmpRepo),
        storeRepositoryProvider.overrideWithValue(mockStoreRepo),
        if (role != null) currentUserRoleProvider.overrideWithValue(role),
        if (role != null)
          currentUserIdProvider.overrideWith((ref) async => 'u-owner'),
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
  
    testWidgets('empty state copy matches the "Ajouter" button',
        (tester) async {
      await tester.pumpWidget(buildPage(employees: []));
      await tester.pumpAndSettle();

      expect(find.textContaining('Appuyez sur +'), findsNothing);
      expect(find.textContaining('Ajouter'), findsNWidgets(2));
    });

    testWidgets('pushed from Settings: header does not sit under back button',
        (tester) async {
      when(() => mockEmpRepo.listEmployees()).thenAnswer((_) async => []);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          employeeRepositoryProvider.overrideWithValue(mockEmpRepo),
          storeRepositoryProvider.overrideWithValue(mockStoreRepo),
        ],
        child: MaterialApp.router(
          routerConfig: GoRouter(routes: [
            GoRoute(
                path: '/',
                builder: (_, __) => const Scaffold(),
                routes: [
                  GoRoute(path: 'team', builder: (_, __) => const TeamPage()),
                ]),
          ], initialLocation: '/team'),
        ),
      ));
      await tester.pumpAndSettle();

      final back = tester.getRect(find.byType(BackButton));
      for (final label in ['Paramètres', 'Équipe']) {
        final text = tester.getRect(find.text(label));
        expect(back.overlaps(text), isFalse, reason: '$label overlaps back');
      }
    });

    // ── v1s-14-6 — FR67 activity on the cards ─────────────────────────
    testWidgets('OWNER: active card shows today sales and the idle badge',
        (tester) async {
      when(() => mockEmpRepo.listActivity()).thenAnswer((_) async => [
            EmployeeActivity(
              employeeId: 'emp-1',
              userId: 'u-1',
              lastActivityAt:
                  DateTime.now().subtract(const Duration(hours: 50)),
              todaySalesCount: 2,
              todaySalesAmount: 10000,
              inactive: true,
            ),
          ]);
      await tester.pumpWidget(
          buildPage(employees: [activeEmployee], role: 'OWNER'));
      await tester.pumpAndSettle();

      expect(find.textContaining("Aujourd'hui : 2 ventes"), findsOneWidget);
      expect(find.text('Dernière activité : il y a 2 j'), findsOneWidget);
      expect(find.text('Sans activité 48 h+'), findsOneWidget);
    });

    testWidgets('activity failure: list renders as before, no error UI',
        (tester) async {
      when(() => mockEmpRepo.listActivity())
          .thenThrow(Exception('offline'));
      await tester.pumpWidget(
          buildPage(employees: [activeEmployee], role: 'OWNER'));
      await tester.pumpAndSettle();

      expect(find.text('Jean Mbida'), findsOneWidget);
      expect(find.textContaining("Aujourd'hui"), findsNothing);
      expect(find.text('Erreur de chargement'), findsNothing);
    });

    testWidgets('EMPLOYEE session: activity never requested', (tester) async {
      await tester.pumpWidget(
          buildPage(employees: [activeEmployee], role: 'EMPLOYEE'));
      await tester.pumpAndSettle();

      verifyNever(() => mockEmpRepo.listActivity());
      expect(find.textContaining("Aujourd'hui"), findsNothing);
    });

    // v1s-16-11 follow-up: « Réessayer » reloads the employee list.
    testWidgets('list load failure → Réessayer shows the employees',
        (tester) async {
      final page = buildPage(employees: [activeEmployee]);
      var failing = true;
      when(() => mockEmpRepo.listEmployees()).thenAnswer((_) async {
        if (failing) throw StateError('database is locked');
        return [activeEmployee];
      });
      await tester.pumpWidget(page);
      await tester.pumpAndSettle();

      expect(find.text('Erreur de chargement'), findsOneWidget);
      expect(find.textContaining('database'), findsNothing);

      failing = false;
      await tester.tap(find.text('Réessayer'));
      await tester.pumpAndSettle();

      expect(find.text('Jean Mbida'), findsOneWidget);
      expect(find.text('Réessayer'), findsNothing);
    });
  });
}
