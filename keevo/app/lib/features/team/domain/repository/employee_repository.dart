import '../model/create_employee_result.dart';
import '../model/employee_activity.dart';
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

  // ── Story 14.11 ───────────────────────────────────────────────────

  Future<EmployeeModel> updateEmployee(String employeeId, {
    String? firstName,
    String? lastName,
    String? phoneNumber,
    String? storeId,
  });

  Future<void> changeRole(String employeeId, String role);

  Future<void> setPassword(String employeeId, String newPassword);

  // ── v1s-14-6 — FR67 activity (OWNER only, online only) ────────────

  /// Activity of every employee. Offline / unreachable server →
  /// `EmployeeActionRequiresConnectionException`.
  Future<List<EmployeeActivity>> listActivity();

  /// Activity of one employee. Offline / unreachable server →
  /// `EmployeeActionRequiresConnectionException`.
  Future<EmployeeActivity> getActivity(String employeeId);
}
