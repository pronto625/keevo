import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
import '../../../../features/auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/remote_audit_datasource.dart';
import '../../data/repository/audit_repository_impl.dart';
import '../../domain/model/audit_entry_dto.dart';
import '../../domain/repository/audit_repository.dart';

part 'audit_provider.g.dart';

const _kAuditPageSize = 20;

// ── Infrastructure providers ──────────────────────────────────────────────────

/// RemoteAuditDataSource provider.
/// Uses the shared [dioProvider] (which includes AuthInterceptor) so every
/// audit request carries the Bearer JWT token automatically.
final remoteAuditDataSourceProvider = Provider<RemoteAuditDataSource>((ref) {
  return RemoteAuditDataSource(dio: ref.watch(dioProvider));
});

/// AuditRepository provider — Strategy pattern (swappable for tests).
final auditRepositoryProvider = Provider<AuditRepository>((ref) {
  return AuditRepositoryImpl(ref.watch(remoteAuditDataSourceProvider));
});

// ── Feature provider ──────────────────────────────────────────────────────────

/// Paginated audit history notifier.
///
/// - [build()] loads page 0 on first watch or after [ref.invalidate()].
/// - [loadMore()] appends the next page to the current list.
/// - [hasMore] indicates whether more pages are available.
/// - [isLoadingMore] is true while a [loadMore()] call is in flight.
///
/// AC6: consumed by [StockHistoryWidget].
@riverpod
class AuditHistoryNotifier extends _$AuditHistoryNotifier {
  int _nextPage = 0;
  bool _hasMore = true;
  bool _isLoadingMore = false;

  // Stored so loadMore() can reuse them without depending on generated mixin fields.
  String? _entityType;
  String? _entityId;

  @override
  Future<List<AuditEntryDto>> build({
    String? entityType,
    String? entityId,
  }) async {
    _entityType = entityType;
    _entityId = entityId;
    _nextPage = 0;
    _hasMore = true;
    _isLoadingMore = false;

    final result = await ref.read(auditRepositoryProvider).getAuditHistoryPage(
          page: 0,
          size: _kAuditPageSize,
          entityType: entityType,
          entityId: entityId,
        );
    _hasMore = result.hasMore;
    _nextPage = 1;
    return result.entries;
  }

  bool get hasMore => _hasMore;
  bool get isLoadingMore => _isLoadingMore;

  /// Appends the next page to the current list.
  /// No-op if already loading or no more pages available.
  Future<void> loadMore() async {
    if (!_hasMore || _isLoadingMore) return;
    final current = state.valueOrNull;
    if (current == null) return;

    _isLoadingMore = true;
    try {
      final result = await ref.read(auditRepositoryProvider).getAuditHistoryPage(
            page: _nextPage,
            size: _kAuditPageSize,
            entityType: _entityType,
            entityId: _entityId,
          );
      _hasMore = result.hasMore;
      _nextPage++;
      state = AsyncData([...current, ...result.entries]);
    } finally {
      _isLoadingMore = false;
    }
  }
}
