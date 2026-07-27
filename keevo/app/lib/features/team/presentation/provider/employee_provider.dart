import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_employee_datasource.dart';
import '../../data/datasource/remote_employee_datasource.dart';
import '../../data/repository/employee_repository_impl.dart';
import '../../domain/model/create_employee_result.dart';
import '../../domain/model/employee_model.dart';
import '../../domain/repository/employee_repository.dart';
import '../../domain/usecase/create_employee_usecase.dart';

part 'employee_provider.g.dart';

// ── Infrastructure ─────────────────────────────────────────────────────────

final remoteEmployeeDataSourceProvider = Provider<RemoteEmployeeDataSource>((ref) {
  return RemoteEmployeeDataSource(dio: ref.watch(dioProvider));
});

final localEmployeeDataSourceProvider = Provider<LocalEmployeeDataSource>((ref) {
  return LocalEmployeeDataSource(ref.watch(appDatabaseProvider));
});

final employeeRepositoryProvider = Provider<EmployeeRepository>((ref) {
  return EmployeeRepositoryImpl(
    remote: ref.watch(remoteEmployeeDataSourceProvider),
    local: ref.watch(localEmployeeDataSourceProvider),
    connectivity: ref.watch(connectivityServiceProvider),
    syncService: ref.watch(syncServiceProvider),
  );
});

final createEmployeeUseCaseProvider = Provider<CreateEmployeeUseCase>((ref) {
  return CreateEmployeeUseCase(ref.watch(employeeRepositoryProvider));
});

// ── Employee list ──────────────────────────────────────────────────────────

@riverpod
class EmployeeList extends _$EmployeeList {
  @override
  Future<List<EmployeeModel>> build() async {
    return ref.watch(employeeRepositoryProvider).listEmployees();
  }

  Future<void> refresh() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
      () => ref.read(employeeRepositoryProvider).listEmployees(),
    );
  }
}

// ── Create employee ────────────────────────────────────────────────────────

@riverpod
class CreateEmployee extends _$CreateEmployee {
  @override
  FutureOr<CreateEmployeeResult?> build() => null;

  Future<void> create({
    required String firstName,
    required String lastName,
    required String phoneNumber,
    required String storeId,
  }) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
      () => ref.read(createEmployeeUseCaseProvider).execute(
            firstName: firstName,
            lastName: lastName,
            phoneNumber: phoneNumber,
            storeId: storeId,
          ),
    );
  }
}

// ── Reassign store ─────────────────────────────────────────────────────────

@riverpod
class ReassignStore extends _$ReassignStore {
  @override
  FutureOr<EmployeeModel?> build() => null;

  Future<void> reassign(String employeeId, String newStoreId) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
      () => ref.read(employeeRepositoryProvider).reassignStore(employeeId, newStoreId),
    );
  }
}

// ── Deactivate employee ────────────────────────────────────────────────────

@riverpod
class DeactivateEmployee extends _$DeactivateEmployee {
  @override
  FutureOr<void> build() => null;

  Future<void> deactivate(String employeeId) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
      () => ref.read(employeeRepositoryProvider).deactivateEmployee(employeeId),
    );
  }
}

// ── Reactivate employee ────────────────────────────────────────────────────

@riverpod
class ReactivateEmployee extends _$ReactivateEmployee {
  @override
  FutureOr<void> build() => null;

  Future<void> reactivate(String employeeId) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
      () => ref.read(employeeRepositoryProvider).reactivateEmployee(employeeId),
    );
  }
}

// ── Regenerate password ────────────────────────────────────────────────────

@riverpod
class RegeneratePassword extends _$RegeneratePassword {
  @override
  FutureOr<CreateEmployeeResult?> build() => null;

  Future<void> regenerate(String employeeId) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
      () => ref.read(employeeRepositoryProvider).regeneratePassword(employeeId),
    );
  }
}

// ── Story 14.11 — Update employee profile ─────────────────────────────────

@riverpod
class UpdateEmployee extends _$UpdateEmployee {
  @override
  FutureOr<EmployeeModel?> build() => null;

  Future<void> updateProfile(String employeeId, {
    String? firstName,
    String? lastName,
    String? phoneNumber,
    String? storeId,
  }) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
      () => ref.read(employeeRepositoryProvider).updateEmployee(
            employeeId,
            firstName: firstName,
            lastName: lastName,
            phoneNumber: phoneNumber,
            storeId: storeId,
          ),
    );
  }
}

// ── Story 14.11 — Change employee role ────────────────────────────────────

@riverpod
class ChangeEmployeeRole extends _$ChangeEmployeeRole {
  @override
  FutureOr<void> build() => null;

  Future<void> change(String employeeId, String role) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
      () => ref.read(employeeRepositoryProvider).changeRole(employeeId, role),
    );
  }
}

// ── Story 14.11 — Set employee password ───────────────────────────────────

@riverpod
class SetEmployeePassword extends _$SetEmployeePassword {
  @override
  FutureOr<void> build() => null;

  Future<void> set(String employeeId, String newPassword) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
      () => ref.read(employeeRepositoryProvider).setPassword(employeeId, newPassword),
    );
  }
}
