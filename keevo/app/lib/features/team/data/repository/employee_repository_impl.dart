import '../../domain/model/create_employee_result.dart';
import '../../domain/model/employee_model.dart';
import '../../domain/repository/employee_repository.dart';
import '../datasource/remote_employee_datasource.dart';

/// EmployeeRepositoryImpl — Delegates to [RemoteEmployeeDataSource].
class EmployeeRepositoryImpl implements EmployeeRepository {
  final RemoteEmployeeDataSource _remote;

  const EmployeeRepositoryImpl(this._remote);

  @override
  Future<CreateEmployeeResult> createEmployee({
    required String firstName,
    required String lastName,
    required String phoneNumber,
    required String storeId,
  }) =>
      _remote.createEmployee(
        firstName: firstName,
        lastName: lastName,
        phoneNumber: phoneNumber,
        storeId: storeId,
      );

  @override
  Future<List<EmployeeModel>> listEmployees() => _remote.listEmployees();

  @override
  Future<EmployeeModel> reassignStore(String employeeId, String newStoreId) =>
      _remote.reassignStore(employeeId, newStoreId);

  @override
  Future<void> deactivateEmployee(String employeeId) =>
      _remote.deactivateEmployee(employeeId);

  @override
  Future<void> reactivateEmployee(String employeeId) =>
      _remote.reactivateEmployee(employeeId);

  @override
  Future<CreateEmployeeResult> regeneratePassword(String employeeId) =>
      _remote.regeneratePassword(employeeId);
}
