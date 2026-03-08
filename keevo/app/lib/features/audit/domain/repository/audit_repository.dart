import '../model/audit_entry_dto.dart';

/// AuditRepository — Strategy port for fetching audit history.
///
/// All parameters are optional — null means "no filter".
/// - Both present → filtered by entityType AND entityId
/// - Only entityType → filtered by entityType only
/// - Neither → full tenant log (safe: scoped by JWT + schema routing)
///
/// AC6: consumed by AuditHistoryProvider.
abstract interface class AuditRepository {
  /// Returns a list of audit entries, sorted by occurredAt DESC.
  ///
  /// [entityType] — optional filter (e.g. "Product", "User").
  /// [entityId] — optional UUID string filter. Requires [entityType] if provided.
  Future<List<AuditEntryDto>> getAuditHistory({
    String? entityType,
    String? entityId,
  });
}
