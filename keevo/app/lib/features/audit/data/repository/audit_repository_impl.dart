import '../../data/datasource/remote_audit_datasource.dart';
import '../../domain/model/audit_entry_dto.dart';
import '../../domain/repository/audit_repository.dart';

/// AuditRepositoryImpl — concrete Strategy implementation of [AuditRepository].
class AuditRepositoryImpl implements AuditRepository {
  final RemoteAuditDataSource _dataSource;

  AuditRepositoryImpl(this._dataSource);

  @override
  Future<AuditPageResult> getAuditHistoryPage({
    required int page,
    required int size,
    String? entityType,
    String? entityId,
  }) {
    return _dataSource.getAuditHistoryPage(
      page: page,
      size: size,
      entityType: entityType,
      entityId: entityId,
    );
  }
}
