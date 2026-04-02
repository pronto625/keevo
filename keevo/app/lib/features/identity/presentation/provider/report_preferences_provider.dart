import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/model/tenant_preferences_model.dart';
import 'tenant_preferences_provider.dart';

/// State for report preferences editing.
class ReportPreferencesState {
  final TenantPreferencesModel? preferences;
  final bool isLoading;
  final bool isSaving;
  final bool testSending;
  final bool weeklyPreviewing;
  final String? error;
  final String? successMessage;

  const ReportPreferencesState({
    this.preferences,
    this.isLoading = false,
    this.isSaving = false,
    this.testSending = false,
    this.weeklyPreviewing = false,
    this.error,
    this.successMessage,
  });

  ReportPreferencesState copyWith({
    TenantPreferencesModel? preferences,
    bool? isLoading,
    bool? isSaving,
    bool? testSending,
    bool? weeklyPreviewing,
    String? error,
    String? successMessage,
  }) {
    return ReportPreferencesState(
      preferences: preferences ?? this.preferences,
      isLoading: isLoading ?? this.isLoading,
      isSaving: isSaving ?? this.isSaving,
      testSending: testSending ?? this.testSending,
      weeklyPreviewing: weeklyPreviewing ?? this.weeklyPreviewing,
      error: error,
      successMessage: successMessage,
    );
  }
}

/// Notifier for report preferences page.
class ReportPreferencesNotifier extends StateNotifier<ReportPreferencesState> {
  final Ref _ref;

  ReportPreferencesNotifier(this._ref) : super(const ReportPreferencesState());

  Future<void> load() async {
    state = state.copyWith(isLoading: true);
    try {
      final repo = _ref.read(tenantPreferencesRepositoryProvider);
      final prefs = await repo.getCurrentTenantPreferences();
      state = state.copyWith(
        isLoading: false,
        preferences: prefs,
      );
    } catch (e) {
      state = state.copyWith(isLoading: false, error: e.toString());
    }
  }

  /// Update a single field locally (for immediate UI feedback).
  void updateField(TenantPreferencesModel Function(TenantPreferencesModel) updater) {
    final current = state.preferences;
    if (current == null) return;
    state = state.copyWith(preferences: updater(current));
  }

  /// Persist current preferences to the backend.
  Future<void> save() async {
    final prefs = state.preferences;
    if (prefs == null) return;
    state = state.copyWith(isSaving: true);
    try {
      final repo = _ref.read(tenantPreferencesRepositoryProvider);
      final updated = await repo.updateReportPreferences({
        'eodReportEnabled': prefs.eodReportEnabled,
        'eodReportChannel': prefs.eodReportChannel,
        'eodReportTime': prefs.eodReportTime,
        'weeklyReportEnabled': prefs.weeklyReportEnabled,
        'weeklyReportDay': prefs.weeklyReportDay,
        'weeklyReportTime': prefs.weeklyReportTime,
        'weeklyReportChannel': prefs.weeklyReportChannel,
        'inventoryReportEnabled': prefs.inventoryReportEnabled,
        'inventoryReportChannel': prefs.inventoryReportChannel,
        'stockAlertChannel': prefs.stockAlertChannel,
      });
      state = state.copyWith(
        isSaving: false,
        preferences: updated,
        successMessage: 'Préférences enregistrées',
      );
      // Invalidate the main preferences cache
      _ref.invalidate(tenantPreferencesProvider);
    } catch (e) {
      state = state.copyWith(isSaving: false, error: e.toString());
    }
  }

  /// Send a test report immediately.
  Future<void> sendTestReport() async {
    state = state.copyWith(testSending: true);
    try {
      final repo = _ref.read(tenantPreferencesRepositoryProvider);
      final sent = await repo.sendTestReport();
      state = state.copyWith(
        testSending: false,
        successMessage: sent
            ? 'Rapport test envoyé sur WhatsApp ✅'
            : 'Échec de l\'envoi WhatsApp. Vérifiez votre numéro.',
      );
    } catch (e) {
      state = state.copyWith(testSending: false, error: e.toString());
    }
  }

  /// Génère immédiatement le rapport hebdomadaire et retourne son ID pour navigation.
  Future<String?> previewWeeklyReport() async {
    state = state.copyWith(weeklyPreviewing: true);
    try {
      final repo = _ref.read(tenantPreferencesRepositoryProvider);
      final reportId = await repo.triggerWeeklyPreview();
      state = state.copyWith(
        weeklyPreviewing: false,
        error: reportId == null ? 'Impossible de générer le rapport' : null,
      );
      return reportId;
    } catch (e) {
      state = state.copyWith(weeklyPreviewing: false, error: e.toString());
      return null;
    }
  }

  void clearMessages() {
    state = state.copyWith(error: null, successMessage: null);
  }
}

final reportPreferencesProvider =
    StateNotifierProvider<ReportPreferencesNotifier, ReportPreferencesState>(
        (ref) => ReportPreferencesNotifier(ref));
