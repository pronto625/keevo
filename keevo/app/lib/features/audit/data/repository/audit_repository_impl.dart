import 'dart:developer' as dev;

import '../../../../core/sync/connectivity_service.dart';
import '../../data/datasource/local_audit_datasource.dart';
import '../../data/datasource/remote_audit_datasource.dart';
import '../../domain/model/audit_entry_dto.dart';
import '../../domain/repository/audit_repository.dart';

/// AuditRepositoryImpl — online/offline-aware [AuditRepository].
///
/// Online: fetches from remote API (backend paginated endpoint).
/// Offline: reads from local Drift DB (populated by pull sync).
class AuditRepositoryImpl implements AuditRepository {
  final RemoteAuditDataSource _remote;
  final LocalAuditDataSource _local;
  final ConnectivityService _connectivity;

  AuditRepositoryImpl(this._remote, this._local, this._connectivity);

  @override
  Future<AuditPageResult> getAuditHistoryPage({
    required int page,
    required int size,
    String? entityType,
    String? entityId,
  }) async {
    if (await _connectivity.isOnline()) {
      try {
        return await _remote.getAuditHistoryPage(
          page: page,
          size: size,
          entityType: entityType,
          entityId: entityId,
        );
      } catch (e) {
        dev.log('Remote audit fetch failed, falling back to local: $e',
            name: 'AuditRepo');
      }
    }
    // Offline or remote failed — read from local Drift DB
    return _local.getAuditHistoryPage(
      page: page,
      size: size,
      entityType: entityType,
      entityId: entityId,
    );
  }
}
