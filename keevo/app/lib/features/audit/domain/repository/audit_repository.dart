import '../model/audit_entry_dto.dart';

/// AuditRepository — Strategy port for fetching paginated audit history.
///
/// [page] is zero-based. [size] controls entries per page.
/// [entityType] and [entityId] are optional filters.
abstract interface class AuditRepository {
  /// Returns a paginated slice of audit entries, sorted by occurredAt DESC.
  ///
  /// [page] — zero-based page index.
  /// [size] — number of entries per page.
  /// [entityType] — optional filter (e.g. "Product", "User").
  /// [entityId] — optional UUID string filter. Requires [entityType] if provided.
  Future<AuditPageResult> getAuditHistoryPage({
    required int page,
    required int size,
    String? entityType,
    String? entityId,
  });
}
