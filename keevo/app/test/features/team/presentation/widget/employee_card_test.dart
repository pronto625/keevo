import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/domain/model/store_type.dart';
import 'package:keevo/features/stores/domain/repository/store_repository.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';
import 'package:keevo/features/team/domain/model/create_employee_result.dart';
import 'package:keevo/features/team/domain/model/employee_activity.dart';
import 'package:keevo/features/team/domain/model/employee_model.dart';
import 'package:keevo/features/team/domain/repository/employee_repository.dart';
import 'package:keevo/features/team/presentation/provider/employee_provider.dart';
import 'package:keevo/features/team/presentation/widget/employee_card.dart';
import 'package:keevo/features/team/presentation/widget/set_password_bottom_sheet.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';

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
        retry: noProviderRetry,
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

  testWidgets(
      'v1s-14-13: reactivation refused by the plan limit opens the plan sheet, '
      'no snackbar, no success', (tester) async {
    var done = 0;
    when(() => empRepo.reactivateEmployee(any())).thenThrow(DioException(
      requestOptions: RequestOptions(path: '/api/v1/employees/emp-1/reactivate'),
      type: DioExceptionType.badResponse,
      response: Response(
        requestOptions:
            RequestOptions(path: '/api/v1/employees/emp-1/reactivate'),
        statusCode: 403,
        data: {
          'error': 'Forbidden',
          'code': 403,
          'domainCode': 'PLAN_LIMIT_EXCEEDED',
          'details': {'entity': 'employees', 'limit': '3', 'current': '3'},
        },
      ),
    ));
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
    expect(
        find.text(
            'Vous avez atteint la limite de 3 employés sur votre plan gratuit'),
        findsOneWidget);
    expect(find.byType(SnackBar), findsNothing);
    expect(find.textContaining('PLAN_LIMIT_EXCEEDED'), findsNothing);
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
    expect(find.text('Régénérer le mot de passe'), findsOneWidget);
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
      retry: noProviderRetry,
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

  // ── v1s-14-6 — FR67 activity / FR65 invitation ───────────────────────

  Widget buildWithActivity(EmployeeModel e, EmployeeActivity? a,
          {String role = 'OWNER'}) =>
      ProviderScope(
        retry: noProviderRetry,
        overrides: overrides(role: role),
        child: MaterialApp(
          home: Scaffold(
            body: EmployeeCard(
                employee: e, activity: a, onActionComplete: () {}),
          ),
        ),
      );

  EmployeeActivity activity({bool inactive = false, DateTime? lastActivity}) =>
      EmployeeActivity(
        employeeId: 'emp-1',
        userId: 'u-1',
        lastLoginAt: DateTime.now().subtract(const Duration(days: 1)),
        lastActivityAt:
            lastActivity ?? DateTime.now().subtract(const Duration(hours: 3)),
        todaySalesCount: 2,
        todaySalesAmount: 10000,
        weekSalesCount: 3,
        weekSalesAmount: 13000,
        activeSession: true,
        inactive: inactive,
      );

  testWidgets('shouldShowEmployeeActivityStats — today sales and last activity',
      (tester) async {
    await pumpCard(tester, buildWithActivity(employee(), activity()));
    await tester.pumpAndSettle();

    expect(find.textContaining('Aujourd\'hui : 2 ventes'), findsOneWidget);
    expect(find.textContaining(RegExp(r'10\s000\sXAF')), findsOneWidget);
    expect(find.text('Dernière activité : il y a 3 h'), findsOneWidget);
    expect(find.text('Sans activité 48 h+'), findsNothing);
  });

  testWidgets('idle 48 h+ → « Sans activité 48 h+ » badge', (tester) async {
    await pumpCard(tester, buildWithActivity(employee(), activity(inactive: true)));
    await tester.pumpAndSettle();

    expect(find.text('Sans activité 48 h+'), findsOneWidget);
  });

  testWidgets('no activity → no stats line (list unaffected)', (tester) async {
    await pumpCard(tester, buildWithActivity(employee(), null));
    await tester.pumpAndSettle();

    expect(find.textContaining('Aujourd\'hui'), findsNothing);
    expect(find.text('Jean Mbida'), findsOneWidget);
  });

  testWidgets('deactivated employee → no stats, no idle badge', (tester) async {
    await pumpCard(tester,
        buildWithActivity(employee(status: 'INACTIVE'), activity(inactive: true)));
    await tester.pumpAndSettle();

    expect(find.textContaining('Aujourd\'hui'), findsNothing);
    expect(find.text('Sans activité 48 h+'), findsNothing);
  });

  testWidgets('OWNER sheet: « Voir l\'activité » opens the activity sheet with '
      'a WhatsApp reminder when idle', (tester) async {
    when(() => empRepo.getActivity('emp-1'))
        .thenAnswer((_) async => activity(inactive: true,
            lastActivity: DateTime.now().subtract(const Duration(hours: 49))));
    final e = employee().copyWith(phoneNumber: '+237690000001', passwordChangeRequired: true);
    await pumpCard(tester, buildWithActivity(e, null));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(EmployeeCard));
    await tester.pumpAndSettle();
    expect(find.text('Renvoyer l\'invitation WhatsApp'), findsOneWidget);
    await tester.tap(find.text('Voir l\'activité'));
    await tester.pumpAndSettle();

    expect(find.text('Activité de Jean Mbida'), findsOneWidget);
    expect(find.textContaining(RegExp(r'^2 ventes · 10\s000\sXAF$')), findsOneWidget);
    expect(find.textContaining(RegExp(r'^3 ventes · 13\s000\sXAF$')), findsOneWidget);
    expect(find.text('il y a 1 j'), findsOneWidget);
    expect(find.text('Connecté sur un appareil'), findsOneWidget);
    expect(find.text('Relancer via WhatsApp'), findsOneWidget);
  });

  testWidgets('activity sheet error → AppErrorWidget, « Réessayer » re-fetches',
      (tester) async {
    var calls = 0;
    when(() => empRepo.getActivity('emp-1')).thenAnswer((_) async {
      calls++;
      if (calls == 1) throw Exception('Cette action nécessite une connexion internet.');
      return activity();
    });
    await pumpCard(tester, buildWithActivity(employee(), null));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(EmployeeCard));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Voir l\'activité'));
    await tester.pumpAndSettle();

    expect(find.text('Activité indisponible'), findsOneWidget);
    await tester.tap(find.text('Réessayer'));
    await tester.pumpAndSettle();

    expect(calls, 2);
    expect(find.text('Relancer via WhatsApp'), findsNothing);
    expect(find.textContaining(RegExp(r'^2 ventes · 10\s000\sXAF$')), findsOneWidget);
  });

  testWidgets('« Renvoyer l\'invitation WhatsApp » opens wa.me without password',
      (tester) async {
    const channel = MethodChannel('plugins.flutter.io/url_launcher');
    final launched = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'launch') {
        launched.add((call.arguments as Map)['url'] as String);
        return true;
      }
      return false;
    });
    addTearDown(() => TestDefaultBinaryMessengerBinding
        .instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null));
    final e = employee().copyWith(phoneNumber: '+237690000001', passwordChangeRequired: true);
    await pumpCard(tester, buildWithActivity(e, null));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(EmployeeCard));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Renvoyer l\'invitation WhatsApp'));
    await tester.pumpAndSettle();

    expect(launched, hasLength(1));
    expect(launched.single, startsWith('https://wa.me/237690000001?text='));
    final text = Uri.parse(launched.single).queryParameters['text']!;
    expect(text, contains('Boutique A'));
    expect(text.toLowerCase(), isNot(contains('mot de passe :')));
  });

  testWidgets('phone unknown (local cache) → no invitation action',
      (tester) async {
    await pumpCard(tester, buildWithActivity(employee(), null));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(EmployeeCard));
    await tester.pumpAndSettle();
    expect(find.text('Renvoyer l\'invitation WhatsApp'), findsNothing);
  });

  testWidgets('EMPLOYEE session: no activity nor invitation action',
      (tester) async {
    final e = employee().copyWith(phoneNumber: '+237690000001', passwordChangeRequired: true);
    await pumpCard(tester, buildWithActivity(e, null, role: 'EMPLOYEE'));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(EmployeeCard));
    await tester.pumpAndSettle();
    expect(find.text('Voir l\'activité'), findsNothing);
    expect(find.text('Renvoyer l\'invitation WhatsApp'), findsNothing);
    verifyNever(() => empRepo.getActivity(any()));
  });

  void mockLauncher(List<String> launched) {
    const channel = MethodChannel('plugins.flutter.io/url_launcher');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'launch') {
        launched.add((call.arguments as Map)['url'] as String);
        return true;
      }
      return false;
    });
    addTearDown(() => TestDefaultBinaryMessengerBinding
        .instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null));
  }

  testWidgets('password already changed → no « Renvoyer l\'invitation » action',
      (tester) async {
    final e = employee().copyWith(phoneNumber: '+237690000001');
    await pumpCard(tester, buildWithActivity(e, null));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(EmployeeCard));
    await tester.pumpAndSettle();
    expect(find.text('Renvoyer l\'invitation WhatsApp'), findsNothing);
    expect(find.text('Voir l\'activité'), findsOneWidget);
  });

  testWidgets('stores unresolved → invitation without the placeholder name',
      (tester) async {
    final launched = <String>[];
    mockLauncher(launched);
    when(() => storeRepo.getStores()).thenThrow(Exception('db'));
    final e = employee().copyWith(
        phoneNumber: '+237690000001', passwordChangeRequired: true);
    await pumpCard(tester, buildWithActivity(e, null));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(EmployeeCard));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Renvoyer l\'invitation WhatsApp'));
    await tester.pumpAndSettle();

    final text = Uri.parse(launched.single).queryParameters['text']!;
    expect(text, isNot(contains('Boutique inconnue')));
    expect(text, isNot(contains('Chargement')));
  });

  testWidgets('regenerate password → « Inviter via WhatsApp » targets the '
      'phone, never the temporary password', (tester) async {
    final launched = <String>[];
    mockLauncher(launched);
    final e = employee().copyWith(phoneNumber: '+237690000001');
    when(() => empRepo.regeneratePassword('emp-1'))
        .thenAnswer((_) async => CreateEmployeeResult(
              employee: e.copyWith(passwordChangeRequired: true),
              temporaryPassword: 'TmpPass98765',
            ));
    await pumpCard(tester, buildWithActivity(e, null));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(EmployeeCard));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Régénérer le mot de passe'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Régénérer'));
    await tester.pumpAndSettle();

    expect(find.text('TmpPass98765'), findsOneWidget);
    await tester.tap(find.text('Inviter via WhatsApp'));
    await tester.pumpAndSettle();

    expect(launched, hasLength(1));
    expect(launched.single, startsWith('https://wa.me/237690000001?text='));
    expect(launched.single, isNot(contains('TmpPass98765')));
    final text = Uri.parse(launched.single).queryParameters['text']!;
    expect(text, contains('Boutique A'));
    expect(text, isNot(contains('TmpPass98765')));
  });
}
