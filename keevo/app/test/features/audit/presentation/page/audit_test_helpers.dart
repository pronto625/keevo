import 'package:keevo/features/audit/domain/model/audit_entry_dto.dart';
import 'package:keevo/features/audit/domain/model/audit_filter.dart';
import 'package:keevo/features/audit/domain/repository/audit_repository.dart';

/// Scriptable [AuditRepository] for the v1s-18-7 page tests.
class FakeAuditRepository implements AuditRepository {
  Future<AuditSlice> Function(AuditFilter filter, String? cursor)? onSlice;
  Future<AuditEntryDto> Function(String id)? onEntry;

  final sliceCalls = <(AuditFilter, String?)>[];
  final entryCalls = <String>[];

  @override
  Future<AuditSlice> getAuditSlice(AuditFilter filter,
      {String? cursor, int size = 20}) {
    sliceCalls.add((filter, cursor));
    return onSlice?.call(filter, cursor) ??
        Future.value(const AuditSlice(entries: []));
  }

  @override
  Future<AuditEntryDto> getAuditEntry(String id) {
    entryCalls.add(id);
    return onEntry?.call(id) ?? Future.error(StateError('no detail'));
  }

  @override
  Future<AuditPageResult> getAuditHistoryPage({
    required int page,
    required int size,
    String? entityType,
    String? entityId,
  }) async =>
      const AuditPageResult(entries: [], hasMore: false);
}

AuditEntryDto auditEntry(
  String id, {
  String action = 'PRODUCT_CREATED',
  String entityType = 'Product',
  String entityId = 'p-1',
  String userId = 'user-awa',
  String? actorName = 'Awa',
  String? entityLabel = 'Pagne wax',
  String? storeName = 'Boutique Centre',
  String? valueBefore,
  String? valueAfter,
  String? metadata,
  String? source = 'ONLINE',
  String? deviceId,
  DateTime? at,
}) =>
    AuditEntryDto(
      id: id,
      entityType: entityType,
      entityId: entityId,
      action: action,
      valueBefore: valueBefore,
      valueAfter: valueAfter,
      userId: userId,
      occurredAt: at ?? DateTime.now(),
      storeName: storeName,
      entityLabel: entityLabel,
      actorName: actorName,
      metadata: metadata,
      source: source,
      deviceId: deviceId,
    );
