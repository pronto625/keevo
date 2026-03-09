import '../model/tenant_preferences_model.dart';

/// TenantPreferencesRepository — Repository pour les préférences du tenant.
abstract class TenantPreferencesRepository {
  /// Récupère les préférences du tenant actuel depuis l'API.  
  Future<TenantPreferencesModel?> getCurrentTenantPreferences();
}