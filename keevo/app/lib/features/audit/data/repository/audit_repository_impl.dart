import 'dart:developer' as dev;

import 'package:dio/dio.dart';

import 'package:keevo_core/sync/connectivity_service.dart';
import '../../data/datasource/local_audit_datasource.dart';
import '../../data/datasource/remote_audit_datasource.dart';
import '../../domain/exception/audit_exception.dart';
import '../../domain/model/audit_entry_dto.dart';
import '../../domain/model/audit_filter.dart';
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

  @override
  Future<AuditSlice> getAuditSlice(
    AuditFilter filter, {
    String? cursor,
    int size = 20,
  }) async {
    // An offline cursor keeps paging the local copy (no mixed sources).
    final localCursor =
        cursor != null && cursor.startsWith(kLocalAuditCursorPrefix);
    if (!localCursor && await _connectivity.isOnline()) {
      try {
        return await _remote.getAuditSlice(filter, cursor: cursor, size: size);
      } catch (e) {
        // A server-cursor page must not be continued from the local copy
        // (mixed sources): the caller's « Réessayer » retries it.
        if (cursor != null || !isConnectivityFailure(e)) rethrow;
        dev.log('Audit list unreachable, reading local copy: $e',
            name: 'AuditRepo');
        return _local.getAuditSlice(filter, size: size);
      }
    }
    return _local.getAuditSlice(filter, cursor: cursor, size: size);
  }

  @override
  Future<AuditEntryDto> getAuditEntry(String id) async {
    Object? remoteError;
    if (await _connectivity.isOnline()) {
      try {
        return await _remote.getById(id);
      } catch (e) {
        remoteError = e;
        dev.log('Audit detail fetch failed, reading local row: $e',
            name: 'AuditRepo');
      }
    }
    final local = await _local.getById(id);
    if (local != null) return local;
    throw remoteError ??
        const AuditException(
          domainCode: 'AUDIT_ENTRY_NOT_FOUND',
          message: 'Action introuvable sur cet appareil',
        );
  }

  /// True for a failure that is about reaching the server (no HTTP answer),
  /// as opposed to a server error that must be shown to the user.
  static bool isConnectivityFailure(Object e) {
    if (e is AuditException) return e.statusCode == null;
    if (e is DioException) return e.response == null;
    return false;
  }
}
