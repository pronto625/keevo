import 'package:freezed_annotation/freezed_annotation.dart';

part 'employee_model.freezed.dart';
part 'employee_model.g.dart';

/// EmployeeModel — Domain model for an employee (Story 3.5).
@freezed
class EmployeeModel with _$EmployeeModel {
  const factory EmployeeModel({
    required String id,
    required String userId,
    required String firstName,
    required String lastName,
    required String storeId,
    required String status,
    required bool passwordChangeRequired,
    required DateTime createdAt,
    @Default('EMPLOYEE') String role,
  }) = _EmployeeModel;

  factory EmployeeModel.fromJson(Map<String, dynamic> json) =>
      _$EmployeeModelFromJson(json);
}
