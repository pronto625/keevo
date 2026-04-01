import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
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
  ReportHistoryRef ref, {
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
  ReportDetailRef ref,
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
    state = await AsyncValue.guard(() async {
      final repo = ref.read(reportHistoryRepositoryProvider);
      await repo.resendReport(reportId);
    });
  }
}
