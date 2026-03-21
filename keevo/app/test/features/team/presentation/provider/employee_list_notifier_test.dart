import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/team/domain/model/employee_model.dart';
import 'package:keevo/features/team/domain/repository/employee_repository.dart';
import 'package:keevo/features/team/presentation/provider/employee_provider.dart';
import 'package:mocktail/mocktail.dart';

class MockEmployeeRepository extends Mock implements EmployeeRepository {}

void main() {
  late MockEmployeeRepository mockRepo;

  final employee = EmployeeModel(
    id: 'emp-1',
    userId: 'user-1',
    firstName: 'Jean',
    lastName: 'Dupont',
    storeId: 'store-1',
    status: 'ACTIVE',
    passwordChangeRequired: false,
    createdAt: DateTime(2026, 3, 17),
  );

  setUp(() {
    mockRepo = MockEmployeeRepository();
  });

  ProviderContainer makeContainer() => ProviderContainer(
        overrides: [
          employeeRepositoryProvider.overrideWithValue(mockRepo),
        ],
      );

  group('EmployeeList notifier — reads from local via repository', () {
    test('build() calls repository.listEmployees which reads from local Drift',
        () async {
      when(() => mockRepo.listEmployees())
          .thenAnswer((_) async => [employee]);

      final container = makeContainer();
      addTearDown(container.dispose);

      final result = await container.read(employeeListProvider.future);

      expect(result, [employee]);
      verify(() => mockRepo.listEmployees()).called(1);
    });

    test('returns empty list when no employees stored locally', () async {
      when(() => mockRepo.listEmployees()).thenAnswer((_) async => []);

      final container = makeContainer();
      addTearDown(container.dispose);

      final result = await container.read(employeeListProvider.future);

      expect(result, isEmpty);
    });

    test('refresh invalidates and reloads from repository', () async {
      when(() => mockRepo.listEmployees())
          .thenAnswer((_) async => [employee]);

      final container = makeContainer();
      addTearDown(container.dispose);

      await container.read(employeeListProvider.future);
      await container.read(employeeListProvider.notifier).refresh();

      verify(() => mockRepo.listEmployees()).called(2);
    });
  });
}
