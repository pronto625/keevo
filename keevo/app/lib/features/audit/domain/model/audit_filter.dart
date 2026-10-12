import 'audit_entry_dto.dart';

/// Sentinel used by [AuditFilter.copyWith] to tell "not passed" from `null`.
const Object _unset = Object();

/// AuditFilter — immutable filters of the « Historique des actions » screen
/// (v1s-18-7). Every field is optional; [from]/[to] are inclusive instants.
class AuditFilter {
  final DateTime? from;
  final DateTime? to;
  final String? storeId;
  final String? userId;
  final String? action;
  final String? entityType;

  /// Object label search text (`q` on the API).
  final String? query;

  const AuditFilter({
    this.from,
    this.to,
    this.storeId,
    this.userId,
    this.action,
    this.entityType,
    this.query,
  });

  static const empty = AuditFilter();

  /// Query text, trimmed, `null` when blank.
  String? get normalizedQuery {
    final q = query?.trim();
    return q == null || q.isEmpty ? null : q;
  }

  bool get isEmpty =>
      from == null &&
      to == null &&
      storeId == null &&
      userId == null &&
      action == null &&
      entityType == null &&
      normalizedQuery == null;

  /// Pass `null` explicitly to clear a field; omitted fields are kept.
  AuditFilter copyWith({
    Object? from = _unset,
    Object? to = _unset,
    Object? storeId = _unset,
    Object? userId = _unset,
    Object? action = _unset,
    Object? entityType = _unset,
    Object? query = _unset,
  }) =>
      AuditFilter(
        from: identical(from, _unset) ? this.from : from as DateTime?,
        to: identical(to, _unset) ? this.to : to as DateTime?,
        storeId: identical(storeId, _unset) ? this.storeId : storeId as String?,
        userId: identical(userId, _unset) ? this.userId : userId as String?,
        action: identical(action, _unset) ? this.action : action as String?,
        entityType: identical(entityType, _unset)
            ? this.entityType
            : entityType as String?,
        query: identical(query, _unset) ? this.query : query as String?,
      );

  /// Same filters without any period.
  AuditFilter clearPeriod() => copyWith(from: null, to: null);

  @override
  bool operator ==(Object other) =>
      other is AuditFilter &&
      other.from == from &&
      other.to == to &&
      other.storeId == storeId &&
      other.userId == userId &&
      other.action == action &&
      other.entityType == entityType &&
      other.normalizedQuery == normalizedQuery;

  @override
  int get hashCode => Object.hash(
      from, to, storeId, userId, action, entityType, normalizedQuery);

  @override
  String toString() => 'AuditFilter(from: $from, to: $to, storeId: $storeId, '
      'userId: $userId, action: $action, entityType: $entityType, '
      'query: $normalizedQuery)';
}

/// AuditSlice — one page of the action history.
///
/// [nextCursor] is the server keyset cursor online, `local:<offset>` offline,
/// `null` when there is nothing more. [isOffline] is true when the slice comes
/// from the synced Drift copy.
class AuditSlice {
  final List<AuditEntryDto> entries;
  final String? nextCursor;
  final bool isOffline;

  const AuditSlice({
    required this.entries,
    this.nextCursor,
    this.isOffline = false,
  });

  bool get hasMore => nextCursor != null;
}
