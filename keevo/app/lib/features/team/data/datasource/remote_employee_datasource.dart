import 'package:dio/dio.dart';

import '../../domain/model/create_employee_result.dart';
import '../../domain/model/employee_model.dart';

/// RemoteEmployeeDataSource — HTTP adapter for /api/v1/employees (Story 3.5).
class RemoteEmployeeDataSource {
  final Dio _dio;

  RemoteEmployeeDataSource({required Dio dio}) : _dio = dio;

  /// POST /api/v1/employees
  Future<CreateEmployeeResult> createEmployee({
    required String firstName,
    required String lastName,
    required String phoneNumber,
    required String storeId,
  }) async {
    final response = await _dio.post<Map<String, dynamic>>(
      '/api/v1/employees',
      data: {
        'firstName': firstName,
        'lastName': lastName,
        'phoneNumber': phoneNumber,
        'storeId': storeId,
      },
    );
    final data = response.data!['data'] as Map<String, dynamic>;
    return CreateEmployeeResult(
      employee: _mapEmployee(data['employee'] as Map<String, dynamic>),
      temporaryPassword: data['temporaryPassword'] as String,
    );
  }

  /// GET /api/v1/employees
  Future<List<EmployeeModel>> listEmployees() async {
    final response = await _dio.get<Map<String, dynamic>>(
      '/api/v1/employees',
    );
    final data = response.data!['data'] as List<dynamic>;
    return data
        .map((e) => _mapEmployee(e as Map<String, dynamic>))
        .toList();
  }

  /// PATCH /api/v1/employees/{id}/store
  Future<EmployeeModel> reassignStore(String employeeId, String newStoreId) async {
    final response = await _dio.patch<Map<String, dynamic>>(
      '/api/v1/employees/$employeeId/store',
      data: {'storeId': newStoreId},
    );
    return _mapEmployee(response.data!['data'] as Map<String, dynamic>);
  }

  /// PATCH /api/v1/employees/{id}/deactivate
  Future<void> deactivateEmployee(String employeeId) async {
    await _dio.patch<Map<String, dynamic>>(
      '/api/v1/employees/$employeeId/deactivate',
    );
  }

  /// PATCH /api/v1/employees/{id}/reactivate
  Future<void> reactivateEmployee(String employeeId) async {
    await _dio.patch<Map<String, dynamic>>(
      '/api/v1/employees/$employeeId/reactivate',
    );
  }

  /// POST /api/v1/employees/{id}/regenerate-password
  Future<CreateEmployeeResult> regeneratePassword(String employeeId) async {
    final response = await _dio.post<Map<String, dynamic>>(
      '/api/v1/employees/$employeeId/regenerate-password',
    );
    final data = response.data!['data'] as Map<String, dynamic>;
    return CreateEmployeeResult(
      employee: _mapEmployee(data['employee'] as Map<String, dynamic>),
      temporaryPassword: data['temporaryPassword'] as String,
    );
  }

  EmployeeModel _mapEmployee(Map<String, dynamic> json) {
    return EmployeeModel(
      id: json['id'] as String,
      userId: json['userId'] as String,
      firstName: json['firstName'] as String,
      lastName: json['lastName'] as String,
      storeId: json['storeId'] as String,
      status: json['status'] as String,
      passwordChangeRequired: json['passwordChangeRequired'] as bool? ?? false,
      createdAt: DateTime.parse(json['createdAt'] as String),
      role: json['role'] as String? ?? 'EMPLOYEE',
    );
  }

  // ── Story 14.11 ───────────────────────────────────────────────────────

  /// PATCH /api/v1/employees/{id} — partial profile update
  Future<EmployeeModel> updateEmployee(String employeeId, {
    String? firstName,
    String? lastName,
    String? phoneNumber,
    String? storeId,
  }) async {
    final body = <String, dynamic>{};
    if (firstName != null) body['firstName'] = firstName;
    if (lastName != null) body['lastName'] = lastName;
    if (phoneNumber != null) body['phoneNumber'] = phoneNumber;
    if (storeId != null) body['storeId'] = storeId;
    final response = await _dio.patch<Map<String, dynamic>>(
      '/api/v1/employees/$employeeId',
      data: body,
    );
    return _mapEmployee(response.data!['data'] as Map<String, dynamic>);
  }

  /// PATCH /api/v1/employees/{id}/role
  Future<void> changeRole(String employeeId, String role) async {
    await _dio.patch<Map<String, dynamic>>(
      '/api/v1/employees/$employeeId/role',
      data: {'role': role},
    );
  }

  /// POST /api/v1/employees/{id}/password
  Future<void> setPassword(String employeeId, String newPassword) async {
    await _dio.post<Map<String, dynamic>>(
      '/api/v1/employees/$employeeId/password',
      data: {'newPassword': newPassword},
    );
  }
}
