import '../model/create_employee_result.dart';
import '../repository/employee_repository.dart';

/// CreateEmployeeUseCase — Domain use case for employee creation (Story 3.5 AC1).
///
/// Delegates to [EmployeeRepository]. Validation is handled server-side
/// (plan limits, phone uniqueness). The caller is responsible for displaying
/// the one-time [CreateEmployeeResult.temporaryPassword].
class CreateEmployeeUseCase {
  final EmployeeRepository _repository;

  const CreateEmployeeUseCase(this._repository);

  Future<CreateEmployeeResult> execute({
    required String firstName,
    required String lastName,
    required String phoneNumber,
    required String storeId,
  }) {
    return _repository.createEmployee(
      firstName: firstName,
      lastName: lastName,
      phoneNumber: phoneNumber,
      storeId: storeId,
    );
  }
}
