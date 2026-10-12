import '../model/audit_entry_dto.dart';
import '../model/audit_filter.dart';

/// AuditRepository — Strategy port for reading the audit log.
abstract interface class AuditRepository {
  /// Returns a paginated slice of audit entries, sorted by occurredAt DESC.
  ///
  /// Used by the « Mouvements de stock » screen ([StockHistoryWidget]).
  /// [page] — zero-based page index. [size] — entries per page.
  /// [entityType] — optional filter (e.g. "Product", "StockMovement").
  /// [entityId] — optional UUID string filter. Requires [entityType] if provided.
  Future<AuditPageResult> getAuditHistoryPage({
    required int page,
    required int size,
    String? entityType,
    String? entityId,
  });

  /// v1s-18-7 — one slice of the « Historique des actions » list, newest first.
  ///
  /// Online: `GET /api/v1/audit` with the filters and the server [cursor].
  /// Offline or connection failure: the synced Drift copy, with
  /// [AuditSlice.isOffline] set. Server errors (4xx/5xx) are rethrown.
  Future<AuditSlice> getAuditSlice(
    AuditFilter filter, {
    String? cursor,
    int size = 20,
  });

  /// v1s-18-7 — full detail of one entry: remote first, local row on failure.
  /// Throws when neither source knows the entry.
  Future<AuditEntryDto> getAuditEntry(String id);
}
