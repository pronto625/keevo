import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/sync_event_logger.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo/features/stores/presentation/provider/active_store_provider.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _MockSyncService extends Mock implements SyncService {}

class _MockEventLogger extends Mock implements SyncEventLogger {}

/// v1s-16-12: a rolled-back offline store creation clears the active-store
/// pref during push. The in-memory active store must follow even when the
/// pull after the push fails (the full provider refresh is then skipped).
void main() {
  test('a rolled-back rejection refreshes the active store even if pull fails',
      () async {
    SharedPreferences.setMockInitialValues({'active_store_id': 'store-x'});
    final prefs = await SharedPreferences.getInstance();
    final sync = _MockSyncService();
    final logger = _MockEventLogger();
    when(() => logger.logPushSuccess(any())).thenAnswer((_) async {});
    when(() => logger.logPushFailed(any())).thenAnswer((_) async {});
    when(() => logger.logPullSuccess(any())).thenAnswer((_) async {});
    when(() => logger.logPullFailed(any())).thenAnswer((_) async {});
    when(() => sync.push()).thenAnswer((_) async {
      await prefs.remove('active_store_id');
      return [
        {
          'operationId': 'op-1',
          'status': 'REJECTED',
          'reason': 'PLAN_LIMIT_EXCEEDED',
          'operation': 'CREATE_STORE',
          'entityId': 'store-x',
          'rolledBack': true,
        }
      ];
    });
    when(() => sync.pull()).thenThrow(Exception('offline'));

    final container = ProviderContainer(overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      syncServiceProvider.overrideWithValue(sync),
      syncEventLoggerProvider.overrideWithValue(logger),
      connectivityStreamProvider.overrideWith(
        (ref) => Stream.value([ConnectivityResult.wifi]),
      ),
    ]);
    addTearDown(container.dispose);

    expect(container.read(activeStoreIdProvider), 'store-x');

    await container.read(syncTriggerNotifierProvider.notifier).triggerSync();

    expect(container.read(activeStoreIdProvider), isNull);
  });
}
