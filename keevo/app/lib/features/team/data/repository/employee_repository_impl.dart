import 'dart:developer' as dev;

import '../../../../core/sync/connectivity_service.dart';
import '../../../../core/sync/sync_service.dart';
import '../../domain/model/create_employee_result.dart';
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

  const EmployeeRepositoryImpl({
    required RemoteEmployeeDataSource remote,
    required LocalEmployeeDataSource local,
    required ConnectivityService connectivity,
    required SyncService syncService,
  })  : _remote = remote,
        _local = local,
        _connectivity = connectivity,
        _syncService = syncService;

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
    if (await _connectivity.isOnline()) {
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
      } catch (e) {
        dev.log('EmployeeRepository.reassignStore: backend failed — local + queue: $e');
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
      } catch (e) {
        dev.log('EmployeeRepository.deactivate: backend failed — local + queue: $e');
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
      } catch (e) {
        dev.log('EmployeeRepository.reactivate: backend failed — local + queue: $e');
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
}
