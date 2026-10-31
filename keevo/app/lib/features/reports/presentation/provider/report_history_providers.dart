import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../auth/presentation/provider/auth_provider.dart';
import '../../../team/presentation/provider/employee_provider.dart';
import '../../data/datasource/local_report_history_datasource.dart';
import '../../data/datasource/remote_report_history_datasource.dart';
import '../../data/repository/report_history_repository_impl.dart';
import '../../domain/model/report_history_model.dart';
import '../../domain/repository/report_history_repository.dart';

part 'report_history_providers.g.dart';

// ── Infrastructure providers ─────────────────────────────────────────────────

final localReportHistoryDataSourceProvider =
    Provider<LocalReportHistoryDataSource>((ref) {
  final db = ref.watch(appDatabaseProvider);
  return LocalReportHistoryDataSource(db);
});

final remoteReportHistoryDataSourceProvider =
    Provider<RemoteReportHistoryDataSource>((ref) {
  final dio = ref.watch(dioProvider);
  return RemoteReportHistoryDataSource(dio);
});

/// J5 — nom affiché d'un rapport personnel quand le serveur n'a pas rempli
/// `actorName` (anciens rapports). Le rapport porte l'id UTILISATEUR du
/// vendeur : on le résout dans la table locale des employés (par user id, et
/// par id de fiche en repli). Ne bloque ni n'échoue jamais : carte vide.
final employeeNamesByActorIdProvider =
    FutureProvider.autoDispose<Map<String, String>>((ref) async {
  try {
    final employees =
        await ref.watch(localEmployeeDataSourceProvider).getAll();
    final names = <String, String>{};
    for (final e in employees) {
      final name = [e.firstName, e.lastName]
          .map((p) => p.trim())
          .where((p) => p.isNotEmpty)
          .join(' ');
      if (name.isEmpty) continue;
      names[e.id] = name;
      if (e.userId.isNotEmpty) names[e.userId] = name;
    }
    return names;
  } catch (_) {
    return const {};
  }
});

final reportHistoryRepositoryProvider =
    Provider<ReportHistoryRepository>((ref) {
  return ReportHistoryRepositoryImpl(
    local: ref.watch(localReportHistoryDataSourceProvider),
    remote: ref.watch(remoteReportHistoryDataSourceProvider),
  );
});

// ── Reactive providers ───────────────────────────────────────────────────────

/// Paginated report history.
/// [type]: optional filter — 'DAILY', 'DAILY_COMBINED', or null for all.
/// [storeId]: optional — filter to a specific store (admin view).
/// [actorId]: optional — filter to a specific employee's reports.
@riverpod
Future<List<ReportHistoryModel>> reportHistory(
  Ref ref, {
  int page = 0,
  int size = 20,
  String? type,
  String? storeId,
  String? actorId,
}) async {
  final repo = ref.watch(reportHistoryRepositoryProvider);
  return repo.getReportHistory(
      page: page, size: size, type: type,
      storeId: storeId, actorId: actorId);
}

/// Single report detail by ID.
@riverpod
Future<ReportHistoryModel?> reportDetail(
  Ref ref,
  String reportId,
) async {
  final repo = ref.watch(reportHistoryRepositoryProvider);
  return repo.getReportById(reportId);
}

/// AsyncNotifier for the resend action.
@riverpod
class ResendReportNotifier extends _$ResendReportNotifier {
  @override
  AsyncValue<void> build() => const AsyncData(null);

  Future<void> resend(String reportId) async {
    state = const AsyncLoading();
    final guarded = await AsyncValue.guard(() async {
      final repo = ref.read(reportHistoryRepositoryProvider);
      await repo.resendReport(reportId);
    });
    // Riverpod 3: a disposed notifier throws on `state`.
    if (ref.mounted) state = guarded;
  }
}
