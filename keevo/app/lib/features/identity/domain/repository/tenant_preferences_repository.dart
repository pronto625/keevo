import '../model/tenant_preferences_model.dart';

/// TenantPreferencesRepository — Repository pour les préférences du tenant.
abstract class TenantPreferencesRepository {
  /// Récupère les préférences du tenant actuel depuis l'API.
  Future<TenantPreferencesModel?> getCurrentTenantPreferences();

  /// Met à jour les préférences de rapport & WhatsApp (OWNER uniquement).
  Future<TenantPreferencesModel> updateReportPreferences(
      Map<String, dynamic> payload);

  /// Envoie un rapport test immédiatement (OWNER uniquement).
  Future<bool> sendTestReport();

  /// Déclenche immédiatement la génération du rapport hebdomadaire et retourne
  /// l'ID du rapport créé, ou null si aucun rapport n'a pu être généré.
  Future<String?> triggerWeeklyPreview();
}