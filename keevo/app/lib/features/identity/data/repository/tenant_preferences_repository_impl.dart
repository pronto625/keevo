import '../../../../core/services/api_service.dart';
import '../../domain/model/tenant_preferences_model.dart';
import '../../domain/repository/tenant_preferences_repository.dart';

/// TenantPreferencesRepositoryImpl — Implémentation du repository.
class TenantPreferencesRepositoryImpl implements TenantPreferencesRepository {
  final ApiService _apiService;

  const TenantPreferencesRepositoryImpl(this._apiService);

  @override
  Future<TenantPreferencesModel?> getCurrentTenantPreferences() async {
    try {
      final response = await _apiService.get('/api/v1/tenant/preferences');
      final data = response['data'] as Map<String, dynamic>;
      return TenantPreferencesModel.fromJson(data);
    } catch (e) {
      return null;
    }
  }

  @override
  Future<TenantPreferencesModel> updateReportPreferences(
      Map<String, dynamic> payload) async {
    final response = await _apiService.put(
        '/api/v1/tenant/report-preferences', payload);
    final data = response['data'] as Map<String, dynamic>;
    return TenantPreferencesModel.fromJson(data);
  }

  @override
  Future<bool> sendTestReport() async {
    try {
      final response =
          await _apiService.post('/api/v1/tenant/report-test', data: {});
      final data = response['data'] as Map<String, dynamic>? ?? {};
      return data['testSent'] as bool? ?? false;
    } catch (_) {
      return false;
    }
  }

  @override
  Future<String?> triggerWeeklyPreview() async {
    try {
      await _apiService.post('/api/v1/reports/trigger-weekly', data: {});
      final listResp =
          await _apiService.get('/api/v1/reports?type=WEEKLY&size=1&page=0');
      final data = listResp['data'] as Map<String, dynamic>? ?? {};
      final items = data['items'] as List<dynamic>? ?? [];
      if (items.isEmpty) return null;
      return (items.first as Map<String, dynamic>)['id'] as String?;
    } catch (_) {
      return null;
    }
  }
}