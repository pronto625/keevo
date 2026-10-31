import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/model/tenant_preferences_model.dart';
import 'tenant_preferences_provider.dart';
import 'package:keevo_core/widget/app_error_widget.dart' show appErrorMessage;

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
class ReportPreferencesNotifier extends Notifier<ReportPreferencesState> {
  @override
  ReportPreferencesState build() => const ReportPreferencesState();

  /// Identity-based notification, as before the Riverpod 3 migration (not ==).
  @override
  bool updateShouldNotify(
          ReportPreferencesState previous, ReportPreferencesState next) =>
      !identical(previous, next);

  Future<void> load() async {
    state = state.copyWith(isLoading: true);
    try {
      final repo = ref.read(tenantPreferencesRepositoryProvider);
      final prefs = await repo.getCurrentTenantPreferences();
      // Riverpod 3: a disposed notifier throws on `state` / `ref`.
      if (!ref.mounted) return;
      state = state.copyWith(
        isLoading: false,
        preferences: prefs,
      );
    } catch (e) {
      if (!ref.mounted) return;
      state = state.copyWith(isLoading: false, error: appErrorMessage(e));
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
      final repo = ref.read(tenantPreferencesRepositoryProvider);
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
        'stockAlertEnabled': prefs.stockAlertEnabled,
        'stockAlertChannel': prefs.stockAlertChannel,
        'trendNotificationEnabled': prefs.trendNotificationEnabled,
      });
      if (!ref.mounted) return;
      state = state.copyWith(
        isSaving: false,
        preferences: updated,
        successMessage: 'Préférences enregistrées',
      );
      // Invalidate the main preferences cache
      ref.invalidate(tenantPreferencesProvider);
    } catch (e) {
      if (!ref.mounted) return;
      state = state.copyWith(isSaving: false, error: appErrorMessage(e));
    }
  }

  /// Send a test report immediately.
  Future<void> sendTestReport() async {
    state = state.copyWith(testSending: true);
    try {
      final repo = ref.read(tenantPreferencesRepositoryProvider);
      final sent = await repo.sendTestReport();
      if (!ref.mounted) return;
      state = state.copyWith(
        testSending: false,
        successMessage: sent
            ? 'Rapport test envoyé sur WhatsApp ✅'
            : 'Échec de l\'envoi WhatsApp. Vérifiez votre numéro.',
      );
    } catch (e) {
      if (!ref.mounted) return;
      state = state.copyWith(testSending: false, error: appErrorMessage(e));
    }
  }

  /// Génère immédiatement le rapport hebdomadaire et retourne son ID pour navigation.
  Future<String?> previewWeeklyReport() async {
    state = state.copyWith(weeklyPreviewing: true);
    try {
      final repo = ref.read(tenantPreferencesRepositoryProvider);
      final reportId = await repo.triggerWeeklyPreview();
      if (!ref.mounted) return reportId;
      state = state.copyWith(
        weeklyPreviewing: false,
        error: reportId == null ? 'Impossible de générer le rapport' : null,
      );
      return reportId;
    } catch (e) {
      if (!ref.mounted) return null;
      state = state.copyWith(weeklyPreviewing: false, error: appErrorMessage(e));
      return null;
    }
  }

  void clearMessages() {
    state = state.copyWith(error: null, successMessage: null);
  }
}

final reportPreferencesProvider =
    NotifierProvider<ReportPreferencesNotifier, ReportPreferencesState>(
        ReportPreferencesNotifier.new);
