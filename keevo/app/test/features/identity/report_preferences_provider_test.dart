import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/identity/domain/model/tenant_preferences_model.dart';
import 'package:keevo/features/identity/domain/repository/tenant_preferences_repository.dart';
import 'package:keevo/features/identity/presentation/provider/report_preferences_provider.dart';
import 'package:keevo/features/identity/presentation/provider/tenant_preferences_provider.dart';
import 'package:keevo_core/network/module_entitlement_exception.dart';
import 'package:keevo_core/services/api_service.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';

// ── Mocks ────────────────────────────────────────────────────────────────────

class MockTenantPreferencesRepository extends Mock
    implements TenantPreferencesRepository {}

// ── Helpers ──────────────────────────────────────────────────────────────────

TenantPreferencesModel _testPrefs() => TenantPreferencesModel(
      sectorType: null,
      eodReportTime: '20:00:00',
      stockAlertEnabled: true,
      createdAt: DateTime(2025),
      eodReportEnabled: false,
      eodReportChannel: 'WHATSAPP',
      weeklyReportEnabled: false,
      weeklyReportDay: 5,
      weeklyReportTime: '08:00:00',
      weeklyReportChannel: 'WHATSAPP',
      inventoryReportEnabled: false,
      inventoryReportChannel: 'WHATSAPP',
      stockAlertChannel: 'PUSH',
    );

void main() {
  late MockTenantPreferencesRepository mockRepo;

  setUp(() {
    mockRepo = MockTenantPreferencesRepository();
    registerFallbackValue(<String, dynamic>{});
  });

  group('ReportPreferencesNotifier', () {
    test('load() — populates preferences on success', () async {
      when(() => mockRepo.getCurrentTenantPreferences())
          .thenAnswer((_) async => _testPrefs());

      final container = ProviderContainer(retry: noProviderRetry, overrides: [
        tenantPreferencesRepositoryProvider.overrideWithValue(mockRepo),
      ]);
      addTearDown(container.dispose);

      await container.read(reportPreferencesProvider.notifier).load();

      final state = container.read(reportPreferencesProvider);
      expect(state.isLoading, isFalse);
      expect(state.preferences, isNotNull);
      expect(state.preferences!.eodReportEnabled, isFalse);
    });

    test('load() — sets error when repository throws', () async {
      when(() => mockRepo.getCurrentTenantPreferences())
          .thenThrow(Exception('network error'));

      final container = ProviderContainer(retry: noProviderRetry, overrides: [
        tenantPreferencesRepositoryProvider.overrideWithValue(mockRepo),
      ]);
      addTearDown(container.dispose);

      await container.read(reportPreferencesProvider.notifier).load();

      final state = container.read(reportPreferencesProvider);
      expect(state.error, isNotNull);
      expect(state.preferences, isNull);
    });

    test('updateField() — updates a single field locally', () async {
      when(() => mockRepo.getCurrentTenantPreferences())
          .thenAnswer((_) async => _testPrefs());

      final container = ProviderContainer(retry: noProviderRetry, overrides: [
        tenantPreferencesRepositoryProvider.overrideWithValue(mockRepo),
      ]);
      addTearDown(container.dispose);

      await container.read(reportPreferencesProvider.notifier).load();

      container.read(reportPreferencesProvider.notifier).updateField(
            (p) => p.copyWith(eodReportEnabled: true),
          );

      final state = container.read(reportPreferencesProvider);
      expect(state.preferences!.eodReportEnabled, isTrue);
    });

    test('save() — calls updateReportPreferences with correct payload',
        () async {
      when(() => mockRepo.getCurrentTenantPreferences())
          .thenAnswer((_) async => _testPrefs());
      final updated = _testPrefs().copyWith(eodReportEnabled: true);
      when(() => mockRepo.updateReportPreferences(any()))
          .thenAnswer((_) async => updated);

      final container = ProviderContainer(retry: noProviderRetry, overrides: [
        tenantPreferencesRepositoryProvider.overrideWithValue(mockRepo),
      ]);
      addTearDown(container.dispose);

      await container.read(reportPreferencesProvider.notifier).load();
      container.read(reportPreferencesProvider.notifier).updateField(
            (p) => p.copyWith(eodReportEnabled: true),
          );
      await container.read(reportPreferencesProvider.notifier).save();

      verify(() => mockRepo.updateReportPreferences(any())).called(1);

      final state = container.read(reportPreferencesProvider);
      expect(state.isSaving, isFalse);
      expect(state.successMessage, isNotNull);
    });

    test('sendTestReport() — returns success message when testSent=true',
        () async {
      when(() => mockRepo.getCurrentTenantPreferences())
          .thenAnswer((_) async => _testPrefs());
      when(() => mockRepo.sendTestReport()).thenAnswer((_) async => true);

      final container = ProviderContainer(retry: noProviderRetry, overrides: [
        tenantPreferencesRepositoryProvider.overrideWithValue(mockRepo),
      ]);
      addTearDown(container.dispose);
      await container.read(reportPreferencesProvider.notifier).load();
      await container.read(reportPreferencesProvider.notifier).sendTestReport();

      final state = container.read(reportPreferencesProvider);
      expect(state.testSending, isFalse);
      expect(state.successMessage, contains('envoyé'));
    });

    test('sendTestReport() — returns warning message when testSent=false',
        () async {
      when(() => mockRepo.getCurrentTenantPreferences())
          .thenAnswer((_) async => _testPrefs());
      when(() => mockRepo.sendTestReport()).thenAnswer((_) async => false);

      final container = ProviderContainer(retry: noProviderRetry, overrides: [
        tenantPreferencesRepositoryProvider.overrideWithValue(mockRepo),
      ]);
      addTearDown(container.dispose);
      await container.read(reportPreferencesProvider.notifier).load();
      await container.read(reportPreferencesProvider.notifier).sendTestReport();

      final state = container.read(reportPreferencesProvider);
      expect(state.successMessage, contains('Échec'));
    });

    test('clearMessages() — resets error and successMessage', () async {
      when(() => mockRepo.getCurrentTenantPreferences())
          .thenThrow(Exception('error'));

      final container = ProviderContainer(retry: noProviderRetry, overrides: [
        tenantPreferencesRepositoryProvider.overrideWithValue(mockRepo),
      ]);
      addTearDown(container.dispose);
      await container.read(reportPreferencesProvider.notifier).load();

      container.read(reportPreferencesProvider.notifier).clearMessages();

      final state = container.read(reportPreferencesProvider);
      expect(state.error, isNull);
    });

    // 13.10b — a Free tenant saving report preferences (report-config) or
    // asking a weekly preview (advanced-reports) reads the French refusal.
    test('save() — module refusal → French message, no code', () async {
      when(() => mockRepo.updateReportPreferences(any())).thenThrow(
          ApiException('Ce module…', 403, domainCode: 'MODULE_NOT_ENTITLED'));
      final container = ProviderContainer(retry: noProviderRetry, overrides: [
        tenantPreferencesRepositoryProvider.overrideWithValue(mockRepo),
      ]);
      addTearDown(container.dispose);
      when(() => mockRepo.getCurrentTenantPreferences())
          .thenAnswer((_) async => _testPrefs());
      final notifier = container.read(reportPreferencesProvider.notifier);
      await notifier.load();

      await notifier.save();

      expect(container.read(reportPreferencesProvider).error,
          ModuleEntitlementException.notEntitledMessage);
    });

    test('previewWeeklyReport() — module refusal → French message', () async {
      when(() => mockRepo.triggerWeeklyPreview()).thenThrow(
          const ModuleEntitlementException(ModuleRefusalReason.readOnly));
      final container = ProviderContainer(retry: noProviderRetry, overrides: [
        tenantPreferencesRepositoryProvider.overrideWithValue(mockRepo),
      ]);
      addTearDown(container.dispose);

      final id = await container
          .read(reportPreferencesProvider.notifier)
          .previewWeeklyReport();

      expect(id, isNull);
      expect(container.read(reportPreferencesProvider).error,
          ModuleEntitlementException.readOnlyMessage);
    });
  });
}
