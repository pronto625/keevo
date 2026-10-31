import 'package:dio/dio.dart';
import 'package:keevo_core/auth/jwt_claims.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../auth/domain/repository/token_storage.dart';
import '../../domain/model/deletion_pending_status.dart';
import '../../domain/repository/account_deletion_repository.dart';
import '../datasource/remote_account_deletion_datasource.dart';

/// AccountDeletionRepositoryImpl — FR91 (Story 14.5, AC7/AC8).
///
/// The server keeps `deletionScheduledAt` but never returns it to the app, and
/// the JWT claim `tenantStatus` is frozen at token issuance (same staleness
/// as the SUSPENDED guard). So the pending state is derived from two sources:
///
/// 1. The date stored locally when THIS device made the request
///    (now + 30 days — the same rule the server applies), keyed by tenant so
///    another account signing in on the device never sees it.
/// 2. Otherwise, the JWT claim `tenantStatus == DELETION_PENDING` (request
///    made elsewhere, visible once the token is refreshed) — date unknown.
///
/// Reads the claim straight from [TokenStorage]: by design independent of
/// `account_status_provider.dart` (trial/suspension, Story 14.9).
class AccountDeletionRepositoryImpl implements AccountDeletionRepository {
  static const gracePeriod = Duration(days: 30);
  static const _prefsPrefix = 'account_deletion_scheduled_at:';

  final RemoteAccountDeletionDataSource _remote;
  final SharedPreferences _prefs;
  final TokenStorage _tokenStorage;
  final DateTime Function() _now;

  AccountDeletionRepositoryImpl({
    required RemoteAccountDeletionDataSource remote,
    required SharedPreferences prefs,
    required TokenStorage tokenStorage,
    DateTime Function()? now,
  })  : _remote = remote,
        _prefs = prefs,
        _tokenStorage = tokenStorage,
        _now = now ?? DateTime.now;

  static String prefsKey(String tenantId) => '$_prefsPrefix$tenantId';

  @override
  Future<DeletionPendingStatus> requestDeletion() async {
    await _remote.requestDeletion();
    final scheduledAt = _now().add(gracePeriod);
    final tenantId = (await _claims())?.tenantId;
    if (tenantId != null) {
      await _prefs.setString(
          prefsKey(tenantId), scheduledAt.toUtc().toIso8601String());
    }
    return DeletionPendingStatus(scheduledAt: scheduledAt);
  }

  @override
  Future<void> cancelDeletion() async {
    try {
      await _remote.cancelDeletion();
    } on DioException catch (e) {
      // Already cancelled (admin, other device): nothing is pending any more,
      // the local trace is stale and the banner must go away.
      if (_domainCode(e) != 'DELETION_NOT_PENDING') rethrow;
    }
    await _clearLocal();
  }

  @override
  Future<DeletionPendingStatus?> currentStatus() async {
    final claims = await _claims();
    final tenantId = claims?.tenantId;
    if (tenantId != null) {
      final stored = _prefs.getString(prefsKey(tenantId));
      final scheduledAt = stored == null ? null : DateTime.tryParse(stored);
      if (scheduledAt != null) {
        if (scheduledAt.isAfter(_now())) {
          return DeletionPendingStatus(scheduledAt: scheduledAt.toLocal());
        }
        // Grace period over: the server has purged (or will), the local
        // trace is meaningless.
        await _prefs.remove(prefsKey(tenantId));
      }
    }
    if (claims?.tenantStatus == 'DELETION_PENDING') {
      return const DeletionPendingStatus();
    }
    return null;
  }

  Future<JwtClaims?> _claims() async =>
      JwtClaims.tryParse(await _tokenStorage.getToken());

  Future<void> _clearLocal() async {
    final tenantId = (await _claims())?.tenantId;
    if (tenantId != null) await _prefs.remove(prefsKey(tenantId));
  }

  static String? _domainCode(DioException e) {
    final body = e.response?.data;
    return body is Map ? body['domainCode'] as String? : null;
  }
}
