import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/remote_employee_datasource.dart';
import '../../data/repository/employee_repository_impl.dart';
import '../../domain/model/create_employee_result.dart';
import '../../domain/model/employee_model.dart';
import '../../domain/repository/employee_repository.dart';

part 'employee_provider.g.dart';

// ── Infrastructure ─────────────────────────────────────────────────────────

final remoteEmployeeDataSourceProvider = Provider<RemoteEmployeeDataSource>((ref) {
  return RemoteEmployeeDataSource(dio: ref.watch(dioProvider));
});

final employeeRepositoryProvider = Provider<EmployeeRepository>((ref) {
  return EmployeeRepositoryImpl(ref.watch(remoteEmployeeDataSourceProvider));
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
      () => ref.read(employeeRepositoryProvider).createEmployee(
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
