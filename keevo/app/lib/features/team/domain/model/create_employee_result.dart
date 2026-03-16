import 'package:freezed_annotation/freezed_annotation.dart';

import 'employee_model.dart';

part 'create_employee_result.freezed.dart';

/// CreateEmployeeResult — Returned once after employee creation.
/// The [temporaryPassword] is displayed once and never persisted.
@freezed
class CreateEmployeeResult with _$CreateEmployeeResult {
  const factory CreateEmployeeResult({
    required EmployeeModel employee,
    required String temporaryPassword,
  }) = _CreateEmployeeResult;
}
