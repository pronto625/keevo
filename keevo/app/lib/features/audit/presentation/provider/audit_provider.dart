import 'package:dio/dio.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/network/auth_interceptor.dart';
import '../../data/datasource/remote_audit_datasource.dart';
import '../../data/repository/audit_repository_impl.dart';
import '../../domain/model/audit_entry_dto.dart';
import '../../domain/repository/audit_repository.dart';

part 'audit_provider.g.dart';

// ── Infrastructure providers ──────────────────────────────────────────────────

/// Base API URL — shared constant (same as auth_provider.dart).
const _apiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'http://10.0.3.2:8080',
);

/// Dio instance for audit calls — reuses base URL and auth interceptor pattern.
final _auditDioProvider = Provider<Dio>((ref) {
  final dio = Dio(BaseOptions(
    baseUrl: _apiBaseUrl,
    connectTimeout: const Duration(seconds: 10),
    receiveTimeout: const Duration(seconds: 10),
    headers: const {'Content-Type': 'application/json'},
  ));
  ref.onDispose(dio.close);
  return dio;
});

/// RemoteAuditDataSource provider.
final remoteAuditDataSourceProvider = Provider<RemoteAuditDataSource>((ref) {
  return RemoteAuditDataSource(dio: ref.watch(_auditDioProvider));
});

/// AuditRepository provider — Strategy pattern (swappable for tests).
final auditRepositoryProvider = Provider<AuditRepository>((ref) {
  return AuditRepositoryImpl(ref.watch(remoteAuditDataSourceProvider));
});

// ── Feature provider ──────────────────────────────────────────────────────────

/// Fetches audit history for the current tenant.
///
/// All params are optional — null = no filter (returns full tenant log).
/// - Both present → filtered by entityType AND entityId
/// - Only entityType → filtered by entityType only
/// - Neither → full tenant log
///
/// AC6: consumed by [StockHistoryWidget].
@riverpod
Future<List<AuditEntryDto>> auditHistory(
  AuditHistoryRef ref, {
  String? entityType,
  String? entityId,
}) async {
  final repo = ref.watch(auditRepositoryProvider);
  return repo.getAuditHistory(entityType: entityType, entityId: entityId);
}
