import 'dart:developer' as dev;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/sync/account_read_only_exception.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../../settings/presentation/provider/account_status_provider.dart';
import '../../data/datasource/local_employee_datasource.dart';
import '../../data/datasource/remote_employee_datasource.dart';
import '../../data/repository/employee_repository_impl.dart';
import '../../domain/model/create_employee_result.dart';
import '../../domain/model/employee_activity.dart';
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
    isOwnerSession: () => ref.read(isOwnerSessionProvider),
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
    final guarded = await AsyncValue.guard(
      () => ref.read(employeeRepositoryProvider).listEmployees(),
    );
    // Riverpod 3: a disposed notifier throws on `state`.
    if (ref.mounted) state = guarded;
  }
}

// ── v1s-14-6 — FR67 employee activity (OWNER only) ───────────────────────

/// Activity of every employee keyed by employee id, for the team cards.
///
/// Never blocks nor fails the team list: a non-OWNER session, an offline
/// device or a server error all yield an empty map (cards show no stats).
final employeeActivityMapProvider =
    FutureProvider.autoDispose<Map<String, EmployeeActivity>>((ref) async {
  try {
    if (ref.watch(currentUserRoleProvider) != 'OWNER') return const {};
    final list = await ref.watch(employeeRepositoryProvider).listActivity();
    return {for (final a in list) a.employeeId: a};
  } catch (e) {
    dev.log('employeeActivityMapProvider: activity unavailable: $e');
    return const {};
  }
});

/// Activity of one employee, for the activity sheet (errors surface).
final employeeActivityProvider = FutureProvider.autoDispose
    .family<EmployeeActivity, String>((ref, employeeId) {
  return ref.watch(employeeRepositoryProvider).getActivity(employeeId);
});

/// A50: team changes are refused while the account is read-only (suspended,
/// deletion pending) — the server would refuse them too.
AccountReadOnlyException? _accountReadOnly(Ref ref) =>
    AccountReadOnlyException.forTenantStatus(
        ref.read(tenantStatusClaimProvider).value);

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
    final readOnly = _accountReadOnly(ref);
    if (readOnly != null) {
      state = AsyncError(readOnly, StackTrace.current);
      return;
    }
    state = const AsyncLoading();
    final guarded = await AsyncValue.guard(
      () => ref.read(createEmployeeUseCaseProvider).execute(
            firstName: firstName,
            lastName: lastName,
            phoneNumber: phoneNumber,
            storeId: storeId,
          ),
    );
    // Riverpod 3: a disposed notifier throws on `state`.
    if (ref.mounted) state = guarded;
  }
}

// ── Regenerate password ────────────────────────────────────────────────────

@riverpod
class RegeneratePassword extends _$RegeneratePassword {
  @override
  FutureOr<CreateEmployeeResult?> build() => null;

  Future<void> regenerate(String employeeId) async {
    final readOnly = _accountReadOnly(ref);
    if (readOnly != null) {
      state = AsyncError(readOnly, StackTrace.current);
      return;
    }
    state = const AsyncLoading();
    final guarded = await AsyncValue.guard(
      () => ref.read(employeeRepositoryProvider).regeneratePassword(employeeId),
    );
    // Riverpod 3: a disposed notifier throws on `state`.
    if (ref.mounted) state = guarded;
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
    final readOnly = _accountReadOnly(ref);
    if (readOnly != null) {
      state = AsyncError(readOnly, StackTrace.current);
      return;
    }
    // J31: kept alive for the whole call — read through `.notifier` without
    // a listener, an auto-disposed notifier could be torn down mid-call and
    // the caller would then read a fresh state with the error lost.
    final link = ref.keepAlive();
    try {
      state = const AsyncLoading();
      final guarded = await AsyncValue.guard(
        () => ref.read(employeeRepositoryProvider).updateEmployee(
              employeeId,
              firstName: firstName,
              lastName: lastName,
              phoneNumber: phoneNumber,
              storeId: storeId,
            ),
      );
      // Riverpod 3: a disposed notifier throws on `state`.
      if (ref.mounted) state = guarded;
    } finally {
      link.close();
    }
  }
}

// ── Story 14.11 — Change employee role ────────────────────────────────────

@riverpod
class ChangeEmployeeRole extends _$ChangeEmployeeRole {
  @override
  FutureOr<void> build() => null;

  Future<void> change(String employeeId, String role) async {
    final readOnly = _accountReadOnly(ref);
    if (readOnly != null) {
      state = AsyncError(readOnly, StackTrace.current);
      return;
    }
    final link = ref.keepAlive(); // J31: see UpdateEmployee.updateProfile
    try {
      state = const AsyncLoading();
      final guarded = await AsyncValue.guard(
        () => ref.read(employeeRepositoryProvider).changeRole(employeeId, role),
      );
      // Riverpod 3: a disposed notifier throws on `state`.
      if (ref.mounted) state = guarded;
    } finally {
      link.close();
    }
  }
}

// ── Story 14.11 — Set employee password ───────────────────────────────────

@riverpod
class SetEmployeePassword extends _$SetEmployeePassword {
  @override
  FutureOr<void> build() => null;

  Future<void> set(String employeeId, String newPassword) async {
    final readOnly = _accountReadOnly(ref);
    if (readOnly != null) {
      state = AsyncError(readOnly, StackTrace.current);
      return;
    }
    state = const AsyncLoading();
    final guarded = await AsyncValue.guard(
      () => ref.read(employeeRepositoryProvider).setPassword(employeeId, newPassword),
    );
    // Riverpod 3: a disposed notifier throws on `state`.
    if (ref.mounted) state = guarded;
  }
}
