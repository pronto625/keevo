import '../../data/datasource/remote_audit_datasource.dart';
import '../../domain/model/audit_entry_dto.dart';
import '../../domain/repository/audit_repository.dart';

/// AuditRepositoryImpl — concrete Strategy implementation of [AuditRepository].
///
/// Delegates HTTP fetching to [RemoteAuditDataSource] and maps the result
/// directly — no additional transformation needed (DTOs match the domain model).
class AuditRepositoryImpl implements AuditRepository {
  final RemoteAuditDataSource _dataSource;

  AuditRepositoryImpl(this._dataSource);

  @override
  Future<List<AuditEntryDto>> getAuditHistory({
    String? entityType,
    String? entityId,
  }) {
    return _dataSource.getAuditHistory(
      entityType: entityType,
      entityId: entityId,
    );
  }
}
