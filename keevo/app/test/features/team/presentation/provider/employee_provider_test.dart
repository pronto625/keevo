import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/team/domain/model/create_employee_result.dart';
import 'package:keevo/features/team/domain/model/employee_model.dart';
import 'package:keevo/features/team/domain/repository/employee_repository.dart';
import 'package:keevo/features/team/presentation/provider/employee_provider.dart';

class _MockEmployeeRepository extends Mock implements EmployeeRepository {}

void main() {
  late _MockEmployeeRepository mockRepo;

  final now = DateTime(2025, 6, 1);
  final sampleEmployee = EmployeeModel(
    id: 'emp-1',
    userId: 'u-1',
    firstName: 'Jean',
    lastName: 'Mbida',
    storeId: 'store-1',
    status: 'ACTIVE',
    passwordChangeRequired: true,
    createdAt: now,
  );

  setUp(() {
    mockRepo = _MockEmployeeRepository();
  });

  ProviderContainer makeContainer() => ProviderContainer(
        overrides: [
          employeeRepositoryProvider.overrideWithValue(mockRepo),
        ],
      );

  group('EmployeeList', () {
    test('initial state loads employees from repository', () async {
      when(() => mockRepo.listEmployees())
          .thenAnswer((_) async => [sampleEmployee]);

      final container = makeContainer();
      addTearDown(container.dispose);

      final state = await container.read(employeeListProvider.future);
      expect(state, [sampleEmployee]);
    });

    test('refresh reloads employees', () async {
      when(() => mockRepo.listEmployees())
          .thenAnswer((_) async => [sampleEmployee]);

      final container = makeContainer();
      addTearDown(container.dispose);

      await container.read(employeeListProvider.future);
      await container.read(employeeListProvider.notifier).refresh();

      verify(() => mockRepo.listEmployees()).called(2);
    });

    test('returns empty list when no employees', () async {
      when(() => mockRepo.listEmployees()).thenAnswer((_) async => []);

      final container = makeContainer();
      addTearDown(container.dispose);

      final state = await container.read(employeeListProvider.future);
      expect(state, isEmpty);
    });
  });

  group('CreateEmployee', () {
    test('initial state is AsyncData(null)', () {
      final container = makeContainer();
      addTearDown(container.dispose);

      final state = container.read(createEmployeeProvider);
      expect(state, const AsyncData<CreateEmployeeResult?>(null));
    });

    test('create delegates to repository and updates state', () async {
      final result = CreateEmployeeResult(
        employee: sampleEmployee,
        temporaryPassword: 'TempPwd123',
      );
      when(() => mockRepo.createEmployee(
            firstName: any(named: 'firstName'),
            lastName: any(named: 'lastName'),
            phoneNumber: any(named: 'phoneNumber'),
            storeId: any(named: 'storeId'),
          )).thenAnswer((_) async => result);

      final container = makeContainer();
      addTearDown(container.dispose);

      await container.read(createEmployeeProvider.notifier).create(
            firstName: 'Jean',
            lastName: 'Mbida',
            phoneNumber: '+237600000000',
            storeId: 'store-1',
          );

      final state = container.read(createEmployeeProvider);
      expect(state.value, result);
    });
  });

  group('DeactivateEmployee', () {
    test('deactivate delegates to repository', () async {
      when(() => mockRepo.deactivateEmployee(any()))
          .thenAnswer((_) async {});

      final container = makeContainer();
      addTearDown(container.dispose);

      await container
          .read(deactivateEmployeeProvider.notifier)
          .deactivate('emp-1');

      verify(() => mockRepo.deactivateEmployee('emp-1')).called(1);
    });
  });

  group('ReassignStore', () {
    test('reassign delegates to repository', () async {
      final updated = sampleEmployee.copyWith(storeId: 'store-2');
      when(() => mockRepo.reassignStore(any(), any()))
          .thenAnswer((_) async => updated);

      final container = makeContainer();
      addTearDown(container.dispose);

      await container
          .read(reassignStoreProvider.notifier)
          .reassign('emp-1', 'store-2');

      final state = container.read(reassignStoreProvider);
      expect(state.value?.storeId, 'store-2');
    });
  });
}
