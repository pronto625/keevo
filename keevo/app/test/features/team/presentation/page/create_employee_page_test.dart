import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl_phone_field/countries.dart';
import 'package:intl_phone_field/intl_phone_field.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/domain/model/store_type.dart';
import 'package:keevo/features/stores/domain/repository/store_repository.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';
import 'package:keevo/features/team/domain/model/create_employee_result.dart';
import 'package:keevo/features/team/domain/model/employee_model.dart';
import 'package:keevo/features/team/domain/repository/employee_repository.dart';
import 'package:keevo/features/team/presentation/page/create_employee_page.dart';
import 'package:keevo/features/team/presentation/provider/employee_provider.dart';

class _MockEmployeeRepo extends Mock implements EmployeeRepository {}

class _MockStoreRepo extends Mock implements StoreRepository {}

void main() {
  late _MockEmployeeRepo empRepo;
  late _MockStoreRepo storeRepo;
  final store = StoreModel(
    id: 'store-1',
    name: 'Boutique A',
    type: StoreType.store,
    createdAt: DateTime(2025),
    updatedAt: DateTime(2025),
  );

  setUp(() {
    empRepo = _MockEmployeeRepo();
    storeRepo = _MockStoreRepo();
    when(() => storeRepo.getStores()).thenAnswer((_) async => [store]);
    when(() => storeRepo.getStores(includeInactive: any(named: 'includeInactive')))
        .thenAnswer((_) async => [store]);
    when(() => storeRepo.syncFromRemote()).thenAnswer((_) async {});
  });

  testWidgets('a phone number cleared after typing is not submitted',
      (tester) async {
    await tester.binding.setSurfaceSize(const Size(800, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(ProviderScope(
      overrides: [
        employeeRepositoryProvider.overrideWithValue(empRepo),
        storeRepositoryProvider.overrideWithValue(storeRepo),
      ],
      child: const MaterialApp(home: CreateEmployeePage()),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextFormField).at(0), 'Jean');
    await tester.enterText(find.byType(TextFormField).at(1), 'Mbida');
    final phone = find.descendant(
        of: find.byType(IntlPhoneField), matching: find.byType(TextField));
    await tester.enterText(phone, '699123456');
    await tester.enterText(phone, '');
    await tester.tap(find.byType(DropdownButtonFormField<StoreModel>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Boutique A').last);
    await tester.pumpAndSettle();

    await tester.tap(find.text("Créer l'employé"));
    await tester.pumpAndSettle();

    verifyNever(() => empRepo.createEmployee(
          firstName: any(named: 'firstName'),
          lastName: any(named: 'lastName'),
          phoneNumber: any(named: 'phoneNumber'),
          storeId: any(named: 'storeId'),
        ));
    expect(find.text('Numéro invalide pour ce pays'), findsOneWidget);
  });

  testWidgets('a valid number is submitted', (tester) async {
    when(() => empRepo.createEmployee(
          firstName: any(named: 'firstName'),
          lastName: any(named: 'lastName'),
          phoneNumber: any(named: 'phoneNumber'),
          storeId: any(named: 'storeId'),
        )).thenThrow(Exception('stop'));
    await tester.binding.setSurfaceSize(const Size(800, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(ProviderScope(
      overrides: [
        employeeRepositoryProvider.overrideWithValue(empRepo),
        storeRepositoryProvider.overrideWithValue(storeRepo),
      ],
      child: const MaterialApp(home: CreateEmployeePage()),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextFormField).at(0), 'Jean');
    await tester.enterText(find.byType(TextFormField).at(1), 'Mbida');
    await tester.enterText(
        find.descendant(
            of: find.byType(IntlPhoneField), matching: find.byType(TextField)),
        '699123456');
    await tester.tap(find.byType(DropdownButtonFormField<StoreModel>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Boutique A').last);
    await tester.pumpAndSettle();

    await tester.tap(find.text("Créer l'employé"));
    await tester.pumpAndSettle();

    verify(() => empRepo.createEmployee(
          firstName: 'Jean',
          lastName: 'Mbida',
          phoneNumber: '+237699123456',
          storeId: 'store-1',
        )).called(1);
  });

  // v1s-16-13 follow-up: the plan-limit sheet states the FREE limit (3
  // employees, PlanType.FREE), never the old hard-coded 5.
  testWidgets('plan limit shows « limite de 3 employés »', (tester) async {
    when(() => empRepo.createEmployee(
          firstName: any(named: 'firstName'),
          lastName: any(named: 'lastName'),
          phoneNumber: any(named: 'phoneNumber'),
          storeId: any(named: 'storeId'),
        )).thenThrow(Exception('PLAN_LIMIT_EXCEEDED'));
    await tester.binding.setSurfaceSize(const Size(800, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(ProviderScope(
      overrides: [
        employeeRepositoryProvider.overrideWithValue(empRepo),
        storeRepositoryProvider.overrideWithValue(storeRepo),
      ],
      child: const MaterialApp(home: CreateEmployeePage()),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextFormField).at(0), 'Jean');
    await tester.enterText(find.byType(TextFormField).at(1), 'Mbida');
    await tester.enterText(
        find.descendant(
            of: find.byType(IntlPhoneField), matching: find.byType(TextField)),
        '699123456');
    await tester.tap(find.byType(DropdownButtonFormField<StoreModel>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Boutique A').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text("Créer l'employé"));
    await tester.pumpAndSettle();

    expect(find.textContaining('Vous avez atteint la limite de 3 employés'),
        findsOneWidget);
  });

  // Changing the country after typing fires no onChanged: the number must
  // still be judged (with the current country) at submit, not rejected.
  testWidgets('a valid number still submits after a country change',
      (tester) async {
    when(() => empRepo.createEmployee(
          firstName: any(named: 'firstName'),
          lastName: any(named: 'lastName'),
          phoneNumber: any(named: 'phoneNumber'),
          storeId: any(named: 'storeId'),
        )).thenThrow(Exception('stop'));
    await tester.binding.setSurfaceSize(const Size(800, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(ProviderScope(
      overrides: [
        employeeRepositoryProvider.overrideWithValue(empRepo),
        storeRepositoryProvider.overrideWithValue(storeRepo),
      ],
      child: const MaterialApp(home: CreateEmployeePage()),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextFormField).at(0), 'Jean');
    await tester.enterText(find.byType(TextFormField).at(1), 'Mbida');
    await tester.enterText(
        find.descendant(
            of: find.byType(IntlPhoneField), matching: find.byType(TextField)),
        '699123456');
    // Country picked again (back to Cameroon) without retyping.
    tester
        .widget<IntlPhoneField>(find.byType(IntlPhoneField))
        .onCountryChanged!(countries.firstWhere((c) => c.code == 'CM'));
    await tester.tap(find.byType(DropdownButtonFormField<StoreModel>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Boutique A').last);
    await tester.pumpAndSettle();

    await tester.tap(find.text("Créer l'employé"));
    await tester.pumpAndSettle();

    expect(find.text('Saisissez un numéro de téléphone valide'), findsNothing);
    verify(() => empRepo.createEmployee(
          firstName: 'Jean',
          lastName: 'Mbida',
          phoneNumber: '+237699123456',
          storeId: 'store-1',
        )).called(1);
  });

  // v1s-14-6 (FR65): after creation the owner can invite through WhatsApp;
  // the invitation never carries the temporary password.
  testWidgets('shouldOpenWhatsAppShareLinkForInvite', (tester) async {
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

    when(() => empRepo.createEmployee(
          firstName: any(named: 'firstName'),
          lastName: any(named: 'lastName'),
          phoneNumber: any(named: 'phoneNumber'),
          storeId: any(named: 'storeId'),
        )).thenAnswer((_) async => CreateEmployeeResult(
          employee: EmployeeModel(
            id: 'emp-1',
            userId: 'u-1',
            firstName: 'Jean',
            lastName: 'Mbida',
            storeId: 'store-1',
            status: 'ACTIVE',
            passwordChangeRequired: true,
            createdAt: DateTime(2026),
            phoneNumber: '+237690000001',
          ),
          temporaryPassword: 'TmpPass12345',
        ));
    await tester.binding.setSurfaceSize(const Size(800, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(ProviderScope(
      overrides: [
        employeeRepositoryProvider.overrideWithValue(empRepo),
        storeRepositoryProvider.overrideWithValue(storeRepo),
      ],
      child: const MaterialApp(home: CreateEmployeePage()),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextFormField).at(0), 'Jean');
    await tester.enterText(find.byType(TextFormField).at(1), 'Mbida');
    await tester.enterText(
        find.descendant(
            of: find.byType(IntlPhoneField), matching: find.byType(TextField)),
        '690000001');
    await tester.tap(find.byType(DropdownButtonFormField<StoreModel>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Boutique A').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text("Créer l'employé"));
    await tester.pumpAndSettle();

    expect(find.text('TmpPass12345'), findsOneWidget);
    await tester.tap(find.text('Inviter via WhatsApp'));
    await tester.pumpAndSettle();

    expect(launched, hasLength(1));
    expect(launched.single, startsWith('https://wa.me/237690000001?text='));
    final text = Uri.parse(launched.single).queryParameters['text']!;
    expect(text, contains('Jean'));
    expect(text, contains('Boutique A'));
    expect(text, contains('+237690000001'));
    expect(launched.single, isNot(contains('TmpPass12345')));
  });
}
