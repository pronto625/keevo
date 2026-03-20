import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
import '../../domain/model/employee_model.dart';

/// LocalEmployeeDataSource — Drift-backed local cache for employees.
class LocalEmployeeDataSource {
  final AppDatabase _db;

  const LocalEmployeeDataSource(this._db);

  /// Insert or replace an employee record.
  Future<void> upsert(EmployeeModel employee) async {
    await _db.into(_db.employees).insertOnConflictUpdate(
          EmployeesCompanion.insert(
            id: employee.id,
            userId: employee.userId,
            firstName: employee.firstName,
            lastName: employee.lastName,
            storeId: employee.storeId,
            status: Value(employee.status),
            passwordChangeRequired: Value(employee.passwordChangeRequired),
            createdAt: employee.createdAt,
          ),
        );
  }

  /// Bulk upsert from remote sync.
  Future<void> upsertAll(List<EmployeeModel> employees) async {
    await _db.batch((batch) {
      for (final e in employees) {
        batch.insert(
          _db.employees,
          EmployeesCompanion.insert(
            id: e.id,
            userId: e.userId,
            firstName: e.firstName,
            lastName: e.lastName,
            storeId: e.storeId,
            status: Value(e.status),
            passwordChangeRequired: Value(e.passwordChangeRequired),
            createdAt: e.createdAt,
          ),
          onConflict: DoUpdate(
            (_) => EmployeesCompanion(
              userId: Value(e.userId),
              firstName: Value(e.firstName),
              lastName: Value(e.lastName),
              storeId: Value(e.storeId),
              status: Value(e.status),
              passwordChangeRequired: Value(e.passwordChangeRequired),
            ),
          ),
        );
      }
    });
  }

  /// Get all employees (optionally filter by status).
  Future<List<EmployeeModel>> getAll({String? status}) async {
    final query = _db.select(_db.employees);
    if (status != null) {
      query.where((e) => e.status.equals(status));
    }
    final rows = await query.get();
    return rows.map(_toModel).toList();
  }

  /// Get a single employee by ID.
  Future<EmployeeModel?> getById(String id) async {
    final query = _db.select(_db.employees)
      ..where((e) => e.id.equals(id));
    final row = await query.getSingleOrNull();
    return row == null ? null : _toModel(row);
  }

  /// Update just the status field.
  Future<void> updateStatus(String id, String status) async {
    await (_db.update(_db.employees)..where((e) => e.id.equals(id)))
        .write(EmployeesCompanion(status: Value(status)));
  }

  /// Update store assignment.
  Future<void> updateStoreAssignment(String id, String storeId) async {
    await (_db.update(_db.employees)..where((e) => e.id.equals(id)))
        .write(EmployeesCompanion(storeId: Value(storeId)));
  }

  EmployeeModel _toModel(Employee row) => EmployeeModel(
        id: row.id,
        userId: row.userId,
        firstName: row.firstName,
        lastName: row.lastName,
        storeId: row.storeId,
        status: row.status,
        passwordChangeRequired: row.passwordChangeRequired,
        createdAt: row.createdAt,
      );
}
