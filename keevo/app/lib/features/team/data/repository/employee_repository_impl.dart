import 'dart:developer' as dev;

import 'package:dio/dio.dart';

import '../../../../core/sync/connectivity_service.dart';
import '../../../../core/sync/sync_service.dart';
import '../../domain/exception/employee_action_requires_connection_exception.dart';
import '../../domain/model/create_employee_result.dart';
import '../../domain/model/employee_activity.dart';
import '../../domain/model/employee_model.dart';
import '../../domain/repository/employee_repository.dart';
import '../datasource/local_employee_datasource.dart';
import '../datasource/remote_employee_datasource.dart';

/// EmployeeRepositoryImpl — Backend-first write-through strategy (Story 5.1).
///
/// Employee creation and password regeneration REQUIRE backend (password generation).
/// Reassign/deactivate/reactivate support offline fallback with sync_queue.
class EmployeeRepositoryImpl implements EmployeeRepository {
  final RemoteEmployeeDataSource _remote;
  final LocalEmployeeDataSource _local;
  final ConnectivityService _connectivity;
  final SyncService _syncService;

  /// GET /employees is OWNER-only: an employee session (seller filters of
  /// the sales / report history) reads what the sync pull stored locally.
  final bool Function() _isOwnerSession;

  const EmployeeRepositoryImpl({
    required RemoteEmployeeDataSource remote,
    required LocalEmployeeDataSource local,
    required ConnectivityService connectivity,
    required SyncService syncService,
    bool Function() isOwnerSession = _always,
  })  : _remote = remote,
        _local = local,
        _connectivity = connectivity,
        _syncService = syncService,
        _isOwnerSession = isOwnerSession;

  static bool _always() => true;

  @override
  Future<CreateEmployeeResult> createEmployee({
    required String firstName,
    required String lastName,
    required String phoneNumber,
    required String storeId,
  }) async {
    // Backend required — password generation is server-side.
    final result = await _remote.createEmployee(
      firstName: firstName,
      lastName: lastName,
      phoneNumber: phoneNumber,
      storeId: storeId,
    );
    await _local.upsert(result.employee);
    return result;
  }

  @override
  Future<List<EmployeeModel>> listEmployees() async {
    if (_isOwnerSession() && await _connectivity.isOnline()) {
      try {
        final remotes = await _remote.listEmployees();
        await _local.upsertAll(remotes);
        return remotes;
      } catch (e) {
        dev.log('EmployeeRepository.listEmployees: backend failed — local: $e');
        return _local.getAll();
      }
    }
    return _local.getAll();
  }

  @override
  Future<EmployeeModel> reassignStore(String employeeId, String newStoreId) async {
    if (await _connectivity.isOnline()) {
      try {
        final result = await _remote.reassignStore(employeeId, newStoreId);
        await _local.upsert(result);
        return result;
      } on DioException catch (e) {
        // Seul un serveur injoignable bascule en file hors ligne : un refus
        // (403/404/409…) doit remonter à l'utilisateur, pas être mis en file.
        if (_isRefusal(e)) rethrow;
        dev.log('EmployeeRepository.reassignStore: backend unreachable — local + queue: $e');
        await _local.updateStoreAssignment(employeeId, newStoreId);
        await _syncService.queueOperation(
          operation: 'REASSIGN_EMPLOYEE',
          payload: {'employeeId': employeeId, 'newStoreId': newStoreId},
          entityId: employeeId,
        );
        final updated = await _local.getById(employeeId);
        return updated!;
      }
    } else {
      await _local.updateStoreAssignment(employeeId, newStoreId);
      await _syncService.queueOperation(
        operation: 'REASSIGN_EMPLOYEE',
        payload: {'employeeId': employeeId, 'newStoreId': newStoreId},
        entityId: employeeId,
      );
      final updated = await _local.getById(employeeId);
      return updated!;
    }
  }

  @override
  Future<void> deactivateEmployee(String employeeId) async {
    if (await _connectivity.isOnline()) {
      try {
        await _remote.deactivateEmployee(employeeId);
        await _local.updateStatus(employeeId, 'DEACTIVATED');
      } on DioException catch (e) {
        // Seul un serveur injoignable bascule en file hors ligne : un refus
        // (403/404/409…) doit remonter à l'utilisateur, pas être mis en file.
        if (_isRefusal(e)) rethrow;
        dev.log('EmployeeRepository.deactivate: backend unreachable — local + queue: $e');
        await _local.updateStatus(employeeId, 'DEACTIVATED');
        await _syncService.queueOperation(
          operation: 'DEACTIVATE_EMPLOYEE',
          payload: {'employeeId': employeeId},
          entityId: employeeId,
        );
      }
    } else {
      await _local.updateStatus(employeeId, 'DEACTIVATED');
      await _syncService.queueOperation(
        operation: 'DEACTIVATE_EMPLOYEE',
        payload: {'employeeId': employeeId},
        entityId: employeeId,
      );
    }
  }

  @override
  Future<void> reactivateEmployee(String employeeId) async {
    if (await _connectivity.isOnline()) {
      try {
        await _remote.reactivateEmployee(employeeId);
        await _local.updateStatus(employeeId, 'ACTIVE');
      } on DioException catch (e) {
        // Seul un serveur injoignable bascule en file hors ligne : un refus
        // (403/404/409…) doit remonter à l'utilisateur, pas être mis en file.
        if (_isRefusal(e)) rethrow;
        dev.log('EmployeeRepository.reactivate: backend unreachable — local + queue: $e');
        await _local.updateStatus(employeeId, 'ACTIVE');
        await _syncService.queueOperation(
          operation: 'REACTIVATE_EMPLOYEE',
          payload: {'employeeId': employeeId},
          entityId: employeeId,
        );
      }
    } else {
      await _local.updateStatus(employeeId, 'ACTIVE');
      await _syncService.queueOperation(
        operation: 'REACTIVATE_EMPLOYEE',
        payload: {'employeeId': employeeId},
        entityId: employeeId,
      );
    }
  }

  @override
  Future<CreateEmployeeResult> regeneratePassword(String employeeId) =>
      _remote.regeneratePassword(employeeId);

  // ── Story 14.11 ───────────────────────────────────────────────────────
  //
  // The backend sync handler only replays REASSIGN / (DE)ACTIVATE, so a
  // profile edit limited to the store goes through [reassignStore] (offline
  // queue available); anything else needs the server and says so.

  @override
  Future<EmployeeModel> updateEmployee(String employeeId, {
    String? firstName,
    String? lastName,
    String? phoneNumber,
    String? storeId,
  }) async {
    final storeOnly = firstName == null &&
        lastName == null &&
        phoneNumber == null &&
        storeId != null;
    if (storeOnly) return reassignStore(employeeId, storeId);

    if (!await _connectivity.isOnline()) {
      throw const EmployeeActionRequiresConnectionException();
    }
    try {
      final result = await _remote.updateEmployee(
        employeeId,
        firstName: firstName,
        lastName: lastName,
        phoneNumber: phoneNumber,
        storeId: storeId,
      );
      await _local.upsert(result);
      return result;
    } on DioException catch (e) {
      if (_isRefusal(e)) rethrow;
      dev.log('EmployeeRepository.updateEmployee: backend unreachable: $e');
      throw const EmployeeActionRequiresConnectionException();
    }
  }

  @override
  Future<void> changeRole(String employeeId, String role) =>
      _onlineOnly(() => _remote.changeRole(employeeId, role), 'changeRole');

  @override
  Future<void> setPassword(String employeeId, String newPassword) =>
      _onlineOnly(
          () => _remote.setPassword(employeeId, newPassword), 'setPassword');

  // ── v1s-14-6 — FR67 activity: server-only read, never cached ─────────

  @override
  Future<List<EmployeeActivity>> listActivity() =>
      _onlineRead(_remote.listActivity, 'listActivity');

  @override
  Future<EmployeeActivity> getActivity(String employeeId) =>
      _onlineRead(() => _remote.getActivity(employeeId), 'getActivity');

  Future<T> _onlineRead<T>(Future<T> Function() call, String name) async {
    if (!await _connectivity.isOnline()) {
      throw const EmployeeActionRequiresConnectionException();
    }
    try {
      return await call();
    } on DioException catch (e) {
      if (_isRefusal(e)) rethrow;
      dev.log('EmployeeRepository.$name: backend unreachable: $e');
      throw const EmployeeActionRequiresConnectionException();
    }
  }

  /// Runs a server-only action: a refusal (4xx) reaches the caller as-is, an
  /// unreachable server (offline, no answer, 5xx) becomes
  /// [EmployeeActionRequiresConnectionException].
  Future<void> _onlineOnly(Future<void> Function() call, String name) async {
    if (!await _connectivity.isOnline()) {
      throw const EmployeeActionRequiresConnectionException();
    }
    try {
      await call();
    } on DioException catch (e) {
      if (_isRefusal(e)) rethrow;
      dev.log('EmployeeRepository.$name: backend unreachable: $e');
      throw const EmployeeActionRequiresConnectionException();
    }
  }

  /// A 4xx answer is a refusal to show the user. No answer, or a 5xx
  /// (server or gateway down), means the server is unreachable: queue it.
  static bool _isRefusal(DioException e) {
    final status = e.response?.statusCode;
    return status != null && status < 500;
  }
}
