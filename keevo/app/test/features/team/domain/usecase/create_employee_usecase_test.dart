import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/team/domain/model/create_employee_result.dart';
import 'package:keevo/features/team/domain/model/employee_model.dart';
import 'package:keevo/features/team/domain/repository/employee_repository.dart';
import 'package:keevo/features/team/domain/usecase/create_employee_usecase.dart';

class _MockEmployeeRepository extends Mock implements EmployeeRepository {}

void main() {
  late _MockEmployeeRepository mockRepo;
  late CreateEmployeeUseCase useCase;

  final sampleResult = CreateEmployeeResult(
    employee: EmployeeModel(
      id: 'emp-1',
      userId: 'u-1',
      firstName: 'Loïc',
      lastName: 'Nkoulou',
      storeId: 'store-1',
      status: 'ACTIVE',
      passwordChangeRequired: true,
      createdAt: DateTime(2025, 6, 1),
    ),
    temporaryPassword: 'TempAbc12345',
  );

  setUp(() {
    mockRepo = _MockEmployeeRepository();
    useCase = CreateEmployeeUseCase(mockRepo);
  });

  test('execute delegates to repository and returns CreateEmployeeResult', () async {
    when(() => mockRepo.createEmployee(
          firstName: any(named: 'firstName'),
          lastName: any(named: 'lastName'),
          phoneNumber: any(named: 'phoneNumber'),
          storeId: any(named: 'storeId'),
        )).thenAnswer((_) async => sampleResult);

    final result = await useCase.execute(
      firstName: 'Loïc',
      lastName: 'Nkoulou',
      phoneNumber: '+237611000001',
      storeId: 'store-1',
    );

    expect(result, sampleResult);
    expect(result.temporaryPassword, 'TempAbc12345');
    expect(result.employee.firstName, 'Loïc');
    verify(() => mockRepo.createEmployee(
          firstName: 'Loïc',
          lastName: 'Nkoulou',
          phoneNumber: '+237611000001',
          storeId: 'store-1',
        )).called(1);
  });

  test('execute propagates repository exception', () async {
    when(() => mockRepo.createEmployee(
          firstName: any(named: 'firstName'),
          lastName: any(named: 'lastName'),
          phoneNumber: any(named: 'phoneNumber'),
          storeId: any(named: 'storeId'),
        )).thenThrow(Exception('PLAN_LIMIT_EXCEEDED'));

    expect(
      () => useCase.execute(
        firstName: 'Test',
        lastName: 'User',
        phoneNumber: '+237611000002',
        storeId: 'store-1',
      ),
      throwsA(isA<Exception>()),
    );
  });
}
