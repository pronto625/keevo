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
      // Si l'API échoue (pas d'onboarding terminé, etc.), retourner null
      return null;
    }
  }
}