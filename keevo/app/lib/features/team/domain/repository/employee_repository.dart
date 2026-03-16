import '../model/create_employee_result.dart';
import '../model/employee_model.dart';

/// EmployeeRepository — Domain port for employee management (Story 3.5).
abstract class EmployeeRepository {
  Future<CreateEmployeeResult> createEmployee({
    required String firstName,
    required String lastName,
    required String phoneNumber,
    required String storeId,
  });

  Future<List<EmployeeModel>> listEmployees();

  Future<EmployeeModel> reassignStore(String employeeId, String newStoreId);

  Future<void> deactivateEmployee(String employeeId);

  Future<void> reactivateEmployee(String employeeId);

  Future<CreateEmployeeResult> regeneratePassword(String employeeId);
}
